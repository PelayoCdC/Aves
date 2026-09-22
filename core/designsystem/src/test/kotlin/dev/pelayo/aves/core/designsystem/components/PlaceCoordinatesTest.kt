package dev.pelayo.aves.core.designsystem.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Las coordenadas escritas a mano en el selector de lugar. */
class PlaceCoordinatesTest {

    @Test
    fun `acepta coma decimal, que es lo que da el teclado español`() {
        val place = parseCoordinates("43,36", "-5,85")!!
        assertEquals(43.36, place.latitude, 1e-9)
        assertEquals(-5.85, place.longitude, 1e-9)
        assertEquals("43,36, -5,85", place.name)
    }

    @Test
    fun `acepta punto decimal y espacios`() {
        assertEquals(37.0, parseCoordinates(" 37.0 ", "-6.45")!!.latitude, 1e-9)
    }

    @Test
    fun `rechaza lo que no es un punto de la Tierra`() {
        assertNull(parseCoordinates("91", "0"))
        assertNull(parseCoordinates("0", "181"))
        assertNull(parseCoordinates("norte", "0"))
        assertNull(parseCoordinates("", ""))
    }

    @Test
    fun `el nombre lleva dos decimales y el signo bien puesto`() {
        assertEquals("-0,50, 0,05", formatCoordinates(-0.5, 0.049))
        assertEquals("40,70, 0,75", formatCoordinates(40.7, 0.75))
    }
}
