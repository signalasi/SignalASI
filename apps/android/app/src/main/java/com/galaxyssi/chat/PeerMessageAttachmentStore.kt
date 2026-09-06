package com.galaxyssi.chat

import android.content.Context
import android.net.Uri
import java.io.File

/** Durable local copies for attachments displayed in peer chat history. */
internal object PeerMessageAttachmentStore {
    private const val ROOT = "peer-message-attachments-v2"
    private const val OUTGOING_VOICE = "outgoing/voice"
    private val displayNamePattern = Regex("voice-(\\d+)\\.(wav|m4a|opus)", RegexOption.IGNORE_CASE)
    private val storedNamePattern = Regex("msg_(\\d+)\\.(wav|m4a|opus)\\.sasie", RegexOption.IGNORE_CASE)

    fun persistOutgoingVoice(
        filesDir: File,
        cacheDir: File,
        source: File,
        messageId: Long,
        extension: String
    ): Result<File> = runCatching {
        require(source.isFile && source.length() > 0L) { "Voice recording is unavailable" }
        val normalizedExtension = extension.lowercase().takeIf { it in setOf("wav", "m4a", "opus") } ?: "wav"
        val directory = File(filesDir, "$ROOT/$OUTGOING_VOICE").apply {
            check(mkdirs() || isDirectory) { "Voice message storage is unavailable" }
        }
        val destination = File(directory, "msg_${messageId}.$normalizedExtension.sasie")
        if (source.canonicalFile == destination.canonicalFile) return@runCatching destination

        AttachmentLocalStore.storeFile(source, destination)
        check(AttachmentLocalStore.metadata(destination).plaintextLength == source.length())
        if (isInside(cacheDir, source)) source.delete()
        destination
    }

    fun persistOutgoingVoiceBytes(
        filesDir: File,
        encoded: ByteArray,
        messageId: Long,
        extension: String
    ): Result<File> = runCatching {
        require(encoded.isNotEmpty()) { "Voice recording is unavailable" }
        val normalizedExtension = extension.lowercase().takeIf { it in setOf("wav", "m4a", "opus") } ?: "opus"
        val directory = File(filesDir, "$ROOT/$OUTGOING_VOICE").apply {
            check(mkdirs() || isDirectory) { "Voice message storage is unavailable" }
        }
        val destination = File(directory, "msg_${messageId}.$normalizedExtension.sasie")
        AttachmentLocalStore.storeBytes(encoded, destination)
        check(AttachmentLocalStore.metadata(destination).plaintextLength == encoded.size.toLong())
        destination
    }

    fun resolveAudio(context: Context, name: String, source: Uri?): Uri? {
        if (source != null && source.scheme != "file") return source
        val sourceFile = source?.path?.let(::File)
        if (sourceFile?.isFile == true) {
            if (isInside(File(context.filesDir, ROOT), sourceFile)) {
                return LocalAttachmentUris.forFile(context, sourceFile, name)
            }
            if (!isInside(context.cacheDir, sourceFile)) return source
            val identity = voiceIdentity(name, sourceFile.name) ?: return source
            return persistOutgoingVoice(
                context.filesDir,
                context.cacheDir,
                sourceFile,
                identity.first,
                identity.second
            ).getOrNull()?.let { LocalAttachmentUris.forFile(context, it, name) } ?: source
        }
        return resolveOutgoingVoice(context.filesDir, name)?.let {
            LocalAttachmentUris.forFile(context, it, name)
        }
    }

    internal fun resolveOutgoingVoice(filesDir: File, name: String): File? {
        val identity = voiceIdentity(name, name) ?: return null
        return File(filesDir, "$ROOT/$OUTGOING_VOICE/msg_${identity.first}.${identity.second}.sasie")
            .takeIf(File::isFile)
    }

    internal fun shouldPruneIncoming(
        receivedAt: Long,
        hasCompletedData: Boolean,
        now: Long,
        maxAgeMillis: Long
    ): Boolean = !hasCompletedData && (
        receivedAt <= 0L || now - receivedAt > maxAgeMillis
    )

    private fun voiceIdentity(displayName: String, storedName: String): Pair<Long, String>? {
        val match = displayNamePattern.matchEntire(displayName)
            ?: storedNamePattern.matchEntire(storedName)
            ?: return null
        val messageId = match.groupValues[1].toLongOrNull() ?: return null
        val extension = match.groupValues[2].lowercase()
        return messageId to extension
    }

    private fun isInside(root: File, candidate: File): Boolean = runCatching {
        candidate.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())
    }.getOrDefault(false)
}
