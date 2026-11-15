package fr.florian.teslamate

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            drives = emptyList(); charges = emptyList(); status = null; health = null; cars = emptyList()
        }
        refresh()
    }

    fun selectCar(id: Int) {
        if (id == carId) return
        carId = id
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putInt("car", id).apply()
        drives = emptyList(); charges = emptyList(); status = null; health = null
        loadCache()
        refresh()
    }

    private fun loadCache() {
        if (carId == 0) return
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
                health = withContext(Dispatchers.IO) { runCatching { api.batteryHealth(id) }.getOrNull() } ?: health

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
