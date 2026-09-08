package dev.pelayo.aves.core.data

import dev.pelayo.aves.core.common.Geohash
import dev.pelayo.aves.core.database.AvesDatabase
import dev.pelayo.aves.core.database.dao.SessionWithCounts
import dev.pelayo.aves.core.database.entity.ListeningSessionEntity
import dev.pelayo.aves.core.database.entity.ObservationCandidateEntity
import dev.pelayo.aves.core.database.entity.ObservationEntity
import dev.pelayo.aves.core.ml.api.ClassificationResult
import dev.pelayo.aves.core.model.ObservationSource
import dev.pelayo.aves.core.model.RarityTier
import kotlinx.coroutines.flow.Flow

/**
 * Escribe y lee la coleccion.
 *
 * Aqui vive la regla que evita que la Pokedex se convierta en basura: **una
 * observacion por especie y sesion**, con la mejor puntuacion alcanzada. Sin
 * ella, la unidad de registro seria la deteccion y veinte minutos de escucha en
 * el jardin generarian cientos de filas del mismo mirlo.
 */
class ObservationRepository(
    private val database: AvesDatabase,
    private val speciesResolver: SpeciesResolver,
) {
    private val sessionDao = database.listeningSessionDao()
    private val observationDao = database.observationDao()

    /** Solo lo que el usuario ha confirmado. Esa es la coleccion de verdad. */
    fun collection(): Flow<List<ObservationEntity>> = observationDao.confirmed()

    /** Lo detectado pero aun sin revisar: la bandeja. */
    fun pendingReview(): Flow<List<ObservationEntity>> = observationDao.pendingReview()

    fun collectedSpeciesCount(): Flow<Int> = observationDao.confirmedSpeciesCount()

    fun collectedSpeciesIds(): Flow<List<String>> = observationDao.confirmedSpeciesIds()

    /** El historial: cada escucha con lo que produjo. */
    fun sessions(): Flow<List<SessionWithCounts>> = sessionDao.recentWithCounts()

    suspend fun session(sessionId: Long): ListeningSessionEntity? = sessionDao.findById(sessionId)

    fun observationsInSession(sessionId: Long): Flow<List<ObservationEntity>> =
        observationDao.inSession(sessionId)

    /** Todas las observaciones, para exportar. */
    suspend fun all(): List<ObservationEntity> = observationDao.all()

    /** Los candidatos alternativos de varias observaciones, agrupados por observacion. */
    suspend fun candidatesFor(observationIds: List<Long>): Map<Long, List<ObservationCandidateEntity>> =
        if (observationIds.isEmpty()) {
            emptyMap()
        } else {
            observationDao.candidatesForAll(observationIds).groupBy { it.observationId }
        }

    suspend fun startSession(
        modelId: String,
        startedAt: Long,
        latitude: Double? = null,
        longitude: Double? = null,
    ): Long = sessionDao.insert(
        ListeningSessionEntity(
            startedAt = startedAt,
            latitude = latitude,
            longitude = longitude,
            cellId = if (latitude != null && longitude != null) {
                Geohash.encode(latitude, longitude, CELL_PRECISION)
            } else {
                null
            },
            modelId = modelId,
        ),
    )

    suspend fun endSession(sessionId: Long, endedAt: Long, processed: Int, skipped: Int) {
        sessionDao.close(sessionId, endedAt, processed, skipped)
    }

    /**
     * Descarta las sesiones que no registraron nada.
     *
     * Se llama al cerrar: una escucha de diez minutos sin resultados no aporta
     * nada al historial y solo lo ensucia.
     */
    suspend fun discardEmptySessions(): Int = sessionDao.deleteEmptySessions()

    /** Borra una escucha entera con sus observaciones. Los recortes los borra quien llama. */
    suspend fun deleteSession(sessionId: Long) = sessionDao.delete(sessionId)

    /**
     * Registra una deteccion dentro de una sesion.
     *
     * Si la especie ya aparecio en esta sesion, solo se actualiza cuando la nueva
     * deteccion es mejor; y aun asi se respetan las decisiones que el usuario ya
     * haya tomado sobre ella. La operacion es atomica en la base de datos, no
     * solo en este repositorio: una regla que solo vive en codigo se salta tarde
     * o temprano.
     *
     * @param candidates los otros candidatos de la MISMA ventana, para que la
     *   bandeja pueda ofrecer "no era esto, era lo segundo". Se sustituyen
     *   enteros con cada deteccion mejor.
     * @return id de la observacion, o null si la clase no corresponde a un ave.
     */
    suspend fun recordDetection(
        sessionId: Long,
        modelId: String,
        detection: ClassificationResult,
        timestamp: Long,
        latitude: Double? = null,
        longitude: Double? = null,
        source: ObservationSource = ObservationSource.AUDIO,
        mediaPath: String? = null,
        rarityTier: RarityTier = RarityTier.UNKNOWN,
        candidates: List<ClassificationResult> = emptyList(),
    ): Long? {
        // La tabla model_labels manda sobre el speciesId provisional que trae el
        // clasificador: es la que sabe de codigos caducados y agrupaciones.
        val speciesId = speciesResolver.resolve(modelId, detection.labelIndex) ?: return null

        val existing = observationDao.findInSession(sessionId, speciesId)
        val observationId = observationDao.recordBestInSession(
            ObservationEntity(
                speciesId = speciesId,
                sessionId = sessionId,
                timestamp = timestamp,
                latitude = latitude,
                longitude = longitude,
                source = source,
                rawConfidence = detection.rawScore,
                adjustedConfidence = detection.adjustedScore,
                modelId = modelId,
                mediaPath = mediaPath,
                // Se congela el tier del momento: si el usuario se muda, sus
                // capturas antiguas no deben cambiar de valor retroactivamente.
                rarityTierAtCapture = rarityTier.level,
            ),
        )

        // Los candidatos solo se tocan si esta deteccion es la que manda: si
        // no mejoro a la existente, los suyos son los que explican la fila.
        val improved = existing == null || detection.adjustedScore > existing.adjustedConfidence
        if (improved) {
            observationDao.replaceCandidates(
                observationId,
                candidates
                    .filter { it.labelIndex != detection.labelIndex }
                    .mapIndexedNotNull { index, candidate ->
                        val candidateSpeciesId =
                            speciesResolver.resolve(modelId, candidate.labelIndex)
                                ?: return@mapIndexedNotNull null
                        if (candidateSpeciesId == speciesId) return@mapIndexedNotNull null
                        ObservationCandidateEntity(
                            observationId = observationId,
                            speciesId = candidateSpeciesId,
                            rank = index,
                            score = candidate.adjustedScore,
                        )
                    },
            )
        }
        return observationId
    }

    /**
     * Una identificacion por foto que el usuario ya ha elegido entre los
     * candidatos. Entra CONFIRMADA: la persona acaba de mirar al ave y al
     * nombre y ha decidido, que es exactamente lo que la bandeja de revision
     * pide para el audio.
     *
     * @return id de la observacion, o null si la clase no mapea a ninguna especie.
     */
    suspend fun recordImageObservation(
        modelId: String,
        detection: ClassificationResult,
        timestamp: Long,
        latitude: Double?,
        longitude: Double?,
        mediaPath: String?,
        rarityTier: RarityTier,
    ): Pair<Long, String>? {
        val speciesId = speciesResolver.resolve(modelId, detection.labelIndex) ?: return null
        val id = observationDao.insert(
            ObservationEntity(
                speciesId = speciesId,
                sessionId = null,
                timestamp = timestamp,
                latitude = latitude,
                longitude = longitude,
                source = ObservationSource.IMAGE,
                rawConfidence = detection.rawScore,
                adjustedConfidence = detection.adjustedScore,
                modelId = modelId,
                mediaPath = mediaPath,
                userConfirmed = true,
                rarityTierAtCapture = rarityTier.level,
            ),
        )
        return id to speciesId
    }

    /**
     * Un avistamiento anotado a mano, sin modelo detras. La confianza es 1: el
     * unico clasificador que ha intervenido es una persona que dice haberlo
     * visto.
     */
    suspend fun recordManualObservation(
        speciesId: String,
        timestamp: Long,
        latitude: Double?,
        longitude: Double?,
        notes: String?,
        rarityTier: RarityTier,
    ): Long = observationDao.insert(
        ObservationEntity(
            speciesId = speciesId,
            sessionId = null,
            timestamp = timestamp,
            latitude = latitude,
            longitude = longitude,
            source = ObservationSource.MANUAL,
            rawConfidence = 1f,
            adjustedConfidence = 1f,
            modelId = MANUAL_MODEL_ID,
            userConfirmed = true,
            notes = notes?.takeIf { it.isNotBlank() },
            rarityTierAtCapture = rarityTier.level,
        ),
    )

    suspend fun confirm(observationId: Long) = observationDao.confirm(observationId)

    suspend fun reject(observationId: Long) = observationDao.reject(observationId)

    /** "No era esto, era lo otro". Ver el DAO para el caso en que lo otro ya existia. */
    suspend fun confirmAs(observationId: Long, speciesId: String): Long =
        observationDao.confirmAs(observationId, speciesId)

    /** Devuelve una observacion a la bandeja de revision. Ver el DAO. */
    suspend fun clearReview(observationId: Long) = observationDao.clearReview(observationId)

    suspend fun updateNotes(observationId: Long, notes: String?) =
        observationDao.updateNotes(observationId, notes?.takeIf { it.isNotBlank() })

    suspend fun delete(observationId: Long) = observationDao.delete(observationId)

    suspend fun find(observationId: Long): ObservationEntity? = observationDao.findById(observationId)

    /**
     * Rellena las rarezas que quedaron pendientes.
     *
     * Es la unica excepcion a "el tier se congela en la captura": una
     * observacion guardada como [RarityTier.UNKNOWN] no tenia tier, tenia un
     * hueco, y el hueco se rellena con los datos de SU lugar y SU mes, que son
     * los que se guardaron con ella. Se llama cuando termina de bajar una celda
     * y al arrancar.
     *
     * @return cuantas observaciones cambiaron.
     */
    suspend fun fillUnknownRarities(regionRepository: RegionRepository): Int {
        var updated = 0
        for (observation in observationDao.withRarityLevel(RarityTier.UNKNOWN.level)) {
            val latitude = observation.latitude ?: continue
            val longitude = observation.longitude ?: continue
            val tier = regionRepository.rarityOf(
                speciesId = observation.speciesId,
                latitude = latitude,
                longitude = longitude,
                month = RegionRepository.monthOf(observation.timestamp),
            ) ?: continue
            observationDao.updateRarity(observation.id, tier.level)
            updated++
        }
        return updated
    }

    companion object {
        /** ~40x20 km: la misma celda con la que se cachea GBIF. */
        private const val CELL_PRECISION = 4

        /** Lo que consta como "modelo" en una observacion anotada a mano. */
        const val MANUAL_MODEL_ID = "manual"
    }
}
