package dev.pelayo.aves.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GeohashTest {

    @Test
    fun `codifica una posicion conocida`() {
        // Valor de referencia clasico del algoritmo original.
        assertEquals("ezs42", Geohash.encode(42.6, -5.6, precision = 5))
    }

    @Test
    fun `precision 4 agrupa puntos de la misma ciudad`() {
        // Dos puntos separados unos pocos km en Oviedo deben caer en la misma celda.
        val a = Geohash.encode(43.3619, -5.8494, precision = 4)
        val b = Geohash.encode(43.3700, -5.8600, precision = 4)
        assertEquals(a, b)
    }

    @Test
    fun `precision 4 separa ciudades distintas`() {
        val oviedo = Geohash.encode(43.3619, -5.8494, precision = 4)
        val madrid = Geohash.encode(40.4168, -3.7038, precision = 4)
        assertNotEquals(oviedo, madrid)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rechaza latitudes imposibles`() {
        Geohash.encode(91.0, 0.0)
    }
}
