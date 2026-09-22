package dev.pelayo.aves.core.designsystem.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.pelayo.aves.core.designsystem.R
import kotlinx.coroutines.launch

/** Un lugar elegido por el usuario para una foto o una grabacion que no se hizo ahora y aqui. */
data class PickedPlace(
    val latitude: Double,
    val longitude: Double,
    /** Como ensenarlo: "Doñana", "Mi ubicacion actual", "43,36, -5,85". */
    val name: String,
)

/**
 * Lo que el selector necesita del resto de la app, que es quien sabe de GPS y de
 * geocodificadores.
 *
 * @param currentPlace la ubicacion actual, o null si no hay permiso, esta apagada
 *   en ajustes o no se consiguio.
 * @param search lugares que casan con un texto. Vacio si no hay nada o no hay
 *   geocodificador en el dispositivo.
 * @param recent los ultimos lugares elegidos: quien sube diez fotos del mismo
 *   viaje no quiere buscar "Monfragüe" diez veces.
 */
class PlacePickerActions(
    val currentPlace: suspend () -> PickedPlace?,
    val search: suspend (String) -> List<PickedPlace>,
    val recent: () -> List<PickedPlace> = { emptyList() },
    val remember: (PickedPlace) -> Unit = {},
)

/** El sitio elegido, como un chip que abre el selector. */
@Composable
fun PlaceChip(
    place: PickedPlace?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AssistChip(
        onClick = onClick,
        modifier = modifier,
        label = {
            Text(
                text = place?.name ?: stringResource(R.string.place_none_chip),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = if (place != null) Icons.Filled.Place else Icons.Filled.LocationOff,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

/**
 * "¿Donde fue?": ubicacion actual, un lugar buscado por nombre, unas coordenadas
 * o ninguna.
 *
 * Existe porque una foto de la galeria o una grabacion que te mandan NO se hizo
 * donde estas ahora. Sin decir donde, el modelo decide a ciegas entre las aves de
 * todo el planeta; diciendolo, descarta de entrada las que alli no hay.
 *
 * @param onPick el lugar elegido, o null para "sin ubicacion".
 */
@Composable
fun PlacePickerDialog(
    actions: PlacePickerActions,
    onPick: (PickedPlace?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PickedPlace>?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    var latitudeText by remember { mutableStateOf("") }
    var longitudeText by remember { mutableStateOf("") }
    var showCoordinates by remember { mutableStateOf(false) }
    val recent = remember { actions.recent() }

    fun pick(place: PickedPlace?) {
        if (place != null) actions.remember(place)
        onPick(place)
    }

    fun useCurrent() {
        isBusy = true
        message = null
        scope.launch {
            val place = runCatching { actions.currentPlace() }.getOrNull()
            isBusy = false
            if (place != null) pick(place) else message = R.string.place_current_failed
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) useCurrent() else message = R.string.place_current_denied
    }

    fun search() {
        val text = query.trim()
        if (text.length < 2) return
        isBusy = true
        message = null
        scope.launch {
            val found = runCatching { actions.search(text) }.getOrDefault(emptyList())
            isBusy = false
            results = found
            if (found.isEmpty()) message = R.string.place_search_empty
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.place_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.place_why),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(4.dp))

                PlaceRow(
                    icon = Icons.Filled.MyLocation,
                    text = stringResource(R.string.place_current),
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) useCurrent() else permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    },
                )
                for (place in recent) {
                    PlaceRow(icon = Icons.Filled.Place, text = place.name, onClick = { pick(place) })
                }
                PlaceRow(
                    icon = Icons.Filled.LocationOff,
                    text = stringResource(R.string.place_none),
                    onClick = { pick(null) },
                )

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.place_search_label)) },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = ::search) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.place_search_action))
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (isBusy) {
                    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.place_busy), style = MaterialTheme.typography.bodySmall)
                    }
                }
                message?.let {
                    Text(
                        text = stringResource(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                results?.forEach { place ->
                    PlaceRow(icon = Icons.Filled.Place, text = place.name, onClick = { pick(place) })
                }

                // La salida para quien no tiene geocodificador (moviles sin
                // servicios de Google) o sabe exactamente donde estuvo.
                TextButton(onClick = { showCoordinates = !showCoordinates }) {
                    Text(stringResource(R.string.place_coordinates_toggle))
                }
                if (showCoordinates) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = latitudeText,
                            onValueChange = { latitudeText = it },
                            label = { Text(stringResource(R.string.place_latitude)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = longitudeText,
                            onValueChange = { longitudeText = it },
                            label = { Text(stringResource(R.string.place_longitude)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    val typed = parseCoordinates(latitudeText, longitudeText)
                    TextButton(
                        onClick = { typed?.let(::pick) },
                        enabled = typed != null,
                    ) { Text(stringResource(R.string.place_coordinates_use)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.place_cancel)) } },
    )
}

@Composable
private fun PlaceRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Coordenadas escritas a mano. Acepta coma o punto decimal (el teclado español
 * da coma) y rechaza lo que no es un punto de la Tierra.
 */
fun parseCoordinates(latitudeText: String, longitudeText: String): PickedPlace? {
    val latitude = latitudeText.trim().replace(',', '.').toDoubleOrNull() ?: return null
    val longitude = longitudeText.trim().replace(',', '.').toDoubleOrNull() ?: return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    return PickedPlace(latitude, longitude, formatCoordinates(latitude, longitude))
}

/** "43,36, -5,85": dos decimales (~1 km) sobran para decir donde fue. */
fun formatCoordinates(latitude: Double, longitude: Double): String {
    fun two(value: Double): String {
        val hundredths = kotlin.math.round(value * 100).toLong()
        val sign = if (hundredths < 0) "-" else ""
        val absolute = kotlin.math.abs(hundredths)
        return "$sign${absolute / 100},${(absolute % 100).toString().padStart(2, '0')}"
    }
    return "${two(latitude)}, ${two(longitude)}"
}
