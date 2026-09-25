package dev.pelayo.aves.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class CancellationTest {

    @Test
    fun `atrapa los fallos normales`() {
        val result = runSuspendCatching { error("boom") }
        assertTrue(result.isFailure)
    }

    @Test
    fun `deja pasar el valor`() {
        assertEquals(3, runSuspendCatching { 1 + 2 }.getOrNull())
    }

    @Test
    fun `no se traga la cancelacion`() {
        try {
            runSuspendCatching { throw CancellationException("parar") }
            fail("La cancelacion tenia que salir")
        } catch (expected: CancellationException) {
            // Es justo lo que runCatching no hace.
        }
    }
}
