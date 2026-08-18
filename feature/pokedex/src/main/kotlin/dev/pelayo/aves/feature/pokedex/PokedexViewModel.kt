package dev.pelayo.aves.feature.pokedex

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pelayo.aves.core.data.ObservationRepository
import dev.pelayo.aves.core.data.SpeciesRepository
import dev.pelayo.aves.core.database.entity.ObservationEntity
import dev.pelayo.aves.core.model.RarityTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
)

/**
 * La coleccion: que especies has confirmado y cuanto te falta.
 *
 * Recibe los repositorios por constructor porque todavia no hay Hilt; :app es
 * quien los construye y quien decide su ciclo de vida, igual que hace con la
 * fabrica del clasificador en `IdentifyViewModel`.
 */
class PokedexViewModel(
    private val speciesRepository: SpeciesRepository,
    private val observationRepository: ObservationRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")

    /**
     * El tamano del catalogo no cambia en caliente (se siembra al instalar), asi
     * que se lee una vez y se cachea en lugar de consultarlo en cada emision de
     * la coleccion.
     */
    private val catalogSize = MutableStateFlow<Int?>(null)

    val uiState: StateFlow<PokedexUiState> = combine(
        // `collectedSpeciesIds` es el cruce con el catalogo que pide la ficha, y
        // `collection` aporta lo que los ids no llevan: tier congelado, fecha y
        // media. Las dos salen de las mismas filas confirmadas, asi que no hay
        // riesgo de que se contradigan.
        observationRepository.collectedSpeciesIds(),
        observationRepository.collection(),
        query,
        catalogSize,
    ) { ids, observations, text, total ->
        Snapshot(ids, observations, text, total)
    }
        .map { snapshot ->
            val catalog = speciesRepository.findAll(snapshot.ids).associateBy { it.speciesId }
            val collected = buildCollection(
                sightings = snapshot.observations.map(ObservationEntity::toSighting),
                catalog = catalog,
            )

            PokedexUiState(
                isLoading = snapshot.total == null,
                query = snapshot.query,
                progress = CollectionProgress(
                    // El numerador sale de las especies que de verdad se pueden
                    // pintar, no de los ids sueltos: si el catalogo no conoce
                    // una etiqueta, contarla haria que el progreso no cuadrase
                    // con las tarjetas visibles.
                    collected = collected.size,
                    total = snapshot.total ?: 0,
                ),
                groups = groupByFamily(filterCollection(collected, snapshot.query)),
                hasAnyCollected = collected.isNotEmpty(),
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
        viewModelScope.launch {
            catalogSize.value = speciesRepository.catalogSize()
        }
    }

    fun onQueryChange(value: String) {
        query.value = value
    }

    /** Agrupa los cuatro flujos sin destructurar tuplas anidadas. */
    private data class Snapshot(
        val ids: List<String>,
        val observations: List<ObservationEntity>,
        val query: String,
        val total: Int?,
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
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
)
