package dev.pelayo.aves.core.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.math.max

/**
 * Una ventana de audio lista para clasificar.
 *
 * @param samples exactamente [AudioCaptureEngine.windowSampleCount] muestras
 *   mono, en orden cronologico y ya convertidas a Float.
 * @param endTimestampMillis instante del final de la ventana, que es el que
 *   interesa: es "cuando se oyo esto", no cuando empezo a grabarse.
 */
data class AudioFrame(
    val samples: FloatArray,
    val endTimestampMillis: Long,
) {
    // equals/hashCode a mano porque FloatArray los compara por referencia y una
    // data class con array dentro se comporta de forma sorprendente.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioFrame) return false
        return endTimestampMillis == other.endTimestampMillis &&
            samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int =
        31 * samples.contentHashCode() + endTimestampMillis.hashCode()
}

/**
 * Como se escalan las muestras PCM de 16 bits al convertirlas a Float.
 *
 * Durante un tiempo esto fue el riesgo tecnico numero uno: no se sabia si
 * BirdNET esperaba las muestras normalizadas a [-1,1] o crudas, y equivocarse
 * no habria dado ningun error, solo peores aciertos en silencio.
 *
 * Para **BirdNET v2.4** esta resuelto: es invariante a la escala. Su frontend
 * `model/MEL_SPEC1` aplica una normalizacion min-max sobre el espectrograma, y
 * eso elimina cualquier factor de ganancia constante. Verificado ejecutando el
 * modelo real con la misma senal a escalas que difieren en cinco ordenes de
 * magnitud (ver `InputScalingRegressionTest`).
 *
 * Para **BirdNET+ V3** NO esta verificado, y por eso la app usa [NORMALIZED]
 * en todas partes: si el modelo es invariante, da igual; si no lo es, [-1,1] es
 * la convencion con la que se entrena cualquier modelo de audio en PyTorch. Es
 * la eleccion que no puede salir mal, y cuesta una multiplicacion por muestra.
 */
enum class PcmScaling {
    /** Se pasa el valor del Short tal cual: rango [-32768, 32767]. */
    RAW,

    /** Se divide por 32768: rango [-1, 1], la convencion habitual en audio ML. */
    NORMALIZED;

    fun apply(sample: Short): Float = when (this) {
        RAW -> sample.toFloat()
        NORMALIZED -> sample / 32768f
    }

    /**
     * Valor absoluto maximo que puede tomar una muestra con este escalado.
     * Sirve para expresar umbrales de energia como fraccion de fondo de escala,
     * de forma que el gate de silencio funcione igual con los dos escalados.
     */
    val fullScale: Float
        get() = when (this) {
            RAW -> 32768f
            NORMALIZED -> 1f
        }
}

/**
 * Captura del microfono en ventanas solapadas.
 *
 * El formato lo impone quien construye el motor, leyendolo del clasificador.
 * Aqui no hay ningun 48000 ni ningun 144000 cableado: ese es justamente el
 * punto que permite cambiar de modelo sin tocar este codigo.
 *
 * @param preferUnprocessed si el dispositivo soporta de verdad la fuente
 *   UNPROCESSED (ver [AudioSourceSupport]). Cuando no, se salta directamente a
 *   VOICE_RECOGNITION en vez de abrir una fuente que se llama "sin procesar" y
 *   procesa igual.
 */
class AudioCaptureEngine(
    private val sampleRate: Int,
    val windowSampleCount: Int,
    private val hopMillis: Long = DEFAULT_HOP_MILLIS,
    private val scaling: PcmScaling = PcmScaling.NORMALIZED,
    private val preferUnprocessed: Boolean = true,
) {
    init {
        require(sampleRate > 0) { "sampleRate debe ser positivo" }
        require(windowSampleCount > 0) { "windowSampleCount debe ser positivo" }
        require(hopMillis > 0) { "hopMillis debe ser positivo" }
    }

    /** Muestras entre dos inferencias consecutivas. Con 800 ms sobre 3 s, el solape es del 73%. */
    val hopSampleCount: Int = ((sampleRate * hopMillis) / 1000L).toInt()

    /**
     * Fuente de audio que se acabo usando, legible una vez arrancada la captura.
     *
     * Se expone porque importa para diagnosticar: sin saber cual esta activa, un
     * "no capta nada" es indistinguible de "no hay pajaros".
     */
    @Volatile
    var activeSourceName: String? = null
        private set

    /**
     * Si el sistema nos ha quitado el microfono para darselo a otra app (una
     * llamada, una nota de voz, el asistente).
     *
     * Desde Android 10 eso no es un error: `AudioRecord` sigue entregando
     * muestras, todas a cero. La puerta de energia las descarta como silencio y
     * la pantalla decia "no se oye nada" cuando en realidad no se estaba
     * escuchando. Siempre false antes de Android 10, donde no se puede saber.
     */
    @Volatile
    var isSilenced: Boolean = false
        private set

    /**
     * Emite una ventana cada [hopMillis] mientras se recolecte.
     *
     * El flujo lleva un buffer de una posicion que descarta lo mas antiguo: si
     * la inferencia tarda mas que el salto entre ventanas, se pierden las
     * intermedias en vez de acumular retraso. Para identificar aves en directo
     * vale mucho mas el audio de hace un segundo que el de hace treinta.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun frames(): Flow<AudioFrame> = flow {
        val minBufferBytes = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, ENCODING)
        check(minBufferBytes > 0) {
            "El dispositivo no admite $sampleRate Hz mono PCM 16-bit (codigo $minBufferBytes)"
        }
        // Holgura de dos ventanas para que un hipo del planificador no provoque
        // overrun y perdida de muestras.
        val bufferBytes = max(minBufferBytes, windowSampleCount * BYTES_PER_SAMPLE * 2)

        val record = createRecord(bufferBytes)
        var silenceWatch: AudioManager.AudioRecordingCallback? = null
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) {
                "AudioRecord no se pudo inicializar"
            }
            record.startRecording()
            silenceWatch = watchSilencing(record)

            val ring = ShortRingBuffer(windowSampleCount)
            val readChunk = ShortArray(hopSampleCount.coerceAtMost(windowSampleCount))
            val snapshot = ShortArray(windowSampleCount)
            var samplesSinceLastEmit = 0L

            while (true) {
                currentCoroutineContext().ensureActive()

                val read = record.read(readChunk, 0, readChunk.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) {
                    // ERROR_INVALID_OPERATION / ERROR_BAD_VALUE / ERROR_DEAD_OBJECT
                    if (read < 0) error("AudioRecord.read fallo con codigo $read")
                    continue
                }

                ring.write(readChunk, read)
                samplesSinceLastEmit += read

                // Sin `isFull` se emitiria una primera ventana medio vacia, y el
                // modelo devolveria basura con aparente confianza.
                if (ring.isFull && samplesSinceLastEmit >= hopSampleCount) {
                    samplesSinceLastEmit = 0
                    ring.snapshotInto(snapshot)
                    emit(
                        AudioFrame(
                            samples = FloatArray(windowSampleCount) { scaling.apply(snapshot[it]) },
                            endTimestampMillis = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        } finally {
            if (silenceWatch != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { record.unregisterAudioRecordingCallback(silenceWatch) }
            }
            isSilenced = false
            runCatching { record.stop() }
            record.release()
        }
    }.buffer(onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST, capacity = 1)
        .flowOn(Dispatchers.IO)

    /** Sigue si el sistema silencia esta grabacion. Ver [isSilenced]. */
    private fun watchSilencing(record: AudioRecord): AudioManager.AudioRecordingCallback? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                isSilenced = record.activeRecordingConfiguration?.isClientSilenced == true
            }
        }
        // Ejecutor directo: el aviso llega por binder y solo cambia un booleano.
        record.registerAudioRecordingCallback({ it.run() }, callback)
        isSilenced = record.activeRecordingConfiguration?.isClientSilenced == true
        return callback
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createRecord(bufferBytes: Int): AudioRecord {
        // UNPROCESSED es esencial cuando existe: el control automatico de
        // ganancia y la supresion de ruido del sistema estan afinados para voz
        // humana y destrozan los cantos, sobre todo los agudos. Pero un
        // dispositivo que no lo implementa lo acepta igual y aplica DEFAULT por
        // debajo, asi que solo se intenta si el sistema dice que es real.
        val sources = if (preferUnprocessed) AUDIO_SOURCES else AUDIO_SOURCES.drop(1)
        for (source in sources) {
            val record = try {
                buildRecord(source, bufferBytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo abrir el microfono con la fuente $source", e)
                null
            }

            if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                activeSourceName = SOURCE_NAMES[source] ?: source.toString()
                if (source != MediaRecorder.AudioSource.UNPROCESSED) {
                    Log.w(
                        TAG,
                        "Este dispositivo no ofrece audio sin procesar; se usa la fuente " +
                            "$source. El procesado del sistema puede degradar la identificacion.",
                    )
                }
                return record
            }
            record?.release()
        }
        error("Ninguna fuente de audio disponible admite $sampleRate Hz mono")
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun buildRecord(source: Int, bufferBytes: Int): AudioRecord =
        AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(CHANNEL_CONFIG)
                    .setEncoding(ENCODING)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .build()

    companion object {
        private const val TAG = "AudioCaptureEngine"

        /**
         * 800 ms sobre una ventana de 3 s da un solape del 73%, que es lo que
         * produce la sensacion de "tiempo real". Bajarlo mucho mas dispara el
         * consumo de bateria sin mejorar la deteccion.
         */
        const val DEFAULT_HOP_MILLIS = 800L

        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2

        /** Para poder enseñar en la interfaz cual se esta usando. */
        private val SOURCE_NAMES = mapOf(
            MediaRecorder.AudioSource.UNPROCESSED to "sin procesar",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "reconocimiento de voz",
            MediaRecorder.AudioSource.MIC to "microfono",
        )

        /** En orden de preferencia. El primero solo si el dispositivo lo soporta de verdad. */
        private val AUDIO_SOURCES = listOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
    }
}
