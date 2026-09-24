package dev.pelayo.aves.core.designsystem.components

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import dev.pelayo.aves.core.designsystem.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * El calendario para decir CUANDO fue algo (una grabacion, una foto, un
 * avistamiento). No deja elegir el futuro.
 *
 * @param currentMillis la fecha de partida, en milisegundos de epoca (hora local).
 * @param onPick el dia elegido, al mediodia LOCAL: ver [localNoonOf].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayPickerDialog(
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
            ) { Text(stringResource(R.string.day_picker_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.day_picker_cancel)) } },
    ) {
        DatePicker(state = state)
    }
}

/**
 * Del dia que devuelve el calendario (medianoche UTC) al mediodia LOCAL de ese
 * dia. Mediodia y no medianoche para que ningun cambio de huso lo empuje al dia
 * de al lado: lo que importa de la fecha es la semana del año.
 */
fun localNoonOf(utcMidnightMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(utcMidnightMillis).atZone(ZoneOffset.UTC).toLocalDate()
        .atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()

private val DayFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale("es"))

/** "12 may 2026", en castellano, en la zona del movil. */
fun formatDay(epochMillis: Long): String =
    DayFormat.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
