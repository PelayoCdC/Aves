package dev.pelayo.aves.feature.pokedex

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import dev.pelayo.aves.core.common.PhotoStorage
import dev.pelayo.aves.core.common.WikipediaImages
import dev.pelayo.aves.core.model.ObservationSource

/*
 * Lo que el usuario ha capturado de una especie, dentro de su ficha: la tira de
 * fotos, los filtros por origen y el visor a pantalla completa.
 *
 * Los recortes de audio no tienen pieza propia aqui: cada avistamiento por canto
 * ya trae su boton de reproducir en la lista, y el filtro "Cantos" los junta.
 */

/**
 * Las fotos de la especie, en una tira: es la manera de llegar a ellas sin
 * recorrer la lista de avistamientos, que en un ave comun son decenas de cantos.
 */
@Composable
internal fun PhotoStrip(
    photos: List<Sighting>,
    onOpen: (Sighting) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.pokedex_detail_photos_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(photos, key = { it.id }) { photo ->
                val file = remember(photo.mediaPath) { PhotoStorage.forThumbnail(photo.mediaPath.orEmpty()) }
                AsyncImage(
                    model = file,
                    contentDescription = stringResource(R.string.pokedex_detail_open_photo),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(PhotoStripSize)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onOpen(photo) },
                )
            }
        }
        Spacer(Modifier.size(8.dp))
    }
}

private val PhotoStripSize = 104.dp

/**
 * Los tres origenes como filtros de la lista, con cuantos hay de cada uno.
 *
 * Son los mismos tres iconos de la rejilla, y por eso el que no se tiene no
 * desaparece: se queda apagado (deshabilitado), que es lo que dice "esta te
 * falta". "Todos" va primero y es el estado por defecto.
 */
@Composable
internal fun SourceFilterRow(
    sources: CaptureSources,
    selected: ObservationSource?,
    onSelect: (ObservationSource?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.pokedex_filter_all)) },
        )
        for (source in SOURCE_ORDER) {
            val count = sources.count(source)
            FilterChip(
                selected = selected == source,
                // Tocar el ya elegido lo quita: es como se comporta un filtro.
                onClick = { onSelect(if (selected == source) null else source) },
                enabled = count > 0,
                label = { Text(stringResource(filterLabel(source), count)) },
                leadingIcon = {
                    Icon(
                        imageVector = sourceIcon(source),
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
            )
        }
    }
}

private fun filterLabel(source: ObservationSource): Int = when (source) {
    ObservationSource.MANUAL -> R.string.pokedex_filter_seen
    ObservationSource.IMAGE -> R.string.pokedex_filter_photos
    ObservationSource.AUDIO -> R.string.pokedex_filter_recordings
}

/**
 * Las fotos a pantalla completa, deslizando de una a otra.
 *
 * Un `Dialog` y no una ruta de navegacion: no tiene estado propio que merezca
 * sobrevivir, y asi "atras" lo cierra sin tocar la pila.
 */
@Composable
internal fun PhotoViewer(
    photos: List<Sighting>,
    startIndex: Int,
    title: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, photos.lastIndex)) { photos.size }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            HorizontalPager(
                state = pagerState,
                key = { photos[it].id },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val photo = photos[page]
                val file = remember(photo.mediaPath) { PhotoStorage.bestAvailable(photo.mediaPath.orEmpty()) }
                ZoomablePhoto(
                    model = file,
                    contentDescription = title,
                    isCurrent = pagerState.currentPage == page,
                )
            }

            // Arriba: cerrar y por cual se va. Abajo: de cuando es. Con un velo
            // para que se lean sobre una foto clara.
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(ViewerScrim)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.pokedex_viewer_close),
                        tint = Color.White,
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (photos.size > 1) {
                    Text(
                        text = stringResource(
                            R.string.pokedex_viewer_position,
                            pagerState.currentPage + 1,
                            photos.size,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                }
            }

            Text(
                text = formatSightingDate(photos[pagerState.currentPage.coerceIn(0, photos.lastIndex)].timestamp),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(ViewerScrim)
                    .padding(16.dp),
            )
        }
    }
}

private val ViewerScrim = Color.Black.copy(alpha = 0.45f)

/**
 * La imagen de Wikipedia de una ficha, sin quedarse gris.
 *
 * Commons solo sirve las miniaturas de unos pocos anchos ya renderizados; pedir
 * otro devuelve 400, y eso era lo que dejaba la cabecera gris. Asi que se pide
 * la miniatura tal como la da Wikipedia y, si aun asi falla, el original
 * ([WikipediaImages.headerCandidates]). Si nada carga, un icono, no un hueco.
 */
@Composable
internal fun WikipediaPhoto(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val candidates = remember(url) { WikipediaImages.headerCandidates(url) }
    var attempt by remember(url) { mutableIntStateOf(0) }
    val exhausted = attempt > candidates.lastIndex

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (exhausted) {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
        } else {
            AsyncImage(
                model = candidates[attempt],
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                onError = { attempt++ },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * UNA imagen a pantalla completa, con zoom: la de Wikipedia de la cabecera de la
 * ficha. El mismo visor que las fotos del usuario, sin paginador.
 *
 * @param model lo que entienda Coil: una URL o un fichero.
 * @param caption la atribucion, que con una imagen de Commons no es opcional.
 */
@Composable
internal fun ImageViewer(
    model: Any,
    title: String,
    caption: String?,
    onDismiss: () -> Unit,
    fallbacks: List<Any> = emptyList(),
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            ZoomablePhoto(model = model, contentDescription = title, isCurrent = true, fallbacks = fallbacks)

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(ViewerScrim)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.pokedex_viewer_close),
                        tint = Color.White,
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }

            if (caption != null) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(ViewerScrim)
                        .padding(16.dp),
                )
            }
        }
    }
}

/** Hasta donde se deja ampliar. Las fotos guardadas no dan para mas sin verse a cuadros. */
private const val MAX_ZOOM = 5f

/** A cuanto salta el doble toque. */
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * Una foto que se amplia con dos dedos o con doble toque.
 *
 * El arrastre SOLO se captura con la foto ampliada (`canPan`): a tamano normal
 * tiene que llegar al paginador, o no habria forma de pasar a la siguiente.
 *
 * @param isCurrent al pasar de pagina la foto vuelve a su tamano: encontrarse
 *   ampliada la que se dejo hace tres fotos no lo espera nadie.
 */
@Composable
private fun ZoomablePhoto(
    model: Any,
    contentDescription: String,
    isCurrent: Boolean,
    modifier: Modifier = Modifier,
    /** Que probar si [model] no carga, en orden. Ver [WikipediaPhoto]. */
    fallbacks: List<Any> = emptyList(),
) {
    val candidates = remember(model, fallbacks) { listOf(model) + fallbacks }
    var attempt by remember(model) { mutableIntStateOf(0) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(isCurrent) {
        if (!isCurrent) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    // Sin tope, la foto se puede arrastrar fuera de la pantalla y no volver.
    fun clamp(candidate: Offset, atScale: Float): Offset {
        val maxX = size.width * (atScale - 1f) / 2f
        val maxY = size.height * (atScale - 1f) / 2f
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
        scale = newScale
        offset = if (newScale == 1f) Offset.Zero else clamp(offset + panChange, newScale)
    }

    AsyncImage(
        model = candidates[attempt.coerceIn(0, candidates.lastIndex)],
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        onError = { if (attempt < candidates.lastIndex) attempt++ },
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = DOUBLE_TAP_ZOOM
                        }
                    },
                )
            }
            .transformable(state = transformState, canPan = { scale > 1f })
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}
