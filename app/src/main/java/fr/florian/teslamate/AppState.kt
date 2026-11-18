package fr.florian.teslamate

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime

/**
 * État global de l'appli : connexion, voiture choisie, historique (trajets / charges),
 * état actuel et santé batterie. Les écrans le lisent directement (état Compose).
 */
class AppState(private val ctx: Context, private val scope: CoroutineScope) {

    var connection by mutableStateOf(Connection.load(ctx))
        private set
    var cars by mutableStateOf<List<Car>>(emptyList())
        private set
    var carId by mutableStateOf(ctx.getSharedPreferences("app", Context.MODE_PRIVATE).getInt("car", 0))
        private set
    var drives by mutableStateOf<List<Drive>>(emptyList())
        private set
    var charges by mutableStateOf<List<Charge>>(emptyList())
        private set
    var status by mutableStateOf<CarStatus?>(null)
        private set
    var health by mutableStateOf<BatteryHealth?>(null)
        private set
    var updates by mutableStateOf<List<SoftwareUpdate>>(emptyList())
        private set

    /** Charge en cours (null hors charge, ou si l'instance n'a pas l'endpoint /charges/current). */
    var currentCharge by mutableStateOf<CurrentCharge?>(null)
        private set
    /** Heure du dernier relevé de l'état (synchro ou rafraîchissement en direct). */
    var statusAt by mutableStateOf<OffsetDateTime?>(null)
        private set

    /** Tracés simplifiés des trajets (id → points), gardés sur disque : le détail d'un trajet pèse lourd. */
    var paths by mutableStateOf<Map<Int, List<GeoPoint>>>(emptyMap())
        private set
    var pathsLoading by mutableStateOf<String?>(null)
        private set

    /** Paramètres de comparaison avec une voiture thermique. */
    var fuelConsumption by mutableStateOf(prefs().getFloat("fuel_l100", 6.5f).toDouble())
        private set
    var fuelPrice by mutableStateOf(prefs().getFloat("fuel_price", 1.85f).toDouble())
        private set

    private fun prefs() = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)

    fun setFuel(l100: Double, price: Double) {
        fuelConsumption = l100
        fuelPrice = price
        prefs().edit().putFloat("fuel_l100", l100.toFloat()).putFloat("fuel_price", price.toFloat()).apply()
    }
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    /** Texte de progression de la synchronisation (null = rien en cours). */
    var progress by mutableStateOf<String?>(null)
        private set
    /** Date de la dernière synchronisation réussie. */
    var lastSync by mutableStateOf(ctx.getSharedPreferences("app", Context.MODE_PRIVATE).getLong("sync", 0L))
        private set

    val api get() = TeslaMateApi(connection)

    init {
        loadCache()
    }

    fun saveConnection(c: Connection) {
        val changed = c.url.trim() != connection.url
        Connection.save(ctx, c)
        connection = Connection.load(ctx)
        if (changed) {
            Cache.clear(ctx)
            drives = emptyList(); charges = emptyList(); status = null; health = null; cars = emptyList(); paths = emptyMap(); updates = emptyList(); currentCharge = null
        }
        refresh()
    }

    fun selectCar(id: Int) {
        if (id == carId) return
        carId = id
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putInt("car", id).apply()
        drives = emptyList(); charges = emptyList(); status = null; health = null; updates = emptyList(); paths = emptyMap(); currentCharge = null
        loadCache()
        refresh()
    }

    private fun loadCache() {
        if (carId == 0) return
        paths = Cache.loadRaw(ctx, "paths_$carId.json").mapNotNull { o ->
            val arr = o.optJSONArray("p") ?: return@mapNotNull null
            o.optInt("id") to (0 until arr.length() step 2).map { GeoPoint(arr.getDouble(it), arr.getDouble(it + 1)) }
        }.toMap()
        drives = Cache.loadRaw(ctx, "drives_$carId.json").mapNotNull { runCatching { TeslaMateApi.parseDrive(it) }.getOrNull() }
        charges = Cache.loadRaw(ctx, "charges_$carId.json").mapNotNull { runCatching { TeslaMateApi.parseCharge(it) }.getOrNull() }
    }

    /**
     * Synchronisation. Sans historique en cache (premier démarrage, autre voiture) ou avec [full] :
     * tout l'historique est téléchargé. Sinon seulement le delta depuis le plus récent connu.
     * Appelée à chaque ouverture de l'appli (MainActivity.onStart).
     */
    fun refresh(full: Boolean = false) {
        if (!connection.configured || loading) return
        loading = true
        error = null
        scope.launch {
            try {
                val api = this@AppState.api
                withContext(Dispatchers.IO) {
                    val list = api.cars()
                    withContext(Dispatchers.Main) {
                        cars = list
                        if (list.none { it.id == carId } && list.isNotEmpty()) {
                            carId = list.first().id
                            ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putInt("car", carId).apply()
                            loadCache()
                        }
                    }
                }
                val id = carId
                if (id == 0) return@launch
                val st = withContext(Dispatchers.IO) { runCatching { api.status(id) }.getOrNull() }
                status = st
                statusAt = OffsetDateTime.now()
                currentCharge = if (st?.isCharging == true) {
                    withContext(Dispatchers.IO) { runCatching { api.currentCharge(id) }.getOrNull() }
                } else null
                updateLive()
                health = withContext(Dispatchers.IO) { runCatching { api.batteryHealth(id) }.getOrNull() } ?: health
                updates = withContext(Dispatchers.IO) { runCatching { api.updates(id) }.getOrNull() } ?: updates

                // Incrémental : on repart un jour avant le plus récent connu (les doublons sont fusionnés par id)
                val dSince = if (full) null else drives.maxOfOrNull { it.start }?.minusDays(1)
                val cSince = if (full) null else charges.maxOfOrNull { it.start }?.minusDays(1)
                val complete = dSince == null || cSince == null
                progress = if (complete) "Synchronisation complète…" else "Mise à jour…"
                val newDrives = withContext(Dispatchers.IO) {
                    api.drives(id, dSince) { n -> if (dSince == null) progress = "Synchronisation complète : $n trajets…" }
                }
                val newCharges = withContext(Dispatchers.IO) {
                    api.charges(id, cSince) { n -> if (cSince == null) progress = "Synchronisation complète : $n charges…" }
                }
                val d = merge(if (full) emptyList() else drives, newDrives, { it.id }, { it.start })
                val c = merge(if (full) emptyList() else charges, newCharges, { it.id }, { it.start })
                drives = d
                charges = c
                withContext(Dispatchers.IO) {
                    Cache.saveRaw(ctx, "drives_$id.json", d.map { it.toJson() })
                    Cache.saveRaw(ctx, "charges_$id.json", c.map { it.toJson() })
                }
                lastSync = System.currentTimeMillis()
                ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putLong("sync", lastSync).apply()
            } catch (e: ApiException) {
                error = e.message
            } catch (e: Exception) {
                error = "Connexion impossible : ${e.message ?: e.javaClass.simpleName}"
            } finally {
                loading = false
                progress = null
            }
        }
    }

    // -----------------------------------------------------------------------
    // Suivi en direct pendant une charge
    // -----------------------------------------------------------------------

    private var foreground = false
    private var watchers = 0
    private var liveJob: Job? = null

    /** Appelée par MainActivity (onStart / onStop) : rien ne tourne quand l'appli est en arrière-plan. */
    fun setForeground(on: Boolean) {
        foreground = on
        updateLive()
    }

    /** Un écran qui affiche la charge en cours s'abonne (true) puis se désabonne (false). */
    fun watchLive(on: Boolean) {
        watchers = (watchers + if (on) 1 else -1).coerceAtLeast(0)
        updateLive()
    }

    /** Démarre ou arrête le rafraîchissement : seulement en charge, appli visible et écran concerné affiché. */
    private fun updateLive() {
        val wanted = foreground && watchers > 0 && status?.isCharging == true
        if (wanted && liveJob?.isActive != true) {
            liveJob = scope.launch {
                while (isActive) {
                    delay(LIVE_PERIOD_MS)
                    liveRefresh()
                }
            }
        } else if (!wanted) {
            liveJob?.cancel()
            liveJob = null
        }
    }

    /**
     * Relevé léger : seulement /status (quelques Ko). La courbe complète (/charges/current,
     * plusieurs centaines de Ko) n'est téléchargée qu'une fois par charge, puis complétée par ces relevés.
     */
    private suspend fun liveRefresh() {
        val id = carId
        if (id == 0 || loading) return
        val api = this.api
        val st = withContext(Dispatchers.IO) { runCatching { api.status(id) }.getOrNull() } ?: return
        val wasCharging = status?.isCharging == true
        status = st
        statusAt = OffsetDateTime.now()
        if (st.isCharging) {
            val cc = currentCharge
            currentCharge = cc?.withStatus(st)
                ?: withContext(Dispatchers.IO) { runCatching { api.currentCharge(id) }.getOrNull() }
        } else {
            currentCharge = null
            // Charge terminée : synchro pour récupérer la charge complète dans l'historique
            if (wasCharging) refresh()
        }
        updateLive()
    }

    /**
     * Prix moyen au kWh des charges payantes déjà faites à [address] (les 10 plus récentes),
     * pour estimer le coût d'une charge en cours. null si aucune référence.
     */
    fun usualPrice(address: String): Double? {
        if (address.isBlank()) return null
        val paid = charges
            .filter { it.address == address && (it.cost ?: 0.0) > 0 && it.energyAdded >= 0.5 }
            .sortedByDescending { it.start }
            .take(10)
        val energy = paid.sumOf { it.energyAdded }
        return if (energy > 0) paid.sumOf { it.cost ?: 0.0 } / energy else null
    }

    /** Télécharge les tracés manquants des trajets [ids] (au plus [max]), puis les garde en cache. */
    fun loadPaths(ids: List<Int>, max: Int = 40) {
        if (pathsLoading != null || !connection.configured) return
        val missing = ids.filter { it !in paths }.take(max)
        if (missing.isEmpty()) return
        val id = carId
        val api = this@AppState.api
        scope.launch {
            try {
                missing.forEachIndexed { i, driveId ->
                    pathsLoading = "Tracés : ${i + 1} / ${missing.size}…"
                    val pts = withContext(Dispatchers.IO) {
                        runCatching { api.drive(id, driveId).path.simplify(80) }.getOrNull()
                    } ?: return@forEachIndexed
                    paths = paths + (driveId to pts)
                }
                val snapshot = paths
                withContext(Dispatchers.IO) {
                    Cache.saveRaw(ctx, "paths_$id.json", snapshot.map { (k, v) ->
                        org.json.JSONObject().put("id", k).put("p", org.json.JSONArray(v.flatMap { listOf(it.lat, it.lng) }))
                    })
                }
            } finally {
                pathsLoading = null
            }
        }
    }

    private fun <T, K : Comparable<K>> merge(old: List<T>, new: List<T>, id: (T) -> Int, key: (T) -> K): List<T> {
        val byId = LinkedHashMap<Int, T>()
        old.forEach { byId[id(it)] = it }
        new.forEach { byId[id(it)] = it }
        return byId.values.sortedByDescending(key)
    }

    /** Test de connexion pour l'écran Réglages. */
    suspend fun test(c: Connection): String = withContext(Dispatchers.IO) {
        try {
            val list = TeslaMateApi(c).cars()
            if (list.isEmpty()) "Connexion OK, mais aucune voiture trouvée"
            else "Connexion OK : " + list.joinToString { it.name }
        } catch (e: Exception) {
            "Échec : ${e.message ?: e.javaClass.simpleName}"
        }
    }
}

/** Période du rafraîchissement en direct pendant une charge. */
const val LIVE_PERIOD_MS = 30_000L

/** Garde au plus [max] points régulièrement espacés (premier et dernier inclus). */
fun List<GeoPoint>.simplify(max: Int): List<GeoPoint> {
    if (size <= max) return this
    val step = (size - 1).toDouble() / (max - 1)
    return (0 until max).map { this[(it * step).toInt().coerceAtMost(size - 1)] }
}
