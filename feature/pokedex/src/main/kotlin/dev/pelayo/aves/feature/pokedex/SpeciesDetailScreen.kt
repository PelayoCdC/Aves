package dev.pelayo.aves.feature.pokedex

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import dev.pelayo.aves.core.data.ObservationRepository
import dev.pelayo.aves.core.data.SpeciesEncyclopedia
import dev.pelayo.aves.core.data.SpeciesRepository
import dev.pelayo.aves.core.database.entity.ObservationEntity
import dev.pelayo.aves.core.designsystem.theme.AvesTheme
import dev.pelayo.aves.core.designsystem.theme.ScientificNameStyle
import dev.pelayo.aves.core.media.ClipPlayIcon
import dev.pelayo.aves.core.media.rememberClipPlayer
import dev.pelayo.aves.core.model.Coordinates
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
    /** Se esta pidiendo la ficha a Wikipedia. */
    val isLoadingEncyclopedia: Boolean = false,
) {
    /** El mejor tier alcanzado; ver el razonamiento en [CollectedSpecies.rarity]. */
    val rarity: RarityTier
        get() = sightings.maxByOrNull { it.rarity.level }?.rarity ?: RarityTier.UNKNOWN

    val isCollected: Boolean get() = sightings.isNotEmpty()
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
 * @param rarityOf rareza de la especie en un lugar y ahora, para lo anotado a mano.
 */
class SpeciesDetailViewModel(
    private val speciesId: String,
    private val speciesRepository: SpeciesRepository,
    private val observationRepository: ObservationRepository,
    private val encyclopedia: SpeciesEncyclopedia? = null,
    private val currentLocation: suspend () -> Coordinates? = { null },
    private val rarityOf: suspend (speciesId: String, where: Coordinates) -> RarityTier = { _, _ -> RarityTier.UNKNOWN },
) : ViewModel() {

    private val species = MutableStateFlow<Species?>(null)
    private val resolved = MutableStateFlow(false)
    private val loadingEncyclopedia = MutableStateFlow(false)

    val uiState: StateFlow<SpeciesDetailUiState> = combine(
        species,
        resolved,
        observationRepository.collection(),
        loadingEncyclopedia,
    ) { found, isResolved, observations, loading ->
        SpeciesDetailUiState(
            isLoading = !isResolved,
            species = found,
            sightings = observations
                .filter { it.speciesId == speciesId }
                .map(ObservationEntity::toSighting)
                .sortedByDescending { it.timestamp },
            isLoadingEncyclopedia = loading,
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
            // La enciclopedia se pide DESPUES de pintar lo que ya hay: la ficha
            // no espera a la red para ensenar el nombre y la familia.
            if (found != null && !found.hasEncyclopedia && encyclopedia != null) {
                loadingEncyclopedia.value = true
                species.value = runCatching { encyclopedia.enrich(found) }.getOrDefault(found)
                loadingEncyclopedia.value = false
            }
        }
    }

    /** "Lo he visto": un avistamiento anotado a mano, ahora y aqui. */
    fun addManualSighting(notes: String?) {
        viewModelScope.launch {
            val where = runCatching { currentLocation() }.getOrNull()
            val rarity = where?.let { runCatching { rarityOf(speciesId, it) }.getOrNull() } ?: RarityTier.UNKNOWN
            observationRepository.recordManualObservation(
                speciesId = speciesId,
                timestamp = System.currentTimeMillis(),
                latitude = where?.latitude,
                longitude = where?.longitude,
                notes = notes,
                rarityTier = rarity,
            )
        }
    }

    fun updateNotes(sightingId: Long, notes: String?) {
        viewModelScope.launch { observationRepository.updateNotes(sightingId, notes) }
    }

    fun deleteSighting(sightingId: Long) {
        viewModelScope.launch { observationRepository.delete(sightingId) }
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

    SpeciesDetailContent(
        state = state,
        onAddManualSighting = viewModel::addManualSighting,
        onUpdateNotes = viewModel::updateNotes,
        onDeleteSighting = viewModel::deleteSighting,
        modifier = modifier,
    )
}

@Composable
internal fun SpeciesDetailContent(
    state: SpeciesDetailUiState,
    modifier: Modifier = Modifier,
    onAddManualSighting: (String?) -> Unit = {},
    onUpdateNotes: (Long, String?) -> Unit = { _, _ -> },
    onDeleteSighting: (Long) -> Unit = {},
) {
    val species = state.species

    // Uno para toda la ficha: dos cantos solapados no se pueden comparar, y asi
    // solo hay un reproductor que liberar al salir.
    val clipPlayer = rememberClipPlayer()

    var showManualDialog by remember { mutableStateOf(false) }
    var editingNotes by remember { mutableStateOf<Sighting?>(null) }
    var deleting by remember { mutableStateOf<Sighting?>(null) }

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
            SpeciesHeader(
                species = species,
                rarity = state.rarity,
                isCollected = state.isCollected,
                isLoadingEncyclopedia = state.isLoadingEncyclopedia,
                onAddManualSighting = { showManualDialog = true },
            )
        }

        item(key = "separador") {
            Column {
                Spacer(Modifier.size(16.dp))
                HorizontalDivider()
                Spacer(Modifier.size(16.dp))
                Text(
                    text = when (state.sightings.size) {
                        0 -> "Todavía no la tienes"
                        1 -> "Tu avistamiento"
                        else -> "Tus ${state.sightings.size} avistamientos"
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
                onEditNotes = { editingNotes = sighting },
                onDelete = { deleting = sighting },
            )
        }
    }

    if (showManualDialog) {
        NotesDialog(
            title = "Anotar un avistamiento de ${species.commonName}",
            initial = "",
            confirmLabel = "Anotar",
            onDismiss = { showManualDialog = false },
            onConfirm = { notes ->
                onAddManualSighting(notes)
                showManualDialog = false
            },
        )
    }

    editingNotes?.let { sighting ->
        NotesDialog(
            title = "Notas del avistamiento",
            initial = sighting.notes.orEmpty(),
            confirmLabel = "Guardar",
            onDismiss = { editingNotes = null },
            onConfirm = { notes ->
                onUpdateNotes(sighting.id, notes)
                editingNotes = null
            },
        )
    }

    deleting?.let { sighting ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("¿Borrar este avistamiento?") },
            text = { Text("Se quita de tu colección. No se puede deshacer.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        clipPlayer.stopIfPlaying(sighting.id)
                        onDeleteSighting(sighting.id)
                        deleting = null
                    },
                ) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun SpeciesHeader(
    species: Species,
    rarity: RarityTier,
    isCollected: Boolean,
    isLoadingEncyclopedia: Boolean,
    onAddManualSighting: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = rarityColor(rarity)
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxWidth()) {
        species.imageUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = species.commonName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
            species.imageAttribution?.let { attribution ->
                Text(
                    text = "Imagen: $attribution",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.size(12.dp))
        }

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

        if (isCollected) {
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
        }

        TaxonomyRow(label = "Familia", value = species.family)
        TaxonomyRow(label = "Orden", value = species.order)
        TaxonomyRow(label = "Género", value = species.genus)

        val summary = species.summary
        when {
            !summary.isNullOrBlank() -> {
                Spacer(Modifier.size(16.dp))
                Text(text = summary, style = MaterialTheme.typography.bodyMedium)
                species.wikipediaUrl?.let { url ->
                    TextButton(
                        onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        },
                    ) { Text("Leer más en Wikipedia") }
                }
            }

            isLoadingEncyclopedia -> {
                Spacer(Modifier.size(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Buscando la ficha en Wikipedia…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.size(16.dp))

        // "Lo he visto": la via manual. Sin modelo detras, con la persona como
        // unico clasificador. Es lo que convierte la busqueda en el catalogo en
        // algo mas que una guia de consulta.
        OutlinedButton(onClick = onAddManualSighting) {
            Text(if (isCollected) "Anotar otro avistamiento" else "Lo he visto: anotarlo")
        }
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
 *   borrados, copia de seguridad sin recortes). El boton se sigue viendo,
 *   desactivado: saber que hubo una grabacion es informacion util aunque ya no
 *   se pueda oir.
 */
@Composable
private fun SightingRow(
    sighting: Sighting,
    modifier: Modifier = Modifier,
    canPlayClip: Boolean = false,
    isPlaying: Boolean = false,
    onTogglePlay: () -> Unit = {},
    onEditNotes: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = formatSightingDate(sighting.timestamp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = buildString {
                            append(sourceLabel(sighting.source))
                            if (sighting.source != ObservationSource.MANUAL) {
                                append(" · ${(sighting.confidence * 100).toInt()} % de confianza")
                            }
                            append(" · ${rarityLabel(sighting.rarity).lowercase()}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (sighting.mediaPath != null && sighting.source == ObservationSource.AUDIO) {
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
                IconButton(onClick = onEditNotes) {
                    Icon(Icons.Filled.Edit, contentDescription = "Editar las notas")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Borrar el avistamiento")
                }
            }

            if (sighting.mediaPath != null && sighting.source == ObservationSource.IMAGE) {
                Spacer(Modifier.size(8.dp))
                AsyncImage(
                    model = java.io.File(sighting.mediaPath),
                    contentDescription = "La foto con la que se identificó",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }

            sighting.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                Spacer(Modifier.size(6.dp))
                Text(text = notes, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun NotesDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Notas (opcional)") },
                placeholder = { Text("Dónde, cómo, con quién…") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(text.takeIf { it.isNotBlank() }) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private fun sourceLabel(source: ObservationSource): String = when (source) {
    ObservationSource.AUDIO -> "Canto"
    ObservationSource.IMAGE -> "Foto"
    ObservationSource.MANUAL -> "Anotado a mano"
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

internal fun formatSightingDate(epochMillis: Long): String =
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
                ).copy(summary = "El cárabo común es una rapaz nocturna de tamaño medio, muy extendida por Europa."),
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
