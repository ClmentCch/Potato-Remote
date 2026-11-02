package com.potatoremote.settings

import android.content.Context
import android.os.Build
import com.potatoremote.BuildConfig

/**
 * Préférences locales. Aucun mot de passe n'est jamais enregistré :
 * seul le jeton de session (secret révocable) permet de reprendre un partage.
 */
class Prefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("potato", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, BuildConfig.DEFAULT_SERVER).orEmpty()
        set(value) = prefs.edit().putString(KEY_SERVER, normalizeServer(value)).apply()

    var sessionName: String
        get() = prefs.getString(KEY_SESSION_NAME, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SESSION_NAME, value).apply()

    var phoneToken: String?
        get() = prefs.getString(KEY_PHONE_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_PHONE_TOKEN, value).apply()

    var serverKey: String
        get() = prefs.getString(KEY_SERVER_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SERVER_KEY, value.trim()).apply()

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, defaultDeviceName()).orEmpty().ifBlank { defaultDeviceName() }
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value.trim()).apply()

    var quality: String
        get() = prefs.getString(KEY_QUALITY, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_QUALITY, value).apply()

    var maxViewers: Int
        get() = prefs.getInt(KEY_MAX_VIEWERS, 4)
        set(value) = prefs.edit().putInt(KEY_MAX_VIEWERS, value.coerceIn(1, 8)).apply()

    var allowControl: Boolean
        get() = prefs.getBoolean(KEY_CONTROL, true)
        set(value) = prefs.edit().putBoolean(KEY_CONTROL, value).apply()

    var allowKeyboard: Boolean
        get() = prefs.getBoolean(KEY_KEYBOARD, true)
        set(value) = prefs.edit().putBoolean(KEY_KEYBOARD, value).apply()

    fun clearSession() {
        prefs.edit().remove(KEY_PHONE_TOKEN).apply()
    }

    companion object {
        private const val KEY_SERVER = "server"
        private const val KEY_SESSION_NAME = "session_name"
        private const val KEY_PHONE_TOKEN = "phone_token"
        private const val KEY_SERVER_KEY = "server_key"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_QUALITY = "quality"
        private const val KEY_MAX_VIEWERS = "max_viewers"
        private const val KEY_CONTROL = "control"
        private const val KEY_KEYBOARD = "keyboard"

        fun defaultDeviceName(): String {
            val model = Build.MODEL?.trim().orEmpty()
            val cleaned = model.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim()
            return cleaned.ifBlank { "Mon téléphone" }.take(32)
        }

        fun normalizeServer(raw: String): String {
            var value = raw.trim()
            if (value.isEmpty()) return ""
            if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) {
                value = "https://$value"
            }
            value = value.trimEnd('/')
            if (value.endsWith("/api/index.php")) value = value.removeSuffix("/api/index.php")
            return value
        }

        fun isValidServer(raw: String): Boolean {
            val value = normalizeServer(raw)
            if (value.isEmpty()) return false
            return try {
                val url = java.net.URL(value)
                val secure = url.protocol == "https" || (BuildConfig.DEBUG && url.protocol == "http")
                secure && !url.host.isNullOrBlank()
            } catch (_: Exception) {
                false
            }
        }
    }
}
