package dev.pelayo.aves.feature.pokedex

import dev.pelayo.aves.core.data.CellSpecies
import dev.pelayo.aves.core.model.RarityTier
import dev.pelayo.aves.core.model.Species
import org.junit.Assert.assertEquals
import org.junit.Test

class NearbyTest {

    private fun species(id: String) = Species(
        speciesId = id,
        scientificName = id,
        genus = id,
        family = "Turdidae",
        order = "Passeriformes",
        commonName = id,
    )

    @Test
    fun `solo lo que la app sabe identificar, en el orden de abundancia, con lo que ya tienes marcado`() {
        val rows = listOf(
            CellSpecies("mirlo", 0.04f, RarityTier.VERY_COMMON),
            CellSpecies("rana", 0.02f, RarityTier.VERY_COMMON), // no es un ave que detecte ningun modelo
            CellSpecies("petirrojo", 0.01f, RarityTier.COMMON),
            CellSpecies("sin_catalogo", 0.005f, RarityTier.UNCOMMON),
        )
        val catalog = listOf("mirlo", "rana", "petirrojo").associateWith(::species)

        val nearby = buildNearby(
            rows = rows,
            catalog = catalog,
            detectable = setOf("mirlo", "petirrojo", "sin_catalogo"),
            collected = setOf("petirrojo"),
        )

        assertEquals(listOf("mirlo", "petirrojo"), nearby.map { it.species.speciesId })
        assertEquals(listOf(false, true), nearby.map { it.collected })
        assertEquals(RarityTier.COMMON, nearby[1].rarity)
    }
}
