package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal object DebugChatHistoryProbe {
    private const val DEFAULT_PAGE_SIZE = 200
    private const val MAX_PAGE_SIZE = 500

    fun run(context: Context, request: JSONObject): JSONObject {
        val requestId = request.optString("request_id")
        val contactId = request.optString("contact_id").trim()
        require(requestId.isNotBlank()) { "request_id is required" }
        require(contactId.isNotBlank()) { "contact_id is required" }

        val contentToken = request.optString("content_token")
        val requiredStages = stringSet(request.optJSONArray("required_stages"))
        val pageSize = request.optInt("page_size", DEFAULT_PAGE_SIZE)
            .coerceIn(1, MAX_PAGE_SIZE)
        val page = ChatHistoryStore.page(context, contactId, pageSize = pageSize)
        val matches = page.messages.filter { message ->
            contentToken.isBlank() || message.optString("content").contains(contentToken)
        }
        val matchedStages = linkedSetOf<String>()
        var matchedReadCount = 0
        matches.forEach { message ->
            if (message.optBoolean("isRead")) matchedReadCount += 1
            val trace = message.optJSONArray("deliveryTrace") ?: JSONArray()
            for (index in 0 until trace.length()) {
                trace.optJSONObject(index)
                    ?.optString("stage")
                    ?.takeIf(String::isNotBlank)
                    ?.let(matchedStages::add)
            }
        }

        var deletedCount = 0
        if (request.optBoolean("delete_matches", false)) {
            matches.forEach { message ->
                val messageId = message.optLong("id", 0L)
                if (messageId > 0L && ChatHistoryStore.deleteMessage(context, messageId)) {
                    deletedCount += 1
                }
            }
        }

        return JSONObject()
            .put("request_id", requestId)
            .put("contact_id", contactId)
            .put("storage", "encrypted_sqlite")
            .put("scanned_count", page.messages.size)
            .put("match_count", matches.size)
            .put("matched_read_count", matchedReadCount)
            .put("matched_unread_count", matches.size - matchedReadCount)
            .put("has_more", page.hasMore)
            .put("matched_stages", JSONArray(matchedStages.toList()))
            .put("missing_stages", JSONArray((requiredStages - matchedStages).toList()))
            .put("deleted_count", deletedCount)
    }

    private fun stringSet(source: JSONArray?): Set<String> {
        if (source == null) return emptySet()
        return buildSet {
            for (index in 0 until source.length()) {
                source.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }
}
