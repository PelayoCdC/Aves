package dev.pelayo.aves.di

import dev.pelayo.aves.di.ObservationExporter.Companion.csvField
import org.junit.Assert.assertEquals
import org.junit.Test

class ObservationExporterTest {

    @Test
    fun `un campo normal sale tal cual`() {
        assertEquals("Turdus merula", csvField("Turdus merula"))
    }

    @Test
    fun `comas y comillas van entre comillas, con las internas duplicadas`() {
        assertEquals("\"cantaba \"\"fuerte\"\", cerca\"", csvField("cantaba \"fuerte\", cerca"))
    }

    @Test
    fun `una nota que parece formula no se ejecuta`() {
        assertEquals("'=SUM(A1:A9)", csvField("=SUM(A1:A9)"))
        assertEquals("'+34 600", csvField("+34 600"))
        assertEquals("'@alguien", csvField("@alguien"))
    }

    @Test
    fun `una longitud negativa no se toca`() {
        assertEquals("-5.8494", csvField("-5.8494"))
    }

    @Test
    fun `un campo vacio sigue vacio`() {
        assertEquals("", csvField(""))
    }
}
