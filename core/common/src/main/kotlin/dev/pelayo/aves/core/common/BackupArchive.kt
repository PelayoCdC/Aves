package dev.pelayo.aves.core.common

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** El fichero no es una copia de esta app, o esta danado. El mensaje se puede ensenar. */
class InvalidBackupException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * El formato de la copia de seguridad: un .zip con la base de datos, los
 * recortes, las fotos y un manifiesto.
 *
 * Es la otra mitad de "tu coleccion vive solo en este movil": sin cuenta ni
 * servidor, la unica forma de cambiar de movil sin perderla era la copia
 * automatica de Google, que puede estar apagada y que ademas deja fuera los
 * recortes y las fotos grandes (no caben en sus 25 MB). Esta copia lo lleva
 * todo y el fichero es del usuario.
 *
 * Aqui solo hay ficheros y zip, sin Android, para poder probarlo en la JVM.
 * Cerrar la base, sustituirla y reiniciar la app lo hace quien llama.
 */
object BackupArchive {

    /** Version del formato del .zip, no del esquema de la base. */
    const val FORMAT = 1

    const val MANIFEST = "manifest.properties"
    const val DATABASE = "aves.db"

    /**
     * Lo que dice la copia de si misma.
     *
     * @param schemaVersion version del esquema de Room de la base copiada. Una
     *   copia de una app MAS NUEVA no se puede restaurar: Room no sabe bajar de
     *   version.
     */
    data class Manifest(
        val format: Int,
        val schemaVersion: Int,
        val createdAtMillis: Long,
        val observations: Int,
    )

    /**
     * Escribe la copia en [out].
     *
     * @param media carpetas de ficheros por su nombre dentro del zip, p. ej.
     *   `"clips" to filesDir/clips`. Se guardan con sus subcarpetas.
     * @return cuantos ficheros de medios se guardaron.
     */
    fun write(out: OutputStream, manifest: Manifest, database: File, media: Map<String, File>): Int {
        var mediaCount = 0
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            manifest.toProperties().store(zip, "Copia de seguridad de Aves")
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(DATABASE))
            database.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()

            for ((name, root) in media) {
                if (!root.isDirectory) continue
                root.walkTopDown().filter { it.isFile && !it.name.endsWith(PARTIAL_SUFFIX) }.forEach { file ->
                    val relative = file.relativeTo(root).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry("$name/$relative"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    mediaCount++
                }
            }
        }
        return mediaCount
    }

    /**
     * Descomprime la copia en [staging], que se vacia antes, y la valida.
     *
     * No toca nada de la app: solo prepara. Si algo falla, lo que haya quedado
     * en [staging] es basura que quien llama puede borrar.
     *
     * @throws InvalidBackupException si no es una copia de esta app, le falta
     *   la base o intenta escribir fuera de [staging].
     */
    fun extract(input: InputStream, staging: File): Manifest {
        staging.deleteRecursively()
        staging.mkdirs()
        val root = staging.canonicalFile

        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                // "Zip slip": una entrada llamada ../../algo escribiria fuera de
                // la carpeta. Un .zip ajeno no puede tocar los datos de la app.
                val target = File(root, entry.name).canonicalFile
                if (!target.path.startsWith(root.path + File.separator)) {
                    throw InvalidBackupException("La copia contiene una ruta no valida: ${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
        }

        val manifestFile = File(root, MANIFEST)
        if (!manifestFile.isFile) throw InvalidBackupException("No es una copia de Aves: falta el manifiesto")
        val manifest = try {
            manifestFile.inputStream().use { stream -> Properties().apply { load(stream) } }.toManifest()
        } catch (e: IllegalArgumentException) {
            throw InvalidBackupException("El manifiesto de la copia esta danado", e)
        }
        if (manifest.format > FORMAT) {
            throw InvalidBackupException("La copia es de una version mas nueva de la app")
        }

        val database = File(root, DATABASE)
        if (!database.isFile || !database.hasSqliteHeader()) {
            throw InvalidBackupException("La copia no contiene una base de datos valida")
        }
        return manifest
    }

    private fun File.hasSqliteHeader(): Boolean {
        val header = ByteArray(SQLITE_HEADER.size)
        val read = inputStream().use { it.read(header) }
        return read == header.size && header.contentEquals(SQLITE_HEADER)
    }

    private fun Manifest.toProperties() = Properties().apply {
        setProperty("format", format.toString())
        setProperty("schemaVersion", schemaVersion.toString())
        setProperty("createdAt", createdAtMillis.toString())
        setProperty("observations", observations.toString())
    }

    private fun Properties.toManifest() = Manifest(
        format = requireInt("format"),
        schemaVersion = requireInt("schemaVersion"),
        createdAtMillis = getProperty("createdAt")?.toLongOrNull() ?: 0L,
        observations = getProperty("observations")?.toIntOrNull() ?: 0,
    )

    private fun Properties.requireInt(key: String): Int =
        requireNotNull(getProperty(key)?.toIntOrNull()) { "Falta '$key' en el manifiesto" }

    /** Los recortes a medio escribir no se copian: ver AudioClipWriter. */
    private const val PARTIAL_SUFFIX = ".part"

    /** "SQLite format 3" y un cero: la cabecera de cualquier base SQLite. */
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
}
