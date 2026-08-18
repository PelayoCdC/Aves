package dev.pelayo.aves.core.data

import dev.pelayo.aves.core.database.dao.RegionDao
import dev.pelayo.aves.core.database.entity.RegionCellEntity
import dev.pelayo.aves.core.database.entity.SpeciesFrequencyEntity
import dev.pelayo.aves.core.ml.api.ObservationContext
import dev.pelayo.aves.core.model.RarityTier
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionRepositoryTest {

    /** Los DAO de Room son interfaces, asi que se pueden doblar sin emulador. */
    private class FakeRegionDao : RegionDao {
        val cells = HashMap<String, RegionCellEntity>()
        val frequencies = ArrayList<SpeciesFrequencyEntity>()

        override suspend fun findCell(cellId: String) = cells[cellId]
        override suspend fun upsertCell(cell: RegionCellEntity) { cells[cell.cellId] = cell }
        override suspend fun upsertFrequencies(frequencies: List<SpeciesFrequencyEntity>) {
            this.frequencies += frequencies
        }
        override suspend fun frequenciesFor(cellId: String, month: Int) =
            frequencies.filter { it.cellId == cellId && it.month == month }
        override suspend fun frequencyOf(cellId: String, speciesId: String, month: Int) =
            frequencies.firstOrNull {
                it.cellId == cellId && it.speciesId == speciesId && it.month == month
            }
        override suspend fun frequencyCountFor(cellId: String) =
            frequencies.count { it.cellId == cellId }
        override suspend fun deleteFrequenciesFor(cellId: String) {
            frequencies.removeAll { it.cellId == cellId }
        }
    }

    private class FakeSource(
        private val perMonth: Map<Int, OccurrenceSummary>,
        private val fallback: OccurrenceSummary = OccurrenceSummary(0, emptyMap()),
    ) : OccurrenceSource {
        var calls = 0
            private set

        override suspend fun occurrencesInCell(
            latitude: Double,
            longitude: Double,
            radiusKm: Int,
            month: Int,
        ): OccurrenceSummary {
            calls++
            return perMonth[month] ?: fallback
        }
    }

    // Datos con la forma real de GBIF: nombre cientifico con autoria.
    private val juneNearOviedo = OccurrenceSummary(
        total = 40_839,
        byScientificName = mapOf(
            "Turdus merula Linnaeus, 1758" to 1_802L,          // cuota 4,4% -> muy comun
            "Erithacus rubecula (Linnaeus, 1758)" to 1_155L,   // cuota 2,8% -> muy comun
            "Parus major Linnaeus, 1758" to 45L,               // cuota 1,1e-3 -> comun
            "Alcedo atthis (Linnaeus, 1758)" to 5L,            // cuota 1,2e-4 -> poco comun
            "Ciconia nigra (Linnaeus, 1758)" to 2L,            // cuota 4,9e-5 -> rara
            "Anas platyrhynchos x Anas acuta" to 99L,          // hibrido: se descarta
        ),
    )

    private val oviedo = 43.3619 to -5.8494

    private fun repository(source: OccurrenceSource, dao: RegionDao = FakeRegionDao()) =
        RegionRepository(dao, source)

    @Test
    fun `cachea los doce meses de una celda`() = runTest {
        // Se bajan los doce de golpe para que salir a pajarear en otra estacion
        // no dependa de tener cobertura.
        val source = FakeSource(mapOf(6 to juneNearOviedo))
        val dao = FakeRegionDao()

        val refreshed = repository(source, dao).ensureCellCached(oviedo.first, oviedo.second, NOW)

        assertEquals(12, refreshed)
        assertEquals(12, source.calls)
        assertEquals(1, dao.cells.size)
    }

    @Test
    fun `no vuelve a descargar una celda fresca`() = runTest {
        val source = FakeSource(mapOf(6 to juneNearOviedo))
        val dao = FakeRegionDao()
        val repository = repository(source, dao)

        repository.ensureCellCached(oviedo.first, oviedo.second, NOW)
        val second = repository.ensureCellCached(oviedo.first, oviedo.second, NOW + 1_000)

        assertEquals("la segunda vez no debe tocar la red", 0, second)
        assertEquals(12, source.calls)
    }

    @Test
    fun `vuelve a descargar cuando los datos caducan`() = runTest {
        val source = FakeSource(mapOf(6 to juneNearOviedo))
        val repository = repository(source)

        repository.ensureCellCached(oviedo.first, oviedo.second, NOW)
        val stale = NOW + RegionRepository.DEFAULT_FRESHNESS_MILLIS + 1
        val second = repository.ensureCellCached(oviedo.first, oviedo.second, stale)

        assertEquals(12, second)
    }

    @Test
    fun `deriva los tiers de rareza de las cuotas reales`() = runTest {
        val dao = FakeRegionDao()
        repository(FakeSource(mapOf(6 to juneNearOviedo)), dao)
            .ensureCellCached(oviedo.first, oviedo.second, NOW)
        val repository = repository(FakeSource(emptyMap()), dao)

        // Un mirlo cerca de Oviedo es trivial; un martin pescador no.
        assertEquals(
            RarityTier.VERY_COMMON,
            repository.rarityOf("turdus_merula", oviedo.first, oviedo.second, 6),
        )
        assertEquals(
            RarityTier.COMMON,
            repository.rarityOf("parus_major", oviedo.first, oviedo.second, 6),
        )
        assertEquals(
            RarityTier.UNCOMMON,
            repository.rarityOf("alcedo_atthis", oviedo.first, oviedo.second, 6),
        )
        assertEquals(
            RarityTier.RARE,
            repository.rarityOf("ciconia_nigra", oviedo.first, oviedo.second, 6),
        )
    }

    @Test
    fun `una especie sin registros locales es excepcional, no desconocida`() = runTest {
        val dao = FakeRegionDao()
        repository(FakeSource(mapOf(6 to juneNearOviedo)), dao)
            .ensureCellCached(oviedo.first, oviedo.second, NOW)

        // GBIF tiene cientos de millones de observaciones: que ninguna sea de
        // esta especie aqui significa algo.
        assertEquals(
            RarityTier.EXCEPTIONAL,
            repository(FakeSource(emptyMap()), dao)
                .rarityOf("struthio_camelus", oviedo.first, oviedo.second, 6),
        )
    }

    @Test
    fun `sin celda cacheada no inventa una rareza`() = runTest {
        assertNull(
            repository(FakeSource(emptyMap()))
                .rarityOf("turdus_merula", oviedo.first, oviedo.second, 6),
        )
    }

    @Test
    fun `descarta hibridos al agregar`() = runTest {
        val dao = FakeRegionDao()
        repository(FakeSource(mapOf(6 to juneNearOviedo)), dao)
            .ensureCellCached(oviedo.first, oviedo.second, NOW)

        // El hibrido traia 99 ocurrencias y no debe sumarlas a ninguna especie.
        assertTrue(dao.frequencies.none { it.speciesId.contains("anas") })
    }

    @Test
    fun `el prior nunca vale cero`() = runTest {
        // Con un cero, un divagante dejaria de ser detectable por construccion,
        // y es justo lo mas interesante que puede pasar en esta app.
        val dao = FakeRegionDao()
        val repository = repository(FakeSource(mapOf(6 to juneNearOviedo)), dao)
        repository.ensureCellCached(oviedo.first, oviedo.second, NOW)

        val prior = repository.prior(
            ObservationContext(oviedo.first, oviedo.second, JUNE_MILLIS, contextInfluence = 1f),
        )

        assertTrue(prior["especie_que_no_existe"] > 0f)
        assertEquals(RegionRepository.FLOOR, prior["especie_que_no_existe"], 1e-6f)
    }

    @Test
    fun `el prior favorece lo comun sin anular lo raro`() = runTest {
        val dao = FakeRegionDao()
        val repository = repository(FakeSource(mapOf(6 to juneNearOviedo)), dao)
        repository.ensureCellCached(oviedo.first, oviedo.second, NOW)

        val prior = repository.prior(
            ObservationContext(oviedo.first, oviedo.second, JUNE_MILLIS, contextInfluence = 1f),
        )

        assertTrue(prior["turdus_merula"] > prior["alcedo_atthis"])
        assertTrue("el martin pescador sigue siendo detectable", prior["alcedo_atthis"] > 0f)
    }

    @Test
    fun `sin ubicacion no se filtra nada`() = runTest {
        // Es preferible no filtrar a filtrar con informacion inventada.
        val prior = repository(FakeSource(emptyMap()))
            .prior(ObservationContext(epochMillis = JUNE_MILLIS))

        assertEquals(1f, prior["turdus_merula"], 0f)
        assertEquals(1f, prior["lo_que_sea"], 0f)
    }

    @Test
    fun `sin datos cacheados tampoco se filtra`() = runTest {
        val prior = repository(FakeSource(emptyMap())).prior(
            ObservationContext(oviedo.first, oviedo.second, JUNE_MILLIS),
        )

        assertEquals(1f, prior["turdus_merula"], 0f)
    }

    private companion object {
        const val NOW = 1_750_000_000_000L

        /** Un instante de junio, el mes de los datos de prueba. */
        val JUNE_MILLIS: Long = java.util.GregorianCalendar(2026, 5, 15).timeInMillis
    }
}
