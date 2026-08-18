package dev.pelayo.aves.core.data

import dev.pelayo.aves.core.common.Geohash
import dev.pelayo.aves.core.database.AvesDatabase
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

    /** Lo detectado pero aun sin revisar: la bandeja de la Fase 3. */
    fun pendingReview(): Flow<List<ObservationEntity>> = observationDao.pendingReview()

    fun collectedSpeciesCount(): Flow<Int> = observationDao.confirmedSpeciesCount()

    fun collectedSpeciesIds(): Flow<List<String>> = observationDao.confirmedSpeciesIds()

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

    /**
     * Registra una deteccion dentro de una sesion.
     *
     * Si la especie ya aparecio en esta sesion, solo se actualiza cuando la nueva
     * deteccion es mejor; y aun asi se respetan las decisiones que el usuario ya
     * haya tomado sobre ella. La operacion es atomica en la base de datos, no
     * solo en este repositorio: una regla que solo vive en codigo se salta tarde
     * o temprano.
     *
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
        rarityTier: RarityTier = RarityTier.EXCEPTIONAL,
        candidates: List<ClassificationResult> = emptyList(),
    ): Long? {
        // La tabla model_labels manda sobre el speciesId provisional que trae el
        // clasificador: es la que sabe de codigos caducados y agrupaciones.
        val speciesId = speciesResolver.resolve(modelId, detection.labelIndex) ?: return null

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

        if (candidates.isNotEmpty()) {
            observationDao.insertCandidates(
                candidates.mapIndexedNotNull { index, candidate ->
                    val candidateSpeciesId =
                        speciesResolver.resolve(modelId, candidate.labelIndex) ?: return@mapIndexedNotNull null
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

    suspend fun confirm(observationId: Long) = observationDao.confirm(observationId)

    suspend fun reject(observationId: Long) = observationDao.reject(observationId)

    /** Devuelve una observacion a la bandeja de revision. Ver el DAO. */
    suspend fun clearReview(observationId: Long) = observationDao.clearReview(observationId)

    private companion object {
        /** ~40x20 km: la misma celda con la que se cachea GBIF en la Fase 4. */
        const val CELL_PRECISION = 4
    }
}
