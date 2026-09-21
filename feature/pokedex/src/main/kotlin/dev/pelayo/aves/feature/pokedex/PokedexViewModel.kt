package dev.pelayo.aves.feature.pokedex

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pelayo.aves.core.data.ChecklistRepository
import dev.pelayo.aves.core.data.HomePlace
import dev.pelayo.aves.core.data.ObservationRepository
import dev.pelayo.aves.core.data.PokedexPreferences
import dev.pelayo.aves.core.data.PokedexZone
import dev.pelayo.aves.core.data.SpeciesRepository
import dev.pelayo.aves.core.data.ZoneLevel
import dev.pelayo.aves.core.data.ZoneSpecies
import dev.pelayo.aves.core.database.entity.ObservationEntity
import dev.pelayo.aves.core.model.Coordinates
import dev.pelayo.aves.core.model.RarityTier
import dev.pelayo.aves.core.model.Species
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Como esta la lista de la zona por la que se filtra. */
enum class ZoneStatus {
    /** Hay lista (o no hace falta: el mundo). */
    READY,

    /** Se esta descargando por primera vez. */
    LOADING,

    /** No se pudo descargar y no habia copia: se ensena la coleccion sin filtrar. */
    UNAVAILABLE,
}

/** Una entrada del menu de zonas. */
data class ZoneOption(
    val level: ZoneLevel,
    /** "Asturias", "España"... Vacio para el mundo, que se nombra en la interfaz. */
    val name: String,
)

/**
 * Lo que la pantalla necesita saber para pintarse.
 *
 * [hasAnyCollected] existe aparte de `groups` porque son dos vacios distintos:
 * uno se arregla saliendo a escuchar y el otro borrando el texto de busqueda.
 * Mezclarlos daria el mismo mensaje inutil en los dos casos.
 */
data class PokedexUiState(
    val isLoading: Boolean = true,
    val query: String = "",
    val progress: CollectionProgress = CollectionProgress(collected = 0, total = 0),
    val groups: List<FamilyGroup> = emptyList(),
    val hasAnyCollected: Boolean = false,
    val stats: CollectionStats? = null,
    /**
     * Especies del catalogo que casan con la busqueda y NO estan ya en pantalla.
     * Es lo que convierte la busqueda en una guia de campo: se puede consultar
     * una especie antes de tenerla, y anotarla a mano si se vio.
     */
    val catalogMatches: List<Species> = emptyList(),
    /** La zona por la que se esta filtrando. */
    val zone: PokedexZone = PokedexZone.World,
    /** A que niveles se puede filtrar, segun lo que se sepa de la zona del usuario. */
    val zoneOptions: List<ZoneOption> = listOf(ZoneOption(ZoneLevel.WORLD, "")),
    val zoneStatus: ZoneStatus = ZoneStatus.READY,
    val showMissing: Boolean = false,
    /** Especies de la coleccion que el filtro deja fuera por no ser de la zona. */
    val hiddenOutsideZone: Int = 0,
    val isDetectingZone: Boolean = false,
)

/**
 * La coleccion: que especies has confirmado, cuanto te falta, y DONDE.
 *
 * Por defecto se filtra por la zona del usuario, al nivel mas fino que se
 * conozca. No hay cuentas en esta app: "tu zona" es donde estabas la primera
 * vez que se abrio esta pantalla (o el pais del movil, si no habia ubicacion),
 * se guarda, y se puede volver a detectar desde el menu.
 *
 * @param currentLocation ubicacion aproximada, ya respetando el modo sin
 *   ubicacion de los ajustes. La decide :app.
 * @param deviceCountryCode el pais del movil, para cuando no hay ubicacion.
 * @param countryDisplayName como se llama un pais en el idioma de la interfaz.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class PokedexViewModel(
    private val speciesRepository: SpeciesRepository,
    private val observationRepository: ObservationRepository,
    private val checklistRepository: ChecklistRepository? = null,
    private val currentLocation: suspend () -> Coordinates? = { null },
    private val deviceCountryCode: () -> String? = { null },
    private val countryDisplayName: (String) -> String = { it },
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val zoneStatus = MutableStateFlow(ZoneStatus.READY)
    private val detecting = MutableStateFlow(false)

    /**
     * El total es lo que algun modelo enlazado puede identificar, no el
     * catalogo entero: prometer 11.000 huecos cuando el clasificador distingue
     * 6.400 seria prometer una coleccion imposible de completar.
     */
    private val detectableTotal = speciesRepository.detectableSpeciesCount()

    /** Busqueda en el catalogo entero, con un respiro para no consultar en cada tecla. */
    private val catalogMatches = query
        .debounce(SEARCH_DEBOUNCE_MILLIS)
        .flatMapLatest { text -> speciesRepository.search(text, limit = CATALOG_MATCH_LIMIT) }

    private val preferences: Flow<PokedexPreferences> =
        checklistRepository?.preferences ?: flowOf(NO_ZONES)

    /**
     * Las aves de la zona elegida, o null si no hay lista con la que filtrar.
     *
     * El mundo sin "ver las que faltan" NO carga nada: son miles de especies
     * para no pintar ninguna. Y una lista vacia (aun sin descargar) cuenta
     * como "no hay lista", no como "aqui no hay aves".
     */
    private val zoneSpecies: Flow<List<ZoneSpecies>?> = preferences
        .map { it.zone to it.showMissing }
        .distinctUntilChanged()
        .flatMapLatest { (zone, showMissing) ->
            val repository = checklistRepository
            when {
                repository == null -> flowOf(null)
                zone.level == ZoneLevel.WORLD && !showMissing -> flowOf(null)
                else -> repository.speciesIn(zone).map { rows -> rows.takeIf { it.isNotEmpty() } }
            }
        }

    private val collectionSnapshot = combine(
        // `collectedSpeciesIds` es el cruce con el catalogo que pide la ficha, y
        // `collection` aporta lo que los ids no llevan: tier congelado, fecha y
        // media. Las dos salen de las mismas filas confirmadas, asi que no hay
        // riesgo de que se contradigan.
        observationRepository.collectedSpeciesIds(),
        observationRepository.collection(),
        query,
        detectableTotal,
        catalogMatches,
    ) { ids, observations, text, total, matches ->
        Snapshot(ids, observations, text, total, matches)
    }

    val uiState: StateFlow<PokedexUiState> = combine(
        collectionSnapshot,
        preferences,
        zoneSpecies,
        zoneStatus,
        detecting,
    ) { snapshot, prefs, species, status, isDetecting ->
        val catalog = speciesRepository.findAll(snapshot.ids).associateBy { it.speciesId }
        val collected = buildCollection(
            sightings = snapshot.observations.map(ObservationEntity::toSighting),
            catalog = catalog,
        )
        val view = buildZoneView(
            collected = collected,
            zoneSpecies = species,
            showMissing = prefs.showMissing,
            query = snapshot.query,
            fallbackTotal = snapshot.total,
        )
        val onScreen = view.groups.flatMapTo(HashSet()) { group ->
            group.members.map { it.species.speciesId } + group.missing.map { it.species.speciesId }
        }

        PokedexUiState(
            isLoading = false,
            query = snapshot.query,
            progress = view.progress,
            groups = view.groups,
            hasAnyCollected = collected.isNotEmpty(),
            stats = computeStats(collected, clock()),
            catalogMatches = snapshot.matches.filterNot { it.speciesId in onScreen },
            zone = prefs.zone,
            zoneOptions = zoneOptionsFor(prefs.home),
            zoneStatus = if (species != null) ZoneStatus.READY else status,
            showMissing = prefs.showMissing,
            hiddenOutsideZone = view.hiddenOutsideZone,
            isDetectingZone = isDetecting,
        )
    }
        .stateIn(
            scope = viewModelScope,
            // La coleccion se recompone tras un cruce con la base de datos; si se
            // tirase el estado al girar la pantalla se veria un parpadeo vacio.
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = PokedexUiState(),
        )

    init {
        val repository = checklistRepository
        if (repository != null) {
            // La primera vez se detecta la zona y el filtro se pone en
            // "provincia". Despues manda lo que el usuario haya elegido.
            //
            // Si aquella vez solo salio el pais (la Pokedex se abrio antes de
            // conceder la ubicacion, o no habia red), se reintenta en cada
            // apertura, pero solo cuando hay ubicacion con la que afinar.
            viewModelScope.launch {
                val current = repository.preferences.first()
                when {
                    !current.isInitialized -> detectZone(resetLevel = true)
                    current.home.provinceId == null -> detectZone(resetLevel = false, onlyIfLocated = true)
                }
            }

            // Cada vez que cambia la zona, se asegura su lista. `collectLatest`
            // para que cambiar de zona a media descarga no deje dos en marcha.
            viewModelScope.launch {
                repository.preferences.map { it.zone }.distinctUntilChanged().collectLatest { zone ->
                    if (zone.id == null) {
                        zoneStatus.value = ZoneStatus.READY
                        return@collectLatest
                    }
                    zoneStatus.value = if (repository.hasChecklist(zone)) ZoneStatus.READY else ZoneStatus.LOADING
                    try {
                        repository.ensureChecklist(zone, clock())
                        zoneStatus.value = ZoneStatus.READY
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "No se pudo descargar la lista de ${zone.name}", e)
                        zoneStatus.value = ZoneStatus.UNAVAILABLE
                    }
                }
            }
        }
    }

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onZoneLevelChange(level: ZoneLevel) {
        viewModelScope.launch { checklistRepository?.setZoneLevel(level) }
    }

    fun onShowMissingChange(show: Boolean) {
        viewModelScope.launch { checklistRepository?.setShowMissing(show) }
    }

    /** "Actualizar mi zona": para quien se ha mudado, o abrio la app de viaje la primera vez. */
    fun redetectZone() {
        viewModelScope.launch { detectZone(resetLevel = false) }
    }

    /** Reintenta la descarga de la lista tras un fallo de red. */
    fun retryChecklist() {
        val repository = checklistRepository ?: return
        viewModelScope.launch {
            val zone = repository.preferences.first().zone
            if (zone.id == null) return@launch
            zoneStatus.value = ZoneStatus.LOADING
            zoneStatus.value = try {
                repository.ensureChecklist(zone, clock(), force = true)
                ZoneStatus.READY
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ZoneStatus.UNAVAILABLE
            }
        }
    }

    /**
     * @param onlyIfLocated para el reintento silencioso: sin ubicacion no hay
     *   nada que afinar, y no merece ni ensenar "Detectando tu zona".
     */
    private suspend fun detectZone(resetLevel: Boolean, onlyIfLocated: Boolean = false) {
        val repository = checklistRepository ?: return
        detecting.value = !onlyIfLocated
        try {
            val where = try {
                currentLocation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (where == null && onlyIfLocated) return
            repository.detectHome(
                coordinates = where?.let { it.latitude to it.longitude },
                fallbackCountryCode = deviceCountryCode(),
                countryDisplayName = countryDisplayName,
                now = clock(),
                resetLevel = resetLevel,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo detectar la zona", e)
        } finally {
            detecting.value = false
        }
    }

    /** Agrupa los flujos sin destructurar tuplas anidadas. */
    private data class Snapshot(
        val ids: List<String>,
        val observations: List<ObservationEntity>,
        val query: String,
        val total: Int,
        val matches: List<Species>,
    )

    private companion object {
        const val TAG = "PokedexViewModel"
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val SEARCH_DEBOUNCE_MILLIS = 250L
        const val CATALOG_MATCH_LIMIT = 20

        /** Sin repositorio de zonas (previews, tests): el mundo y nada mas. */
        val NO_ZONES = PokedexPreferences(
            zoneLevel = ZoneLevel.WORLD,
            showMissing = false,
            home = HomePlace(),
            isInitialized = true,
        )
    }
}

/** Los niveles por los que se puede filtrar, del mas fino al mundo, segun lo que se sepa. */
internal fun zoneOptionsFor(home: HomePlace): List<ZoneOption> = buildList {
    home.provinceName?.takeIf { home.provinceId != null }?.let { add(ZoneOption(ZoneLevel.PROVINCE, it)) }
    // Hay sitios donde GADM llama igual a la region y a la provincia (Asturias,
    // Madrid, Cantabria...). Ensenar las dos seria dos filas iguales en el menu.
    home.regionName?.takeIf { home.regionId != null && it != home.provinceName }
        ?.let { add(ZoneOption(ZoneLevel.REGION, it)) }
    home.countryName?.takeIf { home.countryCode != null }?.let { add(ZoneOption(ZoneLevel.COUNTRY, it)) }
    add(ZoneOption(ZoneLevel.WORLD, ""))
}

/**
 * Frontera entre Room y la interfaz.
 *
 * El tier viaja como Int en la tabla porque un ordinal de enum en la base de
 * datos se rompe en cuanto alguien reordena las constantes; aqui se vuelve a
 * convertir en el unico sitio donde se hace.
 */
internal fun ObservationEntity.toSighting() = Sighting(
    id = id,
    speciesId = speciesId,
    timestamp = timestamp,
    rarity = RarityTier.fromLevel(rarityTierAtCapture),
    source = source,
    confidence = adjustedConfidence,
    mediaPath = mediaPath,
    notes = notes,
    latitude = latitude,
    longitude = longitude,
)
