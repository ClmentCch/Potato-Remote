package com.potatoremote.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.potatoremote.MainActivity
import com.potatoremote.PotatoApp
import com.potatoremote.R
import com.potatoremote.control.AccessibilityBridge
import com.potatoremote.signaling.ApiClient
import com.potatoremote.signaling.ApiException
import com.potatoremote.signaling.CaptureQuality
import com.potatoremote.signaling.IceConfig
import com.potatoremote.signaling.PhoneSnapshot
import com.potatoremote.signaling.RemoteSettings
import com.potatoremote.signaling.ScreenInfo
import com.potatoremote.signaling.SignalMessage
import com.potatoremote.signaling.Viewport
import com.potatoremote.settings.Prefs
import com.potatoremote.ui.SharePhase
import com.potatoremote.ui.ShareState
import com.potatoremote.ui.UiSession
import com.potatoremote.webrtc.ScreenCaptureEngine
import com.potatoremote.webrtc.ViewerSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

class ShareService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: Prefs
    private var api: ApiClient? = null
    private var phoneToken: String? = null
    private var sessionName: String = ""
    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var projection: MediaProjection? = null
    private var engine: ScreenCaptureEngine? = null
    private var iceConfigs: List<IceConfig> = emptyList()
    private val viewers = ConcurrentHashMap<String, ViewerSession>()
    private val controlViewers: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val missingViewers = ConcurrentHashMap<String, Int>()
    private var pollJob: Job? = null
    private var heartbeatJob: Job? = null
    private var cursor = 0L
    private var running = false
    private var stopping = false
    private var lastToastAt = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            scope.launch { stopSharing("Le partage d'écran a été arrêté sur le téléphone.", endServerSession = true) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        instance = this
        ShareState.accessibilityReady.value = AccessibilityBridge.isReady
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (running) return START_NOT_STICKY
                running = true
                startForegroundSafely("Préparation du partage…")
                ShareState.reset()
                ShareState.phase.value = SharePhase.STARTING
                ShareState.accessibilityReady.value = AccessibilityBridge.isReady
                val config = StartConfig(
                    server = intent.getStringExtra(EXTRA_SERVER).orEmpty(),
                    name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
                    password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty(),
                    control = intent.getBooleanExtra(EXTRA_CONTROL, true),
                    keyboard = intent.getBooleanExtra(EXTRA_KEYBOARD, true),
                    quality = intent.getStringExtra(EXTRA_QUALITY) ?: "auto",
                    maxViewers = intent.getIntExtra(EXTRA_MAX_VIEWERS, 4),
                    serverKey = intent.getStringExtra(EXTRA_SERVER_KEY).orEmpty(),
                )
                scope.launch { startSharing(config) }
            }
            ACTION_STOP -> scope.launch { stopSharing("Partage arrêté.", endServerSession = true) }
            else -> {
                if (!running) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundSafely(text: String) {
        val notification = buildNotification("Potato Remote", text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun startSharing(config: StartConfig) {
        try {
            if (!Prefs.isValidServer(config.server)) {
                throw ApiException("bad_server", "Adresse du serveur invalide. Utilisez une URL HTTPS.")
            }
            if (config.name.trim().length < 3) {
                throw ApiException("invalid_session_name", "Le nom de session doit contenir au moins 3 caractères.")
            }
            if (config.password.length < 4) {
                throw ApiException("invalid_password", "Le mot de passe doit contenir au moins 4 caractères.")
            }

            ShareState.startMessage.value = "Validation du serveur…"
            val client = ApiClient(Prefs.normalizeServer(config.server))
            api = client
            client.ping()

            val consent = ProjectionConsent.take()
                ?: throw IllegalStateException("L'autorisation de partage d'écran a expiré. Recommencez.")
            val manager = getSystemService(MediaProjectionManager::class.java)
            val mediaProjection = manager.getMediaProjection(consent.resultCode, consent.data)
                ?: throw IllegalStateException("Android n'a pas accordé le partage d'écran.")
            projection = mediaProjection
            mediaProjection.registerCallback(projectionCallback, mainHandler)

            ShareState.startMessage.value = "Démarrage de la capture d'écran…"
            val quality = CaptureQuality.from(config.quality)
            val capture = ScreenCaptureEngine(this, mediaProjection, quality)
            engine = capture
            capture.start()
            ShareState.quality.value = quality

            ShareState.startMessage.value = "Négociation vidéo…"
            val rootEgl = EglBase.create()
            eglBase = rootEgl
            val peerFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(rootEgl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(rootEgl.eglBaseContext))
                .createPeerConnectionFactory()
            factory = peerFactory

            ShareState.startMessage.value = "Création de la session…"
            val settings = RemoteSettings(
                control = config.control,
                keyboard = config.keyboard,
                allowNewViewers = true,
                maxViewers = config.maxViewers.coerceIn(1, 8),
                quality = quality.key,
            )
            val device = prefs.deviceName
            val screen = ScreenInfo.current(this)
            val resumeToken = prefs.phoneToken?.takeIf { prefs.sessionName.equals(config.name, ignoreCase = true) }
            val created = client.createSession(
                config.name, config.password, device, screen, settings, resumeToken, config.serverKey
            )

            phoneToken = created.phoneToken
            cursor = created.cursor
            sessionName = created.name
            iceConfigs = created.iceServers

            prefs.serverUrl = config.server
            prefs.sessionName = created.name
            prefs.phoneToken = created.phoneToken
            prefs.quality = quality.key
            prefs.maxViewers = settings.maxViewers
            prefs.allowControl = config.control
            prefs.allowKeyboard = config.keyboard

            ShareState.settings.value = settings
            ShareState.turnConfigured.value = created.turnConfigured
            ShareState.session.value = UiSession(created.sessionId, created.name, created.viewerUrl, created.expiresAt)
            ShareState.phase.value = SharePhase.LIVE
            ShareState.startMessage.value = ""
            ShareState.log(
                if (resumeToken != null) "Session « ${created.name} » reprise" else "Session « ${created.name} » créée",
                "success"
            )
            ShareState.log(
                if (created.turnConfigured) "Relais TURN disponible pour les réseaux distants"
                else "TURN non configuré : hors réseau local, la connexion peut échouer",
                if (created.turnConfigured) "info" else "warn"
            )
            updateNotification()
            startPolling(client, created.phoneToken)
            startHeartbeat(client, created.phoneToken)
        } catch (error: Throwable) {
            Log.e(TAG, "Démarrage impossible", error)
            val message = when (error) {
                is ApiException -> friendly(error)
                else -> error.message ?: "Impossible de démarrer le partage."
            }
            fail(message)
        }
    }

    private fun startPolling(client: ApiClient, token: String) {
        pollJob?.cancel()
        pollJob = scope.launch {
            var backoff = 900L
            while (isActive && running) {
                try {
                    val result = client.poll(token, cursor, 18)
                    cursor = result.cursor
                    applySettingsFromServer(result.settings)
                    for (message in result.messages) {
                        if (!running) break
                        handleSignal(client, token, message)
                    }
                    backoff = 900L
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: ApiException) {
                    if (error.code == "invalid_token" || error.code == "session_ended") {
                        stopSharing("La session a expiré ou a été arrêtée.", endServerSession = false, isError = true)
                        return@launch
                    }
                    delay(backoff)
                    backoff = min(backoff * 2, 8000L)
                } catch (error: Throwable) {
                    delay(backoff)
                    backoff = min(backoff * 2, 8000L)
                }
            }
        }
    }

    private fun startHeartbeat(client: ApiClient, token: String) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && running) {
                try {
                    val snapshot = client.status(token, ScreenInfo.current(this@ShareService))
                    applySnapshot(snapshot)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: ApiException) {
                    if (error.code == "invalid_token" || error.code == "session_ended") {
                        stopSharing("La session a expiré ou a été arrêtée.", endServerSession = false, isError = true)
                        return@launch
                    }
                } catch (_: Throwable) {
                    // Réseau instable : on retentera dans cinq secondes.
                }
                delay(5000)
            }
        }
    }

    private fun handleSignal(client: ApiClient, token: String, message: SignalMessage) {
        val viewerId = message.viewerId ?: return
        when (message.type) {
            "offer" -> {
                val sdp = message.payload.optString("sdp", "")
                if (sdp.isBlank()) return
                viewers.remove(viewerId)?.close()
                val currentFactory = factory ?: return
                val currentEngine = engine ?: return
                val label = ShareState.viewers.value.firstOrNull { it.id == viewerId }?.label ?: "Appareil distant"
                val session = ViewerSession(
                    id = viewerId,
                    label = label,
                    factory = currentFactory,
                    iceConfigs = iceConfigs,
                    engine = currentEngine,
                    api = client,
                    phoneToken = token,
                    scope = scope,
                    settingsProvider = { ShareState.settings.value },
                    qualityProvider = { ShareState.quality.value },
                    initialViewport = Viewport.fromJson(message.payload.optJSONObject("viewport")),
                    initialMode = ScreenCaptureEngine.FitMode.from(message.payload.optString("mode")),
                    onLog = { text, kind -> ShareState.log(text, kind) },
                    onControlChanged = { id, active ->
                        if (active) controlViewers.add(id) else controlViewers.remove(id)
                        ShareState.controlActive.value = controlViewers.isNotEmpty()
                        updateNotification()
                    },
                    onKeyboardActivity = { id -> showActivityToast(id) },
                    onClosed = { id ->
                        viewers.remove(id)
                        missingViewers.remove(id)
                        controlViewers.remove(id)
                        ShareState.controlActive.value = controlViewers.isNotEmpty()
                        updateNotification()
                    },
                )
                viewers[viewerId] = session
                session.start(sdp)
                updateNotification()
            }
            "ice" -> viewers[viewerId]?.addRemoteIce(message.payload)
            "bye" -> {
                viewers.remove(viewerId)?.close()
                controlViewers.remove(viewerId)
                ShareState.controlActive.value = controlViewers.isNotEmpty()
                updateNotification()
            }
        }
    }

    private fun applySnapshot(snapshot: PhoneSnapshot) {
        ShareState.viewers.value = snapshot.viewers
        ShareState.turnConfigured.value = snapshot.turnConfigured
        ShareState.lastViewerStats.value = snapshot.viewers.firstOrNull { it.online && it.stats != null }?.stats
        applySettingsFromServer(snapshot.settings)

        val now = System.currentTimeMillis()
        ShareState.keyboardActive.value = snapshot.viewers.any { viewer ->
            viewer.keyboardUsedAt != null && now - viewer.keyboardUsedAt < 15000
        }

        val activeIds = snapshot.viewers.map { it.id }.toSet()
        viewers.keys.filter { it in activeIds }.forEach { missingViewers.remove(it) }
        viewers.keys.filter { it !in activeIds }.forEach { id ->
            val misses = (missingViewers[id] ?: 0) + 1
            if (misses >= 2) {
                missingViewers.remove(id)
                viewers.remove(id)?.close()
                controlViewers.remove(id)
            } else {
                missingViewers[id] = misses
            }
        }
        ShareState.controlActive.value = controlViewers.isNotEmpty()
        updateNotification()
    }

    private fun applySettingsFromServer(settings: RemoteSettings) {
        val current = ShareState.settings.value
        if (current == settings) return
        ShareState.settings.value = settings
        val quality = CaptureQuality.from(settings.quality)
        if (ShareState.quality.value != quality) {
            ShareState.quality.value = quality
            engine?.setQuality(quality)
            viewers.values.forEach { it.applyQuality() }
        }
        updateNotification()
    }

    private fun changeSettings(
        control: Boolean? = null,
        keyboard: Boolean? = null,
        allowNewViewers: Boolean? = null,
        maxViewers: Int? = null,
        quality: String? = null,
    ) {
        val current = ShareState.settings.value
        val next = current.copy(
            control = control ?: current.control,
            keyboard = keyboard ?: current.keyboard,
            allowNewViewers = allowNewViewers ?: current.allowNewViewers,
            maxViewers = (maxViewers ?: current.maxViewers).coerceIn(1, 8),
            quality = quality ?: current.quality,
        )
        ShareState.settings.value = next
        ShareState.controlActive.value = if (!next.control) false else ShareState.controlActive.value
        if (!next.control) controlViewers.clear()

        if (quality != null) {
            val parsed = CaptureQuality.from(next.quality)
            ShareState.quality.value = parsed
            prefs.quality = parsed.key
            engine?.setQuality(parsed)
            viewers.values.forEach { it.applyQuality() }
        }
        if (maxViewers != null) prefs.maxViewers = next.maxViewers
        if (control != null) prefs.allowControl = control
        if (keyboard != null) prefs.allowKeyboard = keyboard
        updateNotification()

        val patch = LinkedHashMap<String, Any?>()
        if (control != null) patch["control"] = control
        if (keyboard != null) patch["keyboard"] = keyboard
        if (allowNewViewers != null) patch["allow_new_viewers"] = allowNewViewers
        if (maxViewers != null) patch["max_viewers"] = next.maxViewers
        if (quality != null) patch["quality"] = next.quality
        if (patch.isEmpty()) return

        val client = api ?: return
        val token = phoneToken ?: return
        scope.launch {
            try {
                val snapshot = client.update(token, patch)
                applySnapshot(snapshot)
            } catch (error: Throwable) {
                Log.w(TAG, "Réglage non synchronisé", error)
                ShareState.log("Réglage non synchronisé avec le serveur", "warn")
            }
        }
    }

    private fun kickViewer(viewerId: String) {
        val client = api ?: return
        val token = phoneToken ?: return
        scope.launch {
            try {
                client.update(token, mapOf("kick" to viewerId))
                viewers.remove(viewerId)?.close()
                controlViewers.remove(viewerId)
                ShareState.controlActive.value = controlViewers.isNotEmpty()
                ShareState.log("Spectateur retiré de la session", "warn")
                updateNotification()
            } catch (error: Throwable) {
                ShareState.log("Impossible de retirer le spectateur", "error")
            }
        }
    }

    private fun showActivityToast(viewerId: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastToastAt < 20000) return
        lastToastAt = now
        val label = ShareState.viewers.value.firstOrNull { it.id == viewerId }?.label ?: "Un spectateur"
        mainHandler.post {
            Toast.makeText(this, "$label utilise le clavier distant", Toast.LENGTH_SHORT).show()
        }
    }

    private fun fail(message: String) {
        Log.w(TAG, "Échec du partage : $message")
        running = false
        ShareState.phase.value = SharePhase.ERROR
        ShareState.error.value = message
        ShareState.startMessage.value = ""
        ShareState.log(message, "error")
        releaseResources(stopProjection = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun stopSharing(reason: String, endServerSession: Boolean, isError: Boolean = false) {
        if (stopping) return
        stopping = true
        running = false
        val client = api
        val token = phoneToken

        pollJob?.cancel()
        heartbeatJob?.cancel()
        viewers.values.forEach { it.close() }
        viewers.clear()
        missingViewers.clear()
        controlViewers.clear()
        ShareState.controlActive.value = false
        ShareState.keyboardActive.value = false
        releaseResources(stopProjection = true)

        if (endServerSession && client != null && token != null) {
            try {
                client.stop(token)
            } catch (error: Throwable) {
                Log.w(TAG, "Session distante non arrêtée", error)
            }
        }

        prefs.clearSession()
        ShareState.reset()
        ShareState.phase.value = SharePhase.IDLE
        ShareState.log(reason, if (isError) "error" else "info")
        ShareState.error.value = if (isError) reason else null

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        stopping = false
    }

    private fun releaseResources(stopProjection: Boolean) {
        try {
            engine?.stop()
        } catch (error: Throwable) {
            Log.w(TAG, "Arrêt de la capture", error)
        }
        engine = null

        try {
            projection?.unregisterCallback(projectionCallback)
            if (stopProjection) projection?.stop()
        } catch (error: Throwable) {
            Log.w(TAG, "Arrêt de la projection", error)
        }
        projection = null

        try {
            factory?.dispose()
        } catch (_: Throwable) {
        }
        factory = null

        try {
            eglBase?.release()
        } catch (_: Throwable) {
        }
        eglBase = null
        ProjectionConsent.clear()
    }

    private fun updateNotification() {
        if (!running) return
        val session = ShareState.session.value
        val title = session?.name ?: "Potato Remote"
        val text = when {
            ShareState.controlActive.value -> "Contrôle tactile actif · ${viewers.size} spectateur(s)"
            ShareState.phase.value == SharePhase.STARTING -> ShareState.startMessage.value
            else -> "Diffusion en cours · ${viewers.size} spectateur(s)"
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(title, text))
        } catch (error: Throwable) {
            Log.w(TAG, "Notification non mise à jour", error)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, ShareService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val session = ShareState.session.value
        val bigText = buildString {
            append(text)
            if (session != null && session.viewerUrl.isNotBlank()) {
                append("\nRejoindre : ")
                append(session.viewerUrl)
            }
        }
        return NotificationCompat.Builder(this, PotatoApp.CHANNEL_SHARING)
            .setSmallIcon(R.drawable.ic_stat_potato)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notification_stop), stopPending)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        if (running) {
            running = false
            viewers.values.forEach { it.close() }
            viewers.clear()
            releaseResources(stopProjection = true)
        }
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun friendly(error: ApiException): String = when (error.code) {
        "name_taken" -> "Ce nom de session est déjà utilisé. Choisissez un autre nom ou attendez son expiration."
        "invalid_password" -> "Le mot de passe doit contenir au moins 4 caractères."
        "invalid_session_name" -> "Le nom de session doit contenir 3 à 32 caractères."
        "rate_limited" -> "Trop de tentatives. Réessayez dans ${max(1, error.retryAfter / 60)} min."
        "invalid_server_key" -> "Ce serveur exige une clé d'accès."
        "https_required" -> "Le serveur doit être utilisé en HTTPS."
        "network" -> "Serveur injoignable. Vérifiez l'adresse et votre connexion."
        "create_disabled" -> "La création de session est désactivée sur ce serveur."
        else -> error.message ?: "Erreur serveur."
    }

    data class StartConfig(
        val server: String,
        val name: String,
        val password: String,
        val control: Boolean,
        val keyboard: Boolean,
        val quality: String,
        val maxViewers: Int,
        val serverKey: String = "",
    )

    companion object {
        private const val TAG = "PotatoService"
        private const val NOTIFICATION_ID = 4210
        const val ACTION_START = "com.potatoremote.action.START"
        const val ACTION_STOP = "com.potatoremote.action.STOP"
        private const val EXTRA_SERVER = "server"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_PASSWORD = "password"
        private const val EXTRA_CONTROL = "control"
        private const val EXTRA_KEYBOARD = "keyboard"
        private const val EXTRA_QUALITY = "quality"
        private const val EXTRA_MAX_VIEWERS = "max_viewers"
        private const val EXTRA_SERVER_KEY = "server_key"

        @Volatile
        var instance: ShareService? = null
            private set

        fun start(context: Context, config: StartConfig) {
            val intent = Intent(context, ShareService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SERVER, config.server)
                putExtra(EXTRA_NAME, config.name)
                putExtra(EXTRA_PASSWORD, config.password)
                putExtra(EXTRA_CONTROL, config.control)
                putExtra(EXTRA_KEYBOARD, config.keyboard)
                putExtra(EXTRA_QUALITY, config.quality)
                putExtra(EXTRA_MAX_VIEWERS, config.maxViewers)
                putExtra(EXTRA_SERVER_KEY, config.serverKey)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ShareService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun setControl(value: Boolean) {
            instance?.changeSettings(control = value)
        }

        fun setKeyboard(value: Boolean) {
            instance?.changeSettings(keyboard = value)
        }

        fun setAllowNewViewers(value: Boolean) {
            instance?.changeSettings(allowNewViewers = value)
        }

        fun setMaxViewers(value: Int) {
            instance?.changeSettings(maxViewers = value)
        }

        fun setQuality(value: String) {
            instance?.changeSettings(quality = value)
        }

        fun kick(viewerId: String) {
            instance?.kickViewer(viewerId)
        }
    }
}
