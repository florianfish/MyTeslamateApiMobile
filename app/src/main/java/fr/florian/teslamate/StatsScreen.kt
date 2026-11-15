package fr.florian.teslamate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.TextStyle

/** Une ligne de statistiques : un mois (vue année) ou une année (vue « Tout »). */
private data class Bucket(
    val label: String,
    val km: Double,
    val driveKwh: Double,
    val chargedKwh: Double,
    val cost: Double,
    val dcKwh: Double,
    val drives: Int,
) {
    val consumption get() = if (km > 0 && driveKwh > 0) driveKwh * 1000 / km else 0.0
}

@Composable
fun StatsScreen(app: AppState) {
    val years = remember(app.drives, app.charges) {
        (app.drives.map { it.start.local().year } + app.charges.map { it.start.local().year })
            .filter { it > 2000 }.distinct().sortedDescending()
    }
    // 0 = toutes les années
    var chosen by rememberSaveable { mutableIntStateOf(LocalDate.now().year) }
    val year = if (chosen != 0 && years.isNotEmpty() && chosen !in years) years.first() else chosen

    val buckets = remember(app.drives, app.charges, year) { buckets(app, year) }
    val drives = remember(app.drives, year) { app.drives.filter { year == 0 || it.start.local().year == year } }
    val charges = remember(app.charges, year) { app.charges.filter { year == 0 || it.start.local().year == year } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(8.dp))
        ChoiceChips(years.map { it to it.toString() } + (0 to "Tout"), year) { chosen = it }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (drives.isEmpty() && charges.isEmpty()) {
                Centered { Text(if (app.loading) "Chargement de l'historique…" else "Pas de données", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                return@Column
            }
            val energy = charges.sumOf { it.energyAdded }
            val cost = charges.sumOf { it.cost ?: 0.0 }
            val dist = drives.sumOf { it.distance }
            Section(if (year == 0) "Depuis le début" else "Année $year") {
                StatGrid(
                    listOf(
                        "Distance" to km(dist),
                        "Trajets" to num(drives.size.toDouble(), 0),
                        "Conso moy." to whkm(drives.avgConsumption()),
                        "Énergie chargée" to kwh(energy),
                        "Coût" to euro(cost),
                        "Coût / 100 km" to (if (dist > 0) euro(cost / dist * 100) else "—"),
                        "Charges" to num(charges.count { it.energyAdded >= 0.5 }.toDouble(), 0),
                        "Part DC" to (if (energy > 0) "${num(charges.filter { it.isDc }.sumOf { it.energyAdded } / energy * 100, 0)} %" else "—"),
                        "Plus long" to km(drives.maxOfOrNull { it.distance }),
                    ),
                )
            }
            val labels = buckets.map { it.label }
            Section("Distance (km)") { BarChart(buckets.map { it.km }, labels) }
            Section("Énergie chargée (kWh)") { BarChart(buckets.map { it.chargedKwh }, labels, color = Color(0xFF43A047)) }
            Section("Coût (€)") { BarChart(buckets.map { it.cost }, labels, color = Color(0xFFFFA000)) }
            Section("Consommation moyenne (Wh/km)") { BarChart(buckets.map { it.consumption }, labels, color = Color(0xFF1E88E5)) }

            val topDest = remember(drives) {
                drives.filter { it.endAddress.isNotBlank() }.groupingBy { it.endAddress }.eachCount()
                    .entries.sortedByDescending { it.value }.take(6)
            }
            if (topDest.isNotEmpty()) {
                Section("Destinations fréquentes") { topDest.forEach { InfoRow(it.key, "${it.value} fois") } }
            }
            val topCharge = remember(charges) {
                charges.filter { it.address.isNotBlank() && it.energyAdded >= 0.5 }.groupBy { it.address }
                    .map { (k, v) -> k to v.sumOf { it.energyAdded } }.sortedByDescending { it.second }.take(6)
            }
            if (topCharge.isNotEmpty()) {
                Section("Lieux de charge (kWh)") { topCharge.forEach { InfoRow(it.first, kwh(it.second)) } }
            }
            app.health?.let { h ->
                Section("Santé batterie") {
                    InfoRow("Santé estimée", "${num(h.healthPercent, 1)} %")
                    InfoRow("Capacité actuelle", kwh(h.currentCapacity))
                    InfoRow("Capacité neuve", kwh(h.maxCapacity))
                    InfoRow("Autonomie à 100 %", km(h.currentRange))
                }
            }
        }
    }
}

private fun buckets(app: AppState, year: Int): List<Bucket> {
    val key: (java.time.OffsetDateTime) -> Int =
        if (year == 0) { d -> d.local().year } else { d -> d.local().monthValue }
    val drives = app.drives.filter { year == 0 || it.start.local().year == year }
    val charges = app.charges.filter { year == 0 || it.start.local().year == year }
    val keys: List<Int> = if (year == 0) {
        (drives.map { key(it.start) } + charges.map { key(it.start) }).filter { it > 2000 }.distinct().sorted()
    } else {
        (1..12).toList()
    }
    val dBy = drives.groupBy { key(it.start) }
    val cBy = charges.groupBy { key(it.start) }
    return keys.map { k ->
        val d = dBy[k].orEmpty()
        val c = cBy[k].orEmpty()
        Bucket(
            label = if (year == 0) "${k % 100}" else java.time.Month.of(k).getDisplayName(TextStyle.NARROW, FR),
            km = d.sumOf { it.distance },
            driveKwh = d.filter { it.energyKwh != null }.sumOf { it.energyKwh!! },
            chargedKwh = c.sumOf { it.energyAdded },
            cost = c.sumOf { it.cost ?: 0.0 },
            dcKwh = c.filter { it.isDc }.sumOf { it.energyAdded },
            drives = d.size,
        )
    }
}
