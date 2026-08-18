package dev.pelayo.aves.feature.pokedex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import dev.pelayo.aves.core.media.ClipPlayerState
import dev.pelayo.aves.core.media.hasPlayableClip
import dev.pelayo.aves.core.media.rememberClipPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pelayo.aves.core.designsystem.theme.AvesTheme
import dev.pelayo.aves.core.model.RarityTier

/**
 * La coleccion.
 *
 * @param viewModelFactory lo construye :app, que es quien tiene los
 *   repositorios. Sigue el patron de `IdentifyScreen`: la feature no sabe de
 *   donde salen sus dependencias.
 * @param onSpeciesClick navegar es cosa de :app; aqui solo se avisa de que se
 *   ha tocado una especie.
 */
@Composable
fun PokedexScreen(
    viewModelFactory: () -> PokedexViewModel,
    onSpeciesClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val viewModel: PokedexViewModel = viewModel { viewModelFactory() }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    PokedexContent(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onSpeciesClick = onSpeciesClick,
        modifier = modifier,
    )
}

/**
 * La pantalla sin ViewModel, para poder verla en @Preview con datos inventados.
 */
@Composable
internal fun PokedexContent(
    state: PokedexUiState,
    onQueryChange: (String) -> Unit,
    onSpeciesClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ProgressHeader(
            progress = state.progress,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        // La busqueda queda fija fuera de la rejilla: si se va con el scroll,
        // deja de servir justo cuando mas falta hace, con la coleccion grande.
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            singleLine = true,
            label = { Text("Buscar en tu colección") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Borrar la búsqueda")
                    }
                }
            },
        )

        Spacer(Modifier.size(12.dp))

        when {
            !state.hasAnyCollected && !state.isLoading -> EmptyCollection(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
            )

            state.groups.isEmpty() && state.query.isNotEmpty() -> NoSearchResults(
                query = state.query,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
            )

            else -> CollectionGrid(
                state = state,
                onSpeciesClick = onSpeciesClick,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun CollectionGrid(
    state: PokedexUiState,
    onSpeciesClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        // Adaptive y no un numero fijo de columnas: la misma rejilla tiene que
        // aguantar un movil en vertical y una tablet sin dos layouts.
        columns = GridCells.Adaptive(minSize = 150.dp),
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.groups.forEach { group ->
            // TODO: pasar a `stickyHeader` cuando se confirme que la version de
            // foundation del BOM lo expone para LazyVerticalGrid; de momento la
            // cabecera scrollea con su bloque.
            item(
                key = "familia-${group.family}",
                span = { GridItemSpan(maxLineSpan) },
                contentType = "cabecera",
            ) {
                FamilyHeader(family = group.family, count = group.members.size)
            }

            items(
                items = group.members,
                key = { it.species.speciesId },
                contentType = { "especie" },
            ) { entry ->
                SpeciesCard(
                    entry = entry,
                    onClick = { onSpeciesClick(entry.species.speciesId) },
                )
            }
        }

        // Los huecos solo tienen sentido viendo la coleccion entera: al filtrar,
        // "lo que falta" no significa nada.
        val remaining = state.progress.remaining
        if (state.query.isEmpty() && remaining > 0) {
            item(
                key = "cabecera-pendientes",
                span = { GridItemSpan(maxLineSpan) },
                contentType = "cabecera",
            ) {
                FamilyHeader(family = "Por descubrir", count = remaining)
            }

            // Se pinta un punado, no las 6.000 que faltan: el objetivo es que se
            // vea el hueco, no simular un catalogo que aun no se conoce.
            items(
                count = minOf(remaining, LOCKED_SLOTS),
                key = { index -> "hueco-$index" },
                contentType = { "hueco" },
            ) {
                LockedSpeciesCard()
            }
        }
    }
}

@Composable
private fun ProgressHeader(progress: CollectionProgress, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = progress.label,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                // Con un catalogo de miles de especies el porcentaje redondeado
                // se queda clavado en 0 %; un decimal deja ver que avanzas.
                text = "${formatPercent(progress.fraction)} %",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.size(8.dp))

        LinearProgressIndicator(
            progress = { progress.fraction },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FamilyHeader(family: String, count: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = family,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = if (count == 1) "1 especie" else "${formatThousands(count)} especies",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * El vacio inicial es la primera pantalla que ve casi todo el mundo, asi que no
 * puede decir "sin datos": tiene que decir que hacer a continuacion.
 */
@Composable
private fun EmptyCollection(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Tu colección está vacía",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Ahí fuera hay miles de especies cantando. Abre la pestaña de " +
                "escucha, deja el micrófono abierto un rato y la primera que " +
                "reconozcas ocupará este hueco.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = "El amanecer es el mejor momento.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NoSearchResults(query: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = "Ninguna especie de tu colección coincide con \"$query\".",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Cuantos huecos se dibujan como cebo. Mas no aporta y alarga el scroll. */
private const val LOCKED_SLOTS = 6

// --- Previews -------------------------------------------------------------

private fun previewState(query: String = "") = PokedexUiState(
    isLoading = false,
    query = query,
    progress = CollectionProgress(collected = 37, total = 6_419),
    groups = groupByFamily(
        filterCollection(
            listOf(
                previewEntry(
                    id = "erirub",
                    common = "Petirrojo europeo",
                    scientific = "Erithacus rubecula",
                    family = "Muscicapidae",
                    tier = RarityTier.VERY_COMMON,
                    sightings = 12,
                ),
                previewEntry(
                    id = "turmer",
                    common = "Mirlo común",
                    scientific = "Turdus merula",
                    family = "Turdidae",
                    tier = RarityTier.VERY_COMMON,
                    sightings = 8,
                ),
                previewEntry(
                    id = "turphi",
                    common = "Zorzal común",
                    scientific = "Turdus philomelos",
                    family = "Turdidae",
                    tier = RarityTier.COMMON,
                    sightings = 2,
                ),
                previewEntry(
                    id = "parmaj",
                    common = "Carbonero común",
                    scientific = "Parus major",
                    family = "Paridae",
                    tier = RarityTier.COMMON,
                    sightings = 5,
                ),
                previewEntry(
                    id = "cyacae",
                    common = "Herrerillo común",
                    scientific = "Cyanistes caeruleus",
                    family = "Paridae",
                    tier = RarityTier.UNCOMMON,
                ),
                previewEntry(
                    id = "strala",
                    common = "Cárabo común",
                    scientific = "Strix aluco",
                    family = "Strigidae",
                    tier = RarityTier.EXCEPTIONAL,
                    order = "Strigiformes",
                ),
            ),
            query,
        ),
    ),
    hasAnyCollected = true,
)

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun PokedexScreenPreview() {
    AvesTheme {
        PokedexContent(
            state = previewState(),
            onQueryChange = {},
            onSpeciesClick = {},
        )
    }
}

@Preview(showBackground = true, heightDp = 700)
@Composable
private fun PokedexEmptyPreview() {
    AvesTheme {
        PokedexContent(
            state = PokedexUiState(
                isLoading = false,
                progress = CollectionProgress(collected = 0, total = 6_419),
            ),
            onQueryChange = {},
            onSpeciesClick = {},
        )
    }
}

@Preview(showBackground = true, heightDp = 700)
@Composable
private fun PokedexSearchPreview() {
    AvesTheme {
        PokedexContent(
            state = previewState(query = "turdus"),
            onQueryChange = {},
            onSpeciesClick = {},
        )
    }
}
