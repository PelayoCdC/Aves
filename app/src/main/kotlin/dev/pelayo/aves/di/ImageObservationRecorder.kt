package dev.pelayo.aves.di

import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.scale
import dev.pelayo.aves.core.common.PhotoStorage
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
        val photoPath = identification.photo?.let { savePhoto(it, identification.fullPhoto) }

        // Una especie elegida a mano en el buscador: no hay clase del modelo que
        // resolver, la persona ya dijo cual es.
        val chosen = candidate.canonicalSpeciesId
        if (candidate.labelIndex < 0 && chosen != null) {
            observations.recordPhotoObservation(
                speciesId = chosen,
                timestamp = context.epochMillis,
                latitude = context.latitude,
                longitude = context.longitude,
                mediaPath = photoPath,
                notes = null,
                rarityTier = rarityAt(chosen, context.latitude, context.longitude, context.epochMillis),
            )
            return chosen
        }

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
            rarityOf = { speciesId -> rarityAt(speciesId, context.latitude, context.longitude, context.epochMillis) },
        )
        return result?.second
    }

    private suspend fun rarityAt(speciesId: String, latitude: Double?, longitude: Double?, epochMillis: Long): RarityTier =
        if (latitude == null || longitude == null) {
            RarityTier.UNKNOWN
        } else {
            regions.rarityOf(speciesId, latitude, longitude, RegionRepository.monthOf(epochMillis)) ?: RarityTier.UNKNOWN
        }

    /**
     * Guarda la foto en sus dos tamanos (ver [PhotoStorage]) y devuelve la ruta
     * de la miniatura, que es la que se anota en la observacion.
     *
     * Si solo se pudo guardar uno de los dos, vale: la ficha ensena lo que haya.
     */
    internal suspend fun savePhoto(thumbnail: Bitmap, full: Bitmap?): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                photosDirectory.mkdirs()
                // El nombre sale de AHORA y no de la fecha de la foto: dos fotos de
                // la galeria con el mismo EXIF (o la misma, elegida dos veces)
                // compartirian fichero, y borrar un avistamiento dejaria al otro
                // sin foto.
                val target = File(photosDirectory, "photo_${System.currentTimeMillis()}.jpg")
                target.writeJpeg(thumbnail)

                if (full != null && !full.isRecycled) {
                    runCatching {
                        val fullTarget = PhotoStorage.fullSizeFor(target)
                        fullTarget.parentFile?.mkdirs()
                        fullTarget.writeJpeg(full.fittingIn(FULL_PHOTO_MAX_SIDE))
                    }.onFailure { Log.w(TAG, "No se pudo guardar la foto a tamano completo", it) }
                }
                target.absolutePath
            }.onFailure { Log.w(TAG, "No se pudo guardar la foto", it) }.getOrNull()
        }

    private fun File.writeJpeg(bitmap: Bitmap) {
        outputStream().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) { "compress fallo" }
        }
    }

    /** Reduce hasta que el lado largo quepa en [maxSide]. Nunca amplia. */
    private fun Bitmap.fittingIn(maxSide: Int): Bitmap {
        val longSide = maxOf(width, height)
        if (longSide <= maxSide) return this
        val factor = maxSide.toFloat() / longSide
        return scale(
            width = (width * factor).toInt().coerceAtLeast(1),
            height = (height * factor).toInt().coerceAtLeast(1),
        )
    }

    private companion object {
        const val TAG = "ImageObservation"

        /** A 224 px, 85 son ~15 KB por foto. Mas no se nota; menos empieza a verse. */
        const val JPEG_QUALITY = 85

        /**
         * Lado largo de la foto que se guarda para mirarla: unos 300 KB. Es una
         * foto de movil de un pajaro lejano, no un original: por encima de esto
         * solo crece el fichero.
         */
        const val FULL_PHOTO_MAX_SIDE = 1600
    }
}
