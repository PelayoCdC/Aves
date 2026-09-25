package dev.pelayo.aves.core.listening

import dev.pelayo.aves.core.ml.api.ClassificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RecentWindowsTest {

    @Test
    fun `elige la ventana en la que la especie saco mas`() {
        // La racha tipica: fuerte al principio y floja al confirmar. Antes se
        // guardaba el audio de la ultima con la puntuacion de la primera.
        val recent = RecentWindows(capacity = 4)
        val strong = FloatArray(1)
        recent.add(strong, 1_000, listOf(result("mirlo", 0.8f)))
        recent.add(FloatArray(1), 2_000, listOf(result("mirlo", 0.4f)))
        recent.add(FloatArray(1), 3_000, listOf(result("mirlo", 0.4f), result("petirrojo", 0.9f)))

        val best = recent.bestFor("mirlo")!!
        assertSame(strong, best.samples)
        assertEquals(0.8f, best.resultFor("mirlo")!!.adjustedScore, 1e-6f)
    }

    @Test
    fun `solo recuerda las ultimas`() {
        val recent = RecentWindows(capacity = 2)
        recent.add(FloatArray(1), 1_000, listOf(result("mirlo", 0.9f)))
        recent.add(FloatArray(1), 2_000, listOf(result("petirrojo", 0.5f)))
        recent.add(FloatArray(1), 3_000, listOf(result("petirrojo", 0.6f)))

        assertEquals(2, recent.size)
        assertNull("La del mirlo ya salio de la ventana", recent.bestFor("mirlo"))
    }

    @Test
    fun `con empate gana la mas reciente`() {
        val recent = RecentWindows(capacity = 3)
        recent.add(FloatArray(1), 1_000, listOf(result("mirlo", 0.5f)))
        recent.add(FloatArray(1), 2_000, listOf(result("mirlo", 0.5f)))

        assertEquals(2_000L, recent.bestFor("mirlo")!!.endTimestampMillis)
    }

    private fun result(speciesId: String, score: Float) =
        ClassificationResult(speciesId, speciesId, speciesId.length, score, score)
}
