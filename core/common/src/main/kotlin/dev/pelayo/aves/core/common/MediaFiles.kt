package dev.pelayo.aves.core.common

import java.io.File

/**
 * Mantenimiento de los ficheros de la coleccion (recortes y fotos): rutas que
 * vienen de otro sitio y ficheros que ya no nombra nadie.
 *
 * Es aritmetica de rutas y nada mas, para poder probarla en la JVM; quien lee
 * la base y borra es la app.
 */
object MediaFiles {

    /** El tramo que separa la raiz de los datos de la app de lo que es nuestro. */
    private const val FILES_SEGMENT = "/files/"

    /**
     * La ruta equivalente de [stored] dentro de [filesDir], o null si no hace
     * falta tocarla.
     *
     * Las observaciones guardan rutas absolutas. Tras restaurar una copia en
     * otro perfil (trabajo, otro usuario de Android) o tras una restauracion de
     * Auto Backup, la raiz cambia (`/data/user/10/...` en vez de
     * `/data/user/0/...`) y todas las rutas apuntan a un sitio que no existe.
     * Lo que va detras de `/files/` es siempre nuestro (`clips/...`,
     * `photos/...`), asi que basta con cambiar la raiz.
     */
    fun relocate(stored: String, filesDir: String): String? {
        val root = filesDir.trimEnd('/') + "/"
        if (stored.startsWith(root)) return null
        val cut = stored.lastIndexOf(FILES_SEGMENT)
        if (cut < 0) return null
        val relative = stored.substring(cut + FILES_SEGMENT.length)
        if (relative.isEmpty()) return null
        return root + relative
    }

    /**
     * Los ficheros de [candidates] que ninguna observacion nombra y que ya
     * tienen cierta edad.
     *
     * La edad minima evita la carrera con una escritura en curso: la foto se
     * guarda en disco un instante ANTES de que exista su fila, y un recorte
     * a medio codificar (`.part`) es de una deteccion que esta entrando ahora.
     *
     * @param referenced rutas absolutas que aparecen en la base. Para una foto,
     *   su miniatura cubre tambien su tamano entero (ver [PhotoStorage]).
     */
    fun orphans(
        candidates: List<File>,
        referenced: Set<String>,
        nowMillis: Long,
        minAgeMillis: Long,
    ): List<File> {
        val keep = HashSet<String>(referenced.size * 2)
        for (path in referenced) {
            keep += File(path).path
            keep += PhotoStorage.fullSizeFor(File(path)).path
        }
        return candidates.filter { file ->
            file.path !in keep && nowMillis - file.lastModified() >= minAgeMillis
        }
    }
}
