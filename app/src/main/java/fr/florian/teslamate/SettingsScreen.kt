package fr.florian.teslamate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

@Composable
fun SettingsScreen(app: AppState) {
    val c = app.connection
    var url by remember(c) { mutableStateOf(c.url) }
    var basic by remember(c) { mutableStateOf(c.basic) }
    var user by remember(c) { mutableStateOf(c.user) }
    var secret by remember(c) { mutableStateOf(c.secret) }
    var show by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val edited = Connection(url, basic, user, secret)
    val insecure = url.trim().startsWith("http://", ignoreCase = true)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section("Connexion à la TeslaMate API") {
            OutlinedTextField(
                url, { url = it },
                Modifier.fillMaxWidth(),
                label = { Text("Adresse de l'API") },
                placeholder = { Text("https://xxxx.api.myteslamate.com") },
                singleLine = true,
                // HTTPS obligatoire : le mot de passe ne doit jamais circuler en clair
                isError = insecure,
                supportingText = { if (insecure) Text("Adresse en https:// obligatoire") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = basic, onClick = { basic = true }, label = { Text("Identifiant + mot de passe") })
                FilterChip(selected = !basic, onClick = { basic = false }, label = { Text("Jeton (Bearer)") })
            }
            if (basic) {
                OutlinedTextField(
                    user, { user = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Identifiant") },
                    placeholder = { Text(edited.basicUser().ifBlank { "sous-domaine de l'adresse" }) },
                    supportingText = { Text("Vide = sous-domaine de l'adresse (${edited.basicUser().ifBlank { "…" }})") },
                    singleLine = true,
                )
            }
            OutlinedTextField(
                secret, { secret = it },
                Modifier.fillMaxWidth(),
                label = { Text(if (basic) "Mot de passe" else "Jeton") },
                singleLine = true,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { show = !show }) {
                        Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Afficher")
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = url.isNotBlank() && !insecure, onClick = {
                    testResult = "Test en cours…"
                    scope.launch { testResult = app.test(edited) }
                }) { Text("Tester") }
                Button(enabled = url.isNotBlank() && !insecure, onClick = { app.saveConnection(edited); testResult = null }) { Text("Enregistrer") }
            }
            testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        if (app.cars.size > 1) {
            Section("Voiture") {
                ChoiceChips(app.cars.map { it.id to it.name }, app.carId) { app.selectCar(it) }
            }
        }

        Section("Synchronisation") {
            InfoRow("Trajets en mémoire", num(app.drives.size.toDouble(), 0))
            InfoRow("Charges en mémoire", num(app.charges.size.toDouble(), 0))
            InfoRow(
                "Dernière synchro",
                if (app.lastSync == 0L) "Jamais"
                else OffsetDateTime.ofInstant(Instant.ofEpochMilli(app.lastSync), ZoneId.systemDefault()).fmtDateTime(),
            )
            Text(
                "Au premier démarrage tout l'historique est téléchargé, puis seulement les nouveautés à chaque ouverture.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(enabled = !app.loading && app.connection.configured, onClick = { app.refresh(full = true) }) {
                Text("Tout resynchroniser")
            }
        }

        Text(
            "MyTeslamateApiMobile ${BuildConfig.VERSION_NAME} · cartes © OpenStreetMap",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
