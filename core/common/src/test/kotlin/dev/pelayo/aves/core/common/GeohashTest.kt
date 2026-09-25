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

    @Test
    fun `el centro de una celda cae en la misma celda`() {
        for ((lat, lon) in listOf(40.4168 to -3.7038, -33.86 to 151.21, 64.13 to -21.94, 0.0 to 0.0)) {
            val cell = Geohash.encode(lat, lon, 4)
            val (centerLat, centerLon) = Geohash.center(cell)
            assertEquals(cell, Geohash.encode(centerLat, centerLon, 4))
        }
    }

    @Test
    fun `el centro esta a menos de media celda del punto`() {
        // Geohash-4 mide ~0,18 x 0,35 grados: el centro no puede estar mas lejos
        // que la mitad de eso. Es lo que se manda a GBIF en vez de la posicion.
        val (lat, lon) = Geohash.center(Geohash.encode(42.5987, -5.5671, 4))
        assertEquals(42.5987, lat, 0.09)
        assertEquals(-5.5671, lon, 0.18)
    }
}
