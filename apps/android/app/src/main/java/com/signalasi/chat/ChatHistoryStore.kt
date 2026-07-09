package com.signalasi.chat

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class StoredIncomingMessage(
    val contactId: String,
    val contactName: String,
    val content: String,
    val messageId: Long = 0L,
    val notify: Boolean = true
)

object ChatHistoryStore {
    private const val HISTORY_PREFS = "signalasi_chat_history"
    private const val OLD_HISTORY_PREFS = "hermes_chat_history"
    private const val HISTORY_KEY = "messages"
    private const val HISTORY_UPDATED_KEY = "updated_at"
    private const val MAX_SAVED_MESSAGES_PER_CONTACT = 500
    private const val CONTACT_HERMES = "hermes"
    private const val CONTACT_SYSTEM = "system"

    @Synchronized
    fun appendOutgoing(
        context: Context,
        contactId: String,
        content: String,
        deliveryStatus: String = "",
        deliveryTrace: JSONArray = JSONArray()
    ): Long {
        val cleanContent = content.trim()
        if (contactId.isBlank() || cleanContent.isBlank()) return 0L
        val appContext = context.applicationContext
        AppStore.ensureInitialized(appContext)
        migrateHistoryPrefs(appContext)
        val prefs = appContext.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
        val root = runCatching {
            JSONObject(prefs.getString(HISTORY_KEY, null).orEmpty())
        }.getOrElse { JSONObject() }
        val nextId = maxMessageId(root) + 1L
        val trace = JSONArray()
        for (index in 0 until deliveryTrace.length()) {
            deliveryTrace.optJSONObject(index)?.let { trace.put(it) }
        }
        appendTrace(trace, "created", "agent_runtime")
        val array = root.optJSONArray(contactId) ?: JSONArray()
        array.put(JSONObject()
            .put("id", nextId)
            .put("content", cleanContent)
            .put("isMine", true)
            .put("contactId", contactId)
            .put("isSystem", false)
            .put("timestamp", System.currentTimeMillis())
            .put("deliveryStatus", deliveryStatus)
            .put("deliveryTrace", trace))
        root.put(contactId, trim(array))
        prefs.edit()
            .putString(HISTORY_KEY, root.toString())
            .putLong(HISTORY_UPDATED_KEY, System.currentTimeMillis())
            .apply()
        return nextId
    }

    @Synchronized
    fun appendIncoming(context: Context, payload: String): StoredIncomingMessage? {
        val appContext = context.applicationContext
        AppStore.ensureInitialized(appContext)
        migrateHistoryPrefs(appContext)
        val parsed = parseIncoming(appContext, payload)
        if (parsed.content.isBlank()) return null
        if (parsed.contactId == CONTACT_HERMES) {
            AppStore.markHermesVerified(appContext)
        }
        val incomingEnvelope = runCatching { JSONObject(payload) }.getOrNull()
        val deliveryTrace = parseDeliveryTrace(incomingEnvelope)
        appendTrace(deliveryTrace, "received", "background_service")
        appendTrace(deliveryTrace, "decrypted", "SignalASI Link")
        appendTrace(deliveryTrace, "persisted", "background_history")

        val prefs = appContext.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
        val root = runCatching {
            JSONObject(prefs.getString(HISTORY_KEY, null).orEmpty())
        }.getOrElse { JSONObject() }
        val nextId = maxMessageId(root) + 1L
        val array = root.optJSONArray(parsed.contactId) ?: JSONArray()
        array.put(JSONObject()
            .put("id", nextId)
            .put("content", parsed.content)
            .put("isMine", false)
            .put("contactId", parsed.contactId)
            .put("isSystem", false)
            .put("timestamp", System.currentTimeMillis())
            .put("deliveryStatus", "")
            .put("deliveryTrace", deliveryTrace))
        root.put(parsed.contactId, trim(array))
        prefs.edit()
            .putString(HISTORY_KEY, root.toString())
            .putLong(HISTORY_UPDATED_KEY, System.currentTimeMillis())
            .apply()
        return parsed.copy(messageId = nextId)
    }

    @Synchronized
    fun markNotified(context: Context, contactId: String, messageId: Long) {
        markMessageTrace(
            context,
            contactId,
            messageId,
            "notified",
            "system_notification",
            context.getString(R.string.delivery_status_notified)
        )
    }

    @Synchronized
    fun markOutgoingDelivery(context: Context, contactId: String, messageId: Long, stage: String, detail: String, status: String) {
        markMessageTrace(context, contactId, messageId, stage, detail, status)
    }

    private fun markMessageTrace(context: Context, contactId: String, messageId: Long, stage: String, detail: String, status: String) {
        if (contactId.isBlank() || messageId <= 0L) return
        val appContext = context.applicationContext
        migrateHistoryPrefs(appContext)
        val prefs = appContext.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
        val root = runCatching {
            JSONObject(prefs.getString(HISTORY_KEY, null).orEmpty())
        }.getOrElse { JSONObject() }
        val array = root.optJSONArray(contactId) ?: return
        var changed = false
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optLong("id", -1L) != messageId) continue
            val trace = item.optJSONArray("deliveryTrace") ?: JSONArray()
            if (!hasTraceStage(trace, stage)) {
                appendTrace(trace, stage, detail)
                item.put("deliveryTrace", trace)
                changed = true
            }
            if (item.optString("deliveryStatus") != status) {
                item.put("deliveryStatus", status)
                changed = true
            }
            break
        }
        if (!changed) return
        root.put(contactId, array)
        prefs.edit()
            .putString(HISTORY_KEY, root.toString())
            .putLong(HISTORY_UPDATED_KEY, System.currentTimeMillis())
            .apply()
    }

    private fun parseIncoming(context: Context, payload: String): StoredIncomingMessage {
        val json = runCatching { JSONObject(payload) }.getOrNull()
        return when (json?.optString("type").orEmpty()) {
            "pairing_revoked" -> {
                AppStore.deleteContact(context, CONTACT_HERMES, deleteMessages = false)
                StoredIncomingMessage(
                    CONTACT_SYSTEM,
                    context.getString(R.string.system_contact_name),
                    json?.optString("content")
                        ?.ifBlank { context.getString(R.string.system_pairing_revoked_default) }
                        ?: context.getString(R.string.system_pairing_revoked_default)
                )
            }
            "pairing_confirmed", "connector_status" -> {
                json?.optJSONArray("connector_agents")?.let { AppStore.updateConnectorAgentStatuses(context, it) }
                StoredIncomingMessage(
                    CONTACT_SYSTEM,
                    context.getString(R.string.system_contact_name),
                    json?.optString("content")?.ifBlank { context.getString(R.string.system_connector_status_updated) }
                        ?: context.getString(R.string.system_connector_status_updated)
                )
            }
            "profile_update" -> {
                val profile = json ?: JSONObject()
                val senderId = profile.optString("sender")
                    .ifBlank { profile.optString("signalasi_id") }
                    .ifBlank { profile.optString("hermes_id") }
                val newName = profile.optString("name")
                if (senderId.isNotBlank() && newName.isNotBlank()) {
                    AppStore.updateContactName(context, senderId, newName)
                }
                val label = newName.ifBlank { senderId.ifBlank { context.getString(R.string.fallback_contact_name) } }
                StoredIncomingMessage(
                    CONTACT_SYSTEM,
                    context.getString(R.string.system_contact_name),
                    context.getString(R.string.system_profile_updated, label)
                )
            }
            else -> {
                val content = json?.optString("content", payload)?.takeIf { it.isNotBlank() } ?: payload
                val sender = json?.optString("sender", CONTACT_HERMES) ?: CONTACT_HERMES
                val contactId = when {
                    sender == "system" -> CONTACT_SYSTEM
                    else -> json?.optString("contact_id", CONTACT_HERMES)?.takeIf { it.isNotBlank() } ?: CONTACT_HERMES
                }
                StoredIncomingMessage(contactId, contactName(context, contactId), content)
            }
        }
    }

    private fun contactName(context: Context, contactId: String): String {
        if (contactId == CONTACT_SYSTEM) return context.getString(R.string.system_contact_name)
        return AppStore.contactById(context, contactId)
            ?.optString("name")
            ?.takeIf { it.isNotBlank() }
            ?: when (contactId) {
                CONTACT_HERMES -> "Hermes Agent"
                "codex" -> "Codex Agent"
                "claude" -> "Claude Code"
                "local-llm" -> "Local LLM"
                "cloud-model" -> "Cloud Model"
                "custom-agent" -> "Custom Agent"
                else -> contactId
            }
    }

    private fun maxMessageId(root: JSONObject): Long {
        var max = 0L
        val keys = root.keys()
        while (keys.hasNext()) {
            val array = root.optJSONArray(keys.next()) ?: continue
            for (i in 0 until array.length()) {
                max = maxOf(max, array.optJSONObject(i)?.optLong("id", 0L) ?: 0L)
            }
        }
        return max
    }

    private fun parseDeliveryTrace(json: JSONObject?): JSONArray {
        val source = json?.optJSONArray("delivery_trace") ?: json?.optJSONArray("deliveryTrace")
        val trace = JSONArray()
        if (source != null) {
            for (i in 0 until source.length()) {
                source.optJSONObject(i)?.let { trace.put(it) }
            }
        }
        return trace
    }

    private fun appendTrace(trace: JSONArray, stage: String, detail: String = "") {
        trace.put(JSONObject()
            .put("stage", stage)
            .put("at", System.currentTimeMillis())
            .put("detail", detail))
    }

    private fun hasTraceStage(trace: JSONArray, stage: String): Boolean {
        for (i in 0 until trace.length()) {
            if (trace.optJSONObject(i)?.optString("stage") == stage) return true
        }
        return false
    }

    private fun migrateHistoryPrefs(context: Context) {
        val oldPrefs = context.getSharedPreferences(OLD_HISTORY_PREFS, Context.MODE_PRIVATE)
        val newPrefs = context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
        if (oldPrefs.all.isEmpty() || newPrefs.all.isNotEmpty()) return
        val editor = newPrefs.edit()
        copySharedPreferences(oldPrefs, editor)
        editor.commit()
    }

    private fun copySharedPreferences(from: SharedPreferences, to: SharedPreferences.Editor) {
        from.all.forEach { (key, value) ->
            when (value) {
                is String -> to.putString(key, value)
                is Int -> to.putInt(key, value)
                is Long -> to.putLong(key, value)
                is Boolean -> to.putBoolean(key, value)
                is Float -> to.putFloat(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    to.putStringSet(key, value as Set<String>)
                }
            }
        }
    }

    private fun trim(array: JSONArray): JSONArray {
        if (array.length() <= MAX_SAVED_MESSAGES_PER_CONTACT) return array
        val trimmed = JSONArray()
        val start = array.length() - MAX_SAVED_MESSAGES_PER_CONTACT
        for (i in start until array.length()) {
            trimmed.put(array.optJSONObject(i))
        }
        return trimmed
    }
}
