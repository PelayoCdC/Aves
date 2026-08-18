package dev.pelayo.aves.feature.pokedex

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pelayo.aves.core.data.ObservationRepository
import dev.pelayo.aves.core.data.SpeciesRepository
import dev.pelayo.aves.core.database.entity.ObservationEntity
import dev.pelayo.aves.core.designsystem.theme.AvesTheme
import dev.pelayo.aves.core.designsystem.theme.ScientificNameStyle
import dev.pelayo.aves.core.model.ObservationSource
import dev.pelayo.aves.core.model.RarityTier
import dev.pelayo.aves.core.model.Species
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class SpeciesDetailUiState(
    val isLoading: Boolean = true,
    val species: Species? = null,
    /** Ordenados del mas reciente al mas antiguo. */
    val sightings: List<Sighting> = emptyList(),
) {
    /** El mejor tier alcanzado; ver el razonamiento en [CollectedSpecies.rarity]. */
    val rarity: RarityTier
        get() = sightings.maxByOrNull { it.rarity.level }?.rarity ?: RarityTier.VERY_COMMON
}

/**
 * La ficha de una especie concreta.
 *
 * Se filtra en memoria en lugar de pedir una consulta nueva al repositorio
 * porque la coleccion confirmada de un usuario son decenas o cientos de filas,
 * no millones, y asi la ficha reacciona al instante cuando se confirma un
 * avistamiento nuevo sin tocar la capa de datos.
 */
class SpeciesDetailViewModel(
    private val speciesId: String,
    private val speciesRepository: SpeciesRepository,
    observationRepository: ObservationRepository,
) : ViewModel() {

    private val species = MutableStateFlow<Species?>(null)
    private val resolved = MutableStateFlow(false)

    val uiState: StateFlow<SpeciesDetailUiState> = combine(
        species,
        resolved,
        observationRepository.collection(),
    ) { found, isResolved, observations ->
        SpeciesDetailUiState(
            isLoading = !isResolved,
            species = found,
            sightings = observations
                .filter { it.speciesId == speciesId }
                .map(ObservationEntity::toSighting)
                .sortedByDescending { it.timestamp },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = SpeciesDetailUiState(),
    )

    init {
        viewModelScope.launch {
            species.value = speciesRepository.find(speciesId)
            resolved.value = true
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

@Composable
fun SpeciesDetailScreen(
    speciesId: String,
    viewModelFactory: (String) -> SpeciesDetailViewModel,
    modifier: Modifier = Modifier,
) {
    // La clave evita que al navegar de una especie a otra se reutilice el
    // ViewModel de la anterior y la ficha se quede con los datos viejos.
    val viewModel: SpeciesDetailViewModel = viewModel(key = "especie-$speciesId") {
        viewModelFactory(speciesId)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SpeciesDetailContent(state = state, modifier = modifier)
}

@Composable
internal fun SpeciesDetailContent(
    state: SpeciesDetailUiState,
    modifier: Modifier = Modifier,
) {
    val species = state.species

    // Uno para toda la ficha: dos cantos solapados no se pueden comparar, y asi
    // solo hay un reproductor que liberar al salir.
    val clipPlayer = rememberClipPlayer()

    if (species == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (state.isLoading) {
                CircularProgressIndicator()
            } else {
                Text(
                    text = "Esta especie no está en el catálogo instalado.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "cabecera") {
            SpeciesHeader(species = species, rarity = state.rarity)
        }

        item(key = "separador") {
            Column {
                Spacer(Modifier.size(16.dp))
                HorizontalDivider()
                Spacer(Modifier.size(16.dp))
                Text(
                    text = if (state.sightings.size == 1) {
                        "Tu avistamiento"
                    } else {
                        "Tus ${state.sightings.size} avistamientos"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }

        items(state.sightings, key = { it.id }) { sighting ->
            // Comprobar si el recorte sigue en disco toca el sistema de
            // ficheros: se hace una vez por avistamiento, no en cada
            // recomposicion de la lista.
            val canPlay = remember(clipPlayer, sighting.mediaPath) {
                clipPlayer.canPlay(sighting.mediaPath)
            }

            SightingRow(
                sighting = sighting,
                canPlayClip = canPlay,
                isPlaying = clipPlayer.isPlaying(sighting.id),
                onTogglePlay = { clipPlayer.toggle(sighting.id, sighting.mediaPath) },
            )
        }
    }
}

@Composable
private fun SpeciesHeader(
    species: Species,
    rarity: RarityTier,
    modifier: Modifier = Modifier,
) {
    val tint = rarityColor(rarity)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = species.commonName,
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            // Cursiva serif por convencion taxonomica, agrandada porque aqui el
            // nombre cientifico es protagonista y no un pie de tarjeta.
            text = species.scientificName,
            style = ScientificNameStyle.copy(fontSize = 18.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.size(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(12.dp)
                    .background(tint, CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = rarityLabel(rarity),
                style = MaterialTheme.typography.labelLarge,
                color = tint,
            )
        }

        Spacer(Modifier.size(16.dp))

        TaxonomyRow(label = "Familia", value = species.family)
        TaxonomyRow(label = "Orden", value = species.order)
        TaxonomyRow(label = "Género", value = species.genus)
    }
}

@Composable
private fun TaxonomyRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Una fila de avistamiento, con su grabacion si la hay.
 *
 * @param canPlayClip false cuando el recorte ya no esta en disco (datos
 *   borrados, retencion). El boton se sigue viendo, desactivado: saber que hubo
 *   una grabacion es informacion util aunque ya no se pueda oir.
 */
@Composable
private fun SightingRow(
    sighting: Sighting,
    modifier: Modifier = Modifier,
    canPlayClip: Boolean = false,
    isPlaying: Boolean = false,
    onTogglePlay: () -> Unit = {},
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = formatSightingDate(sighting.timestamp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "${sourceLabel(sighting.source)} · " +
                        "${(sighting.confidence * 100).toInt()} % de confianza",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (sighting.mediaPath != null) {
                IconButton(onClick = onTogglePlay, enabled = canPlayClip) {
                    ClipPlayIcon(
                        isPlaying = isPlaying,
                        contentDescription = when {
                            !canPlayClip -> "La grabación ya no está en el dispositivo"
                            isPlaying -> "Parar la grabación"
                            else -> "Reproducir la grabación"
                        },
                    )
                }
            }
        }
    }
}

private fun sourceLabel(source: ObservationSource): String = when (source) {
    ObservationSource.AUDIO -> "Canto"
    ObservationSource.IMAGE -> "Imagen"
    ObservationSource.MANUAL -> "Manual"
}

/**
 * Fecha en la zona y el idioma del dispositivo.
 *
 * Se formatea al pintar y no al guardar porque el timestamp es un instante
 * absoluto: si el usuario cruza un huso horario, lo correcto es que la ficha se
 * reinterprete, no que arrastre el formato del momento de la captura.
 */
private val SightingDateFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", Locale.getDefault())

private fun formatSightingDate(epochMillis: Long): String =
    SightingDateFormat.format(
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()),
    )

// --- Previews -------------------------------------------------------------

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun SpeciesDetailPreview() {
    AvesTheme {
        SpeciesDetailContent(
            state = SpeciesDetailUiState(
                isLoading = false,
                species = previewSpecies(
                    id = "strala",
                    common = "Cárabo común",
                    scientific = "Strix aluco",
                    family = "Strigidae",
                    order = "Strigiformes",
                ),
                sightings = listOf(
                    previewSighting(
                        id = 1,
                        speciesId = "strala",
                        timestamp = 1_755_000_000_000L,
                        tier = RarityTier.EXCEPTIONAL,
                        mediaPath = "media/strala-1.ogg",
                    ),
                    previewSighting(
                        id = 2,
                        speciesId = "strala",
                        timestamp = 1_752_000_000_000L,
                        tier = RarityTier.RARE,
                    ),
                ),
            ),
        )
    }
}

@Preview(showBackground = true, heightDp = 400)
@Composable
private fun SpeciesDetailUnknownPreview() {
    AvesTheme {
        SpeciesDetailContent(state = SpeciesDetailUiState(isLoading = false, species = null))
    }
}
