package dev.pelayo.aves.core.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** El fichero no trae audio que este dispositivo sepa decodificar. */
class UnsupportedAudioException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Aritmetica de mezcla a mono y acumulacion, sin una sola clase de Android para
 * poder probarla en la JVM. Lo que SI necesita Android (extraer y decodificar)
 * vive en [AudioFileDecoder].
 */
object PcmMixer {

    /**
     * Mezcla [frames] cuadros entrelazados de [channels] canales promediando,
     * que es lo que espera el modelo. Promediar y no quedarse con el canal
     * izquierdo: una grabadora estereo con el ave a la derecha la tendria a
     * medio volumen en el canal que se eligiera.
     */
    fun mixToMono(interleaved: ShortArray, frames: Int, channels: Int, into: ShortAccumulator): Int {
        require(channels > 0) { "channels debe ser positivo" }
        var written = 0
        var offset = 0
        for (frame in 0 until frames) {
            var sum = 0
            for (channel in 0 until channels) sum += interleaved[offset++]
            if (!into.add((sum / channels).toShort())) break
            written++
        }
        return written
    }

    /** PCM en coma flotante [-1, 1] a 16 bits, recortando: un 1,2 no debe dar la vuelta. */
    fun floatToPcm16(sample: Float): Short {
        if (sample.isNaN()) return 0
        return (sample * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
    }
}

/**
 * Buffer de muestras que crece hasta un tope.
 *
 * El tope existe porque una grabacion de media hora decodificada entera son
 * cientos de megas: [add] devuelve false cuando se llena y quien decodifica
 * para ahi.
 */
class ShortAccumulator(private val maxSize: Int, initialCapacity: Int = 1 shl 16) {
    private var data = ShortArray(initialCapacity.coerceAtMost(maxSize).coerceAtLeast(1))

    var size: Int = 0
        private set

    val isFull: Boolean get() = size >= maxSize

    fun add(sample: Short): Boolean {
        if (size >= maxSize) return false
        if (size == data.size) data = data.copyOf((data.size * 2).coerceAtMost(maxSize))
        data[size++] = sample
        return true
    }

    /** El array interno, valido hasta [size]. No se copia: puede ser enorme. */
    fun backingArray(): ShortArray = data
}

/**
 * Decodifica un fichero de audio en CUALQUIER formato que sepa el dispositivo.
 *
 * Antes solo se admitia WAV PCM de 16 bits, porque era lo que hacia falta para
 * un test de regresion reproducible. Pero nadie tiene sus grabaciones en WAV:
 * la grabadora de Samsung y la de iOS dan M4A (AAC), la de Xiaomi MP3, las
 * antiguas 3GP/AMR, WhatsApp manda notas de voz en Opus, y quien va en serio
 * graba en FLAC. Todos esos los decodifica el propio Android con
 * `MediaExtractor` + `MediaCodec`, sin una sola libreria mas.
 *
 * El WAV de 16 bits sigue pasando por [WavDecoder]: es Kotlin puro, se prueba
 * en la JVM y no depende del codec de ningun fabricante, que es justo lo que
 * se quiere para los ficheros de referencia.
 *
 * La salida es la misma en los dos caminos: mono, a la frecuencia que pide el
 * modelo, remuestreado con filtro anti-aliasing.
 */
object AudioFileDecoder {

    private const val TAG = "AudioFileDecoder"

    /**
     * Cinco minutos. Una grabacion de campo tipica dura uno o dos; cinco a
     * 48 kHz son ya unos 90 MB entre buffers intermedios, y el analisis de un
     * fichero no es el sitio para quedarse sin memoria. Lo que pase de ahi se
     * ignora y se avisa.
     */
    const val DEFAULT_MAX_DURATION_MILLIS = 5 * 60_000L

    /** Por encima de esto un WAV ni se intenta leer entero a memoria. */
    private const val MAX_WAV_BYTES = 64L * 1024 * 1024

    private const val TIMEOUT_US = 10_000L

    /** Lo que se le ensena al usuario cuando un fichero no se deja abrir. */
    const val SUPPORTED_FORMATS = "WAV, MP3, M4A/AAC, OGG, Opus, FLAC, AMR/3GP"

    suspend fun decode(
        context: Context,
        uri: Uri,
        targetSampleRate: Int,
        scaling: PcmScaling = PcmScaling.NORMALIZED,
        maxDurationMillis: Long = DEFAULT_MAX_DURATION_MILLIS,
    ): DecodedAudio = withContext(Dispatchers.IO) {
        decodeAsWav(context, uri, targetSampleRate, scaling, maxDurationMillis)
            ?: decodeWithPlatform(context, uri, targetSampleRate, scaling, maxDurationMillis)
    }

    /**
     * El camino de Kotlin puro, solo para WAV PCM de 16 bits razonablemente
     * pequenos. Devuelve null (y no lanza) cuando no aplica: un WAV de 24 bits
     * o en coma flotante lo sabe leer el decodificador de la plataforma.
     */
    private fun decodeAsWav(
        context: Context,
        uri: Uri,
        targetSampleRate: Int,
        scaling: PcmScaling,
        maxDurationMillis: Long,
    ): DecodedAudio? {
        val resolver = context.contentResolver
        val length = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)
        if (length > MAX_WAV_BYTES) return null

        val header = ByteArray(12)
        val read = resolver.openInputStream(uri)?.use { it.read(header) } ?: return null
        if (read < 12 || !isRiffWave(header)) return null

        return try {
            val decoded = resolver.openInputStream(uri)!!.use { stream ->
                WavDecoder.decode(stream, targetSampleRate, scaling)
            }
            val maxSamples = (targetSampleRate * maxDurationMillis / 1000L).toInt()
            if (decoded.samples.size > maxSamples) {
                decoded.copy(samples = decoded.samples.copyOf(maxSamples), truncated = true)
            } else {
                decoded
            }
        } catch (e: WavFormatException) {
            Log.i(TAG, "WAV no soportado por el decodificador propio (${e.message}); se prueba el del sistema")
            null
        }
    }

    internal fun isRiffWave(header: ByteArray): Boolean =
        header.size >= 12 &&
            String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(header, 8, 4, Charsets.US_ASCII) == "WAVE"

    private suspend fun decodeWithPlatform(
        context: Context,
        uri: Uri,
        targetSampleRate: Int,
        scaling: PcmScaling,
        maxDurationMillis: Long,
    ): DecodedAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: IOException) {
                throw UnsupportedAudioException("No se pudo abrir el fichero. Formatos admitidos: $SUPPORTED_FORMATS", e)
            } catch (e: RuntimeException) {
                throw UnsupportedAudioException("No se pudo abrir el fichero. Formatos admitidos: $SUPPORTED_FORMATS", e)
            }

            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw UnsupportedAudioException("El fichero no contiene ninguna pista de audio")

            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            extractor.selectTrack(trackIndex)

            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            val created = try {
                MediaCodec.createDecoderByType(mime).also {
                    it.configure(inputFormat, null, null, 0)
                    it.start()
                }
            } catch (e: Exception) {
                throw UnsupportedAudioException(
                    "Este dispositivo no sabe decodificar $mime. Formatos admitidos: $SUPPORTED_FORMATS",
                    e,
                )
            }
            codec = created

            // El tope se fija con la frecuencia de ORIGEN, que es a la que se
            // acumula; si el decodificador la corrige luego, se reajusta.
            var accumulator = ShortAccumulator(maxSamplesFor(sampleRate, maxDurationMillis))
            val bufferInfo = MediaCodec.BufferInfo()
            var interleaved = ShortArray(0)
            var inputDone = false
            var outputDone = false

            while (!outputDone && !accumulator.isFull) {
                currentCoroutineContext().ensureActive()

                if (!inputDone) {
                    val inputIndex = created.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val buffer = checkNotNull(created.getInputBuffer(inputIndex))
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            created.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            created.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = created.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Lo que dice la cabecera del contenedor y lo que entrega
                        // el decodificador no siempre coinciden (HE-AAC declara la
                        // mitad de la frecuencia real). Manda el decodificador.
                        val output = created.outputFormat
                        val newRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = output.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        if (newRate != sampleRate && accumulator.size == 0) {
                            sampleRate = newRate
                            accumulator = ShortAccumulator(maxSamplesFor(sampleRate, maxDurationMillis))
                        }
                    }

                    else -> if (outputIndex >= 0) {
                        val buffer = checkNotNull(created.getOutputBuffer(outputIndex))
                        if (bufferInfo.size > 0) {
                            buffer.position(bufferInfo.offset)
                            buffer.limit(bufferInfo.offset + bufferInfo.size)
                            interleaved = readPcm(buffer, pcmEncoding, interleaved)
                            val samples = pcmSampleCount(bufferInfo.size, pcmEncoding)
                            PcmMixer.mixToMono(interleaved, samples / channels, channels, accumulator)
                        }
                        created.releaseOutputBuffer(outputIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            }

            if (accumulator.size == 0) {
                throw UnsupportedAudioException("El fichero no contiene audio decodificable")
            }

            val truncated = accumulator.isFull && !outputDone
            val resampled = Resampler.resample(
                input = accumulator.backingArray(),
                from = sampleRate,
                to = targetSampleRate,
                length = accumulator.size,
            )
            Log.i(TAG, "Decodificado $mime: $sampleRate Hz, $channels canales, ${accumulator.size} muestras" +
                if (truncated) " (truncado)" else "")

            return DecodedAudio(
                samples = FloatArray(resampled.size) { scaling.apply(resampled[it]) },
                sampleRate = targetSampleRate,
                originalSampleRate = sampleRate,
                originalChannels = channels,
                truncated = truncated,
            )
        } catch (e: CancellationException) {
            // Va antes que IllegalStateException porque ES una: sin esto, salir
            // de la pantalla a mitad se contaba como "fichero danado".
            throw e
        } catch (e: IllegalStateException) {
            // MediaCodec.CodecException: el decodificador se atraganto con el fichero.
            throw UnsupportedAudioException("El fichero esta danado o usa una variante no soportada", e)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun maxSamplesFor(sampleRate: Int, maxDurationMillis: Long): Int =
        (sampleRate.toLong() * maxDurationMillis / 1000L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

    private fun pcmSampleCount(bytes: Int, encoding: Int): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> bytes / 4
        AudioFormat.ENCODING_PCM_8BIT -> bytes
        else -> bytes / 2
    }

    /** Lee el buffer de salida a PCM de 16 bits, venga como venga, reutilizando [reuse] si cabe. */
    private fun readPcm(buffer: ByteBuffer, encoding: Int, reuse: ShortArray): ShortArray {
        val ordered = buffer.order(ByteOrder.nativeOrder())
        val count = pcmSampleCount(ordered.remaining(), encoding)
        val target = if (reuse.size >= count) reuse else ShortArray(count)
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = ordered.asFloatBuffer()
                for (i in 0 until count) target[i] = PcmMixer.floatToPcm16(floats.get(i))
            }

            AudioFormat.ENCODING_PCM_8BIT -> {
                // 8 bits sin signo, centrado en 128.
                for (i in 0 until count) target[i] = (((ordered.get().toInt() and 0xFF) - 128) shl 8).toShort()
            }

            else -> ordered.asShortBuffer().get(target, 0, count)
        }
        return target
    }
}
