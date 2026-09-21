package dev.pelayo.aves.feature.pokedex

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.pelayo.aves.core.designsystem.theme.AvesTheme
import dev.pelayo.aves.core.designsystem.theme.RarityColors
import dev.pelayo.aves.core.designsystem.theme.ScientificNameStyle
import dev.pelayo.aves.core.model.ObservationSource
import dev.pelayo.aves.core.model.RarityTier
import dev.pelayo.aves.core.model.Species

/** Altura comun de tarjeta capturada y hueco: si bailan, la rejilla se lee mal. */
private val CardMinHeight = 128.dp

/**
 * Una especie capturada dentro de la rejilla.
 *
 * El color de rareza va en un punto y en el nombre del tier, no en el fondo de
 * la tarjeta: tenir la tarjeta entera de dorado destroza el contraste del texto
 * en tema claro, y esta pantalla se usa de noche.
 */
@Composable
fun SpeciesCard(
    entry: CollectedSpecies,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = rarityColor(entry.rarity)

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = CardMinHeight),
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(tint, CircleShape),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = rarityText(entry.rarity),
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.size(8.dp))

            Text(
                text = entry.species.commonName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.species.scientificName,
                style = ScientificNameStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.size(8.dp))

            Text(
                text = pluralStringResource(R.plurals.pokedex_sightings_count, entry.count, entry.count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * El hueco por rellenar.
 *
 * Es la mitad del sentido de esta pantalla: una lista de lo que ya tienes no
 * invita a nada, un hueco si. Va sin `onClick` a proposito porque no hay nada
 * que abrir todavia y un toque que no hace nada frustra mas que un hueco mudo.
 */
@Composable
fun LockedSpeciesCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = CardMinHeight)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            ),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = CardMinHeight),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.pokedex_locked),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/**
 * Una especie de la zona que todavia no se ha visto.
 *
 * A diferencia de [LockedSpeciesCard] tiene nombre y se puede tocar: es un
 * objetivo concreto, no un hueco anonimo. Va sin relleno y en gris para que la
 * rejilla se lea de un vistazo (lo que tienes pesa, lo que falta es contorno), y
 * dice lo frecuente que es AQUI: "comun" es una invitacion a salir manana;
 * "rara" es un premio.
 */
@Composable
fun MissingSpeciesCard(
    missing: MissingSpecies,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = CardMinHeight)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            ),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
        ) {
            Text(
                text = missing.rarityHere?.let { stringResource(R.string.pokedex_missing_rarity_here, rarityText(it)) }
                    ?: stringResource(R.string.pokedex_missing_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.size(8.dp))

            Text(
                text = missing.species.commonName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = missing.species.scientificName,
                style = ScientificNameStyle,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Sin datos no hay color de rareza: un gris neutro, que es lo que significa. */
@Composable
internal fun rarityColor(tier: RarityTier): Color =
    if (tier.isKnown) {
        RarityColors.getOrElse(tier.level) { RarityColors.last() }
    } else {
        MaterialTheme.colorScheme.outline
    }

/** Nombre visible del tier, desde recursos. */
@Composable
internal fun rarityText(tier: RarityTier): String = stringResource(
    when (tier) {
        RarityTier.VERY_COMMON -> R.string.pokedex_rarity_very_common
        RarityTier.COMMON -> R.string.pokedex_rarity_common
        RarityTier.UNCOMMON -> R.string.pokedex_rarity_uncommon
        RarityTier.RARE -> R.string.pokedex_rarity_rare
        RarityTier.EXCEPTIONAL -> R.string.pokedex_rarity_exceptional
        RarityTier.UNKNOWN -> R.string.pokedex_rarity_unknown
    },
)

// --- Previews -------------------------------------------------------------

internal fun previewSpecies(
    id: String,
    common: String,
    scientific: String,
    family: String,
    order: String = "Passeriformes",
) = Species(
    speciesId = id,
    scientificName = scientific,
    genus = scientific.substringBefore(' '),
    family = family,
    order = order,
    commonName = common,
)

internal fun previewSighting(
    id: Long,
    speciesId: String,
    timestamp: Long,
    tier: RarityTier,
    mediaPath: String? = null,
) = Sighting(
    id = id,
    speciesId = speciesId,
    timestamp = timestamp,
    rarity = tier,
    source = ObservationSource.AUDIO,
    confidence = 0.87f,
    mediaPath = mediaPath,
)

internal fun previewEntry(
    id: String,
    common: String,
    scientific: String,
    family: String,
    tier: RarityTier,
    sightings: Int = 1,
    order: String = "Passeriformes",
) = CollectedSpecies(
    species = previewSpecies(id, common, scientific, family, order),
    sightings = List(sightings) { index ->
        previewSighting(
            id = index.toLong(),
            speciesId = id,
            timestamp = 1_755_000_000_000L - index * 86_400_000L,
            tier = tier,
        )
    },
)

@Preview(showBackground = true, widthDp = 200)
@Composable
private fun SpeciesCardPreview() {
    AvesTheme {
        SpeciesCard(
            entry = previewEntry(
                id = "erirub",
                common = "Petirrojo europeo",
                scientific = "Erithacus rubecula",
                family = "Muscicapidae",
                tier = RarityTier.COMMON,
                sightings = 4,
            ),
            onClick = {},
            modifier = Modifier.padding(8.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 200)
@Composable
private fun SpeciesCardRarePreview() {
    AvesTheme {
        SpeciesCard(
            entry = previewEntry(
                id = "strala",
                common = "Cárabo común",
                scientific = "Strix aluco",
                family = "Strigidae",
                tier = RarityTier.EXCEPTIONAL,
                order = "Strigiformes",
            ),
            onClick = {},
            modifier = Modifier.padding(8.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 200)
@Composable
private fun LockedSpeciesCardPreview() {
    AvesTheme {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LockedSpeciesCard(Modifier.padding(8.dp))
        }
    }
}
