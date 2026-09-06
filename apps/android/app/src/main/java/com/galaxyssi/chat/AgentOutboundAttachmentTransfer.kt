package com.galaxyssi.chat

import android.content.Context
import android.util.Base64
import com.galaxyssi.chat.blob.BlobChunkInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

private fun outboundChunkFile(directory: File, index: Int): File =
    File(directory, "chunk-${index.toString().padStart(6, '0')}.sasie")

internal data class AgentAttachmentTransferScope(
    val contactId: String,
    val desktopId: String,
    val clientRouteId: String,
    val conversationId: String,
    val taskId: String,
    val turnId: String,
    val clientMessageId: Long?,
    val attachmentRequestId: String = "",
    val durationMillis: Long = 0L
) {
    init {
        require(contactId.isNotBlank() && contactId.length <= 256)
        require(desktopId.isNotBlank() && desktopId.length <= 256)
        require(GalaxySSILinkProtocol.validRouteId(clientRouteId))
        require(conversationId.isNotBlank() && conversationId.length <= 256)
        require(taskId.isNotBlank() && taskId.length <= 256)
        require(turnId.isNotBlank() && turnId.length <= 256)
        require(attachmentRequestId.isBlank() || attachmentRequestId.matches(REQUEST_ID))
    }

    private companion object {
        val REQUEST_ID = Regex("[a-f0-9]{32}")
    }
}

internal data class AgentPreparedOutboundAttachment(
    val transferId: String,
    val attachmentId: String,
    val ordinal: Int,
    val name: String,
    val originalName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val originalSizeBytes: Long,
    val sha256: String,
    val chunkCount: Int,
    val chunkSizeBytes: Int,
    val transportProfile: String,
    val requiresValidatedNetwork: Boolean,
    val scope: AgentAttachmentTransferScope,
    private val chunkDirectory: File
) {
    fun descriptor(): JSONObject = JSONObject()
        .put("id", attachmentId)
        .put("transfer_id", transferId)
        .put("name", name)
        .put("original_name", originalName)
        .put("mime_type", mimeType)
        .put("size", sizeBytes)
        .put("transport_size", sizeBytes)
        .put("original_size", originalSizeBytes)
        .put("sha256", sha256)
        .put("chunk_count", chunkCount)
        .put("chunk_size_bytes", chunkSizeBytes)
        .put("transport_profile", transportProfile)
        .put("transport_status", "chunked")
        .also { if (scope.durationMillis > 0L) it.put("duration_ms", scope.durationMillis) }

    fun manifestPayload(resume: Boolean, eagerChunks: Boolean = false): JSONObject =
        commonPayload("input_attachment_manifest")
            .put("resume", resume)
            .put("eager_chunks", eagerChunks)

    fun chunkPayload(index: Int): JSONObject {
        val bytes = readPlainChunk(index)
        return try {
            commonPayload("input_attachment_chunk")
                .put("chunk_index", index)
                .put("chunk_size", bytes.size)
                .put("chunk_sha256", AgentAttachmentTransferProtocol.sha256(bytes))
                .put("data_b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
        } finally {
            bytes.wipeSensitive()
        }
    }

    fun openPlaintext(): InputStream = BlobChunkInputStream(chunkCount, sizeBytes) { index ->
        try { readPlainChunk(index) }
        catch (_: javax.crypto.AEADBadTagException) {
            throw com.galaxyssi.chat.blob.BlobFailure("local_chunk_missing_or_corrupt", 409)
        } catch (_: IllegalArgumentException) {
            throw com.galaxyssi.chat.blob.BlobFailure("local_chunk_missing_or_corrupt", 409)
        }
    }

    private fun readPlainChunk(index: Int): ByteArray {
        require(index in 0 until chunkCount) { "Attachment chunk index is invalid" }
        val start = index.toLong() * chunkSizeBytes
        val expected = minOf(
            chunkSizeBytes.toLong(),
            sizeBytes - start
        ).toInt()
        require(expected > 0) { "Attachment chunk is empty" }
        val storedChunk = outboundChunkFile(chunkDirectory, index)
        require(storedChunk.length() <= expected + 128L &&
            AttachmentLocalStore.metadata(storedChunk).plaintextLength == expected.toLong()) {
            "Attachment chunk length is invalid"
        }
        val bytes = AttachmentLocalStore.readBytes(storedChunk)
        if (bytes.size != expected) {
            bytes.wipeSensitive()
            throw IllegalArgumentException("Attachment chunk length is invalid")
        }
        return bytes
    }

    private fun commonPayload(type: String): JSONObject = JSONObject()
        .put("type", type)
        .put("transfer_id", transferId)
        .put("attachment_id", attachmentId)
        .put("attachment_ordinal", ordinal)
        .put("name", name)
        .put("original_name", originalName)
        .put("mime_type", mimeType)
        .put("size_bytes", sizeBytes)
        .put("original_size_bytes", originalSizeBytes)
        .put("sha256", sha256)
        .put("chunk_count", chunkCount)
        .put("chunk_size_bytes", chunkSizeBytes)
        .put("transport_profile", transportProfile)
        .put("contact_id", scope.contactId)
        .put("desktop_id", scope.desktopId)
        .put("client_route_id", scope.clientRouteId)
        .put("conversation_id", scope.conversationId)
        .put("task_id", scope.taskId)
        .put("turn_id", scope.turnId)
        .put("time", System.currentTimeMillis())
        .also { payload ->
            if (scope.durationMillis > 0L) payload.put("duration_ms", scope.durationMillis)
            scope.clientMessageId?.let { payload.put("client_message_id", it) }
            if (scope.attachmentRequestId.isNotBlank()) {
                payload.put("attachment_request_id", scope.attachmentRequestId)
            }
            if (requiresValidatedNetwork) payload.put("defer_media_upload", true)
        }
}

internal object AgentAttachmentTransferProtocol {
    private val SHA256 = Regex("[a-f0-9]{64}")

    fun transferId(
        scope: AgentAttachmentTransferScope,
        attachmentId: String,
        sha256: String
    ): String {
        require(sha256.matches(SHA256))
        val canonical = listOf(
            scope.clientRouteId,
            scope.conversationId,
            scope.taskId,
            scope.turnId,
            attachmentId,
            sha256
        ).let { parts ->
            if (scope.attachmentRequestId.isBlank()) parts else parts + scope.attachmentRequestId
        }.joinToString("\u0000")
        return sha256(canonical.toByteArray(Charsets.UTF_8))
    }

    fun missingRanges(indices: Collection<Int>): JSONArray {
        val ordered = indices.distinct().sorted()
        val ranges = JSONArray()
        var start: Int? = null
        var previous: Int? = null
        ordered.forEach { value ->
            require(value >= 0)
            if (start == null) {
                start = value
                previous = value
            } else if (value == previous!! + 1) {
                previous = value
            } else {
                ranges.put(JSONArray().put(start).put(previous))
                start = value
                previous = value
            }
        }
        if (start != null) ranges.put(JSONArray().put(start).put(previous))
        return ranges
    }

    fun expandMissingRanges(ranges: JSONArray?, chunkCount: Int): List<Int> {
        require(chunkCount in 1..AgentOutboundAttachmentTransferStore.MAX_CHUNKS)
        if (ranges == null) return emptyList()
        val result = linkedSetOf<Int>()
        for (index in 0 until ranges.length()) {
            val range = ranges.optJSONArray(index)
                ?: throw IllegalArgumentException("Attachment missing range is invalid")
            val start = range.optInt(0, -1)
            val end = range.optInt(1, -1)
            require(start in 0 until chunkCount && end in start until chunkCount) {
                "Attachment missing range is out of bounds"
            }
            for (value in start..end) result += value
        }
        return result.toList()
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

internal object AgentOutboundAttachmentTransferStore {
    data class StoredAcknowledgement(
        val transferId: String,
        val matchedMessages: Int,
        val releasedMessages: Int
    )

    // Fits one encrypted/base64 MQTT wire packet in the 512 KiB plaintext bucket.
    const val CHUNK_BYTES = 256 * 1024
    const val MAX_ATTACHMENT_BYTES = 1024L * 1024L * 1024L
    const val MAX_CHUNKS = (MAX_ATTACHMENT_BYTES / CHUNK_BYTES).toInt()
    private const val MAX_ATTACHMENTS_PER_TURN = 10
    private const val ROOT_DIRECTORY = "agent-link-outgoing-attachments-v2"
    private const val MANIFEST_FILE = "manifest.json"
    private const val CHUNKS_DIRECTORY = "chunks"
    private const val MAX_AGE_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    private val SHA256 = Regex("[a-f0-9]{64}")

    @Synchronized
    fun prepare(
        context: Context,
        scope: AgentAttachmentTransferScope,
        attachments: List<AgentInputAttachment>,
        mediaProfile: AgentMediaDeliveryProfile,
        preserveOriginalBytes: Boolean = false
    ): List<AgentPreparedOutboundAttachment> {
        require(attachments.size <= MAX_ATTACHMENTS_PER_TURN) { "Too many Agent attachments" }
        prune(context)
        return attachments.mapIndexed { ordinal, attachment ->
            prepareOne(
                context.applicationContext,
                scope,
                attachment,
                ordinal,
                mediaProfile,
                preserveOriginalBytes
            )
        }
    }

    @Synchronized
    fun pending(context: Context): List<AgentPreparedOutboundAttachment> {
        prune(context)
        return root(context).listFiles()
            .orEmpty()
            .filter(File::isDirectory)
            .mapNotNull(::readPrepared)
            .sortedBy { it.transferId }
    }

    @Synchronized
    fun find(context: Context, transferId: String): AgentPreparedOutboundAttachment? {
        if (!transferId.matches(SHA256)) return null
        return readPrepared(File(root(context), transferId))
    }

    @Synchronized
    fun discard(context: Context, transferIds: Collection<String>) {
        val normalized = transferIds
            .map(String::lowercase)
            .filter { it.matches(SHA256) }
            .toSet()
        if (normalized.isEmpty()) return
        normalized.forEach { transferId ->
            GalaxySSILinkDeliveryStore.discardAttachmentTransferMessages(context, transferId)
            transferDirectory(context, transferId).deleteRecursively()
        }
        GalaxySSILinkDeliveryStore.discardBlockedByAttachmentTransfers(context, normalized)
    }

    @Synchronized
    fun discardDesktop(context: Context, desktopId: String): Int {
        val cleanDesktopId = desktopId.trim()
        if (cleanDesktopId.isBlank()) return 0
        val transferIds = root(context).listFiles()
            .orEmpty()
            .filter(File::isDirectory)
            .mapNotNull(::readPrepared)
            .filter { it.scope.desktopId == cleanDesktopId }
            .map { it.transferId }
        discard(context, transferIds)
        return transferIds.size
    }

    @Synchronized
    fun acknowledgeStored(context: Context, payload: JSONObject): StoredAcknowledgement? {
        if (payload.optString("status") != "stored") return null
        val transfer = find(context, payload.optString("transfer_id").lowercase()) ?: return null
        if (
            payload.optString("sha256").lowercase() != transfer.sha256 ||
            payload.optString("client_route_id") != transfer.scope.clientRouteId ||
            payload.optString("conversation_id") != transfer.scope.conversationId ||
            payload.optString("task_id") != transfer.scope.taskId ||
            payload.optString("turn_id") != transfer.scope.turnId ||
            payload.optString("contact_id") != transfer.scope.contactId ||
            (
                transfer.scope.clientMessageId != null &&
                    payload.optString("source_message_id") !=
                    transfer.scope.clientMessageId.toString()
            )
        ) return null
        val release = GalaxySSILinkDeliveryStore.releaseAttachmentDependencyResult(
            context,
            transfer.transferId
        )
        GalaxySSILinkDeliveryStore.discardAttachmentTransferMessages(context, transfer.transferId)
        transferDirectory(context, transfer.transferId).deleteRecursively()
        return StoredAcknowledgement(
            transferId = transfer.transferId,
            matchedMessages = release.matchedMessages,
            releasedMessages = release.releasedMessages
        )
    }

    private fun prepareOne(
        context: Context,
        scope: AgentAttachmentTransferScope,
        attachment: AgentInputAttachment,
        ordinal: Int,
        mediaProfile: AgentMediaDeliveryProfile,
        preserveOriginalBytes: Boolean
    ): AgentPreparedOutboundAttachment {
        require(attachment.id.isNotBlank() && attachment.id.length <= 256)
        require(attachment.sizeBytes <= MAX_ATTACHMENT_BYTES) { "Agent attachment is too large" }
        val preparing = File(root(context), ".preparing-${UUID.randomUUID()}")
        check(preparing.mkdirs()) { "Attachment transfer staging is unavailable" }
        val stagedChunks = File(preparing, CHUNKS_DIRECTORY).apply {
            check(mkdirs() || isDirectory) { "Attachment transfer staging is unavailable" }
        }
        var transportName = attachment.displayName.ifBlank { "attachment-${ordinal + 1}" }
        var transportMime = attachment.mimeType.ifBlank { "application/octet-stream" }
        var transportSize = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            if (attachment.isImage && !preserveOriginalBytes) {
                val encoded = AgentImagePipeline.encodeForTransport(
                    context,
                    attachment,
                    mediaProfile.imageTargetBytes
                ) ?: error("Image attachment could not be prepared")
                require(encoded.bytes.isNotEmpty())
                transportName = encoded.transportName(transportName)
                transportMime = encoded.mimeType
                try {
                    transportSize = stageChunks(
                        ByteArrayInputStream(encoded.bytes),
                        encoded.bytes.size.toLong(),
                        stagedChunks,
                        digest
                    )
                } finally {
                    encoded.wipe()
                }
            } else {
                val input = context.contentResolver.openInputStream(attachment.uri)
                    ?: error("Attachment content is unavailable")
                input.buffered().use { source ->
                    transportSize = stageChunks(
                        source,
                        attachment.sizeBytes,
                        stagedChunks,
                        digest
                    )
                }
            }
            require(transportSize in 1..MAX_ATTACHMENT_BYTES) { "Agent attachment is empty" }
            val fullHash = digest.digest().joinToString("") { "%02x".format(it) }
            val transferId = AgentAttachmentTransferProtocol.transferId(
                scope,
                attachment.id,
                fullHash
            )
            val destination = transferDirectory(context, transferId)
            readPrepared(destination)?.let { existing ->
                preparing.deleteRecursively()
                return existing
            }
            destination.deleteRecursively()
            check(preparing.renameTo(destination)) { "Attachment transfer data could not be committed" }
            val chunkCount = ((transportSize + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()
            require(chunkCount in 1..MAX_CHUNKS)
            val manifest = JSONObject()
                .put("transfer_id", transferId)
                .put("attachment_id", attachment.id)
                .put("attachment_ordinal", ordinal)
                .put("name", transportName)
                .put("original_name", attachment.displayName)
                .put("mime_type", transportMime)
                .put("size_bytes", transportSize)
                .put("original_size_bytes", attachment.sizeBytes)
                .put("sha256", fullHash)
                .put("chunk_count", chunkCount)
                .put("chunk_size_bytes", CHUNK_BYTES)
                .put(
                    "transport_profile",
                    if (preserveOriginalBytes) "peer-original" else mediaProfile.id
                )
                .put(
                    "requires_validated_network",
                    !preserveOriginalBytes &&
                        mediaProfile.deferMediaUpload &&
                        attachment.isTransportMedia()
                )
                .put("contact_id", scope.contactId)
                .put("desktop_id", scope.desktopId)
                .put("client_route_id", scope.clientRouteId)
                .put("conversation_id", scope.conversationId)
                .put("task_id", scope.taskId)
                .put("turn_id", scope.turnId)
                .put("client_message_id", scope.clientMessageId)
                .put("attachment_request_id", scope.attachmentRequestId)
                .put("duration_ms", scope.durationMillis)
                .put("created_at", System.currentTimeMillis())
            writeManifest(destination, manifest)
            return readPrepared(destination) ?: error("Attachment transfer manifest is invalid")
        } finally {
            preparing.deleteRecursively()
        }
    }

    private fun readPrepared(directory: File): AgentPreparedOutboundAttachment? = runCatching {
        if (!directory.isDirectory || !directory.name.matches(SHA256)) return@runCatching null
        val manifest = JSONObject(File(directory, MANIFEST_FILE).readText(Charsets.UTF_8))
        val chunks = File(directory, CHUNKS_DIRECTORY)
        val transferId = manifest.getString("transfer_id").lowercase()
        val size = manifest.getLong("size_bytes")
        val chunkCount = manifest.getInt("chunk_count")
        val chunkSizeBytes = manifest.optInt("chunk_size_bytes", CHUNK_BYTES)
        val digest = manifest.getString("sha256").lowercase()
        require(
            transferId == directory.name &&
                transferId.matches(SHA256) &&
                digest.matches(SHA256) &&
                size in 1..MAX_ATTACHMENT_BYTES &&
                chunks.isDirectory &&
                isSupportedChunkSize(chunkSizeBytes) &&
                chunkCount == ((size + chunkSizeBytes - 1) / chunkSizeBytes).toInt() &&
                chunkCount in 1..MAX_CHUNKS &&
                (0 until chunkCount).all { index ->
                    runCatching {
                        AttachmentLocalStore.metadata(outboundChunkFile(chunks, index)).plaintextLength ==
                            expectedChunkSize(size, index, chunkSizeBytes).toLong()
                    }.getOrDefault(false)
                }
        )
        val prepared = AgentPreparedOutboundAttachment(
            transferId = transferId,
            attachmentId = manifest.getString("attachment_id"),
            ordinal = manifest.getInt("attachment_ordinal"),
            name = manifest.getString("name"),
            originalName = manifest.optString("original_name"),
            mimeType = manifest.getString("mime_type"),
            sizeBytes = size,
            originalSizeBytes = manifest.optLong("original_size_bytes", size),
            sha256 = digest,
            chunkCount = chunkCount,
            chunkSizeBytes = chunkSizeBytes,
            transportProfile = manifest.optString("transport_profile", "standard"),
            requiresValidatedNetwork = manifest.optBoolean("requires_validated_network"),
            scope = AgentAttachmentTransferScope(
                contactId = manifest.getString("contact_id"),
                desktopId = manifest.getString("desktop_id"),
                clientRouteId = manifest.getString("client_route_id"),
                conversationId = manifest.getString("conversation_id"),
                taskId = manifest.getString("task_id"),
                turnId = manifest.getString("turn_id"),
                clientMessageId = manifest.optLong("client_message_id", -1L).takeIf { it >= 0L },
                attachmentRequestId = manifest.optString("attachment_request_id"),
                durationMillis = manifest.optLong("duration_ms", 0L)
            ),
            chunkDirectory = chunks
        )
        require(
            AgentAttachmentTransferProtocol.transferId(
                prepared.scope,
                prepared.attachmentId,
                prepared.sha256
            ) == prepared.transferId
        )
        prepared
    }.getOrNull()

    private fun writeManifest(directory: File, manifest: JSONObject) {
        val temporary = File(directory, ".$MANIFEST_FILE.tmp")
        val target = File(directory, MANIFEST_FILE)
        temporary.writeText(manifest.toString(), Charsets.UTF_8)
        check(temporary.renameTo(target)) { "Attachment transfer manifest could not be committed" }
    }

    private fun stageChunks(
        input: InputStream,
        declaredLength: Long,
        directory: File,
        digest: MessageDigest
    ): Long {
        require(declaredLength in 0..MAX_ATTACHMENT_BYTES)
        val buffer = ByteArray(CHUNK_BYTES)
        var total = 0L
        var chunkIndex = 0
        try {
            while (true) {
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    if (read == 0) continue
                    count += read
                }
                if (count == 0) break
                total += count
                require(total <= MAX_ATTACHMENT_BYTES) { "Agent attachment exceeds the transfer limit" }
                digest.update(buffer, 0, count)
                AttachmentLocalStore.storeStream(
                    ByteArrayInputStream(buffer, 0, count),
                    count.toLong(),
                    outboundChunkFile(directory, chunkIndex)
                )
                buffer.wipeSensitive()
                chunkIndex += 1
                if (count < buffer.size) break
            }
        } finally {
            buffer.wipeSensitive()
        }
        if (declaredLength > 0L) {
            require(total == declaredLength) { "Agent attachment length changed while preparing" }
        }
        require(total > 0L) { "Agent attachment is empty" }
        return total
    }

    private fun expectedChunkSize(size: Long, index: Int, chunkSizeBytes: Int): Int = minOf(
        chunkSizeBytes.toLong(),
        size - index.toLong() * chunkSizeBytes
    ).toInt()

    fun isSupportedChunkSize(value: Int): Boolean = value == CHUNK_BYTES

    private fun prune(context: Context) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MILLIS
        val discardedTransferIds = mutableSetOf<String>()
        root(context).listFiles().orEmpty().forEach { entry ->
            val createdAt = runCatching {
                JSONObject(File(entry, MANIFEST_FILE).readText(Charsets.UTF_8))
                    .optLong("created_at", entry.lastModified())
            }.getOrDefault(entry.lastModified())
            if (
                entry.name.startsWith(".preparing-") ||
                createdAt < cutoff ||
                (entry.isDirectory && readPrepared(entry) == null)
            ) {
                if (entry.name.matches(SHA256)) discardedTransferIds += entry.name
                entry.deleteRecursively()
            }
        }
        if (discardedTransferIds.isNotEmpty()) {
            GalaxySSILinkDeliveryStore.discardBlockedByAttachmentTransfers(
                context,
                discardedTransferIds
            )
        }
    }

    private fun transferDirectory(context: Context, transferId: String): File =
        File(root(context), transferId)

    private fun root(context: Context): File =
        File(context.applicationContext.filesDir, ROOT_DIRECTORY).apply {
            check(mkdirs() || isDirectory) { "Attachment transfer root is unavailable" }
        }

    private fun AgentInputAttachment.isTransportMedia(): Boolean =
        mimeType.startsWith("image/", ignoreCase = true) ||
            mimeType.startsWith("audio/", ignoreCase = true) ||
            mimeType.startsWith("video/", ignoreCase = true)
}
