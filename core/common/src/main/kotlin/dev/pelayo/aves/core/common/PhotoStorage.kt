package dev.pelayo.aves.core.common

import java.io.File

/**
 * Donde viven las fotos de los avistamientos, y en que dos tamanos.
 *
 * Cada foto se guarda dos veces:
 *
 *  - **La miniatura** (`photos/photo_<t>.jpg`): el recorte cuadrado que vio el
 *    modelo, unos 15 KB. Es la ruta que guarda la observacion, viaja en la copia
 *    de seguridad y es lo unico que tienen las fotos de versiones anteriores.
 *  - **La foto entera** (`photos/full/photo_<t>.jpg`): el encuadre completo a un
 *    tamano que se puede mirar, unos cientos de KB. Queda FUERA de la copia en la
 *    nube: Auto Backup tiene 25 MB de cuota por app y, si se pasa, no falla esa
 *    foto sino la copia entera, coleccion incluida.
 *
 * La entera se localiza por convencion a partir de la miniatura, sin columna
 * nueva en la base de datos: asi una observacion restaurada de una copia (sin
 * foto entera) sigue ensenando su miniatura en vez de un hueco.
 */
object PhotoStorage {

    const val FULL_DIRECTORY = "full"

    /** Donde va (o iria) la foto entera de una miniatura. */
    fun fullSizeFor(thumbnail: File): File =
        File(File(thumbnail.parentFile, FULL_DIRECTORY), thumbnail.name)

    /** Lo mejor que haya en disco para ensenar: la entera si existe; si no, la miniatura. */
    fun bestAvailable(thumbnailPath: String): File {
        val thumbnail = File(thumbnailPath)
        return fullSizeFor(thumbnail).takeIf { it.isFile } ?: thumbnail
    }

    /**
     * Lo que ensenar en pequeno: la miniatura, o la entera si la miniatura se ha
     * perdido. Null si no queda ninguna de las dos en disco.
     */
    fun forThumbnail(thumbnailPath: String): File? {
        val thumbnail = File(thumbnailPath)
        return thumbnail.takeIf { it.isFile } ?: fullSizeFor(thumbnail).takeIf { it.isFile }
    }

    /** Borra los dos tamanos. No pasa nada si alguno ya no esta. */
    fun delete(thumbnailPath: String) {
        val thumbnail = File(thumbnailPath)
        fullSizeFor(thumbnail).delete()
        thumbnail.delete()
    }
}
