package dev.pelayo.aves.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** La convencion de los dos tamanos de foto, que no esta escrita en ninguna tabla. */
class PhotoStorageTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun photos(): File = folder.newFolder("photos")

    private fun File.touch(): File = apply {
        parentFile?.mkdirs()
        writeText("jpeg")
    }

    @Test
    fun `la foto entera vive en full, con el mismo nombre que la miniatura`() {
        val thumbnail = File("/data/photos/photo_1.jpg")
        assertEquals(File("/data/photos/full/photo_1.jpg"), PhotoStorage.fullSizeFor(thumbnail))
    }

    @Test
    fun `para mirarla se prefiere la entera`() {
        val thumbnail = File(photos(), "photo_1.jpg").touch()
        val full = PhotoStorage.fullSizeFor(thumbnail).touch()

        assertEquals(full, PhotoStorage.bestAvailable(thumbnail.path))
    }

    @Test
    fun `una foto de antes, o restaurada de una copia, ensena su miniatura`() {
        // Las versiones anteriores solo guardaban la miniatura, y la entera no
        // viaja en la copia de seguridad: ninguna de las dos puede dejar un hueco.
        val thumbnail = File(photos(), "photo_1.jpg").touch()

        assertEquals(thumbnail, PhotoStorage.bestAvailable(thumbnail.path))
        assertEquals(thumbnail, PhotoStorage.forThumbnail(thumbnail.path))
    }

    @Test
    fun `si se pierde la miniatura vale la entera`() {
        val thumbnail = File(photos(), "photo_1.jpg")
        val full = PhotoStorage.fullSizeFor(thumbnail).touch()

        assertEquals(full, PhotoStorage.forThumbnail(thumbnail.path))
    }

    @Test
    fun `sin ninguna de las dos no hay nada que ensenar`() {
        assertNull(PhotoStorage.forThumbnail(File(photos(), "photo_1.jpg").path))
    }

    @Test
    fun `borrar se lleva los dos tamanos y no toca las demas fotos`() {
        val directory = photos()
        val thumbnail = File(directory, "photo_1.jpg").touch()
        val full = PhotoStorage.fullSizeFor(thumbnail).touch()
        val other = File(directory, "photo_2.jpg").touch()
        val otherFull = PhotoStorage.fullSizeFor(other).touch()

        PhotoStorage.delete(thumbnail.path)

        assertFalse(thumbnail.exists())
        assertFalse(full.exists())
        assertEquals(listOf(true, true), listOf(other.exists(), otherFull.exists()))
    }

    @Test
    fun `borrar una foto que ya no esta no falla`() {
        PhotoStorage.delete(File(photos(), "photo_9.jpg").path)
    }
}
