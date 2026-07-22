package com.signalasi.chat

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class DesktopControlAuthorization(
    val authorizationId: String,
    val phoneName: String,
    val phoneFingerprint: String,
    val grantedAt: Long,
    val lastUsedAt: Long,
    val status: String,
    val allowedTools: List<String>
)

data class DesktopControlScreenshot(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int,
    val originalWidth: Int,
    val originalHeight: Int,
    val capturedAt: Long
)

data class DesktopControlAudit(
    val eventType: String,
    val toolId: String,
    val status: String,
    val summary: String,
    val createdAt: Long
)

data class DesktopRemoteControlSnapshot(
    val desktopId: String,
    val desktopName: String,
    val desktopFingerprint: String,
    val serverRouteId: String,
    val enabled: Boolean,
    val requireUnlocked: Boolean,
    val currentAuthorization: DesktopControlAuthorization?,
    val authorizations: List<DesktopControlAuthorization>,
    val recentAudit: List<DesktopControlAudit>,
    val lastActionStatus: String,
    val lastActionSummary: String,
    val lastActionAt: Long,
    val screenshot: DesktopControlScreenshot?
) {
    val authorized: Boolean get() = enabled && currentAuthorization?.status == "active"
    val pending: Boolean get() = currentAuthorization?.status == "pending"
}

object DesktopRemoteControl {
    const val SCREENSHOT = "desktop.screenshot"
    const val CLICK_XY = "desktop.click_xy"
    const val TYPE_TEXT = "desktop.type_text"
    const val HOTKEY = "desktop.hotkey"
    const val SCROLL = "desktop.scroll"

    private const val PREFS = "signalasi_desktop_control_v1"
    private const val KEY_DESKTOPS = "desktops"
    private const val ACTION_TTL_MS = 30_000L
    private const val MAX_SCREENSHOT_BYTES = 300 * 1024

    private data class RuntimeState(
        var status: String = "",
        var summary: String = "",
        var at: Long = 0L,
        var screenshot: DesktopControlScreenshot? = null
    )

    private val runtime = ConcurrentHashMap<String, RuntimeState>()

    fun handleInbound(context: Context, payload: JSONObject): Boolean {
        val type = payload.optString("type")
        if (type == "capability_manifest") {
            val desktopId = payload.optJSONObject("server")?.optString("id").orEmpty()
                .ifBlank { payload.optString("desktop_id") }
            val control = payload.optJSONObject("desktop_control") ?: return false
            updateDesktopState(
                context,
                desktopId,
                payload.optJSONObject("server")?.optString("name").orEmpty(),
                control,
                control.optJSONArray("authorizations") ?: JSONArray(),
                control.optJSONArray("authorizations")?.optJSONObject(0)
            )
            return false
        }
        if (type !in setOf(
                "desktop_control_authorizations",
                "desktop_control_authorization_changed",
                "desktop_executor_event",
                "desktop_action_receipt"
            )
        ) return false

        val desktopId = payload.optString("desktop_id")
        if (desktopId.isBlank()) return true
        when (type) {
            "desktop_control_authorizations" -> updateDesktopState(
                context,
                desktopId,
                payload.optString("desktop_name"),
                payload,
                payload.optJSONArray("items") ?: JSONArray(),
                payload.optJSONObject("current_authorization")
            )
            "desktop_control_authorization_changed" -> {
                val authorization = payload.optJSONObject("authorization")
                mergeAuthorization(context, desktopId, payload.optString("desktop_name"), authorization)
                runtime.computeIfAbsent(desktopId) { RuntimeState() }.apply {
                    status = authorization?.optString("status").orEmpty()
                    summary = payload.optString("reason")
                    at = System.currentTimeMillis()
                }
            }
            "desktop_executor_event" -> runtime.computeIfAbsent(desktopId) { RuntimeState() }.apply {
                status = payload.optString("status")
                summary = payload.optString("summary")
                at = payload.optLong("timestamp", System.currentTimeMillis())
            }
            "desktop_action_receipt" -> {
                val state = runtime.computeIfAbsent(desktopId) { RuntimeState() }
                state.status = payload.optString("status")
                state.summary = payload.optString("summary")
                state.at = payload.optLong("completed_at", System.currentTimeMillis())
                (
                    screenshotFrom(payload.optJSONObject("post_screenshot"))
                        ?: screenshotFrom(payload.optJSONObject("output")?.optJSONObject("screenshot"))
                    )?.let { state.screenshot = it }
                if (payload.optString("status") == "succeeded") {
                    touchAuthorization(context, desktopId, state.at)
                }
            }
        }
        return true
    }

    fun markPairingOffer(context: Context, pairingQr: JSONObject) {
        val offer = pairingQr.optJSONObject("desktop_control_authorization") ?: return
        if (offer.optString("token").isBlank()) return
        val desktopId = pairingQr.optString("desktop_id")
        if (desktopId.isBlank()) return
        val root = read(context)
        val current = root.optJSONObject(desktopId) ?: JSONObject()
        current
            .put("desktop_id", desktopId)
            .put("desktop_name", pairingQr.optString("desktop_name", "SignalASI Desktop"))
            .put("enabled", true)
            .put("current_authorization", JSONObject().put("status", "pending"))
            .put("updated_at", System.currentTimeMillis())
        root.put(desktopId, current)
        write(context, root)
    }

    fun snapshot(context: Context, desktopId: String): DesktopRemoteControlSnapshot {
        val item = read(context).optJSONObject(desktopId) ?: JSONObject()
        val link = SignalASILinkProtocol.serverLink(context, desktopId)
        val authorizations = parseAuthorizations(item.optJSONArray("authorizations") ?: JSONArray())
        val current = parseAuthorization(item.optJSONObject("current_authorization"))
            ?: authorizations.firstOrNull { it.status == "active" }
            ?: authorizations.firstOrNull { it.status == "pending" }
        val live = runtime[desktopId]
        return DesktopRemoteControlSnapshot(
            desktopId = desktopId,
            desktopName = item.optString("desktop_name", "SignalASI Desktop"),
            desktopFingerprint = item.optString("desktop_fingerprint").ifBlank {
                link?.desktopFingerprint.orEmpty()
            },
            serverRouteId = item.optString("server_route_id").ifBlank {
                link?.routes?.serverRouteId.orEmpty()
            },
            enabled = item.optBoolean("enabled", false),
            requireUnlocked = item.optBoolean("require_unlocked", false),
            currentAuthorization = current,
            authorizations = authorizations,
            recentAudit = parseAudit(item.optJSONArray("recent_audit") ?: JSONArray()),
            lastActionStatus = live?.status.orEmpty(),
            lastActionSummary = live?.summary.orEmpty(),
            lastActionAt = live?.at ?: 0L,
            screenshot = live?.screenshot
        )
    }

    fun requestAuthorizations(desktopId: String): Boolean =
        SignalASIMqttClient.publishDesktopControlAuthorizationsRequest(desktopId)

    fun requestScreenshot(desktopId: String): Boolean = requestAction(desktopId, SCREENSHOT, JSONObject())

    fun click(desktopId: String, x: Int, y: Int): Boolean = requestAction(
        desktopId,
        CLICK_XY,
        JSONObject().put("x", x).put("y", y).put("button", "left")
    )

    fun typeText(desktopId: String, text: String): Boolean {
        if (text.isBlank() || text.length > 4_096) return false
        return requestAction(desktopId, TYPE_TEXT, JSONObject().put("text", text))
    }

    fun hotkey(desktopId: String, vararg keys: String): Boolean {
        if (keys.isEmpty() || keys.size > 4) return false
        return requestAction(desktopId, HOTKEY, JSONObject().put("keys", JSONArray(keys.toList())))
    }

    fun scroll(desktopId: String, delta: Int): Boolean {
        if (delta == 0 || delta !in -2_400..2_400) return false
        return requestAction(desktopId, SCROLL, JSONObject().put("delta", delta))
    }

    fun revoke(desktopId: String, authorizationId: String): Boolean =
        authorizationId.isNotBlank() && SignalASIMqttClient.publishDesktopControlRevoke(
            desktopId,
            authorizationId
        )

    fun clearDesktop(context: Context, desktopId: String) {
        val root = read(context)
        root.remove(desktopId)
        write(context, root)
        runtime.remove(desktopId)
    }

    private fun requestAction(desktopId: String, toolId: String, input: JSONObject): Boolean {
        val context = SignalASIMqttClient.applicationContext() ?: return false
        val authorization = snapshot(context, desktopId).currentAuthorization
            ?.takeIf { it.status == "active" } ?: return false
        val now = System.currentTimeMillis()
        val actionId = UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("type", "desktop_executor_request")
            .put("task_id", "desktop-control-$actionId")
            .put("action_id", actionId)
            .put("authorization_id", authorization.authorizationId)
            .put("tool_id", toolId)
            .put("input", input)
            .put("sent_at", now)
            .put("expires_at", now + ACTION_TTL_MS)
        runtime.computeIfAbsent(desktopId) { RuntimeState() }.apply {
            status = "sending"
            summary = toolId
            at = now
        }
        return SignalASIMqttClient.publishDesktopExecutorRequest(desktopId, payload)
    }

    private fun updateDesktopState(
        context: Context,
        desktopId: String,
        desktopName: String,
        control: JSONObject,
        items: JSONArray,
        currentAuthorization: JSONObject?
    ) {
        if (desktopId.isBlank()) return
        val root = read(context)
        val item = root.optJSONObject(desktopId) ?: JSONObject()
        item
            .put("desktop_id", desktopId)
            .put("desktop_name", desktopName.ifBlank { item.optString("desktop_name", "SignalASI Desktop") })
            .put("desktop_fingerprint", control.optString("desktop_fingerprint", item.optString("desktop_fingerprint")))
            .put("server_route_id", control.optString("server_route_id", item.optString("server_route_id")))
            .put("enabled", control.optBoolean("enabled", item.optBoolean("enabled", false)))
            .put("require_unlocked", control.optBoolean("require_unlocked", item.optBoolean("require_unlocked", false)))
            .put("allowed_tools", control.optJSONArray("allowed_tools") ?: item.optJSONArray("allowed_tools") ?: JSONArray())
            .put("authorizations", items)
            .put("current_authorization", currentAuthorization ?: JSONObject.NULL)
            .put("recent_audit", control.optJSONArray("recent_audit") ?: item.optJSONArray("recent_audit") ?: JSONArray())
            .put("updated_at", System.currentTimeMillis())
        root.put(desktopId, item)
        write(context, root)
    }

    private fun mergeAuthorization(
        context: Context,
        desktopId: String,
        desktopName: String,
        authorization: JSONObject?
    ) {
        if (authorization == null) return
        val root = read(context)
        val item = root.optJSONObject(desktopId) ?: JSONObject()
        val rows = item.optJSONArray("authorizations") ?: JSONArray()
        val replacement = JSONArray()
        var found = false
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            if (row.optString("authorization_id") == authorization.optString("authorization_id")) {
                replacement.put(authorization)
                found = true
            } else replacement.put(row)
        }
        if (!found) replacement.put(authorization)
        item
            .put("desktop_id", desktopId)
            .put("desktop_name", desktopName.ifBlank { item.optString("desktop_name", "SignalASI Desktop") })
            .put("authorizations", replacement)
            .put("current_authorization", authorization)
            .put("updated_at", System.currentTimeMillis())
        root.put(desktopId, item)
        write(context, root)
    }

    private fun touchAuthorization(context: Context, desktopId: String, at: Long) {
        val root = read(context)
        val item = root.optJSONObject(desktopId) ?: return
        item.optJSONObject("current_authorization")?.put("last_used_at", at)
        root.put(desktopId, item)
        write(context, root)
    }

    private fun screenshotFrom(json: JSONObject?): DesktopControlScreenshot? {
        val source = json ?: return null
        if (source.optString("image_mime") != "image/jpeg") return null
        val bytes = runCatching { Base64.decode(source.optString("image_base64"), Base64.DEFAULT) }
            .getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_SCREENSHOT_BYTES) return null
        return DesktopControlScreenshot(
            jpegBytes = bytes,
            width = source.optInt("width"),
            height = source.optInt("height"),
            originalWidth = source.optInt("original_width"),
            originalHeight = source.optInt("original_height"),
            capturedAt = source.optLong("captured_at", System.currentTimeMillis())
        ).takeIf { it.width > 0 && it.height > 0 && it.originalWidth > 0 && it.originalHeight > 0 }
    }

    private fun parseAuthorizations(array: JSONArray): List<DesktopControlAuthorization> = buildList {
        for (index in 0 until array.length()) parseAuthorization(array.optJSONObject(index))?.let(::add)
    }

    private fun parseAudit(array: JSONArray): List<DesktopControlAudit> = buildList {
        for (index in 0 until array.length()) {
            val source = array.optJSONObject(index) ?: continue
            add(DesktopControlAudit(
                eventType = source.optString("event_type"),
                toolId = source.optString("tool_id"),
                status = source.optString("status"),
                summary = source.optString("summary"),
                createdAt = source.optLong("created_at")
            ))
        }
    }

    private fun parseAuthorization(json: JSONObject?): DesktopControlAuthorization? {
        val source = json ?: return null
        val status = source.optString("status")
        val id = source.optString("authorization_id")
        if (id.isBlank() && status != "pending") return null
        val tools = source.optJSONArray("allowed_tools") ?: JSONArray()
        return DesktopControlAuthorization(
            authorizationId = id,
            phoneName = source.optString("phone_name"),
            phoneFingerprint = source.optString("phone_fingerprint"),
            grantedAt = source.optLong("granted_at"),
            lastUsedAt = source.optLong("last_used_at"),
            status = status,
            allowedTools = buildList { for (index in 0 until tools.length()) add(tools.optString(index)) }
        )
    }

    private fun read(context: Context): JSONObject {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DESKTOPS, "{}").orEmpty()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun write(context: Context, root: JSONObject) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DESKTOPS, root.toString()).apply()
    }
}
