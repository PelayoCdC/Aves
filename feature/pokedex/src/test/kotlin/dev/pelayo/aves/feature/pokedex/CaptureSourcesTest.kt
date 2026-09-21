package dev.pelayo.aves.feature.pokedex

import dev.pelayo.aves.core.model.ObservationSource
import dev.pelayo.aves.core.model.RarityTier
import dev.pelayo.aves.core.model.Species
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los tres iconos de la tarjeta: vista, fotografiada, oida. */
class CaptureSourcesTest {

    private fun sighting(id: Long, source: ObservationSource) =
        Sighting(id, "turdus_merula", 1_000L * id, RarityTier.COMMON, source, 0.9f)

    @Test
    fun `cada origen enciende su icono y solo el suyo`() {
        val heard = CaptureSources.of(listOf(sighting(1, ObservationSource.AUDIO)))

        assertTrue(heard.has(ObservationSource.AUDIO))
        assertFalse(heard.has(ObservationSource.IMAGE))
        assertFalse(heard.has(ObservationSource.MANUAL))
    }

    @Test
    fun `se cuenta cuantas hay de cada una`() {
        val sources = CaptureSources.of(
            listOf(
                sighting(1, ObservationSource.AUDIO),
                sighting(2, ObservationSource.AUDIO),
                sighting(3, ObservationSource.IMAGE),
                sighting(4, ObservationSource.MANUAL),
                sighting(5, ObservationSource.AUDIO),
            ),
        )

        assertEquals(CaptureSources(seen = 1, photographed = 1, heard = 3), sources)
        assertEquals(3, sources.count(ObservationSource.AUDIO))
    }

    @Test
    fun `sin avistamientos los tres quedan apagados`() {
        assertEquals(CaptureSources.None, CaptureSources.of(emptyList()))
        assertTrue(ObservationSource.entries.none(CaptureSources.None::has))
    }

    @Test
    fun `hay un icono para cada origen que existe`() {
        // Si algun dia se anade un origen nuevo, que falle aqui y no en una
        // tarjeta a la que le falta un icono sin que nadie se entere.
        assertEquals(ObservationSource.entries.toSet(), SOURCE_ORDER.toSet())
        assertEquals(SOURCE_ORDER.size, SOURCE_ORDER.distinct().size)
    }

    @Test
    fun `la especie de la coleccion expone de donde viene`() {
        val entry = CollectedSpecies(
            species = Species(
                speciesId = "turdus_merula",
                scientificName = "Turdus merula",
                genus = "Turdus",
                family = "Turdidae",
                order = "Passeriformes",
                commonName = "Mirlo común",
            ),
            sightings = listOf(sighting(2, ObservationSource.IMAGE), sighting(1, ObservationSource.AUDIO)),
        )

        assertEquals(CaptureSources(photographed = 1, heard = 1), entry.sources)
    }
}
