package dev.pelayo.aves.core.designsystem.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfidenceTest {

    private val space = Char(0x202F)

    @Test
    fun `redondea a entero con el signo separado`() {
        assertEquals("87$space%", formatConfidence(0.8671f))
    }

    @Test
    fun `satura en los extremos y no revienta con NaN`() {
        assertEquals("100$space%", formatConfidence(1.7f))
        assertEquals("0$space%", formatConfidence(-0.2f))
        assertEquals("0$space%", formatConfidence(Float.NaN))
    }
}
