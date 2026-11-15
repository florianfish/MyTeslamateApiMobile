package fr.florian.teslamate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun stateLabel(s: CarStatus): String = when {
    s.chargingState.equals("Charging", true) -> "En charge"
    s.shiftState in listOf("D", "R", "N") -> "En route"
    else -> when (s.state.lowercase()) {
        "online" -> "En ligne"
        "asleep" -> "Endormie"
        "offline" -> "Hors ligne"
        "suspended" -> "En veille"
        "driving" -> "En route"
        "charging" -> "En charge"
        "updating" -> "Mise à jour"
        else -> s.state.ifBlank { "Inconnu" }
    }
}

private fun chargingLabel(s: CarStatus): String = when {
    !s.pluggedIn -> "Débranchée"
    s.chargingState.equals("Charging", true) -> "En charge"
    s.chargingState.equals("Complete", true) -> "Charge terminée"
    s.chargingState.equals("Stopped", true) -> "Branchée, en attente"
    else -> "Branchée"
}

@Composable
fun StatusScreen(app: AppState) {
    val s = app.status
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (s == null) {
            Centered {
                Text(
                    if (app.loading) "Chargement…" else app.error ?: "Aucune donnée",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        val car = app.cars.firstOrNull { it.id == app.carId }

        // Batterie en grand
        Section("") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(car?.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${s.batteryLevel ?: "—"}", fontSize = 44.sp, fontWeight = FontWeight.Bold)
                        Text(" %", fontSize = 20.sp, modifier = Modifier.padding(bottom = 8.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(km(s.ratedRange), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
                AssistChip(onClick = {}, label = { Text(stateLabel(s)) })
            }
            BatteryBar(s.batteryLevel, s.chargeLimit)
            Text(
                "Limite ${s.chargeLimit ?: "—"} % · ${chargingLabel(s)}" +
                    (if (s.chargingState.equals("Charging", true)) " · ${num(s.chargerPower, 0)} kW · +${kwh(s.chargeEnergyAdded)}" +
                        (s.timeToFull?.takeIf { it > 0 }?.let { " · plein dans ${duration((it * 60).toInt())}" } ?: "") else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (s.latitude != null && s.longitude != null) {
            OsmMap(
                Modifier.fillMaxWidth().height(200.dp),
                markers = listOf(MapMarker(GeoPoint(s.latitude, s.longitude), s.geofence)),
                interactive = false,
            )
        }
        if (s.geofence.isNotBlank()) Text("📍 ${s.geofence}", style = MaterialTheme.typography.bodyMedium)

        Section("Véhicule") {
            InfoRow("État depuis", s.stateSince?.fmtDateTime() ?: "—")
            InfoRow("Verrouillée", s.locked?.let { if (it) "Oui" else "Non" } ?: "—")
            InfoRow("Sentinelle", s.sentry?.let { if (it) "Activée" else "Désactivée" } ?: "—")
            val open = buildList {
                if (s.doorsOpen) add("portes")
                if (s.windowsOpen) add("fenêtres")
                if (s.trunkOpen) add("coffre")
                if (s.frunkOpen) add("frunk")
            }
            InfoRow("Ouvert", if (open.isEmpty()) "Rien" else open.joinToString())
            InfoRow("Kilométrage", km(s.odometer))
            InfoRow("Logiciel", s.version.ifBlank { "—" } + if (s.updateVersion.isNotBlank()) " (mise à jour ${s.updateVersion} dispo)" else "")
        }

        Section("Climat") {
            StatGrid(
                listOf(
                    "Intérieur" to "${num(s.insideTemp, 1)} °C",
                    "Extérieur" to "${num(s.outsideTemp, 1)} °C",
                    "Clim" to if (s.climateOn) "Allumée" else "Éteinte",
                ),
            )
        }

        if (s.tpms.any { it != null }) {
            Section("Pneus (${s.unitPressure})") {
                val labels = listOf("Avant gauche", "Avant droit", "Arrière gauche", "Arrière droit")
                StatGrid(
                    labels.indices.map { i ->
                        labels[i] to (num(s.tpms[i], 2) + if (s.tpmsWarning[i]) " ⚠" else "")
                    },
                    perRow = 2,
                )
            }
        }

        app.health?.let { h ->
            Section("Santé batterie") {
                StatGrid(
                    listOf(
                        "Santé" to "${num(h.healthPercent, 1)} %",
                        "Capacité" to "${num(h.currentCapacity, 1)} / ${num(h.maxCapacity, 1)} kWh",
                        "Autonomie 100 %" to "${km(h.currentRange)} (neuve ${km(h.maxRange)})",
                    ),
                    perRow = 1,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
