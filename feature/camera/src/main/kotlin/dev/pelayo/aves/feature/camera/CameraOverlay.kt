package dev.pelayo.aves.feature.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pelayo.aves.core.designsystem.components.LocalShowConfidence
import dev.pelayo.aves.core.designsystem.components.formatConfidence
import dev.pelayo.aves.core.designsystem.theme.ScientificNameStyle

/**
 * Todo lo que se dibuja encima de la vista previa.
 *
 * Sin ViewModel ni CameraX, para poder previsualizarlo.
 */
@Composable
internal fun CaptureOverlay(
    state: CameraUiState,
    onShutter: () -> Unit,
    onDismissResults: () -> Unit,
    onIdentified: (ImageCandidate) -> Unit,
    modifier: Modifier = Modifier,
    onPickFromGallery: (() -> Unit)? = null,
    /** true cuando lo que hay debajo es una foto de la galeria y no la camara. */
    fromGallery: Boolean = false,
    /** El chip de "¿donde fue?", que se pinta encima de los resultados. Solo con galeria. */
    placeChip: (@Composable () -> Unit)? = null,
    /** Abre el buscador del catalogo: "se cual es". Nulo esconde el boton. */
    onSearchSpecies: (() -> Unit)? = null,
    /** Abre el cuadro de "encuadra el ave" sobre la ultima foto. Nulo esconde el boton. */
    onReframe: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        state.error?.let { error ->
            ErrorCard(error = error, detail = state.errorDetail)
            Spacer(Modifier.size(12.dp))
        }

        placeChip?.let { chip ->
            MaterialSurface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            ) { chip() }
            Spacer(Modifier.size(12.dp))
        }

        // Si el ave era pequena o no estaba en el centro, el modelo vio sobre
        // todo ramas: encuadrarla a mano y volver a mirar.
        if (onReframe != null && state.hasResult && !state.isClassifying) {
            FilledTonalButton(onClick = onReframe) {
                Text(stringResource(R.string.camera_framing_open))
            }
            Spacer(Modifier.size(12.dp))
        }

        when {
            state.candidates.isNotEmpty() -> CandidateList(
                candidates = state.candidates,
                onIdentified = onIdentified,
                onDismiss = onDismissResults,
                onSearchSpecies = onSearchSpecies,
            )

            // Que el modelo no vea ningun ave es un resultado, no un fallo, y hay
            // que decirlo con esas palabras: si no, el usuario cree que la app se
            // ha roto y vuelve a disparar a lo mismo.
            state.hasResult && state.error == null -> NothingRecognized(onDismiss = onDismissResults, onSearchSpecies = onSearchSpecies)
        }

        Spacer(Modifier.size(20.dp))

        // Ocupa el sitio del disparador mientras se piensa, en vez de dejar un
        // boton apagado que invita a insistir con el dedo.
        if (state.isWarmingUp || state.isClassifying) {
            Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        } else if (fromGallery) {
            // Con una foto de la galeria no hay nada que disparar: se elige otra
            // o se vuelve a la camara.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismissResults) {
                    Text(stringResource(R.string.camera_back_to_camera), color = Color.White)
                }
                if (onPickFromGallery != null) {
                    Button(onClick = onPickFromGallery) {
                        Text(stringResource(R.string.camera_pick_another))
                    }
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                // Hueco del mismo ancho que el boton de galeria, para que el
                // disparador quede centrado de verdad.
                Spacer(Modifier.size(48.dp))
                ShutterButton(
                    enabled = state.error == null,
                    onClick = onShutter,
                )
                if (onPickFromGallery != null) {
                    FilledTonalIconButton(onClick = onPickFromGallery, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = Icons.Filled.PhotoLibrary,
                            contentDescription = stringResource(R.string.camera_gallery),
                        )
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
            }
        }

        Spacer(Modifier.size(12.dp))

        OverlayLabel(
            text = stringResource(
                when {
                    state.isWarmingUp -> R.string.camera_warming
                    state.isClassifying -> R.string.camera_classifying
                    state.candidates.isNotEmpty() -> R.string.camera_pick_candidate
                    fromGallery -> R.string.camera_picked_hint
                    else -> R.string.camera_frame_hint
                },
            ),
        )

        // Requisito de licencia, igual que en la pantalla de escucha.
        state.attribution?.let { attribution ->
            Spacer(Modifier.size(8.dp))
            OverlayLabel(text = attribution, alpha = 0.7f)
        }
    }
}

@Composable
internal fun CandidateList(
    candidates: List<ImageCandidate>,
    onIdentified: (ImageCandidate) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onSearchSpecies: (() -> Unit)? = null,
) {
    MaterialSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        // Semitransparente para no perder de vista al ave mientras se decide:
        // la comparacion entre la foto y el nombre es justo lo que hay que hacer.
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.camera_could_be),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))

            candidates.forEach { candidate ->
                CandidateRow(candidate = candidate, onIdentified = onIdentified)
                Spacer(Modifier.size(12.dp))
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // "Se cual es": cuando el modelo no da con ella pero la persona si.
                if (onSearchSpecies != null) {
                    TextButton(onClick = onSearchSpecies) { Text(stringResource(R.string.camera_search_species)) }
                } else {
                    Spacer(Modifier.size(1.dp))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.camera_none_of_these))
                }
            }
        }
    }
}

/**
 * Un candidato. Por defecto solo el nombre: la decision es mirar al ave y al
 * nombre, y un "23 %" junto a una especie hacia desconfiar de las tres. El orden
 * ya dice cual es la mas probable. Con el ajuste "Mostrar confianza", el
 * porcentaje va al lado.
 */
@Composable
internal fun CandidateRow(
    candidate: ImageCandidate,
    onIdentified: (ImageCandidate) -> Unit,
    modifier: Modifier = Modifier,
    showConfidence: Boolean = LocalShowConfidence.current,
) {
    val selectable = candidate.speciesId != null
    val common = candidate.commonName

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                enabled = selectable,
                onClickLabel = stringResource(R.string.camera_confirm_candidate, common ?: candidate.scientificName),
                role = Role.Button,
            ) { onIdentified(candidate) }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CandidateNames(candidate, Modifier.weight(1f))
        // Con "Mostrar confianza": la misma puntuacion que ordena la lista (la
        // del modelo con el contexto del lugar, si lo hay).
        if (showConfidence) {
            Text(
                text = formatConfidence(candidate.confidence),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun CandidateNames(candidate: ImageCandidate, modifier: Modifier = Modifier) {
    val common = candidate.commonName
    Column(modifier = modifier) {
        // El nombre en castellano manda; el cientifico, debajo, como en la
        // Pokedex. Si el catalogo no lo conoce, solo hay cientifico.
        if (common != null) {
            Text(text = common, style = MaterialTheme.typography.titleMedium)
        }
        Text(
            text = candidate.scientificName,
            style = ScientificNameStyle,
            color = if (common != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
internal fun NothingRecognized(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onSearchSpecies: (() -> Unit)? = null,
) {
    MaterialSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.camera_nothing_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = stringResource(R.string.camera_nothing_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = stringResource(R.string.camera_nothing_audio_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // Que el modelo no la vea no quiere decir que la persona no sepa cual es.
                if (onSearchSpecies != null) {
                    TextButton(onClick = onSearchSpecies) { Text(stringResource(R.string.camera_search_species)) }
                } else {
                    Spacer(Modifier.size(1.dp))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.camera_retry))
                }
            }
        }
    }
}

/**
 * Disparador.
 *
 * Circulo blanco dibujado a mano y no un color del tema: esta encima de una
 * imagen en directo cuyo color no se puede prever, y ahi el unico contraste
 * fiable es el del propio boton contra su borde.
 */
@Composable
internal fun ShutterButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val alpha = if (enabled) 1f else 0.35f

    Box(
        modifier = modifier
            .size(76.dp)
            .clip(CircleShape)
            .border(3.dp, Color.White.copy(alpha = alpha), CircleShape)
            .clickable(
                enabled = enabled,
                onClickLabel = stringResource(R.string.camera_shutter),
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = alpha)),
        )
    }
}

/**
 * Texto sobre la imagen en directo.
 *
 * Lleva su propia caja oscura porque el fondo puede ser cielo blanco: sin ella
 * el texto desaparece justo en el escenario mas comun, un ave contra el cielo.
 */
@Composable
internal fun OverlayLabel(
    text: String,
    modifier: Modifier = Modifier,
    alpha: Float = 0.85f,
) {
    MaterialSurface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color.Black.copy(alpha = 0.45f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            color = Color.White.copy(alpha = alpha),
        )
    }
}

