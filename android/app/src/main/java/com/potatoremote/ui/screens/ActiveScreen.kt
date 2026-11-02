package com.potatoremote.ui.screens

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.potatoremote.service.ShareService
import com.potatoremote.signaling.LinkStats
import com.potatoremote.signaling.RemoteSettings
import com.potatoremote.signaling.RemoteViewer
import com.potatoremote.signaling.Viewport
import com.potatoremote.ui.ShareState
import com.potatoremote.ui.UiActivity
import com.potatoremote.ui.UiSession
import com.potatoremote.ui.components.BrandHeader
import com.potatoremote.ui.components.InfoNote
import com.potatoremote.ui.components.PotatoScreenSurface
import com.potatoremote.ui.components.Pill
import com.potatoremote.ui.components.PotatoCard
import com.potatoremote.ui.components.PrimaryButton
import com.potatoremote.ui.components.PotatoLogo
import com.potatoremote.ui.components.QualityChips
import com.potatoremote.ui.components.SectionTitle
import com.potatoremote.ui.components.ToggleRow
import com.potatoremote.ui.theme.Accent
import com.potatoremote.ui.theme.Brown
import com.potatoremote.ui.theme.BrownSoft
import com.potatoremote.ui.theme.Danger
import com.potatoremote.ui.theme.Sage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ActiveScreen(
    session: UiSession,
    settings: RemoteSettings,
    viewers: List<RemoteViewer>,
    activity: List<UiActivity>,
    controlActive: Boolean,
    keyboardActive: Boolean,
    accessibilityReady: Boolean,
    lastStats: LinkStats?,
    onSettings: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val timeFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)

    PotatoScreenSurface {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BrandHeader("Partage en cours", onSettings)

            PotatoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(Sage)
                    )
                    Spacer(Modifier.width(9.dp))
                    Text("SESSION ACTIVE", style = MaterialTheme.typography.labelSmall, color = Sage)
                }
                Spacer(Modifier.height(10.dp))
                Text(session.name, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Expire à ${timeFormat.format(Date(session.expiresAt))} · ${viewers.size} spectateur(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = BrownSoft,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    session.viewerUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = Brown,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(session.viewerUrl))
                            Toast.makeText(context, "Lien de session copié", Toast.LENGTH_SHORT).show()
                        }
                    ) { Text("Copier le lien") }
                    TextButton(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "Rejoins ma session Potato Remote « ${session.name} » : ${session.viewerUrl}"
                                )
                            }
                            context.startActivity(Intent.createChooser(send, "Partager le lien"))
                        }
                    ) { Text("Partager") }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                StatBlock(
                    if (viewers.isEmpty()) "Aucun" else "${viewers.size}",
                    "spectateur(s)",
                    Modifier.weight(1f),
                )
                StatBlock(
                    if (lastStats != null && lastStats.rtt > 0) "${lastStats.rtt / 2} ms" else "—",
                    "latence",
                    Modifier.weight(1f),
                )
                StatBlock(
                    if (lastStats != null && lastStats.height > 0) "${lastStats.height}p" else "—",
                    "flux",
                    Modifier.weight(1f),
                )
            }

            PotatoCard {
                SectionTitle("AUTORISATIONS")
                ToggleRow(
                    "Contrôle tactile",
                    if (controlActive) "Un spectateur contrôle actuellement l'écran." else "Clics, glissements et appuis longs.",
                    settings.control,
                    { ShareService.setControl(it) },
                )
                Spacer(Modifier.height(12.dp))
                ToggleRow(
                    "Clavier distant",
                    if (keyboardActive) "Saisie en cours depuis un spectateur." else "Saisie dans le champ actif du téléphone.",
                    settings.keyboard,
                    { ShareService.setKeyboard(it) },
                )
                if (!accessibilityReady) {
                    Spacer(Modifier.height(12.dp))
                    InfoNote(
                        "Accessibilité inactive : la vidéo fonctionne, le contrôle et le clavier non. Activez « Potato Remote » dans les services d'accessibilité Android.",
                        Accent,
                    )
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) { Text("Ouvrir les réglages d'accessibilité") }
                }
            }

            PotatoCard {
                SectionTitle("QUALITÉ DE DIFFUSION")
                QualityChips(settings.quality) { ShareService.setQuality(it) }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Spectateurs maximum", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("${settings.maxViewers}", style = MaterialTheme.typography.titleMedium)
                }
            }

            PotatoCard {
                SectionTitle("APPAREILS CONNECTÉS")
                if (viewers.isEmpty()) {
                    Text(
                        "En attente d'un spectateur. Ouvrez le site Potato Remote et connectez-vous avec le nom et le mot de passe.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BrownSoft,
                    )
                } else {
                    viewers.forEachIndexed { index, viewer ->
                        if (index > 0) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp)
                                    .height(1.dp)
                                    .background(Brown.copy(alpha = 0.07f))
                            )
                        }
                        ViewerRow(viewer, onKick = { ShareService.kick(viewer.id) })
                    }
                }
            }

            if (activity.isNotEmpty()) {
                PotatoCard {
                    SectionTitle("ACTIVITÉ RÉCENTE")
                    activity.take(6).forEach { entry ->
                        Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                            Box(
                                Modifier
                                    .padding(top = 6.dp)
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(activityColor(entry.kind))
                            )
                            Spacer(Modifier.width(9.dp))
                            Text(
                                timeFormat.format(Date(entry.timestamp)),
                                style = MaterialTheme.typography.bodySmall,
                                color = BrownSoft,
                                modifier = Modifier.width(42.dp),
                            )
                            Text(entry.text, style = MaterialTheme.typography.bodySmall, color = Brown)
                        }
                    }
                }
            }

            PrimaryButton("Arrêter le partage", onStop, destructive = true)

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                PotatoLogo(26.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "La notification « Partage en cours » permet d'arrêter à tout moment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BrownSoft,
                )
            }
        }
    }
}

@Composable
private fun StatBlock(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(Brown.copy(alpha = 0.045f))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = Brown)
        Text(label, style = MaterialTheme.typography.labelSmall, color = BrownSoft)
    }
}

@Composable
private fun ViewerRow(viewer: RemoteViewer, onKick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (viewer.online) Sage else BrownSoft.copy(alpha = 0.4f))
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(viewer.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = buildList {
                add(if (viewer.online) "En ligne" else "Hors ligne")
                viewer.viewport?.let { add(aspectLabel(it)) }
                viewer.stats?.let { stats ->
                    if (stats.height > 0) add("${stats.height}p")
                    if (stats.fps > 0) add("${stats.fps} i/s")
                }
            }.joinToString(" · ")
            Text(details, style = MaterialTheme.typography.bodySmall, color = BrownSoft)
        }
        TextButton(onClick = onKick) {
            Text("Retirer", color = Danger, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun aspectLabel(viewport: Viewport): String = when {
    viewport.aspect > 1.65 -> "16:9"
    viewport.aspect > 1.3 -> "4:3"
    viewport.aspect > 1.05 -> "paysage"
    viewport.aspect > 0.85 -> "carré"
    viewport.aspect > 0.65 -> "3:4"
    else -> "portrait"
}

private fun activityColor(kind: String) = when (kind) {
    "success" -> Sage
    "warn", "control", "keyboard" -> Accent
    "error" -> Danger
    else -> BrownSoft
}
