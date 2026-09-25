package dev.pelayo.aves.core.data

import dev.pelayo.aves.core.common.Geohash
import dev.pelayo.aves.core.database.AvesDatabase
import dev.pelayo.aves.core.database.entity.SeasonalityEntity
import dev.pelayo.aves.core.model.Species
import dev.pelayo.aves.core.network.GbifClient
import kotlin.coroutines.cancellation.CancellationException

/**
 * Cuando se ve una especie por aqui: sus registros en GBIF de cada mes, en
 * unos 50 km alrededor de la celda.
 *
 * Es lo que distingue a una residente de una estival o de una de paso, y lo que
 * dice si merece la pena salir a buscarla ahora. `GbifClient.monthlyProfile`
 * existia desde el principio y no lo usaba nadie.
 *
 * Se pregunta por el centro de la celda geohash-4, como la rareza, y no por la
 * posicion del usuario. Se guarda en Room y se refresca cada [freshnessMillis]:
 * la forma del ano de un ave no cambia de un mes para otro.
 */
class SeasonalityRepository(
    database: AvesDatabase,
    private val gbif: GbifClient,
    private val freshnessMillis: Long = DEFAULT_FRESHNESS_MILLIS,
) {
    private val dao = database.seasonalityDao()

    /**
     * Registros de enero a diciembre (12 valores), o null si no hay datos ni
     * forma de conseguirlos (sin red y sin cache).
     */
    suspend fun profile(species: Species, latitude: Double, longitude: Double, nowMillis: Long): IntArray? {
        val cellId = Geohash.encode(latitude, longitude, RegionRepository.CELL_PRECISION)
        val cached = dao.find(species.speciesId, cellId)
        if (cached != null && nowMillis - cached.fetchedAt < freshnessMillis) return parse(cached.monthlyCounts)

        val (centerLat, centerLon) = Geohash.center(cellId)
        val counts = try {
            val byMonth = species.gbifSpeciesKey?.let { gbif.monthlyProfile(it, centerLat, centerLon) }
                ?: gbif.monthlyProfile(species.scientificName, centerLat, centerLon)
            IntArray(MONTHS) { index -> (byMonth[index + 1] ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Sin red: lo que hubiera, aunque sea viejo, es mejor que nada.
            return cached?.let { parse(it.monthlyCounts) }
        }

        dao.upsert(SeasonalityEntity(species.speciesId, cellId, counts.joinToString(","), nowMillis))
        return counts
    }

    companion object {
        const val MONTHS = 12

        /** Noventa dias, como las listas de zona. */
        const val DEFAULT_FRESHNESS_MILLIS = 90L * 24 * 60 * 60 * 1000

        internal fun parse(stored: String): IntArray? {
            val values = stored.split(',').mapNotNull { it.trim().toIntOrNull() }
            return if (values.size == MONTHS) values.toIntArray() else null
        }
    }
}
