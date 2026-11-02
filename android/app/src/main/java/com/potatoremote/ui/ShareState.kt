package com.potatoremote.ui

import com.potatoremote.signaling.CaptureQuality
import com.potatoremote.signaling.LinkStats
import com.potatoremote.signaling.RemoteSettings
import com.potatoremote.signaling.RemoteViewer
import kotlinx.coroutines.flow.MutableStateFlow

enum class SharePhase { IDLE, STARTING, LIVE, ERROR }

data class UiSession(
    val id: String,
    val name: String,
    val viewerUrl: String,
    val expiresAt: Long,
)

data class UiActivity(
    val timestamp: Long,
    val text: String,
    val kind: String = "info",
)

/**
 * État observable partagé entre le service de partage et l'interface Compose.
 * Source de vérité unique : le service met à jour, l'UI lit.
 */
object ShareState {
    val phase = MutableStateFlow(SharePhase.IDLE)
    val session = MutableStateFlow<UiSession?>(null)
    val viewers = MutableStateFlow<List<RemoteViewer>>(emptyList())
    val activity = MutableStateFlow<List<UiActivity>>(emptyList())
    val settings = MutableStateFlow(RemoteSettings())
    val quality = MutableStateFlow(CaptureQuality.AUTO)
    val error = MutableStateFlow<String?>(null)
    val startMessage = MutableStateFlow("Connexion au serveur…")
    val accessibilityReady = MutableStateFlow(false)
    val controlActive = MutableStateFlow(false)
    val keyboardActive = MutableStateFlow(false)
    val turnConfigured = MutableStateFlow(true)
    val lastViewerStats = MutableStateFlow<LinkStats?>(null)

    fun reset() {
        phase.value = SharePhase.IDLE
        session.value = null
        viewers.value = emptyList()
        controlActive.value = false
        keyboardActive.value = false
        lastViewerStats.value = null
        startMessage.value = "Connexion au serveur…"
    }

    fun log(text: String, kind: String = "info") {
        val entry = UiActivity(System.currentTimeMillis(), text, kind)
        activity.value = (listOf(entry) + activity.value).take(24)
    }
}
