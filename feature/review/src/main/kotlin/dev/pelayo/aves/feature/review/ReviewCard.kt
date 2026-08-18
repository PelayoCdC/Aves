package dev.pelayo.aves.feature.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import dev.pelayo.aves.core.media.ClipPlayIcon
import dev.pelayo.aves.core.media.ClipPlayerState
import dev.pelayo.aves.core.media.hasPlayableClip
import dev.pelayo.aves.core.media.rememberClipPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pelayo.aves.core.designsystem.theme.AvesTheme
import dev.pelayo.aves.core.designsystem.theme.ScientificNameStyle
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
 */
@Composable
fun ReviewCard(
    observation: PendingObservation,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
    canPlayClip: Boolean = false,
    isPlaying: Boolean = false,
    onTogglePlay: () -> Unit = {},
) {
    val accent = accentFor(observation.level)

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = observation.commonName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = observation.scientificName,
                        style = ScientificNameStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.size(12.dp))
                ConfidenceBadge(confidence = observation.confidence, accent = accent)
            }

            Spacer(Modifier.size(12.dp))

            // El grosor de la barra crece con la confianza. El color ya lo dice,
            // pero el peso visual se lee sin tener que interpretar un codigo de
            // colores ni leer el porcentaje.
            LinearProgressIndicator(
                progress = { observation.confidence },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeightFor(observation.level))
                    .clip(RoundedCornerShape(percent = 50)),
                color = accent,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )

            Spacer(Modifier.size(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = levelLabel(observation.level),
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
                Hint("El modelo no lo tiene claro. Compruébalo antes de darlo por bueno.")
            }

            if (!observation.isResolved) {
                Spacer(Modifier.size(8.dp))
                Hint("Esta especie no está en el catálogo, así que no podemos darte su nombre.")
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
                    Text("Descartar")
                }

                // Cuando la deteccion es dudosa, confirmar deja de ser el boton
                // por defecto: no queremos que aceptar a ciegas sea el camino
                // mas comodo justo en el caso en el que mas suele fallar.
                if (observation.level == ConfidenceLevel.DOUBTFUL) {
                    OutlinedButton(onClick = onConfirm) { Text("Confirmar") }
                } else {
                    Button(onClick = onConfirm) { Text("Confirmar") }
                }
            }
        }
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
            text = when {
                !enabled -> "Sin grabación"
                isPlaying -> "Parar"
                else -> "Escuchar"
            },
        )
    }
}

@Composable
private fun ConfidenceBadge(confidence: Float, accent: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        // Sobre el color plano el numero pierde contraste en tema oscuro; con un
        // velo del acento se lee igual de bien en los dos temas.
        color = accent.copy(alpha = 0.16f),
    ) {
        Text(
            text = "${(confidence * 100).toInt()} %",
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

private fun levelLabel(level: ConfidenceLevel): String = when (level) {
    ConfidenceLevel.SOLID -> "Muy probable"
    ConfidenceLevel.PROBABLE -> "Probable"
    ConfidenceLevel.DOUBTFUL -> "Dudosa"
}

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
    ),
    PendingObservation(
        observationId = 2L,
        speciesId = "eurrob",
        commonName = "Petirrojo europeo",
        scientificName = "Erithacus rubecula",
        confidence = 0.66f,
        rawConfidence = 0.72f,
        timestamp = 1_755_400_000_000L,
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
    ),
)
