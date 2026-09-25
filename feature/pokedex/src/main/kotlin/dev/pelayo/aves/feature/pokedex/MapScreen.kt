package dev.pelayo.aves.feature.pokedex

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pelayo.aves.core.designsystem.theme.ScientificNameStyle
import dev.pelayo.aves.core.map.BirdMap
import dev.pelayo.aves.core.map.GbifTiles
import dev.pelayo.aves.core.map.MapPoint
import dev.pelayo.aves.core.map.MapViewport
import dev.pelayo.aves.core.map.pinColor
import dev.pelayo.aves.core.model.ObservationSource

/**
 * El mapa a pantalla completa: de una especie (donde se registra en el mundo y
 * donde la has visto tu) o de toda la coleccion (tus avistamientos y donde se
 * registran aves).
 *
 * @param speciesId la especie, o null para el mapa de la coleccion.
 */
@Composable
fun MapScreen(
    speciesId: String?,
    viewModelFactory: (String?) -> MapViewModel,
    onSpeciesClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val viewModel: MapViewModel = viewModel(key = "mapa-${speciesId ?: "coleccion"}") { viewModelFactory(speciesId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    MapContent(
        state = state,
        onToggleDensity = viewModel::toggleDensity,
        onTogglePins = viewModel::togglePins,
        onSelect = viewModel::select,
        onRetryDensity = viewModel::resolveDensity,
        onSpeciesClick = onSpeciesClick,
        modifier = modifier,
    )
}

@Composable
internal fun MapContent(
    state: MapUiState,
    onToggleDensity: () -> Unit,
    onTogglePins: () -> Unit,
    onSelect: (MapPin?) -> Unit,
    onRetryDensity: () -> Unit,
    onSpeciesClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        BirdMap(
            viewport = state.viewport,
            points = state.visiblePoints,
            densityTaxonKey = state.densityTaxonKey.takeIf { state.showDensity },
            modifier = Modifier.fillMaxSize(),
            onPointClick = { point -> onSelect(state.pins.firstOrNull { it.point.id == point.id }) },
            onMapTap = { onSelect(null) },
        )

        // Las capas, arriba, sobre un velo: encima de un mapa un chip sin fondo
        // no se lee.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.showPins,
                        onClick = onTogglePins,
                        label = { Text(stringResource(R.string.pokedex_map_layer_mine)) },
                    )
                    FilterChip(
                        selected = state.showDensity,
                        onClick = onToggleDensity,
                        enabled = state.densityStatus != DensityStatus.UNAVAILABLE,
                        label = {
                            Text(
                                stringResource(
                                    if (state.isSpeciesMap) R.string.pokedex_map_layer_range else R.string.pokedex_map_layer_gbif,
                                ),
                            )
                        },
                    )
                }
            }
            state.speciesName?.takeIf { it.isNotBlank() }?.let { name ->
                Spacer(Modifier.size(6.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }

        // Abajo: la tarjeta del punto tocado, o un aviso si no hay nada que
        // pintar, y siempre la atribucion.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            val selected = state.selected
            when {
                selected != null -> PinCard(pin = selected, onOpenSpecies = { onSpeciesClick(selected.speciesId) })

                state.showPins && state.pins.isEmpty() && !state.isLoading -> Notice(
                    text = stringResource(R.string.pokedex_map_no_points),
                )

                state.showDensity && state.densityStatus == DensityStatus.UNAVAILABLE -> Notice(
                    text = stringResource(R.string.pokedex_map_key_unavailable),
                )

                state.showDensity && state.densityStatus == DensityStatus.OFFLINE -> Notice(
                    text = stringResource(R.string.pokedex_map_offline),
                    action = stringResource(R.string.pokedex_map_retry),
                    onAction = onRetryDensity,
                )
            }
            Spacer(Modifier.size(6.dp))
            Text(
                text = GbifTiles.ATTRIBUTION,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** El avistamiento tocado: que es, como se consiguio, cuando, y la puerta a su ficha. */
@Composable
private fun PinCard(pin: MapPin, onOpenSpecies: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(pinColor(pin.point.kind))),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(text = pin.commonName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = pin.scientificName, style = ScientificNameStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = sourceIcon(pin.source),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = formatSightingDate(pin.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onOpenSpecies) { Text(stringResource(R.string.pokedex_map_open_species)) }
        }
    }
}

@Composable
private fun Notice(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

/**
 * El mapa pequeno de la ficha: donde se registra la especie y tus avistamientos
 * de ella. No se puede mover; tocarlo abre el grande.
 */
@Composable
internal fun RangeMapCard(
    taxonKey: Int?,
    points: List<MapPoint>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewport = points.takeIf { it.isNotEmpty() }?.let { MapViewport.fitting(it) } ?: MapViewport.SPAIN
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(16.dp)),
    ) {
        BirdMap(
            viewport = viewport,
            points = points,
            densityTaxonKey = taxonKey,
            interactive = false,
            onMapTap = onOpen,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            text = stringResource(R.string.pokedex_map_card_hint),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** Los avistamientos con coordenadas de una ficha, como puntos del mapa. */
internal fun List<Sighting>.toMapPoints(title: String): List<MapPoint> = mapNotNull { sighting ->
    val latitude = sighting.latitude ?: return@mapNotNull null
    val longitude = sighting.longitude ?: return@mapNotNull null
    MapPoint(sighting.id, latitude, longitude, title, sighting.source.toPointKind())
}

@Suppress("unused")
private fun ObservationSource.label(): Int = when (this) {
    ObservationSource.AUDIO -> R.string.pokedex_source_audio
    ObservationSource.IMAGE -> R.string.pokedex_source_image
    ObservationSource.MANUAL -> R.string.pokedex_source_manual
}
