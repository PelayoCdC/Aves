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

    @Test
    fun `el recorte lleva delante el primer salto de las ventanas anteriores`() {
        // Ventanas de 4 muestras con salto de 2: cada una empieza 2 despues de
        // la anterior. La senal seguida es 0,1,2,3,4,5,6,7.
        val recent = RecentWindows(capacity = 5)
        recent.add(floatArrayOf(0f, 1f, 2f, 3f), 1_000, emptyList())
        recent.add(floatArrayOf(2f, 3f, 4f, 5f), 1_800, emptyList())
        recent.add(floatArrayOf(4f, 5f, 6f, 7f), 2_600, listOf(result("mirlo", 0.9f)))

        val best = recent.bestFor("mirlo")!!
        val audio = recent.audioEndingWith(best, hopSamples = 2, hopMillis = 800, preRollWindows = 3)

        assertEquals(listOf(0f, 1f, 2f, 3f, 4f, 5f, 6f, 7f), audio.toList())
    }

    @Test
    fun `si falta una ventana el preambulo se corta ahi`() {
        // Entre 1.000 y 2.600 falta la de 1.800 (descartada, o sonaba un recorte
        // propio): pegar la de 1.000 meteria un salto en el audio.
        val recent = RecentWindows(capacity = 5)
        recent.add(floatArrayOf(0f, 1f, 2f, 3f), 1_000, emptyList())
        recent.add(floatArrayOf(4f, 5f, 6f, 7f), 2_600, listOf(result("mirlo", 0.9f)))

        val best = recent.bestFor("mirlo")!!
        val audio = recent.audioEndingWith(best, hopSamples = 2, hopMillis = 800, preRollWindows = 3)

        assertEquals(listOf(4f, 5f, 6f, 7f), audio.toList())
    }

    private fun result(speciesId: String, score: Float) =
        ClassificationResult(speciesId, speciesId, speciesId.length, score, score)
}
