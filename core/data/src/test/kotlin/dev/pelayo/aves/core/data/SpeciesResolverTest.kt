package dev.pelayo.aves.core.data

import dev.pelayo.aves.core.database.dao.ModelLabelDao
import dev.pelayo.aves.core.database.entity.ModelLabelEntity
import dev.pelayo.aves.core.ml.api.CombinedModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeciesResolverTest {

    /**
     * Los DAO de Room son interfaces, asi que se pueden implementar a mano y
     * probar la logica de resolucion en la JVM, sin emulador ni base de datos.
     */
    private class FakeModelLabelDao(
        private val labels: Map<String, List<ModelLabelEntity>>,
    ) : ModelLabelDao {
        var queryCount = 0
            private set

        override suspend fun labelsFor(modelId: String): List<ModelLabelEntity> {
            queryCount++
            return labels[modelId].orEmpty()
        }

        override suspend fun countFor(modelId: String): Int = labels[modelId].orEmpty().size

        override suspend fun unmappedCountFor(modelId: String): Int =
            labels[modelId].orEmpty().count { it.speciesId == null }

        override suspend fun modelIds(): List<String> = labels.keys.toList()

        override suspend fun detectableSpeciesIds(): List<String> =
            labels.values.flatten().mapNotNull { it.speciesId }.distinct()

        override fun detectableSpeciesCount(): kotlinx.coroutines.flow.Flow<Int> =
            kotlinx.coroutines.flow.flowOf(
                labels.values.flatten().mapNotNull { it.speciesId }.distinct().size,
            )

        override suspend fun upsertAll(labels: List<ModelLabelEntity>) = Unit

        override suspend fun deleteModel(modelId: String) = Unit
    }

    private fun label(index: Int, speciesId: String?, raw: String = "raw$index") =
        ModelLabelEntity(
            modelId = MODEL,
            labelIndex = index,
            speciesId = speciesId,
            rawLabel = raw,
        )

    private fun resolver(vararg labels: ModelLabelEntity) =
        SpeciesResolver(FakeModelLabelDao(mapOf(MODEL to labels.toList())))

    @Test
    fun `traduce el indice de tensor a la especie canonica`() = runTest {
        val resolver = resolver(
            label(0, "erithacus_rubecula"),
            label(1, "turdus_merula"),
        )

        assertEquals("erithacus_rubecula", resolver.resolve(MODEL, 0))
        assertEquals("turdus_merula", resolver.resolve(MODEL, 1))
    }

    @Test
    fun `devuelve null para las clases que no son aves`() = runTest {
        // Las 11 de no-evento y los 90 animales que no son aves tienen
        // speciesId nulo a proposito: no es un error, es informacion.
        val resolver = resolver(
            label(0, "turdus_merula"),
            label(1, null, "Noise_Noise"),
            label(2, null, "Anaxyrus americanus_American Toad"),
        )

        assertEquals("turdus_merula", resolver.resolve(MODEL, 0))
        assertNull("el ruido no es un ave", resolver.resolve(MODEL, 1))
        assertNull("un sapo tampoco", resolver.resolve(MODEL, 2))
    }

    @Test
    fun `un indice fuera de rango no revienta`() = runTest {
        // Defensa contra un fichero de etiquetas desparejado del modelo: mejor
        // perder un candidato que tumbar la escucha entera.
        val resolver = resolver(label(0, "turdus_merula"))

        assertNull(resolver.resolve(MODEL, 9_999))
    }

    @Test
    fun `consulta la base de datos una sola vez por modelo`() = runTest {
        // La inferencia resuelve varios candidatos por ventana, unas cuatro
        // veces por segundo. Sin cache esto seria una consulta a SQLite por
        // cada uno.
        val dao = FakeModelLabelDao(mapOf(MODEL to listOf(label(0, "turdus_merula"))))
        val resolver = SpeciesResolver(dao)

        repeat(50) { resolver.resolve(MODEL, 0) }

        assertEquals(1, dao.queryCount)
    }

    @Test
    fun `invalidar obliga a releer el mapeo`() = runTest {
        val dao = FakeModelLabelDao(mapOf(MODEL to listOf(label(0, "turdus_merula"))))
        val resolver = SpeciesResolver(dao)

        resolver.resolve(MODEL, 0)
        resolver.invalidate()
        resolver.resolve(MODEL, 0)

        assertEquals(2, dao.queryCount)
    }

    @Test
    fun `falla ruidosamente si no hay cross-walk para el modelo`() = runTest {
        // Sin mapeo, la app identificaria sin poder traducir nada y parecerian
        // fallos del modelo. Es preferible un error claro: falta sembrar.
        val resolver = SpeciesResolver(FakeModelLabelDao(emptyMap()))

        val error = runCatching { resolver.resolve("modelo-inexistente", 0) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("modelo-inexistente"))
    }

    @Test
    fun `con los dos modelos cada indice se resuelve con la tabla del suyo`() = runTest {
        val resolver = SpeciesResolver(
            FakeModelLabelDao(
                mapOf(
                    CombinedModel.FIRST_MODEL_ID to listOf(label(0, "turdus_merula")),
                    CombinedModel.SECOND_MODEL_ID to listOf(
                        ModelLabelEntity(CombinedModel.SECOND_MODEL_ID, 0, "curruca_communis", "raw"),
                    ),
                ),
            ),
        )

        assertEquals("turdus_merula", resolver.resolve(CombinedModel.MODEL_ID, CombinedModel.encodeFirst(0)))
        assertEquals("curruca_communis", resolver.resolve(CombinedModel.MODEL_ID, CombinedModel.encodeSecond(0)))
    }

    private companion object {
        const val MODEL = "birdnet-v2.4"
    }
}
