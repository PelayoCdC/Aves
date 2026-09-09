package dev.pelayo.aves.feature.identify

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pelayo.aves.core.audio.EnergyGate
import dev.pelayo.aves.core.audio.PcmScaling
import dev.pelayo.aves.core.audio.WavDecoder
import dev.pelayo.aves.core.audio.WavFormatException
import dev.pelayo.aves.core.audio.audioWindows
import dev.pelayo.aves.core.listening.CandidateGlimpse
import dev.pelayo.aves.core.listening.DetectionNamer
import dev.pelayo.aves.core.listening.DetectionStabilizer
import dev.pelayo.aves.core.listening.FileAnalysisSaver
import dev.pelayo.aves.core.listening.ListeningFailure
import dev.pelayo.aves.core.listening.ListeningService
import dev.pelayo.aves.core.listening.StableDetection
import dev.pelayo.aves.core.ml.api.AudioBirdClassifier
import dev.pelayo.aves.core.ml.api.ObservationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

/** Por que no se puede escuchar. Se distinguen porque cada una se arregla distinto. */
enum class IdentifyError {
    /** El flavor compilado no incluye ningun modelo de audio. */
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
    /** Nivel de audio de la ultima ventana, 0..1 sobre fondo de escala. */
    val levelRms: Float = 0f,
    /** Fuente de microfono en uso. */
    val audioSource: String? = null,
    /** El mejor candidato de la ultima ventana, confirmado o no. */
    val glimpse: CandidateGlimpse? = null,
    /** Ultimas columnas del espectrograma, de mas antigua a mas nueva. */
    val spectrogram: List<FloatArray> = emptyList(),
    /** Si la escucha en curso tiene ubicacion (prior y rareza activos). */
    val hasLocation: Boolean = false,
    val error: IdentifyError? = null,
    val errorDetail: String? = null,
    /** Instante en el que la escucha se cortara sola. 0 si no hay ninguna en curso. */
    val stopsAtMillis: Long = 0L,
    /** La ultima escucha termino por agotar el limite de tiempo, no porque nadie lo pidiera. */
    val stoppedByTimeout: Boolean = false,
    /** Nombre del fichero analizado, o null si la fuente es el microfono. */
    val analyzedFileName: String? = null,
    val isAnalyzingFile: Boolean = false,
    /** Frecuencia que pide el modelo, para poder decir "conviertelo a N Hz". */
    val modelSampleRate: Int? = null,
    /** Cuantas detecciones del fichero se guardaron en la bandeja, o null si aun no se ha guardado. */
    val savedToReview: Int? = null,
    val isSaving: Boolean = false,
)

/**
 * Pantalla de identificacion.
 *
 * **La escucha en directo NO vive aqui.** Corre en [ListeningService], y este
 * ViewModel solo observa su estado y le manda arrancar o parar.
 *
 * El motivo es de producto antes que de arquitectura: con el bucle dentro del
 * ViewModel, apagar la pantalla o cambiar de app cortaba la identificacion, y
 * eso rompe el caso de uso mas interesante que tiene la app, que es dejar el
 * movil escuchando un rato largo mientras haces otra cosa.
 *
 * El analisis de FICHERO si se queda aqui: es puntual, acotado y no tiene
 * sentido sacarlo a un servicio en primer plano.
 *
 * @param namer nombres del catalogo para el analisis de fichero. Los de la
 *   escucha en directo los pone el servicio.
 */
class IdentifyViewModel(
    private val appContext: Context,
    private val classifierFactory: () -> AudioBirdClassifier?,
    private val namer: DetectionNamer? = null,
    private val analysisSaver: FileAnalysisSaver? = null,
) : ViewModel() {

    /** Estado propio del analisis de fichero, que no pasa por el servicio. */
    private val fileState = MutableStateFlow(IdentifyUiState())

    private var analyzeJob: Job? = null
    private var fileClassifier: AudioBirdClassifier? = null

    val uiState: StateFlow<IdentifyUiState> =
        combine(ListeningService.state, fileState) { service, file ->
            // Mientras se analiza un fichero manda ese estado; si no, el del
            // servicio. Nunca se solapan: analizar un fichero para la escucha.
            if (file.isAnalyzingFile || file.analyzedFileName != null) {
                file
            } else {
                IdentifyUiState(
                    isListening = service.isRunning,
                    isWarmingUp = service.isWarmingUp,
                    detections = service.detections,
                    windowsProcessed = service.windowsProcessed,
                    windowsSkipped = service.windowsSkipped,
                    lastInferenceMillis = service.lastInferenceMillis,
                    levelRms = service.levelRms,
                    audioSource = service.audioSource,
                    glimpse = service.glimpse,
                    spectrogram = service.spectrogram,
                    hasLocation = service.hasLocation,
                    stopsAtMillis = service.stopsAtMillis,
                    stoppedByTimeout = service.stoppedByTimeout,
                    error = service.failure?.toIdentifyError(),
                    errorDetail = service.failureDetail,
                    modelSampleRate = file.modelSampleRate,
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = IdentifyUiState(),
        )

    /**
     * Arranca o para la escucha en segundo plano.
     *
     * No hace falta `@RequiresPermission` aqui: quien comprueba el permiso es la
     * pantalla antes de llamar, y el servicio vuelve a fallar de forma
     * controlada si se hubiera revocado por el camino.
     */
    fun toggleListening() {
        stopFileAnalysis()
        if (ListeningService.state.value.isRunning) {
            ListeningService.stop(appContext)
        } else {
            ListeningService.start(appContext)
        }
    }

    fun stopListening() {
        if (ListeningService.state.value.isRunning) ListeningService.stop(appContext)
    }

    /**
     * Analiza un fichero de audio en vez del microfono.
     *
     * Esta fue la pieza que permitio cerrar el riesgo de la normalizacion de
     * entrada: sin una via de meter un WAV conocido en el pipeline no habia
     * forma de comparar la salida del modelo contra una referencia.
     *
     * A diferencia del modo en directo NO se aplica histeresis: no hay un flujo
     * continuo que estabilizar, hay un fichero finito. Se analiza por ventanas
     * solapadas y se agrega quedandose con la mejor puntuacion de cada especie,
     * que es lo que hace BirdNET-Analyzer.
     */
    fun analyzeFile(fileName: String, openStream: () -> java.io.InputStream) {
        // Analizar un fichero y escuchar el microfono a la vez no tiene sentido:
        // compiten por el mismo modelo y la pantalla solo puede enseñar uno.
        stopListening()
        analyzeJob?.cancel()

        val active = fileClassifier ?: classifierFactory()
        if (active == null) {
            fileState.update { it.copy(error = IdentifyError.NO_MODEL_IN_BUILD) }
            return
        }
        fileClassifier = active
        val modelId = active.descriptor.modelId

        fileState.value = IdentifyUiState(
            isAnalyzingFile = true,
            isWarmingUp = true,
            analyzedFileName = fileName,
            modelSampleRate = active.requiredSampleRate,
        )

        analyzeJob = viewModelScope.launch {
            try {
                active.warmUp()
                fileState.update { it.copy(isWarmingUp = false) }

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
                        fileState.update { it.copy(windowsSkipped = skipped) }
                        continue
                    }

                    val results = active.classify(
                        samples = window,
                        context = ObservationContext(
                            epochMillis = System.currentTimeMillis(),
                            // Una grabacion puede venir de cualquier sitio del
                            // mundo: el contexto de AQUI no dice nada de ella.
                            contextInfluence = 0f,
                        ),
                    )
                    processed++

                    for (result in results) {
                        val speciesId = result.speciesId ?: continue
                        val current = best[speciesId]
                        if (current == null || result.adjustedScore > current.score) {
                            val detection = StableDetection(
                                speciesId = speciesId,
                                label = result.rawLabel,
                                score = result.adjustedScore,
                                labelIndex = result.labelIndex,
                                streak = (current?.streak ?: 0) + 1,
                            )
                            best[speciesId] = namer?.name(modelId, detection) ?: detection
                        } else {
                            best[speciesId] = current.copy(streak = current.streak + 1)
                        }
                    }

                    fileState.update {
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
            } catch (e: IllegalArgumentException) {
                fail(IdentifyError.UNSUPPORTED_AUDIO, e)
            } finally {
                fileState.update { it.copy(isAnalyzingFile = false, isWarmingUp = false) }
            }
        }
    }

    /**
     * Manda lo que se encontro en el fichero a la bandeja de revision.
     *
     * Antes el analisis de fichero se veia y se tiraba. Ahora deja las especies
     * que pasan el umbral en la bandeja, donde el usuario decide igual que con
     * el microfono.
     */
    fun saveFileAnalysis() {
        val saver = analysisSaver ?: return
        val current = fileState.value
        val modelId = fileClassifier?.descriptor?.modelId ?: return
        if (current.isSaving || current.savedToReview != null || current.detections.isEmpty()) return

        fileState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val saved = runCatching {
                saver.save(modelId, current.detections, DetectionStabilizer.DEFAULT_CONFIRM_THRESHOLD)
            }.onFailure { Log.e(TAG, "No se pudo guardar el analisis", it) }.getOrDefault(0)
            fileState.update { it.copy(isSaving = false, savedToReview = saved) }
        }
    }

    /** Vuelve al modo microfono, descartando el resultado del fichero. */
    fun stopFileAnalysis() {
        analyzeJob?.cancel()
        analyzeJob = null
        fileState.value = IdentifyUiState()
    }

    private fun fail(error: IdentifyError, cause: Throwable) {
        Log.e(TAG, "Fallo el analisis del fichero: $error", cause)
        fileState.update {
            it.copy(error = error, errorDetail = cause.message, detections = emptyList())
        }
    }

    override fun onCleared() {
        analyzeJob?.cancel()
        // Solo se cierra el clasificador del analisis de fichero. El de la
        // escucha es del servicio, que le sobrevive a esta pantalla a proposito.
        fileClassifier?.close()
        fileClassifier = null
        super.onCleared()
    }

    private companion object {
        const val TAG = "IdentifyViewModel"

        /** El mismo que usa el servicio. Ver `PcmScaling`. */
        val INPUT_SCALING = PcmScaling.NORMALIZED

        /**
         * Margen para que un giro de pantalla no desconecte del servicio y
         * vuelva a conectar, perdiendo el estado un instante.
         */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * Traduce el fallo del servicio al vocabulario de esta pantalla.
 *
 * Son dos enumeraciones distintas a proposito: el servicio conoce fallos que a
 * la pantalla no le dicen nada (que nadie publicara sus dependencias, que el
 * sistema denegara el paso a primer plano) y que aqui solo pueden presentarse
 * como "no se pudo escuchar".
 */
private fun ListeningFailure.toIdentifyError(): IdentifyError = when (this) {
    ListeningFailure.NO_MODEL_IN_BUILD -> IdentifyError.NO_MODEL_IN_BUILD
    // El motor se para tanto por falta de ficheros del modelo como por
    // problemas de microfono. El detalle textual distingue el caso concreto.
    ListeningFailure.ENGINE_FAILED -> IdentifyError.MODEL_FILES_MISSING
    ListeningFailure.MICROPHONE_PERMISSION_MISSING,
    ListeningFailure.FOREGROUND_START_DENIED,
    ListeningFailure.NOT_CONFIGURED,
    -> IdentifyError.MICROPHONE_UNAVAILABLE
}
