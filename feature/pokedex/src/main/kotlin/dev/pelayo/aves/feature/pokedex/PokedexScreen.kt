package dev.pelayo.aves.feature.pokedex

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pelayo.aves.core.data.PokedexZone
import dev.pelayo.aves.core.data.ZoneChoice
import dev.pelayo.aves.core.data.ZoneLevel
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
    onOpenAchievements: (() -> Unit)? = null,
    onOpenMap: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** "¿Que hay aqui ahora?". Null esconde el boton. */
    onOpenNearby: (() -> Unit)? = null,
) {
    val viewModel: PokedexViewModel = viewModel { viewModelFactory() }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    PokedexContent(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onSpeciesClick = onSpeciesClick,
        onOpenHistory = onOpenHistory,
        onZoneLevelChange = viewModel::onZoneLevelChange,
        onShowMissingChange = viewModel::onShowMissingChange,
        onRedetectZone = viewModel::redetectZone,
        onRetryChecklist = viewModel::retryChecklist,
        onOpenAchievements = onOpenAchievements,
        onOpenMap = onOpenMap,
        onOpenBrowser = viewModel::openZoneBrowser,
        onClearChosen = viewModel::clearChosenZone,
        onBrowseInto = viewModel::browseInto,
        onBrowseBack = viewModel::browseBack,
        onCloseBrowser = viewModel::closeZoneBrowser,
        onChooseZone = viewModel::chooseZone,
        onSortChange = viewModel::onSortChange,
        onSourceFilterChange = viewModel::onSourceFilterChange,
        onOpenNearby = onOpenNearby,
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
    onZoneLevelChange: (ZoneLevel) -> Unit = {},
    onShowMissingChange: (Boolean) -> Unit = {},
    onRedetectZone: () -> Unit = {},
    onRetryChecklist: () -> Unit = {},
    onOpenAchievements: (() -> Unit)? = null,
    onOpenMap: (() -> Unit)? = null,
    onOpenBrowser: () -> Unit = {},
    onClearChosen: () -> Unit = {},
    onBrowseInto: (ZoneChoice) -> Unit = {},
    onBrowseBack: () -> Unit = {},
    onCloseBrowser: () -> Unit = {},
    onChooseZone: (PokedexZone) -> Unit = {},
    onSortChange: (CollectionSort) -> Unit = {},
    onSourceFilterChange: (SourceFilter) -> Unit = {},
    onOpenNearby: (() -> Unit)? = null,
) {
    state.zoneBrowser?.let { browser ->
        ZoneBrowserDialog(
            browser = browser,
            onBrowseInto = onBrowseInto,
            onBack = onBrowseBack,
            onChoose = onChooseZone,
            onDismiss = onCloseBrowser,
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        ProgressHeader(
            progress = state.progress,
            zone = state.zone,
            onOpenHistory = onOpenHistory,
            onOpenAchievements = onOpenAchievements,
            onOpenMap = onOpenMap,
            onOpenNearby = onOpenNearby,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        ZoneFilterRow(
            state = state,
            onZoneLevelChange = onZoneLevelChange,
            onShowMissingChange = onShowMissingChange,
            onRedetectZone = onRedetectZone,
            onOpenBrowser = onOpenBrowser,
            onClearChosen = onClearChosen,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        ZoneStatusLine(
            state = state,
            onRetry = onRetryChecklist,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        // La busqueda queda fija fuera de la rejilla: si se va con el scroll,
        // deja de servir justo cuando mas falta hace, con la coleccion grande.
        // El texto vive aqui y no en el ViewModel: si el campo pintara lo que
        // devuelve el flujo (que llega un instante despues, tras cruzar la base
        // de datos), el cursor saltaria a la posicion anterior con cada letra.
        var queryText by rememberSaveable { mutableStateOf(state.query) }
        // El texto sobrevive a la muerte del proceso y el ViewModel no: sin
        // esto el campo decia "mirlo" y la lista salia entera, sin filtrar.
        LaunchedEffect(Unit) {
            if (queryText.isNotEmpty() && queryText != state.query) onQueryChange(queryText)
        }
        OutlinedTextField(
            value = queryText,
            onValueChange = { text ->
                queryText = text
                onQueryChange(text)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            singleLine = true,
            label = { Text(stringResource(R.string.pokedex_search_label)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (queryText.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            queryText = ""
                            onQueryChange("")
                        },
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.pokedex_clear_search))
                    }
                }
            },
        )

        if (state.hasAnyCollected) {
            SortAndSourceRow(
                sort = state.sort,
                filter = state.sourceFilter,
                onSortChange = onSortChange,
                onFilterChange = onSourceFilterChange,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        Spacer(Modifier.size(12.dp))

        when {
            // El vacio inicial solo cuando de verdad no hay nada que pintar: con
            // "ver las que faltan" la rejilla tiene contenido aunque la
            // coleccion este a cero, y eso invita mas que un parrafo.
            state.groups.isEmpty() && !state.hasAnyCollected && !state.isLoading &&
                state.catalogMatches.isEmpty() && state.query.isEmpty() -> EmptyCollection(
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

/**
 * Orden de la rejilla (un desplegable) y filtro por origen (chips): "que he
 * fotografiado", "lo ultimo que oi", "lo mas raro que tengo".
 */
@Composable
private fun SortAndSourceRow(
    sort: CollectionSort,
    filter: SourceFilter,
    onSortChange: (CollectionSort) -> Unit,
    onFilterChange: (SourceFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            FilterChip(
                selected = sort != CollectionSort.TAXONOMIC,
                onClick = { menuOpen = true },
                label = { Text(stringResource(sort.label())) },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                CollectionSort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(option.label())) },
                        onClick = {
                            menuOpen = false
                            onSortChange(option)
                        },
                    )
                }
            }
        }
        SourceFilter.entries.forEach { option ->
            FilterChip(
                selected = filter == option,
                onClick = { onFilterChange(option) },
                label = { Text(stringResource(option.label())) },
            )
        }
    }
}

private fun CollectionSort.label(): Int = when (this) {
    CollectionSort.TAXONOMIC -> R.string.pokedex_sort_taxonomic
    CollectionSort.RECENT -> R.string.pokedex_sort_recent
    CollectionSort.RARITY -> R.string.pokedex_sort_rarity
}

private fun SourceFilter.label(): Int = when (this) {
    SourceFilter.ALL -> R.string.pokedex_source_filter_all
    SourceFilter.SEEN -> R.string.pokedex_source_seen
    SourceFilter.PHOTOGRAPHED -> R.string.pokedex_source_photographed
    SourceFilter.HEARD -> R.string.pokedex_source_heard
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
                FamilyHeader(
                    family = if (group.family == FLAT_GROUP) {
                        stringResource(
                            if (state.sort == CollectionSort.RARITY) R.string.pokedex_group_by_rarity else R.string.pokedex_group_recent,
                        )
                    } else {
                        group.family
                    },
                    count = group.size,
                    collected = group.members.size.takeIf { group.missing.isNotEmpty() },
                )
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

            // Lo que hay en la zona y aun no se ha visto, dentro de su familia:
            // asi se ve de un vistazo que ramas del arbol estan a medias.
            items(
                items = group.missing,
                key = { "falta-${it.species.speciesId}" },
                contentType = { "falta" },
            ) { missing ->
                MissingSpeciesCard(
                    missing = missing,
                    onClick = { onSpeciesClick(missing.species.speciesId) },
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
                FamilyHeader(
                    family = stringResource(R.string.pokedex_catalog_header),
                    count = state.catalogMatches.size,
                )
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
        // Y solo si no se estan ensenando ya las que faltan de verdad: entonces
        // los huecos anonimos sobran.
        if (state.query.isEmpty() && remaining > 0 && !state.showMissing) {
            item(
                key = "cabecera-pendientes",
                span = { GridItemSpan(maxLineSpan) },
                contentType = "cabecera",
            ) {
                FamilyHeader(family = stringResource(R.string.pokedex_to_discover), count = remaining)
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

/** El nombre de una zona tal y como se le ensena al usuario. El mundo no trae nombre propio. */
@Composable
private fun zoneDisplayName(level: ZoneLevel, name: String): String =
    if (level == ZoneLevel.WORLD || name.isBlank()) stringResource(R.string.pokedex_zone_world) else name

/**
 * Por donde se filtra y si se ensenan las que faltan.
 *
 * Dos chips y no un dialogo de filtros: son las dos unicas decisiones, y las
 * dos se cambian a menudo (mirar la provincia, abrir al pais, volver). El menu
 * del primero lleva ademas la puerta a cualquier otra zona.
 */
@Composable
private fun ZoneFilterRow(
    state: PokedexUiState,
    onZoneLevelChange: (ZoneLevel) -> Unit,
    onShowMissingChange: (Boolean) -> Unit,
    onRedetectZone: () -> Unit,
    onOpenBrowser: () -> Unit,
    onClearChosen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            FilterChip(
                selected = state.zone.level != ZoneLevel.WORLD,
                onClick = { menuOpen = true },
                label = {
                    Text(
                        text = zoneDisplayName(state.zone.level, state.zone.name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = { Icon(Icons.Filled.Place, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = stringResource(R.string.pokedex_zone_change),
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                state.zoneOptions.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(zoneDisplayName(option.level, option.name))
                                Text(
                                    text = zoneLevelLabel(option.level, state.homeCountryCode),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            menuOpen = false
                            onZoneLevelChange(option.level)
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pokedex_zone_choose_other)) },
                    onClick = {
                        menuOpen = false
                        onOpenBrowser()
                    },
                )
                if (state.chosenZone != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.pokedex_zone_back_home)) },
                        onClick = {
                            menuOpen = false
                            onClearChosen()
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (state.isDetectingZone) R.string.pokedex_zone_detecting else R.string.pokedex_zone_redetect,
                            ),
                        )
                    },
                    enabled = !state.isDetectingZone,
                    onClick = {
                        menuOpen = false
                        onRedetectZone()
                    },
                )
            }
        }

        FilterChip(
            selected = state.showMissing,
            onClick = { onShowMissingChange(!state.showMissing) },
            label = { Text(stringResource(R.string.pokedex_show_missing)) },
        )
    }
}

/**
 * Como se llama cada nivel. En España, el nivel 1 de GADM es la comunidad
 * autonoma y asi hay que decirlo: "tu region" no le dice nada a nadie aqui.
 */
@Composable
internal fun zoneLevelLabel(level: ZoneLevel, countryCode: String?): String = stringResource(
    when (level) {
        ZoneLevel.PROVINCE -> R.string.pokedex_zone_level_province
        ZoneLevel.REGION -> if (countryCode == SPAIN) R.string.pokedex_zone_level_region_es else R.string.pokedex_zone_level_region
        ZoneLevel.COUNTRY -> R.string.pokedex_zone_level_country
        ZoneLevel.PLACE -> R.string.pokedex_zone_level_place
        ZoneLevel.WORLD -> R.string.pokedex_zone_level_world
    },
)

private const val SPAIN = "ES"

/**
 * Lo que el filtro esta haciendo y no se ve: que la lista se esta bajando, que
 * no se pudo, o que ha dejado fuera parte de la coleccion. Sin esta linea, el
 * ave que alguien se trajo de un viaje "desaparece" y parece un fallo.
 */
@Composable
private fun ZoneStatusLine(state: PokedexUiState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val zoneName = zoneDisplayName(state.zone.level, state.zone.name)
    when {
        state.zoneStatus == ZoneStatus.LOADING -> Text(
            text = stringResource(R.string.pokedex_zone_loading, zoneName),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(top = 4.dp),
        )

        state.zoneStatus == ZoneStatus.UNAVAILABLE -> Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.pokedex_zone_unavailable, zoneName),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text(stringResource(R.string.pokedex_zone_retry)) }
        }

        state.hiddenOutsideZone > 0 -> Text(
            text = pluralStringResource(
                R.plurals.pokedex_zone_hidden,
                state.hiddenOutsideZone,
                state.hiddenOutsideZone,
                zoneName,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ProgressHeader(
    progress: CollectionProgress,
    zone: PokedexZone,
    onOpenHistory: (() -> Unit)?,
    onOpenAchievements: (() -> Unit)? = null,
    onOpenMap: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onOpenNearby: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (zone.level == ZoneLevel.WORLD) {
                    progress.label
                } else {
                    stringResource(R.string.pokedex_progress_in_zone, progress.label, zone.name)
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                // Con un catalogo de miles de especies el porcentaje redondeado
                // se queda clavado en 0 %; un decimal deja ver que avanzas.
                text = stringResource(R.string.pokedex_percent, formatPercent(progress.fraction)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onOpenNearby != null) {
                IconButton(onClick = onOpenNearby) {
                    Icon(Icons.Filled.NearMe, contentDescription = stringResource(R.string.nearby_open))
                }
            }
            if (onOpenMap != null) {
                IconButton(onClick = onOpenMap) {
                    Icon(Icons.Filled.Map, contentDescription = stringResource(R.string.pokedex_map_open))
                }
            }
            if (onOpenAchievements != null) {
                IconButton(onClick = onOpenAchievements) {
                    Icon(Icons.Filled.EmojiEvents, contentDescription = stringResource(R.string.pokedex_achievements_open))
                }
            }
            if (onOpenHistory != null) {
                IconButton(onClick = onOpenHistory) {
                    Icon(Icons.Filled.History, contentDescription = stringResource(R.string.pokedex_history))
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
            Stat(value = stats.families, label = stringResource(R.string.pokedex_stat_families))
            Stat(value = stats.newThisMonth, label = stringResource(R.string.pokedex_stat_new_this_month))
            Stat(value = stats.notable, label = stringResource(R.string.pokedex_stat_notable))
        }
        stats.latestLifer?.let { lifer ->
            Text(
                text = stringResource(R.string.pokedex_latest_lifer, lifer.species.commonName),
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

/**
 * @param collected cuantas de las [count] se tienen. Null cuando no se estan
 *   ensenando las que faltan y el recuento a secas ya lo dice todo.
 */
@Composable
private fun FamilyHeader(
    family: String,
    count: Int,
    modifier: Modifier = Modifier,
    collected: Int? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = family,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = if (collected != null) {
                stringResource(R.string.pokedex_family_progress, collected, count)
            } else {
                pluralStringResource(R.plurals.pokedex_species_count, count, formatThousands(count))
            },
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
            text = stringResource(R.string.pokedex_empty_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.pokedex_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.pokedex_empty_hint),
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
            text = stringResource(R.string.pokedex_no_results, query),
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
