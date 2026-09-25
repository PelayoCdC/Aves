package dev.pelayo.aves.feature.pokedex

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pelayo.aves.core.common.PhotoStorage
import dev.pelayo.aves.core.common.runSuspendCatching
import dev.pelayo.aves.core.data.ObservationRepository
import dev.pelayo.aves.core.data.RangeMapRepository
import dev.pelayo.aves.core.data.SpeciesEncyclopedia
import dev.pelayo.aves.core.data.SpeciesRepository
import dev.pelayo.aves.core.database.entity.ObservationEntity
import dev.pelayo.aves.core.designsystem.components.PickedPlace
import dev.pelayo.aves.core.model.Coordinates
import dev.pelayo.aves.core.model.ObservationSource
import dev.pelayo.aves.core.model.RarityTier
import dev.pelayo.aves.core.model.Species
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class SpeciesDetailUiState(
    val isLoading: Boolean = true,
    val species: Species? = null,
    /** Ordenados del mas reciente al mas antiguo. */
    val sightings: List<Sighting> = emptyList(),
    /** Se esta pidiendo la ficha a Wikipedia. */
    val isLoadingEncyclopedia: Boolean = false,
    /** El taxon de GBIF, para el mapa de donde se registra. Null mientras no se sepa o si GBIF no la reconoce. */
    val gbifTaxonKey: Int? = null,
    /** Una foto subida desde la ficha, ya en disco, a la espera de cuando y donde fue. */
    val photoDraft: SavedPhoto? = null,
    /** Se esta leyendo y guardando la foto elegida. */
    val isSavingPhoto: Boolean = false,
    val photoFailed: Boolean = false,
) {
    /** El mejor tier alcanzado; ver el razonamiento en [CollectedSpecies.rarity]. */
    val rarity: RarityTier
        get() = sightings.maxByOrNull { it.rarity.level }?.rarity ?: RarityTier.UNKNOWN

    val isCollected: Boolean get() = sightings.isNotEmpty()

    /** Cuantas veces se ha visto, fotografiado y oido. */
    val sources: CaptureSources get() = CaptureSources.of(sightings)

    /** Los avistamientos con foto, en el mismo orden: lo que recorre el visor. */
    val photos: List<Sighting>
        get() = sightings.filter { it.source == ObservationSource.IMAGE && it.mediaPath != null }
}

/**
 * La ficha de una especie concreta.
 *
 * Se filtra en memoria en lugar de pedir una consulta nueva al repositorio
 * porque la coleccion confirmada de un usuario son decenas o cientos de filas,
 * no millones, y asi la ficha reacciona al instante cuando se confirma un
 * avistamiento nuevo sin tocar la capa de datos.
 *
 * @param encyclopedia rellena foto y descripcion la primera vez. Nulo deja la
 *   ficha con lo que trae el catalogo.
 * @param currentLocation para las observaciones a mano.
 * @param rarityAt rareza de la especie en un lugar y una fecha: para lo anotado a
 *   mano, las fotos subidas y las correcciones.
 * @param savePhoto guarda una foto de la galeria en sus dos tamanos y le lee la
 *   fecha. La pone :app, que es quien sabe de ficheros. Nulo esconde el boton.
 */
class SpeciesDetailViewModel(
    private val speciesId: String,
    private val speciesRepository: SpeciesRepository,
    private val observationRepository: ObservationRepository,
    private val encyclopedia: SpeciesEncyclopedia? = null,
    /** La clave de GBIF para el mapa. Nulo deja la ficha sin capa de densidad. */
    private val rangeMaps: RangeMapRepository? = null,
    private val currentLocation: suspend () -> Coordinates? = { null },
    private val rarityAt: suspend (speciesId: String, where: Coordinates, epochMillis: Long) -> RarityTier =
        { _, _, _ -> RarityTier.UNKNOWN },
    private val savePhoto: (suspend (Uri) -> SavedPhoto?)? = null,
    /** Como se llama la ubicacion actual en el selector de lugar. Lo da quien tiene los recursos. */
    private val currentPlaceName: String = "",
) : ViewModel() {

    val canAddPhotos: Boolean get() = savePhoto != null

    private val photoDraft = MutableStateFlow<SavedPhoto?>(null)
    private val photoBusy = MutableStateFlow(false)
    private val photoFailed = MutableStateFlow(false)

    private val species = MutableStateFlow<Species?>(null)
    private val resolved = MutableStateFlow(false)
    private val loadingEncyclopedia = MutableStateFlow(false)
    private val taxonKey = MutableStateFlow<Int?>(null)

    private val photoState = combine(photoDraft, photoBusy, photoFailed) { draft, busy, failed -> Triple(draft, busy, failed) }

    val uiState: StateFlow<SpeciesDetailUiState> = combine(
        species,
        resolved,
        observationRepository.collectionOf(speciesId),
        combine(loadingEncyclopedia, taxonKey) { loading, key -> loading to key },
        photoState,
    ) { found, isResolved, observations, (loading, key), (draft, busy, failed) ->
        SpeciesDetailUiState(
            isLoading = !isResolved,
            species = found,
            sightings = observations.map(ObservationEntity::toSighting),
            isLoadingEncyclopedia = loading,
            gbifTaxonKey = key,
            photoDraft = draft,
            isSavingPhoto = busy,
            photoFailed = failed,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = SpeciesDetailUiState(),
    )

    init {
        viewModelScope.launch {
            val found = speciesRepository.find(speciesId)
            species.value = found
            resolved.value = true
            // La clave de GBIF para el mapa, en paralelo con la enciclopedia:
            // ninguna de las dos debe esperar a la otra.
            if (found != null && rangeMaps != null) {
                launch {
                    taxonKey.value = try {
                        rangeMaps.taxonKeyFor(found)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            // La enciclopedia se pide DESPUES de pintar lo que ya hay: la ficha
            // no espera a la red para ensenar el nombre y la familia.
            if (found != null && !found.hasEncyclopedia && encyclopedia != null) {
                loadingEncyclopedia.value = true
                species.value = runSuspendCatching { encyclopedia.enrich(found) }.getOrDefault(found)
                loadingEncyclopedia.value = false
            }
        }
    }

    /** "Lo he visto": un avistamiento anotado a mano, cuando y donde diga el usuario. */
    fun addManualSighting(draft: SightingDraft) {
        viewModelScope.launch {
            val where = draft.place?.let { Coordinates(it.latitude, it.longitude) }
            observationRepository.recordManualObservation(
                speciesId = speciesId,
                timestamp = draft.timestamp,
                latitude = where?.latitude,
                longitude = where?.longitude,
                notes = draft.notes,
                rarityTier = rarityFor(where, draft.timestamp),
            )
        }
    }

    /** Corrige cuando, donde y las notas de un avistamiento que ya existe. */
    fun updateSighting(sightingId: Long, draft: SightingDraft) {
        viewModelScope.launch {
            val where = draft.place?.let { Coordinates(it.latitude, it.longitude) }
            observationRepository.updateDetails(
                observationId = sightingId,
                timestamp = draft.timestamp,
                latitude = where?.latitude,
                longitude = where?.longitude,
                notes = draft.notes,
                rarityTier = rarityFor(where, draft.timestamp),
            )
        }
    }

    /**
     * Una foto de la galeria para esta especie: se guarda ya (para leerle la
     * fecha y no perderla) y se pregunta cuando y donde fue. Hasta que el
     * usuario confirme no hay avistamiento; si cancela, la foto se borra.
     */
    fun preparePhoto(uri: Uri) {
        val saver = savePhoto ?: return
        viewModelScope.launch {
            photoBusy.value = true
            photoFailed.value = false
            val saved = saver(uri)
            photoBusy.value = false
            if (saved == null) photoFailed.value = true else photoDraft.value = saved
        }
    }

    fun confirmPhoto(draft: SightingDraft) {
        val photo = photoDraft.value ?: return
        photoDraft.value = null
        viewModelScope.launch {
            val where = draft.place?.let { Coordinates(it.latitude, it.longitude) }
            observationRepository.recordPhotoObservation(
                speciesId = speciesId,
                timestamp = draft.timestamp,
                latitude = where?.latitude,
                longitude = where?.longitude,
                mediaPath = photo.mediaPath,
                notes = draft.notes,
                rarityTier = rarityFor(where, draft.timestamp),
            )
        }
    }

    fun discardPhoto() {
        val photo = photoDraft.value ?: return
        photoDraft.value = null
        viewModelScope.launch(Dispatchers.IO) { runCatching { PhotoStorage.delete(photo.mediaPath) } }
    }

    fun dismissPhotoFailure() {
        photoFailed.value = false
    }

    /** La ubicacion actual como lugar propuesto para "lo he visto". */
    suspend fun currentPlace(): PickedPlace? =
        runSuspendCatching { currentLocation() }.getOrNull()?.let { PickedPlace(it.latitude, it.longitude, currentPlaceName) }

    private suspend fun rarityFor(where: Coordinates?, epochMillis: Long): RarityTier =
        where?.let { runSuspendCatching { rarityAt(speciesId, it, epochMillis) }.getOrNull() } ?: RarityTier.UNKNOWN

    /**
     * Borra el avistamiento Y lo que capturo. Antes solo se borraba la fila y la
     * foto o el recorte se quedaban en disco para siempre, sin nada que los
     * ensenara ni los pudiera borrar.
     */
    fun deleteSighting(sightingId: Long) {
        val sighting = uiState.value.sightings.firstOrNull { it.id == sightingId }
        viewModelScope.launch {
            observationRepository.delete(sightingId)
            val path = sighting?.mediaPath ?: return@launch
            withContext(Dispatchers.IO) {
                runCatching {
                    if (sighting.source == ObservationSource.IMAGE) PhotoStorage.delete(path) else File(path).delete()
                }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
