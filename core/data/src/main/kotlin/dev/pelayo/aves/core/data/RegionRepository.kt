package dev.pelayo.aves.core.data

import dev.pelayo.aves.core.common.Geohash
import dev.pelayo.aves.core.database.dao.RegionDao
import dev.pelayo.aves.core.database.entity.RegionCellEntity
import dev.pelayo.aves.core.database.entity.SpeciesFrequencyEntity
import dev.pelayo.aves.core.ml.api.ContextPriorProvider
import dev.pelayo.aves.core.ml.api.ObservationContext
import dev.pelayo.aves.core.ml.api.SpeciesPrior
import dev.pelayo.aves.core.model.RarityTier
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Recuento de ocurrencias de una celda, tal y como lo devuelve la fuente. */
data class OccurrenceSummary(
    /** Total de observaciones de aves en la celda y mes. Es el denominador de la rareza. */
    val total: Long,
    /** Nombre cientifico crudo (con autoria) -> recuento. */
    val byScientificName: Map<String, Long>,
)

/**
 * De donde salen los datos de ocurrencia.
 *
 * Es una interfaz nuestra y no el cliente de GBIF directamente para que el
 * repositorio se pueda probar con un doble sin red, y para que cambiar de fuente
 * no obligue a tocar la logica de rareza.
 */
interface OccurrenceSource {
    suspend fun occurrencesInCell(
        latitude: Double,
        longitude: Double,
        radiusKm: Int,
        month: Int,
    ): OccurrenceSummary
}

/**
 * Calcula y cachea la rareza de cada especie por celda geografica y mes.
 *
 * La rareza no es propiedad de la especie sino del trio (especie, lugar, mes):
 * un mirlo es trivial en Madrid y notable en Islandia. De ahi la clave compuesta
 * de la cache.
 *
 * Tambien actua de [ContextPriorProvider]: los mismos datos que dan la rareza
 * sirven para filtrar las predicciones del modelo. Es la version que funciona en
 * los dos flavors; el flavor `personal` tiene ademas el meta-modelo de BirdNET,
 * que es mas fino porque se entreno junto al clasificador.
 */
class RegionRepository(
    private val regionDao: RegionDao,
    private val occurrenceSource: OccurrenceSource,
    private val radiusKm: Int = DEFAULT_RADIUS_KM,
    private val freshnessMillis: Long = DEFAULT_FRESHNESS_MILLIS,
) : ContextPriorProvider {

    private val mutex = Mutex()
    private var cachedPrior: CachedPrior? = null

    private class CachedPrior(
        val cellId: String,
        val month: Int,
        val multipliers: Map<String, Float>,
    )

    /**
     * Descarga y cachea los datos de la celda si hacen falta.
     *
     * Doce llamadas, una por mes, y despues la app funciona sin red en esa zona
     * indefinidamente. Se hacen los doce meses de golpe y no solo el actual
     * porque el coste marginal es bajo y evita que salir a pajarear en otra
     * estacion dependa de tener cobertura.
     *
     * @return numero de meses efectivamente refrescados.
     */
    suspend fun ensureCellCached(
        latitude: Double,
        longitude: Double,
        now: Long,
        force: Boolean = false,
    ): Int {
        val cellId = Geohash.encode(latitude, longitude, CELL_PRECISION)
        val existing = regionDao.findCell(cellId)
        val fresh = existing != null && now - existing.lastRefreshed < freshnessMillis
        if (fresh && !force && regionDao.frequencyCountFor(cellId) > 0) return 0

        var refreshed = 0
        var totalAcrossYear = 0L
        val frequencies = ArrayList<SpeciesFrequencyEntity>()

        for (month in 1..12) {
            val summary = occurrenceSource.occurrencesInCell(latitude, longitude, radiusKm, month)
            totalAcrossYear += summary.total
            frequencies += toFrequencies(cellId, month, summary)
            refreshed++
        }

        regionDao.replaceCell(
            cell = RegionCellEntity(
                cellId = cellId,
                centerLat = latitude,
                centerLon = longitude,
                totalObservations = totalAcrossYear,
                lastRefreshed = now,
            ),
            frequencies = frequencies,
        )
        invalidate()
        return refreshed
    }

    /** Tier de rareza de una especie aqui y ahora, o null si la celda no esta cacheada. */
    suspend fun rarityOf(
        speciesId: String,
        latitude: Double,
        longitude: Double,
        month: Int,
    ): RarityTier? {
        val cellId = Geohash.encode(latitude, longitude, CELL_PRECISION)
        if (regionDao.findCell(cellId) == null) return null
        val frequency = regionDao.frequencyOf(cellId, speciesId, month)
            // Sin registro local la especie no es "desconocida", es excepcional:
            // GBIF tiene cientos de millones de observaciones, y que ninguna sea
            // de esta especie aqui significa algo.
            ?: return RarityTier.EXCEPTIONAL
        return RarityTier.fromLevel(frequency.rarityTier)
    }

    override suspend fun prior(context: ObservationContext): SpeciesPrior {
        val latitude = context.latitude
        val longitude = context.longitude
        if (latitude == null || longitude == null) return SpeciesPrior.Uniform

        val cellId = Geohash.encode(latitude, longitude, CELL_PRECISION)
        val month = monthOf(context.epochMillis)

        val multipliers = mutex.withLock {
            val cached = cachedPrior
            if (cached != null && cached.cellId == cellId && cached.month == month) {
                cached.multipliers
            } else {
                val rows = regionDao.frequenciesFor(cellId, month)
                val built = rows.associate { it.speciesId to multiplierFor(it.share) }
                cachedPrior = CachedPrior(cellId, month, built)
                built
            }
        }

        // Sin datos cacheados no se filtra nada. Es preferible no filtrar a
        // filtrar con informacion inventada.
        if (multipliers.isEmpty()) return SpeciesPrior.Uniform

        return object : SpeciesPrior {
            override fun get(speciesId: String): Float = multipliers[speciesId] ?: FLOOR
        }
    }

    fun invalidate() {
        cachedPrior = null
    }

    private fun toFrequencies(
        cellId: String,
        month: Int,
        summary: OccurrenceSummary,
    ): List<SpeciesFrequencyEntity> {
        if (summary.total <= 0L) return emptyList()

        // Varias entradas de GBIF pueden plegarse sobre la misma especie
        // (subespecies, sinonimos), asi que se agregan antes de dividir.
        val bySpecies = HashMap<String, Long>()
        for ((rawName, count) in summary.byScientificName) {
            val speciesId = GbifSpeciesNames.toSpeciesId(rawName) ?: continue
            bySpecies[speciesId] = (bySpecies[speciesId] ?: 0L) + count
        }

        return bySpecies.map { (speciesId, count) ->
            val share = count.toDouble() / summary.total.toDouble()
            SpeciesFrequencyEntity(
                cellId = cellId,
                speciesId = speciesId,
                month = month,
                occurrenceCount = count,
                share = share.toFloat(),
                rarityTier = RarityTier.fromShare(share.toFloat()).level,
            )
        }
    }

    companion object {
        /** ~40x20 km. Fina para que el prior sirva, gruesa para no recargar al cruzar la ciudad. */
        const val CELL_PRECISION = 4

        const val DEFAULT_RADIUS_KM = 50

        /** Los datos de GBIF cambian despacio; refrescar cada 30 dias sobra. */
        const val DEFAULT_FRESHNESS_MILLIS = 30L * 24 * 60 * 60 * 1000

        /**
         * Suelo del prior. NUNCA cero.
         *
         * Con un cero, una especie sin registros locales dejaria de ser
         * detectable por construccion, y las primeras victimas serian las
         * rarezas: precisamente lo mas interesante que puede pasar en esta app.
         * Un divagante es raro justamente porque GBIF no lo tiene registrado
         * aqui.
         */
        const val FLOOR = 0.05f

        /**
         * Discretizar en tramos es mas robusto que multiplicar por la cuota
         * cruda: absorbe el ruido de muestreo de GBIF, que en zonas poco
         * visitadas es considerable.
         */
        internal fun multiplierFor(share: Float): Float = when {
            share >= 1e-3f -> 1.0f
            share >= 1e-4f -> 0.8f
            share > 0f -> 0.5f
            else -> FLOOR
        }

        fun monthOf(epochMillis: Long): Int {
            val calendar = java.util.Calendar.getInstance()
            calendar.timeInMillis = epochMillis
            return calendar.get(java.util.Calendar.MONTH) + 1
        }
    }
}
