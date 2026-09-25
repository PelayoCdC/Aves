package dev.pelayo.aves.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val manifest = BackupArchive.Manifest(
        format = BackupArchive.FORMAT,
        schemaVersion = 5,
        createdAtMillis = 1_700_000_000_000L,
        observations = 42,
    )

    @Test
    fun `ida y vuelta conserva la base, los recortes y las fotos`() {
        val files = temp.newFolder("files")
        val database = File(files, "aves.db").apply { writeBytes(sqliteBytes("coleccion")) }
        val clips = File(files, "clips").apply { File(this, "session_3/turdus_merula.m4a").write("canto") }
        val photos = File(files, "photos").apply {
            File(this, "photo_1.jpg").write("miniatura")
            File(this, "full/photo_1.jpg").write("entera")
            File(this, "a_medias.m4a.part").write("basura")
        }

        val zip = ByteArrayOutputStream()
        val media = BackupArchive.write(zip, manifest, database, mapOf("clips" to clips, "photos" to photos))
        assertEquals("Lo que esta a medio escribir no viaja", 3, media)

        val staging = temp.newFolder("staging")
        val read = BackupArchive.extract(ByteArrayInputStream(zip.toByteArray()), staging)

        assertEquals(manifest, read)
        assertTrue(File(staging, BackupArchive.DATABASE).readBytes().contentEquals(database.readBytes()))
        assertEquals("canto", File(staging, "clips/session_3/turdus_merula.m4a").readText())
        assertEquals("entera", File(staging, "photos/full/photo_1.jpg").readText())
        assertFalse(File(staging, "photos/a_medias.m4a.part").exists())
    }

    @Test(expected = InvalidBackupException::class)
    fun `un zip cualquiera no es una copia`() {
        val zip = zipOf("hola.txt" to "no soy una copia".toByteArray())
        BackupArchive.extract(ByteArrayInputStream(zip), temp.newFolder("staging"))
    }

    @Test(expected = InvalidBackupException::class)
    fun `una base que no es SQLite se rechaza`() {
        val zip = zipOf(
            BackupArchive.MANIFEST to "format=1\nschemaVersion=5\n".toByteArray(),
            BackupArchive.DATABASE to "texto".toByteArray(),
        )
        BackupArchive.extract(ByteArrayInputStream(zip), temp.newFolder("staging"))
    }

    @Test
    fun `una entrada que sale de la carpeta se rechaza sin escribir fuera`() {
        val outside = File(temp.root, "fuera.txt")
        val zip = zipOf(
            BackupArchive.MANIFEST to "format=1\nschemaVersion=5\n".toByteArray(),
            "../fuera.txt" to "te pise".toByteArray(),
        )
        try {
            BackupArchive.extract(ByteArrayInputStream(zip), temp.newFolder("staging"))
            throw AssertionError("Tenia que rechazarla")
        } catch (expected: InvalidBackupException) {
            assertFalse(outside.exists())
        }
    }

    @Test(expected = InvalidBackupException::class)
    fun `una copia de un formato mas nuevo no se restaura`() {
        val zip = zipOf(
            BackupArchive.MANIFEST to "format=99\nschemaVersion=5\n".toByteArray(),
            BackupArchive.DATABASE to sqliteBytes("x"),
        )
        BackupArchive.extract(ByteArrayInputStream(zip), temp.newFolder("staging"))
    }

    private fun File.write(text: String) {
        parentFile?.mkdirs()
        writeText(text)
    }

    private fun sqliteBytes(body: String): ByteArray =
        "SQLite format 3".toByteArray() + byteArrayOf(0) + body.toByteArray()

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
