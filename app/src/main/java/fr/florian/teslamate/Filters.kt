package fr.florian.teslamate

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Période de filtrage. [from] inclus, [to] exclu ; null = sans borne. */
data class Period(val label: String, val from: LocalDate?, val to: LocalDate?) {
    fun contains(d: OffsetDateTime): Boolean {
        val day = d.local().toLocalDate()
        return (from == null || !day.isBefore(from)) && (to == null || day.isBefore(to))
    }

    companion object {
        val ALL = Period("Tout", null, null)

        fun presets(today: LocalDate = LocalDate.now()): List<Period> = listOf(
            Period("7 jours", today.minusDays(6), null),
            Period("30 jours", today.minusDays(29), null),
            Period("Ce mois", today.withDayOfMonth(1), null),
            Period("Cette année", today.withDayOfYear(1), null),
            Period("${today.year - 1}", LocalDate.of(today.year - 1, 1, 1), LocalDate.of(today.year, 1, 1)),
            ALL,
        )
    }
}

/** Puces de période + sélecteur de dates personnalisé. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodChips(period: Period, onChange: (Period) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val presets = remember { Period.presets() }
    val custom = presets.none { it == period }
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        presets.forEach { p ->
            FilterChip(selected = p == period, onClick = { onChange(p) }, label = { Text(p.label) })
        }
        FilterChip(
            selected = custom,
            onClick = { picking = true },
            leadingIcon = { Icon(Icons.Default.DateRange, null) },
            label = { Text(if (custom) period.label else "Dates…") },
        )
    }
    if (picking) {
        val state = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        val from = state.selectedStartDateMillis?.toLocalDate()
                        val to = (state.selectedEndDateMillis ?: state.selectedStartDateMillis)?.toLocalDate()
                        if (from != null && to != null) {
                            val label = if (from == to) from.toString() else "${from.dayOfMonth}/${from.monthValue} → ${to.dayOfMonth}/${to.monthValue}/${to.year % 100}"
                            onChange(Period(label, from, to.plusDays(1)))
                        }
                        picking = false
                    },
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Annuler") } },
        ) {
            DateRangePicker(state, Modifier.padding(top = 8.dp))
        }
    }
}

// Le sélecteur renvoie des millisecondes à minuit UTC
private fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

/** Rangée de puces à choix unique. */
@Composable
fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (v, l) ->
            FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(l) })
        }
    }
}

/** Menu déroulant compact (tri). */
@Composable
fun <T> DropdownChip(label: String, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        FilterChip(
            selected = false,
            onClick = { open = true },
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, l) ->
                DropdownMenuItem(text = { Text(l) }, onClick = { onSelect(v); open = false })
            }
        }
    }
}
