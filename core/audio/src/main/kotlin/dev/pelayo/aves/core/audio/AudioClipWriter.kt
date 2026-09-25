package dev.pelayo.aves.core.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Conversion de muestras Float a PCM de 16 bits.
 *
 * Vive fuera de [AudioClipWriter] a proposito, y no por gusto de separar: en un
 * test unitario de JVM solo esta el `android.jar` de stubs, donde `MediaCodec` y
 * `MediaMuxer` lanzan `RuntimeException("Stub!")` en cuanto se tocan. La
 * codificacion AAC, por tanto, solo se puede validar en dispositivo o con un
 * test instrumentado.
 *
 * La conversion previa si es aritmetica pura, y es justo donde caen los fallos
 * caros y silenciosos: confundir el escalado deja el recorte inaudible o
 * reventado, y no recortar los extremos produce wrap-around (un +32768 se
 * convierte en -32768), que se oye como un chasquido seco en mitad del canto.
 * Sacandola aqui, todo eso queda bajo test en la JVM.
 */
object Pcm16Converter {

    private const val BYTES_PER_SAMPLE = 2
    private const val MIN_PCM16 = -32768f
    private const val MAX_PCM16 = 32767f

    /**
     * Convierte una muestra al rango [-32768, 32767] respetando [scaling].
     *
     * El `coerceIn` va ANTES del `toInt`: recortar despues no serviria de nada,
     * porque el desbordamiento ya se habria producido al truncar a Short.
     */
    fun toPcm16(sample: Float, scaling: PcmScaling): Short {
        // Un NaN colado desde un filtro o una division por cero envenenaria
        // roundToInt (lanza), asi que se trata como silencio.
        if (sample.isNaN()) return 0
        val scaled = when (scaling) {
            PcmScaling.RAW -> sample
            PcmScaling.NORMALIZED -> sample * 32768f
        }
        return scaled.coerceIn(MIN_PCM16, MAX_PCM16).roundToInt().toShort()
    }

    fun toPcm16(samples: FloatArray, scaling: PcmScaling): ShortArray =
        ShortArray(samples.size) { toPcm16(samples[it], scaling) }

    /**
     * La misma conversion, ya serializada en little-endian, que es el orden que
     * espera el codificador AAC en sus buffers de entrada.
     *
     * Se convierte el recorte entero de una vez en lugar de ir alimentando el
     * codec muestra a muestra: son ~288 KB para 3 s a 48 kHz, despreciables al
     * lado del FloatArray de origen (el doble), y a cambio el bucle de
     * MediaCodec se queda solo con la gestion de buffers.
     */
    fun toPcm16Bytes(samples: FloatArray, scaling: PcmScaling): ByteArray {
        val bytes = ByteArray(samples.size * BYTES_PER_SAMPLE)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) buffer.putShort(toPcm16(sample, scaling))
        return bytes
    }
}

/** Recorte ya escrito en disco. */
data class ClipResult(
    val file: File,
    val sizeBytes: Long,
    val durationMillis: Long,
)

/**
 * Guarda un recorte de audio como AAC-LC dentro de un contenedor MP4 (.m4a).
 *
 * El motivo es puro almacenamiento. Cada identificacion guarda los 3 segundos
 * que la provocaron para poder escucharlos luego en la Pokedex; en WAV crudo
 * eso son ~288 KB por recorte (48 kHz, 16 bits, mono), que a cien detecciones
 * diarias son ~29 MB al dia y unos 10 GB al ano. Con AAC a 64 kbps el mismo
 * recorte baja a ~24 KB: mas de diez veces menos, y a 64 kbps mono un canto de
 * ave sigue siendo perfectamente reconocible de oido.
 *
 * @param sampleRate el de las muestras que se van a pasar, normalmente el que
 *   pide el clasificador.
 * @param scaling escala en la que vienen las muestras. Debe ser el mismo que
 *   uso quien las produjo (ver [PcmScaling]); equivocarse aqui no da error, solo
 *   un recorte mudo o saturado.
 */
class AudioClipWriter(
    private val sampleRate: Int,
    private val scaling: PcmScaling,
    private val bitRate: Int = DEFAULT_BIT_RATE,
) {
    init {
        require(sampleRate > 0) { "sampleRate debe ser positivo" }
        require(bitRate > 0) { "bitRate debe ser positivo" }
    }

    /**
     * Codifica [samples] y lo deja en [target], creando los directorios que
     * falten. Si [target] ya existe se sobreescribe.
     *
     * Se codifica a un temporal al lado y solo al terminar se renombra encima
     * de [target]. El recorte de una especie en una sesion vive siempre en el
     * mismo fichero, asi que escribir directamente en el y borrarlo si fallaba
     * se llevaba por delante el recorte BUENO de la deteccion anterior, al que
     * la base seguia apuntando. Si algo falla, se borra solo el temporal: un
     * .m4a truncado no se puede reproducir y es peor que el que habia.
     */
    suspend fun write(samples: FloatArray, target: File): ClipResult {
        require(samples.isNotEmpty()) { "No se puede guardar un recorte sin muestras" }
        return withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            val partial = File(target.parentFile, target.name + PARTIAL_SUFFIX)
            try {
                encode(Pcm16Converter.toPcm16Bytes(samples, scaling), partial)
                val sizeBytes = partial.length()
                check(sizeBytes > 0) { "El codificador no escribio nada en ${target.name}" }
                // En el mismo directorio, renameTo sustituye de forma atomica.
                check(partial.renameTo(target)) { "No se pudo colocar ${target.name}" }
                ClipResult(
                    file = target,
                    sizeBytes = sizeBytes,
                    durationMillis = samples.size * 1000L / sampleRate,
                )
            } catch (e: Throwable) {
                // Throwable y no Exception a proposito: la cancelacion tambien
                // deja un fichero a medias que hay que limpiar.
                partial.delete()
                throw e
            }
        }
    }

    private suspend fun encode(pcm: ByteArray, target: File) {
        val format = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, CHANNEL_COUNT).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        }

        val codec = MediaCodec.createEncoderByType(MIME_TYPE)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bufferInfo = MediaCodec.BufferInfo()
            var trackIndex = NO_TRACK
            var inputOffset = 0
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                // El bucle no se interrumpe solo: sin esto, cancelar la corrutina
                // dejaria un hilo de IO codificando hasta el final.
                currentCoroutineContext().ensureActive()

                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val input = checkNotNull(codec.getInputBuffer(index))
                        input.clear()
                        val chunk = minOf(input.capacity(), pcm.size - inputOffset)
                        // El timestamp se deriva del offset en bytes en vez de
                        // acumularse trozo a trozo: asi no arrastra error de
                        // redondeo y la pista dura exactamente lo que debe.
                        val presentationTimeUs =
                            inputOffset.toLong() * 1_000_000L / (BYTES_PER_SAMPLE * sampleRate)
                        if (chunk > 0) input.put(pcm, inputOffset, chunk)
                        inputOffset += chunk
                        inputDone = inputOffset >= pcm.size
                        // Sin END_OF_STREAM el codificador nunca vacia lo que
                        // tiene dentro y el fichero sale corto.
                        codec.queueInputBuffer(
                            index,
                            0,
                            chunk,
                            presentationTimeUs,
                            if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0,
                        )
                    }
                }

                when (val index = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Este es el unico momento valido para addTrack: antes el
                        // formato aun no tiene el csd-0 y el muxer escribiria una
                        // cabecera invalida. Y start() va justo despues.
                        check(!muxerStarted) { "El formato de salida cambio dos veces" }
                        val started = checkNotNull(muxer)
                        trackIndex = started.addTrack(codec.outputFormat)
                        started.start()
                        muxerStarted = true
                    }

                    else -> if (index >= 0) {
                        val output = checkNotNull(codec.getOutputBuffer(index))
                        // El buffer CODEC_CONFIG lleva la configuracion que el
                        // muxer ya guardo al anadir la pista; escribirla como
                        // muestra corromperia el fichero.
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0) {
                            check(muxerStarted) { "Llegaron muestras antes de arrancar el muxer" }
                            output.position(bufferInfo.offset)
                            output.limit(bufferInfo.offset + bufferInfo.size)
                            checkNotNull(muxer).writeSampleData(trackIndex, output, bufferInfo)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            // Los recursos nativos no los recoge el GC: si no se sueltan aqui, el
            // siguiente recorte se queda sin codificador disponible.
            runCatching { codec.stop() }
            codec.release()
            val pending = muxer
            if (pending != null) {
                // stop() sobre un muxer que nunca arranco lanza IllegalStateException
                // y taparia la excepcion real que trajo hasta aqui.
                if (muxerStarted) runCatching { pending.stop() }
                runCatching { pending.release() }
            }
        }
    }

    companion object {
        /** Sufijo del temporal mientras se codifica. El barrido de huerfanos lo reconoce. */
        const val PARTIAL_SUFFIX = ".part"

        /**
         * 64 kbps. Es holgado para una sola voz mono y deja el recorte de 3 s en
         * ~24 KB. Bajar a 32 kbps ahorraria la mitad, pero empieza a comerse los
         * agudos, que es justo donde vive la mayor parte del canto.
         */
        const val DEFAULT_BIT_RATE = 64_000

        private val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val CHANNEL_COUNT = 1
        private const val BYTES_PER_SAMPLE = 2
        private const val TIMEOUT_US = 10_000L
        private const val MAX_INPUT_SIZE = 16_384
        private const val NO_TRACK = -1
    }
}

/**
 * Politica de nombres y de borrado de los recortes.
 *
 * Convencion: `<baseDir>/session_<sessionId>/<speciesId saneado>.m4a`
 *
 * Se eligio asi por tres razones concretas:
 *
 * 1. **Se localiza sin base de datos.** Sabiendo la sesion y la especie, la ruta
 *    del recorte es una funcion pura de ambas: la Pokedex puede comprobar si hay
 *    audio con un `exists()`, sin consultar nada.
 * 2. **Se borra por sesion de un tiro.** Un directorio por sesion convierte
 *    "olvidar la salida del sabado" en listar y borrar un directorio, en vez de
 *    en un barrido con patrones sobre miles de ficheros sueltos.
 * 3. **Acota el crecimiento sola.** Como la clave es (sesion, especie), la
 *    decima deteccion de un mirlo en la misma sesion sobreescribe a la primera
 *    en lugar de anadir otro fichero. Las cien detecciones diarias del
 *    planteamiento son en realidad unas pocas especies repetidas, asi que el
 *    gasto real es de decenas de KB al dia, no de MB.
 *
 * El identificador de especie se sanea a `[A-Za-z0-9-]`, sustituyendo lo demas
 * por `_`, porque puede venir con espacios, acentos o barras (nombre cientifico,
 * codigo eBird...) y no todos los sistemas de ficheros los aceptan igual. El
 * saneado es determinista, asi que el mismo id siempre da el mismo fichero; a
 * cambio, dos ids que solo difieran en caracteres no admitidos colisionarian,
 * cosa que no ocurre con identificadores estables tipo codigo eBird.
 */
object ClipStorage {

    private const val SESSION_DIR_PREFIX = "session_"
    private const val CLIP_EXTENSION = ".m4a"

    /** Ruta del recorte de [speciesId] en [sessionId]. No comprueba si existe. */
    fun clipFile(baseDir: File, sessionId: Long, speciesId: String): File {
        require(speciesId.isNotBlank()) { "speciesId no puede estar en blanco" }
        return File(sessionDir(baseDir, sessionId), sanitize(speciesId) + CLIP_EXTENSION)
    }

    /**
     * Borra los recortes de [sessionId] y devuelve cuantos se borraron.
     *
     * El directorio de la sesion se elimina solo si queda vacio: si alguien dejo
     * ahi otra cosa, no le corresponde a esta funcion decidir por el.
     */
    suspend fun deleteClipsFor(baseDir: File, sessionId: Long): Int = withContext(Dispatchers.IO) {
        val dir = sessionDir(baseDir, sessionId)
        val clips = dir.listFiles()?.filter { it.isFile && it.name.endsWith(CLIP_EXTENSION) }
            ?: return@withContext 0
        val deleted = clips.count { it.delete() }
        dir.delete()
        deleted
    }

    /**
     * Bytes ocupados por todos los recortes bajo [baseDir].
     *
     * Solo cuenta ficheros `.m4a`, no el directorio entero: lo que se quiere
     * medir es lo que la politica de retencion puede liberar.
     */
    suspend fun totalBytes(baseDir: File): Long = withContext(Dispatchers.IO) {
        baseDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(CLIP_EXTENSION) }
            .sumOf { it.length() }
    }

    private fun sessionDir(baseDir: File, sessionId: Long): File =
        File(baseDir, "$SESSION_DIR_PREFIX$sessionId")

    private fun sanitize(speciesId: String): String = buildString(speciesId.length) {
        for (c in speciesId) {
            val safe = (c.isLetterOrDigit() && c.code < 128) || c == '-'
            append(if (safe) c else '_')
        }
    }
}
