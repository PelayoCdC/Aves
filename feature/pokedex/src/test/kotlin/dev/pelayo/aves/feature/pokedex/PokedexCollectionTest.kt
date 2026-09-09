package dev.pelayo.aves.feature.pokedex

import dev.pelayo.aves.core.model.ObservationSource
import dev.pelayo.aves.core.model.RarityTier
import dev.pelayo.aves.core.model.Species
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la logica de coleccion. Todo lo que hay aqui corre en la JVM porque
 * `PokedexCollection.kt` no toca ni Android ni Room a proposito.
 */
class PokedexCollectionTest {

    // --- buildCollection ---------------------------------------------------

    @Test
    fun `agrupa los avistamientos de la misma especie en una sola entrada`() {
        val collection = buildCollection(
            sightings = listOf(
                sighting(id = 1, speciesId = "turmer", timestamp = 100),
                sighting(id = 2, speciesId = "turmer", timestamp = 300),
                sighting(id = 3, speciesId = "parmaj", timestamp = 200),
            ),
            catalog = catalogOf(mirlo, carbonero),
        )

        assertEquals(2, collection.size)
        val mirloEntry = collection.first { it.species.speciesId == "turmer" }
        assertEquals(2, mirloEntry.count)
    }

    @Test
    fun `los avistamientos quedan del mas reciente al mas antiguo`() {
        val collection = buildCollection(
            sightings = listOf(
                sighting(id = 1, speciesId = "turmer", timestamp = 100),
                sighting(id = 2, speciesId = "turmer", timestamp = 900),
                sighting(id = 3, speciesId = "turmer", timestamp = 500),
            ),
            catalog = catalogOf(mirlo),
        )

        assertEquals(listOf(900L, 500L, 100L), collection[0].sightings.map { it.timestamp })
        assertEquals(900L, collection[0].lastSeenAt)
    }

    @Test
    fun `descarta las especies que el catalogo no conoce`() {
        val collection = buildCollection(
            sightings = listOf(
                sighting(id = 1, speciesId = "turmer", timestamp = 100),
                sighting(id = 2, speciesId = "especie-fantasma", timestamp = 100),
            ),
            catalog = catalogOf(mirlo),
        )

        assertEquals(1, collection.size)
        assertEquals("turmer", collection[0].species.speciesId)
    }

    @Test
    fun `la rareza mostrada es la mejor alcanzada, no la ultima`() {
        // El tier va congelado por captura: si un dia valio como excepcional,
        // la coleccion no debe degradarlo porque luego se oiga en el jardin.
        val collection = buildCollection(
            sightings = listOf(
                sighting(id = 1, speciesId = "turmer", timestamp = 900, tier = RarityTier.VERY_COMMON),
                sighting(id = 2, speciesId = "turmer", timestamp = 100, tier = RarityTier.EXCEPTIONAL),
            ),
            catalog = catalogOf(mirlo),
        )

        assertEquals(RarityTier.EXCEPTIONAL, collection[0].rarity)
    }

    // --- groupByFamily -----------------------------------------------------

    @Test
    fun `agrupa por familia y ordena familias alfabeticamente`() {
        val groups = groupByFamily(
            listOf(entryOf(carbonero), entryOf(mirlo), entryOf(zorzal), entryOf(herrerillo)),
        )

        assertEquals(listOf("Paridae", "Turdidae"), groups.map { it.family })
        assertEquals(2, groups[0].members.size)
        assertEquals(2, groups[1].members.size)
    }

    @Test
    fun `dentro de cada familia ordena por nombre comun`() {
        val groups = groupByFamily(listOf(entryOf(zorzal), entryOf(mirlo)))

        assertEquals(
            listOf("Mirlo común", "Zorzal común"),
            groups.single().members.map { it.species.commonName },
        )
    }

    @Test
    fun `una coleccion vacia no produce grupos`() {
        assertTrue(groupByFamily(emptyList()).isEmpty())
    }

    // --- filterCollection --------------------------------------------------

    @Test
    fun `sin texto devuelve la coleccion entera`() {
        val all = listOf(entryOf(mirlo), entryOf(carbonero))

        assertEquals(all, filterCollection(all, ""))
        assertEquals(all, filterCollection(all, "   "))
    }

    @Test
    fun `busca por nombre comun ignorando mayusculas y tildes`() {
        val all = listOf(entryOf(mirlo), entryOf(carbonero))

        assertEquals(listOf("turmer"), filterCollection(all, "MIRLO").ids())
        // Escribir sin tildes es lo normal con el movil en una mano.
        assertEquals(listOf("turmer"), filterCollection(all, "mirlo comun").ids())
    }

    @Test
    fun `busca por nombre cientifico, genero y familia`() {
        val all = listOf(entryOf(mirlo), entryOf(zorzal), entryOf(carbonero))

        assertEquals(listOf("turmer"), filterCollection(all, "merula").ids())
        assertEquals(listOf("turmer", "turphi"), filterCollection(all, "Turdus").ids())
        assertEquals(listOf("turmer", "turphi"), filterCollection(all, "turdidae").ids())
        assertEquals(listOf("parmaj"), filterCollection(all, "parus").ids())
    }

    @Test
    fun `un texto que no coincide con nada devuelve vacio`() {
        val all = listOf(entryOf(mirlo), entryOf(carbonero))

        assertTrue(filterCollection(all, "flamenco").isEmpty())
    }

    @Test
    fun `el filtro conserva el orden de entrada`() {
        val all = listOf(entryOf(zorzal), entryOf(mirlo))

        assertEquals(listOf("turphi", "turmer"), filterCollection(all, "turdus").ids())
    }

    // --- CollectionProgress ------------------------------------------------

    @Test
    fun `el progreso calcula fraccion y restantes`() {
        val progress = CollectionProgress(collected = 37, total = 6_419)

        assertEquals(6_382, progress.remaining)
        assertEquals(37f / 6_419f, progress.fraction, 1e-6f)
    }

    @Test
    fun `un catalogo vacio no divide entre cero`() {
        val progress = CollectionProgress(collected = 0, total = 0)

        assertEquals(0f, progress.fraction, 0f)
        assertEquals(0, progress.remaining)
    }

    @Test
    fun `el progreso nunca pasa del cien por cien ni da restantes negativos`() {
        // Puede ocurrir de forma transitoria: el tamano del catalogo se lee una
        // vez y la coleccion es un flujo vivo.
        val progress = CollectionProgress(collected = 12, total = 10)

        assertEquals(1f, progress.fraction, 0f)
        assertEquals(0, progress.remaining)
    }

    @Test
    fun `la etiqueta de progreso lleva separador de millares`() {
        assertEquals(
            "37 de 6.419 especies",
            CollectionProgress(collected = 37, total = 6_419).label,
        )
    }

    // --- formatThousands ---------------------------------------------------

    @Test
    fun `el separador de millares no depende del Locale`() {
        assertEquals("0", formatThousands(0))
        assertEquals("999", formatThousands(999))
        assertEquals("1.000", formatThousands(1_000))
        assertEquals("6.419", formatThousands(6_419))
        assertEquals("12.345", formatThousands(12_345))
        assertEquals("1.234.567", formatThousands(1_234_567))
    }

    // --- Datos de apoyo ----------------------------------------------------

    private fun List<CollectedSpecies>.ids() = map { it.species.speciesId }

    private val mirlo = species("turmer", "Mirlo común", "Turdus merula", "Turdidae")
    private val zorzal = species("turphi", "Zorzal común", "Turdus philomelos", "Turdidae")
    private val carbonero = species("parmaj", "Carbonero común", "Parus major", "Paridae")
    private val herrerillo =
        species("cyacae", "Herrerillo común", "Cyanistes caeruleus", "Paridae")

    private fun species(
        id: String,
        common: String,
        scientific: String,
        family: String,
    ) = Species(
        speciesId = id,
        scientificName = scientific,
        genus = scientific.substringBefore(' '),
        family = family,
        order = "Passeriformes",
        commonName = common,
    )

    private fun catalogOf(vararg entries: Species) = entries.associateBy { it.speciesId }

    private fun sighting(
        id: Long,
        speciesId: String,
        timestamp: Long,
        tier: RarityTier = RarityTier.COMMON,
    ) = Sighting(
        id = id,
        speciesId = speciesId,
        timestamp = timestamp,
        rarity = tier,
        source = ObservationSource.AUDIO,
        confidence = 0.9f,
        mediaPath = null,
    )

    private fun entryOf(target: Species) = CollectedSpecies(
        species = target,
        sightings = listOf(sighting(id = 1, speciesId = target.speciesId, timestamp = 1_000)),
    )
}

/**
 * El formato numerico no depende del Locale del dispositivo: la interfaz esta
 * en español fijo, asi que los separadores tambien.
 */
class FormatPercentTest {

    @Test
    fun `usa coma decimal y no punto`() {
        // El bug original: los miles salian bien ("6.419") y el decimal mal
        // ("0.0 %") en la misma linea, porque String.format sigue el Locale.
        assertEquals("0,0", formatPercent(0f))
        assertEquals("12,5", formatPercent(0.125f))
    }

    @Test
    fun `un decimal basta para que se vea avance con miles de especies`() {
        // Con 6.419 especies, el porcentaje entero se queda clavado en 0 hasta
        // la especie 32 y parece que la app no registra nada.
        assertEquals("0,1", formatPercent(1f / 6419 * 5))
    }

    @Test
    fun `satura en los extremos`() {
        assertEquals("100,0", formatPercent(1f))
        assertEquals("0,0", formatPercent(-1f))
        assertEquals("100,0", formatPercent(5f))
    }
}

/**
 * Los numeros de coleccionista. Se prueban porque "nuevas este mes" es
 * exactamente el tipo de cuenta que sale mal por una frontera de calendario.
 */
class CollectionStatsTest {

    private val zone = java.time.ZoneId.of("Europe/Madrid")

    private fun instant(year: Int, month: Int, day: Int): Long =
        java.time.ZonedDateTime.of(year, month, day, 10, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun species(id: String, family: String) = Species(
        speciesId = id,
        scientificName = "Genus $id",
        genus = "Genus",
        family = family,
        order = "Passeriformes",
        commonName = "Especie $id",
    )

    private fun entry(id: String, family: String, firstSeen: Long, lastSeen: Long = firstSeen, tier: RarityTier = RarityTier.COMMON) =
        CollectedSpecies(
            species = species(id, family),
            sightings = listOf(
                Sighting(2, id, lastSeen, tier, ObservationSource.AUDIO, 0.9f),
                Sighting(1, id, firstSeen, tier, ObservationSource.AUDIO, 0.9f),
            ),
        )

    @Test
    fun `cuenta especies, familias y notables`() {
        val stats = computeStats(
            listOf(
                entry("a", "Turdidae", instant(2026, 4, 2)),
                entry("b", "Turdidae", instant(2026, 3, 2), tier = RarityTier.RARE),
                entry("c", "Paridae", instant(2026, 1, 2), tier = RarityTier.EXCEPTIONAL),
            ),
            nowMillis = instant(2026, 4, 20),
            zoneId = zone,
        )

        assertEquals(3, stats.species)
        assertEquals(2, stats.families)
        assertEquals(2, stats.notable)
    }

    @Test
    fun `nuevas este mes usa la PRIMERA vez que se vio, no la ultima`() {
        // Una especie de enero que se vuelve a oir en abril no es nueva en abril.
        val stats = computeStats(
            listOf(
                entry("a", "Turdidae", firstSeen = instant(2026, 1, 5), lastSeen = instant(2026, 4, 5)),
                entry("b", "Turdidae", firstSeen = instant(2026, 4, 1)),
            ),
            nowMillis = instant(2026, 4, 20),
            zoneId = zone,
        )

        assertEquals(1, stats.newThisMonth)
    }

    @Test
    fun `este mes es el mes natural, no los ultimos treinta dias`() {
        val stats = computeStats(
            listOf(entry("a", "Turdidae", instant(2026, 3, 31))),
            nowMillis = instant(2026, 4, 1),
            zoneId = zone,
        )

        assertEquals(0, stats.newThisMonth)
    }

    @Test
    fun `la ultima nueva es la de primera vista mas reciente`() {
        val stats = computeStats(
            listOf(
                entry("vieja", "Turdidae", instant(2026, 1, 1), lastSeen = instant(2026, 4, 19)),
                entry("nueva", "Paridae", instant(2026, 4, 10)),
            ),
            nowMillis = instant(2026, 4, 20),
            zoneId = zone,
        )

        assertEquals("nueva", stats.latestLifer?.species?.speciesId)
    }

    @Test
    fun `una coleccion vacia da ceros y ninguna ultima`() {
        val stats = computeStats(emptyList(), nowMillis = instant(2026, 4, 20), zoneId = zone)

        assertEquals(0, stats.species)
        assertEquals(0, stats.families)
        assertEquals(null, stats.latestLifer)
    }

    @Test
    fun `lo desconocido no cuenta como notable`() {
        val stats = computeStats(
            listOf(entry("a", "Turdidae", instant(2026, 4, 2), tier = RarityTier.UNKNOWN)),
            nowMillis = instant(2026, 4, 20),
            zoneId = zone,
        )

        assertEquals(0, stats.notable)
        assertEquals(RarityTier.UNKNOWN, stats.latestLifer?.rarity)
    }
}
