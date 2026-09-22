package dev.pelayo.aves.core.listening

import dev.pelayo.aves.core.ml.api.ClassificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Que especies da por seguras el analisis de un fichero. */
class FileAnalysisAccumulatorTest {

    private fun result(species: String, score: Float, index: Int = species.hashCode()) = ClassificationResult(
        speciesId = species,
        rawLabel = species,
        labelIndex = index,
        rawScore = score,
        adjustedScore = score,
    )

    private val hop = 72_000

    @Test
    fun `una especie floja en una sola ventana no sale`() {
        // Es el caso que llenaba la lista de "aves" al 6 %: un clasificador de
        // miles de clases siempre menciona alguna.
        val accumulator = FileAnalysisAccumulator(confirmThreshold = 0.35f)
        accumulator.add(0, listOf(result("turdus_merula", 0.40f)))

        assertTrue(accumulator.confirmed().isEmpty())
    }

    @Test
    fun `por debajo del umbral ni se apunta, por muchas ventanas que sean`() {
        val accumulator = FileAnalysisAccumulator(confirmThreshold = 0.35f)
        repeat(20) { accumulator.add(it * hop, listOf(result("turdus_merula", 0.30f))) }

        assertTrue(accumulator.confirmed().isEmpty())
    }

    @Test
    fun `dos ventanas por encima del umbral confirman`() {
        val accumulator = FileAnalysisAccumulator(confirmThreshold = 0.35f)
        accumulator.add(0, listOf(result("turdus_merula", 0.40f)))
        accumulator.add(5 * hop, listOf(result("turdus_merula", 0.55f)))

        val found = accumulator.confirmed().single()
        assertEquals("turdus_merula", found.detection.speciesId)
        // Se queda con la mejor puntuacion y con las dos ventanas, que es de
        // donde saldra su pista.
        assertEquals(0.55f, found.detection.score, 0f)
        assertEquals(listOf(0, 5 * hop), found.windows.map { it.startSample })
    }

    @Test
    fun `un reclamo aislado pero rotundo tambien confirma`() {
        val accumulator = FileAnalysisAccumulator(confirmThreshold = 0.35f)
        accumulator.add(3 * hop, listOf(result("strix_aluco", 0.92f)))

        assertEquals("strix_aluco", accumulator.confirmed().single().detection.speciesId)
    }

    @Test
    fun `salen de la mas clara a la menos`() {
        val accumulator = FileAnalysisAccumulator(confirmThreshold = 0.35f)
        accumulator.add(0, listOf(result("a", 0.5f), result("b", 0.9f)))
        accumulator.add(hop, listOf(result("a", 0.6f), result("b", 0.8f)))

        assertEquals(listOf("b", "a"), accumulator.confirmed().map { it.detection.speciesId })
    }

    @Test
    fun `lo que no es un ave no cuenta`() {
        val accumulator = FileAnalysisAccumulator(confirmThreshold = 0.35f)
        val noise = ClassificationResult(null, "Engine", 1, 0.99f, 0.99f)
        accumulator.add(0, listOf(noise))
        accumulator.add(hop, listOf(noise))

        assertTrue(accumulator.confirmed().isEmpty())
    }

    @Test
    fun `el umbral es el de los ajustes del usuario`() {
        val strict = FileAnalysisAccumulator(confirmThreshold = 0.6f)
        val lenient = FileAnalysisAccumulator(confirmThreshold = 0.2f)
        for (accumulator in listOf(strict, lenient)) {
            accumulator.add(0, listOf(result("parus_major", 0.45f)))
            accumulator.add(hop, listOf(result("parus_major", 0.50f)))
        }

        assertTrue(strict.confirmed().isEmpty())
        assertEquals(1, lenient.confirmed().size)
    }
}
