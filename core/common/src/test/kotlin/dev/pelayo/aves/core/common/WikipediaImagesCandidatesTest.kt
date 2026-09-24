package dev.pelayo.aves.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

/** Que se pide, y en que orden, para que la cabecera de la ficha no se quede gris. */
class WikipediaImagesCandidatesTest {

    private val thumb = "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a9/Bird.jpg/330px-Bird.jpg"

    @Test
    fun `primero el tamano deseado, luego la URL tal cual, luego una pequena`() {
        assertEquals(
            listOf(
                "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a9/Bird.jpg/800px-Bird.jpg",
                thumb,
                "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a9/Bird.jpg/320px-Bird.jpg",
            ),
            WikipediaImages.candidates(thumb, 800),
        )
    }

    @Test
    fun `una imagen original, sin tamano en la URL, solo se pide una vez`() {
        val original = "https://upload.wikimedia.org/wikipedia/commons/a/a9/Bird.jpg"
        assertEquals(listOf(original), WikipediaImages.candidates(original, 800))
    }

    @Test
    fun `si lo guardado ya era el tamano deseado, no se repite`() {
        val big = "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a9/Bird.jpg/800px-Bird.jpg"
        assertEquals(
            listOf(big, "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a9/Bird.jpg/320px-Bird.jpg"),
            WikipediaImages.candidates(big, 800),
        )
    }
}
