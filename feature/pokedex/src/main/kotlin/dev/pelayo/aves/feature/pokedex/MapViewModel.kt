package dev.pelayo.aves.feature.pokedex

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pelayo.aves.core.data.ObservationRepository
import dev.pelayo.aves.core.data.RangeMapRepository
import dev.pelayo.aves.core.data.SpeciesRepository
import dev.pelayo.aves.core.database.entity.ObservationEntity
import dev.pelayo.aves.core.map.GbifTiles
import dev.pelayo.aves.core.map.MapPoint
import dev.pelayo.aves.core.map.MapPointKind
import dev.pelayo.aves.core.map.MapViewport
import dev.pelayo.aves.core.model.ObservationSource
import dev.pelayo.aves.core.model.Species
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Un avistamiento en el mapa, con lo que hace falta para la tarjeta al tocarlo. */
data class MapPin(
    val point: MapPoint,
    val speciesId: String,
    val commonName: String,
    val scientificName: String,
    val source: ObservationSource,
    val timestamp: Long,
)

/** Si hay capa de densidad que pintar. */
enum class DensityStatus {
    RESOLVING,
    READY,

    /** GBIF no reconoce la especie: no hay mapa de rango para ella. */
    UNAVAILABLE,

    /** Sin red: se puede reintentar. */
    OFFLINE,
}

data class MapUiState(
    val isLoading: Boolean = true,
    /** El nombre de la especie, si el mapa es de una; null para el de toda la coleccion. */
    val speciesName: String? = null,
    val pins: List<MapPin> = emptyList(),
    /** El taxon de la capa de densidad, cuando esta lista. */
    val densityTaxonKey: Int? = null,
    val densityStatus: DensityStatus = DensityStatus.RESOLVING,
    val showDensity: Boolean = true,
    val showPins: Boolean = true,
    /** El encuadre inicial: el que contiene tus avistamientos. Se decide una vez. */
    val viewport: MapViewport = MapViewport.SPAIN,
    val selected: MapPin? = null,
) {
    val isSpeciesMap: Boolean get() = speciesName != null

    val visiblePoints: List<MapPoint> get() = if (showPins) pins.map { it.point } else emptyList()
}

/**
 * El mapa: donde se registra una especie en el mundo (GBIF) y donde la has
 * visto tu; o, sin especie, todos tus avistamientos y donde se registran aves.
 *
 * Es lo que se echaba en falta de eBird. Los mapas de eBird no se pueden usar
 * (licencia no comercial), pero la densidad de registros de GBIF cuenta lo
 * mismo con otros datos.
 *
 * @param speciesId la especie del mapa, o null para el mapa de la coleccion.
 */
class MapViewModel(
    private val speciesId: String?,
    private val speciesRepository: SpeciesRepository,
    private val observationRepository: ObservationRepository,
    private val rangeMaps: RangeMapRepository? = null,
) : ViewModel() {

    private val species = MutableStateFlow<Species?>(null)
    private val density = MutableStateFlow<Pair<Int?, DensityStatus>>(null to DensityStatus.RESOLVING)
    private val showDensity = MutableStateFlow(true)
    private val showPins = MutableStateFlow(true)
    private val selectedId = MutableStateFlow<Long?>(null)
    private val initialViewport = MutableStateFlow<MapViewport?>(null)

    private val pins = observationRepository.collection().map { observations ->
        val located = observations.filter { it.latitude != null && it.longitude != null }
            .filter { speciesId == null || it.speciesId == speciesId }
        val catalog = speciesRepository.findAll(located.map { it.speciesId }.distinct()).associateBy { it.speciesId }
        located.mapNotNull { observation ->
            val found = catalog[observation.speciesId] ?: return@mapNotNull null
            observation.toPin(found)
        }
    }

    val uiState: StateFlow<MapUiState> = combine(
        pins,
        species,
        density,
        combine(showDensity, showPins, selectedId, initialViewport) { d, p, s, v -> Layers(d, p, s, v) },
    ) { located, found, (taxonKey, status), layers ->
        // El encuadre se decide con los primeros puntos que llegan y no se
        // vuelve a mover: un mapa que se recoloca solo cada vez que confirmas
        // algo es un mapa que no se puede mirar.
        val viewport = layers.viewport ?: MapViewport.fitting(located.map { it.point }).also { initialViewport.value = it }
        MapUiState(
            isLoading = false,
            speciesName = if (speciesId != null) found?.commonName ?: "" else null,
            pins = located,
            densityTaxonKey = taxonKey,
            densityStatus = status,
            showDensity = layers.showDensity,
            showPins = layers.showPins,
            viewport = viewport,
            selected = layers.selectedId?.let { id -> located.firstOrNull { it.point.id == id } },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = MapUiState(),
    )

    init {
        resolveDensity()
    }

    fun toggleDensity() = showDensity.value.let { showDensity.value = !it }

    fun togglePins() = showPins.value.let { showPins.value = !it }

    fun select(pin: MapPin?) {
        selectedId.value = pin?.point?.id
    }

    /** Vuelve a pedir la clave del taxon, para despues de un fallo de red. */
    fun resolveDensity() {
        viewModelScope.launch {
            if (speciesId == null) {
                // Sin especie, la capa es "todas las aves": donde se registran.
                density.value = GbifTiles.TAXON_KEY_AVES to DensityStatus.READY
                return@launch
            }
            val found = speciesRepository.find(speciesId)
            species.value = found
            val repository = rangeMaps
            if (found == null || repository == null) {
                density.value = null to DensityStatus.UNAVAILABLE
                return@launch
            }
            density.value = null to DensityStatus.RESOLVING
            density.value = try {
                val key = repository.taxonKeyFor(found)
                if (key != null) key to DensityStatus.READY else null to DensityStatus.UNAVAILABLE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo casar ${found.scientificName} con GBIF", e)
                null to DensityStatus.OFFLINE
            }
        }
    }

    private data class Layers(
        val showDensity: Boolean,
        val showPins: Boolean,
        val selectedId: Long?,
        val viewport: MapViewport?,
    )

    private companion object {
        const val TAG = "MapViewModel"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** Frontera entre Room y el mapa. Solo para observaciones con coordenadas. */
internal fun ObservationEntity.toPin(species: Species): MapPin = MapPin(
    point = MapPoint(
        id = id,
        latitude = latitude ?: 0.0,
        longitude = longitude ?: 0.0,
        title = species.commonName,
        kind = source.toPointKind(),
    ),
    speciesId = species.speciesId,
    commonName = species.commonName,
    scientificName = species.scientificName,
    source = source,
    timestamp = timestamp,
)

internal fun ObservationSource.toPointKind(): MapPointKind = when (this) {
    ObservationSource.MANUAL -> MapPointKind.SEEN
    ObservationSource.IMAGE -> MapPointKind.PHOTO
    ObservationSource.AUDIO -> MapPointKind.HEARD
}
