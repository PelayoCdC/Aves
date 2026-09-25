package dev.pelayo.aves.di

import android.util.Log
import dev.pelayo.aves.core.common.MediaFiles
import dev.pelayo.aves.core.data.ObservationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pone en orden los recortes y las fotos al arrancar, en segundo plano.
 *
 * Dos cosas, en este orden:
 *
 *  1. **Rutas que vienen de otro sitio.** Las observaciones guardan rutas
 *     absolutas; tras restaurar una copia en otro perfil o en otro movil, la
 *     raiz cambia y ninguna apunta a nada. Se reescriben a la raiz actual (ver
 *     [MediaFiles.relocate]).
 *  2. **Ficheros que ya no nombra nadie.** Una foto guardada justo antes de un
 *     fallo, un `.part` de un recorte que no llego a terminar, el recorte de
 *     una observacion que se borro por otro camino. Sin barrido, el almacen
 *     solo crecia. Va DESPUES de reescribir las rutas: al reves, se borraria
 *     como huerfano lo que solo estaba mal apuntado.
 *
 * Los recortes de observaciones descartadas NO se borran: descartar se puede
 * deshacer semanas despues desde la ficha de la sesion, y sin su audio la
 * observacion ya no se podria volver a juzgar.
 */
class MediaHousekeeping(
    private val filesDirectory: File,
    private val mediaDirectories: List<File>,
    private val observations: ObservationRepository,
) {
    suspend fun run() = withContext(Dispatchers.IO) {
        val refs = observations.mediaRefs()

        var relocated = 0
        val referenced = HashSet<String>(refs.size)
        for (ref in refs) {
            val moved = MediaFiles.relocate(ref.mediaPath, filesDirectory.absolutePath)
            if (moved != null && File(moved).exists()) {
                observations.updateMediaPath(ref.id, moved)
                referenced += File(moved).canonicalPath
                relocated++
            } else {
                referenced += File(ref.mediaPath).canonicalPath
            }
        }

        // Rutas canonicas a los dos lados: `/data/data/...` y `/data/user/0/...`
        // son el mismo sitio, y una comparacion de texto que no lo supiera
        // borraria recortes buenos.
        val candidates = mediaDirectories.flatMap { directory ->
            directory.walkTopDown().filter { it.isFile }.map { it.canonicalFile }.toList()
        }
        if (refs.isEmpty() && candidates.isNotEmpty()) {
            // Una base vacia con ficheros en disco es una restauracion a medias o
            // una lectura fallida, no una coleccion sin nada: no se borra.
            Log.w(TAG, "La base no nombra ningun fichero y hay ${candidates.size}; no se barre")
            return@withContext
        }
        val orphans = MediaFiles.orphans(
            candidates = candidates,
            referenced = referenced,
            nowMillis = System.currentTimeMillis(),
            minAgeMillis = MIN_ORPHAN_AGE_MILLIS,
        )
        if (orphans.size > MAX_ORPHAN_SHARE * candidates.size && orphans.size > MIN_SUSPICIOUS_COUNT) {
            // Mas de la mitad huerfana no es desgaste normal, es un fallo (una
            // ruta mal reescrita, otra raiz). Mejor ocupar sitio que perder audio.
            Log.w(TAG, "${orphans.size} de ${candidates.size} ficheros sin referencia; no se barre")
            return@withContext
        }
        val deleted = orphans.count { it.delete() }

        if (relocated > 0 || deleted > 0) {
            Log.i(TAG, "Mantenimiento: $relocated rutas reubicadas, $deleted ficheros huerfanos borrados")
        }
    }

    private companion object {
        const val TAG = "MediaHousekeeping"

        /**
         * Una hora. Mucho mas de lo que tarda cualquier escritura (la foto se
         * guarda un instante antes que su fila), y poco para lo que importa:
         * que el almacen no crezca sin limite.
         */
        const val MIN_ORPHAN_AGE_MILLIS = 60L * 60L * 1000L

        /** Por encima de esta parte huerfana, se sospecha de un fallo y no se borra. */
        const val MAX_ORPHAN_SHARE = 0.5

        /** Con pocos ficheros la proporcion no dice nada. */
        const val MIN_SUSPICIOUS_COUNT = 20
    }
}
