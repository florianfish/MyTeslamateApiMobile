package fr.florian.teslamate

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ChargeType(val label: String) { ALL("AC + DC"), AC("AC (lente)"), DC("DC (rapide)") }

enum class ChargeSort(val label: String) { RECENT("Plus récentes"), OLDEST("Plus anciennes"), ENERGY("Plus d'énergie"), COST("Plus chères"), PRICE("Prix au kWh le plus haut") }

data class ChargeFilter(
    val period: Period = Period.presets()[1],
    val type: ChargeType = ChargeType.ALL,
    val query: String = "",
    val sort: ChargeSort = ChargeSort.RECENT,
    val hideEmpty: Boolean = true,
    val paidOnly: Boolean = false,
    val map: Boolean = false,
)

fun List<Charge>.filtered(f: ChargeFilter): List<Charge> {
    val q = f.query.trim()
    val res = filter { c ->
        f.period.contains(c.start) &&
            (!f.hideEmpty || c.energyAdded >= 0.5) &&
            (!f.paidOnly || (c.cost ?: 0.0) > 0) &&
            when (f.type) {
                ChargeType.ALL -> true
                ChargeType.AC -> !c.isDc
                ChargeType.DC -> c.isDc
            } &&
            (q.isEmpty() || c.address.contains(q, true))
    }
    return when (f.sort) {
        ChargeSort.RECENT -> res.sortedByDescending { it.start }
        ChargeSort.OLDEST -> res.sortedBy { it.start }
        ChargeSort.ENERGY -> res.sortedByDescending { it.energyAdded }
        ChargeSort.COST -> res.sortedByDescending { it.cost ?: 0.0 }
        ChargeSort.PRICE -> res.filter { (it.cost ?: 0.0) > 0 && it.energyAdded > 0 }.sortedByDescending { it.cost!! / it.energyAdded }
    }
}

/** Lieux de charge les plus fréquents (raccourcis de filtre). */
fun List<Charge>.topPlaces(n: Int = 4): List<String> =
    filter { it.address.isNotBlank() && it.energyAdded >= 0.5 }
        .groupingBy { it.address }.eachCount()
        .entries.sortedByDescending { it.value }.take(n).map { it.key }

@Composable
fun ChargesScreen(app: AppState, filter: ChargeFilter, onFilter: (ChargeFilter) -> Unit, onOpen: (Int) -> Unit) {
    val list = remember(app.charges, filter) { app.charges.filtered(filter) }
    val places = remember(app.charges) { app.charges.topPlaces() }
    Column(Modifier.fillMaxSize()) {
        SearchField(filter.query, "Lieu de charge") { onFilter(filter.copy(query = it)) }
        if (places.isNotEmpty()) {
            ChoiceChips(listOf("" to "Tous lieux") + places.map { it to it }, filter.query) { onFilter(filter.copy(query = it)) }
        }
        PeriodChips(filter.period) { onFilter(filter.copy(period = it)) }
        ChoiceChips(ChargeType.entries.map { it to it.label }, filter.type) { onFilter(filter.copy(type = it)) }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DropdownChip("Tri : ${filter.sort.label}", ChargeSort.entries.map { it to it.label }) { onFilter(filter.copy(sort = it)) }
            FilterChip(selected = filter.paidOnly, onClick = { onFilter(filter.copy(paidOnly = !filter.paidOnly)) }, label = { Text("Payantes") })
            FilterChip(selected = !filter.hideEmpty, onClick = { onFilter(filter.copy(hideEmpty = !filter.hideEmpty)) }, label = { Text("Inclure vides") })
            FilterChip(
                selected = filter.map,
                onClick = { onFilter(filter.copy(map = !filter.map)) },
                leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Map, null) },
                label = { Text("Carte") },
            )
        }
        ChargeSummary(list)
        HorizontalDivider()
        if (list.isEmpty()) {
            Centered { Text(if (app.loading) "Chargement de l'historique…" else "Aucune charge pour ces filtres", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else if (filter.map) {
            ChargesMap(list)
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(list, key = { it.id }) { c -> ChargeRow(c) { onOpen(c.id) } }
        }
    }
}

/** Carte des lieux de charge de la sélection : un repère par lieu, avec le total (toucher pour le détail). */
@Composable
private fun ChargesMap(list: List<Charge>) {
    val markers = remember(list) {
        list.filter { it.latitude != null && it.longitude != null }
            .groupBy { it.address.ifBlank { "%.3f,%.3f".format(it.latitude, it.longitude) } }
            .map { (place, cs) ->
                val first = cs.first()
                MapMarker(
                    GeoPoint(first.latitude!!, first.longitude!!),
                    "$place\n${cs.size} charge(s) · ${kwh(cs.sumOf { it.energyAdded })}" +
                        (cs.sumOf { it.cost ?: 0.0 }.takeIf { it > 0 }?.let { " · ${euro(it)}" } ?: ""),
                    MarkerKind.CHARGE,
                )
            }
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            "${markers.size} lieu(x) · toucher un repère pour le détail",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        OsmMap(Modifier.fillMaxSize(), markers = markers, zoom = 14.0)
    }
}

@Composable
private fun ChargeSummary(list: List<Charge>) {
    val energy = list.sumOf { it.energyAdded }
    val cost = list.sumOf { it.cost ?: 0.0 }
    val paidEnergy = list.filter { (it.cost ?: 0.0) > 0 }.sumOf { it.energyAdded }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        StatGrid(
            listOf(
                "Charges" to num(list.size.toDouble(), 0),
                "Énergie" to kwh(energy),
                "Coût" to euro(cost),
                "Prix moy." to (if (paidEnergy > 0) "${num(cost / paidEnergy, 3)} €/kWh" else "—"),
                "Part DC" to (if (energy > 0) "${num(list.filter { it.isDc }.sumOf { it.energyAdded } / energy * 100, 0)} %" else "—"),
                "Durée" to duration(list.sumOf { it.durationMin }),
            ),
        )
    }
}

@Composable
private fun ChargeRow(c: Charge, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.start.fmtDateTime(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(c.address.ifBlank { "?" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                "${c.startBattery ?: "—"} → ${c.endBattery ?: "—"} % · ${duration(c.durationMin)}" +
                    (c.avgPowerKw?.let { " · ${num(it, 1)} kW" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(kwh(c.energyAdded), fontWeight = FontWeight.SemiBold)
            if ((c.cost ?: 0.0) > 0) Text(euro(c.cost), style = MaterialTheme.typography.labelSmall)
            Text(if (c.isDc) "DC" else "AC", style = MaterialTheme.typography.labelSmall, color = if (c.isDc) Color(0xFFE53935) else Color(0xFF43A047))
        }
    }
}

@Composable
fun ChargeDetailScreen(app: AppState, id: Int) {
    var detail by remember(id) { mutableStateOf<ChargeDetail?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    val summary = app.charges.firstOrNull { it.id == id }
    LaunchedEffect(id) {
        try {
            val api = app.api
            detail = withContext(Dispatchers.IO) { api.charge(app.carId, id) }
        } catch (e: Exception) {
            error = e.message ?: "Erreur"
        }
    }
    val c = detail?.charge ?: summary
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (c == null) {
            Centered { if (error != null) Text(error!!) else CircularProgressIndicator() }
            return@Column
        }
        Text(c.start.fmtDateTime(), style = MaterialTheme.typography.titleMedium)
        Text(c.address, style = MaterialTheme.typography.bodyMedium)
        if (c.latitude != null && c.longitude != null) {
            OsmMap(Modifier.fillMaxWidth().height(200.dp), markers = listOf(MapMarker(GeoPoint(c.latitude, c.longitude), c.address, MarkerKind.CHARGE)))
        }
        Section("Charge") {
            StatGrid(
                listOf(
                    "Énergie ajoutée" to kwh(c.energyAdded),
                    "Énergie consommée" to kwh(c.energyUsed),
                    "Rendement" to (c.energyUsed?.takeIf { it > 0 }?.let { "${num(c.energyAdded / it * 100, 0)} %" } ?: "—"),
                    "Durée" to duration(c.durationMin),
                    "Puissance moy." to (c.avgPowerKw?.let { "${num(it, 1)} kW" } ?: "—"),
                    "Type" to (if (c.isDc) "DC" else "AC"),
                    "Batterie" to "${c.startBattery ?: "—"} → ${c.endBattery ?: "—"} %",
                    "Coût" to euro(c.cost),
                    "Prix" to (if ((c.cost ?: 0.0) > 0 && c.energyAdded > 0) "${num(c.cost!! / c.energyAdded, 3)} €/kWh" else "—"),
                    "Temp. ext." to "${num(c.outsideTemp, 1)} °C",
                    "Fin" to (c.end?.fmtTime() ?: "—"),
                ),
            )
        }
        detail?.let { det ->
            // L'API renvoie les points du plus récent au plus ancien : on remet dans l'ordre du temps
            val power = det.power.sortedBy { it.time }.map { it.value }
            val battery = det.battery.sortedBy { it.time }.map { it.value }
            if (power.size > 1) Section("Puissance de charge (kW)") { LineChart(power.downsample()) }
            if (battery.size > 1) Section("Niveau de batterie (%)") { LineChart(battery.downsample(), color = Color(0xFF43A047)) }
        }
        error?.let { Text("Détail indisponible : $it", color = MaterialTheme.colorScheme.error) }
    }
}
