package dev.pelayo.aves.feature.pokedex

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.pelayo.aves.core.designsystem.theme.ScientificNameStyle
import dev.pelayo.aves.core.media.ClipPlayIcon
import dev.pelayo.aves.core.media.ClipPlayerState

/**
 * "Se confunde con...": las especies que el modelo propuso como alternativa en
 * tus propias detecciones de esta, o al reves. Solo aparece si hay datos
 * propios; no es una lista generica de parecidas, es la de TU oido y TU movil.
 *
 * Si tienes un recorte de cada una, se oyen desde aqui, una tras otra, que es
 * la forma de aprender a distinguirlas.
 */
@Composable
internal fun LookalikesCard(
    lookalikes: List<Lookalike>,
    clipPlayer: ClipPlayerState,
    onOpenSpecies: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(text = stringResource(R.string.pokedex_lookalikes_title), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(R.string.pokedex_lookalikes_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            lookalikes.forEach { lookalike ->
                LookalikeRow(lookalike, clipPlayer, onClick = { onOpenSpecies(lookalike.species.speciesId) })
            }
        }
    }
}

@Composable
private fun LookalikeRow(lookalike: Lookalike, clipPlayer: ClipPlayerState, onClick: () -> Unit) {
    val species = lookalike.species
    // Clave propia para el reproductor, que no choque con la de los avistamientos.
    val key = remember(species.speciesId) { LOOKALIKE_KEY_BASE - species.speciesId.hashCode().toLong() }
    val canPlay = remember(clipPlayer, lookalike.clipPath) { clipPlayer.canPlay(lookalike.clipPath) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            // La foto de Wikipedia solo si ya se bajo: no se piden fichas de
            // otras especies solo por pintar esta lista.
            species.imageUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(text = species.commonName, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = species.scientificName,
                style = ScientificNameStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = pluralStringResource(R.plurals.pokedex_lookalikes_times, lookalike.times, lookalike.times),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (canPlay) {
            val playing = clipPlayer.isPlaying(key)
            IconButton(onClick = { clipPlayer.toggle(key, lookalike.clipPath) }) {
                ClipPlayIcon(
                    isPlaying = playing,
                    contentDescription = stringResource(
                        if (playing) R.string.pokedex_lookalikes_stop else R.string.pokedex_lookalikes_play,
                        species.commonName,
                    ),
                )
            }
        }
    }
}

/** Los avistamientos usan su id (positivo); las parecidas, claves negativas. */
private const val LOOKALIKE_KEY_BASE = -1_000_000_000L
