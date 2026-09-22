package dev.pelayo.aves.feature.identify

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pelayo.aves.core.designsystem.components.PickedPlace
import dev.pelayo.aves.core.designsystem.components.PlaceChip
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * El fichero ya esta elegido y falta lo que el fichero no dice: donde y cuando
 * se grabo.
 *
 * Se pregunta ANTES de analizar y no despues porque el lugar cambia el
 * resultado (descarta lo que alli no hay) y el analisis de una grabacion larga
 * tarda: repetirlo por haberse olvidado del lugar es pagar dos veces.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PendingFileCard(
    file: PendingFile,
    place: PickedPlace?,
    canPickPlace: Boolean,
    onPickPlace: () -> Unit,
    onPickDate: () -> Unit,
    onAnalyze: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = stringResource(R.string.identify_file_context_title), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(R.string.identify_file_context_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Los dos chips en una fila que se PARTE si no caben: con un lugar
            // largo ("Parque Nacional de Doñana, Huelva, Andalucía") la fecha se
            // quedaba aplastada al lado. Y el lugar tiene tope de ancho.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (canPickPlace) {
                    PlaceChip(place = place, onClick = onPickPlace, modifier = Modifier.widthIn(max = 260.dp))
                }
                AssistChip(
                    onClick = onPickDate,
                    label = { Text(formatRecordingDate(file.recordedAt)) },
                    leadingIcon = {
                        Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.identify_file_cancel)) }
                Spacer(Modifier.size(8.dp))
                Button(onClick = onAnalyze) { Text(stringResource(R.string.identify_file_analyze)) }
            }
        }
    }
}

/**
 * Que esta haciendo el analisis y cuanto le falta.
 *
 * Antes solo cambiaba un contador de ventanas en letra pequena: con una
 * grabacion de varios minutos la pantalla parecia colgada. Solo la fase de
 * analisis sabe cuanto le queda; las demas ensenan una barra indeterminada, que
 * es decir la verdad: "estoy en ello y no se cuanto".
 */
@Composable
internal fun FileProgress(stage: FileStage, progress: Float, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = when (stage) {
                FileStage.LOADING_MODEL -> stringResource(R.string.identify_stage_model)
                FileStage.DECODING -> stringResource(R.string.identify_stage_decoding)
                FileStage.ANALYZING -> stringResource(R.string.identify_stage_analyzing, (progress * 100).toInt())
                FileStage.BUILDING_TRACKS -> stringResource(R.string.identify_stage_tracks)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (stage == FileStage.ANALYZING) {
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/** El calendario para decir cuando se grabo. No deja elegir el futuro. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordingDateDialog(
    currentMillis: Long,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    val state = rememberDatePickerState(
        // El calendario de Material trabaja en UTC: se le da la medianoche UTC
        // del dia LOCAL, o segun la hora saldria marcado el dia de antes.
        initialSelectedDateMillis = Instant.ofEpochMilli(currentMillis).atZone(ZoneId.systemDefault())
            .toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)

            override fun isSelectableYear(year: Int): Boolean = year <= today.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { onPick(localNoonOf(it)) } },
                enabled = state.selectedDateMillis != null,
            ) { Text(stringResource(R.string.identify_file_date_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.identify_file_cancel)) } },
    ) {
        DatePicker(state = state)
    }
}

/**
 * Del dia que devuelve el calendario (medianoche UTC) al mediodia LOCAL de ese
 * dia. Mediodia y no medianoche para que ningun cambio de huso lo empuje al dia
 * de al lado: lo que importa de la fecha es la semana del año.
 */
internal fun localNoonOf(utcMidnightMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(utcMidnightMillis).atZone(ZoneOffset.UTC).toLocalDate()
        .atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()

private val RecordingDateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale("es"))

internal fun formatRecordingDate(epochMillis: Long): String =
    RecordingDateFormat.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

/**
 * Cuando se modifico el fichero por ultima vez, que es lo mas parecido a "cuando
 * se grabo" que ofrece un proveedor de documentos. Null si no lo dice.
 */
internal fun Context.lastModifiedOf(uri: Uri): Long? = runCatching {
    contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }
}.getOrNull()?.takeIf { it > 0L }
