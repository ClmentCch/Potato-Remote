package com.potatoremote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.potatoremote.service.ShareService
import com.potatoremote.settings.Prefs
import com.potatoremote.ui.SharePhase
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
import com.potatoremote.ui.components.ToggleRow
import com.potatoremote.ui.theme.Danger
import com.potatoremote.ui.theme.Sage

@Composable
fun CreateScreen(
    prefs: Prefs,
    onBack: () -> Unit,
    onStart: (ShareService.StartConfig) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(prefs.sessionName.ifBlank { prefs.deviceName }) }
    var password by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf(prefs.serverUrl) }
    var serverKey by rememberSaveable { mutableStateOf(prefs.serverKey) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var control by rememberSaveable { mutableStateOf(prefs.allowControl) }
    var keyboard by rememberSaveable { mutableStateOf(prefs.allowKeyboard) }
    var quality by rememberSaveable { mutableStateOf(prefs.quality) }
    var maxViewers by rememberSaveable { mutableStateOf(prefs.maxViewers) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    val phase by ShareState.phase.collectAsStateWithLifecycle()
    val accessibilityReady by ShareState.accessibilityReady.collectAsStateWithLifecycle()

    PotatoScreenSurface {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenHeader(
                "Nouvelle session",
                "Le téléphone est la source du partage.",
                onBack,
            )

            PotatoCard {
                LabeledField(
                    value = name,
                    onValueChange = { name = it.take(32) },
                    label = "Nom de session",
                    placeholder = "ClementPhone",
                    keyboardType = KeyboardType.Text,
                )
                Spacer(Modifier.height(14.dp))
                LabeledField(
                    value = password,
                    onValueChange = { password = it.take(128) },
                    label = "Mot de passe",
                    placeholder = "Au moins 4 caractères",
                    keyboardType = KeyboardType.Password,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = {
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(if (showPassword) "Masquer" else "Afficher", style = MaterialTheme.typography.labelMedium)
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Le spectateur devra saisir ces deux informations sur le site. Le mot de passe n'est jamais enregistré sur le téléphone.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            PotatoCard {
                SectionTitle("CONTRÔLE DU TÉLÉPHONE")
                ToggleRow(
                    "Contrôle tactile",
                    "Autoriser les clics, glissements et appuis longs.",
                    control,
                    { control = it },
                )
                Spacer(Modifier.height(12.dp))
                ToggleRow(
                    "Clavier distant",
                    "Autoriser la saisie de texte dans le champ actif.",
                    keyboard,
                    { keyboard = it },
                )
                if ((control || keyboard) && !accessibilityReady) {
                    Spacer(Modifier.height(12.dp))
                    InfoNote(
                        "Le service d'accessibilité Potato Remote n'est pas actif : le partage vidéo fonctionnera, mais pas le contrôle ni le clavier. Activez-le dans les réglages Android.",
                        Sage,
                    )
                }
            }

            PotatoCard {
                SectionTitle("QUALITÉ VIDÉO")
                QualityChips(quality) { quality = it }
                Spacer(Modifier.height(8.dp))
                Text(
                    "La résolution s'adapte au format de chaque spectateur. « Auto » privilégie 720p.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(16.dp))
                SectionTitle("SPECTATEURS MAXIMUM")
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        "Connexions simultanées",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Stepper(maxViewers, { maxViewers = it }, 1, 8)
                }
            }

            PotatoCard {
                SectionTitle("SERVEUR POTATO REMOTE")
                LabeledField(
                    value = server,
                    onValueChange = { server = it },
                    label = "Adresse HTTPS",
                    placeholder = "https://potato.mondomaine.fr",
                    keyboardType = KeyboardType.Uri,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Le site PHP créé sur o2switch. Il sert uniquement à authentifier et à négocier la connexion ; la vidéo passe par WebRTC.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                LabeledField(
                    value = serverKey,
                    onValueChange = { serverKey = it },
                    label = "Clé serveur (optionnelle)",
                    placeholder = "Laisser vide si le serveur est public",
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "À renseigner uniquement si create_secret est défini dans config.php.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (error != null) {
                InfoNote(error!!, Danger)
            }

            PrimaryButton(
                text = if (phase == SharePhase.STARTING) "Démarrage…" else "Démarrer le partage",
                onClick = {
                    val cleanedName = name.trim()
                    val cleanedServer = Prefs.normalizeServer(server)
                    error = when {
                        !Prefs.isValidServer(cleanedServer) -> "Indiquez une adresse de serveur HTTPS valide."
                        cleanedName.length < 3 -> "Le nom de session doit contenir au moins 3 caractères."
                        !Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N} ._-]*$").matches(cleanedName) ->
                            "Le nom de session ne peut contenir que lettres, chiffres, espaces, - _ ."
                        password.length < 4 -> "Le mot de passe doit contenir au moins 4 caractères."
                        else -> null
                    }
                    if (error == null) {
                        prefs.serverUrl = cleanedServer
                        prefs.serverKey = serverKey
                        prefs.sessionName = cleanedName
                        prefs.allowControl = control
                        prefs.allowKeyboard = keyboard
                        prefs.quality = quality
                        prefs.maxViewers = maxViewers
                        onStart(
                            ShareService.StartConfig(
                                server = cleanedServer,
                                name = cleanedName,
                                password = password,
                                control = control,
                                keyboard = keyboard,
                                quality = quality,
                                maxViewers = maxViewers,
                                serverKey = serverKey.trim(),
                            )
                        )
                    }
                },
                loading = phase == SharePhase.STARTING,
            )

            Text(
                "Android affichera une demande d'autorisation de capture d'écran. Rien n'est partagé avant votre accord.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
    }
}
