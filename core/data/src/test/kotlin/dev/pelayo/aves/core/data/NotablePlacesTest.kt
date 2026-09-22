package dev.pelayo.aves.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los lugares destacados: que sus rectangulos sean rectangulos y esten en España. */
class NotablePlacesTest {

    @Test
    fun `el poligono va en sentido antihorario y cerrado, como pide GBIF`() {
        assertEquals(
            "POLYGON((-6.6500 36.7500,-6.0500 36.7500,-6.0500 37.2500,-6.6500 37.2500,-6.6500 36.7500))",
            NotablePlaces.boxWkt(west = -6.65, south = 36.75, east = -6.05, north = 37.25),
        )
    }

    @Test
    fun `un rectangulo del reves no pasa`() {
        assertTrue(runCatching { NotablePlaces.boxWkt(west = 1.0, south = 0.0, east = 0.0, north = 1.0) }.isFailure)
    }

    @Test
    fun `todos los lugares son lugares, con identificador propio y poligono`() {
        assertTrue(NotablePlaces.all.size >= 20)
        for (place in NotablePlaces.all) {
            assertEquals(ZoneLevel.PLACE, place.level)
            assertTrue(place.name, NotablePlaces.isPlaceId(place.id))
            assertTrue(place.name, place.geometry!!.startsWith("POLYGON(("))
        }
        assertEquals(NotablePlaces.all.size, NotablePlaces.all.map { it.id }.distinct().size)
    }

    @Test
    fun `todos caen dentro de la peninsula y las islas`() {
        // Un signo cambiado en una longitud manda Doñana al Mediterraneo.
        val coordinates = Regex("(-?\\d+\\.\\d+) (-?\\d+\\.\\d+)")
        for (place in NotablePlaces.all) {
            for (match in coordinates.findAll(place.geometry!!)) {
                val lon = match.groupValues[1].toDouble()
                val lat = match.groupValues[2].toDouble()
                assertTrue("${place.name}: lon $lon", lon in -9.5..4.5)
                assertTrue("${place.name}: lat $lat", lat in 35.5..44.0)
            }
        }
    }

    @Test
    fun `se encuentran por identificador`() {
        assertEquals("Doñana", NotablePlaces.byId("PLACE:donana")?.name)
        assertEquals(null, NotablePlaces.byId("ESP.3_1"))
    }
}
