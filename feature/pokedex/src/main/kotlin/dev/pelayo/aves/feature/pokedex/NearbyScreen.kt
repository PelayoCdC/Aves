package dev.pelayo.aves.feature.pokedex

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pelayo.aves.core.common.runSuspendCatching
import dev.pelayo.aves.core.data.CellSpecies
import dev.pelayo.aves.core.data.ObservationRepository
import dev.pelayo.aves.core.data.RegionRepository
import dev.pelayo.aves.core.data.SpeciesRepository
import dev.pelayo.aves.core.designsystem.theme.ScientificNameStyle
import dev.pelayo.aves.core.model.Coordinates
import dev.pelayo.aves.core.model.RarityTier
import dev.pelayo.aves.core.model.Species
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/** Una especie que se registra aqui este mes. */
data class NearbySpecies(
    val species: Species,
    /** Como de comun es AQUI y AHORA, no en general. */
    val rarity: RarityTier,
    val collected: Boolean,
)

enum class NearbyStatus {
    LOADING,
    READY,

    /** Sin ubicacion (sin permiso, apagada, o el modo sin ubicacion). */
    NO_LOCATION,

    /** No hay datos de este mes aqui y no se pudieron descargar (sin red). */
    NO_DATA,
}

data class NearbyUiState(
    val status: NearbyStatus = NearbyStatus.LOADING,
    /** 1..12. */
    val month: Int = 0,
    val species: List<NearbySpecies> = emptyList(),
    val onlyMissing: Boolean = false,
    /** Cuantas faltan, con el filtro o sin el. */
    val missingCount: Int = 0,
    val totalCount: Int = 0,
)

/**
 * Lo que la lista ensena: las especies registradas aqui este mes que algun
 * modelo sabe identificar, de mas a menos abundante, marcando cuales ya tienes.
 *
 * @param rows lo que da GBIF para la celda y el mes, ya ordenado.
 * @param catalog las especies del catalogo por id. Lo que no esta, fuera.
 */
internal fun buildNearby(
    rows: List<CellSpecies>,
    catalog: Map<String, Species>,
    detectable: Set<String>,
    collected: Set<String>,
): List<NearbySpecies> = rows.mapNotNull { row ->
    if (row.speciesId !in detectable) return@mapNotNull null
    val species = catalog[row.speciesId] ?: return@mapNotNull null
    NearbySpecies(species, row.rarity, row.speciesId in collected)
}

/**
 * "¿Que puedo oir aqui?": las aves que se registran en tu celda (~40 km) este
 * mes, de las mas comunes a las mas escasas, y cuales te faltan.
 *
 * Sale de los mismos datos de GBIF que calculan la rareza, que ya estan en la
 * base cuando se ha escuchado aqui alguna vez; si no, se descargan (solo el mes
 * actual). Sin porcentajes: el orden y tres o cuatro palabras bastan, y no
 * es la confianza de ningun modelo sino cuanto se ve un ave por aqui.
 */
class NearbyViewModel(
    private val speciesRepository: SpeciesRepository,
    observationRepository: ObservationRepository,
    private val currentLocation: suspend () -> Coordinates?,
    private val speciesHere: suspend (where: Coordinates, month: Int) -> List<CellSpecies>?,
    private val downloadHere: suspend (where: Coordinates, nowMillis: Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private data class Loaded(val status: NearbyStatus, val month: Int, val all: List<NearbySpecies>)

    private val loaded = MutableStateFlow(Loaded(NearbyStatus.LOADING, 0, emptyList()))
    private val onlyMissing = MutableStateFlow(false)

    val uiState: StateFlow<NearbyUiState> = combine(
        loaded,
        onlyMissing,
        observationRepository.collectedSpeciesIds(),
    ) { data, missingOnly, collectedIds ->
        val collected = collectedIds.toHashSet()
        // La coleccion puede crecer con la pantalla abierta (una escucha en
        // marcha): la marca de "ya la tienes" se recalcula con cada cambio.
        val all = data.all.map { it.copy(collected = it.species.speciesId in collected) }
        NearbyUiState(
            status = data.status,
            month = data.month,
            species = if (missingOnly) all.filterNot { it.collected } else all,
            onlyMissing = missingOnly,
            missingCount = all.count { !it.collected },
            totalCount = all.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), NearbyUiState())

    init {
        viewModelScope.launch { load() }
    }

    fun setOnlyMissing(value: Boolean) = onlyMissing.update { value }

    private suspend fun load() {
        val now = clock()
        val month = RegionRepository.monthOf(now)
        val where = runSuspendCatching { currentLocation() }.getOrNull()
        if (where == null) {
            loaded.value = Loaded(NearbyStatus.NO_LOCATION, month, emptyList())
            return
        }

        val rows = speciesHere(where, month)
            ?: run {
                runSuspendCatching { downloadHere(where, now) }
                speciesHere(where, month)
            }
        if (rows.isNullOrEmpty()) {
            loaded.value = Loaded(NearbyStatus.NO_DATA, month, emptyList())
            return
        }

        val detectable = speciesRepository.detectableSpeciesIds()
        val catalog = speciesRepository.findAll(rows.map { it.speciesId }).associateBy { it.speciesId }
        loaded.value = Loaded(
            NearbyStatus.READY,
            month,
            buildNearby(rows, catalog, detectable, collected = emptySet()),
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

@Composable
fun NearbyScreen(
    viewModelFactory: () -> NearbyViewModel,
    onSpeciesClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: NearbyViewModel = viewModel { viewModelFactory() }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    when (state.status) {
        NearbyStatus.LOADING -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        NearbyStatus.NO_LOCATION -> NearbyMessage(stringResource(R.string.nearby_no_location), modifier)

        NearbyStatus.NO_DATA -> NearbyMessage(stringResource(R.string.nearby_no_data), modifier)

        NearbyStatus.READY -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "cabecera") {
                Column(Modifier.padding(bottom = 8.dp)) {
                    Text(
                        text = stringResource(R.string.nearby_header, monthName(state.month), state.totalCount),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = pluralStringResource(R.plurals.nearby_missing, state.missingCount, state.missingCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilterChip(
                        selected = state.onlyMissing,
                        onClick = { viewModel.setOnlyMissing(!state.onlyMissing) },
                        label = { Text(stringResource(R.string.nearby_only_missing)) },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            items(state.species, key = { it.species.speciesId }) { entry ->
                NearbyRow(entry = entry, onClick = { onSpeciesClick(entry.species.speciesId) })
            }
        }
    }
}

@Composable
private fun NearbyRow(entry: NearbySpecies, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = entry.species.commonName, style = MaterialTheme.typography.titleSmall)
            Text(
                text = entry.species.scientificName,
                style = ScientificNameStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = rarityText(entry.rarity),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entry.collected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.nearby_collected),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            } else {
                Text(
                    text = stringResource(R.string.nearby_missing_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun NearbyMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun monthName(month: Int): String =
    if (month in 1..12) Month.of(month).getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault()) else ""
