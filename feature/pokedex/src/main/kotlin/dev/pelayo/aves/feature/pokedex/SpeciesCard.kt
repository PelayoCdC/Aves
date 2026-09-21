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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pelayo.aves.core.designsystem.icons.AvesIcons
import dev.pelayo.aves.core.designsystem.theme.AvesTheme
import dev.pelayo.aves.core.designsystem.theme.RarityColors
import dev.pelayo.aves.core.designsystem.theme.ScientificNameStyle
import dev.pelayo.aves.core.model.ObservationSource
import dev.pelayo.aves.core.model.RarityTier
import dev.pelayo.aves.core.model.Species

/**
 * Altura comun de tarjeta capturada y hueco: si bailan, la rejilla se lee mal.
 * Es lo que mide una tarjeta con el nombre en dos lineas y la fila de iconos.
 */
private val CardMinHeight = 152.dp

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

            Spacer(Modifier.size(6.dp))

            SourceIcons(sources = entry.sources)
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

            // Las tres apagadas: es la misma tarjeta que cuando se consiga, con
            // las casillas por rellenar.
            Spacer(Modifier.size(8.dp))
            SourceIcons(sources = CaptureSources.None)
        }
    }
}

/**
 * Como se ha conseguido la especie: ojo (vista), camara (fotografiada) y clave
 * de sol (oida). Las tres SIEMPRE, en el mismo orden y en el mismo sitio; la que
 * no se tiene se apaga en vez de desaparecer.
 *
 * Que el hueco se vea es el sentido del dibujo: una fila con solo los iconos
 * conseguidos diria lo mismo que el numero de avistamientos, y una que baila
 * segun la especie no se puede leer de un vistazo al recorrer la rejilla.
 *
 * Para el lector de pantalla los tres iconos son una sola frase ("Vista y oida"):
 * tres "imagen" seguidas por tarjeta, en una rejilla de cientos, serian ruido.
 *
 * @param showCounts en la ficha hay sitio para decir cuantas de cada; en la
 *   tarjeta no.
 */
@Composable
internal fun SourceIcons(
    sources: CaptureSources,
    modifier: Modifier = Modifier,
    iconSize: Dp = 18.dp,
    showCounts: Boolean = false,
) {
    val description = sourcesDescription(sources)
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(if (showCounts) 16.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (source in SOURCE_ORDER) {
            val count = sources.count(source)
            val tint = if (count > 0) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = INACTIVE_SOURCE_ALPHA)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = sourceIcon(source),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(iconSize),
                )
                if (showCounts) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = tint,
                    )
                }
            }
        }
    }
}

/** El orden fijo de los tres iconos: el mismo en la rejilla, la ficha y los filtros. */
internal val SOURCE_ORDER = listOf(ObservationSource.MANUAL, ObservationSource.IMAGE, ObservationSource.AUDIO)

/**
 * Lo apagado que queda un icono que no se tiene. Se tiene que ver que ESTA (es un
 * hueco por rellenar, no un adorno) sin competir con los encendidos.
 */
private const val INACTIVE_SOURCE_ALPHA = 0.28f

internal fun sourceIcon(source: ObservationSource): ImageVector = when (source) {
    ObservationSource.MANUAL -> AvesIcons.Seen
    ObservationSource.IMAGE -> AvesIcons.Photographed
    ObservationSource.AUDIO -> AvesIcons.Heard
}

/** "Vista, fotografiada y oida", o que todavia nada. */
@Composable
private fun sourcesDescription(sources: CaptureSources): String {
    val achieved = buildList {
        if (sources.seen > 0) add(stringResource(R.string.pokedex_source_seen))
        if (sources.photographed > 0) add(stringResource(R.string.pokedex_source_photographed))
        if (sources.heard > 0) add(stringResource(R.string.pokedex_source_heard))
    }.mapIndexed { index, word -> if (index == 0) word else word.lowercase() }
    return when (achieved.size) {
        0 -> stringResource(R.string.pokedex_sources_none)
        1 -> achieved[0]
        else -> stringResource(
            R.string.pokedex_sources_joined,
            achieved.dropLast(1).joinToString(", "),
            achieved.last(),
        )
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
    source: ObservationSource = ObservationSource.AUDIO,
) = Sighting(
    id = id,
    speciesId = speciesId,
    timestamp = timestamp,
    rarity = tier,
    source = source,
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

@Preview(showBackground = true, widthDp = 200, name = "Vista, fotografiada y oida")
@Composable
private fun SpeciesCardAllSourcesPreview() {
    AvesTheme {
        SpeciesCard(
            entry = CollectedSpecies(
                species = previewSpecies("turmer", "Mirlo común", "Turdus merula", "Turdidae"),
                sightings = SOURCE_ORDER.mapIndexed { index, source ->
                    previewSighting(
                        id = index.toLong(),
                        speciesId = "turmer",
                        timestamp = 1_755_000_000_000L,
                        tier = RarityTier.VERY_COMMON,
                        source = source,
                    )
                },
            ),
            onClick = {},
            modifier = Modifier.padding(8.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 200, name = "Sin ver")
@Composable
private fun MissingSpeciesCardPreview() {
    AvesTheme {
        MissingSpeciesCard(
            missing = MissingSpecies(
                species = previewSpecies("parmaj", "Carbonero común", "Parus major", "Paridae"),
                share = 0.02f,
            ),
            onClick = {},
            modifier = Modifier.padding(8.dp),
        )
    }
}
