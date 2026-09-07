package com.galaxyssi.chat.blob

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/** Ephemeral local UI subscribers; attaching a card never subscribes MQTT or replays chat. */
internal object BlobArtifactCardUpdates {
    interface Listener { fun onArtifactEvent(event: JSONObject) }
    private val listeners = CopyOnWriteArrayList<WeakReference<Listener>>()
    private val main = Handler(Looper.getMainLooper())

    fun add(listener: Listener) {
        remove(listener)
        listeners += WeakReference(listener)
    }

    fun remove(listener: Listener) {
        listeners.removeAll { it.get() == null || it.get() === listener }
    }

    fun publish(event: JSONObject) {
        if (!BlobArtifactPresentation.isEvent(event) || event.opt("peer_chat") != false || listeners.isEmpty()) return
        // Only the sanitized local event fields may outlive the transport callback.
        val copy = JSONObject()
        listOf("type", "blob_publication", "peer_chat", "client_route_id", "desktop_id", "conversation_id",
            "task_id", "turn_id", "execution_generation", "transfer_id", "artifact_uri", "sha256", "size_bytes",
            "progress", "error_code").forEach { key -> if (event.has(key)) copy.put(key, event.get(key)) }
        val recipients = listeners.toList()
        main.post {
            recipients.forEach { reference ->
                if (reference in listeners) reference.get()?.let { runCatching { it.onArtifactEvent(copy) } }
            }
            listeners.removeAll { it.get() == null }
        }
    }
}
