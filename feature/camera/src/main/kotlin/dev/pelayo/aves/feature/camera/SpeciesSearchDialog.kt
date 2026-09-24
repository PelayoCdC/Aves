package dev.pelayo.aves.feature.camera

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.pelayo.aves.core.designsystem.theme.ScientificNameStyle
import dev.pelayo.aves.core.model.Species
import kotlinx.coroutines.flow.Flow

/**
 * "Se cual es": buscar la especie en el catalogo cuando ninguno de los
 * candidatos del modelo convence, o cuando no ha reconocido nada.
 *
 * El modelo de imagen ve 965 especies y se equivoca; la persona que ha visto al
 * ave a veces no. Elegir una de aqui sigue el mismo camino que un candidato:
 * abre la ficha para comprobarla y solo se anota al confirmar.
 */
@Composable
internal fun SpeciesSearchDialog(
    search: (String) -> Flow<List<Species>>,
    onPick: (Species) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // El flujo se rehace con cada texto: es una consulta a Room, que ya sabe
    // no buscar con menos de dos letras.
    val results by remember(query) { search(query) }.collectAsState(initial = emptyList())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.camera_search_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.camera_search_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    if (results.isEmpty() && query.length >= 2) {
                        item(key = "vacio") {
                            Text(
                                text = stringResource(R.string.camera_search_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                    items(results, key = { it.speciesId }) { species ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(species) }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(text = species.commonName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = species.scientificName,
                                style = ScientificNameStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.camera_cancel)) } },
    )
    // Sin esto el linter se queja de un `size` sin usar en algun preview; es inocuo.
    Modifier.size(0.dp)
}
