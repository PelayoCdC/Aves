package dev.pelayo.aves.core.listening

import dev.pelayo.aves.core.data.ObservationRepository
import dev.pelayo.aves.core.ml.api.ClassificationResult
import dev.pelayo.aves.core.model.RarityTier

/**
 * Guarda el resultado de analizar un fichero como si hubiera sido una escucha.
 *
 * Antes el analisis de fichero era un callejon sin salida: se veia el
 * resultado y se descartaba. Ahora crea una sesion (sin ubicacion, que la
 * grabacion puede venir de cualquier sitio) y deja cada especie en la bandeja
 * de revision, donde el usuario decide igual que con el microfono.
 */
class FileAnalysisSaver(private val observations: ObservationRepository) {

    /**
     * @param minScore por debajo de esto no se guarda: el analisis de fichero
     *   no tiene histeresis y un candidato de una sola ventana al 8 % es ruido.
     * @return cuantas observaciones entraron en la bandeja.
     */
    suspend fun save(
        modelId: String,
        detections: List<StableDetection>,
        minScore: Float,
    ): Int {
        val worth = detections.filter { it.score >= minScore }
        if (worth.isEmpty()) return 0

        val now = System.currentTimeMillis()
        val sessionId = observations.startSession(modelId = modelId, startedAt = now)
        var saved = 0
        for (detection in worth) {
            val id = observations.recordDetection(
                sessionId = sessionId,
                modelId = modelId,
                detection = ClassificationResult(
                    speciesId = detection.speciesId,
                    rawLabel = detection.label,
                    labelIndex = detection.labelIndex,
                    rawScore = detection.score,
                    adjustedScore = detection.score,
                ),
                timestamp = now,
                rarityTier = RarityTier.UNKNOWN,
            )
            if (id != null) saved++
        }
        observations.endSession(sessionId, now, processed = 0, skipped = 0)
        observations.discardEmptySessions()
        return saved
    }
}
