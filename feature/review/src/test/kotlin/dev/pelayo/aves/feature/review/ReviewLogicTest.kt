package dev.pelayo.aves.feature.review

import dev.pelayo.aves.core.database.entity.ObservationEntity
import dev.pelayo.aves.core.model.ObservationSource
import dev.pelayo.aves.core.model.Species
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewLogicTest {

    private fun observation(
        id: Long,
        speciesId: String,
        adjusted: Float,
        raw: Float = adjusted,
        timestamp: Long = 1_000L,
    ) = ObservationEntity(
        id = id,
        speciesId = speciesId,
        sessionId = 1L,
        timestamp = timestamp,
        source = ObservationSource.AUDIO,
        rawConfidence = raw,
        adjustedConfidence = adjusted,
        modelId = "birdnet-v2.4",
        rarityTierAtCapture = 1,
    )

    private fun species(speciesId: String, common: String, scientific: String) = Species(
        speciesId = speciesId,
        scientificName = scientific,
        genus = scientific.substringBefore(' '),
        family = "Turdidae",
        order = "Passeriformes",
        commonName = common,
    )

    // --- Cruce observacion <-> especie -------------------------------------

    @Test
    fun `pone el nombre comun y el cientifico del catalogo`() {
        val merged = mergePendingWithSpecies(
            observations = listOf(observation(id = 7L, speciesId = "turmer", adjusted = 0.9f)),
            species = listOf(species("turmer", "Mirlo común", "Turdus merula")),
        )

        assertEquals(1, merged.size)
        assertEquals(7L, merged[0].observationId)
        assertEquals("Mirlo común", merged[0].commonName)
        assertEquals("Turdus merula", merged[0].scientificName)
        assertTrue(merged[0].isResolved)
    }

    @Test
    fun `una especie sirve para todas sus observaciones`() {
        val merged = mergePendingWithSpecies(
            observations = listOf(
                observation(id = 1L, speciesId = "turmer", adjusted = 0.9f),
                observation(id = 2L, speciesId = "turmer", adjusted = 0.8f),
            ),
            species = listOf(species("turmer", "Mirlo común", "Turdus merula")),
        )

        assertEquals(listOf("Mirlo común", "Mirlo común"), merged.map { it.commonName })
    }

    @Test
    fun `una especie que no esta en el catalogo se muestra igualmente`() {
        // Ocultarla la dejaria pendiente para siempre: el usuario ni siquiera
        // podria rechazarla.
        val merged = mergePendingWithSpecies(
            observations = listOf(observation(id = 3L, speciesId = "desconocida", adjusted = 0.7f)),
            species = emptyList(),
        )

        assertEquals(1, merged.size)
        assertFalse(merged[0].isResolved)
        assertEquals(UNKNOWN_SPECIES_NAME, merged[0].commonName)
        assertEquals("desconocida", merged[0].scientificName)
    }

    @Test
    fun `ignora especies del catalogo que no tienen observacion pendiente`() {
        val merged = mergePendingWithSpecies(
            observations = listOf(observation(id = 1L, speciesId = "turmer", adjusted = 0.9f)),
            species = listOf(
                species("turmer", "Mirlo común", "Turdus merula"),
                species("eurrob", "Petirrojo europeo", "Erithacus rubecula"),
            ),
        )

        assertEquals(listOf("turmer"), merged.map { it.speciesId })
    }

    @Test
    fun `conserva las dos confianzas por separado`() {
        // Si se colapsaran en una sola no habria forma de auditar despues si el
        // prior geo-temporal ayuda o estorba.
        val merged = mergePendingWithSpecies(
            observations = listOf(observation(id = 1L, speciesId = "turmer", adjusted = 0.8f, raw = 0.6f)),
            species = emptyList(),
        )

        assertEquals(0.8f, merged[0].confidence, 1e-6f)
        assertEquals(0.6f, merged[0].rawConfidence, 1e-6f)
    }

    @Test
    fun `una lista vacia no revienta`() {
        assertTrue(mergePendingWithSpecies(emptyList(), emptyList()).isEmpty())
    }

    // --- Ordenacion --------------------------------------------------------

    @Test
    fun `ordena por confianza descendente`() {
        val merged = mergePendingWithSpecies(
            observations = listOf(
                observation(id = 1L, speciesId = "a", adjusted = 0.42f),
                observation(id = 2L, speciesId = "b", adjusted = 0.95f),
                observation(id = 3L, speciesId = "c", adjusted = 0.66f),
            ),
            species = emptyList(),
        )

        assertEquals(listOf(2L, 3L, 1L), merged.map { it.observationId })
    }

    @Test
    fun `ordena por la confianza ajustada y no por la del modelo`() {
        // La ajustada es sobre la que decide el usuario, asi que es la que manda
        // en la cola de trabajo.
        val merged = mergePendingWithSpecies(
            observations = listOf(
                observation(id = 1L, speciesId = "a", adjusted = 0.9f, raw = 0.1f),
                observation(id = 2L, speciesId = "b", adjusted = 0.2f, raw = 0.99f),
            ),
            species = emptyList(),
        )

        assertEquals(listOf(1L, 2L), merged.map { it.observationId })
    }

    @Test
    fun `a igual confianza va primero lo mas reciente`() {
        val merged = mergePendingWithSpecies(
            observations = listOf(
                observation(id = 1L, speciesId = "a", adjusted = 0.7f, timestamp = 100L),
                observation(id = 2L, speciesId = "b", adjusted = 0.7f, timestamp = 900L),
            ),
            species = emptyList(),
        )

        assertEquals(listOf(2L, 1L), merged.map { it.observationId })
    }

    @Test
    fun `el orden es estable con datos identicos`() {
        // Sin desempate por id, dos emisiones del mismo Flow podrian dar ordenes
        // distintos y la lista bailaria bajo el dedo del usuario.
        val observations = listOf(
            observation(id = 5L, speciesId = "a", adjusted = 0.7f, timestamp = 100L),
            observation(id = 2L, speciesId = "b", adjusted = 0.7f, timestamp = 100L),
            observation(id = 9L, speciesId = "c", adjusted = 0.7f, timestamp = 100L),
        )

        val first = mergePendingWithSpecies(observations, emptyList()).map { it.observationId }
        val second = mergePendingWithSpecies(observations.reversed(), emptyList())
            .map { it.observationId }

        assertEquals(listOf(2L, 5L, 9L), first)
        assertEquals(first, second)
    }

    // --- Niveles de confianza ---------------------------------------------

    @Test
    fun `clasifica la confianza en tres niveles`() {
        assertEquals(ConfidenceLevel.SOLID, ConfidenceLevel.of(0.95f))
        assertEquals(ConfidenceLevel.PROBABLE, ConfidenceLevel.of(0.66f))
        assertEquals(ConfidenceLevel.DOUBTFUL, ConfidenceLevel.of(0.41f))
    }

    @Test
    fun `los umbrales son inclusivos por abajo`() {
        assertEquals(ConfidenceLevel.SOLID, ConfidenceLevel.of(ConfidenceLevel.SOLID_THRESHOLD))
        assertEquals(
            ConfidenceLevel.PROBABLE,
            ConfidenceLevel.of(ConfidenceLevel.PROBABLE_THRESHOLD),
        )
    }

    @Test
    fun `una confianza del noventa y cinco y otra del sesenta y seis no son el mismo nivel`() {
        // Es el requisito de diseno: tienen que sentirse distintas en pantalla.
        val merged = mergePendingWithSpecies(
            observations = listOf(
                observation(id = 1L, speciesId = "a", adjusted = 0.95f),
                observation(id = 2L, speciesId = "b", adjusted = 0.66f),
            ),
            species = emptyList(),
        )

        assertEquals(ConfidenceLevel.SOLID, merged[0].level)
        assertEquals(ConfidenceLevel.PROBABLE, merged[1].level)
    }
}
