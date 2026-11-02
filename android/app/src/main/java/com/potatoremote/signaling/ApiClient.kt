package com.potatoremote.signaling

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiException(
    val code: String,
    message: String,
    val status: Int = 0,
    val retryAfter: Int = 0,
) : Exception(message)

/**
 * Client de l'API Potato Remote (PHP).
 * Toutes les requêtes sont authentifiées par jeton porteur, jamais par cookie.
 */
class ApiClient(baseUrl: String) {
    private val root = baseUrl.trimEnd('/') + "/api/index.php"
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(50, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun ping() = withContext(Dispatchers.IO) { request("config", null, null, "GET") }

    suspend fun createSession(
        name: String,
        password: String,
        device: String,
        screen: ScreenInfo,
        settings: RemoteSettings,
        phoneToken: String?,
        serverKey: String = "",
    ): CreatedSession = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("session", name)
            put("password", password)
            put("device", device)
            put("screen", screen.toJson())
            put("settings", JSONObject().apply {
                put("control", settings.control)
                put("keyboard", settings.keyboard)
                put("max_viewers", settings.maxViewers)
                put("quality", settings.quality)
            })
            if (!phoneToken.isNullOrBlank()) put("phone_token", phoneToken)
            if (serverKey.isNotBlank()) put("server_key", serverKey)
        }
        val data = request("session.create", null, body)
        CreatedSession(
            sessionId = data.getString("sessionId"),
            name = data.getString("name"),
            phoneToken = data.getString("phoneToken"),
            viewerUrl = data.optString("viewerUrl", ""),
            iceServers = parseIceServers(data.optJSONArray("iceServers")),
            turnConfigured = data.optBoolean("turnConfigured", false),
            expiresAt = data.optLong("expiresAt", 0L),
            cursor = data.optLong("cursor", 0L),
        )
    }

    suspend fun status(phoneToken: String, screen: ScreenInfo): PhoneSnapshot = withContext(Dispatchers.IO) {
        val body = JSONObject().apply { put("screen", screen.toJson()) }
        parseSnapshot(request("phone.status", phoneToken, body))
    }

    suspend fun update(phoneToken: String, patch: Map<String, Any?>): PhoneSnapshot = withContext(Dispatchers.IO) {
        val body = JSONObject()
        patch.forEach { (key, value) ->
            when (value) {
                null -> Unit
                is Boolean -> body.put(key, value)
                is Int -> body.put(key, value)
                is Long -> body.put(key, value)
                is String -> body.put(key, value)
                else -> body.put(key, value.toString())
            }
        }
        parseSnapshot(request("phone.update", phoneToken, body))
    }

    suspend fun stop(phoneToken: String) = withContext(Dispatchers.IO) {
        request("phone.stop", phoneToken, JSONObject())
        Unit
    }

    suspend fun poll(phoneToken: String, cursor: Long, waitSeconds: Int = 18): PollResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("cursor", cursor)
            put("wait", waitSeconds)
        }
        val data = request("signal.poll", phoneToken, body)
        val messagesJson = data.optJSONArray("messages") ?: JSONArray()
        val messages = ArrayList<SignalMessage>(messagesJson.length())
        for (index in 0 until messagesJson.length()) {
            val item = messagesJson.optJSONObject(index) ?: continue
            messages += SignalMessage(
                seq = item.optLong("seq", 0L),
                from = item.optString("from", ""),
                viewerId = item.optString("viewerId", "").ifBlank { null },
                type = item.optString("type", ""),
                payload = item.optJSONObject("payload") ?: JSONObject(),
            )
        }
        PollResult(
            messages = messages,
            cursor = data.optLong("cursor", cursor),
            settings = RemoteSettings.fromJson(data.optJSONObject("settings")),
            viewerCount = data.optInt("viewerCount", 0),
            phoneOnline = data.optBoolean("phoneOnline", true),
            phoneLabel = data.optString("phoneLabel", ""),
            turnConfigured = data.optBoolean("turnConfigured", false),
        )
    }

    suspend fun send(phoneToken: String, type: String, payload: JSONObject, to: String? = null) = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("type", type)
            put("payload", payload)
            if (!to.isNullOrBlank()) put("to", to)
        }
        request("signal.send", phoneToken, body)
        Unit
    }

    private fun parseSnapshot(data: JSONObject): PhoneSnapshot {
        val viewersJson = data.optJSONArray("viewers") ?: JSONArray()
        val viewers = ArrayList<RemoteViewer>(viewersJson.length())
        for (index in 0 until viewersJson.length()) {
            val item = viewersJson.optJSONObject(index) ?: continue
            viewers += RemoteViewer(
                id = item.optString("id", ""),
                label = item.optString("label", "Appareil"),
                online = item.optBoolean("online", false),
                joinedAt = item.optLong("joinedAt", 0L),
                viewport = Viewport.fromJson(item.optJSONObject("viewport")),
                stats = LinkStats.fromJson(item.optJSONObject("stats")),
                controlUsedAt = item.optLong("controlUsedAt", 0L).takeIf { it > 0 },
                keyboardUsedAt = item.optLong("keyboardUsedAt", 0L).takeIf { it > 0 },
            )
        }
        return PhoneSnapshot(
            viewers = viewers,
            viewerCount = data.optInt("viewerCount", viewers.size),
            settings = RemoteSettings.fromJson(data.optJSONObject("settings")),
            expiresAt = data.optLong("expiresAt", 0L),
            viewerUrl = data.optString("viewerUrl", ""),
            turnConfigured = data.optBoolean("turnConfigured", false),
        )
    }

    private fun request(
        action: String,
        token: String?,
        body: JSONObject?,
        method: String = "POST",
    ): JSONObject {
        val base = root.toHttpUrlOrNull() ?: throw ApiException("bad_server", "Adresse du serveur invalide.")
        val url = base.newBuilder().addQueryParameter("action", action).build()
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "PotatoRemote-Android/1.0")

        if (method == "GET") {
            builder.get()
        } else {
            builder.post((body ?: JSONObject()).toString().toRequestBody(jsonType))
        }
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
            builder.header("X-Potato-Token", token)
        }

        client.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = try {
                JSONObject(raw)
            } catch (_: Exception) {
                throw ApiException("bad_response", "Le serveur a renvoyé une réponse illisible.", response.code)
            }
            if (!response.isSuccessful || !json.optBoolean("ok", false)) {
                val error = json.optJSONObject("error") ?: JSONObject()
                throw ApiException(
                    code = error.optString("code", "http_${response.code}"),
                    message = error.optString("message", "Erreur serveur (${response.code})."),
                    status = response.code,
                    retryAfter = error.optInt("retryAfter", 0),
                )
            }
            return json.optJSONObject("data") ?: JSONObject()
        }
    }

    private fun parseIceServers(array: JSONArray?): List<IceConfig> {
        if (array == null) return emptyList()
        val servers = ArrayList<IceConfig>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val urls = ArrayList<String>()
            when (val rawUrls = item.opt("urls")) {
                is JSONArray -> for (u in 0 until rawUrls.length()) {
                    rawUrls.optString(u).takeIf { it.isNotBlank() }?.let(urls::add)
                }
                is String -> if (rawUrls.isNotBlank()) urls += rawUrls
            }
            if (urls.isEmpty()) continue
            servers += IceConfig(
                urls = urls,
                username = item.optString("username", "").ifBlank { null },
                credential = item.optString("credential", "").ifBlank { null },
            )
        }
        return servers
    }

}
