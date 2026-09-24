package dev.pelayo.aves.di

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.pelayo.aves.feature.camera.loadGalleryImage
import dev.pelayo.aves.feature.pokedex.SavedPhoto
import kotlinx.coroutines.CancellationException

/**
 * Una foto de la galeria que el usuario sube DESDE LA FICHA de una especie: no
 * hay que clasificarla (ya sabe cual es), solo guardarla en sus dos tamanos y
 * leerle la fecha.
 *
 * Reutiliza la carga de la camara (orientacion EXIF, recorte cuadrado a 224 px
 * para la miniatura) y el guardado de [ImageObservationRecorder], para que una
 * foto subida asi sea indistinguible de una identificada con la camara.
 */
class GalleryPhotoSaver(
    private val context: Context,
    private val recorder: ImageObservationRecorder,
) {
    suspend fun save(uri: Uri): SavedPhoto? = try {
        val image = context.loadGalleryImage(uri, THUMBNAIL_PX)
        val path = recorder.savePhoto(thumbnail = image.modelInput, full = image.display)
        path?.let { SavedPhoto(mediaPath = it, takenAtMillis = image.takenAtMillis) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo guardar la foto de la galeria", e)
        null
    }

    private companion object {
        const val TAG = "GalleryPhotoSaver"

        /** La miniatura del mismo tamano que la que ve el modelo, por coherencia. */
        const val THUMBNAIL_PX = 224
    }
}
