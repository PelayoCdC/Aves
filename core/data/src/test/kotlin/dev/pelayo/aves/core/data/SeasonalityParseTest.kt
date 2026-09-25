package dev.pelayo.aves.core.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeasonalityParseTest {

    @Test
    fun `doce meses separados por comas`() {
        assertArrayEquals(
            intArrayOf(0, 0, 3, 40, 120, 130, 90, 60, 20, 2, 0, 0),
            SeasonalityRepository.parse("0,0,3,40,120,130,90,60,20,2,0,0"),
        )
    }

    @Test
    fun `una cache danada no se inventa meses`() {
        assertNull(SeasonalityRepository.parse("1,2,3"))
        assertNull(SeasonalityRepository.parse(""))
    }
}
