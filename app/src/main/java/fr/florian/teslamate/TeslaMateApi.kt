package fr.florian.teslamate

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.OffsetDateTime

// ---------------------------------------------------------------------------
// Connexion
// ---------------------------------------------------------------------------

/** Paramètres de connexion à la TeslaMate API (MyTeslaMate ou auto-hébergée). */
data class Connection(
    val url: String = "",
    /** true : authentification Basic (MyTeslaMate), false : jeton Bearer (auto-hébergé). */
    val basic: Boolean = true,
    /** Identifiant Basic ; vide = sous-domaine de l'URL (ex. « abcd » pour abcd.api.myteslamate.com). */
    val user: String = "",
    /** Mot de passe Basic ou jeton Bearer. */
    val secret: String = "",
) {
    val configured get() = url.isNotBlank()

    fun basicUser(): String {
        if (user.isNotBlank()) return user.trim()
        val host = url.trim().removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore(':').lowercase()
        return host.removeSuffix(".api.myteslamate.com").substringBefore('.')
    }

    companion object {
        private const val PREFS = "connexion"

        fun load(ctx: Context): Connection {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return Connection(
                url = p.getString("url", "") ?: "",
                basic = p.getBoolean("basic", true),
                user = p.getString("user", "") ?: "",
                secret = p.getString("secret", "") ?: "",
            )
        }

        fun save(ctx: Context, c: Connection) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("url", c.url.trim())
                .putBoolean("basic", c.basic)
                .putString("user", c.user.trim())
                .putString("secret", c.secret.trim())
                .apply()
        }
    }
}

class ApiException(message: String, val unauthorized: Boolean = false) : Exception(message)

// ---------------------------------------------------------------------------
// Modèles
// ---------------------------------------------------------------------------

data class Car(val id: Int, val name: String, val model: String, val totalDrives: Int, val totalCharges: Int)

data class CarStatus(
    val state: String,
    val stateSince: OffsetDateTime?,
    val odometer: Double?,
    val batteryLevel: Int?,
    val usableBatteryLevel: Int?,
    val ratedRange: Double?,
    val chargeLimit: Int?,
    val pluggedIn: Boolean,
    val chargingState: String,
    val chargerPower: Double?,
    val chargeEnergyAdded: Double?,
    val timeToFull: Double?,
    val insideTemp: Double?,
    val outsideTemp: Double?,
    val climateOn: Boolean,
    val locked: Boolean?,
    val sentry: Boolean?,
    val doorsOpen: Boolean,
    val windowsOpen: Boolean,
    val trunkOpen: Boolean,
    val frunkOpen: Boolean,
    val geofence: String,
    val latitude: Double?,
    val longitude: Double?,
    val version: String,
    val updateVersion: String,
    val tpms: List<Double?>, // AVG, AVD, ARG, ARD
    val tpmsWarning: List<Boolean>,
    val unitPressure: String,
    val shiftState: String,
    val speed: Double?,
)

data class Drive(
    val id: Int,
    val start: OffsetDateTime,
    val end: OffsetDateTime?,
    val startAddress: String,
    val endAddress: String,
    val distance: Double, // km
    val durationMin: Int,
    val speedMax: Double?,
    val speedAvg: Double?,
    val startBattery: Int?,
    val endBattery: Int?,
    val outsideTemp: Double?,
    val energyKwh: Double?, // énergie nette consommée
    val consumption: Double?, // Wh/km
)

data class Charge(
    val id: Int,
    val start: OffsetDateTime,
    val end: OffsetDateTime?,
    val address: String,
    val energyAdded: Double,
    val energyUsed: Double?,
    val cost: Double?,
    val durationMin: Int,
    val startBattery: Int?,
    val endBattery: Int?,
    val outsideTemp: Double?,
    val latitude: Double?,
    val longitude: Double?,
) {
    /** Puissance moyenne (kW) : sert à distinguer AC (lente) et DC (rapide). */
    val avgPowerKw: Double?
        get() = if (durationMin > 0 && energyAdded > 0) energyAdded / (durationMin / 60.0) else null

    /** Estimation : charge rapide DC au-delà de 25 kW de moyenne, ou Superchargeur dans l'adresse. */
    val isDc: Boolean
        get() = address.contains("supercharger", ignoreCase = true) || (avgPowerKw ?: 0.0) > 25.0
}

data class GeoPoint(val lat: Double, val lng: Double)

data class SeriesPoint(val time: OffsetDateTime?, val value: Double)

data class DriveDetail(val drive: Drive, val path: List<GeoPoint>, val speed: List<SeriesPoint>, val power: List<SeriesPoint>, val elevation: List<SeriesPoint>)

data class ChargeDetail(val charge: Charge, val power: List<SeriesPoint>, val battery: List<SeriesPoint>)

data class BatteryHealth(
    val healthPercent: Double?,
    val maxCapacity: Double?,
    val currentCapacity: Double?,
    val maxRange: Double?,
    val currentRange: Double?,
)

// ---------------------------------------------------------------------------
// Client HTTP (bloquant : appeler sur Dispatchers.IO)
// ---------------------------------------------------------------------------

class TeslaMateApi(private val conn: Connection) {

    private fun get(path: String): JSONObject {
        val base = conn.url.trim().trimEnd('/')
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("Accept", "application/json")
        if (conn.secret.isNotBlank()) {
            val auth = if (conn.basic) {
                "Basic " + Base64.encodeToString("${conn.basicUser()}:${conn.secret}".toByteArray(), Base64.NO_WRAP)
            } else {
                "Bearer ${conn.secret}"
            }
            c.setRequestProperty("Authorization", auth)
        }
        try {
            val code = c.responseCode
            if (code == 401 || code == 403) throw ApiException("Accès refusé ($code) : vérifier identifiant et mot de passe", unauthorized = true)
            if (code !in 200..299) throw ApiException("Erreur du serveur ($code)")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            // La TeslaMate API enveloppe tout dans « data »
            return json.optJSONObject("data") ?: json
        } finally {
            c.disconnect()
        }
    }

    fun cars(): List<Car> {
        val arr = get("/api/v1/cars").optJSONArray("cars") ?: JSONArray()
        return arr.objects().map { o ->
            val stats = o.optJSONObject("teslamate_stats")
            Car(
                id = o.getInt("car_id"),
                name = o.str("name").ifBlank { "Tesla" },
                model = o.optJSONObject("car_details")?.str("model").orEmpty(),
                totalDrives = stats?.optInt("total_drives") ?: 0,
                totalCharges = stats?.optInt("total_charges") ?: 0,
            )
        }
    }

    fun status(carId: Int): CarStatus {
        val data = get("/api/v1/cars/$carId/status")
        val s = data.optJSONObject("status") ?: data
        val units = data.optJSONObject("units")
        val bat = s.optJSONObject("battery_details") ?: JSONObject()
        val ch = s.optJSONObject("charging_details") ?: JSONObject()
        val cl = s.optJSONObject("climate_details") ?: JSONObject()
        val st = s.optJSONObject("car_status") ?: JSONObject()
        val geo = s.optJSONObject("car_geodata") ?: JSONObject()
        val ver = s.optJSONObject("car_versions") ?: JSONObject()
        val tp = s.optJSONObject("tpms_details") ?: JSONObject()
        val dr = s.optJSONObject("driving_details") ?: JSONObject()
        val wheels = listOf("fl", "fr", "rl", "rr")
        return CarStatus(
            state = s.str("state"),
            stateSince = s.date("state_since"),
            odometer = s.dbl("odometer"),
            batteryLevel = bat.int("battery_level"),
            usableBatteryLevel = bat.int("usable_battery_level"),
            ratedRange = bat.dbl("rated_battery_range"),
            chargeLimit = ch.int("charge_limit_soc"),
            pluggedIn = ch.optBoolean("plugged_in"),
            chargingState = ch.str("charging_state"),
            chargerPower = ch.dbl("charger_power"),
            chargeEnergyAdded = ch.dbl("charge_energy_added"),
            timeToFull = ch.dbl("time_to_full_charge"),
            insideTemp = cl.dbl("inside_temp"),
            outsideTemp = cl.dbl("outside_temp"),
            climateOn = cl.optBoolean("is_climate_on"),
            locked = if (st.has("locked")) st.optBoolean("locked") else null,
            sentry = if (st.has("sentry_mode")) st.optBoolean("sentry_mode") else null,
            doorsOpen = st.optBoolean("doors_open"),
            windowsOpen = st.optBoolean("windows_open"),
            trunkOpen = st.optBoolean("trunk_open"),
            frunkOpen = st.optBoolean("frunk_open"),
            geofence = geo.str("geofence"),
            latitude = geo.dbl("latitude"),
            longitude = geo.dbl("longitude"),
            version = ver.str("version"),
            updateVersion = if (ver.optBoolean("update_available")) ver.str("update_version") else "",
            tpms = wheels.map { tp.dbl("tpms_pressure_$it") },
            tpmsWarning = wheels.map { tp.optBoolean("tpms_soft_warning_$it") },
            unitPressure = units?.str("unit_of_pressure").orEmpty().ifBlank { "bar" },
            shiftState = dr.str("shift_state"),
            speed = dr.dbl("speed"),
        )
    }

    fun batteryHealth(carId: Int): BatteryHealth {
        val h = get("/api/v1/cars/$carId/battery-health").optJSONObject("battery_health") ?: JSONObject()
        return BatteryHealth(
            healthPercent = h.dbl("battery_health_percentage"),
            maxCapacity = h.dbl("max_capacity"),
            currentCapacity = h.dbl("current_capacity"),
            maxRange = h.dbl("max_range"),
            currentRange = h.dbl("current_range"),
        )
    }

    /** Tous les trajets (pagination par lots), optionnellement à partir d'une date. */
    /** [progress] reçoit le nombre d'éléments déjà téléchargés. */
    fun drives(carId: Int, since: OffsetDateTime? = null, progress: (Int) -> Unit = {}): List<Drive> =
        paged("/api/v1/cars/$carId/drives", "drives", since, progress).map(::parseDrive)

    fun charges(carId: Int, since: OffsetDateTime? = null, progress: (Int) -> Unit = {}): List<Charge> =
        paged("/api/v1/cars/$carId/charges", "charges", since, progress).map(::parseCharge)

    private fun paged(path: String, key: String, since: OffsetDateTime?, progress: (Int) -> Unit): List<JSONObject> {
        val all = mutableListOf<JSONObject>()
        val size = 500
        var page = 1
        val from = since?.let { "&startDate=" + URLEncoder.encode(it.toString(), "UTF-8") }.orEmpty()
        while (page <= 200) {
            val arr = get("$path?page=$page&show=$size$from").optJSONArray(key) ?: break
            all += arr.objects()
            progress(all.size)
            if (arr.length() < size) break
            page++
        }
        return all
    }

    fun drive(carId: Int, id: Int): DriveDetail {
        val o = get("/api/v1/cars/$carId/drives/$id").let { it.optJSONObject("drive") ?: it }
        val details = (o.optJSONArray("drive_details") ?: JSONArray()).objects()
        return DriveDetail(
            drive = parseDrive(o),
            path = details.mapNotNull { d ->
                val lat = d.dbl("latitude"); val lng = d.dbl("longitude")
                if (lat != null && lng != null && (lat != 0.0 || lng != 0.0)) GeoPoint(lat, lng) else null
            },
            speed = details.series("speed"),
            power = details.series("power"),
            elevation = details.series("elevation"),
        )
    }

    fun charge(carId: Int, id: Int): ChargeDetail {
        val o = get("/api/v1/cars/$carId/charges/$id").let { it.optJSONObject("charge") ?: it }
        val details = (o.optJSONArray("charge_details") ?: JSONArray()).objects()
        return ChargeDetail(
            charge = parseCharge(o),
            power = details.mapNotNull { d ->
                d.optJSONObject("charger_details")?.dbl("charger_power")?.let { SeriesPoint(d.date("date"), it) }
            },
            battery = details.series("battery_level"),
        )
    }

    companion object {
        fun parseDrive(o: JSONObject): Drive {
            val odo = o.optJSONObject("odometer_details") ?: JSONObject()
            val bat = o.optJSONObject("battery_details") ?: JSONObject()
            return Drive(
                id = o.getInt("drive_id"),
                start = o.date("start_date") ?: OffsetDateTime.MIN,
                end = o.date("end_date"),
                startAddress = o.str("start_address"),
                endAddress = o.str("end_address"),
                distance = odo.dbl("odometer_distance") ?: 0.0,
                durationMin = o.optInt("duration_min"),
                speedMax = o.dbl("speed_max"),
                speedAvg = o.dbl("speed_avg"),
                startBattery = bat.int("start_battery_level"),
                endBattery = bat.int("end_battery_level"),
                outsideTemp = o.dbl("outside_temp_avg"),
                energyKwh = o.dbl("energy_consumed_net"),
                consumption = o.dbl("consumption_net"),
            )
        }

        fun parseCharge(o: JSONObject): Charge {
            val bat = o.optJSONObject("battery_details") ?: JSONObject()
            return Charge(
                id = o.getInt("charge_id"),
                start = o.date("start_date") ?: OffsetDateTime.MIN,
                end = o.date("end_date"),
                address = o.str("address"),
                energyAdded = o.dbl("charge_energy_added") ?: 0.0,
                energyUsed = o.dbl("charge_energy_used"),
                cost = o.dbl("cost"),
                durationMin = o.optInt("duration_min"),
                startBattery = bat.int("start_battery_level"),
                endBattery = bat.int("end_battery_level"),
                outsideTemp = o.dbl("outside_temp_avg"),
                latitude = o.dbl("latitude"),
                longitude = o.dbl("longitude"),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Cache disque : l'historique complet est gardé en JSON pour un démarrage instantané
// et une mise à jour incrémentale (seulement les nouveaux trajets / charges).
// ---------------------------------------------------------------------------

object Cache {
    private fun file(ctx: Context, name: String) = File(ctx.filesDir, name)

    fun saveRaw(ctx: Context, name: String, items: List<JSONObject>) {
        file(ctx, name).writeText(JSONArray(items).toString())
    }

    fun loadRaw(ctx: Context, name: String): List<JSONObject> = try {
        val f = file(ctx, name)
        if (f.exists()) JSONArray(f.readText()).objects() else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    fun clear(ctx: Context) {
        ctx.filesDir.listFiles()?.filter { it.name.endsWith(".json") }?.forEach { it.delete() }
    }
}

// Conversion modèle → JSON pour le cache (mêmes clés que l'API, relues par parseDrive / parseCharge)
fun Drive.toJson(): JSONObject = JSONObject()
    .put("drive_id", id)
    .put("start_date", start.toString())
    .put("end_date", end?.toString())
    .put("start_address", startAddress)
    .put("end_address", endAddress)
    .put("odometer_details", JSONObject().put("odometer_distance", distance))
    .put("duration_min", durationMin)
    .put("speed_max", speedMax)
    .put("speed_avg", speedAvg)
    .put("battery_details", JSONObject().put("start_battery_level", startBattery).put("end_battery_level", endBattery))
    .put("outside_temp_avg", outsideTemp)
    .put("energy_consumed_net", energyKwh)
    .put("consumption_net", consumption)

fun Charge.toJson(): JSONObject = JSONObject()
    .put("charge_id", id)
    .put("start_date", start.toString())
    .put("end_date", end?.toString())
    .put("address", address)
    .put("charge_energy_added", energyAdded)
    .put("charge_energy_used", energyUsed)
    .put("cost", cost)
    .put("duration_min", durationMin)
    .put("battery_details", JSONObject().put("start_battery_level", startBattery).put("end_battery_level", endBattery))
    .put("outside_temp_avg", outsideTemp)
    .put("latitude", latitude)
    .put("longitude", longitude)

// ---------------------------------------------------------------------------
// Aides org.json
// ---------------------------------------------------------------------------

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")

fun JSONObject.dbl(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

fun JSONObject.int(key: String): Int? = dbl(key)?.toInt()

fun JSONObject.date(key: String): OffsetDateTime? = str(key).takeIf { it.isNotBlank() }?.let {
    try {
        OffsetDateTime.parse(it)
    } catch (_: Exception) {
        null
    }
}

private fun List<JSONObject>.series(key: String): List<SeriesPoint> =
    mapNotNull { d -> d.dbl(key)?.let { SeriesPoint(d.date("date"), it) } }
