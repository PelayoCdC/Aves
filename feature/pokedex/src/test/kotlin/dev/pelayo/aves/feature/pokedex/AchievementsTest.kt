package dev.pelayo.aves.feature.pokedex

import dev.pelayo.aves.core.data.PokedexZone
import dev.pelayo.aves.core.data.ZoneLevel
import dev.pelayo.aves.core.model.Species
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los cortes de las medallas: donde un redondeo da o quita un oro. */
class AchievementsTest {

    private fun species(id: String, family: String) = Species(
        speciesId = id,
        scientificName = id,
        genus = id.substringBefore('_'),
        family = family,
        order = "Passeriformes",
        commonName = id,
    )

    @Test
    fun `cada metal empieza en su umbral, incluido`() {
        val scale = MedalScale.ZONE
        assertEquals(MedalTier.NONE, scale.tierFor(0.049f))
        assertEquals(MedalTier.IRON, scale.tierFor(0.05f))
        assertEquals(MedalTier.BRONZE, scale.tierFor(0.15f))
        assertEquals(MedalTier.SILVER, scale.tierFor(0.30f))
        assertEquals(MedalTier.GOLD, scale.tierFor(0.50f))
        assertEquals(MedalTier.PLATINUM, scale.tierFor(0.75f))
        assertEquals(MedalTier.DIAMOND, scale.tierFor(0.95f))
        assertEquals(MedalTier.DIAMOND, scale.tierFor(1f))
    }

    @Test
    fun `el total del planeta se mide con una escala mas corta`() {
        // 41 de 6.419 es el 0,64 %: hierro en la global, nada en la de zona.
        assertEquals(MedalTier.IRON, MedalScale.GLOBAL.tierFor(41f / 6_419f))
        assertEquals(MedalTier.NONE, MedalScale.ZONE.tierFor(41f / 6_419f))
    }

    @Test
    fun `cuantas faltan para el siguiente metal, redondeando hacia arriba`() {
        // 38 de 310 es el 12,3 %: hierro. El bronce pide el 15 %: 46,5 -> 47.
        val medal = Achievement("z", AchievementKind.ZONE, "Asturias", 38, 310, MedalScale.ZONE)
        assertEquals(MedalTier.IRON, medal.tier)
        assertEquals(9, medal.remainingForNext)
    }

    @Test
    fun `con diamante no queda nada por delante`() {
        val medal = Achievement("f", AchievementKind.FAMILY, "Paridae", 5, 5, MedalScale.ZONE)
        assertEquals(MedalTier.DIAMOND, medal.tier)
        assertNull(medal.remainingForNext)
    }

    @Test
    fun `sin lista no hay metal ni fraccion`() {
        val medal = Achievement("z", AchievementKind.PLACE, "Delta del Ebro", 12, null, MedalScale.ZONE)
        assertFalse(medal.hasData)
        assertEquals(MedalTier.NONE, medal.tier)
        assertEquals(0f, medal.fraction, 0f)
        assertNull(medal.remainingForNext)
    }

    @Test
    fun `las medallas de zona cuentan solo las especies de la lista`() {
        val asturias = PokedexZone(ZoneLevel.PROVINCE, "ESP.3.1_1", "Asturias")
        val medals = buildAchievements(
            collectedIds = setOf("a", "b", "cardinal"),
            detectableTotal = 100,
            zones = listOf(ZoneChecklist(asturias, setOf("a", "b", "c", "d"))),
            detectableByFamily = emptyMap(),
        )

        val zone = medals.first { it.kind == AchievementKind.ZONE }
        // El cardenal no es de Asturias: no cuenta ahi, pero si en la coleccion.
        assertEquals(2, zone.collected)
        assertEquals(4, zone.total)
        assertEquals(3, medals.first { it.kind == AchievementKind.COLLECTOR }.collected)
    }

    @Test
    fun `las familias solo salen con algo conseguido y si son grandes`() {
        val turdidae = (1..6).map { species("turdus_$it", "Turdidae") }
        val paridae = (1..6).map { species("parus_$it", "Paridae") }
        val tiny = (1..2).map { species("upupa_$it", "Upupidae") }

        val medals = buildAchievements(
            collectedIds = setOf("turdus_1", "upupa_1", "upupa_2"),
            detectableTotal = 14,
            zones = emptyList(),
            detectableByFamily = mapOf("Turdidae" to turdidae, "Paridae" to paridae, "Upupidae" to tiny),
        )

        val families = medals.filter { it.kind == AchievementKind.FAMILY }.map { it.title }
        // Paridae: nada conseguido. Upupidae: completa, pero dos especies no son un logro.
        assertEquals(listOf("Turdidae"), families)
    }

    @Test
    fun `las ganadas van primero, del mejor metal al peor`() {
        val zones = listOf(
            ZoneChecklist(PokedexZone(ZoneLevel.PLACE, "PLACE:a", "Sin datos"), null),
            ZoneChecklist(PokedexZone(ZoneLevel.PLACE, "PLACE:b", "Oro"), (1..10).map { "s$it" }.toSet()),
            ZoneChecklist(PokedexZone(ZoneLevel.PLACE, "PLACE:c", "Hierro"), (1..100).map { "s$it" }.toSet()),
        )
        val medals = buildAchievements(
            collectedIds = (1..5).map { "s$it" }.toSet(),
            detectableTotal = 100_000,
            zones = zones,
            detectableByFamily = emptyMap(),
        )

        assertEquals(listOf("Oro", "Hierro", "", "Sin datos"), medals.map { it.title })
        assertTrue(medals.last().tier == MedalTier.NONE)
    }

    @Test
    fun `el dibujo de una medalla es siempre el mismo`() {
        val medal = Achievement("zone:PLACE:donana", AchievementKind.PLACE, "Doñana", 1, 10, MedalScale.ZONE)
        assertEquals(MedalDesign.of(medal), MedalDesign.of(medal.copy(collected = 9)))
        val other = Achievement("zone:PLACE:monfrague", AchievementKind.PLACE, "Monfragüe", 1, 10, MedalScale.ZONE)
        assertTrue(MedalDesign.of(medal) != MedalDesign.of(other))
    }
}
