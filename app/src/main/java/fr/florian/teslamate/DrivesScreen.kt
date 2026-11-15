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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class DriveSort(val label: String) { RECENT("Plus récents"), OLDEST("Plus anciens"), LONGEST("Plus longs"), ECO("Conso la plus basse"), GREEDY("Conso la plus haute") }

enum class DistanceRange(val label: String, val min: Double, val max: Double) {
    ALL("Toutes distances", 0.0, Double.MAX_VALUE),
    SHORT("< 10 km", 0.0, 10.0),
    MEDIUM("10–50 km", 10.0, 50.0),
    LONG("50–200 km", 50.0, 200.0),
    TRIP("> 200 km", 200.0, Double.MAX_VALUE),
}

/** Filtres des trajets (gardés au niveau de l'écran principal pour survivre aux changements d'onglet). */
data class DriveFilter(
    val period: Period = Period.presets()[1],
    val distance: DistanceRange = DistanceRange.ALL,
    val query: String = "",
    val sort: DriveSort = DriveSort.RECENT,
    val hideTiny: Boolean = true,
    /** Affichage carte au lieu de la liste. */
    val map: Boolean = false,
)

fun List<Drive>.filtered(f: DriveFilter): List<Drive> {
    val q = f.query.trim()
    val res = filter { d ->
        f.period.contains(d.start) &&
            d.distance >= f.distance.min && d.distance < f.distance.max &&
            (!f.hideTiny || d.distance >= 0.5) &&
            (q.isEmpty() || d.startAddress.contains(q, true) || d.endAddress.contains(q, true))
    }
    return when (f.sort) {
        DriveSort.RECENT -> res.sortedByDescending { it.start }
        DriveSort.OLDEST -> res.sortedBy { it.start }
        DriveSort.LONGEST -> res.sortedByDescending { it.distance }
        DriveSort.ECO -> res.filter { it.consumption != null }.sortedBy { it.consumption }
        DriveSort.GREEDY -> res.filter { it.consumption != null }.sortedByDescending { it.consumption }
    }
}

/** Consommation moyenne pondérée par la distance (Wh/km). */
fun List<Drive>.avgConsumption(): Double? {
    val withE = filter { it.energyKwh != null && it.distance > 0 }
    val dist = withE.sumOf { it.distance }
    return if (dist > 0) withE.sumOf { it.energyKwh!! } * 1000 / dist else null
}

@Composable
fun DrivesScreen(app: AppState, filter: DriveFilter, onFilter: (DriveFilter) -> Unit, onOpen: (Int) -> Unit) {
    val list = remember(app.drives, filter) { app.drives.filtered(filter) }
    Column(Modifier.fillMaxSize()) {
        SearchField(filter.query, "Lieu de départ ou d'arrivée") { onFilter(filter.copy(query = it)) }
        PeriodChips(filter.period) { onFilter(filter.copy(period = it)) }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChoiceChips(DistanceRange.entries.map { it to it.label }, filter.distance) { onFilter(filter.copy(distance = it)) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DropdownChip("Tri : ${filter.sort.label}", DriveSort.entries.map { it to it.label }) { onFilter(filter.copy(sort = it)) }
            androidx.compose.material3.FilterChip(
                selected = !filter.hideTiny,
                onClick = { onFilter(filter.copy(hideTiny = !filter.hideTiny)) },
                label = { Text("Inclure < 500 m") },
            )
            androidx.compose.material3.FilterChip(
                selected = filter.map,
                onClick = { onFilter(filter.copy(map = !filter.map)) },
                leadingIcon = { Icon(androidx.compose.material.icons.Icons.Default.Map, null) },
                label = { Text("Carte") },
            )
        }
        DriveSummary(list)
        HorizontalDivider()
        if (list.isEmpty()) {
            Centered { Text(if (app.loading) "Chargement de l'historique…" else "Aucun trajet pour ces filtres", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else if (filter.map) {
            DrivesMap(app, list)
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(list, key = { it.id }) { d -> DriveRow(d) { onOpen(d.id) } }
        }
    }
}

/** Carte des trajets sélectionnés : tracés simplifiés, téléchargés une fois puis gardés en cache. */
@Composable
private fun DrivesMap(app: AppState, list: List<Drive>) {
    val max = 40
    val ids = remember(list) { list.sortedByDescending { it.start }.take(max).map { it.id } }
    LaunchedEffect(ids) { app.loadPaths(ids, max) }
    val shown = ids.mapNotNull { app.paths[it] }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            app.pathsLoading
                ?: ("${shown.size} trajet(s) affiché(s)" + if (list.size > max) " · les $max plus récents de la sélection" else ""),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        OsmMap(Modifier.fillMaxSize(), paths = shown)
    }
}

@Composable
fun SearchField(value: String, hint: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        placeholder = { Text(hint) },
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Clear, "Effacer") } },
    )
}

@Composable
private fun DriveSummary(list: List<Drive>) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        StatGrid(
            listOf(
                "Trajets" to num(list.size.toDouble(), 0),
                "Distance" to km(list.sumOf { it.distance }),
                "Conso moy." to whkm(list.avgConsumption()),
                "Énergie" to kwh(list.sumOf { it.energyKwh ?: 0.0 }),
                "Durée" to duration(list.sumOf { it.durationMin }),
                "Vitesse max" to (list.mapNotNull { it.speedMax }.maxOrNull()?.let { "${num(it, 0)} km/h" } ?: "—"),
            ),
        )
    }
}

@Composable
private fun DriveRow(d: Drive, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(d.start.fmtDateTime(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(d.startAddress.ifBlank { "?" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("→ " + d.endAddress.ifBlank { "?" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(km(d.distance), fontWeight = FontWeight.SemiBold)
            Text(duration(d.durationMin), style = MaterialTheme.typography.labelSmall)
            Text(whkm(d.consumption), style = MaterialTheme.typography.labelSmall, color = consumptionColor(d.consumption))
        }
    }
}

@Composable
fun DriveDetailScreen(app: AppState, id: Int) {
    var detail by remember(id) { mutableStateOf<DriveDetail?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    val summary = app.drives.firstOrNull { it.id == id }
    LaunchedEffect(id) {
        try {
            val api = app.api
            detail = withContext(Dispatchers.IO) { api.drive(app.carId, id) }
        } catch (e: Exception) {
            error = e.message ?: "Erreur"
        }
    }
    val d = detail?.drive ?: summary
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (d == null) {
            Centered { if (error != null) Text(error!!) else CircularProgressIndicator() }
            return@Column
        }
        Text(d.start.fmtDateTime(), style = MaterialTheme.typography.titleMedium)
        Text("${d.startAddress} → ${d.endAddress}", style = MaterialTheme.typography.bodyMedium)

        val path = detail?.path.orEmpty()
        if (path.size > 1) {
            OsmMap(
                Modifier.fillMaxWidth().height(260.dp),
                path = path,
                markers = listOf(MapMarker(path.first(), "Départ", MarkerKind.START), MapMarker(path.last(), "Arrivée", MarkerKind.END)),
            )
        } else if (detail == null && error == null) {
            Centered { CircularProgressIndicator() }
        }

        Section("Trajet") {
            StatGrid(
                listOf(
                    "Distance" to km(d.distance),
                    "Durée" to duration(d.durationMin),
                    "Conso" to whkm(d.consumption),
                    "Énergie" to kwh(d.energyKwh),
                    "Vitesse moy." to "${num(d.speedAvg, 0)} km/h",
                    "Vitesse max" to "${num(d.speedMax, 0)} km/h",
                    "Batterie" to "${d.startBattery ?: "—"} → ${d.endBattery ?: "—"} %",
                    "Temp. ext." to "${num(d.outsideTemp, 1)} °C",
                    "Arrivée" to (d.end?.fmtTime() ?: "—"),
                ),
            )
        }
        detail?.let { det ->
            if (det.speed.size > 1) Section("Vitesse (km/h)") { LineChart(det.speed.map { it.value }.downsample(), unit = "") }
            if (det.power.size > 1) Section("Puissance (kW, négatif = régénération)") { LineChart(det.power.map { it.value }.downsample()) }
            if (det.elevation.size > 1) Section("Altitude (m)") { LineChart(det.elevation.map { it.value }.downsample()) }
        }
        error?.let { Text("Détail indisponible : $it", color = MaterialTheme.colorScheme.error) }
    }
}

/** Réduit une série à ~[max] points (moyennes par paquets) pour garder le dessin léger. */
fun List<Double>.downsample(max: Int = 300): List<Double> {
    if (size <= max) return this
    val step = size.toDouble() / max
    return (0 until max).map { i ->
        val a = (i * step).toInt()
        val b = ((i + 1) * step).toInt().coerceAtMost(size).coerceAtLeast(a + 1)
        subList(a, b).average()
    }
}
