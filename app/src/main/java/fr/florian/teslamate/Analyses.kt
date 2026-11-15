package fr.florian.teslamate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.Month
import java.time.OffsetDateTime
import java.time.format.TextStyle as JTextStyle
import kotlin.math.floor
import kotlin.math.roundToInt

// ===========================================================================
// Trajets récurrents
// ===========================================================================

data class Route(val from: String, val to: String, val drives: List<Drive>) {
    val avgKm get() = drives.map { it.distance }.average()
    val avgMin get() = drives.map { it.durationMin }.average()
    val avgConso get() = drives.avgConsumption()
    val best get() = drives.filter { it.consumption != null }.minByOrNull { it.consumption!! }
    val worst get() = drives.filter { it.consumption != null }.maxByOrNull { it.consumption!! }
}

/** Trajets faits au moins [min] fois entre les mêmes adresses de départ et d'arrivée. */
fun List<Drive>.routes(min: Int = 3): List<Route> =
    filter { it.startAddress.isNotBlank() && it.endAddress.isNotBlank() && it.startAddress != it.endAddress && it.distance >= 0.5 }
        .groupBy { it.startAddress to it.endAddress }
        .filter { it.value.size >= min }
        .map { (k, v) -> Route(k.first, k.second, v) }
        .sortedByDescending { it.drives.size }

@Composable
fun RoutesSection(drives: List<Drive>, allDrives: List<Drive>) {
    val routes = remember(drives) { drives.routes().take(15) }
    var open by remember { mutableStateOf<Pair<String, String>?>(null) }
    if (routes.isEmpty()) {
        Centered { Text("Aucun trajet fait au moins 3 fois sur cette période", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    Text(
        "Mêmes adresses de départ et d'arrivée, au moins 3 fois. Toucher un trajet pour sa conso selon le mois (toutes années).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    routes.forEach { r ->
        val key = r.from to r.to
        Section("") {
            Column(Modifier.fillMaxWidth().clickable { open = if (open == key) null else key }) {
                Text(r.from, style = MaterialTheme.typography.bodyMedium)
                Text("→ ${r.to}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            StatGrid(
                listOf(
                    "Fois" to "${r.drives.size}",
                    "Distance moy." to km(r.avgKm),
                    "Durée moy." to duration(r.avgMin.roundToInt()),
                    "Conso moy." to whkm(r.avgConso),
                    "Meilleure" to whkm(r.best?.consumption),
                    "Pire" to whkm(r.worst?.consumption),
                ),
            )
            if (open == key) {
                // Saisonnalité sur tout l'historique de ce trajet
                val all = allDrives.filter { it.startAddress == r.from && it.endAddress == r.to }
                val byMonth = (1..12).map { m -> all.filter { it.start.local().monthValue == m }.avgConsumption() ?: 0.0 }
                Text("Conso moyenne par mois (Wh/km)", style = MaterialTheme.typography.labelMedium)
                BarChart(byMonth, monthLabels(), color = Color(0xFF1E88E5))
                r.best?.let { InfoRow("Meilleure fois", "${it.start.fmtDate()} · ${num(it.outsideTemp, 0)} °C") }
                r.worst?.let { InfoRow("Pire fois", "${it.start.fmtDate()} · ${num(it.outsideTemp, 0)} °C") }
            }
        }
    }
}

fun monthLabels(): List<String> = (1..12).map { Month.of(it).getDisplayName(JTextStyle.NARROW, FR) }

// ===========================================================================
// Consommation selon la température
// ===========================================================================

@Composable
fun TemperatureSection(drives: List<Drive>) {
    // Trajets d'au moins 5 km : les très courts trajets (chauffage au démarrage) faussent tout
    val pts = remember(drives) {
        drives.filter { it.distance >= 5 && it.consumption != null && it.outsideTemp != null && it.consumption in 50.0..500.0 }
    }
    if (pts.size < 5) {
        Centered { Text("Pas assez de trajets (≥ 5 km) sur cette période", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    // Tranches de 5 °C
    val bands = remember(pts) {
        pts.groupBy { (floor(it.outsideTemp!! / 5) * 5).toInt() }.toSortedMap()
            .map { (t, ds) -> Triple(t, ds.avgConsumption() ?: 0.0, ds.size) }
    }
    Section("Wh/km selon la température extérieure") {
        ScatterChart(pts.map { it.outsideTemp!! to it.consumption!! })
        Text("${pts.size} trajets de 5 km et plus", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Section("Moyenne par tranche de 5 °C") {
        BarChart(bands.map { it.second }, bands.map { "${it.first}°" }, color = Color(0xFF1E88E5))
        val warm = bands.filter { it.first in 15..20 }.maxByOrNull { it.third }
        val cold = bands.filter { it.first <= 5 }.maxByOrNull { it.third }
        if (warm != null && cold != null && warm.second > 0) {
            val extra = (cold.second / warm.second - 1) * 100
            Text(
                "Entre ${cold.first} et ${cold.first + 5} °C, tu consommes ${num(extra, 0)} % de plus qu'entre ${warm.first} et ${warm.first + 5} °C.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        bands.forEach { (t, c, n) -> InfoRow("$t à ${t + 5} °C ($n trajets)", whkm(c)) }
    }
}

/** Nuage de points (x = température, y = Wh/km). */
@Composable
fun ScatterChart(points: List<Pair<Double, Double>>, color: Color = MaterialTheme.colorScheme.primary) {
    val measurer = rememberTextMeasurer()
    val small = TextStyle(fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(220.dp)) {
        val xMin = floor(points.minOf { it.first } / 5) * 5
        val xMax = (floor(points.maxOf { it.first } / 5) + 1) * 5
        val yMin = floor(points.minOf { it.second } / 50) * 50
        val yMax = (floor(points.maxOf { it.second } / 50) + 1) * 50
        val left = 30.dp.toPx()
        val bottom = 14.dp.toPx()
        val w = size.width - left
        val h = size.height - bottom
        fun x(v: Double) = (left + (v - xMin) / (xMax - xMin) * w).toFloat()
        fun y(v: Double) = (h - (v - yMin) / (yMax - yMin) * h).toFloat()
        var t = yMin
        while (t <= yMax) {
            drawLine(grid, Offset(left, y(t)), Offset(size.width, y(t)), 1f)
            val l = measurer.measure("${t.toInt()}", small)
            drawText(l, topLeft = Offset(0f, (y(t) - l.size.height / 2).coerceIn(0f, h - l.size.height)))
            t += 50
        }
        var tx = xMin
        while (tx <= xMax) {
            val l = measurer.measure("${tx.toInt()}°", small)
            drawText(l, topLeft = Offset((x(tx) - l.size.width / 2).coerceIn(0f, size.width - l.size.width), h + 2f))
            tx += 5
        }
        points.forEach { (px, py) -> drawCircle(color.copy(alpha = 0.35f), 2.5.dp.toPx(), Offset(x(px), y(py))) }
    }
}

// ===========================================================================
// Pertes à l'arrêt (batterie perdue entre deux trajets / charges)
// ===========================================================================

data class Idle(val start: OffsetDateTime, val end: OffsetDateTime, val place: String, val lossPct: Int) {
    val hours get() = Duration.between(start, end).toMinutes() / 60.0
    val pctPerDay get() = if (hours > 0) lossPct / hours * 24 else 0.0
}

private data class Event(val start: OffsetDateTime, val end: OffsetDateTime, val startBat: Int?, val endBat: Int?, val place: String)

/** Périodes d'arrêt d'au moins 1 h entre deux événements consécutifs, avec le % de batterie perdu. */
fun idlePeriods(drives: List<Drive>, charges: List<Charge>): List<Idle> {
    val events = (drives.map { Event(it.start, it.end ?: it.start, it.startBattery, it.endBattery, it.endAddress) } +
        charges.map { Event(it.start, it.end ?: it.start, it.startBattery, it.endBattery, it.address) })
        .sortedBy { it.start }
    return events.zipWithNext().mapNotNull { (a, b) ->
        val endBat = a.endBat ?: return@mapNotNull null
        val startBat = b.startBat ?: return@mapNotNull null
        if (Duration.between(a.end, b.start).toMinutes() < 60) return@mapNotNull null
        Idle(a.end, b.start, a.place, (endBat - startBat).coerceAtLeast(0))
    }
}

@Composable
fun IdleSection(app: AppState, year: Int) {
    val idles = remember(app.drives, app.charges, year) {
        idlePeriods(app.drives, app.charges).filter { year == 0 || it.start.local().year == year }
    }
    if (idles.isEmpty()) {
        Centered { Text("Pas de données sur cette période", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    val totalPct = idles.sumOf { it.lossPct }
    val hours = idles.sumOf { it.hours }
    val capacity = app.health?.currentCapacity
    val rangeFull = app.health?.currentRange
    Section("À l'arrêt") {
        StatGrid(
            listOf(
                "Perdu au total" to "$totalPct %",
                "Énergie" to (capacity?.let { kwh(totalPct / 100.0 * it) } ?: "—"),
                "Autonomie" to (rangeFull?.let { km(totalPct / 100.0 * it) } ?: "—"),
                "Par jour d'arrêt" to "${num(totalPct / hours * 24, 2)} %",
                "Temps à l'arrêt" to "${num(hours / 24, 0)} j",
                "Arrêts" to "${idles.size}",
            ),
        )
        Text(
            "Écart de batterie entre la fin d'un trajet ou d'une charge et le début du suivant (arrêts d'au moins 1 h). " +
                "La batterie étant relevée au % près, les arrêts courts sont imprécis.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (year != 0) {
        Section("% perdus par mois") {
            BarChart((1..12).map { m -> idles.filter { it.start.local().monthValue == m }.sumOf { it.lossPct }.toDouble() }, monthLabels(), color = Color(0xFFE53935))
        }
    }
    val byPlace = remember(idles) {
        idles.filter { it.place.isNotBlank() && it.hours >= 6 }.groupBy { it.place }
            .map { (p, l) -> Triple(p, l.sumOf { it.lossPct } / l.sumOf { it.hours } * 24, l.size) }
            .filter { it.third >= 3 }.sortedByDescending { it.third }.take(6)
    }
    if (byPlace.isNotEmpty()) {
        Section("Perte par jour selon le lieu (arrêts ≥ 6 h)") {
            byPlace.forEach { (p, rate, n) -> InfoRow("$p ($n)", "${num(rate, 2)} %/j") }
        }
    }
    Section("Plus grosses pertes") {
        idles.sortedByDescending { it.lossPct }.take(8).forEach { i ->
            InfoRow("${i.start.fmtDate()} · ${num(i.hours, 0)} h · ${i.place.ifBlank { "?" }}", "−${i.lossPct} %")
        }
    }
}

// ===========================================================================
// Économies
// ===========================================================================

@Composable
fun SavingsSection(app: AppState, drives: List<Drive>, charges: List<Charge>) {
    var l100 by remember { mutableStateOf(num(app.fuelConsumption, 1)) }
    var price by remember { mutableStateOf(num(app.fuelPrice, 2)) }
    fun parse(s: String) = s.replace(',', '.').replace(" ", "").toDoubleOrNull()

    val dist = drives.sumOf { it.distance }
    val elec = charges.sumOf { it.cost ?: 0.0 }
    val fuelCost = dist / 100 * app.fuelConsumption * app.fuelPrice
    val paid = charges.filter { (it.cost ?: 0.0) > 0 }
    val avgPrice = paid.sumOf { it.cost!! }.let { c -> paid.sumOf { it.energyAdded }.takeIf { it > 0 }?.let { c / it } }
    val free = charges.filter { it.energyAdded >= 0.5 && (it.cost ?: 0.0) <= 0.0 }
    val freeKwh = free.sumOf { it.energyAdded }

    Section("Voiture thermique de comparaison") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                l100, { l100 = it; parse(it)?.let { v -> app.setFuel(v, app.fuelPrice) } },
                Modifier.weight(1f), label = { Text("L/100 km") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            OutlinedTextField(
                price, { price = it; parse(it)?.let { v -> app.setFuel(app.fuelConsumption, v) } },
                Modifier.weight(1f), label = { Text("€ / L") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }
    }
    Section("Bilan") {
        StatGrid(
            listOf(
                "Distance" to km(dist),
                "Électricité" to euro(elec),
                "En thermique" to euro(fuelCost),
                "Économie" to euro(fuelCost - elec),
                "Élec / 100 km" to (if (dist > 0) euro(elec / dist * 100) else "—"),
                "Therm. / 100 km" to euro(app.fuelConsumption * app.fuelPrice),
            ),
        )
        Text(
            "Électricité = coûts renseignés dans TeslaMate (les charges sans coût comptent pour 0).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (freeKwh > 0) {
        Section("Charges gratuites") {
            StatGrid(
                listOf(
                    "Énergie" to kwh(freeKwh),
                    "Charges" to "${free.size}",
                    "Valeur" to (avgPrice?.let { euro(freeKwh * it) } ?: "—"),
                ),
            )
            avgPrice?.let {
                Text("Valeur au prix moyen payé ailleurs (${num(it, 3)} €/kWh)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            free.groupBy { it.address.ifBlank { "?" } }
                .map { (p, l) -> p to l.sumOf { it.energyAdded } }
                .sortedByDescending { it.second }.take(6)
                .forEach { (p, e) -> InfoRow(p, kwh(e)) }
        }
    }
}

// ===========================================================================
// Mises à jour logicielles
// ===========================================================================

@Composable
fun UpdatesSection(updates: List<SoftwareUpdate>) {
    if (updates.isEmpty()) {
        Centered { Text("Aucune mise à jour connue", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    val sorted = updates.sortedByDescending { it.start }
    val gaps = sorted.zipWithNext { a, b -> Duration.between(b.start, a.start).toDays() }
    val current = sorted.first()
    val sinceDays = Duration.between(current.start, OffsetDateTime.now()).toDays()
    val installs = sorted.mapNotNull { u -> u.end?.let { Duration.between(u.start, it).toMinutes() }?.takeIf { it > 0 } }
    Section("Logiciel") {
        StatGrid(
            listOf(
                "Version" to current.version,
                "Depuis" to "$sinceDays j",
                "Mises à jour" to "${sorted.size}",
                "Écart moyen" to (if (gaps.isNotEmpty()) "${gaps.average().roundToInt()} j" else "—"),
                "Install. moy." to (if (installs.isNotEmpty()) "${installs.average().roundToInt()} min" else "—"),
                "Par an" to num(sorted.size / (Duration.between(sorted.last().start, OffsetDateTime.now()).toDays() / 365.0).coerceAtLeast(1e-9), 1),
            ),
        )
    }
    Section("Historique") {
        sorted.forEachIndexed { i, u ->
            val dur = u.end?.let { Duration.between(u.start, it).toMinutes() }?.takeIf { it > 0 }
            val gap = gaps.getOrNull(i)
            InfoRow(
                "${u.start.fmtDate()}" + (dur?.let { " · $it min" } ?: "") + (gap?.let { " · +$it j" } ?: ""),
                u.version,
            )
        }
    }
}
