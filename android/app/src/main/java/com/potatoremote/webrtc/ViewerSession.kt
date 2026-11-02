package com.potatoremote.webrtc

import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.potatoremote.control.AccessibilityBridge
import com.potatoremote.control.TextInjector
import com.potatoremote.signaling.ApiClient
import com.potatoremote.signaling.CaptureQuality
import com.potatoremote.signaling.IceConfig
import com.potatoremote.signaling.RemoteSettings
import com.potatoremote.signaling.Viewport
import com.potatoremote.ui.ShareState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.CapturerObserver
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Une PeerConnection par spectateur.
 *
 * Le spectateur envoie une offre (vidéo en réception + DataChannel de contrôle).
 * Le téléphone répond avec une piste vidéo propre à ce spectateur, encodée au
 * format exact demandé par son navigateur.
 */
class ViewerSession(
    val id: String,
    val label: String,
    private val factory: PeerConnectionFactory,
    private val iceConfigs: List<IceConfig>,
    private val engine: ScreenCaptureEngine,
    private val api: ApiClient,
    private val phoneToken: String,
    private val scope: CoroutineScope,
    private val settingsProvider: () -> RemoteSettings,
    private val qualityProvider: () -> CaptureQuality,
    initialViewport: Viewport?,
    initialMode: ScreenCaptureEngine.FitMode,
    private val onLog: (String, String) -> Unit,
    private val onControlChanged: (String, Boolean) -> Unit,
    private val onKeyboardActivity: (String) -> Unit,
    private val onClosed: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturerObserver: CapturerObserver? = null
    private var attachment: ScreenCaptureEngine.Attachment? = null

    private var viewportAspect: Double = initialViewport?.aspect
        ?: (engine.screenWidth.toDouble() / engine.screenHeight.coerceAtLeast(1)).takeIf { it.isFinite() && it > 0.2 }
        ?: 1.0
    private var fitMode: ScreenCaptureEngine.FitMode = initialMode
    private var remoteReady = false
    private val pendingIce = ArrayList<JSONObject>(8)
    private var closed = false
    private var controlActive = false
    private var lastControlLog = 0L
    private var lastKeyboardLog = 0L

    fun start(offerSdp: String) {
        val configuration = PeerConnection.RTCConfiguration(toIceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceCandidatePoolSize = 2
            enableCpuOveruseDetection = true
            screencastMinBitrate = 150
        }

        val peer = factory.createPeerConnection(configuration, observer)
        if (peer == null) {
            onLog("Impossible de créer la connexion avec $label", "error")
            close()
            return
        }
        peerConnection = peer

        peer.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                remoteReady = true
                addVideo(peer)
                flushPendingIce()
                createAnswer(peer)
            }

            override fun onSetFailure(error: String?) {
                Log.w(TAG, "setRemoteDescription: $error")
                close()
            }
        }, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
    }

    private fun addVideo(peer: PeerConnection) {
        val source = factory.createVideoSource(true)
        source.setIsScreencast(true)
        val track = factory.createVideoTrack(POTATO_TRACK_ID, source)
        val observer = source.capturerObserver
        observer.onCapturerStarted(true)
        peer.addTrack(track, listOf("potato-screen"))

        videoSource = source
        videoTrack = track
        capturerObserver = observer

        val spec = outputSpec()
        attachment = engine.attach(spec) { buffer, timestampNs ->
            val capturer = capturerObserver
            if (capturer == null) {
                buffer.release()
                return@attach
            }
            val frame = VideoFrame(buffer, 0, timestampNs)
            try {
                capturer.onFrameCaptured(frame)
            } catch (error: Throwable) {
                Log.w(TAG, "Envoi de trame impossible", error)
            } finally {
                frame.release()
            }
        }
        source.adaptOutputFormat(spec.width, spec.height, 30)
        Log.i(TAG, "Flux $label : ${spec.width}x${spec.height} (${spec.mode})")
    }

    private fun createAnswer(peer: PeerConnection) {
        peer.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription?) {
                if (description == null) {
                    close()
                    return
                }
                peer.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        val payload = JSONObject().apply { put("sdp", description.description) }
                        sendSignal("answer", payload)
                    }

                    override fun onSetFailure(error: String?) {
                        Log.w(TAG, "setLocalDescription: $error")
                        close()
                    }
                }, description)
            }

            override fun onCreateFailure(error: String?) {
                Log.w(TAG, "createAnswer: $error")
                close()
            }
        }, MediaConstraints())
    }

    private fun even(value: Int): Int = (value.coerceAtLeast(2) / 2) * 2

    private fun outputSpec(): ScreenCaptureEngine.OutputSpec {
        val aspect = viewportAspect.coerceIn(0.25, 4.0)
        val height = when (qualityProvider()) {
            CaptureQuality.HIGH -> 1080
            CaptureQuality.LOW -> 540
            else -> 720
        }
        var width = (height * aspect).roundToInt()
        var targetHeight = height
        if (width > 2400) {
            width = 2400
            targetHeight = (width / aspect).roundToInt()
        }
        if (width < 240) {
            width = 240
            targetHeight = (width / aspect).roundToInt()
        }
        return ScreenCaptureEngine.OutputSpec(even(width), even(targetHeight), fitMode)
    }

    fun applyQuality() {
        val spec = outputSpec()
        attachment?.update(spec)
        videoSource?.adaptOutputFormat(spec.width, spec.height, 30)
    }

    fun addRemoteIce(payload: JSONObject) {
        if (!remoteReady) {
            if (pendingIce.size < 80) pendingIce.add(payload)
            return
        }
        addIce(payload)
    }

    private fun flushPendingIce() {
        val queued = pendingIce.toList()
        pendingIce.clear()
        queued.forEach { addIce(it) }
    }

    private fun addIce(payload: JSONObject) {
        val candidate = payload.optString("candidate", "")
        if (candidate.isBlank()) return
        try {
            peerConnection?.addIceCandidate(
                IceCandidate(
                    payload.optString("sdpMid", "0"),
                    payload.optInt("sdpMLineIndex", 0),
                    candidate
                )
            )
        } catch (error: Throwable) {
            Log.w(TAG, "Candidat ICE refusé", error)
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            if (state == PeerConnection.IceConnectionState.CONNECTED) {
                onLog("$label connecté", "success")
            } else if (state == PeerConnection.IceConnectionState.FAILED) {
                close()
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit

        override fun onIceCandidate(candidate: IceCandidate?) {
            val value = candidate ?: return
            val payload = JSONObject().apply {
                put("candidate", value.sdp)
                put("sdpMid", value.sdpMid ?: "0")
                put("sdpMLineIndex", value.sdpMLineIndex)
            }
            sendSignal("ice", payload)
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit

        override fun onDataChannel(channel: DataChannel) {
            dataChannel = channel
            channel.registerObserver(dataObserver)
        }

        override fun onRenegotiationNeeded() = Unit
    }

    private val dataObserver = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit

        override fun onStateChange() {
            val state = dataChannel?.state()
            if (state == DataChannel.State.OPEN) {
                onLog("Canal de contrôle prêt pour $label", "success")
            }
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            val data = buffer.data
            if (data.remaining() > 65536) return
            val bytes = ByteArray(data.remaining())
            data.get(bytes)
            val json = try {
                JSONObject(String(bytes, Charsets.UTF_8))
            } catch (_: Exception) {
                return
            }
            main.post { handleCommand(json) }
        }
    }

    private fun handleCommand(message: JSONObject) {
        if (closed) return
        when (message.optString("t")) {
            "hello" -> {
                val viewport = message.optJSONObject("viewport")
                applyViewport(
                    Viewport.fromJson(viewport),
                    ScreenCaptureEngine.FitMode.from(message.optString("mode"))
                )
            }
            "ping" -> {
                val pong = JSONObject().apply {
                    put("t", "pong")
                    put("ts", message.optDouble("ts", 0.0))
                }
                send(pong)
            }
            "viewport" -> {
                val viewport = Viewport.fromJson(
                    JSONObject().apply {
                        put("w", message.optInt("w", 0))
                        put("h", message.optInt("h", 0))
                        put("aspect", message.optDouble("aspect", 1.0))
                    }
                )
                applyViewport(viewport, ScreenCaptureEngine.FitMode.from(message.optString("mode")))
            }
            "control" -> setControlActive(message.optBoolean("active", false))
            "touch" -> handleTouch(message)
            "keyboard" -> setKeyboardActive(message.optBoolean("active", false))
            "text" -> handleText(message)
            "key" -> handleKey(message)
            "bye" -> close()
        }
    }

    private fun applyViewport(viewport: Viewport?, mode: ScreenCaptureEngine.FitMode) {
        if (viewport == null) return
        val aspect = viewport.aspect.coerceIn(0.25, 4.0)
        if (abs(aspect - viewportAspect) < 0.002 && mode == fitMode) return
        viewportAspect = aspect
        fitMode = mode
        applyQuality()
    }

    private fun setControlActive(active: Boolean) {
        if (active) {
            if (!settingsProvider().control) {
                sendResult("control-result", false, "Le contrôle tactile est désactivé sur le téléphone.")
                return
            }
            if (!AccessibilityBridge.isReady) {
                sendResult("control-result", false, "Activez le service d'accessibilité Potato Remote sur le téléphone.")
                return
            }
        }
        controlActive = active
        onControlChanged(id, active)
        sendResult("control-result", true, null)
        if (active) {
            ShareState.log("Contrôle tactile activé par $label", "warn")
        } else {
            ShareState.log("Contrôle tactile désactivé par $label")
        }
    }

    private fun setKeyboardActive(active: Boolean) {
        if (active) {
            if (!settingsProvider().keyboard) {
                sendResult("keyboard-result", false, "Le clavier distant est désactivé sur le téléphone.")
                return
            }
            if (!AccessibilityBridge.isReady) {
                sendResult("keyboard-result", false, "Activez le service d'accessibilité Potato Remote sur le téléphone.")
                return
            }
            ShareState.log("Clavier distant ouvert par $label", "warn")
        }
        sendResult("keyboard-result", true, null)
    }

    private fun handleTouch(message: JSONObject) {
        if (!settingsProvider().control) {
            sendResult("control-result", false, "Contrôle tactile désactivé sur le téléphone.")
            return
        }
        val injector = AccessibilityBridge.service()?.touch()
        if (injector == null) {
            sendResult("control-result", false, "Service d'accessibilité inactif sur le téléphone.")
            return
        }
        val point = mapTouch(message.optDouble("x", 0.5), message.optDouble("y", 0.5))
        when (message.optString("a")) {
            "down" -> {
                if (!injector.down(point.x, point.y)) {
                    sendResult("control-result", false, "Android a refusé le geste.")
                    return
                }
                logControlUse()
            }
            "move" -> injector.move(point.x, point.y)
            "up" -> injector.up(point.x, point.y)
            "cancel" -> injector.cancel()
        }
    }

    private fun handleText(message: JSONObject) {
        if (!settingsProvider().keyboard) {
            sendResult("keyboard-result", false, "Clavier distant désactivé sur le téléphone.")
            return
        }
        val service = AccessibilityBridge.service()
        if (service == null) {
            sendResult("keyboard-result", false, "Service d'accessibilité inactif.")
            return
        }
        val result = TextInjector.inject(
            service,
            message.optString("op", "insert"),
            message.optString("text", ""),
            message.optInt("count", 1)
        )
        sendResult("keyboard-result", result.ok, result.reason)
        if (result.ok) logKeyboardUse()
    }

    private fun handleKey(message: JSONObject) {
        if (!settingsProvider().keyboard) {
            sendResult("keyboard-result", false, "Clavier distant désactivé sur le téléphone.")
            return
        }
        val service = AccessibilityBridge.service()
        if (service == null) {
            sendResult("keyboard-result", false, "Service d'accessibilité inactif.")
            return
        }
        val result = TextInjector.pressKey(service, message.optString("key", ""))
        sendResult("keyboard-result", result.ok, result.reason)
        if (result.ok) logKeyboardUse()
    }

    private fun mapTouch(normalizedX: Double, normalizedY: Double): PointF {
        val screenW = engine.screenWidth
        val screenH = engine.screenHeight
        if (screenW <= 0 || screenH <= 0) return PointF()
        val frameWidth = 1000.0
        val frameHeight = 1000.0 / viewportAspect.coerceIn(0.25, 4.0)
        val screenAspect = screenW.toDouble() / screenH
        val scale = if (fitMode == ScreenCaptureEngine.FitMode.FILL) {
            max(frameWidth / screenW, frameHeight / screenH)
        } else {
            min(frameWidth / screenW, frameHeight / screenH)
        }
        val contentWidth = screenW * scale
        val contentHeight = screenH * scale
        val left = (frameWidth - contentWidth) / 2.0
        val top = (frameHeight - contentHeight) / 2.0
        val x = ((normalizedX * frameWidth - left) / contentWidth * screenW).coerceIn(0.0, screenW - 1.0)
        val y = ((normalizedY * frameHeight - top) / contentHeight * screenH).coerceIn(0.0, screenH - 1.0)
        return PointF(x.toFloat(), y.toFloat())
    }

    private fun logControlUse() {
        val now = System.currentTimeMillis()
        if (now - lastControlLog > 8000) {
            lastControlLog = now
            ShareState.log("Geste tactile de $label", "control")
        }
    }

    private fun logKeyboardUse() {
        val now = System.currentTimeMillis()
        if (now - lastKeyboardLog > 8000) {
            lastKeyboardLog = now
            ShareState.log("Saisie au clavier depuis $label", "keyboard")
            onKeyboardActivity(id)
        }
    }

    private fun sendResult(type: String, ok: Boolean, reason: String?) {
        val payload = JSONObject().apply {
            put("t", type)
            put("ok", ok)
            if (!ok && !reason.isNullOrBlank()) put("reason", reason)
        }
        send(payload)
    }

    private fun send(payload: JSONObject) {
        val channel = dataChannel ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        try {
            val buffer = DataChannel.Buffer(
                ByteBuffer.wrap(payload.toString().toByteArray(Charsets.UTF_8)),
                false
            )
            channel.send(buffer)
        } catch (error: Throwable) {
            Log.w(TAG, "Envoi DataChannel impossible", error)
        }
    }

    private fun sendSignal(type: String, payload: JSONObject) {
        scope.launch {
            try {
                api.send(phoneToken, type, payload, id)
            } catch (error: Throwable) {
                Log.w(TAG, "Signal $type non envoyé", error)
            }
        }
    }

    private fun toIceServers(): List<PeerConnection.IceServer> {
        val servers = ArrayList<PeerConnection.IceServer>()
        for (config in iceConfigs) {
            if (config.urls.isEmpty()) continue
            try {
                val builder = PeerConnection.IceServer.builder(config.urls)
                config.username?.let(builder::setUsername)
                config.credential?.let(builder::setPassword)
                servers.add(builder.createIceServer())
            } catch (error: Throwable) {
                Log.w(TAG, "Serveur ICE ignoré", error)
            }
        }
        return servers
    }

    fun close() {
        if (closed) return
        closed = true
        main.post {
            if (controlActive) {
                controlActive = false
                onControlChanged(id, false)
            }
            attachment?.close()
            attachment = null
            try {
                capturerObserver?.onCapturerStopped()
            } catch (_: Throwable) {
            }
            capturerObserver = null
            videoTrack?.dispose()
            videoTrack = null
            videoSource?.dispose()
            videoSource = null
            dataChannel?.unregisterObserver()
            dataChannel?.dispose()
            dataChannel = null
            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null
            onClosed(id)
        }
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    companion object {
        private const val TAG = "PotatoViewer"
        private const val POTATO_TRACK_ID = "potato-screen"
    }
}
