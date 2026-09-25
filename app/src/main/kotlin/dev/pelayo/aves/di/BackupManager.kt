package dev.pelayo.aves.di

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import dev.pelayo.aves.core.common.BackupArchive
import dev.pelayo.aves.core.common.InvalidBackupException
import dev.pelayo.aves.core.database.AvesDatabase
import dev.pelayo.aves.core.database.CatalogSeeder
import dev.pelayo.aves.core.listening.ListeningService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

/**
 * Copia de seguridad completa: exportar a un .zip del usuario y restaurar desde
 * uno. El formato vive en [BackupArchive]; aqui esta lo que toca Android: sacar
 * una foto coherente de la base, cerrar Room, cambiar los ficheros y reiniciar.
 *
 * Restaurar SUSTITUYE la coleccion actual, no la mezcla: mezclar dos bases con
 * sesiones, candidatos y claves propias es una fuente de duplicados que no
 * compensa para el caso real, que es cambiar de movil.
 */
class BackupManager(
    private val context: Context,
    private val database: () -> AvesDatabase,
    private val clipsDirectory: File,
    private val photosDirectory: File,
) {
    /** Lo que salio de una exportacion, para decirselo al usuario. */
    data class ExportResult(val observations: Int, val mediaFiles: Int)

    /** Una copia ya descomprimida y validada, lista para sustituir a la actual. */
    class StagedBackup internal constructor(
        val manifest: BackupArchive.Manifest,
        internal val directory: File,
    )

    suspend fun export(target: Uri): ExportResult = withContext(Dispatchers.IO) {
        val snapshot = File(context.cacheDir, SNAPSHOT_NAME)
        try {
            snapshotDatabase(snapshot)
            val db = database().openHelper.readableDatabase
            val observations = db.query("SELECT COUNT(*) FROM observations").use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
            val manifest = BackupArchive.Manifest(
                format = BackupArchive.FORMAT,
                schemaVersion = db.version,
                createdAtMillis = System.currentTimeMillis(),
                observations = observations,
            )
            val media = context.contentResolver.openOutputStream(target, "wt")?.use { out ->
                BackupArchive.write(out, manifest, snapshot, mediaDirectories())
            } ?: throw IOException("No se pudo abrir el destino")
            ExportResult(observations, media)
        } finally {
            snapshot.delete()
        }
    }

    /**
     * Descomprime y valida la copia sin tocar nada de la app. La restauracion
     * de verdad ([restoreAndRestart]) va aparte para poder preguntar antes.
     */
    suspend fun stage(source: Uri): StagedBackup = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, STAGING_NAME)
        try {
            val manifest = context.contentResolver.openInputStream(source)?.use { input ->
                BackupArchive.extract(input, staging)
            } ?: throw IOException("No se pudo abrir la copia")
            val current = database().openHelper.readableDatabase.version
            if (manifest.schemaVersion > current) {
                throw InvalidBackupException("La copia es de una version mas nueva de la app")
            }
            StagedBackup(manifest, staging)
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }

    fun discard(staged: StagedBackup) {
        staged.directory.deleteRecursively()
    }

    /**
     * Sustituye la base, los recortes y las fotos por los de [staged] y
     * reinicia la app.
     *
     * Lo actual no se borra hasta que lo nuevo esta en su sitio: se aparta a
     * una carpeta, y si algo falla a mitad se devuelve. Perder la coleccion por
     * un fallo al restaurar seria peor que no haber restaurado.
     *
     * Room se abre con la base nueva al arrancar otra vez, y ahi hace las
     * migraciones que falten (una copia de una version anterior) y
     * [MediaHousekeeping] reescribe las rutas del otro movil.
     */
    suspend fun restoreAndRestart(staged: StagedBackup) = withContext(Dispatchers.IO) {
        check(!ListeningService.state.value.isRunning) { "Hay una escucha en curso" }

        val databaseFile = context.getDatabasePath(AvesDatabase.NAME)
        val aside = File(context.noBackupFilesDir, ASIDE_NAME).apply {
            deleteRecursively()
            mkdirs()
        }
        val moves = listOf(
            databaseFile to File(staged.directory, BackupArchive.DATABASE),
            clipsDirectory to File(staged.directory, clipsDirectory.name),
            photosDirectory to File(staged.directory, photosDirectory.name),
        )

        database().close()
        val setAside = ArrayList<Pair<File, File>>()
        try {
            // Los ficheros auxiliares de SQLite se borran: son de la base vieja.
            for (suffix in SQLITE_SIDE_FILES) File(databaseFile.path + suffix).delete()
            for ((current, _) in moves) {
                if (!current.exists()) continue
                val parked = File(aside, current.name)
                move(current, parked)
                setAside += current to parked
            }
            for ((current, restored) in moves) {
                if (restored.exists()) move(restored, current) else if (current != databaseFile) current.mkdirs()
            }
        } catch (e: Exception) {
            // Con Room ya cerrado la app no puede seguir como si nada: se
            // devuelve lo que habia y se reinicia igual, con la coleccion de
            // antes intacta.
            Log.e(TAG, "Fallo la restauracion; se devuelve lo que habia y se reinicia", e)
            for ((current, parked) in setAside) {
                current.deleteRecursively()
                runCatching { move(parked, current) }
            }
            staged.directory.deleteRecursively()
            restart()
        }

        aside.deleteRecursively()
        staged.directory.deleteRecursively()
        // El catalogo de la copia puede ser de otra version: que se vuelva a
        // comparar con el de esta app en el siguiente arranque.
        context.getSharedPreferences(CatalogSeeder.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        restart()
    }

    /**
     * Una copia coherente de la base aunque la app este escribiendo.
     *
     * `VACUUM INTO` (Android 11+) la saca de una vez y ya compactada. Antes de
     * eso no existe: se vuelca el diario a la base y se copia el fichero.
     */
    private fun snapshotDatabase(target: File) {
        target.delete()
        val db = database().openHelper.writableDatabase
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            db.execSQL("VACUUM INTO '${target.path.replace("'", "''")}'")
        } else {
            db.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
            context.getDatabasePath(AvesDatabase.NAME).copyTo(target, overwrite = true)
        }
    }

    private fun mediaDirectories(): Map<String, File> = mapOf(
        clipsDirectory.name to clipsDirectory,
        photosDirectory.name to photosDirectory,
    )

    /** Renombra si se puede (mismo sistema de ficheros); si no, copia y borra. */
    private fun move(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) return
        if (from.isDirectory) from.copyRecursively(to, overwrite = true) else from.copyTo(to, overwrite = true)
        from.deleteRecursively()
    }

    private fun restart() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val component = launch?.component
        if (component != null) context.startActivity(Intent.makeRestartActivityTask(component))
        // Room, los repositorios y las caches en memoria son de la base vieja:
        // lo unico seguro es un proceso nuevo.
        exitProcess(0)
    }

    private companion object {
        const val TAG = "BackupManager"
        const val SNAPSHOT_NAME = "backup-snapshot.db"
        const val STAGING_NAME = "backup-restore"
        const val ASIDE_NAME = "backup-previous"
        val SQLITE_SIDE_FILES = listOf("-wal", "-shm", "-journal")
    }
}
