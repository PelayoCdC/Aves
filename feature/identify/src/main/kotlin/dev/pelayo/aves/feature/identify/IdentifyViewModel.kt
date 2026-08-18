package dev.pelayo.aves.feature.identify

import android.Manifest
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pelayo.aves.core.audio.AudioCaptureEngine
import dev.pelayo.aves.core.audio.AudioClipWriter
import dev.pelayo.aves.core.audio.ClipStorage
import dev.pelayo.aves.core.audio.EnergyGate
import dev.pelayo.aves.core.audio.PcmScaling
import dev.pelayo.aves.core.audio.WavDecoder
import dev.pelayo.aves.core.audio.WavFormatException
import dev.pelayo.aves.core.audio.audioWindows
import dev.pelayo.aves.core.data.ObservationRepository
import dev.pelayo.aves.core.ml.api.AudioBirdClassifier
import dev.pelayo.aves.core.ml.api.ClassificationResult
import dev.pelayo.aves.core.ml.api.ObservationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.FileNotFoundException
import java.io.File
import java.io.IOException

/** Por que no se puede escuchar. Se distinguen porque cada una se arregla distinto. */
enum class IdentifyError {
    /** El flavor compilado no incluye ningun modelo de audio (caso del flavor `open`). */
    NO_MODEL_IN_BUILD,

    /** Hay codigo de modelo pero faltan los ficheros: hay que ejecutar fetch-models.ps1. */
    MODEL_FILES_MISSING,

    /** El microfono no se pudo abrir. */
    MICROPHONE_UNAVAILABLE,

    /** El fichero elegido no es un WAV PCM de 16 bits. */
    UNSUPPORTED_AUDIO,
}

data class IdentifyUiState(
    val isListening: Boolean = false,
    val isWarmingUp: Boolean = false,
    val detections: List<StableDetection> = emptyList(),
    val windowsProcessed: Int = 0,
    /** Ventanas descartadas por el gate de energia sin gastar inferencia. */
    val windowsSkipped: Int = 0,
    val lastInferenceMillis: Long = 0L,
    val error: IdentifyError? = null,
    val errorDetail: String? = null,
    /** Texto de atribucion del modelo. CC BY-NC-SA lo exige, no es opcional. */
    val attribution: String? = null,
    /** Nombre del fichero analizado, o null si la fuente es el microfono. */
    val analyzedFileName: String? = null,
    val isAnalyzingFile: Boolean = false,
    /** Especies distintas guardadas en la sesion actual. */
    val speciesRecorded: Int = 0,
)

/**
 * Orquesta el bucle de Fase 1: microfono -> ventana -> BirdNET -> histeresis ->
 * pantalla.
 *
 * El clasificador se recibe como fabrica y no como instancia porque solo debe
 * existir mientras se escucha: retiene un modelo de ~25 MB mapeado en memoria.
 */
class IdentifyViewModel(
    private val classifierFactory: () -> AudioBirdClassifier?,
    /**
     * Nulo en previews y en tests que solo ejercitan el pipeline de audio. Si
     * falta, la app identifica pero no guarda nada: degradar asi es preferible
     * a exigir una base de datos para poder ver una pantalla.
     */
    private val observationRepository: ObservationRepository? = null,
    /**
     * Donde guardar los recortes. Nulo desactiva la grabacion: la app sigue
     * identificando y coleccionando, solo que sin audio que escuchar despues.
     */
    private val clipsDirectory: File? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(IdentifyUiState())
    val uiState: StateFlow<IdentifyUiState> = _uiState.asStateFlow()

    private var listenJob: Job? = null
    private var classifier: AudioBirdClassifier? = null

    private var sessionId: Long? = null

    /**
     * Mejor puntuacion ya escrita para cada especie de la sesion.
     *
     * Evita ir a la base de datos en cada ventana: con una inferencia cada
     * 800 ms y tres candidatos, serian unas cuatro consultas por segundo para
     * no cambiar nada la mayoria de las veces. La regla de "quedarse con la
     * mejor" tambien vive en el DAO, asi que esto es solo un filtro barato
     * delante, no la fuente de verdad.
     */
    private val recordedScores = HashMap<String, Float>()

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun toggleListening() {
        if (_uiState.value.isListening) stopListening() else startListening()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startListening() {
        if (listenJob?.isActive == true) return

        val active = classifier ?: classifierFactory()
        if (active == null) {
            _uiState.update { it.copy(error = IdentifyError.NO_MODEL_IN_BUILD) }
            return
        }
        classifier = active

        val stabilizer = DetectionStabilizer()
        recordedScores.clear()
        _uiState.update {
            it.copy(
                isListening = true,
                isWarmingUp = true,
                error = null,
                errorDetail = null,
                detections = emptyList(),
                windowsProcessed = 0,
                windowsSkipped = 0,
                speciesRecorded = 0,
                attribution = active.descriptor.attributionText,
            )
        }

        listenJob = viewModelScope.launch {
            try {
                active.warmUp()
                _uiState.update { it.copy(isWarmingUp = false) }

                // La sesion se abre DESPUES de calentar el modelo: si el .tflite
                // no esta, no queremos dejar una sesion huerfana en la base.
                sessionId = observationRepository?.startSession(
                    modelId = active.descriptor.modelId,
                    startedAt = System.currentTimeMillis(),
                )

                val engine = AudioCaptureEngine(
                    // Los parametros los manda el MODELO. Si manana se cambia a
                    // uno de 32 kHz y 5 s, esta linea no se toca.
                    sampleRate = active.requiredSampleRate,
                    windowSampleCount = active.requiredSampleCount,
                    scaling = INPUT_SCALING,
                )
                val gate = EnergyGate(INPUT_SCALING)
                // El codificador se configura con el formato que pide el modelo,
                // igual que la captura: nada de constantes cableadas aqui.
                val clipWriter = clipsDirectory?.let {
                    AudioClipWriter(
                        sampleRate = active.requiredSampleRate,
                        scaling = INPUT_SCALING,
                    )
                }

                engine.frames().collect { frame ->
                    // Calcular la energia cuesta microsegundos; la inferencia
                    // cuesta cientos de milisegundos. En el campo la mayor parte
                    // del tiempo no canta nada, asi que esta linea se ahorra la
                    // mayoria del gasto de bateria.
                    if (!gate.shouldClassify(frame.samples)) {
                        _uiState.update { it.copy(windowsSkipped = it.windowsSkipped + 1) }
                        return@collect
                    }

                    val started = System.nanoTime()
                    val results = active.classify(
                        samples = frame.samples,
                        context = ObservationContext(
                            epochMillis = frame.endTimestampMillis,
                            // Fase 1 no tiene ubicacion ni prior. Llega en Fase 4.
                            contextInfluence = 0f,
                        ),
                    )
                    val elapsedMillis = (System.nanoTime() - started) / 1_000_000

                    val stable = stabilizer.update(results)
                    persist(
                        detections = stable,
                        modelId = active.descriptor.modelId,
                        timestamp = frame.endTimestampMillis,
                        samples = frame.samples,
                        clipWriter = clipWriter,
                    )

                    _uiState.update {
                        it.copy(
                            detections = stable,
                            windowsProcessed = it.windowsProcessed + 1,
                            lastInferenceMillis = elapsedMillis,
                            speciesRecorded = recordedScores.size,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileNotFoundException) {
                // El caso mas probable al empezar: el .tflite no esta en assets.
                fail(IdentifyError.MODEL_FILES_MISSING, e)
            } catch (e: IOException) {
                fail(IdentifyError.MODEL_FILES_MISSING, e)
            } catch (e: IllegalStateException) {
                fail(IdentifyError.MICROPHONE_UNAVAILABLE, e)
            } finally {
                closeSession()
                _uiState.update { it.copy(isListening = false, isWarmingUp = false) }
            }
        }
    }

    private fun fail(error: IdentifyError, cause: Throwable) {
        Log.e(TAG, "Se detuvo la escucha: $error", cause)
        _uiState.update {
            it.copy(error = error, errorDetail = cause.message, detections = emptyList())
        }
    }

    /**
     * Analiza un fichero de audio en vez del microfono.
     *
     * Esta era la pieza que faltaba para poder cerrar el riesgo #1: sin una via
     * de meter un WAV conocido en el pipeline, no hay forma de comparar la
     * salida del modelo contra una referencia y saber si el escalado de entrada
     * es el correcto.
     *
     * A diferencia del modo en directo aqui NO se aplica histeresis: no hay un
     * flujo continuo que estabilizar, hay un fichero finito. Se analiza por
     * ventanas solapadas y se agrega quedandose con la mejor puntuacion de cada
     * especie, que es lo que hace BirdNET-Analyzer.
     *
     * @param openStream se invoca en el hilo de E/S; el llamante decide de donde
     *   sale el flujo (ContentResolver, assets, fichero de test...).
     */
    fun analyzeFile(fileName: String, openStream: () -> java.io.InputStream) {
        stopListening()

        val active = classifier ?: classifierFactory()
        if (active == null) {
            _uiState.update { it.copy(error = IdentifyError.NO_MODEL_IN_BUILD) }
            return
        }
        classifier = active

        _uiState.update {
            it.copy(
                isAnalyzingFile = true,
                isWarmingUp = true,
                error = null,
                errorDetail = null,
                detections = emptyList(),
                windowsProcessed = 0,
                windowsSkipped = 0,
                analyzedFileName = fileName,
                attribution = active.descriptor.attributionText,
            )
        }

        listenJob = viewModelScope.launch {
            try {
                active.warmUp()
                _uiState.update { it.copy(isWarmingUp = false) }

                val decoded = withContext(Dispatchers.IO) {
                    openStream().use { stream ->
                        WavDecoder.decode(
                            input = stream,
                            targetSampleRate = active.requiredSampleRate,
                            scaling = INPUT_SCALING,
                        )
                    }
                }

                val gate = EnergyGate(INPUT_SCALING)
                val best = LinkedHashMap<String, StableDetection>()
                var processed = 0
                var skipped = 0

                val windowSize = active.requiredSampleCount
                // Al analizar un fichero se puede permitir mas solape que en
                // directo: no hay presion de tiempo real, solo de precision.
                val hopSize = windowSize / 2

                for (window in decoded.samples.audioWindows(windowSize, hopSize)) {
                    currentCoroutineContext().ensureActive()

                    if (!gate.shouldClassify(window)) {
                        skipped++
                        _uiState.update { it.copy(windowsSkipped = skipped) }
                        continue
                    }

                    val results = active.classify(
                        samples = window,
                        context = ObservationContext(
                            epochMillis = System.currentTimeMillis(),
                            contextInfluence = 0f,
                        ),
                    )
                    processed++

                    for (result in results) {
                        val speciesId = result.speciesId ?: continue
                        val current = best[speciesId]
                        if (current == null || result.adjustedScore > current.score) {
                            best[speciesId] = StableDetection(
                                speciesId = speciesId,
                                label = result.rawLabel,
                                score = result.adjustedScore,
                                labelIndex = result.labelIndex,
                                streak = (current?.streak ?: 0) + 1,
                            )
                        } else {
                            best[speciesId] = current.copy(streak = current.streak + 1)
                        }
                    }

                    _uiState.update {
                        it.copy(
                            windowsProcessed = processed,
                            detections = best.values.sortedByDescending { d -> d.score },
                        )
                    }
                }

                Log.i(
                    TAG,
                    "Fichero analizado: $fileName · ${decoded.originalSampleRate} Hz " +
                        "${decoded.originalChannels}ch · ${decoded.durationMillis} ms · " +
                        "$processed ventanas, $skipped saltadas",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: WavFormatException) {
                fail(IdentifyError.UNSUPPORTED_AUDIO, e)
            } catch (e: FileNotFoundException) {
                fail(IdentifyError.MODEL_FILES_MISSING, e)
            } catch (e: IOException) {
                fail(IdentifyError.UNSUPPORTED_AUDIO, e)
            } finally {
                _uiState.update { it.copy(isAnalyzingFile = false, isWarmingUp = false) }
            }
        }
    }

    /**
     * Guarda las detecciones confirmadas de la sesion en curso.
     *
     * Solo escribe cuando la especie es nueva en la sesion o ha mejorado su
     * mejor puntuacion. El DAO aplica la misma regla de forma atomica, asi que
     * esto es solo un filtro barato para no consultar la base cuatro veces por
     * segundo sin necesidad.
     */
    private suspend fun persist(
        detections: List<StableDetection>,
        modelId: String,
        timestamp: Long,
        samples: FloatArray,
        clipWriter: AudioClipWriter?,
    ) {
        val repository = observationRepository ?: return
        val session = sessionId ?: return

        for (detection in detections) {
            val alreadyWritten = recordedScores[detection.speciesId]
            if (alreadyWritten != null && detection.score <= alreadyWritten) continue

            // El recorte se escribe con la ventana que PRODUJO esta deteccion,
            // no con una posterior: es la prueba de por que el modelo dijo lo
            // que dijo, y es lo que hace que la bandeja de revision se pueda
            // usar de verdad en vez de pedir un acto de fe.
            val clipPath = writeClip(clipWriter, session, detection.speciesId, samples)

            repository.recordDetection(
                sessionId = session,
                modelId = modelId,
                detection = ClassificationResult(
                    speciesId = detection.speciesId,
                    rawLabel = detection.label,
                    labelIndex = detection.labelIndex,
                    rawScore = detection.score,
                    adjustedScore = detection.score,
                ),
                timestamp = timestamp,
                mediaPath = clipPath,
            )
            recordedScores[detection.speciesId] = detection.score
        }
    }

    /**
     * Codifica el recorte a AAC. Devuelve null si falla.
     *
     * Un fallo al guardar el audio NO puede tumbar la escucha: es preferible
     * una observacion sin recorte que perder la identificacion entera.
     */
    private suspend fun writeClip(
        clipWriter: AudioClipWriter?,
        session: Long,
        speciesId: String,
        samples: FloatArray,
    ): String? {
        val writer = clipWriter ?: return null
        val baseDir = clipsDirectory ?: return null
        return try {
            val target = ClipStorage.clipFile(baseDir, session, speciesId)
            writer.write(samples, target).file.absolutePath
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo guardar el recorte de $speciesId", e)
            null
        }
    }

    /**
     * Cierra la sesion y descarta las que no registraron nada.
     *
     * Va en NonCancellable porque este codigo corre en el `finally` de una
     * corrutina que casi siempre llega ahi por cancelacion: sin esto, parar la
     * escucha dejaria la sesion abierta para siempre.
     */
    private suspend fun closeSession() {
        val repository = observationRepository ?: return
        val session = sessionId ?: return
        sessionId = null

        withContext(NonCancellable) {
            val state = _uiState.value
            repository.endSession(
                sessionId = session,
                endedAt = System.currentTimeMillis(),
                processed = state.windowsProcessed,
                skipped = state.windowsSkipped,
            )
            // Una escucha de diez minutos sin resultados no aporta nada al
            // historial y solo lo ensucia.
            repository.discardEmptySessions()
        }
    }

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
    }

    override fun onCleared() {
        stopListening()
        // Sin esto se queda vivo un MappedByteBuffer de ~25 MB.
        classifier?.close()
        classifier = null
        super.onCleared()
    }

    companion object {
        private const val TAG = "IdentifyViewModel"

        /**
         * Da igual cual se elija: BirdNET normaliza por min-max dentro del
         * grafo y es invariante a la escala (verificado en
         * `InputScalingRegressionTest`).
         *
         * Se deja en RAW porque evita una multiplicacion por cada una de las
         * 144.000 muestras de cada ventana, unas 180.000 operaciones por
         * segundo que no aportan nada.
         */
        val INPUT_SCALING = PcmScaling.RAW
    }
}
