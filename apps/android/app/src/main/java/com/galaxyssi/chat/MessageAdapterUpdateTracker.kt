package com.galaxyssi.chat

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

internal data class MessageRowSnapshot(
    val id: Long,
    val content: String,
    val isMine: Boolean,
    val contact: Contact,
    val isSystem: Boolean,
    val timestamp: Long,
    val showTimeDivider: Boolean,
    val attachments: List<PeerChatAttachment>,
    val voiceTranscript: String,
    val voiceTranscriptionPending: Boolean
)

internal object MessageRowSnapshotFactory {
    const val ATTACHMENT_PROGRESS = "attachment-progress"
    private const val TIME_DIVIDER_GAP_MILLIS = 30 * 60 * 1000L

    fun from(messages: List<ChatMessage>): List<MessageRowSnapshot> =
        messages.mapIndexed { index, message ->
            val previous = messages.getOrNull(index - 1)
            MessageRowSnapshot(
                id = message.id,
                content = message.content,
                isMine = message.isMine,
                contact = message.contact,
                isSystem = message.isSystem,
                timestamp = message.timestamp,
                showTimeDivider = previous == null ||
                    message.timestamp - previous.timestamp >= TIME_DIVIDER_GAP_MILLIS ||
                    dateKey(previous.timestamp) != dateKey(message.timestamp),
                attachments = message.attachments.toList(),
                voiceTranscript = message.voiceTranscript,
                voiceTranscriptionPending = message.voiceTranscriptionPending
            )
        }

    fun progressOnly(previous: MessageRowSnapshot, next: MessageRowSnapshot): Boolean {
        if (previous.attachments.size != next.attachments.size || previous.attachments.isEmpty()) return false
        val normalized = next.attachments.mapIndexed { index, attachment ->
            val old = previous.attachments[index]
            if (attachment.transferProgress != old.transferProgress &&
                (attachment.transferState != old.transferState || attachment.transferState !in setOf("uploading", "downloading"))) return false
            attachment.copy(transferProgress = old.transferProgress)
        }
        return previous != next && next.copy(attachments = normalized) == previous
    }
}

internal object MessageListSnapshot {
    fun copy(messages: List<ChatMessage>): List<ChatMessage> = messages.toList()
}

internal class MessageAdapterUpdateTracker(messages: List<ChatMessage>) {
    private var rendered = MessageRowSnapshotFactory.from(messages)

    fun sync(messages: List<ChatMessage>, adapter: RecyclerView.Adapter<*>) {
        val next = MessageRowSnapshotFactory.from(messages)
        val previous = rendered
        val result = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = previous.size

            override fun getNewListSize(): Int = next.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                previous[oldItemPosition].id == next[newItemPosition].id

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                previous[oldItemPosition] == next[newItemPosition]

            override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any? =
                if (MessageRowSnapshotFactory.progressOnly(previous[oldItemPosition], next[newItemPosition]))
                    MessageRowSnapshotFactory.ATTACHMENT_PROGRESS else null
        }, false)
        rendered = next
        result.dispatchUpdatesTo(adapter)
    }
}
