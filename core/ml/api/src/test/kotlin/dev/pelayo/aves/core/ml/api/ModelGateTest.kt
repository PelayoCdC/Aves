package dev.pelayo.aves.core.ml.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * El fallo que motiva [ModelGate] era nativo (un interprete liberado con un
 * `run()` a medias), y ese no se puede reproducir en la JVM. Lo que si se puede
 * comprobar es la regla: nadie libera mientras otro infiere, y se libera una
 * sola vez.
 */
class ModelGateTest {

    @Test
    fun `cerrar sin nadie dentro libera en el acto`() {
        var releases = 0
        val gate = ModelGate { releases++ }

        gate.close()

        assertEquals(1, releases)
        assertTrue(gate.isClosed)
    }

    @Test
    fun `cerrar durante una inferencia espera a que termine`() = runTest {
        var releases = 0
        val gate = ModelGate { releases++ }
        val inside = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()

        val inference = launch {
            gate.use {
                inside.complete(Unit)
                proceed.await()
                // Si close() hubiera liberado ya, aqui se usaria memoria nativa
                // liberada. Es exactamente lo que no puede pasar.
                assertEquals(0, releases)
            }
        }

        inside.await()
        gate.close()
        assertEquals("No se libera con una inferencia en curso", 0, releases)

        proceed.complete(Unit)
        inference.join()
        assertEquals("La inferencia libera al salir", 1, releases)
    }

    @Test
    fun `usar despues de cerrar falla limpio`() = runTest {
        val gate = ModelGate {}
        gate.close()

        try {
            gate.use { fail("No deberia ejecutarse") }
            fail("Deberia haber lanzado IllegalStateException")
        } catch (expected: IllegalStateException) {
            // Un error normal que la pantalla puede ensenar.
        }
    }

    @Test
    fun `cerrar dos veces libera una`() {
        var releases = 0
        val gate = ModelGate { releases++ }

        gate.close()
        gate.close()

        assertEquals(1, releases)
    }
}
