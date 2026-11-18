package fr.florian.teslamate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Mesure affichée par le calendrier d'activité. */
enum class CalendarMetric(val label: String) { KM("Km parcourus"), KWH("kWh chargés") }

/** Valeur par jour (date locale de début) : km des trajets ou kWh des charges. */
private fun dailyValues(drives: List<Drive>, charges: List<Charge>, metric: CalendarMetric): Map<LocalDate, Double> =
    when (metric) {
        CalendarMetric.KM -> drives.groupBy { it.start.local().toLocalDate() }.mapValues { (_, v) -> v.sumOf { it.distance } }
        CalendarMetric.KWH -> charges.groupBy { it.start.local().toLocalDate() }.mapValues { (_, v) -> v.sumOf { it.energyAdded } }
    }.filterValues { it >= 0.5 }

/** Seuils des 4 niveaux de couleur : quartiles des jours non vides (comme GitHub). */
private fun levels(values: Collection<Double>): List<Double> {
    if (values.isEmpty()) return emptyList()
    val s = values.sorted()
    return listOf(0.25, 0.5, 0.75).map { q -> s[((s.size - 1) * q).toInt()] }
}

private fun levelOf(v: Double, thresholds: List<Double>): Int = when {
    v <= 0 -> 0
    thresholds.isEmpty() -> 4
    else -> 1 + thresholds.count { v > it }
}

private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", FR)

/**
 * Calendrier d'activité façon GitHub : une case par jour, une colonne par semaine (lundi en haut).
 * [year] = 0 : une grille par année, la plus récente en premier. Toucher un jour non vide → [onDay].
 */
@Composable
fun CalendarSection(
    drives: List<Drive>,
    charges: List<Charge>,
    year: Int,
    metric: CalendarMetric,
    onDay: (LocalDate, CalendarMetric) -> Unit,
) {
    val values = remember(drives, charges, metric) { dailyValues(drives, charges, metric) }
    // Seuils communs à toutes les années affichées, pour que les couleurs restent comparables
    val thresholds = remember(values) { levels(values.values) }
    val color = if (metric == CalendarMetric.KM) MaterialTheme.colorScheme.primary else Color(0xFF43A047)
    val unit: (Double) -> String = { v -> if (metric == CalendarMetric.KM) km(v) else kwh(v) }
    val years = remember(values, year) {
        if (year != 0) listOf(year) else values.keys.map { it.year }.distinct().sortedDescending()
    }

    if (values.isEmpty()) {
        Centered { Text("Aucune activité sur la période", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    years.forEach { y ->
        val ofYear = values.filterKeys { it.year == y }
        Section(if (years.size > 1) "$y" else "Activité $y") {
            val days = ofYear.size
            val total = ofYear.values.sum()
            val best = ofYear.maxByOrNull { it.value }
            StatGrid(
                listOf(
                    (if (metric == CalendarMetric.KM) "Jours roulés" else "Jours de charge") to num(days.toDouble(), 0),
                    "Moy. / jour actif" to (if (days > 0) unit(total / days) else "—"),
                    "Record" to (best?.let { unit(it.value) } ?: "—"),
                ),
            )
            best?.let {
                Text(
                    "Record le ${it.key.format(dayFmt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            YearHeatmap(y, ofYear, thresholds, color) { onDay(it, metric) }
            Legend(color)
        }
    }
    Text(
        "Touchez un jour pour voir ses ${if (metric == CalendarMetric.KM) "trajets" else "charges"}.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val CELL = 13.dp
private val GAP = 3.dp
private val MONTH_H = 14.dp

/** Grille d'une année ; défile horizontalement (53 semaines) et se cale sur la semaine en cours. */
@Composable
private fun YearHeatmap(year: Int, values: Map<LocalDate, Double>, thresholds: List<Double>, color: Color, onDay: (LocalDate) -> Unit) {
    val measurer = rememberTextMeasurer()
    val small = TextStyle(fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val today = remember { LocalDate.now() }
    val jan1 = LocalDate.of(year, 1, 1)
    // Lundi = ligne 0 : décalage de la première semaine
    val offset = jan1.dayOfWeek.value - 1
    val weeks = (jan1.lengthOfYear() + offset + 6) / 7
    val scroll = rememberScrollState()
    val stepPx = with(LocalDensity.current) { (CELL + GAP).toPx() }
    val tap by rememberUpdatedState(onDay)
    // Année en cours : caler la semaine actuelle à droite de l'écran (les semaines récentes d'emblée visibles)
    LaunchedEffect(year, scroll.maxValue, scroll.viewportSize) {
        if (year == today.year && scroll.viewportSize > 0) {
            val col = (today.dayOfYear - 1 + offset) / 7
            scroll.scrollTo(((col + 1) * stepPx - scroll.viewportSize).toInt().coerceIn(0, scroll.maxValue))
        }
    }

    fun dateAt(col: Int, row: Int): LocalDate? {
        val idx = col * 7 + row - offset
        if (idx < 0 || idx >= jan1.lengthOfYear()) return null
        return jan1.plusDays(idx.toLong())
    }

    Row {
        // Libellés des jours, fixes à gauche
        Canvas(Modifier.width(14.dp).height(MONTH_H + (CELL + GAP) * 7)) {
            val step = (CELL + GAP).toPx()
            listOf(0 to "L", 2 to "M", 4 to "V", 6 to "D").forEach { (row, l) ->
                val t = measurer.measure(l, small)
                drawText(t, topLeft = Offset(0f, MONTH_H.toPx() + row * step + (CELL.toPx() - t.size.height) / 2))
            }
        }
        Row(Modifier.horizontalScroll(scroll)) {
            Canvas(
                Modifier
                    .width((CELL + GAP) * weeks)
                    .height(MONTH_H + (CELL + GAP) * 7)
                    .pointerInput(year, values) {
                        detectTapGestures { p ->
                            val step = (CELL + GAP).toPx()
                            val y = p.y - MONTH_H.toPx()
                            if (y < 0) return@detectTapGestures
                            val d = dateAt((p.x / step).toInt(), (y / step).toInt().coerceAtMost(6))
                            if (d != null && (values[d] ?: 0.0) > 0) tap(d)
                        }
                    },
            ) {
                val step = (CELL + GAP).toPx()
                val cell = CELL.toPx()
                val top = MONTH_H.toPx()
                val r = CornerRadius(2.dp.toPx())
                for (col in 0 until weeks) {
                    for (row in 0..6) {
                        val d = dateAt(col, row) ?: continue
                        val lvl = levelOf(values[d] ?: 0.0, thresholds)
                        drawRoundRect(
                            // Jours à venir : case vide estompée
                            color = when {
                                d.isAfter(today) -> empty.copy(alpha = 0.35f)
                                lvl == 0 -> empty
                                else -> color.copy(alpha = 0.25f + 0.25f * (lvl - 1))
                            },
                            topLeft = Offset(col * step, top + row * step),
                            size = Size(cell, cell),
                            cornerRadius = r,
                        )
                    }
                }
                // Mois : libellé au-dessus de la colonne contenant le 1er
                for (m in 1..12) {
                    val first = LocalDate.of(year, m, 1)
                    val col = (first.dayOfYear - 1 + offset) / 7
                    val t = measurer.measure(first.month.getDisplayName(java.time.format.TextStyle.SHORT, FR), small)
                    drawText(t, topLeft = Offset(col * step, 0f))
                }
            }
        }
    }
}

@Composable
private fun Legend(color: Color) {
    val empty = MaterialTheme.colorScheme.surfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("Moins", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(2.dp))
        (0..4).forEach { lvl ->
            Canvas(Modifier.size(CELL)) {
                drawRoundRect(
                    color = if (lvl == 0) empty else color.copy(alpha = 0.25f + 0.25f * (lvl - 1)),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
            }
        }
        Spacer(Modifier.width(2.dp))
        Text("Plus", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
