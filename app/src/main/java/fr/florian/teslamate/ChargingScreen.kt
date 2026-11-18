package fr.florian.teslamate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.time.OffsetDateTime
import kotlin.math.roundToLong

// ---------------------------------------------------------------------------
// Calculs
// ---------------------------------------------------------------------------

/** Heure de fin estimée à partir des heures restantes annoncées par la voiture. */
private fun endAt(hours: Double?): OffsetDateTime? =
    hours?.takeIf { it > 0 }?.let { OffsetDateTime.now().plusMinutes((it * 60).roundToLong()) }

private fun fmtEnd(hours: Double?): String {
    val end = endAt(hours) ?: return "—"
    val sameDay = end.local().toLocalDate() == OffsetDateTime.now().local().toLocalDate()
    return if (sameDay) "≈ ${end.fmtTime()}" else "≈ ${end.fmtDate()} ${end.fmtTime()}"
}

private fun phasesLabel(n: Int?): String = when (n) {
    null, 0 -> "—"
    1 -> "Monophasé"
    3 -> "Triphasé"
    else -> "$n phases"
}

private fun amps(v: Number?): String = if (v == null) "—" else "${num(v.toDouble(), 0)} A"

/** Ce qui limite l'intensité (charge AC seulement) : la borne ou le réglage de la voiture. */
private fun limitHint(s: CarStatus, pilot: Double?, dc: Boolean): String? {
    if (dc) return null
    val max = s.chargeCurrentRequestMax
    val request = s.chargeCurrentRequest
    return when {
        pilot != null && max != null && pilot < max - 0.5 -> "Intensité limitée par la borne (${amps(pilot)})"
        request != null && max != null && request < max -> "Intensité limitée par le réglage de la voiture (${amps(request)} sur ${amps(max)} possibles)"
        else -> null
    }
}

/** Coût : réel s'il est déjà connu, sinon estimé avec le prix habituel du lieu. */
private fun costLabel(app: AppState, cc: CurrentCharge?, s: CarStatus): Pair<String, String?> {
    cc?.cost?.takeIf { it > 0 }?.let { return euro(it) to null }
    val address = cc?.address?.ifBlank { null } ?: s.geofence
    val price = app.usualPrice(address) ?: return ("—" to null)
    val energy = cc?.energyAdded ?: s.chargeEnergyAdded ?: return ("—" to null)
    return "≈ ${euro(energy * price)}" to "Coût estimé avec le prix moyen des charges payantes à ce lieu (${num(price, 3)} €/kWh)"
}

private fun typeLabel(cc: CurrentCharge?): String {
    if (cc == null) return "—"
    return when (cc.dc) {
        true -> "DC" + (cc.fastChargerType.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
        false -> "AC"
        null -> "—"
    }
}

/** Abonne l'écran au rafraîchissement en direct tant qu'il est affiché. */
@Composable
private fun WatchLive(app: AppState) {
    DisposableEffect(Unit) {
        app.watchLive(true)
        onDispose { app.watchLive(false) }
    }
}

private fun liveNote(app: AppState): String =
    "Actualisé à ${app.statusAt?.local()?.toLocalTime()?.withNano(0) ?: "—"} · toutes les ${LIVE_PERIOD_MS / 1000} s pendant la charge"

// ---------------------------------------------------------------------------
// Carte « Charge en cours » de l'écran État
// ---------------------------------------------------------------------------

@Composable
fun ChargingCard(app: AppState, s: CarStatus, onOpen: (Int) -> Unit) {
    WatchLive(app)
    val cc = app.currentCharge
    val (cost, costNote) = costLabel(app, cc, s)
    Section("Charge en cours") {
        StatGrid(
            listOf(
                "Puissance" to "${num(s.chargerPower, 0)} kW",
                "Ajouté" to kwh(cc?.energyAdded ?: s.chargeEnergyAdded),
                "Autonomie" to (cc?.addedRange?.let { "+${km(it)}" } ?: "—"),
                "Durée" to (cc?.let { duration(it.minutes()) } ?: "—"),
                "Fin" to fmtEnd(s.timeToFull),
                "Coût" to cost,
            ),
        )
        val electric = listOfNotNull(
            s.chargerVoltage?.let { "${num(it, 0)} V" },
            s.chargerCurrent?.let { amps(it) },
            phasesLabel(s.chargerPhases).takeIf { it != "—" },
            typeLabel(cc).takeIf { it != "—" },
        )
        if (electric.isNotEmpty()) {
            Text(electric.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
        limitHint(s, cc?.last?.pilotCurrent, cc?.dc == true)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
        costNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(liveNote(app), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (cc != null) {
            TextButton(onClick = { onOpen(cc.id) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("Courbes et détails")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Écran détaillé de la charge en cours
// ---------------------------------------------------------------------------

@Composable
fun CurrentChargeScreen(app: AppState) {
    WatchLive(app)
    val cc = app.currentCharge
    val s = app.status
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (cc == null || s == null) {
            Centered { CircularProgressIndicator() }
            return@Column
        }
        val minutes = cc.minutes()
        val hours = minutes / 60.0
        val (cost, costNote) = costLabel(app, cc, s)
        val gained = if (cc.battery != null && cc.startBattery != null) cc.battery - cc.startBattery else null

        Text("En charge depuis ${cc.start.fmtTime()}", style = MaterialTheme.typography.titleMedium)
        if (cc.address.isNotBlank()) Text(cc.address, style = MaterialTheme.typography.bodyMedium)

        Section("") {
            BatteryBar(cc.battery ?: s.batteryLevel, s.chargeLimit)
            StatGrid(
                listOf(
                    "Batterie" to "${cc.startBattery ?: "—"} → ${cc.battery ?: "—"} %",
                    "Limite" to "${s.chargeLimit ?: "—"} %",
                    "Fin" to fmtEnd(s.timeToFull),
                    "Ajouté" to kwh(cc.energyAdded),
                    "Autonomie" to (cc.addedRange?.let { "+${km(it)}" } ?: "—"),
                    "Durée" to duration(minutes),
                    "Puissance" to "${num(s.chargerPower, 0)} kW",
                    "Vitesse" to (if (gained != null && hours > 0.1) "${num(gained / hours, 1)} %/h" else "—"),
                    "Km / heure" to (cc.addedRange?.takeIf { hours > 0.1 }?.let { "+${num(it / hours, 0)} km/h" } ?: "—"),
                    "Coût" to cost,
                    "Puissance moy." to (if (hours > 0.1) "${num(cc.energyAdded / hours, 1)} kW" else "—"),
                    "Temp. ext." to "${num(s.outsideTemp ?: cc.outsideTemp, 1)} °C",
                ),
            )
            costNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        val last = cc.last
        Section("Électrique") {
            InfoRow("Type", typeLabel(cc))
            InfoRow("Tension", s.chargerVoltage?.let { "${num(it, 0)} V" } ?: "—")
            InfoRow("Intensité", amps(s.chargerCurrent))
            InfoRow("Demandée / max", "${amps(s.chargeCurrentRequest)} / ${amps(s.chargeCurrentRequestMax)}")
            InfoRow("Annoncée par la borne", amps(last?.pilotCurrent))
            InfoRow("Phases", phasesLabel(s.chargerPhases))
            InfoRow("Câble", cc.cable.ifBlank { "—" })
            InfoRow("Préchauffage batterie", if (last?.heaterOn == true) "En cours" else "Non")
            limitHint(s, last?.pilotCurrent, cc.dc == true)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }

        val power = cc.points.mapNotNull { it.power }
        val battery = cc.points.mapNotNull { it.battery?.toDouble() }
        if (power.size > 1) Section("Puissance de charge (kW)") { LineChart(power.downsample()) }
        if (battery.size > 1) Section("Niveau de batterie (%)") { LineChart(battery.downsample(), color = Color(0xFF43A047)) }

        Text(liveNote(app), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
    }
}
