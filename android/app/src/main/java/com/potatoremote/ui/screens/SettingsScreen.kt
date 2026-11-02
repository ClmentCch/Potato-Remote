package com.potatoremote.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.potatoremote.BuildConfig
import com.potatoremote.service.ShareService
import com.potatoremote.settings.Prefs
import com.potatoremote.signaling.ApiClient
import com.potatoremote.ui.ShareState
import com.potatoremote.ui.components.InfoNote
import com.potatoremote.ui.components.PotatoScreenSurface
import com.potatoremote.ui.components.LabeledField
import com.potatoremote.ui.components.PotatoCard
import com.potatoremote.ui.components.PrimaryButton
import com.potatoremote.ui.components.QualityChips
import com.potatoremote.ui.components.ScreenHeader
import com.potatoremote.ui.components.SectionTitle
import com.potatoremote.ui.components.Stepper
import com.potatoremote.ui.theme.BrownSoft
import com.potatoremote.ui.theme.Danger
import com.potatoremote.ui.theme.Sage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(prefs: Prefs, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accessibilityReady by ShareState.accessibilityReady.collectAsStateWithLifecycle()

    var server by remember { mutableStateOf(prefs.serverUrl) }
    var serverKey by remember { mutableStateOf(prefs.serverKey) }
    var deviceName by remember { mutableStateOf(prefs.deviceName) }
    var quality by remember { mutableStateOf(prefs.quality) }
    var maxViewers by remember { mutableStateOf(prefs.maxViewers) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusError by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }

    PotatoScreenSurface {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenHeader("Réglages", "Connexion, autorisations et confidentialité.", onBack)

            PotatoCard {
                SectionTitle("SERVEUR")
                LabeledField(
                    value = server,
                    onValueChange = {
                        server = it
                        status = null
                    },
                    label = "Adresse du site Potato Remote",
                    placeholder = "https://potato.mondomaine.fr",
                    keyboardType = KeyboardType.Uri,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            val normalized = Prefs.normalizeServer(server)
                            if (!Prefs.isValidServer(normalized)) {
                                status = "Adresse invalide : HTTPS est requis."
                                statusError = true
                                return@TextButton
                            }
                            testing = true
                            status = null
                            scope.launch {
                                val result = try {
                                    withContext(Dispatchers.IO) { ApiClient(normalized).ping() }
                                    "Serveur joignable."
                                } catch (error: Throwable) {
                                    error.message ?: "Serveur injoignable."
                                }
                                testing = false
                                status = result
                                statusError = !result.startsWith("Serveur joignable")
                            }
                        },
                        enabled = !testing,
                    ) { Text(if (testing) "Test en cours…" else "Tester la connexion") }
                }
                if (status != null) {
                    Text(
                        status!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (statusError) Danger else Sage,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Exemple o2switch : https://potato.mondomaine.fr (sans /api).",
                    style = MaterialTheme.typography.bodySmall,
                    color = BrownSoft,
                )
                Spacer(Modifier.height(12.dp))
                LabeledField(
                    value = serverKey,
                    onValueChange = { serverKey = it },
                    label = "Clé serveur (optionnelle)",
                    placeholder = "Si create_secret est configuré",
                )
            }

            PotatoCard {
                SectionTitle("APPAREIL")
                LabeledField(
                    value = deviceName,
                    onValueChange = { deviceName = it.take(32) },
                    label = "Nom affiché aux spectateurs",
                    placeholder = "Téléphone de Clément",
                )
            }

            PotatoCard {
                SectionTitle("VALEURS PAR DÉFAUT")
                QualityChips(quality) { quality = it }
                Spacer(Modifier.height(14.dp))
                Row {
                    Text("Spectateurs maximum", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Stepper(maxViewers, { maxViewers = it }, 1, 8)
                }
            }

            PotatoCard {
                SectionTitle("AUTORISATIONS ANDROID")
                Text("Contrôle tactile et clavier distant", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (accessibilityReady) "Service d'accessibilité actif."
                    else "Service d'accessibilité inactif : le contrôle et le clavier sont indisponibles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (accessibilityReady) Sage else BrownSoft,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text(if (accessibilityReady) "Gérer l'accessibilité" else "Activer l'accessibilité") }
                Spacer(Modifier.height(6.dp))
                InfoNote(
                    "Android interdit l'injection de gestes sans consentement. Potato Remote n'accède ni à vos mots de passe ni au contenu de vos champs : il exécute uniquement les gestes et textes envoyés par un spectateur autorisé.",
                    Sage,
                )
            }

            PotatoCard {
                SectionTitle("À PROPOS")
                Text("Potato Remote ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Partage d'écran WebRTC avec contrôle tactile et clavier distant. Aucun compte, aucun serveur tiers : seule votre installation PHP fait la signalisation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BrownSoft,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Limites connues : les champs protégés et certaines applications refusent la saisie distante ; sans serveur TURN, les connexions entre réseaux très restrictifs peuvent échouer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BrownSoft,
                )
            }

            PrimaryButton(
                "Enregistrer les réglages",
                onClick = {
                    prefs.serverUrl = Prefs.normalizeServer(server)
                    prefs.serverKey = serverKey
                    prefs.deviceName = deviceName
                    prefs.quality = quality
                    prefs.maxViewers = maxViewers
                    if (ShareState.phase.value == com.potatoremote.ui.SharePhase.LIVE) {
                        ShareService.setQuality(quality)
                        ShareService.setMaxViewers(maxViewers)
                    }
                    onBack()
                },
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}
