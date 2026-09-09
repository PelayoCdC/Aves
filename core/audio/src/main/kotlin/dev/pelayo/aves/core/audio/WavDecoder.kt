package dev.pelayo.aves.core.audio

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Audio ya decodificado, mono y a la frecuencia que pidio el modelo. */
data class DecodedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
    val originalSampleRate: Int,
    val originalChannels: Int,
) {
    val durationMillis: Long get() = samples.size * 1000L / sampleRate

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodedAudio) return false
        return sampleRate == other.sampleRate &&
            originalSampleRate == other.originalSampleRate &&
            originalChannels == other.originalChannels &&
            samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + originalSampleRate
        result = 31 * result + originalChannels
        return result
    }
}

class WavFormatException(message: String) : IllegalArgumentException(message)

/**
 * Decodificador de WAV PCM.
 *
 * Existe por una razon muy concreta: sin una via para meter un fichero conocido
 * en el pipeline, el riesgo #1 (¿normaliza BirdNET internamente?) no se puede
 * cerrar. Con solo microfono no hay forma de comparar contra una referencia.
 *
 * De paso habilita analizar grabaciones ajenas, que es una funcion real.
 *
 * Deliberadamente **solo WAV PCM**. Para MP3/OGG/M4A haria falta
 * MediaExtractor + MediaCodec, que ata el codigo a Android y no se puede probar
 * en la JVM. Como el objetivo inmediato es un test de regresion reproducible,
 * WAV puro es la eleccion correcta; lo otro puede venir despues.
 */
object WavDecoder {

    private const val HEADER_MIN_BYTES = 44
    private const val FORMAT_PCM = 1
    private const val FORMAT_EXTENSIBLE = 0xFFFE

    fun decode(
        input: InputStream,
        targetSampleRate: Int,
        scaling: PcmScaling = PcmScaling.RAW,
    ): DecodedAudio {
        val bytes = input.readBytes()
        return decode(bytes, targetSampleRate, scaling)
    }

    fun decode(
        bytes: ByteArray,
        targetSampleRate: Int,
        scaling: PcmScaling = PcmScaling.RAW,
    ): DecodedAudio {
        if (bytes.size < HEADER_MIN_BYTES) {
            throw WavFormatException("El fichero es demasiado corto para ser un WAV")
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require4CC(buffer, "RIFF")
        buffer.int // tamano total, no hace falta
        require4CC(buffer, "WAVE")

        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataLength = 0

        // Recorrido de chunks. No se asume que 'data' venga justo detras de
        // 'fmt ': muchos ficheros meten LIST/INFO en medio y dar por hecho el
        // orden es el error clasico al parsear WAV.
        while (buffer.remaining() >= 8) {
            val chunkId = read4CC(buffer)
            val chunkSize = buffer.int
            if (chunkSize < 0 || chunkSize > buffer.remaining()) {
                if (chunkId == "data") {
                    // Algunos ficheros truncados declaran de mas: se usa lo que hay.
                    dataOffset = buffer.position()
                    dataLength = buffer.remaining()
                }
                break
            }

            when (chunkId) {
                "fmt " -> {
                    val start = buffer.position()
                    val audioFormat = buffer.short.toInt() and 0xFFFF
                    channels = buffer.short.toInt() and 0xFFFF
                    sampleRate = buffer.int
                    buffer.int // byteRate
                    buffer.short // blockAlign
                    bitsPerSample = buffer.short.toInt() and 0xFFFF

                    if (audioFormat != FORMAT_PCM && audioFormat != FORMAT_EXTENSIBLE) {
                        throw WavFormatException(
                            "Solo se admite WAV PCM sin comprimir (formato $audioFormat). " +
                                "Convierte el fichero con: ffmpeg -i entrada -ac 1 -ar " +
                                "$targetSampleRate -c:a pcm_s16le salida.wav",
                        )
                    }
                    buffer.position(start + chunkSize)
                }

                "data" -> {
                    dataOffset = buffer.position()
                    dataLength = chunkSize
                    buffer.position(buffer.position() + chunkSize)
                }

                else -> buffer.position(buffer.position() + chunkSize)
            }

            // Los chunks se alinean a 2 bytes.
            if (chunkSize % 2 == 1 && buffer.remaining() > 0) buffer.position(buffer.position() + 1)
        }

        if (channels == 0 || sampleRate == 0) throw WavFormatException("Falta el chunk 'fmt '")
        if (dataOffset < 0) throw WavFormatException("Falta el chunk 'data'")
        if (bitsPerSample != 16) {
            throw WavFormatException(
                "Solo se admiten 16 bits por muestra (el fichero tiene $bitsPerSample). " +
                    "Conviertelo con: ffmpeg -i entrada -ac 1 -ar $targetSampleRate " +
                    "-c:a pcm_s16le salida.wav",
            )
        }

        val mono = readMono(bytes, dataOffset, dataLength, channels)
        val resampled = Resampler.resample(mono, sampleRate, targetSampleRate)

        return DecodedAudio(
            samples = FloatArray(resampled.size) { scaling.apply(resampled[it]) },
            sampleRate = targetSampleRate,
            originalSampleRate = sampleRate,
            originalChannels = channels,
        )
    }

    /** Mezcla los canales a mono promediando, que es lo que espera el modelo. */
    private fun readMono(bytes: ByteArray, offset: Int, length: Int, channels: Int): ShortArray {
        val data = ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.LITTLE_ENDIAN)
        val frameCount = length / (2 * channels)
        val mono = ShortArray(frameCount)
        for (frame in 0 until frameCount) {
            var sum = 0
            for (channel in 0 until channels) sum += data.short.toInt()
            mono[frame] = (sum / channels).toShort()
        }
        return mono
    }

    private fun require4CC(buffer: ByteBuffer, expected: String) {
        val actual = read4CC(buffer)
        if (actual != expected) {
            throw WavFormatException("No parece un WAV: se esperaba '$expected' y hay '$actual'")
        }
    }

    private fun read4CC(buffer: ByteBuffer): String {
        val chars = ByteArray(4)
        buffer.get(chars)
        return String(chars, Charsets.US_ASCII)
    }
}

/**
 * Trocea una senal larga en ventanas solapadas del tamano que pide el modelo.
 *
 * Es el mismo esquema que usa BirdNET-Analyzer para ficheros: analizar por
 * ventanas y quedarse con el maximo por especie. La ultima ventana se rellena
 * con ceros en vez de descartarse, porque si no un canto en los ultimos
 * segundos del fichero se perderia.
 */
fun FloatArray.audioWindows(windowSize: Int, hopSize: Int): Sequence<FloatArray> {
    require(windowSize > 0) { "windowSize debe ser positivo" }
    require(hopSize > 0) { "hopSize debe ser positivo" }
    val source = this
    return sequence {
        if (source.isEmpty()) return@sequence
        var start = 0
        while (start < source.size) {
            val window = FloatArray(windowSize)
            val available = minOf(windowSize, source.size - start)
            source.copyInto(window, 0, start, start + available)
            yield(window)
            if (start + windowSize >= source.size) break
            start += hopSize
        }
    }
}
