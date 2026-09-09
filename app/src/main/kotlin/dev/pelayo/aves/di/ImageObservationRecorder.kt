package dev.pelayo.aves.di

import android.graphics.Bitmap
import android.util.Log
import dev.pelayo.aves.core.data.ObservationRepository
import dev.pelayo.aves.core.data.RegionRepository
import dev.pelayo.aves.core.ml.api.ClassificationResult
import dev.pelayo.aves.core.model.RarityTier
import dev.pelayo.aves.feature.camera.ImageIdentification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Convierte "el usuario ha tocado este candidato" en una observacion de la
 * coleccion, con su foto.
 *
 * Antes la camara navegaba a la ficha con el nombre provisional del modelo y
 * no guardaba nada: identificar por foto no dejaba rastro en la Pokedex.
 */
class ImageObservationRecorder(
    private val observations: ObservationRepository,
    private val regions: RegionRepository,
    private val photosDirectory: File,
) {
    /**
     * Guarda la observacion (ya confirmada, la eligio una persona) y la foto.
     *
     * @return el speciesId canonico con el que quedo registrada, o null si la
     *   clase del modelo no mapea a ninguna especie del catalogo.
     */
    suspend fun record(identification: ImageIdentification): String? {
        val candidate = identification.candidate
        val context = identification.context
        val photoPath = identification.photo?.let { savePhoto(it, context.epochMillis) }

        val result = observations.recordImageObservation(
            modelId = identification.modelId,
            detection = ClassificationResult(
                speciesId = candidate.speciesId,
                rawLabel = candidate.scientificName,
                labelIndex = candidate.labelIndex,
                rawScore = candidate.rawConfidence,
                adjustedScore = candidate.confidence,
            ),
            timestamp = context.epochMillis,
            latitude = context.latitude,
            longitude = context.longitude,
            mediaPath = photoPath,
            rarityOf = { speciesId ->
                val latitude = context.latitude
                val longitude = context.longitude
                if (latitude == null || longitude == null) {
                    RarityTier.UNKNOWN
                } else {
                    regions.rarityOf(
                        speciesId,
                        latitude,
                        longitude,
                        RegionRepository.monthOf(context.epochMillis),
                    ) ?: RarityTier.UNKNOWN
                }
            },
        )
        return result?.second
    }

    private suspend fun savePhoto(bitmap: Bitmap, timestamp: Long): String? = withContext(Dispatchers.IO) {
        runCatching {
            photosDirectory.mkdirs()
            val target = File(photosDirectory, "photo_$timestamp.jpg")
            target.outputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) { "compress fallo" }
            }
            target.absolutePath
        }.onFailure { Log.w(TAG, "No se pudo guardar la foto", it) }.getOrNull()
    }

    private companion object {
        const val TAG = "ImageObservation"

        /** A 224 px, 85 son ~15 KB por foto. Mas no se nota; menos empieza a verse. */
        const val JPEG_QUALITY = 85
    }
}
