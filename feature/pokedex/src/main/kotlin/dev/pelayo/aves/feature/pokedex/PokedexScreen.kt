package dev.pelayo.aves.feature.pokedex

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pelayo.aves.core.designsystem.theme.AvesTheme
import dev.pelayo.aves.core.designsystem.theme.ScientificNameStyle
import dev.pelayo.aves.core.model.RarityTier
import dev.pelayo.aves.core.model.Species

/**
 * La coleccion.
 *
 * @param viewModelFactory lo construye :app, que es quien tiene los
 *   repositorios. Sigue el patron de `IdentifyScreen`: la feature no sabe de
 *   donde salen sus dependencias.
 * @param onSpeciesClick navegar es cosa de :app; aqui solo se avisa de que se
 *   ha tocado una especie.
 * @param onOpenHistory abre el historial de escuchas.
 */
@Composable
fun PokedexScreen(
    viewModelFactory: () -> PokedexViewModel,
    onSpeciesClick: (String) -> Unit = {},
    onOpenHistory: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val viewModel: PokedexViewModel = viewModel { viewModelFactory() }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    PokedexContent(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onSpeciesClick = onSpeciesClick,
        onOpenHistory = onOpenHistory,
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
    onOpenHistory: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ProgressHeader(
            progress = state.progress,
            onOpenHistory = onOpenHistory,
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
            label = { Text("Buscar en tu colección y en el catálogo") },
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
            !state.hasAnyCollected && !state.isLoading && state.catalogMatches.isEmpty() -> EmptyCollection(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
            )

            state.groups.isEmpty() && state.query.isNotEmpty() && state.catalogMatches.isEmpty() -> NoSearchResults(
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
        // Las estadisticas solo con la coleccion entera a la vista: al filtrar,
        // hablarian de algo que no se esta viendo.
        val stats = state.stats
        if (state.query.isEmpty() && stats != null && stats.species > 0) {
            item(key = "stats", span = { GridItemSpan(maxLineSpan) }, contentType = "stats") {
                StatsRow(stats)
            }
        }

        state.groups.forEach { group ->
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

        // Lo que casa con la busqueda pero aun no esta en la coleccion: la guia
        // de campo. Se puede abrir la ficha y anotar la especie a mano.
        if (state.catalogMatches.isNotEmpty()) {
            item(
                key = "cabecera-catalogo",
                span = { GridItemSpan(maxLineSpan) },
                contentType = "cabecera",
            ) {
                FamilyHeader(family = "En el catálogo, aún sin ver", count = state.catalogMatches.size)
            }
            items(
                items = state.catalogMatches,
                key = { "catalogo-${it.speciesId}" },
                contentType = { "catalogo" },
            ) { species ->
                CatalogSpeciesCard(species = species, onClick = { onSpeciesClick(species.speciesId) })
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

            // Se pinta un punado, no las miles que faltan: el objetivo es que se
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
private fun ProgressHeader(
    progress: CollectionProgress,
    onOpenHistory: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = progress.label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                // Con un catalogo de miles de especies el porcentaje redondeado
                // se queda clavado en 0 %; un decimal deja ver que avanzas.
                text = "${formatPercent(progress.fraction)} %",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onOpenHistory != null) {
                IconButton(onClick = onOpenHistory) {
                    Icon(Icons.Filled.History, contentDescription = "Historial de escuchas")
                }
            }
        }

        Spacer(Modifier.size(8.dp))

        LinearProgressIndicator(
            progress = { progress.fraction },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Los numeros de coleccionista: familias, nuevas este mes, notables, la ultima primera. */
@Composable
private fun StatsRow(stats: CollectionStats, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Stat(value = stats.families, label = "familias")
            Stat(value = stats.newThisMonth, label = "nuevas este mes")
            Stat(value = stats.notable, label = "notables")
        }
        stats.latestLifer?.let { lifer ->
            Text(
                text = "Última nueva: ${lifer.species.commonName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).padding(bottom = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Stat(value: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = formatThousands(value), style = MaterialTheme.typography.titleLarge)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** Una especie del catalogo que todavia no esta en la coleccion. */
@Composable
private fun CatalogSpeciesCard(species: Species, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = species.commonName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = species.scientificName,
                style = ScientificNameStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = species.family,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
            text = "Nada en tu colección ni en el catálogo coincide con \"$query\".",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Cuantos huecos se dibujan como cebo. Mas no aporta y alarga el scroll. */
private const val LOCKED_SLOTS = 6

// --- Previews -------------------------------------------------------------

private fun previewState(query: String = ""): PokedexUiState {
    val collected = filterCollection(
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
    )
    return PokedexUiState(
        isLoading = false,
        query = query,
        progress = CollectionProgress(collected = 37, total = 6_419),
        groups = groupByFamily(collected),
        hasAnyCollected = true,
        stats = computeStats(collected, 1_755_000_000_000L),
    )
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun PokedexScreenPreview() {
    AvesTheme {
        PokedexContent(
            state = previewState(),
            onQueryChange = {},
            onSpeciesClick = {},
            onOpenHistory = {},
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
