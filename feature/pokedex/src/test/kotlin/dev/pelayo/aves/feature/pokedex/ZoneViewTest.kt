package dev.pelayo.aves.feature.pokedex

import dev.pelayo.aves.core.data.HomePlace
import dev.pelayo.aves.core.data.ZoneLevel
import dev.pelayo.aves.core.data.ZoneSpecies
import dev.pelayo.aves.core.model.ObservationSource
import dev.pelayo.aves.core.model.RarityTier
import dev.pelayo.aves.core.model.Species
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El cruce entre la coleccion y la lista de aves de una zona: lo que decide que
 * se ve, que falta y cuanto llevas AQUI.
 */
class ZoneViewTest {

    private fun species(id: String, common: String, family: String) = Species(
        speciesId = id,
        scientificName = id.replace('_', ' ').replaceFirstChar(Char::uppercase),
        genus = id.substringBefore('_').replaceFirstChar(Char::uppercase),
        family = family,
        order = "Passeriformes",
        commonName = common,
    )

    private val mirlo = species("turdus_merula", "Mirlo común", "Turdidae")
    private val zorzal = species("turdus_philomelos", "Zorzal común", "Turdidae")
    private val carbonero = species("parus_major", "Carbonero común", "Paridae")
    private val cardenal = species("cardinalis_cardinalis", "Cardenal norteño", "Cardinalidae")

    private fun collected(target: Species) = CollectedSpecies(
        species = target,
        sightings = listOf(
            Sighting(1, target.speciesId, 1_000, RarityTier.COMMON, ObservationSource.AUDIO, 0.9f),
        ),
    )

    private val asturias = listOf(
        ZoneSpecies(mirlo, share = 0.04f),
        ZoneSpecies(zorzal, share = 0.002f),
        ZoneSpecies(carbonero, share = 0.00005f),
    )

    @Test
    fun `sin lista de zona no se filtra nada`() {
        // Sin red la primera vez, o el mundo sin pedir las que faltan: mejor la
        // coleccion entera que una vacia.
        val view = buildZoneView(
            collected = listOf(collected(mirlo), collected(cardenal)),
            zoneSpecies = null,
            showMissing = true,
            query = "",
            fallbackTotal = 6_419,
        )

        assertEquals(2, view.groups.sumOf { it.members.size })
        assertEquals(0, view.groups.sumOf { it.missing.size })
        assertEquals(CollectionProgress(collected = 2, total = 6_419), view.progress)
        assertEquals(0, view.hiddenOutsideZone)
    }

    @Test
    fun `el progreso cuenta sobre las aves de la zona`() {
        val view = buildZoneView(
            collected = listOf(collected(mirlo), collected(cardenal)),
            zoneSpecies = asturias,
            showMissing = false,
            query = "",
            fallbackTotal = 6_419,
        )

        // Uno de tres: el cardenal no es de Asturias y no suma aqui.
        assertEquals(CollectionProgress(collected = 1, total = 3), view.progress)
    }

    @Test
    fun `lo que se vio fuera de la zona no se ensena, pero se cuenta`() {
        // Si no se dijera, el ave que alguien se trajo de un viaje
        // "desapareceria" de su coleccion y pareceria un fallo.
        val view = buildZoneView(
            collected = listOf(collected(mirlo), collected(cardenal)),
            zoneSpecies = asturias,
            showMissing = false,
            query = "",
            fallbackTotal = 0,
        )

        assertEquals(listOf("turdus_merula"), view.groups.flatMap { it.members }.map { it.species.speciesId })
        assertEquals(1, view.hiddenOutsideZone)
    }

    @Test
    fun `las que faltan son las de la zona que no estan en la coleccion`() {
        val view = buildZoneView(
            collected = listOf(collected(mirlo)),
            zoneSpecies = asturias,
            showMissing = true,
            query = "",
            fallbackTotal = 0,
        )

        assertEquals(
            setOf("turdus_philomelos", "parus_major"),
            view.groups.flatMap { it.missing }.map { it.species.speciesId }.toSet(),
        )
    }

    @Test
    fun `con el interruptor apagado no se ensena ninguna que falte`() {
        val view = buildZoneView(
            collected = listOf(collected(mirlo)),
            zoneSpecies = asturias,
            showMissing = false,
            query = "",
            fallbackTotal = 0,
        )

        assertTrue(view.groups.all { it.missing.isEmpty() })
        // Pero el total sigue siendo el de la zona.
        assertEquals(3, view.progress.total)
    }

    @Test
    fun `una coleccion vacia con zona ensena todo lo que hay por ver`() {
        val view = buildZoneView(
            collected = emptyList(),
            zoneSpecies = asturias,
            showMissing = true,
            query = "",
            fallbackTotal = 0,
        )

        assertEquals(3, view.groups.sumOf { it.missing.size })
        assertEquals(CollectionProgress(collected = 0, total = 3), view.progress)
    }

    @Test
    fun `lo visto y lo que falta de una familia van en el mismo bloque`() {
        val view = buildZoneView(
            collected = listOf(collected(mirlo)),
            zoneSpecies = asturias,
            showMissing = true,
            query = "",
            fallbackTotal = 0,
        )

        assertEquals(listOf("Paridae", "Turdidae"), view.groups.map { it.family })
        val turdidae = view.groups.first { it.family == "Turdidae" }
        assertEquals(listOf("turdus_merula"), turdidae.members.map { it.species.speciesId })
        assertEquals(listOf("turdus_philomelos"), turdidae.missing.map { it.species.speciesId })
        assertEquals(2, turdidae.size)
    }

    @Test
    fun `la busqueda filtra tambien las que faltan`() {
        val view = buildZoneView(
            collected = listOf(collected(mirlo)),
            zoneSpecies = asturias,
            showMissing = true,
            query = "zorzal",
            fallbackTotal = 0,
        )

        assertTrue(view.groups.all { it.members.isEmpty() })
        assertEquals(listOf("turdus_philomelos"), view.groups.flatMap { it.missing }.map { it.species.speciesId })
        // Buscar no cambia cuanto llevas.
        assertEquals(CollectionProgress(collected = 1, total = 3), view.progress)
    }

    @Test
    fun `la rareza de lo que falta es la de la zona`() {
        assertEquals(RarityTier.VERY_COMMON, MissingSpecies(mirlo, share = 0.04f).rarityHere)
        assertEquals(RarityTier.COMMON, MissingSpecies(zorzal, share = 0.002f).rarityHere)
        assertEquals(RarityTier.RARE, MissingSpecies(carbonero, share = 0.00005f).rarityHere)
        // Sin zona (el mundo) no hay "aqui" del que hablar.
        assertNull(MissingSpecies(mirlo, share = null).rarityHere)
    }

    // --- El menu de zonas ---------------------------------------------------

    @Test
    fun `el menu ofrece los niveles que se conocen, del mas fino al mundo`() {
        val options = zoneOptionsFor(
            HomePlace(
                countryCode = "ES",
                countryName = "España",
                regionId = "ESP.7_1",
                regionName = "Castilla y León",
                provinceId = "ESP.7.5_1",
                provinceName = "León",
            ),
        )

        assertEquals(
            listOf(ZoneLevel.PROVINCE, ZoneLevel.REGION, ZoneLevel.COUNTRY, ZoneLevel.WORLD),
            options.map { it.level },
        )
        assertEquals(listOf("León", "Castilla y León", "España", ""), options.map { it.name })
    }

    @Test
    fun `una region que se llama igual que su provincia no sale dos veces`() {
        // Asturias, Madrid, Cantabria, La Rioja, Murcia, Navarra...
        val options = zoneOptionsFor(
            HomePlace(
                countryCode = "ES",
                countryName = "España",
                regionId = "ESP.13_1",
                regionName = "Asturias",
                provinceId = "ESP.13.1_1",
                provinceName = "Asturias",
            ),
        )

        assertEquals(listOf(ZoneLevel.PROVINCE, ZoneLevel.COUNTRY, ZoneLevel.WORLD), options.map { it.level })
    }

    @Test
    fun `sin zona conocida solo queda el mundo`() {
        assertEquals(listOf(ZoneLevel.WORLD), zoneOptionsFor(HomePlace()).map { it.level })
    }

    @Test
    fun `sin ubicacion se ofrece el pais del movil`() {
        val options = zoneOptionsFor(HomePlace(countryCode = "ES", countryName = "España"))
        assertEquals(listOf(ZoneLevel.COUNTRY, ZoneLevel.WORLD), options.map { it.level })
    }
}
