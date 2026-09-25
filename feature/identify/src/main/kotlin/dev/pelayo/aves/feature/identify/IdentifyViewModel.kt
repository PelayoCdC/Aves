package dev.pelayo.aves.feature.identify

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pelayo.aves.core.audio.AudioClipWriter
import dev.pelayo.aves.core.audio.AudioFileDecoder
import dev.pelayo.aves.core.audio.DecodedAudio
import dev.pelayo.aves.core.audio.EnergyGate
import dev.pelayo.aves.core.audio.PcmScaling
import dev.pelayo.aves.core.audio.SpeciesTrackBuilder
import dev.pelayo.aves.core.audio.SpectralCleaner
import dev.pelayo.aves.core.audio.WavFormatException
import dev.pelayo.aves.core.audio.audioWindows
import dev.pelayo.aves.core.audio.normalizePeak
import dev.pelayo.aves.core.designsystem.components.PickedPlace
import dev.pelayo.aves.core.listening.CandidateGlimpse
import dev.pelayo.aves.core.listening.DetectionNamer
import dev.pelayo.aves.core.listening.FileAnalysisAccumulator
import dev.pelayo.aves.core.listening.FileAnalysisEntry
import dev.pelayo.aves.core.listening.FileAnalysisSaver
import dev.pelayo.aves.core.listening.FileDetection
import dev.pelayo.aves.core.listening.ListeningFailure
import dev.pelayo.aves.core.listening.ListeningService
import dev.pelayo.aves.core.listening.ListeningSettings
import dev.pelayo.aves.core.listening.StableDetection
import dev.pelayo.aves.core.ml.api.AudioBirdClassifier
import dev.pelayo.aves.core.ml.api.ObservationContext
import dev.pelayo.aves.core.model.Coordinates
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
import java.io.File
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

    /** El fichero elegido no trae audio que este dispositivo sepa decodificar. */
    UNSUPPORTED_AUDIO,

    /**
     * Fallo el modelo a mitad del analisis por algo que no es el fichero (un
     * `OrtException` de ONNX Runtime, por ejemplo). Antes escapaba del
     * `viewModelScope` y cerraba la app entera.
     */
    ANALYSIS_FAILED,
}

/** En que anda el analisis de un fichero. Cada fase tarda lo suyo y se tiene que ver cual es. */
enum class FileStage {
    /** Cargando el modelo: la primera vez son unos segundos. */
    LOADING_MODEL,

    /** Decodificando y remuestreando el audio. No se sabe cuanto falta. */
    DECODING,

    /** Pasando el modelo ventana a ventana. Aqui si hay porcentaje. */
    ANALYZING,

    /** Montando la pista de cada especie encontrada. */
    BUILDING_TRACKS,
}

/** Un fichero elegido pero todavia sin analizar: falta decir donde y cuando se grabo. */
data class PendingFile(
    val name: String,
    val uri: Uri,
    /** Cuando se grabo. De entrada, la fecha del fichero; el usuario la puede cambiar. */
    val recordedAt: Long,
)

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
    /** Otra app se ha quedado el microfono. */
    val micSilenced: Boolean = false,
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
    /** El fichero era mas largo que el tope y solo se analizo el principio. */
    val analysisTruncated: Boolean = false,
    /** El fichero elegido, a la espera de que el usuario diga donde fue y pulse analizar. */
    val pendingFile: PendingFile? = null,
    /** Donde se grabo el fichero, si el usuario lo ha dicho. */
    val filePlace: PickedPlace? = null,
    val fileStage: FileStage? = null,
    /** Avance de [FileStage.ANALYZING], 0..1. */
    val fileProgress: Float = 0f,
    /** La pista de cada especie encontrada: speciesId del modelo -> ruta del fichero. */
    val tracks: Map<String, String> = emptyMap(),
) {
    /** Hay un fichero entre manos (elegido, analizandose o analizado) y la pantalla es suya. */
    val isFileMode: Boolean get() = pendingFile != null || isAnalyzingFile || analyzedFileName != null
}

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
 * @param settings los ajustes del usuario, para que el fichero se mida con el
 *   mismo liston y el mismo peso del contexto que la escucha.
 */
class IdentifyViewModel(
    private val appContext: Context,
    private val classifierFactory: () -> AudioBirdClassifier?,
    private val namer: DetectionNamer? = null,
    private val analysisSaver: FileAnalysisSaver? = null,
    private val settings: suspend () -> ListeningSettings = { ListeningSettings() },
) : ViewModel() {

    /** Estado propio del analisis de fichero, que no pasa por el servicio. */
    private val fileState = MutableStateFlow(IdentifyUiState())

    private var analyzeJob: Job? = null
    private var fileClassifier: AudioBirdClassifier? = null

    /** La fecha de la grabacion analizada: la que llevara la sesion si se guarda. */
    private var analyzedAt: Long = 0L

    val uiState: StateFlow<IdentifyUiState> =
        combine(ListeningService.state, fileState) { service, file ->
            // Mientras se analiza un fichero manda ese estado; si no, el del
            // servicio. Nunca se solapan: analizar un fichero para la escucha.
            if (file.isFileMode) {
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
                    micSilenced = service.micSilenced,
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
     * El usuario ha elegido un fichero. Todavia NO se analiza: primero puede
     * decir donde y cuando se grabo, porque el analisis tarda y repetirlo por
     * haber olvidado el lugar seria pagar dos veces.
     */
    fun prepareFile(fileName: String, uri: Uri, lastModifiedMillis: Long?) {
        stopListening()
        analyzeJob?.cancel()
        clearTracks()
        fileState.value = IdentifyUiState(
            pendingFile = PendingFile(
                name = fileName,
                uri = uri,
                recordedAt = lastModifiedMillis?.takeIf { it > 0L } ?: System.currentTimeMillis(),
            ),
            // El lugar se conserva de un fichero al siguiente: quien analiza
            // cinco grabaciones de la misma excursion lo dice una vez.
            filePlace = fileState.value.filePlace,
        )
    }

    fun setFilePlace(place: PickedPlace?) = fileState.update { it.copy(filePlace = place) }

    fun setFileDate(epochMillis: Long) = fileState.update { state ->
        state.copy(pendingFile = state.pendingFile?.copy(recordedAt = epochMillis))
    }

    /**
     * Analiza el fichero pendiente.
     *
     * Esta fue la pieza que permitio cerrar el riesgo de la normalizacion de
     * entrada: sin una via de meter un WAV conocido en el pipeline no habia
     * forma de comparar la salida del modelo contra una referencia.
     *
     * A diferencia del modo en directo no hay histeresis (no hay un flujo
     * continuo que estabilizar, hay un fichero finito), pero si el mismo
     * liston: [FileAnalysisAccumulator] solo deja pasar lo que convence, y la
     * pantalla ensena especies (con su porcentaje solo si el usuario lo pide).
     */
    fun analyzePending() {
        val pending = fileState.value.pendingFile ?: return
        val place = fileState.value.filePlace

        // Analizar un fichero y escuchar el microfono a la vez no tiene sentido:
        // compiten por el mismo modelo y la pantalla solo puede enseñar uno.
        stopListening()
        analyzeJob?.cancel()
        clearTracks()

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
            fileStage = FileStage.LOADING_MODEL,
            analyzedFileName = pending.name,
            modelSampleRate = active.requiredSampleRate,
            filePlace = place,
        )
        analyzedAt = pending.recordedAt

        analyzeJob = viewModelScope.launch {
            try {
                val current = settings()
                active.warmUp()
                fileState.update { it.copy(isWarmingUp = false, fileStage = FileStage.DECODING) }

                // Cualquier formato que sepa el dispositivo: M4A de la grabadora,
                // MP3, OGG, Opus de una nota de voz, FLAC, AMR... y WAV.
                val decoded = AudioFileDecoder.decode(
                    context = appContext,
                    uri = pending.uri,
                    targetSampleRate = active.requiredSampleRate,
                    scaling = INPUT_SCALING,
                )
                fileState.update {
                    it.copy(analysisTruncated = decoded.truncated, fileStage = FileStage.ANALYZING, fileProgress = 0f)
                }

                // Con lugar, el contexto cuenta igual que en una escucha: es el
                // del sitio y la fecha de la GRABACION. Sin lugar no se aplica:
                // una grabacion puede venir de cualquier parte del mundo y el
                // contexto de aqui no dice nada de ella.
                val observationContext = ObservationContext(
                    latitude = place?.latitude,
                    longitude = place?.longitude,
                    epochMillis = pending.recordedAt,
                    contextInfluence = if (place != null) current.contextInfluence else 0f,
                )

                val gate = EnergyGate(INPUT_SCALING, sampleRate = active.requiredSampleRate)
                val accumulator = FileAnalysisAccumulator(confirmThreshold = current.confirmThreshold)
                var processed = 0
                var skipped = 0

                val windowSize = active.requiredSampleCount
                // Al analizar un fichero se puede permitir mas solape que en
                // directo: no hay presion de tiempo real, solo de precision.
                val hopSize = windowSize / 2
                val totalWindows = windowCount(decoded.samples.size, windowSize, hopSize)

                for ((index, window) in decoded.samples.audioWindows(windowSize, hopSize).withIndex()) {
                    currentCoroutineContext().ensureActive()

                    if (!gate.shouldClassify(window)) {
                        skipped++
                    } else {
                        accumulator.add(
                            startSample = index * hopSize,
                            results = active.classify(samples = window, context = observationContext),
                        )
                        processed++
                    }

                    fileState.update {
                        it.copy(
                            windowsProcessed = processed,
                            windowsSkipped = skipped,
                            fileProgress = (index + 1).toFloat() / totalWindows,
                            detections = accumulator.confirmed().map { found ->
                                namer?.name(modelId, found.detection) ?: found.detection
                            },
                        )
                    }
                }

                val confirmed = accumulator.confirmed()
                if (confirmed.isNotEmpty()) {
                    fileState.update { it.copy(fileStage = FileStage.BUILDING_TRACKS) }
                    val tracks = buildTracks(decoded, confirmed, windowSize)
                    fileState.update { it.copy(tracks = tracks) }
                }

                Log.i(
                    TAG,
                    "Fichero analizado: ${pending.name} · ${decoded.originalSampleRate} Hz " +
                        "${decoded.originalChannels}ch · ${decoded.durationMillis} ms · " +
                        "$processed ventanas, $skipped saltadas, ${confirmed.size} especies",
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
            } catch (e: Exception) {
                fail(IdentifyError.ANALYSIS_FAILED, e)
            } finally {
                fileState.update { it.copy(isAnalyzingFile = false, isWarmingUp = false, fileStage = null) }
            }
        }
    }

    /**
     * La pista de cada especie: sus tramos, limpios y a un volumen que se oiga.
     *
     * Van a la cache, no a la coleccion: hasta que el usuario guarde el analisis
     * son un borrador. Que una pista falle no tira el analisis; esa especie se
     * queda sin boton de reproducir y ya.
     */
    private suspend fun buildTracks(
        decoded: DecodedAudio,
        confirmed: List<FileDetection>,
        windowSize: Int,
    ): Map<String, String> = withContext(Dispatchers.Default) {
        val directory = tracksDirectory().apply { mkdirs() }
        val cleaner = SpectralCleaner()
        // El ruido de fondo se estima UNA vez, de la grabacion entera.
        val noise = cleaner.noiseProfile(decoded.samples)
        val writer = AudioClipWriter(decoded.sampleRate, INPUT_SCALING)

        val tracks = LinkedHashMap<String, String>()
        confirmed.forEachIndexed { index, found ->
            currentCoroutineContext().ensureActive()
            try {
                val raw = SpeciesTrackBuilder.build(decoded.samples, decoded.sampleRate, found.windows, windowSize)
                if (raw.isEmpty()) return@forEachIndexed
                val track = normalizePeak(cleaner.clean(raw, noise))
                val target = File(directory, "track_$index.m4a")
                tracks[found.detection.speciesId] = writer.write(track, target).file.absolutePath
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo montar la pista de ${found.detection.speciesId}", e)
            }
        }
        tracks
    }

    /**
     * Manda lo que se encontro en el fichero a la bandeja de revision, cada
     * especie con su pista.
     */
    fun saveFileAnalysis() {
        val saver = analysisSaver ?: return
        val current = fileState.value
        val modelId = fileClassifier?.descriptor?.modelId ?: return
        if (current.isSaving || current.savedToReview != null || current.detections.isEmpty()) return

        fileState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val saved = runCatching {
                saver.save(
                    modelId = modelId,
                    entries = current.detections.map { detection ->
                        FileAnalysisEntry(detection, current.tracks[detection.speciesId]?.let(::File))
                    },
                    where = current.filePlace?.let { Coordinates(it.latitude, it.longitude) },
                    recordedAt = analyzedAt,
                )
            }.onFailure { Log.e(TAG, "No se pudo guardar el analisis", it) }.getOrDefault(0)
            fileState.update { it.copy(isSaving = false, savedToReview = saved) }
        }
    }

    /** Vuelve al modo microfono, descartando el resultado del fichero. */
    fun stopFileAnalysis() {
        analyzeJob?.cancel()
        analyzeJob = null
        clearTracks()
        fileState.value = IdentifyUiState()
    }

    private fun tracksDirectory(): File = File(appContext.cacheDir, TRACKS_DIRECTORY)

    /** Las pistas son un borrador: fuera en cuanto se suelta el fichero. */
    private fun clearTracks() {
        runCatching { tracksDirectory().deleteRecursively() }
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

        const val TRACKS_DIRECTORY = "analysis_tracks"

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

/**
 * Cuantas ventanas saldran de un audio de [samples] muestras. Tiene que contar
 * exactamente igual que `audioWindows`, o la barra de progreso no llega al final
 * (o se pasa).
 */
internal fun windowCount(samples: Int, windowSize: Int, hopSize: Int): Int = when {
    samples <= 0 -> 0
    samples <= windowSize -> 1
    else -> (samples - windowSize + hopSize - 1) / hopSize + 1
}
