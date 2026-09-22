package dev.pelayo.aves.feature.pokedex

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pelayo.aves.core.data.ChecklistRepository
import dev.pelayo.aves.core.data.NotablePlaces
import dev.pelayo.aves.core.data.ObservationRepository
import dev.pelayo.aves.core.data.PokedexZone
import dev.pelayo.aves.core.data.SpeciesRepository
import dev.pelayo.aves.core.data.ZoneLevel
import dev.pelayo.aves.core.model.Species
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AchievementsUiState(
    val isLoading: Boolean = true,
    val medals: List<Achievement> = emptyList(),
    /** Se estan bajando las listas de las zonas que aun no las tenian. */
    val isDownloading: Boolean = false,
    /** Alguna lista no se pudo bajar: sin red, esas medallas quedan "sin datos". */
    val downloadFailed: Boolean = false,
    /** La medalla abierta, si hay una. */
    val selected: Achievement? = null,
)

/**
 * Las medallas: cuanto llevas de tu provincia, de tu comunidad, de tu pais, de
 * cada lugar destacado, de cada familia y del total.
 *
 * Todo se calcula al vuelo a partir de la coleccion y de las listas de zona que
 * ya usa el filtro de la Pokedex: no hay tabla de logros que pueda quedarse
 * desfasada. Lo unico que hace de mas es descargar las listas de los lugares
 * destacados la primera vez que se abre, para que las medallas tengan total.
 */
class AchievementsViewModel(
    private val speciesRepository: SpeciesRepository,
    private val observationRepository: ObservationRepository,
    private val checklistRepository: ChecklistRepository? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val selectedId = MutableStateFlow<String?>(null)
    private val downloading = MutableStateFlow(false)
    private val downloadFailed = MutableStateFlow(false)

    /** Todas las especies que algun modelo sabe identificar, para el total y las familias. */
    private val detectable = checklistRepository?.speciesIn(PokedexZone.World)?.map { rows -> rows.map { it.species } }
        ?: flowOf(emptyList())

    /** Las zonas con medalla: la del usuario a sus niveles y los lugares destacados. */
    private val zones = (checklistRepository?.preferences ?: flowOf(null)).map { prefs ->
        val home = prefs?.home
        buildList {
            if (home?.provinceId != null) add(PokedexZone(ZoneLevel.PROVINCE, home.provinceId, home.provinceName.orEmpty()))
            if (home?.regionId != null) add(PokedexZone(ZoneLevel.REGION, home.regionId, home.regionName.orEmpty()))
            if (home?.countryCode != null) add(PokedexZone(ZoneLevel.COUNTRY, home.countryCode, home.countryName.orEmpty()))
            if ((home?.countryCode ?: PLACES_COUNTRY_CODE) == PLACES_COUNTRY_CODE) addAll(NotablePlaces.all)
        }
    }

    /** Las listas descargadas, como disparador: cuando entra una, se recalcula. */
    private val cached = checklistRepository?.cachedChecklists?.map { it.map { entity -> entity.regionId }.toSet() }
        ?: flowOf(emptySet())

    private val medals = combine(
        observationRepository.collectedSpeciesIds(),
        detectable,
        zones,
        cached,
    ) { collectedIds, species, candidateZones, _ ->
        val repository = checklistRepository
        buildAchievements(
            collectedIds = collectedIds.toHashSet(),
            detectableTotal = species.size,
            zones = candidateZones.map { zone -> ZoneChecklist(zone, repository?.speciesIdsIn(zone)) },
            detectableByFamily = species.groupBy(Species::family),
        )
    }

    val uiState: StateFlow<AchievementsUiState> = combine(
        medals,
        selectedId,
        downloading,
        downloadFailed,
    ) { list, selected, isDownloading, failed ->
        AchievementsUiState(
            isLoading = false,
            medals = list,
            isDownloading = isDownloading,
            downloadFailed = failed,
            selected = selected?.let { id -> list.firstOrNull { it.id == id } },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = AchievementsUiState(),
    )

    init {
        downloadMissing()
    }

    fun select(achievement: Achievement?) {
        selectedId.value = achievement?.id
    }

    /** Baja las listas que falten, una tras otra. Lo que falle queda "sin datos" y se puede reintentar. */
    fun downloadMissing() {
        val repository = checklistRepository ?: return
        if (downloading.value) return
        viewModelScope.launch {
            downloading.value = true
            downloadFailed.value = false
            try {
                for (zone in zones.first()) {
                    if (repository.hasChecklist(zone)) continue
                    try {
                        repository.ensureChecklist(zone, clock())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "No se pudo bajar la lista de ${zone.name}", e)
                        downloadFailed.value = true
                    }
                }
            } finally {
                downloading.value = false
            }
        }
    }

    private companion object {
        const val TAG = "AchievementsViewModel"
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Los lugares destacados son, por ahora, todos de España. */
        const val PLACES_COUNTRY_CODE = "ES"
    }
}
