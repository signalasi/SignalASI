package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object SignalASILinkDeliveryStore {
    private const val PREFS = "signalasi_link_delivery_v1"
    private const val KEY_OUTBOX = "outbox"
    private const val KEY_INBOX = "inbox"
    private const val MAX_INBOX_IDS = 4096
    private const val MAX_RETRY_COUNT = 8

    data class PendingMessage(
        val messageId: String,
        val topic: String,
        val wirePayload: String,
        val attempts: Int,
        val createdAt: Long
    )

    @Synchronized
    fun enqueue(context: Context, messageId: String, topic: String, wirePayload: String) {
        val values = outboxArray(context)
        for (index in 0 until values.length()) {
            if (values.optJSONObject(index)?.optString("message_id") == messageId) return
        }
        values.put(
            JSONObject()
                .put("message_id", messageId)
                .put("topic", topic)
                .put("wire_payload", wirePayload)
                .put("status", "queued")
                .put("attempts", 0)
                .put("next_attempt_at", System.currentTimeMillis())
                .put("created_at", System.currentTimeMillis())
                .put("updated_at", System.currentTimeMillis())
        )
        writeArray(context, KEY_OUTBOX, values)
    }

    @Synchronized
    fun markPublished(context: Context, messageId: String) {
        updateOutbox(context, messageId) { item ->
            item.put("status", "published")
                .put("next_attempt_at", System.currentTimeMillis() + 30_000L)
                .put("updated_at", System.currentTimeMillis())
        }
    }

    @Synchronized
    fun markAttempt(context: Context, messageId: String) {
        updateOutbox(context, messageId) { item ->
            val attempts = item.optInt("attempts") + 1
            val delayMs = (2_000L shl (attempts - 1).coerceAtMost(7)).coerceAtMost(300_000L)
            item.put("status", "publishing")
                .put("attempts", attempts)
                .put("next_attempt_at", System.currentTimeMillis() + delayMs)
                .put("updated_at", System.currentTimeMillis())
        }
    }

    @Synchronized
    fun acknowledge(context: Context, messageId: String) {
        if (messageId.isBlank()) return
        val source = outboxArray(context)
        val kept = JSONArray()
        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: continue
            if (item.optString("message_id") != messageId) kept.put(item)
        }
        writeArray(context, KEY_OUTBOX, kept)
    }

    @Synchronized
    fun discardRoutes(context: Context, routes: SignalASILinkProtocol.Routes): Int {
        val source = outboxArray(context)
        val discardedTopics = setOf(routes.up, routes.down, routes.control, routes.pairing)
        val kept = retainMessagesOutsideTopics(source, discardedTopics)
        val removed = source.length() - kept.length()
        if (removed > 0) writeArray(context, KEY_OUTBOX, kept)
        return removed
    }

    @Synchronized
    fun pending(context: Context): List<PendingMessage> {
        val values = outboxArray(context)
        return buildList {
            for (index in 0 until values.length()) {
                val item = values.optJSONObject(index) ?: continue
                if (item.optInt("attempts") >= MAX_RETRY_COUNT) continue
                if (item.optLong("next_attempt_at") > System.currentTimeMillis()) continue
                add(
                    PendingMessage(
                        item.optString("message_id"),
                        item.optString("topic"),
                        item.optString("wire_payload"),
                        item.optInt("attempts"),
                        item.optLong("created_at")
                    )
                )
            }
        }
    }

    @Synchronized
    fun claimIncoming(context: Context, messageId: String): Boolean {
        if (messageId.isBlank()) return false
        val values = readArray(context, KEY_INBOX)
        for (index in 0 until values.length()) {
            if (values.optString(index) == messageId) return false
        }
        values.put(messageId)
        val trimmed = JSONArray()
        val start = (values.length() - MAX_INBOX_IDS).coerceAtLeast(0)
        for (index in start until values.length()) trimmed.put(values.optString(index))
        writeArray(context, KEY_INBOX, trimmed)
        return true
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun updateOutbox(context: Context, messageId: String, block: (JSONObject) -> Unit) {
        val values = outboxArray(context)
        for (index in 0 until values.length()) {
            val item = values.optJSONObject(index) ?: continue
            if (item.optString("message_id") == messageId) block(item)
        }
        writeArray(context, KEY_OUTBOX, values)
    }

    private fun outboxArray(context: Context): JSONArray = readArray(context, KEY_OUTBOX)

    private fun readArray(context: Context, key: String): JSONArray {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "[]") ?: "[]"
        return runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    }

    private fun writeArray(context: Context, key: String, value: JSONArray) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, value.toString()).commit()
    }

    internal fun retainMessagesOutsideTopics(source: JSONArray, discardedTopics: Set<String>): JSONArray {
        if (discardedTopics.isEmpty()) return JSONArray(source.toString())
        val kept = JSONArray()
        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: continue
            if (item.optString("topic") !in discardedTopics) kept.put(JSONObject(item.toString()))
        }
        return kept
    }
}
