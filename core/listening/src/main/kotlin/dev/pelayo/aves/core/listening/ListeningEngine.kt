package dev.pelayo.aves.core.listening

import android.Manifest
import android.util.Log
import androidx.annotation.RequiresPermission
import dev.pelayo.aves.core.audio.AudioCaptureEngine
import dev.pelayo.aves.core.audio.AudioClipWriter
import dev.pelayo.aves.core.audio.ClipStorage
import dev.pelayo.aves.core.audio.EnergyGate
import dev.pelayo.aves.core.audio.PcmScaling
import dev.pelayo.aves.core.audio.PlaybackSignal
import dev.pelayo.aves.core.audio.SpectrogramComputer
import dev.pelayo.aves.core.common.runSuspendCatching
import dev.pelayo.aves.core.data.ObservationRepository
import dev.pelayo.aves.core.data.RegionRepository
import dev.pelayo.aves.core.ml.api.AudioBirdClassifier
import dev.pelayo.aves.core.ml.api.ClassificationResult
import dev.pelayo.aves.core.ml.api.ObservationContext
import dev.pelayo.aves.core.model.Coordinates
import dev.pelayo.aves.core.model.RarityTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lo que va pasando durante una escucha.
 *
 * Es un flujo de eventos y no un `StateFlow` a proposito: quien lo consume
 * decide como agregarlos. Una pantalla los pliega sobre su estado de UI; un
 * servicio en primer plano solo mira unos pocos para pintar la notificacion.
 */
sealed interface ListeningEvent {

    /**
     * El modelo se esta cargando. Puede tardar un par de segundos la primera
     * vez, y sin este aviso la app parece colgada justo al empezar.
     */
    data object WarmingUp : ListeningEvent

    /**
     * Ya se esta capturando audio.
     *
     * [sessionId] es null cuando no hay repositorio: la escucha funciona igual,
     * solo que no guarda nada. Degradar asi es preferible a exigir base de datos
     * para poder identificar.
     */
    data class Started(val sessionId: Long?) : ListeningEvent

    /**
     * Se ha consumido una ventana de audio. Los contadores son ACUMULADOS de la
     * sesion, no de la ventana, para que el consumidor no tenga que sumar.
     *
     * @param inferenceMillis lo que costo la ultima inferencia, o 0 si la
     *   ventana se descarto sin clasificar.
     * @param levelRms nivel de la ventana, 0..1 sobre fondo de escala. Sin el,
     *   un "no detecta nada" es ambiguo: no se distingue un microfono que no
     *   capta de uno que capta bien pero no hay ningun ave cantando.
     * @param sourceName fuente de audio en uso. Ver AudioCaptureEngine.activeSourceName.
     * @param glimpse el mejor candidato de ESTA ventana, confirmado o no.
     * @param spectrogram columnas nuevas del espectrograma desde la ventana anterior.
     */
    data class Window(
        val processed: Int,
        val skipped: Int,
        val inferenceMillis: Long,
        val levelRms: Float = 0f,
        val sourceName: String? = null,
        val glimpse: CandidateGlimpse? = null,
        val spectrogram: List<FloatArray> = emptyList(),
    ) : ListeningEvent

    /**
     * La lista de detecciones confirmadas ha cambiado.
     *
     * Solo se emite cuando cambia. En el campo lo normal es que no haya nada
     * confirmado durante minutos enteros, asi que esto se ahorra la mayoria de
     * las emisiones y, con ellas, las recomposiciones y los repintados de
     * notificacion que no cambiaban nada.
     */
    data class Detections(val detections: List<StableDetection>) : ListeningEvent

    /**
     * La escucha se ha parado por un fallo. Es terminal: tras esto el flujo se
     * completa. Cancelar la corrutina NO produce este evento.
     */
    data class Failed(val cause: Throwable) : ListeningEvent
}

/**
 * El bucle de escucha completo: microfono -> gate de energia -> clasificador ->
 * histeresis -> persistencia.
 *
 * Vive fuera de cualquier ViewModel porque un ViewModel muere con su pantalla y
 * el caso de uso mas interesante de la app es dejar el movil escuchando un rato
 * largo mientras haces otra cosa. Esto corre dentro de [ListeningService].
 *
 * Aqui NO entra nada de Android UI ni de `androidx.lifecycle`: esa es justo la
 * propiedad que lo hace reutilizable.
 *
 * @param classifier ya construido. El motor NO lo cierra: no es suyo.
 * @param observationRepository nulo desactiva la persistencia.
 * @param regionRepository nulo desactiva las rarezas.
 * @param clipsDirectory nulo desactiva la grabacion de recortes.
 * @param scaling debe ser el mismo con el que se captura, se clasifica y se
 *   codifica el recorte; mezclarlos no da error, solo audio mudo o saturado.
 * @param settings lo que el usuario ajusto: umbral, peso del contexto.
 * @param namer pone nombres del catalogo a las detecciones. Nulo deja la
 *   etiqueta cruda del modelo.
 * @param preferUnprocessed si el dispositivo soporta de verdad audio sin procesar.
 */
class ListeningEngine(
    private val classifier: AudioBirdClassifier,
    private val observationRepository: ObservationRepository?,
    private val regionRepository: RegionRepository?,
    private val clipsDirectory: File?,
    private val scaling: PcmScaling,
    private val settings: ListeningSettings = ListeningSettings(),
    private val namer: DetectionNamer? = null,
    private val preferUnprocessed: Boolean = true,
) {

    /**
     * Escucha hasta que se cancele la corrutina que recolecta.
     *
     * No hay `stop()`: la unica forma de parar es cancelar, y asi no existe el
     * estado intermedio "pedido parar pero todavia grabando" que siempre acaba
     * en fugas de sesion.
     *
     * Cualquier excepcion que no sea una cancelacion termina en un
     * [ListeningEvent.Failed] y el flujo se completa. Antes solo se capturaban
     * tres tipos concretos, y un `IllegalArgumentException` del runtime del
     * modelo o una `SQLiteException` salian del flujo y mataban el proceso con
     * el microfono abierto.
     *
     * @param location ubicacion aproximada de la sesion. Se resuelve UNA vez
     *   fuera y se pasa hecha: el usuario no se mueve 40 km mientras escucha, y
     *   consultarla cada 800 ms seria gasto puro.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun listen(location: Coordinates?): Flow<ListeningEvent> = flow {
        val stabilizer = DetectionStabilizer(
            confirmThreshold = settings.confirmThreshold,
            keepThreshold = settings.keepThreshold,
        )

        /**
         * Mejor puntuacion ya escrita para cada especie de la sesion.
         *
         * Filtro barato delante de la base de datos: con una inferencia cada
         * 800 ms y varios candidatos serian bastantes consultas por segundo para
         * no cambiar nada la mayoria de las veces. La regla de "quedarse con la
         * mejor" tambien vive en el DAO, asi que esto no es la fuente de verdad.
         */
        val recordedScores = HashMap<String, Float>()

        var sessionId: Long? = null
        var processed = 0
        var skipped = 0
        var lastDetections: List<StableDetection> = emptyList()

        try {
            emit(ListeningEvent.WarmingUp)
            classifier.warmUp()

            // La sesion se abre DESPUES de calentar el modelo: si el .tflite no
            // esta, no queremos dejar una sesion huerfana en la base.
            sessionId = observationRepository?.startSession(
                modelId = classifier.descriptor.modelId,
                startedAt = System.currentTimeMillis(),
                latitude = location?.latitude,
                longitude = location?.longitude,
            )
            emit(ListeningEvent.Started(sessionId))

            val capture = AudioCaptureEngine(
                // Los parametros los manda el MODELO. Si manana se cambia a uno
                // de 32 kHz y 5 s, esta linea no se toca.
                sampleRate = classifier.requiredSampleRate,
                windowSampleCount = classifier.requiredSampleCount,
                scaling = scaling,
                preferUnprocessed = preferUnprocessed,
            )
            val gate = EnergyGate(scaling)
            val clipWriter = clipsDirectory?.let {
                AudioClipWriter(sampleRate = classifier.requiredSampleRate, scaling = scaling)
            }
            val spectrogram = SpectrogramComputer(classifier.requiredSampleRate, scaling)
            // Solo se pintan las columnas del audio NUEVO desde la ventana
            // anterior; el resto ya se pinto. Un poco mas del salto, para que
            // el ultimo tramo tenga con que rellenar su FFT.
            val freshSampleCount = capture.hopSampleCount + spectrogram.hopSize
            val windowMillis = classifier.requiredSampleCount * 1000L / classifier.requiredSampleRate
            val modelId = classifier.descriptor.modelId

            capture.frames().collect { frame ->
                val columns = spectrogram.columns(
                    frame.samples.copyOfRange(
                        (frame.samples.size - freshSampleCount).coerceAtLeast(0),
                        frame.samples.size,
                    ),
                )

                // Mientras la propia app reproduce un recorte, el microfono lo
                // oye por el altavoz: clasificar eso seria detectar lo que
                // acabamos de reproducir.
                if (PlaybackSignal.contaminates(frame.endTimestampMillis, windowMillis)) {
                    skipped++
                    emit(
                        ListeningEvent.Window(
                            processed, skipped, 0L,
                            levelRms = gate.normalizedRms(frame.samples),
                            sourceName = capture.activeSourceName,
                            spectrogram = columns,
                        ),
                    )
                    return@collect
                }

                // Calcular la energia cuesta microsegundos; la inferencia cuesta
                // cientos de milisegundos. En el campo la mayor parte del tiempo
                // no canta nada, asi que esta linea se ahorra la mayoria del
                // gasto de bateria. Se calcula UNA vez y se reutiliza para
                // decidir y para informar.
                val level = gate.normalizedRms(frame.samples)
                if (level < gate.thresholdFraction) {
                    skipped++
                    emit(
                        ListeningEvent.Window(
                            processed, skipped, 0L,
                            levelRms = level,
                            sourceName = capture.activeSourceName,
                            spectrogram = columns,
                        ),
                    )
                    return@collect
                }

                val startedAt = System.nanoTime()
                val results = classifier.classify(
                    samples = frame.samples,
                    context = ObservationContext(
                        latitude = location?.latitude,
                        longitude = location?.longitude,
                        epochMillis = frame.endTimestampMillis,
                        contextInfluence = settings.contextInfluence,
                    ),
                )
                val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
                processed++

                val stable = stabilizer.update(results).map { detection ->
                    namer?.name(modelId, detection) ?: detection
                }
                persistSafely(
                    detections = stable,
                    candidates = results,
                    sessionId = sessionId,
                    location = location,
                    recordedScores = recordedScores,
                    timestamp = frame.endTimestampMillis,
                    samples = frame.samples,
                    clipWriter = clipWriter,
                )

                emit(
                    ListeningEvent.Window(
                        processed, skipped, elapsedMillis,
                        levelRms = level,
                        sourceName = capture.activeSourceName,
                        glimpse = glimpseOf(modelId, results),
                        spectrogram = columns,
                    ),
                )
                if (stable != lastDetections) {
                    lastDetections = stable
                    emit(ListeningEvent.Detections(stable))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Modelo ausente, microfono ocupado, E/S, un tensor mal formado, la
            // base de datos: todo termina aqui, con un evento y no con un crash.
            emit(fail(e))
        } finally {
            closeSession(sessionId, processed, skipped)
        }
    }

    private suspend fun glimpseOf(modelId: String, results: List<ClassificationResult>): CandidateGlimpse? {
        val best = results.firstOrNull() ?: return null
        val speciesId = best.speciesId ?: return null
        val glimpse = CandidateGlimpse(speciesId, best.rawLabel, best.adjustedScore)
        return namer?.name(modelId, glimpse, best.labelIndex) ?: glimpse
    }

    private fun fail(cause: Throwable): ListeningEvent.Failed {
        Log.e(TAG, "Se detuvo la escucha", cause)
        return ListeningEvent.Failed(cause)
    }

    /**
     * Guarda sin dejar que un fallo de persistencia tumbe la escucha.
     *
     * Una fila que no se pudo escribir es una observacion perdida; una escucha
     * que se para por eso son todas las siguientes perdidas tambien. Esto cubre,
     * entre otros, la carrera del primer arranque en la que el catalogo aun se
     * esta sembrando cuando llega la primera deteccion.
     */
    private suspend fun persistSafely(
        detections: List<StableDetection>,
        candidates: List<ClassificationResult>,
        sessionId: Long?,
        location: Coordinates?,
        recordedScores: HashMap<String, Float>,
        timestamp: Long,
        samples: FloatArray,
        clipWriter: AudioClipWriter?,
    ) {
        try {
            persist(detections, candidates, sessionId, location, recordedScores, timestamp, samples, clipWriter)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo guardar la deteccion; la escucha sigue", e)
        }
    }

    /**
     * Guarda las detecciones confirmadas de la sesion en curso.
     *
     * Solo escribe cuando la especie es nueva en la sesion o ha mejorado su
     * mejor puntuacion. El DAO aplica la misma regla de forma atomica, asi que
     * esto es solo un filtro barato para no consultar la base varias veces por
     * segundo sin necesidad.
     */
    private suspend fun persist(
        detections: List<StableDetection>,
        candidates: List<ClassificationResult>,
        sessionId: Long?,
        location: Coordinates?,
        recordedScores: HashMap<String, Float>,
        timestamp: Long,
        samples: FloatArray,
        clipWriter: AudioClipWriter?,
    ) {
        val repository = observationRepository ?: return
        val session = sessionId ?: return

        for (detection in detections) {
            val alreadyWritten = recordedScores[detection.speciesId]
            if (alreadyWritten != null && detection.score <= alreadyWritten) continue

            // El recorte se escribe con la ventana que PRODUJO esta deteccion, no
            // con una posterior: es la prueba de por que el modelo dijo lo que
            // dijo, y es lo que hace que la bandeja de revision se pueda usar de
            // verdad en vez de pedir un acto de fe.
            val clipPath = writeClip(clipWriter, session, detection.speciesId, samples)

            repository.recordDetection(
                sessionId = session,
                modelId = classifier.descriptor.modelId,
                detection = ClassificationResult(
                    speciesId = detection.speciesId,
                    rawLabel = detection.label,
                    labelIndex = detection.labelIndex,
                    rawScore = detection.score,
                    adjustedScore = detection.score,
                ),
                timestamp = timestamp,
                latitude = location?.latitude,
                longitude = location?.longitude,
                mediaPath = clipPath,
                rarityTier = rarityOf(detection.canonicalSpeciesId ?: detection.speciesId, location, timestamp),
                // Los demas candidatos de la MISMA ventana: la bandeja los
                // ofrece como "no era esto, era lo segundo".
                candidates = candidates,
            )
            recordedScores[detection.speciesId] = detection.score
        }
    }

    /**
     * Rareza de la especie aqui y ahora.
     *
     * Se congela en la observacion: si el usuario se muda, sus capturas antiguas
     * no deben cambiar de valor retroactivamente. Aquel mirlo en Islandia fue
     * notable de verdad.
     *
     * Sin ubicacion, o sin ese mes de esa celda descargado, se marca como
     * DESCONOCIDA y no como excepcional: es un hueco que se rellena cuando los
     * datos llegan, no un veredicto.
     */
    private suspend fun rarityOf(
        speciesId: String,
        location: Coordinates?,
        timestamp: Long,
    ): RarityTier {
        val where = location ?: return RarityTier.UNKNOWN
        val repository = regionRepository ?: return RarityTier.UNKNOWN
        return runSuspendCatching {
            repository.rarityOf(
                speciesId = speciesId,
                latitude = where.latitude,
                longitude = where.longitude,
                month = RegionRepository.monthOf(timestamp),
            )
        }.getOrNull() ?: RarityTier.UNKNOWN
    }

    /**
     * Codifica el recorte a AAC. Devuelve null si falla.
     *
     * Un fallo al guardar el audio NO puede tumbar la escucha: es preferible una
     * observacion sin recorte que perder la identificacion entera.
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
     * Va en NonCancellable porque este codigo corre en el `finally` de un flujo
     * al que se llega casi siempre POR cancelacion: sin esto, parar la escucha
     * dejaria la sesion abierta para siempre.
     */
    private suspend fun closeSession(sessionId: Long?, processed: Int, skipped: Int) {
        val repository = observationRepository ?: return
        val session = sessionId ?: return

        withContext(NonCancellable) {
            runCatching {
                repository.endSession(
                    sessionId = session,
                    endedAt = System.currentTimeMillis(),
                    processed = processed,
                    skipped = skipped,
                )
                // Una escucha de diez minutos sin resultados no aporta nada al
                // historial y solo lo ensucia.
                repository.discardEmptySessions()
            }.onFailure { Log.w(TAG, "No se pudo cerrar la sesion $session", it) }
        }
    }

    private companion object {
        const val TAG = "ListeningEngine"
    }
}
