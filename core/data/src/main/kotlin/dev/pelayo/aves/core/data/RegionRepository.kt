package dev.pelayo.aves.core.data

import dev.pelayo.aves.core.common.Geohash
import dev.pelayo.aves.core.database.dao.RegionDao
import dev.pelayo.aves.core.database.entity.RegionCellEntity
import dev.pelayo.aves.core.database.entity.RegionMonthEntity
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
 * los dos flavors; cada flavor tiene ademas el geomodelo de su clasificador,
 * que es mas fino porque se entreno junto a el.
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
     * Descarga y cachea los meses de la celda que falten o hayan caducado.
     *
     * El mes de [now] se pide PRIMERO: es el que hace falta para la escucha en
     * curso. Los otros once van despues, y cada uno se guarda en cuanto llega.
     * Asi una escucha corta con mala cobertura deja al menos el mes actual, y la
     * siguiente salida en otra estacion no depende de tener red.
     *
     * Antes se escribia todo o nada, y si la descarga se cancelaba a medias la
     * celda nunca llegaba a existir.
     *
     * @return numero de meses efectivamente descargados.
     */
    suspend fun ensureCellCached(
        latitude: Double,
        longitude: Double,
        now: Long,
        force: Boolean = false,
    ): Int {
        val cellId = Geohash.encode(latitude, longitude, CELL_PRECISION)
        val current = monthOf(now)
        val order = (0 until 12).map { offset -> ((current - 1 + offset) % 12) + 1 }

        var refreshed = 0
        for (month in order) {
            if (ensureMonthCached(cellId, month, now, force)) refreshed++
        }
        return refreshed
    }

    /**
     * Solo el mes que hace falta ahora. Es lo primero que se llama al empezar
     * una escucha, y lo unico que se necesita para que la rareza funcione hoy.
     *
     * @return true si hubo que descargarlo.
     */
    suspend fun ensureCurrentMonthCached(latitude: Double, longitude: Double, now: Long): Boolean {
        val cellId = Geohash.encode(latitude, longitude, CELL_PRECISION)
        return ensureMonthCached(cellId, monthOf(now), now, force = false)
    }

    private suspend fun ensureMonthCached(
        cellId: String,
        month: Int,
        now: Long,
        force: Boolean,
    ): Boolean {
        val existing = regionDao.findMonth(cellId, month)
        val fresh = existing != null && now - existing.fetchedAt < freshnessMillis
        if (fresh && !force) return false

        // Se pregunta por el centro de la celda, no por donde esta el usuario:
        // la cache es por celda, y a GBIF no le hace falta saber mas. Antes se
        // mandaba la posicion exacta, y ademas la consulta quedaba centrada en
        // el primer sitio desde el que se abrio la celda.
        val (centerLat, centerLon) = Geohash.center(cellId)
        val summary = occurrenceSource.occurrencesInCell(centerLat, centerLon, radiusKm, month)
        val cell = regionDao.findCell(cellId)
        regionDao.replaceMonth(
            cell = RegionCellEntity(
                cellId = cellId,
                centerLat = centerLat,
                centerLon = centerLon,
                // El total de la celda es la suma de los meses que se conocen.
                totalObservations = (cell?.totalObservations ?: 0L) -
                    (existing?.totalObservations ?: 0L) + summary.total,
                lastRefreshed = now,
            ),
            month = RegionMonthEntity(
                cellId = cellId,
                month = month,
                fetchedAt = now,
                totalObservations = summary.total,
            ),
            frequencies = toFrequencies(cellId, month, summary),
        )
        invalidate()
        return true
    }

    /**
     * Tier de rareza de una especie aqui y ahora, o null si ese mes de esa celda
     * no esta descargado todavia o no tiene datos para decidir. Null quiere
     * decir "no se", y quien llama debe tratarlo asi ([RarityTier.UNKNOWN]),
     * nunca inventarse un tier.
     */
    suspend fun rarityOf(
        speciesId: String,
        latitude: Double,
        longitude: Double,
        month: Int,
    ): RarityTier? {
        val cellId = Geohash.encode(latitude, longitude, CELL_PRECISION)
        val monthData = regionDao.findMonth(cellId, month) ?: return null
        val frequency = regionDao.frequencyOf(cellId, speciesId, month)
        if (frequency != null) return RarityTier.fromLevel(frequency.rarityTier)

        // Sin registro local la especie es excepcional... solo si la celda tiene
        // datos de sobra para que la ausencia signifique algo. En alta mar, en
        // una sierra poco visitada o en un mes con cuatro registros, TODAS las
        // especies salian excepcionales, que es justo el fallo que [RarityTier]
        // dice que no hay que repetir.
        return if (monthData.totalObservations >= MIN_OBSERVATIONS_FOR_ABSENCE) {
            RarityTier.EXCEPTIONAL
        } else {
            null
        }
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

        /**
         * Registros que tiene que tener un mes de una celda para que NO
         * encontrar una especie la haga excepcional.
         *
         * Sale de los cortes de [RarityTier.fromShare]: "rara" es una cuota por
         * debajo de 1e-4, asi que con menos de 10.000 registros un solo avistamiento
         * ya pesa mas que eso, y no verla no distingue "excepcional" de "nadie ha
         * mirado". Por debajo se responde "no se".
         */
        const val MIN_OBSERVATIONS_FOR_ABSENCE = 10_000L

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
