package dev.pelayo.aves.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MediaFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `una ruta de otro perfil pasa a la raiz actual`() {
        assertEquals(
            "/data/user/10/dev.pelayo.aves/files/clips/session_3/sp.m4a",
            MediaFiles.relocate(
                stored = "/data/user/0/dev.pelayo.aves/files/clips/session_3/sp.m4a",
                filesDir = "/data/user/10/dev.pelayo.aves/files",
            ),
        )
    }

    @Test
    fun `una ruta que ya esta en su sitio no se toca`() {
        assertNull(
            MediaFiles.relocate(
                stored = "/data/user/0/dev.pelayo.aves/files/photos/photo_1.jpg",
                filesDir = "/data/user/0/dev.pelayo.aves/files/",
            ),
        )
    }

    @Test
    fun `una ruta sin files no se inventa`() {
        assertNull(MediaFiles.relocate("/sdcard/Music/canto.m4a", "/data/user/0/dev.pelayo.aves/files"))
    }

    @Test
    fun `huerfanos son los que nadie nombra y ya tienen edad`() {
        val now = 10_000_000L
        val old = now - 2 * HOUR
        val clips = temp.newFolder("clips")
        val referenced = File(clips, "a.m4a").aged(old)
        val orphan = File(clips, "b.m4a").aged(old)
        val fresh = File(clips, "c.m4a.part").aged(now - 1_000)

        val result = MediaFiles.orphans(
            candidates = listOf(referenced, orphan, fresh),
            referenced = setOf(referenced.path),
            nowMillis = now,
            minAgeMillis = HOUR,
        )

        assertEquals(listOf(orphan), result)
    }

    @Test
    fun `la foto entera va con su miniatura`() {
        val now = 10_000_000L
        val photos = temp.newFolder("photos")
        val thumbnail = File(photos, "photo_1.jpg").aged(now - 2 * HOUR)
        val full = PhotoStorage.fullSizeFor(thumbnail).aged(now - 2 * HOUR)

        val result = MediaFiles.orphans(
            candidates = listOf(thumbnail, full),
            referenced = setOf(thumbnail.path),
            nowMillis = now,
            minAgeMillis = HOUR,
        )

        assertEquals(emptyList<File>(), result)
    }

    private fun File.aged(lastModified: Long): File = apply {
        parentFile?.mkdirs()
        writeText("x")
        setLastModified(lastModified)
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
