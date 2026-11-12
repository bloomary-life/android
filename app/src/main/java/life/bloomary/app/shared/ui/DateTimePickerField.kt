package life.bloomary.app.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Date + time field in the device time zone; the counterpart of SwiftUI's
 * compact `DatePicker`. Days outside [min]…[max] can't be picked; the caller
 * still validates the exact instant.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimePickerField(
    value: Instant,
    onValueChange: (Instant) -> Unit,
    min: Instant,
    max: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    var showsDate by remember { mutableStateOf(false) }
    var showsTime by remember { mutableStateOf(false) }
    val local = value.atZone(zone)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(Locale.getDefault())
                .format(local),
            color = AppStyle.ink,
            fontSize = 16.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showsDate = true }) { Text("Date") }
            OutlinedButton(onClick = { showsTime = true }) { Text("Time") }
        }
    }

    if (showsDate) {
        val minDay = min.atZone(zone).toLocalDate()
        val maxDay = max.atZone(zone).toLocalDate()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                // DatePicker reports days as UTC midnight.
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val day = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return !day.isBefore(minDay) && !day.isAfter(maxDay)
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { showsDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val day: LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onValueChange(day.atTime(local.toLocalTime()).atZone(zone).toInstant())
                    }
                    showsDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showsDate = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state)
        }
    }

    if (showsTime) {
        val state = rememberTimePickerState(initialHour = local.hour, initialMinute = local.minute)
        AlertDialog(
            onDismissRequest = { showsTime = false },
            confirmButton = {
                TextButton(onClick = {
                    onValueChange(local.toLocalDate().atTime(LocalTime.of(state.hour, state.minute)).atZone(zone).toInstant())
                    showsTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showsTime = false }) { Text("Cancel") } },
            text = { TimePicker(state = state) },
        )
    }
}
