package dev.pelayo.aves.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Que pedir a Commons para que la foto cargue: miniatura estandar u original, nunca anchos inventados. */
class WikipediaImagesTest {

    private val thumb =
        "https://thumb.wikimedia.org/wikipedia/commons/thumb/6/6e/Crabier_chevelu.jpg/330px-Crabier_chevelu.jpg?utm_source=es.wikipedia.org&utm_campaign=api&utm_content=thumbnail"

    @Test
    fun `el original sale de la miniatura, en upload y sin la query`() {
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/commons/6/6e/Crabier_chevelu.jpg",
            WikipediaImages.original(thumb),
        )
    }

    @Test
    fun `la miniatura estandar se deja como viene`() {
        assertEquals(thumb, WikipediaImages.thumbnail(thumb))
        assertTrue(WikipediaImages.isThumbnail(thumb))
    }

    @Test
    fun `una miniatura de un ancho que Commons ya no sirve vuelve a 330`() {
        // Las URLs que se guardaron a 800 px cuando aun se pensaba que se podia.
        val broken = "https://thumb.wikimedia.org/wikipedia/commons/thumb/6/6e/Crabier_chevelu.jpg/800px-Crabier_chevelu.jpg?utm_source=x"
        assertEquals(
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/6/6e/Crabier_chevelu.jpg/330px-Crabier_chevelu.jpg",
            WikipediaImages.thumbnail(broken),
        )
    }

    @Test
    fun `una imagen original no es una miniatura`() {
        val original = "https://upload.wikimedia.org/wikipedia/commons/6/6e/Crabier_chevelu.jpg"
        assertNull(WikipediaImages.original(original))
        assertEquals(original, WikipediaImages.thumbnail(original))
        assertFalse(WikipediaImages.isThumbnail(original))
    }

    @Test
    fun `las miniaturas de SVG y TIFF llevan prefijo antes del ancho y se entienden igual`() {
        val svg = "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Bird.svg/330px-Bird.svg.png"
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/a/ab/Bird.svg", WikipediaImages.original(svg))
        val tif = "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Bird.tif/lossy-page1-330px-Bird.tif.jpg"
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/a/ab/Bird.tif", WikipediaImages.original(tif))
    }

    @Test
    fun `la cabecera prueba la miniatura y luego el original, el visor al reves`() {
        val original = "https://upload.wikimedia.org/wikipedia/commons/6/6e/Crabier_chevelu.jpg"
        assertEquals(listOf(thumb, original), WikipediaImages.headerCandidates(thumb))
        assertEquals(listOf(original, thumb), WikipediaImages.viewerCandidates(thumb))
    }

    @Test
    fun `con un original guardado solo hay una cosa que probar`() {
        val original = "https://upload.wikimedia.org/wikipedia/commons/6/6e/Crabier_chevelu.jpg"
        assertEquals(listOf(original), WikipediaImages.headerCandidates(original))
        assertEquals(listOf(original), WikipediaImages.viewerCandidates(original))
    }
}
