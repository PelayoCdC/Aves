package dev.pelayo.aves.feature.pokedex

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pelayo.aves.core.data.PokedexZone
import dev.pelayo.aves.core.data.ZoneChoice

/**
 * Elegir OTRA zona: las comunidades de tu pais, las provincias de una de ellas,
 * o un lugar destacado.
 *
 * Dos niveles y no un buscador: son diecisiete comunidades y medio centenar de
 * provincias, que se recorren de un vistazo; escribir "Cáceres" con el movil en
 * el campo es mas lento que dos toques. Cada comunidad se puede elegir entera
 * (primera fila al entrar) o bajar a una provincia.
 */
@Composable
internal fun ZoneBrowserDialog(
    browser: ZoneBrowserState,
    onBrowseInto: (ZoneChoice) -> Unit,
    onBack: () -> Unit,
    onChoose: (PokedexZone) -> Unit,
    onDismiss: () -> Unit,
) {
    val parent = browser.parent
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (parent != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.pokedex_zone_browser_back),
                        )
                    }
                }
                Text(
                    text = parent?.zone?.name ?: stringResource(R.string.pokedex_zone_browser_title),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                if (parent != null) {
                    item(key = "toda") {
                        ChoiceRow(
                            icon = Icons.Filled.Map,
                            title = stringResource(R.string.pokedex_zone_browser_all, parent.zone.name),
                            subtitle = parent.kind,
                            onClick = { onChoose(parent.zone) },
                        )
                    }
                } else {
                    item(key = "cabecera-regiones") {
                        SectionLabel(stringResource(R.string.pokedex_zone_browser_regions, browser.countryName))
                    }
                }

                when {
                    browser.isLoading -> item(key = "cargando") {
                        Row(
                            modifier = Modifier.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.pokedex_zone_browser_loading),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    browser.failed -> item(key = "fallo") {
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(
                                text = stringResource(R.string.pokedex_zone_browser_failed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = { if (parent != null) onBrowseInto(parent) else onBack() }) {
                                Text(stringResource(R.string.pokedex_zone_browser_retry))
                            }
                        }
                    }

                    else -> items(browser.choices, key = { it.zone.id ?: it.zone.name }) { choice ->
                        if (parent == null) {
                            // Una comunidad: se entra a ver sus provincias, y dentro
                            // esta la opcion de quedarsela entera.
                            ChoiceRow(
                                icon = Icons.Filled.Map,
                                title = choice.zone.name,
                                subtitle = choice.kind,
                                trailing = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                onClick = { onBrowseInto(choice) },
                            )
                        } else {
                            ChoiceRow(
                                icon = Icons.Filled.Place,
                                title = choice.zone.name,
                                subtitle = choice.kind,
                                onClick = { onChoose(choice.zone) },
                            )
                        }
                    }
                }

                if (parent == null && browser.places.isNotEmpty()) {
                    item(key = "cabecera-lugares") {
                        Spacer(Modifier.size(8.dp))
                        SectionLabel(stringResource(R.string.pokedex_zone_browser_places))
                    }
                    items(browser.places, key = { "lugar-${it.id}" }) { place ->
                        ChoiceRow(
                            icon = Icons.Filled.Landscape,
                            title = place.name,
                            subtitle = null,
                            onClick = { onChoose(place) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.pokedex_zone_browser_close)) }
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun ChoiceRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    trailing: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Icon(trailing, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
