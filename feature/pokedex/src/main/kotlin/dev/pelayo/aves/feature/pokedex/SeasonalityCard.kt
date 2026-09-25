package dev.pelayo.aves.feature.pokedex

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * Doce barras, una por mes, con los registros de la especie por aqui.
 *
 * Sin cifras a proposito: lo que importa es la forma (todo el ano, solo en
 * verano, dos picos de paso), y los numeros absolutos dependen mas de cuanta
 * gente sale a mirar que del ave. El mes actual va resaltado.
 */
@Composable
internal fun SeasonalityCard(monthlyCounts: IntArray, modifier: Modifier = Modifier) {
    val max = monthlyCounts.maxOrNull()?.takeIf { it > 0 } ?: return
    val current = remember { LocalDate.now().monthValue }
    val locale = Locale.getDefault()
    val description = stringResource(R.string.pokedex_seasonality_description, seasonalityShape(monthlyCounts, locale))

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(text = stringResource(R.string.pokedex_seasonality_title), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(R.string.pokedex_seasonality_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(BAR_AREA_HEIGHT)
                    .semantics { contentDescription = description },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                monthlyCounts.forEachIndexed { index, count ->
                    // Un minimo visible para los meses con algun registro: una
                    // barra de 0,3 px no se distingue de "nada".
                    val fraction = if (count == 0) 0f else (count.toFloat() / max).coerceAtLeast(MIN_VISIBLE_FRACTION)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(fraction)
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(
                                if (index + 1 == current) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                },
                            ),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (month in 1..12) {
                    Text(
                        text = Month.of(month).getDisplayName(TextStyle.NARROW_STANDALONE, locale),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (month == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * Los meses con mas registros, para leerlo en voz alta: el lector de pantalla
 * no puede ver las barras.
 */
private fun seasonalityShape(counts: IntArray, locale: Locale): String {
    val max = counts.maxOrNull() ?: 0
    return counts.indices
        .filter { counts[it] >= max / 2 && counts[it] > 0 }
        .joinToString(", ") { Month.of(it + 1).getDisplayName(TextStyle.FULL_STANDALONE, locale) }
}

private val BAR_AREA_HEIGHT = 56.dp
private const val MIN_VISIBLE_FRACTION = 0.06f
