package dev.pelayo.aves.core.data

import dev.pelayo.aves.core.database.AvesDatabase
import dev.pelayo.aves.core.database.dao.RegionSpeciesRow
import dev.pelayo.aves.core.database.entity.PokedexPreferencesEntity
import dev.pelayo.aves.core.database.entity.RegionChecklistEntity
import dev.pelayo.aves.core.database.entity.RegionChecklistSpeciesEntity
import dev.pelayo.aves.core.model.Species
import dev.pelayo.aves.core.network.GbifClient
import dev.pelayo.aves.core.network.GbifLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/** A que escala se filtra la Pokedex. De mas fina a mas gruesa. */
enum class ZoneLevel {
    PROVINCE,
    REGION,
    COUNTRY,

    /** Un lugar que no es administrativo: Doñana, el delta del Ebro. Solo elegido a mano. */
    PLACE,
    WORLD,
    ;

    companion object {
        fun fromName(name: String?): ZoneLevel = entries.firstOrNull { it.name == name } ?: WORLD
    }
}

/**
 * La zona del usuario, a los tres niveles. Cualquiera puede faltar: mar
 * adentro no hay provincia, y sin ubicacion solo se conoce (con suerte) el pais.
 */
data class HomePlace(
    val countryCode: String? = null,
    val countryName: String? = null,
    val regionId: String? = null,
    val regionName: String? = null,
    val provinceId: String? = null,
    val provinceName: String? = null,
) {
    val isKnown: Boolean get() = countryCode != null || regionId != null || provinceId != null

    fun has(level: ZoneLevel): Boolean = when (level) {
        ZoneLevel.PROVINCE -> provinceId != null
        ZoneLevel.REGION -> regionId != null
        ZoneLevel.COUNTRY -> countryCode != null
        ZoneLevel.PLACE -> false
        ZoneLevel.WORLD -> true
    }

    /** El identificador GADM del pais ("ESP"), que es lo que pide la lista de sus regiones. */
    val countryGadmId: String?
        get() = countryCode?.let { code ->
            runCatching { Locale("", code).isO3Country }.getOrNull()?.takeIf { it.isNotBlank() }
        }

    companion object {
        /**
         * La zona que queda tras un intento de deteccion.
         *
         * Un intento fallido no puede dejar las cosas peor de lo que estaban:
         * "Actualizar mi zona" sin cobertura no debe borrar la provincia que ya
         * se sabia. Solo se tira lo anterior si el movil dice que ha cambiado de
         * pais, porque entonces la provincia vieja seguro que ya no vale.
         *
         * @param previous lo que habia guardado.
         * @param located lo que ha dicho el geocodificador, o null si no habia
         *   ubicacion o fallo la red.
         * @param fallbackCountryCode el pais del movil, ya normalizado.
         */
        fun merge(previous: HomePlace, located: HomePlace?, fallbackCountryCode: String?): HomePlace = when {
            // Mar adentro el geocodificador contesta, pero sin nada dentro.
            located != null && located.isKnown -> located
            previous.isKnown && (fallbackCountryCode == null || fallbackCountryCode == previous.countryCode) -> previous
            else -> HomePlace(countryCode = fallbackCountryCode)
        }
    }
}

/**
 * Una zona concreta por la que filtrar.
 *
 * @param id lo que entiende GBIF: el codigo ISO del pais o el GADM de la
 *   region/provincia; para un lugar, un identificador propio ("PLACE:donana").
 *   Null solo para el mundo entero.
 * @param geometry el poligono WKT de un lugar que no es administrativo. Null
 *   para las zonas administrativas y el mundo.
 */
data class PokedexZone(
    val level: ZoneLevel,
    val id: String?,
    val name: String,
    val geometry: String? = null,
) {
    companion object {
        val World = PokedexZone(ZoneLevel.WORLD, id = null, name = "")
    }
}

/**
 * Una zona que se puede elegir en el navegador de zonas.
 *
 * @param kind como se llama ese tipo de zona en su pais ("Comunidad Autónoma",
 *   "Provincia"). Null si no se sabe.
 */
data class ZoneChoice(
    val zone: PokedexZone,
    val kind: String? = null,
)

/** Como quiere ver el usuario su Pokedex. */
data class PokedexPreferences(
    val zoneLevel: ZoneLevel,
    val showMissing: Boolean,
    val home: HomePlace,
    /** false hasta que se ha intentado detectar la zona al menos una vez. */
    val isInitialized: Boolean,
    /** Una zona elegida a mano, que manda sobre la detectada mientras exista. */
    val chosen: PokedexZone? = null,
) {
    /**
     * La zona que toca: la elegida a mano si la hay; si no, la del nivel pedido,
     * cayendo al nivel siguiente si ese no se conoce. Alguien sin ubicacion que
     * pide "provincia" ve su pais, no un error.
     */
    val zone: PokedexZone
        get() {
            chosen?.let { return it }
            val level = ZoneLevel.entries.first { it >= zoneLevel && home.has(it) }
            return when (level) {
                ZoneLevel.PROVINCE -> PokedexZone(level, home.provinceId, home.provinceName.orEmpty())
                ZoneLevel.REGION -> PokedexZone(level, home.regionId, home.regionName.orEmpty())
                ZoneLevel.COUNTRY -> PokedexZone(level, home.countryCode, home.countryName.orEmpty())
                ZoneLevel.PLACE, ZoneLevel.WORLD -> PokedexZone.World
            }
        }
}

/** Una especie de una zona, con lo frecuente que es alli. */
data class ZoneSpecies(
    val species: Species,
    /** Cuota de los registros de aves de la zona. Null fuera de una zona (el mundo). */
    val share: Float?,
)

/** De donde salen las listas y la geografia. Interfaz para poder probar sin red. */
interface ChecklistSource {
    suspend fun speciesIn(zone: PokedexZone): OccurrenceSummary

    suspend fun placeAt(latitude: Double, longitude: Double): HomePlace

    /** Las zonas dentro de [gadmId]: las regiones de un pais, las provincias de una region. */
    suspend fun subdivisions(gadmId: String): List<ZoneChoice>
}

class GbifChecklistSource(private val client: GbifClient) : ChecklistSource {

    override suspend fun speciesIn(zone: PokedexZone): OccurrenceSummary {
        val id = requireNotNull(zone.id) { "El mundo entero no se le pide a GBIF" }
        val summary = when {
            zone.geometry != null -> client.speciesInRegion(geometry = zone.geometry)
            zone.level == ZoneLevel.COUNTRY -> client.speciesInRegion(countryCode = id)
            else -> client.speciesInRegion(gadmGid = id)
        }
        return OccurrenceSummary(total = summary.total, byScientificName = summary.speciesCounts)
    }

    override suspend fun placeAt(latitude: Double, longitude: Double): HomePlace {
        // Solo las capas que contienen el punto: la "mas cercana" de alguien que
        // esta en un barco no es su provincia.
        val layers = client.reverseGeocode(latitude, longitude).filter { it.distance == 0.0 }
        val country = layers.firstOrNull { it.type == GbifLocation.TYPE_COUNTRY }
        val region = layers.firstOrNull { it.type == GbifLocation.TYPE_REGION }
        val province = layers.firstOrNull { it.type == GbifLocation.TYPE_PROVINCE }
        return HomePlace(
            countryCode = country?.isoCountryCode2Digit?.takeIf { it.isNotBlank() },
            countryName = country?.name?.takeIf { it.isNotBlank() },
            regionId = region?.id?.takeIf { it.isNotBlank() },
            regionName = region?.name?.takeIf { it.isNotBlank() },
            provinceId = province?.id?.takeIf { it.isNotBlank() },
            provinceName = province?.name?.takeIf { it.isNotBlank() },
        )
    }

    override suspend fun subdivisions(gadmId: String): List<ZoneChoice> =
        client.subdivisions(gadmId)
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .map { region ->
                ZoneChoice(
                    zone = PokedexZone(
                        level = if (region.gadmLevel <= 1) ZoneLevel.REGION else ZoneLevel.PROVINCE,
                        id = region.id,
                        name = region.name,
                    ),
                    kind = region.typeName,
                )
            }
}

/**
 * Las aves de tu zona: que lista toca, descargarla y recordarla.
 *
 * "Filtrar la Pokedex por zona" necesita dos cosas que la app no tenia: saber
 * donde vive el usuario a escala administrativa (pais, region, provincia) y la
 * lista de aves de ese sitio. Las dos salen de GBIF, sin clave y sin clausula no
 * comercial: la geocodificacion inversa da los identificadores GADM, y una
 * busqueda de ocurrencias facetada por nombre da la lista con sus frecuencias.
 *
 * Todo se guarda. La zona se detecta una vez (no hay cuentas de usuario: "tu
 * zona" es donde estabas la primera vez, y se puede volver a detectar) y cada
 * lista se descarga una vez y se refresca cada tres meses, asi que la Pokedex
 * filtrada funciona sin red.
 */
class ChecklistRepository(
    database: AvesDatabase,
    private val source: ChecklistSource,
    private val freshnessMillis: Long = DEFAULT_FRESHNESS_MILLIS,
) {
    private val checklistDao = database.checklistDao()
    private val speciesDao = database.speciesDao()
    private val downloads = Mutex()

    val preferences: Flow<PokedexPreferences> = checklistDao.preferences().map { it.toDomain() }

    /** Las listas que hay en el movil, vivas: cuando entra una, quien las mire se entera. */
    val cachedChecklists: Flow<List<RegionChecklistEntity>> = checklistDao.checklists()

    /** Lo que se puede ver en una zona. Para el mundo, todo lo que algun modelo identifica. */
    fun speciesIn(zone: PokedexZone): Flow<List<ZoneSpecies>> {
        val id = zone.id
        return if (zone.level == ZoneLevel.WORLD || id == null) {
            speciesDao.detectableSpecies().map { rows -> rows.map { ZoneSpecies(it.toDomain(), share = null) } }
        } else {
            checklistDao.speciesInRegion(id).map { rows -> rows.map(RegionSpeciesRow::toZoneSpecies) }
        }
    }

    /**
     * Las especies de [zone] que algun modelo sabe identificar, si su lista esta
     * en el movil. Null si no esta: "sin datos" no es lo mismo que "ninguna".
     */
    suspend fun speciesIdsIn(zone: PokedexZone): Set<String>? {
        val id = zone.id ?: return null
        if (checklistDao.findChecklist(id) == null) return null
        return checklistDao.speciesIdsInRegion(id).toHashSet()
    }

    /** true si la lista de [zone] ya esta en el movil (aunque este caducada). */
    suspend fun hasChecklist(zone: PokedexZone): Boolean =
        zone.id == null || checklistDao.findChecklist(zone.id) != null

    /**
     * Descarga la lista de [zone] si falta o ha caducado.
     *
     * @return true si habia (o ahora hay) lista. Lanza si hacia falta descargar
     *   y la red fallo Y no habia ninguna copia: con una copia vieja se sigue.
     */
    suspend fun ensureChecklist(zone: PokedexZone, now: Long, force: Boolean = false): Boolean {
        val id = zone.id ?: return true
        return downloads.withLock {
            val existing = checklistDao.findChecklist(id)
            val fresh = existing != null && now - existing.fetchedAt < freshnessMillis
            if (fresh && !force) return@withLock true

            val summary = try {
                source.speciesIn(zone)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Una lista de hace cuatro meses es mucho mejor que ninguna.
                if (existing != null) return@withLock true
                throw e
            }

            val bySpecies = HashMap<String, Long>()
            for ((rawName, count) in summary.byScientificName) {
                val speciesId = GbifSpeciesNames.toSpeciesId(rawName) ?: continue
                bySpecies[speciesId] = (bySpecies[speciesId] ?: 0L) + count
            }
            val total = summary.total.coerceAtLeast(1L)
            checklistDao.replaceChecklist(
                checklist = RegionChecklistEntity(
                    regionId = id,
                    name = zone.name,
                    fetchedAt = now,
                    totalOccurrences = summary.total,
                ),
                species = bySpecies.map { (speciesId, count) ->
                    RegionChecklistSpeciesEntity(
                        regionId = id,
                        speciesId = speciesId,
                        occurrenceCount = count,
                        share = (count.toDouble() / total).toFloat(),
                    )
                },
            )
            true
        }
    }

    /** Las zonas dentro de otra, para el navegador de zonas. Necesita red. */
    suspend fun subdivisions(gadmId: String): List<ZoneChoice> = source.subdivisions(gadmId)

    /** Elegir un nivel de TU zona es dejar la elegida a mano. */
    suspend fun setZoneLevel(level: ZoneLevel) = edit { it.copy(zoneLevel = level.name).withoutChosen() }

    /** "Quiero ver Extremadura": una zona que no es la detectada. */
    suspend fun chooseZone(zone: PokedexZone) {
        requireNotNull(zone.id) { "El mundo no se elige: se quita el filtro" }
        edit {
            it.copy(
                customZoneId = zone.id,
                customZoneName = zone.name,
                customZoneLevel = zone.level.name,
                customZoneGeometry = zone.geometry,
            )
        }
    }

    /** Vuelta a la zona detectada, al nivel que estuviera. */
    suspend fun clearChosenZone() = edit { it.withoutChosen() }

    suspend fun setShowMissing(show: Boolean) = edit { it.copy(showMissing = show) }

    /**
     * Detecta (o vuelve a detectar) la zona del usuario.
     *
     * @param coordinates donde esta, o null si no hay ubicacion o esta apagada.
     * @param fallbackCountryCode el pais del movil, para cuando no hay mas.
     * @param countryDisplayName como se llama un pais en el idioma de la app.
     * @param resetLevel true la primera vez: el filtro se pone en "provincia".
     *   Si aun no se conoce, [PokedexPreferences.zone] cae sola al pais, y el
     *   dia que se conozca (porque se concedio la ubicacion, o volvio la red)
     *   el filtro se afina sin que nadie tenga que tocar nada. En una
     *   redeteccion se respeta el nivel que el usuario haya elegido.
     */
    suspend fun detectHome(
        coordinates: Pair<Double, Double>?,
        fallbackCountryCode: String?,
        countryDisplayName: (String) -> String,
        now: Long,
        resetLevel: Boolean,
    ): HomePlace {
        val located = coordinates?.let { (latitude, longitude) ->
            try {
                source.placeAt(latitude, longitude)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        val previous = checklistDao.currentPreferences().toDomain().home
        val merged = HomePlace.merge(
            previous = previous,
            located = located,
            fallbackCountryCode = fallbackCountryCode?.uppercase()?.takeIf { it.length == 2 },
        )
        // El nombre del pais en castellano, no "Spain": GADM lo da en ingles.
        val home = merged.copy(countryName = merged.countryCode?.let(countryDisplayName) ?: merged.countryName)

        edit { current ->
            current.copy(
                zoneLevel = if (resetLevel) ZoneLevel.PROVINCE.name else current.zoneLevel,
                countryCode = home.countryCode,
                countryName = home.countryName,
                regionId = home.regionId,
                regionName = home.regionName,
                provinceId = home.provinceId,
                provinceName = home.provinceName,
                detectedAt = now,
            )
        }
        return home
    }

    private suspend fun edit(transform: (PokedexPreferencesEntity) -> PokedexPreferencesEntity) {
        val current = checklistDao.currentPreferences() ?: DEFAULT_ENTITY
        checklistDao.upsertPreferences(transform(current))
    }

    companion object {
        /** Las listas de GBIF cambian despacio: tres meses sobran. */
        const val DEFAULT_FRESHNESS_MILLIS = 90L * 24 * 60 * 60 * 1000

        private val DEFAULT_ENTITY = PokedexPreferencesEntity(
            zoneLevel = ZoneLevel.WORLD.name,
            // Ver lo que falta es la mitad de la gracia de filtrar por zona: en
            // una provincia son unos cientos de huecos, no seis mil.
            showMissing = true,
        )

        private fun PokedexPreferencesEntity.withoutChosen() = copy(
            customZoneId = null,
            customZoneName = null,
            customZoneLevel = null,
            customZoneGeometry = null,
        )

        private fun PokedexPreferencesEntity?.toDomain(): PokedexPreferences {
            val entity = this ?: DEFAULT_ENTITY
            return PokedexPreferences(
                zoneLevel = ZoneLevel.fromName(entity.zoneLevel),
                showMissing = entity.showMissing,
                home = HomePlace(
                    countryCode = entity.countryCode,
                    countryName = entity.countryName,
                    regionId = entity.regionId,
                    regionName = entity.regionName,
                    provinceId = entity.provinceId,
                    provinceName = entity.provinceName,
                ),
                isInitialized = this != null && entity.detectedAt > 0L,
                chosen = entity.customZoneId?.let { id ->
                    PokedexZone(
                        // Un nivel guardado que ya no se entiende no puede ser
                        // "el mundo": una zona elegida siempre es algo concreto.
                        level = ZoneLevel.fromName(entity.customZoneLevel)
                            .takeIf { level -> level != ZoneLevel.WORLD } ?: ZoneLevel.PLACE,
                        id = id,
                        name = entity.customZoneName.orEmpty(),
                        geometry = entity.customZoneGeometry,
                    )
                },
            )
        }
    }
}

private fun RegionSpeciesRow.toZoneSpecies() = ZoneSpecies(species = species.toDomain(), share = share)
