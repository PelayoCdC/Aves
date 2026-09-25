package dev.pelayo.aves.feature.pokedex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.pelayo.aves.core.common.runSuspendCatching
import dev.pelayo.aves.core.designsystem.components.DayPickerDialog
import dev.pelayo.aves.core.designsystem.components.PickedPlace
import dev.pelayo.aves.core.designsystem.components.PlaceChip
import dev.pelayo.aves.core.designsystem.components.PlacePickerActions
import dev.pelayo.aves.core.designsystem.components.PlacePickerDialog
import dev.pelayo.aves.core.designsystem.components.formatCoordinates
import dev.pelayo.aves.core.designsystem.components.formatDay

/**
 * Lo que el usuario decide de un avistamiento: cuando, donde y que apuntar.
 *
 * Es lo mismo para anotar uno a mano, para una foto que sube desde la ficha y
 * para corregir uno que ya existe. Antes solo se podian tocar las notas.
 */
data class SightingDraft(
    val timestamp: Long,
    val place: PickedPlace?,
    val notes: String?,
) {
    companion object {
        fun of(sighting: Sighting): SightingDraft = SightingDraft(
            timestamp = sighting.timestamp,
            place = if (sighting.latitude != null && sighting.longitude != null) {
                PickedPlace(sighting.latitude, sighting.longitude, formatCoordinates(sighting.latitude, sighting.longitude))
            } else {
                null
            },
            notes = sighting.notes,
        )
    }
}

/**
 * Una foto ya guardada en disco que espera a que el usuario diga cuando y
 * donde fue para convertirse en avistamiento.
 *
 * @param takenAtMillis la fecha del EXIF, si la foto la traia: es la propuesta
 *   para "cuando".
 */
data class SavedPhoto(
    val mediaPath: String,
    val takenAtMillis: Long?,
)

/**
 * El editor de un avistamiento: fecha, lugar y notas.
 *
 * @param initial de donde parte. Para uno nuevo, ahora y sin lugar.
 * @param placePicker para elegir el lugar. Nulo esconde el chip.
 * @param locateIfEmpty si se da, y el borrador no trae lugar, se propone la
 *   ubicacion actual al abrir. Es lo que espera quien anota "lo he visto":
 *   lo esta viendo AQUI.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SightingEditorDialog(
    title: String,
    initial: SightingDraft,
    confirmLabel: String,
    placePicker: PlacePickerActions?,
    onConfirm: (SightingDraft) -> Unit,
    onDismiss: () -> Unit,
    locateIfEmpty: (suspend () -> PickedPlace?)? = null,
) {
    var timestamp by remember { mutableLongStateOf(initial.timestamp) }
    var place by remember { mutableStateOf(initial.place) }
    var notes by remember { mutableStateOf(initial.notes.orEmpty()) }
    var showDate by remember { mutableStateOf(false) }
    var showPlace by remember { mutableStateOf(false) }
    var placeTouched by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (place == null && locateIfEmpty != null) {
            val located = runSuspendCatching { locateIfEmpty() }.getOrNull()
            // Si mientras tanto el usuario ya eligio algo, manda lo suyo.
            if (located != null && !placeTouched && place == null) place = located
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AssistChip(
                        onClick = { showDate = true },
                        label = { Text(formatDay(timestamp)) },
                        leadingIcon = {
                            Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                    )
                    if (placePicker != null) {
                        PlaceChip(place = place, onClick = { showPlace = true }, modifier = Modifier.widthIn(max = 240.dp))
                    }
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.pokedex_detail_notes_label)) },
                    placeholder = { Text(stringResource(R.string.pokedex_detail_notes_placeholder)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(SightingDraft(timestamp, place, notes.takeIf { it.isNotBlank() })) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.pokedex_cancel)) } },
    )

    if (showDate) {
        DayPickerDialog(
            currentMillis = timestamp,
            onPick = { picked ->
                timestamp = picked
                showDate = false
            },
            onDismiss = { showDate = false },
        )
    }
    if (showPlace && placePicker != null) {
        PlacePickerDialog(
            actions = placePicker,
            onPick = { picked ->
                place = picked
                placeTouched = true
                showPlace = false
            },
            onDismiss = { showPlace = false },
        )
    }
}
