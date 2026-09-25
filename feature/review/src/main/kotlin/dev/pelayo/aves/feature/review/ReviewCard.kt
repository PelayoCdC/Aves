package dev.pelayo.aves.feature.review

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pelayo.aves.core.designsystem.components.LocalShowConfidence
import dev.pelayo.aves.core.designsystem.components.formatConfidence
import dev.pelayo.aves.core.designsystem.theme.AvesTheme
import dev.pelayo.aves.core.designsystem.theme.ScientificNameStyle
import dev.pelayo.aves.core.media.ClipPlayIcon
import java.text.DateFormat
import java.util.Date

/**
 * Una deteccion pendiente, con su veredicto a un toque.
 *
 * Las dos acciones se muestran siempre las dos, sin gestos de deslizar ocultos:
 * descartar es destructivo y no puede depender de que el usuario adivine que
 * existe un gesto.
 *
 * El estado de la escucha entra por parametros sueltos y no como un
 * `ClipPlayerState`: asi la tarjeta no sabe nada de ExoPlayer y se puede
 * previsualizar y probar sin montar un reproductor.
 *
 * @param canPlayClip false cuando el recorte ya no esta en disco. El boton se
 *   sigue viendo, desactivado: que existio una grabacion es informacion util
 *   aunque ya no se pueda oir.
 * @param onConfirmAs "no era esto, era lo otro": confirma como la alternativa.
 */
@Composable
fun ReviewCard(
    observation: PendingObservation,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
    onConfirmAs: (Alternative) -> Unit = {},
    canPlayClip: Boolean = false,
    isPlaying: Boolean = false,
    onTogglePlay: () -> Unit = {},
    /** Abre la ficha de la especie. Null si el catalogo no la conoce. */
    onOpenSpecies: (() -> Unit)? = null,
    /** Ajuste "Mostrar confianza": el porcentaje junto al nivel y a cada alternativa. */
    showConfidence: Boolean = LocalShowConfidence.current,
) {
    val accent = accentFor(observation.level)

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                // El nombre abre la ficha: para decidir si era un carbonero o un
                // herrerillo hace falta ver la foto y leer la descripcion, no solo
                // oir el recorte.
                Column(
                    Modifier
                        .weight(1f)
                        .then(
                            if (onOpenSpecies != null) {
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(
                                        onClickLabel = stringResource(R.string.review_open_species, observation.commonName),
                                        onClick = onOpenSpecies,
                                    )
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = observation.commonName,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (onOpenSpecies != null) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Text(
                        text = observation.scientificName,
                        style = ScientificNameStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (observation.isLifer && observation.isResolved) {
                        Spacer(Modifier.size(4.dp))
                        LiferBadge()
                    }
                }

                Spacer(Modifier.size(12.dp))
                ConfidenceBadge(level = observation.level, accent = accent)
            }

            Spacer(Modifier.size(12.dp))

            // Una linea del color del nivel: "segura", "probable" o "dudosa" es
            // lo que hay que saber para decidir. El numero solo con el ajuste
            // "Mostrar confianza"; por defecto hacia desconfiar de las tres.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(barHeightFor(observation.level))
                    .clip(RoundedCornerShape(percent = 50))
                    .background(accent),
            )

            Spacer(Modifier.size(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (showConfidence) {
                        "${levelLabel(observation.level)} · ${formatConfidence(observation.confidence)}"
                    } else {
                        levelLabel(observation.level)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                )
                Text(
                    text = formatTimestamp(observation.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (observation.level == ConfidenceLevel.DOUBTFUL) {
                Spacer(Modifier.size(8.dp))
                Hint(stringResource(R.string.review_hint_doubtful))
            }

            if (!observation.isResolved) {
                Spacer(Modifier.size(8.dp))
                Hint(stringResource(R.string.review_hint_unresolved))
            }

            // "¿Era otra?" Los otros candidatos de la misma ventana, a un toque.
            // Existe porque buscar la especie correcta a mano es justo el paso
            // que hace que la gente descarte en vez de corregir.
            if (observation.alternatives.isNotEmpty()) {
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.review_was_it_another),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    observation.alternatives.forEach { alternative ->
                        AssistChip(
                            onClick = { onConfirmAs(alternative) },
                            label = {
                                Text(
                                    if (showConfidence) {
                                        "${alternative.commonName} · ${formatConfidence(alternative.score)}"
                                    } else {
                                        alternative.commonName
                                    },
                                )
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.size(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Escuchar va a la izquierda, separado del veredicto: es el paso
                // previo, no una tercera opcion al mismo nivel que confirmar o
                // descartar.
                if (observation.mediaPath != null) {
                    ClipButton(
                        isPlaying = isPlaying,
                        enabled = canPlayClip,
                        onClick = onTogglePlay,
                    )
                }

                Spacer(Modifier.weight(1f))

                TextButton(onClick = onReject) {
                    Text(stringResource(R.string.review_reject))
                }

                // Cuando la deteccion es dudosa, confirmar deja de ser el boton
                // por defecto: no queremos que aceptar a ciegas sea el camino
                // mas comodo justo en el caso en el que mas suele fallar.
                if (observation.level == ConfidenceLevel.DOUBTFUL) {
                    OutlinedButton(onClick = onConfirm) { Text(stringResource(R.string.review_confirm)) }
                } else {
                    Button(onClick = onConfirm) { Text(stringResource(R.string.review_confirm)) }
                }
            }
        }
    }
}

/** "Nueva para ti": la especie no esta en la coleccion todavia. */
@Composable
private fun LiferBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(
            text = stringResource(R.string.review_lifer_badge),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * El boton que hace utilizable la bandeja: oir la deteccion antes de juzgarla.
 *
 * Lleva texto y no solo icono porque es la accion que da sentido a la pantalla y
 * no puede depender de que se reconozca un triangulito; ademas el texto es lo
 * unico que puede decir que la grabacion ya no esta sin anadir otra linea a la
 * tarjeta.
 */
@Composable
private fun ClipButton(
    isPlaying: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        ClipPlayIcon(
            isPlaying = isPlaying,
            // El texto del propio boton ya lo describe; duplicarlo haria que el
            // lector de pantalla lo dijera dos veces.
            contentDescription = null,
            size = ButtonDefaults.IconSize,
        )
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(
            text = stringResource(
                when {
                    !enabled -> R.string.review_clip_missing
                    isPlaying -> R.string.review_clip_stop
                    else -> R.string.review_clip_play
                },
            ),
        )
    }
}

@Composable
private fun ConfidenceBadge(level: ConfidenceLevel, accent: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        // Sobre el color plano el numero pierde contraste en tema oscuro; con un
        // velo del acento se lee igual de bien en los dos temas.
        color = accent.copy(alpha = 0.16f),
    ) {
        Text(
            text = stringResource(
                when (level) {
                    ConfidenceLevel.SOLID -> R.string.review_level_solid
                    ConfidenceLevel.PROBABLE -> R.string.review_level_probable
                    ConfidenceLevel.DOUBTFUL -> R.string.review_level_doubtful
                },
            ),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun accentFor(level: ConfidenceLevel): Color = when (level) {
    ConfidenceLevel.SOLID -> MaterialTheme.colorScheme.primary
    ConfidenceLevel.PROBABLE -> MaterialTheme.colorScheme.tertiary
    ConfidenceLevel.DOUBTFUL -> MaterialTheme.colorScheme.error
}

private fun barHeightFor(level: ConfidenceLevel): Dp = when (level) {
    ConfidenceLevel.SOLID -> 10.dp
    ConfidenceLevel.PROBABLE -> 7.dp
    ConfidenceLevel.DOUBTFUL -> 4.dp
}

@Composable
private fun levelLabel(level: ConfidenceLevel): String = stringResource(
    when (level) {
        ConfidenceLevel.SOLID -> R.string.review_level_solid
        ConfidenceLevel.PROBABLE -> R.string.review_level_probable
        ConfidenceLevel.DOUBTFUL -> R.string.review_level_doubtful
    },
)

@Composable
private fun formatTimestamp(millis: Long): String {
    // El formato depende del idioma del telefono, no del nuestro: la fecha es
    // dato de campo y tiene que leerse como la lee el usuario.
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    return remember(millis, format) { format.format(Date(millis)) }
}

@Preview(showBackground = true)
@Composable
private fun ReviewCardPreview() {
    AvesTheme(dynamicColor = false) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SampleObservations.forEach { observation ->
                ReviewCard(
                    observation = observation,
                    onConfirm = {},
                    onReject = {},
                    // En la previsualizacion no hay reproductor ni ficheros, asi
                    // que los estados se fuerzan a mano para poder ver los tres:
                    // sonando, listo para sonar y sin grabacion.
                    canPlayClip = observation.observationId != 3L,
                    isPlaying = observation.observationId == 1L,
                )
            }
        }
    }
}

/**
 * Datos de ejemplo para las previsualizaciones. Uno de cada nivel a proposito, y
 * uno sin recorte para ver la tarjeta cuando no hay nada que escuchar.
 */
internal val SampleObservations = listOf(
    PendingObservation(
        observationId = 1L,
        speciesId = "turmer",
        commonName = "Mirlo común",
        scientificName = "Turdus merula",
        confidence = 0.95f,
        rawConfidence = 0.91f,
        timestamp = 1_755_500_000_000L,
        mediaPath = "clips/session_12/turmer.m4a",
        isLifer = true,
    ),
    PendingObservation(
        observationId = 2L,
        speciesId = "eurrob",
        commonName = "Petirrojo europeo",
        scientificName = "Erithacus rubecula",
        confidence = 0.66f,
        rawConfidence = 0.72f,
        timestamp = 1_755_400_000_000L,
        alternatives = listOf(
            Alternative("phocol", "Colirrojo tizón", "Phoenicurus ochruros", 0.21f),
        ),
    ),
    PendingObservation(
        observationId = 3L,
        speciesId = "sedwar",
        commonName = "Carricero común",
        scientificName = "Acrocephalus scirpaceus",
        confidence = 0.41f,
        rawConfidence = 0.55f,
        timestamp = 1_755_300_000_000L,
        mediaPath = "clips/session_12/sedwar.m4a",
        alternatives = listOf(
            Alternative("acrpal", "Carricero políglota", "Acrocephalus palustris", 0.33f),
            Alternative("acrsch", "Carricerín común", "Acrocephalus schoenobaenus", 0.12f),
        ),
    ),
)
