package com.signalasi.chat

import android.content.Context
import org.json.JSONObject

data class VoiceAgentTranscript(
    val requestId: Long,
    val content: String,
    val success: Boolean
)

class VoiceAgentTranscriptStore(context: Context) {
    private val preferences = AgentEncryptedPreferences(context.applicationContext, PREFS)

    fun begin(requestId: Long) {
        if (requestId <= 0L) return
        preferences.writeString(
            KEY_STATE,
            JSONObject()
                .put("request_id", requestId)
                .put("content", "")
                .put("success", false)
                .put("received", false)
                .put("created_at", System.currentTimeMillis())
                .toString()
        )
    }

    fun pendingRequestId(): Long {
        val current = state()
        val createdAt = current.optLong("created_at", 0L)
        if (createdAt <= 0L || System.currentTimeMillis() - createdAt > MAX_PENDING_AGE_MILLIS) {
            clear()
            return 0L
        }
        return current.optLong("request_id", 0L)
    }

    fun saveResponse(payload: JSONObject): Boolean {
        val requestId = payload.optString("source_message_id").toLongOrNull()
            ?: payload.optLong("source_message_id", 0L)
        val current = state()
        if (requestId <= 0L || requestId != current.optLong("request_id", 0L)) return false
        preferences.writeString(
            KEY_STATE,
            JSONObject()
                .put("request_id", requestId)
                .put("content", payload.optString("content").take(MAX_TRANSCRIPT_CHARACTERS))
                .put("success", payload.optBoolean("transcription_success", false))
                .put("received", true)
                .put("created_at", current.optLong("created_at", System.currentTimeMillis()))
                .toString()
        )
        return true
    }

    fun consume(): VoiceAgentTranscript? {
        val current = state()
        if (!current.optBoolean("received", false)) return null
        val transcript = VoiceAgentTranscript(
            requestId = current.optLong("request_id", 0L),
            content = current.optString("content"),
            success = current.optBoolean("success", false)
        )
        clear()
        return transcript.takeIf { it.requestId > 0L }
    }

    fun clear() = preferences.clear()

    private fun state(): JSONObject = runCatching {
        JSONObject(preferences.readString(KEY_STATE, "{}"))
    }.getOrDefault(JSONObject())

    private companion object {
        const val PREFS = "signalasi_voice_agent_transcript"
        const val KEY_STATE = "state"
        const val MAX_TRANSCRIPT_CHARACTERS = 12_000
        const val MAX_PENDING_AGE_MILLIS = 120_000L
    }
}
