package dev.pelayo.aves.core.listening

import android.util.Log
import dev.pelayo.aves.core.audio.ClipStorage
import dev.pelayo.aves.core.data.ObservationRepository
import dev.pelayo.aves.core.ml.api.ClassificationResult
import dev.pelayo.aves.core.model.Coordinates
import dev.pelayo.aves.core.model.RarityTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Una especie del fichero lista para guardar: que es y donde esta su pista. */
data class FileAnalysisEntry(
    val detection: StableDetection,
    /** La pista de la especie, en un fichero temporal. Null si no se pudo generar. */
    val track: File? = null,
)

/**
 * Guarda el resultado de analizar un fichero como si hubiera sido una escucha.
 *
 * Antes el analisis de fichero era un callejon sin salida: se veia el
 * resultado y se descartaba. Ahora crea una sesion y deja cada especie en la
 * bandeja de revision, donde el usuario decide igual que con el microfono, y
 * con su pista: sin ella no habia nada que escuchar ni en la bandeja ni,
 * despues, en la ficha.
 *
 * @param clipsDirectory donde viven los recortes. Null guarda sin pistas.
 * @param rarityOf rareza de una especie en un lugar y un momento. Solo se
 *   pregunta si el usuario dijo donde se grabo.
 */
class FileAnalysisSaver(
    private val observations: ObservationRepository,
    private val clipsDirectory: File? = null,
    private val rarityOf: suspend (speciesId: String, where: Coordinates, epochMillis: Long) -> RarityTier? =
        { _, _, _ -> null },
) {

    /**
     * @param entries solo lo que el analisis dio por seguro; aqui no se filtra mas.
     * @param where donde se grabo, si el usuario lo dijo.
     * @param recordedAt cuando se grabo. Por defecto, ahora.
     * @return cuantas observaciones entraron en la bandeja.
     */
    suspend fun save(
        modelId: String,
        entries: List<FileAnalysisEntry>,
        where: Coordinates? = null,
        recordedAt: Long = System.currentTimeMillis(),
    ): Int {
        if (entries.isEmpty()) return 0

        val sessionId = observations.startSession(
            modelId = modelId,
            startedAt = recordedAt,
            latitude = where?.latitude,
            longitude = where?.longitude,
        )
        var saved = 0
        for (entry in entries) {
            val detection = entry.detection
            val clipPath = entry.track?.let { keepTrack(it, sessionId, detection.speciesId) }
            val rarity = where?.let { place ->
                val canonical = detection.canonicalSpeciesId ?: detection.speciesId
                try {
                    rarityOf(canonical, place, recordedAt)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            } ?: RarityTier.UNKNOWN

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
                timestamp = recordedAt,
                latitude = where?.latitude,
                longitude = where?.longitude,
                mediaPath = clipPath,
                rarityTier = rarity,
            )
            if (id != null) saved++
        }
        observations.endSession(sessionId, recordedAt, processed = 0, skipped = 0)
        observations.discardEmptySessions()
        return saved
    }

    /**
     * Pasa la pista del temporal a la carpeta de recortes, con el mismo nombre
     * que tendria el recorte de una escucha: asi borrar la sesion se la lleva.
     *
     * Se COPIA y no se mueve: la pantalla del analisis sigue pudiendo
     * reproducirla despues de guardar.
     */
    private suspend fun keepTrack(track: File, sessionId: Long, speciesId: String): String? {
        val baseDir = clipsDirectory ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val target = ClipStorage.clipFile(baseDir, sessionId, speciesId)
                target.parentFile?.mkdirs()
                track.copyTo(target, overwrite = true)
                target.absolutePath
            }.onFailure { Log.w(TAG, "No se pudo guardar la pista de $speciesId", it) }.getOrNull()
        }
    }

    private companion object {
        const val TAG = "FileAnalysisSaver"
    }
}
