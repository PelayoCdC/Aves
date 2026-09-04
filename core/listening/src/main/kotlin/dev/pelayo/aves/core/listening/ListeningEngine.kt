package dev.pelayo.aves.core.listening

import android.Manifest
import android.util.Log
import androidx.annotation.RequiresPermission
import dev.pelayo.aves.core.audio.AudioCaptureEngine
import dev.pelayo.aves.core.audio.AudioClipWriter
import dev.pelayo.aves.core.audio.ClipStorage
import dev.pelayo.aves.core.audio.EnergyGate
import dev.pelayo.aves.core.audio.PcmScaling
import dev.pelayo.aves.core.data.ObservationRepository
import dev.pelayo.aves.core.data.RegionRepository
import dev.pelayo.aves.core.ml.api.AudioBirdClassifier
import dev.pelayo.aves.core.ml.api.ClassificationResult
import dev.pelayo.aves.core.ml.api.ObservationContext
import dev.pelayo.aves.core.model.Coordinates
import dev.pelayo.aves.core.model.RarityTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

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
     *   ventana la descarto el gate de energia sin clasificar.
     */
    data class Window(
        val processed: Int,
        val skipped: Int,
        val inferenceMillis: Long,
        /**
         * Nivel de la ventana, 0..1 sobre fondo de escala.
         *
         * Viaja hasta la interfaz porque sin el un "no detecta nada" es
         * ambiguo: no se distingue un microfono que no capta de un microfono
         * que capta bien pero no hay ningun ave cantando. La primera prueba de
         * campo real se perdio justo por no poder distinguir esos dos casos.
         */
        val levelRms: Float = 0f,
        /** Fuente de audio en uso. Ver AudioCaptureEngine.activeSourceName. */
        val sourceName: String? = null,
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
 * Extraido del ViewModel de `:feature:identify` porque ahi era imposible cumplir
 * el caso de uso mas interesante de la app: dejar el movil escuchando un rato
 * largo mientras haces otra cosa. Un ViewModel muere con su pantalla; esto vive
 * donde lo pongan, y en particular dentro de un
 * [servicio en primer plano][ListeningService].
 *
 * Aqui NO entra nada de Android UI ni de `androidx.lifecycle`: esa es justo la
 * propiedad que lo hace reutilizable.
 *
 * @param classifier ya construido. El motor NO lo cierra: no es suyo, y quien lo
 *   creo puede querer reutilizarlo entre escuchas para no repagar el `warmUp`.
 * @param observationRepository nulo desactiva la persistencia.
 * @param regionRepository nulo desactiva rarezas y prior de respaldo.
 * @param clipsDirectory nulo desactiva la grabacion de recortes.
 * @param scaling debe ser el mismo con el que se capturo, se clasifico y se
 *   codifica el recorte; mezclarlos no da error, solo audio mudo o saturado.
 */
class ListeningEngine(
    private val classifier: AudioBirdClassifier,
    private val observationRepository: ObservationRepository?,
    private val regionRepository: RegionRepository?,
    private val clipsDirectory: File?,
    private val scaling: PcmScaling,
) {

    /**
     * Escucha hasta que se cancele la corrutina que recolecta.
     *
     * No hay `stop()`: la unica forma de parar es cancelar, y asi no existe el
     * estado intermedio "pedido parar pero todavia grabando" que siempre acaba
     * en fugas de sesion.
     *
     * @param location ubicacion aproximada de la sesion. Se resuelve UNA vez
     *   fuera y se pasa hecha: el usuario no se mueve 40 km mientras escucha, y
     *   consultarla cada 800 ms seria gasto puro.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun listen(location: Coordinates?): Flow<ListeningEvent> = flow {
        val stabilizer = DetectionStabilizer()

        /**
         * Mejor puntuacion ya escrita para cada especie de la sesion.
         *
         * Filtro barato delante de la base de datos: con una inferencia cada
         * 800 ms y tres candidatos serian unas cuatro consultas por segundo para
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
            )
            val gate = EnergyGate(scaling)
            // El codificador se configura con el formato que pide el modelo,
            // igual que la captura: nada de constantes cableadas aqui.
            val clipWriter = clipsDirectory?.let {
                AudioClipWriter(sampleRate = classifier.requiredSampleRate, scaling = scaling)
            }

            coroutineScope {
                // Descargar las ocurrencias de la celda puede tardar; no debe
                // retrasar la primera identificacion. Si llega tarde, las
                // primeras detecciones se guardan sin rareza y ya esta.
                val prefetch = location?.let { where ->
                    launch {
                        runCatching {
                            regionRepository?.ensureCellCached(
                                where.latitude,
                                where.longitude,
                                System.currentTimeMillis(),
                            )
                        }.onFailure { Log.w(TAG, "No se pudo cachear la celda", it) }
                    }
                }

                try {
                    capture.frames().collect { frame ->
                        // Calcular la energia cuesta microsegundos; la inferencia
                        // cuesta cientos de milisegundos. En el campo la mayor
                        // parte del tiempo no canta nada, asi que esta linea se
                        // ahorra la mayoria del gasto de bateria.
                        // Se calcula UNA vez y se reutiliza para decidir y para
                        // informar: recorrer 144.000 muestras dos veces por
                        // ventana no aporta nada.
                        val level = gate.normalizedRms(frame.samples)
                        if (level < gate.thresholdFraction) {
                            skipped++
                            emit(
                                ListeningEvent.Window(
                                    processed = processed,
                                    skipped = skipped,
                                    inferenceMillis = 0L,
                                    levelRms = level,
                                    sourceName = capture.activeSourceName,
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
                                contextInfluence = DEFAULT_CONTEXT_INFLUENCE,
                            ),
                        )
                        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
                        processed++

                        val stable = stabilizer.update(results)
                        persist(
                            detections = stable,
                            sessionId = sessionId,
                            location = location,
                            recordedScores = recordedScores,
                            timestamp = frame.endTimestampMillis,
                            samples = frame.samples,
                            clipWriter = clipWriter,
                        )

                        emit(
                            ListeningEvent.Window(
                                processed = processed,
                                skipped = skipped,
                                inferenceMillis = elapsedMillis,
                                levelRms = level,
                                sourceName = capture.activeSourceName,
                            ),
                        )
                        if (stable != lastDetections) {
                            lastDetections = stable
                            emit(ListeningEvent.Detections(stable))
                        }
                    }
                } finally {
                    // Sin esto, una descarga de GBIF a medias mantendria vivo el
                    // coroutineScope despues de que la captura ya haya parado, y
                    // el flujo tardaria segundos en completarse.
                    prefetch?.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: FileNotFoundException) {
            // El caso mas probable al empezar: el .tflite no esta en assets.
            emit(fail(e))
        } catch (e: IOException) {
            emit(fail(e))
        } catch (e: IllegalStateException) {
            // Microfono no disponible: otra app lo tiene, o el formato del modelo
            // no lo admite este dispositivo.
            emit(fail(e))
        } finally {
            closeSession(sessionId, processed, skipped)
        }
    }

    private fun fail(cause: Throwable): ListeningEvent.Failed {
        Log.e(TAG, "Se detuvo la escucha", cause)
        return ListeningEvent.Failed(cause)
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
                rarityTier = rarityOf(detection.speciesId, location, timestamp),
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
     * Sin ubicacion o sin datos cacheados se marca como excepcional, que es el
     * fallo seguro: es preferible sobrevalorar una captura que ocultar una
     * rareza autentica.
     */
    private suspend fun rarityOf(
        speciesId: String,
        location: Coordinates?,
        timestamp: Long,
    ): RarityTier {
        val where = location ?: return RarityTier.EXCEPTIONAL
        val repository = regionRepository ?: return RarityTier.EXCEPTIONAL
        return runCatching {
            repository.rarityOf(
                speciesId = speciesId,
                latitude = where.latitude,
                longitude = where.longitude,
                month = RegionRepository.monthOf(timestamp),
            )
        }.getOrNull() ?: RarityTier.EXCEPTIONAL
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
            repository.endSession(
                sessionId = session,
                endedAt = System.currentTimeMillis(),
                processed = processed,
                skipped = skipped,
            )
            // Una escucha de diez minutos sin resultados no aporta nada al
            // historial y solo lo ensucia.
            repository.discardEmptySessions()
        }
    }

    companion object {
        private const val TAG = "ListeningEngine"

        /**
         * Cuanto pesa el contexto frente a la salida cruda del modelo.
         *
         * A la mitad y no al maximo a proposito: el prior es una estadistica, no
         * una verdad. Si mandara del todo, un divagante de verdad quedaria
         * sepultado por lo que "deberia" haber en la zona, y detectar eso es
         * justo lo mas emocionante que puede pasar con esta app.
         */
        const val DEFAULT_CONTEXT_INFLUENCE = 0.5f
    }
}
