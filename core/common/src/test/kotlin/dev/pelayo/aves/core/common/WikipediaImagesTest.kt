package dev.pelayo.aves.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pedir a Commons la imagen del tamano que toca, sin romper la URL. */
class WikipediaImagesTest {

    private val thumb =
        "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a9/Common_Blackbird.jpg/330px-Common_Blackbird.jpg?utm_source=es.wikipedia.org"

    @Test
    fun `cambia el ancho de una miniatura y respeta el resto`() {
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a9/Common_Blackbird.jpg/800px-Common_Blackbird.jpg?utm_source=es.wikipedia.org",
            WikipediaImages.sized(thumb, 800),
        )
        assertTrue(WikipediaImages.isResizable(thumb))
    }

    @Test
    fun `una imagen original, sin ancho en la URL, se deja como esta`() {
        val original = "https://upload.wikimedia.org/wikipedia/commons/a/a9/Common_Blackbird.jpg"
        assertEquals(original, WikipediaImages.sized(original, 800))
        assertFalse(WikipediaImages.isResizable(original))
    }

    @Test
    fun `un nombre de fichero con numeros y px no engana al patron`() {
        // El ancho va justo detras de una barra; "Bird_100px_photo.jpg" no lo es.
        val tricky = "https://upload.wikimedia.org/wikipedia/commons/thumb/b/b1/Bird_100px_photo.jpg/220px-Bird_100px_photo.jpg"
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/commons/thumb/b/b1/Bird_100px_photo.jpg/1600px-Bird_100px_photo.jpg",
            WikipediaImages.sized(tricky, 1600),
        )
    }

    @Test
    fun `no se pide un ancho absurdo`() {
        assertTrue(runCatching { WikipediaImages.sized(thumb, 0) }.isFailure)
    }
}
