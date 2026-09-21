package dev.pelayo.aves.feature.pokedex

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import dev.pelayo.aves.core.common.PhotoStorage
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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

    // El filtro por origen. Si se borra el ultimo avistamiento del tipo elegido
    // se vuelve a "todos": un filtro sobre nada dejaria la lista vacia sin que
    // se entendiera por que.
    var sourceFilter by rememberSaveable { mutableStateOf<ObservationSource?>(null) }
    val sources = state.sources
    val activeFilter = sourceFilter?.takeIf(sources::has)
    val visibleSightings = remember(state.sightings, activeFilter) {
        if (activeFilter == null) state.sightings else state.sightings.filter { it.source == activeFilter }
    }

    // El id y no el indice: si se borra una foto con el visor abierto, el indice
    // apuntaria a otra.
    // Solo las que siguen en disco: una foto perdida no tiene nada que ensenar
    // en el visor, y su fila ya dice que falta.
    val photos = remember(state.sightings) {
        state.photos.filter { PhotoStorage.forThumbnail(it.mediaPath.orEmpty()) != null }
    }
    var viewingPhotoId by rememberSaveable { mutableStateOf<Long?>(null) }

    if (species == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (state.isLoading) {
                CircularProgressIndicator()
            } else {
                Text(
                    text = stringResource(R.string.pokedex_detail_not_in_catalog),
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
                    text = if (state.sightings.isEmpty()) {
                        stringResource(R.string.pokedex_detail_no_sightings)
                    } else {
                        pluralStringResource(R.plurals.pokedex_detail_sightings, state.sightings.size, state.sightings.size)
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }

        if (photos.isNotEmpty()) {
            item(key = "fotos") {
                PhotoStrip(
                    photos = photos,
                    onOpen = { viewingPhotoId = it.id },
                )
            }
        }

        item(key = "filtros") {
            if (state.sightings.isEmpty()) {
                // Las tres casillas apagadas: lo que hay por conseguir.
                SourceIcons(sources = sources, iconSize = 22.dp)
            } else {
                SourceFilterRow(
                    sources = sources,
                    selected = activeFilter,
                    onSelect = { sourceFilter = it },
                )
            }
        }

        items(visibleSightings, key = { it.id }) { sighting ->
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
                onOpenPhoto = { viewingPhotoId = sighting.id },
                onEditNotes = { editingNotes = sighting },
                onDelete = { deleting = sighting },
            )
        }
    }

    val viewingIndex = photos.indexOfFirst { it.id == viewingPhotoId }
    if (viewingIndex >= 0) {
        PhotoViewer(
            photos = photos,
            startIndex = viewingIndex,
            title = species.commonName,
            onDismiss = { viewingPhotoId = null },
        )
    }

    if (showManualDialog) {
        NotesDialog(
            title = stringResource(R.string.pokedex_detail_manual_title, species.commonName),
            initial = "",
            confirmLabel = stringResource(R.string.pokedex_detail_annotate),
            onDismiss = { showManualDialog = false },
            onConfirm = { notes ->
                onAddManualSighting(notes)
                showManualDialog = false
            },
        )
    }

    editingNotes?.let { sighting ->
        NotesDialog(
            title = stringResource(R.string.pokedex_detail_notes_title),
            initial = sighting.notes.orEmpty(),
            confirmLabel = stringResource(R.string.pokedex_detail_save),
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
            title = { Text(stringResource(R.string.pokedex_detail_delete_title)) },
            text = { Text(stringResource(R.string.pokedex_detail_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        clipPlayer.stopIfPlaying(sighting.id)
                        onDeleteSighting(sighting.id)
                        deleting = null
                    },
                ) { Text(stringResource(R.string.pokedex_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.pokedex_cancel)) }
            },
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
                    text = stringResource(R.string.pokedex_detail_image_credit, attribution),
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
                    text = rarityText(rarity),
                    style = MaterialTheme.typography.labelLarge,
                    color = tint,
                )
            }
            Spacer(Modifier.size(16.dp))
        }

        TaxonomyRow(label = stringResource(R.string.pokedex_detail_family), value = species.family)
        TaxonomyRow(label = stringResource(R.string.pokedex_detail_order), value = species.order)
        TaxonomyRow(label = stringResource(R.string.pokedex_detail_genus), value = species.genus)

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
                    ) { Text(stringResource(R.string.pokedex_detail_read_more)) }
                }
            }

            isLoadingEncyclopedia -> {
                Spacer(Modifier.size(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.pokedex_detail_loading_encyclopedia),
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
            Text(
                stringResource(
                    if (isCollected) R.string.pokedex_detail_add_another else R.string.pokedex_detail_add_first,
                ),
            )
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
    onOpenPhoto: () -> Unit = {},
    onEditNotes: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // El mismo dibujo que en la rejilla: se ve de un vistazo si esta
                // fila es una vista, una foto o un canto. El texto de debajo ya
                // lo dice con palabras, asi que el icono va mudo.
                Icon(
                    imageVector = sourceIcon(sighting.source),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = formatSightingDate(sighting.timestamp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = buildList {
                            add(sourceLabel(sighting.source))
                            if (sighting.source != ObservationSource.MANUAL) {
                                add(stringResource(R.string.pokedex_detail_confidence, (sighting.confidence * 100).toInt()))
                            }
                            add(rarityText(sighting.rarity).lowercase())
                        }.joinToString(SEPARATOR),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (sighting.mediaPath != null && sighting.source == ObservationSource.AUDIO) {
                    IconButton(onClick = onTogglePlay, enabled = canPlayClip) {
                        ClipPlayIcon(
                            isPlaying = isPlaying,
                            contentDescription = stringResource(
                                when {
                                    !canPlayClip -> R.string.pokedex_detail_clip_missing
                                    isPlaying -> R.string.pokedex_detail_clip_stop
                                    else -> R.string.pokedex_detail_clip_play
                                },
                            ),
                        )
                    }
                }
                IconButton(onClick = onEditNotes) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.pokedex_detail_edit_notes))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.pokedex_detail_delete_sighting))
                }
            }

            if (sighting.mediaPath != null && sighting.source == ObservationSource.IMAGE) {
                Spacer(Modifier.size(8.dp))
                // Tocar el sistema de ficheros una vez por foto, no por recomposicion.
                val thumbnail = remember(sighting.mediaPath) { PhotoStorage.forThumbnail(sighting.mediaPath) }
                if (thumbnail != null) {
                    AsyncImage(
                        model = thumbnail,
                        contentDescription = stringResource(R.string.pokedex_detail_open_photo),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onOpenPhoto),
                    )
                } else {
                    // Igual que con los recortes de audio: saber que hubo una
                    // foto es informacion aunque ya no se pueda ver.
                    Text(
                        text = stringResource(R.string.pokedex_detail_photo_missing),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                label = { Text(stringResource(R.string.pokedex_detail_notes_label)) },
                placeholder = { Text(stringResource(R.string.pokedex_detail_notes_placeholder)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(text.takeIf { it.isNotBlank() }) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.pokedex_cancel)) } },
    )
}

@Composable
private fun sourceLabel(source: ObservationSource): String = stringResource(
    when (source) {
        ObservationSource.AUDIO -> R.string.pokedex_source_audio
        ObservationSource.IMAGE -> R.string.pokedex_source_image
        ObservationSource.MANUAL -> R.string.pokedex_source_manual
    },
)

private const val SEPARATOR = " · "

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
