package dev.pelayo.aves.ml

import dev.pelayo.aves.core.ml.api.ClassificationResult
import dev.pelayo.aves.core.ml.api.CombinedModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MergeByCanonicalSpeciesTest {

    // v2.4 aun llama Sylvia a la curruca zarcera; BirdNET+ ya la llama Curruca.
    private val first = mapOf(0 to "turdus_merula", 1 to "curruca_communis", 2 to null)
    private val second = mapOf(0 to "curruca_communis", 1 to "phylloscopus_ibericus")

    private val resolve: suspend (String, Int) -> String? = { model, index ->
        if (model == CombinedModel.FIRST_MODEL_ID) first[index] else second[index]
    }

    @Test
    fun `junta por especie canonica y se queda con la mejor puntuacion`() = runBlocking {
        val merged = mergeByCanonicalSpecies(
            first = listOf(result(0, 0.9f), result(1, 0.3f), result(2, 0.8f)),
            second = listOf(result(0, 0.6f), result(1, 0.5f)),
            firstModelId = CombinedModel.FIRST_MODEL_ID,
            secondModelId = CombinedModel.SECOND_MODEL_ID,
            resolve = resolve,
            topK = 8,
        )

        assertEquals(listOf("turdus_merula", "curruca_communis", "phylloscopus_ibericus"), merged.map { it.speciesId })
        // La zarcera la oyo mejor BirdNET+: su indice va codificado como del segundo.
        assertEquals(CombinedModel.encodeSecond(0), merged[1].labelIndex)
        assertEquals(0.6f, merged[1].adjustedScore, 1e-6f)
        // Lo que no es un ave (el indice 2 del primero) no llega.
        assertEquals(3, merged.size)
    }

    @Test
    fun `respeta el top K`() = runBlocking {
        val merged = mergeByCanonicalSpecies(
            first = listOf(result(0, 0.9f), result(1, 0.3f)),
            second = listOf(result(1, 0.5f)),
            firstModelId = CombinedModel.FIRST_MODEL_ID,
            secondModelId = CombinedModel.SECOND_MODEL_ID,
            resolve = resolve,
            topK = 1,
        )
        assertEquals(listOf("turdus_merula"), merged.map { it.speciesId })
    }

    private fun result(index: Int, score: Float) =
        ClassificationResult(speciesId = "provisional_$index", rawLabel = "x", labelIndex = index, rawScore = score, adjustedScore = score)
}
