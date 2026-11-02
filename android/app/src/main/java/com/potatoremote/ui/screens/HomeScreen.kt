package com.potatoremote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.potatoremote.settings.Prefs
import com.potatoremote.ui.components.BrandHeader
import com.potatoremote.ui.components.InfoNote
import com.potatoremote.ui.components.PotatoScreenSurface
import com.potatoremote.ui.components.Pill
import com.potatoremote.ui.components.PotatoCard
import com.potatoremote.ui.components.PotatoLogo
import com.potatoremote.ui.components.PrimaryButton
import com.potatoremote.ui.components.SectionTitle
import com.potatoremote.ui.theme.Accent
import com.potatoremote.ui.theme.Brown
import com.potatoremote.ui.theme.BrownSoft
import com.potatoremote.ui.theme.Sage

@Composable
fun HomeScreen(
    prefs: Prefs,
    onStart: () -> Unit,
    onSettings: () -> Unit,
) {
    PotatoScreenSurface {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            BrandHeader("Partage d'écran Android", onSettings)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Pill("Privé · sans compte", Sage, dot = true)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Votre téléphone,\nsur grand écran.",
                        style = MaterialTheme.typography.displaySmall,
                        color = Brown,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Partagez votre écran en temps réel et contrôlez-le depuis un iPad, un ordinateur ou un autre téléphone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = BrownSoft,
                    )
                }
                Spacer(Modifier.width(12.dp))
                PotatoLogo(104.dp)
            }

            if (prefs.serverUrl.isBlank()) {
                PotatoCard {
                    Pill("À configurer", Accent, dot = true)
                    Spacer(Modifier.height(10.dp))
                    Text("Indiquez l'adresse de votre site Potato Remote", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "Le serveur PHP hébergé sur o2switch crée les sessions et sécurise la connexion WebRTC.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BrownSoft,
                    )
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton("Ouvrir les réglages", onSettings, modifier = Modifier.height(48.dp))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill("Serveur prêt", Sage, dot = true)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        prefs.serverUrl.removePrefix("https://").removePrefix("http://"),
                        style = MaterialTheme.typography.bodySmall,
                        color = BrownSoft,
                        maxLines = 1,
                    )
                }
            }

            PrimaryButton("Démarrer le partage", onStart)

            PotatoCard {
                SectionTitle("COMMENT ÇA MARCHE")
                StepRow("1", "Créez une session", "Choisissez un nom et un mot de passe. Android demande l'autorisation de partage d'écran.")
                StepRow("2", "Ouvrez le site sur le récepteur", "iPad, Mac, PC ou autre téléphone : entrez le nom et le mot de passe.")
                StepRow("3", "Contrôlez si vous le souhaitez", "Le tactile et le clavier s'activent depuis ce téléphone, et se coupent à tout instant.")
            }

            InfoNote(
                "Le contrôle tactile et le clavier distant utilisent le service d'accessibilité Android. Ils sont facultatifs, visibles et révocables.",
                Sage,
            )

            Text(
                "Potato Remote 1.0 · WebRTC chiffré de bout en bout",
                style = MaterialTheme.typography.bodySmall,
                color = BrownSoft.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun StepRow(number: String, title: String, text: String) {
    Row(Modifier.padding(vertical = 7.dp)) {
        Text(
            number,
            style = MaterialTheme.typography.labelLarge,
            color = Accent,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(22.dp),
        )
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodySmall, color = BrownSoft)
        }
    }
}
