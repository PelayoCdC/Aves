package dev.pelayo.aves.feature.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Una foto elegida de la galeria, ya lista para las dos cosas que se hacen con
 * ella: ensenarla y clasificarla.
 *
 * @param display la foto entera, derecha, a un tamano razonable para pantalla.
 * @param modelInput el recorte cuadrado central a la resolucion del modelo.
 * @param takenAtMillis cuando se hizo, segun su EXIF. Null si no lo dice.
 */
internal class GalleryImage(
    val display: Bitmap,
    val modelInput: Bitmap,
    val takenAtMillis: Long?,
)

/** Lado largo maximo de la imagen que se ensena. Mas no se ve y si se paga en memoria. */
private const val DISPLAY_TARGET_PX = 1280

/**
 * Carga una foto de la galeria.
 *
 * Dos diferencias con la foto de la camara que conviene no "unificar":
 *
 *  - **La orientacion viene en el EXIF**, no en un `rotationDegrees`. Una foto
 *    hecha en vertical se guarda tumbada con una etiqueta que dice "girame"; si
 *    no se lee, el modelo recibe un ave de lado, que no da ningun error: solo
 *    peores candidatos.
 *  - **La fecha es la de la foto, no la de ahora.** Alguien que sube hoy una
 *    foto de sus vacaciones vio ese pajaro entonces, y asi tiene que constar.
 */
internal suspend fun Context.loadGalleryImage(uri: Uri, inputSize: Int): GalleryImage =
    withContext(Dispatchers.IO) {
        val resolver = contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: error("No se pudo abrir la imagen")
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "El fichero no es una imagen que se pueda leer" }

        val options = BitmapFactory.Options().apply {
            // Se submuestrea contra el lado LARGO: lo que se quiere acotar es la
            // memoria de la imagen en pantalla, y el recorte del modelo sale de ella.
            inSampleSize = inSampleSizeFor(
                width = maxOf(bounds.outWidth, bounds.outHeight),
                height = maxOf(bounds.outWidth, bounds.outHeight),
                targetSize = DISPLAY_TARGET_PX,
            )
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("La imagen no se pudo decodificar")

        val exif = runCatching { resolver.openInputStream(uri)?.use { ExifInterface(it) } }.getOrNull()
        val rotation = exifRotationDegrees(
            exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                ?: ExifInterface.ORIENTATION_NORMAL,
        )

        val display = if (isRotationNeeded(rotation)) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }

        GalleryImage(
            display = display,
            modelInput = squareModelInput(display, inputSize),
            takenAtMillis = parseExifDate(exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)),
        )
    }

/**
 * El recorte cuadrado central de [source] a [targetSize], SIN reciclar el
 * original: a diferencia de la foto de la camara, esta se sigue ensenando.
 */
private fun squareModelInput(source: Bitmap, targetSize: Int): Bitmap {
    val crop = centerSquareCrop(source.width, source.height)
    val cropped = Bitmap.createBitmap(source, crop.left, crop.top, crop.size, crop.size)
    val scaled = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
    if (cropped !== source && cropped !== scaled) cropped.recycle()
    // `createBitmap` y `createScaledBitmap` devuelven el MISMO objeto cuando no
    // hay nada que hacer. El modelo y la pantalla no pueden compartir bitmap:
    // cada uno lo recicla cuando le toca.
    return if (scaled === source) source.copy(source.config ?: Bitmap.Config.ARGB_8888, false) else scaled
}

/** Grados en sentido horario que pide la etiqueta de orientacion del EXIF. */
internal fun exifRotationDegrees(orientation: Int): Int = when (orientation) {
    ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
    ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
    ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
    else -> 0
}

/**
 * La fecha del EXIF (`2026:04:15 07:32:10`, en hora local y sin zona) a
 * milisegundos. Null si falta o no se entiende: mejor no tener fecha que
 * inventarse una.
 */
internal fun parseExifDate(value: String?): Long? {
    val text = value?.trim().orEmpty()
    if (text.isEmpty() || text.startsWith("0000")) return null
    val format = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT).apply { isLenient = false }
    val position = ParsePosition(0)
    val parsed = format.parse(text, position) ?: return null
    if (position.index != text.length) return null
    return parsed.time.takeIf { it > 0L }
}
