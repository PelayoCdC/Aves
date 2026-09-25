package dev.pelayo.aves.feature.pokedex

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.AddPhotoAlternate
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
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import dev.pelayo.aves.core.common.PhotoStorage
import dev.pelayo.aves.core.common.WikipediaImages
import dev.pelayo.aves.core.designsystem.components.LocalShowConfidence
import dev.pelayo.aves.core.designsystem.components.PickedPlace
import dev.pelayo.aves.core.designsystem.components.PlacePickerActions
import dev.pelayo.aves.core.designsystem.components.formatConfidence
import dev.pelayo.aves.core.designsystem.theme.AvesTheme
import dev.pelayo.aves.core.designsystem.theme.ScientificNameStyle
import dev.pelayo.aves.core.media.ClipPlayIcon
import dev.pelayo.aves.core.media.rememberClipPlayer
import dev.pelayo.aves.core.model.ObservationSource
import dev.pelayo.aves.core.model.RarityTier
import dev.pelayo.aves.core.model.Species
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Una foto identificada que espera a que el usuario diga si es esta especie.
 *
 * Existe porque antes tocar un candidato GUARDABA el avistamiento y abria la
 * ficha con el ya dentro. Lo que se quiere es lo contrario: abrir la ficha para
 * comparar la foto con la imagen y la descripcion, y anotar solo si cuadra.
 *
 * @param preview la foto del usuario, para tenerla al lado de la de Wikipedia.
 */
class PendingPhotoConfirmation(
    val preview: Bitmap?,
    val onConfirm: () -> Unit,
    val onReject: () -> Unit,
)

@Composable
fun SpeciesDetailScreen(
    speciesId: String,
    viewModelFactory: (String) -> SpeciesDetailViewModel,
    modifier: Modifier = Modifier,
    pendingPhoto: PendingPhotoConfirmation? = null,
    /** Abre el mapa grande de esta especie. Nulo esconde el mapa de la ficha. */
    onOpenMap: (() -> Unit)? = null,
    /** Para decir donde fue un avistamiento. Nulo esconde el chip de lugar. */
    placePicker: PlacePickerActions? = null,
    /** Abre la ficha de otra especie (las parecidas). */
    onOpenSpecies: (String) -> Unit = {},
) {
    // La clave evita que al navegar de una especie a otra se reutilice el
    // ViewModel de la anterior y la ficha se quede con los datos viejos.
    val viewModel: SpeciesDetailViewModel = viewModel(key = "especie-$speciesId") {
        viewModelFactory(speciesId)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.preparePhoto(uri)
    }

    SpeciesDetailContent(
        state = state,
        onAddManualSighting = viewModel::addManualSighting,
        onUpdateSighting = viewModel::updateSighting,
        onDeleteSighting = viewModel::deleteSighting,
        modifier = modifier,
        pendingPhoto = pendingPhoto,
        onOpenMap = onOpenMap,
        placePicker = placePicker,
        locate = viewModel::currentPlace,
        onAddPhoto = if (viewModel.canAddPhotos) {
            { photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        } else {
            null
        },
        onConfirmPhoto = viewModel::confirmPhoto,
        onDiscardPhoto = viewModel::discardPhoto,
        onDismissPhotoFailure = viewModel::dismissPhotoFailure,
        onOpenSpecies = onOpenSpecies,
    )
}

@Composable
internal fun SpeciesDetailContent(
    state: SpeciesDetailUiState,
    modifier: Modifier = Modifier,
    onAddManualSighting: (SightingDraft) -> Unit = {},
    onUpdateSighting: (Long, SightingDraft) -> Unit = { _, _ -> },
    onDeleteSighting: (Long) -> Unit = {},
    pendingPhoto: PendingPhotoConfirmation? = null,
    onOpenMap: (() -> Unit)? = null,
    placePicker: PlacePickerActions? = null,
    locate: (suspend () -> PickedPlace?)? = null,
    onAddPhoto: (() -> Unit)? = null,
    onConfirmPhoto: (SightingDraft) -> Unit = {},
    onDiscardPhoto: () -> Unit = {},
    onDismissPhotoFailure: () -> Unit = {},
    onOpenSpecies: (String) -> Unit = {},
) {
    val species = state.species

    // Uno para toda la ficha: dos cantos solapados no se pueden comparar, y asi
    // solo hay un reproductor que liberar al salir.
    val clipPlayer = rememberClipPlayer()

    var showManualDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Sighting?>(null) }
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
        pendingPhoto?.let { pending ->
            item(key = "confirmar-foto") {
                PendingPhotoCard(pending = pending, speciesName = species.commonName)
            }
        }

        item(key = "cabecera") {
            SpeciesHeader(
                species = species,
                rarity = state.rarity,
                isCollected = state.isCollected,
                isLoadingEncyclopedia = state.isLoadingEncyclopedia,
                onAddManualSighting = { showManualDialog = true },
                onAddPhoto = onAddPhoto,
                isSavingPhoto = state.isSavingPhoto,
            )
        }

        // Donde se registra en el mundo (GBIF) y donde la has visto tu. Va
        // despues de la cabecera y antes de tus avistamientos: es la respuesta
        // a "¿y esta por aqui?", que se pregunta antes de mirar la lista.
        if (onOpenMap != null) {
            item(key = "mapa") {
                Column {
                    Spacer(Modifier.size(8.dp))
                    RangeMapCard(
                        taxonKey = state.gbifTaxonKey,
                        points = state.sightings.toMapPoints(species.commonName),
                        onOpen = onOpenMap,
                    )
                }
            }
        }

        // Cuando se ve por aqui: la otra mitad de "¿y esta por aqui?".
        state.seasonality?.takeIf { months -> months.any { it > 0 } }?.let { months ->
            item(key = "estacionalidad") {
                Column {
                    Spacer(Modifier.size(8.dp))
                    SeasonalityCard(monthlyCounts = months)
                }
            }
        }

        // Con que se confunde, en TUS detecciones: la ayuda para distinguir
        // parejas dificiles (mosquitero comun e iberico, carbonero y garrapinos)
        // con los cantos propios de cada una, uno tras otro.
        if (state.lookalikes.isNotEmpty()) {
            item(key = "parecidas") {
                Column {
                    Spacer(Modifier.size(8.dp))
                    LookalikesCard(
                        lookalikes = state.lookalikes,
                        clipPlayer = clipPlayer,
                        onOpenSpecies = onOpenSpecies,
                    )
                }
            }
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
                onEditNotes = { editing = sighting },
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
        SightingEditorDialog(
            title = stringResource(R.string.pokedex_detail_manual_title, species.commonName),
            initial = SightingDraft(timestamp = System.currentTimeMillis(), place = null, notes = null),
            confirmLabel = stringResource(R.string.pokedex_detail_annotate),
            placePicker = placePicker,
            locateIfEmpty = locate,
            onDismiss = { showManualDialog = false },
            onConfirm = { draft ->
                onAddManualSighting(draft)
                showManualDialog = false
            },
        )
    }

    editing?.let { sighting ->
        SightingEditorDialog(
            title = stringResource(R.string.pokedex_detail_edit_sighting),
            initial = SightingDraft.of(sighting),
            confirmLabel = stringResource(R.string.pokedex_detail_save),
            placePicker = placePicker,
            onDismiss = { editing = null },
            onConfirm = { draft ->
                onUpdateSighting(sighting.id, draft)
                editing = null
            },
        )
    }

    // La foto subida desde la ficha: ya esta en disco, falta cuando y donde.
    state.photoDraft?.let { photo ->
        SightingEditorDialog(
            title = stringResource(R.string.pokedex_detail_photo_sighting_title, species.commonName),
            initial = SightingDraft(
                timestamp = photo.takenAtMillis ?: System.currentTimeMillis(),
                place = null,
                notes = null,
            ),
            confirmLabel = stringResource(R.string.pokedex_detail_annotate),
            placePicker = placePicker,
            onDismiss = onDiscardPhoto,
            onConfirm = onConfirmPhoto,
        )
    }

    if (state.photoFailed) {
        AlertDialog(
            onDismissRequest = onDismissPhotoFailure,
            title = { Text(stringResource(R.string.pokedex_detail_photo_failed_title)) },
            text = { Text(stringResource(R.string.pokedex_detail_photo_failed)) },
            confirmButton = { TextButton(onClick = onDismissPhotoFailure) { Text(stringResource(R.string.pokedex_detail_photo_failed_ok)) } },
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeciesHeader(
    species: Species,
    rarity: RarityTier,
    isCollected: Boolean,
    isLoadingEncyclopedia: Boolean,
    onAddManualSighting: () -> Unit,
    modifier: Modifier = Modifier,
    onAddPhoto: (() -> Unit)? = null,
    isSavingPhoto: Boolean = false,
) {
    val tint = rarityColor(rarity)
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxWidth()) {
        species.imageUrl?.let { url ->
            // ENTERA (ajustada, no recortada): las fotos de Commons son de
            // cualquier proporcion, y con recorte a un ave vertical se le iba
            // la cabeza. Al tocarla, a pantalla completa y con zoom.
            var showViewer by remember { mutableStateOf(false) }
            WikipediaPhoto(
                url = url,
                contentDescription = stringResource(R.string.pokedex_detail_open_image),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { showViewer = true },
            )
            if (showViewer) {
                val candidates = WikipediaImages.viewerCandidates(url)
                ImageViewer(
                    model = candidates.first(),
                    fallbacks = candidates.drop(1),
                    title = species.commonName,
                    caption = species.imageAttribution,
                    onDismiss = { showViewer = false },
                )
            }
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
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedButton(onClick = onAddManualSighting) {
                Text(
                    stringResource(
                        if (isCollected) R.string.pokedex_detail_add_another else R.string.pokedex_detail_add_first,
                    ),
                )
            }
            // Una foto que ya tienes de esta especie: sin pasar por el modelo,
            // que para eso ya sabes cual es.
            if (onAddPhoto != null) {
                OutlinedButton(onClick = onAddPhoto, enabled = !isSavingPhoto) {
                    Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pokedex_detail_add_photo))
                }
            }
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
    showConfidence: Boolean = LocalShowConfidence.current,
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
                            // El porcentaje, solo con "Mostrar confianza" (y nunca en lo
                            // anotado a mano, que no tiene modelo detras).
                            if (showConfidence && sighting.source != ObservationSource.MANUAL) {
                                add(stringResource(R.string.pokedex_detail_confidence, formatConfidence(sighting.confidence)))
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
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.pokedex_detail_edit_sighting))
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


/**
 * "¿Es esta?": la foto del usuario al lado de la ficha, y dos botones.
 *
 * Va arriba de todo, antes incluso de la imagen de Wikipedia, porque es la
 * pregunta que hay que responder antes de mirar nada mas. Al confirmar, el
 * avistamiento aparece abajo, en la lista, y esta tarjeta desaparece.
 */
@Composable
private fun PendingPhotoCard(
    pending: PendingPhotoConfirmation,
    speciesName: String,
    modifier: Modifier = Modifier,
) {
    val preview = remember(pending.preview) { pending.preview?.takeIf { !it.isRecycled }?.asImageBitmap() }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (preview != null) {
                    Image(
                        bitmap = preview,
                        contentDescription = stringResource(R.string.pokedex_detail_photo),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.width(16.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.pokedex_detail_photo_pending_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = stringResource(R.string.pokedex_detail_photo_pending_body, speciesName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = pending.onReject) { Text(stringResource(R.string.pokedex_detail_photo_reject)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = pending.onConfirm) { Text(stringResource(R.string.pokedex_detail_photo_confirm)) }
            }
        }
    }
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
