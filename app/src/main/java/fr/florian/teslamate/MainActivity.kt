package fr.florian.teslamate

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import org.osmdroid.config.Configuration

enum class Tab(val label: String, val icon: ImageVector) {
    STATUS("État", Icons.Default.DirectionsCar),
    DRIVES("Trajets", Icons.Default.Route),
    CHARGES("Charges", Icons.Default.ElectricBolt),
    STATS("Stats", Icons.Default.BarChart),
    SETTINGS("Réglages", Icons.Default.Settings),
}

class MainActivity : ComponentActivity() {
    private lateinit var app: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // osmdroid : User-Agent obligatoire pour les serveurs de tuiles OpenStreetMap
        Configuration.getInstance().userAgentValue = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}"
        Configuration.getInstance().osmdroidBasePath = cacheDir
        Configuration.getInstance().osmdroidTileCache = cacheDir.resolve("tiles")
        app = AppState(applicationContext, lifecycleScope)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            val dark = isSystemInDarkTheme()
            val colors = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) { MainScreen(app) }
        }
    }

    // Chaque ouverture (ou retour) dans l'appli : synchronisation du delta
    override fun onStart() {
        super.onStart()
        app.setForeground(true)
        app.refresh()
    }

    // En arrière-plan : plus aucun rafraîchissement en direct
    override fun onStop() {
        app.setForeground(false)
        super.onStop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(app: AppState) {
    var tab by rememberSaveable { mutableStateOf(if (app.connection.configured) Tab.STATUS else Tab.SETTINGS) }
    var openDrive by rememberSaveable { mutableStateOf<Int?>(null) }
    var openCharge by rememberSaveable { mutableStateOf<Int?>(null) }
    var driveFilter by remember { mutableStateOf(DriveFilter()) }
    var chargeFilter by remember { mutableStateOf(ChargeFilter()) }

    val detail = openDrive != null || openCharge != null
    BackHandler(enabled = detail) { openDrive = null; openCharge = null }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            when {
                                openDrive != null -> "Trajet"
                                openCharge != null && openCharge == app.currentCharge?.id -> "Charge en cours"
                                openCharge != null -> "Charge"
                                else -> app.cars.firstOrNull { it.id == app.carId }?.name ?: "TeslaMate"
                            },
                        )
                    },
                    navigationIcon = {
                        if (detail) IconButton(onClick = { openDrive = null; openCharge = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                        }
                    },
                    actions = {
                        if (!detail && app.connection.configured) {
                            IconButton(enabled = !app.loading, onClick = { app.refresh() }) { Icon(Icons.Default.Refresh, "Actualiser") }
                        }
                    },
                )
                if (app.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                app.progress?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                }
                app.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
        },
        bottomBar = {
            if (!detail) {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                openDrive != null -> DriveDetailScreen(app, openDrive!!)
                // Charge en cours : vue en direct ; une fois terminée, on bascule sur le détail classique
                openCharge != null && openCharge == app.currentCharge?.id -> CurrentChargeScreen(app)
                openCharge != null -> ChargeDetailScreen(app, openCharge!!)
                !app.connection.configured && tab != Tab.SETTINGS -> SettingsScreen(app)
                else -> when (tab) {
                    Tab.STATUS -> StatusScreen(app) { openCharge = it }
                    Tab.DRIVES -> DrivesScreen(app, driveFilter, { driveFilter = it }) { openDrive = it }
                    Tab.CHARGES -> ChargesScreen(app, chargeFilter, { chargeFilter = it }) { openCharge = it }
                    Tab.STATS -> StatsScreen(app) { day, metric ->
                        // Calendrier d'activité : ouvrir la liste filtrée sur ce jour
                        val period = Period(day.format(java.time.format.DateTimeFormatter.ofPattern("d/M/yyyy")), day, day.plusDays(1))
                        if (metric == CalendarMetric.KM) {
                            driveFilter = driveFilter.copy(period = period, distance = DistanceRange.ALL, query = "", map = false)
                            tab = Tab.DRIVES
                        } else {
                            chargeFilter = chargeFilter.copy(period = period, type = ChargeType.ALL, query = "", paidOnly = false, map = false)
                            tab = Tab.CHARGES
                        }
                    }
                    Tab.SETTINGS -> SettingsScreen(app)
                }
            }
        }
    }
}
