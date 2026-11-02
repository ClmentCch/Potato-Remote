package com.potatoremote.signaling

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import org.json.JSONObject

data class IceConfig(val urls: List<String>, val username: String?, val credential: String?)

data class Viewport(val width: Int, val height: Int, val aspect: Double) {
    companion object {
        fun fromJson(json: JSONObject?): Viewport? {
            if (json == null) return null
            val width = json.optInt("w", 0)
            val height = json.optInt("h", 0)
            if (width < 120 || height < 120) return null
            val rawAspect = json.optDouble("aspect", width.toDouble() / height)
            val aspect = if (rawAspect.isFinite() && rawAspect > 0.15 && rawAspect < 6.0) rawAspect else width.toDouble() / height
            return Viewport(width, height, aspect)
        }
    }
}

data class LinkStats(
    val rtt: Int = 0,
    val fps: Int = 0,
    val kbps: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val jitter: Int = 0,
    val lost: Int = 0,
) {
    companion object {
        fun fromJson(json: JSONObject?): LinkStats? {
            if (json == null) return null
            return LinkStats(
                rtt = json.optInt("rtt", 0),
                fps = json.optInt("fps", 0),
                kbps = json.optInt("kbps", 0),
                width = json.optInt("width", 0),
                height = json.optInt("height", 0),
                jitter = json.optInt("jitter", 0),
                lost = json.optInt("lost", 0),
            )
        }
    }
}

data class RemoteSettings(
    val control: Boolean = true,
    val keyboard: Boolean = true,
    val allowNewViewers: Boolean = true,
    val maxViewers: Int = 4,
    val quality: String = "auto",
) {
    companion object {
        fun fromJson(json: JSONObject?): RemoteSettings {
            val data = json ?: return RemoteSettings()
            return RemoteSettings(
                control = data.optBoolean("control", true),
                keyboard = data.optBoolean("keyboard", true),
                allowNewViewers = data.optBoolean("allow_new_viewers", true),
                maxViewers = data.optInt("max_viewers", 4).coerceIn(1, 8),
                quality = data.optString("quality", "auto"),
            )
        }
    }
}

data class RemoteViewer(
    val id: String,
    val label: String,
    val online: Boolean,
    val joinedAt: Long,
    val viewport: Viewport?,
    val stats: LinkStats?,
    val controlUsedAt: Long?,
    val keyboardUsedAt: Long?,
)

data class PhoneSnapshot(
    val viewers: List<RemoteViewer>,
    val viewerCount: Int,
    val settings: RemoteSettings,
    val expiresAt: Long,
    val viewerUrl: String,
    val turnConfigured: Boolean,
)

data class CreatedSession(
    val sessionId: String,
    val name: String,
    val phoneToken: String,
    val viewerUrl: String,
    val iceServers: List<IceConfig>,
    val turnConfigured: Boolean,
    val expiresAt: Long,
    val cursor: Long,
)

data class SignalMessage(
    val seq: Long,
    val from: String,
    val viewerId: String?,
    val type: String,
    val payload: JSONObject,
)

data class PollResult(
    val messages: List<SignalMessage>,
    val cursor: Long,
    val settings: RemoteSettings,
    val viewerCount: Int,
    val phoneOnline: Boolean,
    val phoneLabel: String,
    val turnConfigured: Boolean,
)

data class ScreenInfo(val width: Int, val height: Int, val rotation: Int, val density: Float) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("width", width)
        put("height", height)
        put("rotation", rotation)
        put("density", density.toDouble())
    }

    companion object {
        @Suppress("DEPRECATION")
        fun current(context: Context): ScreenInfo {
            val manager = context.getSystemService(WindowManager::class.java)
            val size = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = manager.currentWindowMetrics.bounds
                Point(bounds.width(), bounds.height())
            } else {
                val point = Point()
                manager.defaultDisplay.getRealSize(point)
                point
            }
            val rotation = when (manager.defaultDisplay.rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            return ScreenInfo(size.x, size.y, rotation, context.resources.displayMetrics.density)
        }
    }
}

enum class CaptureQuality(val key: String, val shortSide: Int, val label: String) {
    AUTO("auto", 720, "Auto"),
    LOW("540p", 540, "540p"),
    MEDIUM("720p", 720, "720p"),
    HIGH("1080p", 1080, "1080p");

    companion object {
        fun from(key: String?): CaptureQuality = entries.firstOrNull { it.key == key } ?: AUTO
    }
}
