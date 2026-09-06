package com.galaxyssi.chat

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.File
import java.security.MessageDigest

/**
 * Replaces inline rich-output payloads with durable app-private files.
 *
 * Connector replies can contain hundreds of kilobytes of Base64 data. Keeping
 * that data in transcripts and recovery stores multiplies parsing, encryption,
 * and allocation costs on every subsequent turn.
 */
object AgentRichContentMaterializer {
    private const val DIRECTORY_NAME = "agent-rich-output-v2"
    private const val MAX_MATERIALIZED_BYTES = 4 * 1024 * 1024

    @Synchronized
    fun materialize(context: Context, raw: String): String {
        val normalized = AgentRichContentCodec.normalize(raw)
        if (normalized.isBlank()) return ""
        val blocks = AgentRichContentCodec.decode(normalized)
        if (blocks.none {
                it.dataB64.isNotBlank() ||
                    Uri.parse(it.uri).scheme == "galaxyssi-artifact"
            }
        ) return normalized

        val directory = File(context.applicationContext.filesDir, DIRECTORY_NAME)
        val materialized = blocks.map { block ->
            val resolved = AgentDesktopArtifactStore.resolveBlock(context.applicationContext, block)
            materializeBlock(context.applicationContext, directory, resolved) ?: resolved
        }
        return AgentRichContentCodec.encode(materialized)
    }

    private fun materializeBlock(
        context: Context,
        directory: File,
        block: AgentRichBlock
    ): AgentRichBlock? {
        if (block.dataB64.isBlank()) return block
        val bytes = runCatching { Base64.decode(block.dataB64, Base64.DEFAULT) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() && it.size <= MAX_MATERIALIZED_BYTES }
            ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val target = File(directory, "$digest.sasie")
        try {
            val alreadyStored = runCatching {
                AttachmentLocalStore.metadata(target).plaintextLength == bytes.size.toLong()
            }.getOrDefault(false)
            if (!alreadyStored) {
                if (!directory.exists() && !directory.mkdirs()) return null
                if (runCatching { AttachmentLocalStore.storeBytes(bytes, target) }.isFailure) {
                    return null
                }
            }
            val displayName = block.title.ifBlank { "$digest.${extensionFor(block)}" }
            return block.copy(
                uri = LocalAttachmentUris.forFile(
                    context,
                    target,
                    displayName,
                    block.mimeType
                ).toString(),
                dataB64 = "",
                metadata = block.metadata + mapOf(
                    "size_bytes" to bytes.size.toString(),
                    "sha256" to digest,
                    "storage" to "keystore_aes_256_gcm"
                )
            )
        } finally {
            bytes.fill(0)
        }
    }

    private fun extensionFor(block: AgentRichBlock): String = when (block.mimeType.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heic", "image/heif" -> "heic"
        "audio/mpeg" -> "mp3"
        "audio/mp4", "audio/x-m4a" -> "m4a"
        "audio/wav", "audio/x-wav" -> "wav"
        "video/mp4" -> "mp4"
        "application/pdf" -> "pdf"
        "application/zip" -> "zip"
        "text/plain" -> "txt"
        else -> when (block.type) {
            AgentRichBlockType.IMAGE, AgentRichBlockType.GALLERY -> "img"
            AgentRichBlockType.AUDIO -> "audio"
            AgentRichBlockType.VIDEO -> "video"
            else -> "bin"
        }
    }
}
