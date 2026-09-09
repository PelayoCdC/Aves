package dev.pelayo.aves.core.media

import java.io.File

/*
 * Logica pura del reproductor de recortes: donde esta el audio y cual suena.
 *
 * Vive separada del composable porque es justo la parte que se puede probar sin
 * Android: decidir si hay algo que reproducir y cual de las tarjetas suena.
 */

/**
 * Localiza el recorte de una observacion, o null si no hay nada que reproducir.
 *
 * Devuelve null en vez de lanzar porque la ausencia del fichero es un caso
 * normal, no un error: los recortes viven en `filesDir` y el usuario puede haber
 * borrado los datos de la app, o la copia de seguridad puede haberlos excluido,
 * mientras la fila de la base de datos sigue ahi con su ruta. La interfaz tiene
 * que poder degradar a "sin grabacion" en lugar de reventar.
 *
 * @param baseDir raiz contra la que resolver rutas relativas. Puede ser null
 *   (previsualizaciones, tests): entonces solo se aceptan rutas absolutas.
 */
fun resolveClipFile(mediaPath: String?, baseDir: File?): File? {
    val path = mediaPath?.trim().orEmpty()
    if (path.isEmpty()) return null

    val raw = File(path)
    // Hoy `mediaPath` se guarda absoluto (lo escribe `ListeningEngine` con
    // `File.absolutePath`), pero se admite relativo para que un cambio futuro en
    // la politica de rutas no deje mudos los recortes ya guardados.
    val candidate = when {
        raw.isAbsolute -> raw
        baseDir != null -> File(baseDir, path)
        else -> return null
    }

    // Se exige tamano mayor que cero ademas de que exista: un .m4a de 0 bytes es
    // una escritura que se quedo a medias, y darselo al reproductor solo produce
    // un error asincrono en vez de un boton honestamente desactivado.
    return candidate.takeIf { it.isFile && it.length() > 0L }
}

/** Atajo de [resolveClipFile] para cuando solo interesa si se puede escuchar. */
fun hasPlayableClip(mediaPath: String?, baseDir: File?): Boolean =
    resolveClipFile(mediaPath, baseDir) != null

/**
 * Que hacer con el reproductor cuando el usuario toca el boton de un recorte.
 *
 * [SWITCH] es la regla de "solo uno sonando": dos cantos solapados no dejan
 * juzgar ninguno de los dos, asi que arrancar uno implica callar el anterior.
 */
enum class PlaybackAction {
    /** No sonaba nada: arrancar este recorte. */
    START,

    /** Sonaba otro recorte: pararlo y arrancar este. */
    SWITCH,

    /** Sonaba este mismo: el boton es un interruptor, asi que se calla. */
    STOP,
}

/**
 * Decide la accion a partir de lo que suena ahora y de lo que se ha pulsado.
 *
 * @param playingKey identificador de la observacion que suena, o null si hay
 *   silencio.
 */
fun playbackActionFor(playingKey: Long?, requestedKey: Long): PlaybackAction = when (playingKey) {
    requestedKey -> PlaybackAction.STOP
    null -> PlaybackAction.START
    else -> PlaybackAction.SWITCH
}

/**
 * Que queda sonando despues del toque.
 *
 * Se expresa como funcion aparte, y no como un `if` dentro del reproductor,
 * porque es la invariante del sistema: el resultado es siempre una sola clave o
 * ninguna, se pulse lo que se pulse y en el orden que sea.
 */
fun playbackAfter(playingKey: Long?, requestedKey: Long): Long? =
    if (playbackActionFor(playingKey, requestedKey) == PlaybackAction.STOP) null else requestedKey
