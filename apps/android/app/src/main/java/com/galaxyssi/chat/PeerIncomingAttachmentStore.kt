package com.galaxyssi.chat

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Durable, integrity-checked receive side of phone-to-phone attachment transfer. */
internal object PeerIncomingAttachmentStore {
    private const val ROOT = "peer-incoming-attachments-v2"
    private const val MANIFEST = "manifest.json"
    private const val CHUNKS = "chunks"
    private const val DATA = "data.sasie"
    private const val MAX_AGE_MILLIS = 30L * 24L * 60L * 60L * 1_000L
    private val sha256Pattern = Regex("[a-f0-9]{64}")
    private val reportedProgress = mutableMapOf<String, Int>()

    data class IngestResult(
        val receipt: JSONObject?,
        val progress: JSONObject?
    )

    data class PendingDownload(
        val transferId: String,
        val sourceId: String
    )

    @Synchronized
    fun pendingDownloads(context: Context): List<PendingDownload> {
        prune(context)
        return root(context).listFiles().orEmpty()
            .filter(File::isDirectory)
            .mapNotNull { directory ->
                val manifest = readManifest(directory) ?: return@mapNotNull null
                val transferId = manifest.optString("transfer_id").lowercase()
                val sourceId = manifest.optString("source_id")
                if (!transferId.matches(sha256Pattern) || sourceId.isBlank()) return@mapNotNull null
                if (!isDownloadRequested(manifest)) return@mapNotNull null
                if (storedAttachment(context, transferId, sourceId) != null) return@mapNotNull null
                if (missingChunkIndices(directory, manifest).isEmpty()) return@mapNotNull null
                PendingDownload(transferId, sourceId)
            }
            .distinct()
    }

    @Synchronized
    fun ingest(
        context: Context,
        payload: JSONObject,
        sourceId: String,
        routes: GalaxySSILinkProtocol.Routes
    ): IngestResult? {
        prune(context)
        return when (payload.optString("type")) {
            "input_attachment_manifest" -> ingestManifest(context, payload, sourceId, routes)
            "input_attachment_chunk" -> ingestChunk(context, payload, sourceId, routes)
            else -> null
        }
    }

    @Synchronized
    fun resolveMessageAttachments(
        context: Context,
        sourceId: String,
        payload: JSONObject
    ): JSONArray? {
        val source = payload.optJSONArray("attachments") ?: return JSONArray()
        val resolved = JSONArray()
        for (index in 0 until source.length()) {
            val descriptor = source.optJSONObject(index) ?: return null
            val transferId = descriptor.optString("transfer_id").lowercase()
            val directory = transferDirectory(context, transferId)
            val manifest = readManifest(directory)
                ?.takeIf { it.optString("source_id") == sourceId }
                ?: return null
            val descriptorSize = descriptor.optLong("size", descriptor.optLong("size_bytes"))
            if (descriptor.optString("sha256").lowercase() != manifest.optString("sha256") ||
                descriptorSize != manifest.optLong("size_bytes")
            ) return null
            val stored = storedAttachment(context, transferId, sourceId)
            val missing = if (stored == null) missingChunkIndices(directory, manifest) else emptyList()
            val received = if (stored == null) receivedBytes(manifest, missing) else stored.sizeBytes
            resolved.put(JSONObject(descriptor.toString()).apply {
                put("name", manifest.optString("name", "attachment"))
                put("mime_type", manifest.optString("mime_type", "application/octet-stream"))
                put("size_bytes", manifest.optLong("size_bytes"))
                put(
                    "transfer_progress",
                    PeerAttachmentTransferProgress.percent(received, manifest.optLong("size_bytes"))
                )
                put(
                    "transfer_state",
                    when {
                        stored != null -> PeerAttachmentTransferProgress.STATE_COMPLETE
                        isDownloadRequested(manifest) -> PeerAttachmentTransferProgress.STATE_DOWNLOADING
                        else -> PeerAttachmentTransferProgress.STATE_AVAILABLE
                    }
                )
                if (stored != null) {
                    put(
                        "uri",
                        LocalAttachmentUris.forFile(
                            context,
                            stored.dataFile,
                            stored.name,
                            stored.mimeType
                        ).toString()
                    )
                } else {
                    put("uri", "")
                }
                payload.optLong("duration_ms", 0L)
                    .takeIf { manifest.optString("mime_type").startsWith("audio/") }
                    ?.let { put("duration_ms", it) }
            })
        }
        return resolved
    }

    @Synchronized
    fun requestDownload(context: Context, transferId: String, sourceId: String): IngestResult? {
        val normalizedId = transferId.lowercase()
        if (!normalizedId.matches(sha256Pattern)) return null
        val directory = transferDirectory(context, normalizedId)
        val manifest = readManifest(directory)
            ?.takeIf { it.optString("source_id") == sourceId }
            ?: return null
        storedAttachment(context, normalizedId, sourceId)?.let { stored ->
            return IngestResult(
                receipt(manifest, "stored", stored.sizeBytes),
                PeerAttachmentTransferProgress.event(
                    manifest,
                    sourceId,
                    "inbound",
                    100,
                    PeerAttachmentTransferProgress.STATE_COMPLETE,
                    stored.sizeBytes,
                    LocalAttachmentUris.forFile(
                        context,
                        stored.dataFile,
                        stored.name,
                        stored.mimeType
                    ).toString()
                )
            )
        }
        val missing = missingChunkIndices(directory, manifest)
        val received = receivedBytes(manifest, missing)
        val requested = PeerAttachmentTransferProgress.requestWindow(
            missing,
            manifest.getLong("size_bytes"),
            manifest.getInt("chunk_size_bytes")
        )
        manifest.put("download_requested", true)
        manifest.put("requested_indices", JSONArray(requested))
        manifest.put("last_request_at", System.currentTimeMillis())
        writeJson(File(directory, MANIFEST), manifest)
        return IngestResult(
            receipt(manifest, "missing", received).put(
                "missing_ranges",
                AgentAttachmentTransferProtocol.missingRanges(requested)
            ),
            PeerAttachmentTransferProgress.event(
                manifest,
                sourceId,
                "inbound",
                PeerAttachmentTransferProgress.percent(received, manifest.getLong("size_bytes")),
                PeerAttachmentTransferProgress.STATE_DOWNLOADING,
                received
            )
        )
    }

    @Synchronized
    fun saveToDownloads(context: Context, attachment: PeerChatAttachment): Result<String> = runCatching {
        val source = attachment.resolvedUri(context) ?: error("Attachment is unavailable")
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safeName(attachment.name))
            put(android.provider.MediaStore.Downloads.MIME_TYPE, attachment.mimeType)
            put(
                android.provider.MediaStore.Downloads.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_DOWNLOADS + "/GalaxySSI"
            )
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val destination = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Downloads is unavailable")
        try {
            resolver.openInputStream(source)?.use { input ->
                resolver.openOutputStream(destination)?.use(input::copyTo)
                    ?: error("Download destination is unavailable")
            } ?: error("Attachment is unavailable")
            resolver.update(
                destination,
                android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                },
                null,
                null
            )
            destination.toString()
        } catch (error: Throwable) {
            resolver.delete(destination, null, null)
            throw error
        }
    }

    @Synchronized
    fun deleteLocalCopies(context: Context, attachments: List<PeerChatAttachment>) {
        attachments.forEach { attachment ->
            attachment.transferId.lowercase()
                .takeIf { it.matches(sha256Pattern) }
                ?.let { transferDirectory(context, it).deleteRecursively() }

            val localUri = runCatching { Uri.parse(attachment.uri) }.getOrNull()
            if (localUri?.scheme != "file") return@forEach
            val localFile = localUri.path?.let(::File) ?: return@forEach
            val canonical = runCatching { localFile.canonicalFile }.getOrNull() ?: return@forEach
            val privateRoots = listOf(context.cacheDir, context.filesDir).mapNotNull { root ->
                runCatching { root.canonicalFile }.getOrNull()
            }
            if (privateRoots.any { root -> canonical.toPath().startsWith(root.toPath()) }) {
                canonical.delete()
            }
        }
    }

    private fun ingestManifest(
        context: Context,
        payload: JSONObject,
        sourceId: String,
        routes: GalaxySSILinkProtocol.Routes
    ): IngestResult? {
        val normalized = normalizedManifest(payload, sourceId, routes) ?: return null
        val transferId = normalized.getString("transfer_id")
        val directory = transferDirectory(context, transferId)
        val existing = readManifest(directory)
        if (existing != null && !sameTransfer(existing, normalized)) {
            directory.deleteRecursively()
        }
        if (!directory.exists() && !directory.mkdirs()) return null
        val active = readManifest(directory)?.takeIf { sameTransfer(it, normalized) }
            ?.apply { put("received_at", System.currentTimeMillis()) }
            ?: normalized
        File(directory, CHUNKS).mkdirs()
        val stored = storedAttachment(context, transferId, sourceId)
        if (stored != null) {
            val uri = LocalAttachmentUris.forFile(
                context,
                stored.dataFile,
                stored.name,
                stored.mimeType
            ).toString()
            return IngestResult(
                receipt(active, "stored", stored.sizeBytes),
                PeerAttachmentTransferProgress.event(
                    active,
                    sourceId,
                    "inbound",
                    100,
                    PeerAttachmentTransferProgress.STATE_COMPLETE,
                    stored.sizeBytes,
                    uri
                )
            )
        }
        val missing = missingChunkIndices(directory, active)
        val receivedBytes = receivedBytes(active, missing)
        if (payload.optBoolean("eager_chunks") && !payload.optBoolean("resume")) {
            active.put("download_requested", true)
            active.put("requested_indices", JSONArray(missing))
            writeJson(File(directory, MANIFEST), active)
            return IngestResult(
                null,
                PeerAttachmentTransferProgress.event(
                    active,
                    sourceId,
                    "inbound",
                    PeerAttachmentTransferProgress.percent(receivedBytes, active.getLong("size_bytes")),
                    PeerAttachmentTransferProgress.STATE_DOWNLOADING,
                    receivedBytes
                )
            )
        }
        val autoReceive = PeerAttachmentTransferProgress.shouldAutoReceive(
            active.optString("mime_type")
        )
        if (!isDownloadRequested(active) && !autoReceive) {
            active.remove("requested_indices")
            active.put("download_requested", false)
            writeJson(File(directory, MANIFEST), active)
            return IngestResult(
                null,
                PeerAttachmentTransferProgress.event(
                    active,
                    sourceId,
                    "inbound",
                    PeerAttachmentTransferProgress.percent(receivedBytes, active.getLong("size_bytes")),
                    PeerAttachmentTransferProgress.STATE_AVAILABLE,
                    receivedBytes
                )
            )
        }
        val requested = PeerAttachmentTransferProgress.requestWindow(
            missing,
            active.getLong("size_bytes"),
            active.getInt("chunk_size_bytes")
        )
        active.put("download_requested", true)
        active.put("requested_indices", JSONArray(requested))
        active.put("last_request_at", System.currentTimeMillis())
        writeJson(File(directory, MANIFEST), active)
        return IngestResult(
            receipt(active, "missing", receivedBytes).put(
                "missing_ranges",
                AgentAttachmentTransferProtocol.missingRanges(requested)
            ),
            PeerAttachmentTransferProgress.event(
                active,
                sourceId,
                "inbound",
                PeerAttachmentTransferProgress.percent(receivedBytes, active.getLong("size_bytes")),
                PeerAttachmentTransferProgress.STATE_DOWNLOADING,
                receivedBytes
            )
        )
    }

    private fun ingestChunk(
        context: Context,
        payload: JSONObject,
        sourceId: String,
        routes: GalaxySSILinkProtocol.Routes
    ): IngestResult? {
        val transferId = payload.optString("transfer_id").lowercase()
        if (!transferId.matches(sha256Pattern)) return null
        val directory = transferDirectory(context, transferId)
        val manifest = readManifest(directory) ?: return null
        if (!manifestMatchesPayload(manifest, payload, sourceId, routes)) return null
        storedAttachment(context, transferId, sourceId)?.let { stored ->
            val uri = LocalAttachmentUris.forFile(
                context,
                stored.dataFile,
                stored.name,
                stored.mimeType
            ).toString()
            return IngestResult(
                receipt(manifest, "stored", stored.sizeBytes),
                PeerAttachmentTransferProgress.event(
                    manifest,
                    sourceId,
                    "inbound",
                    100,
                    PeerAttachmentTransferProgress.STATE_COMPLETE,
                    stored.sizeBytes,
                    uri
                )
            )
        }
        val chunkCount = manifest.getInt("chunk_count")
        val index = payload.optInt("chunk_index", -1)
        if (index !in 0 until chunkCount) return null
        val bytes = runCatching { Base64.decode(payload.getString("data_b64"), Base64.DEFAULT) }
            .getOrNull() ?: return null
        try {
            val expectedSize = expectedChunkSize(manifest, index)
            val expectedDigest = payload.optString("chunk_sha256").lowercase()
            if (bytes.size != expectedSize ||
                payload.optInt("chunk_size", -1) != expectedSize ||
                expectedDigest != sha256(bytes)
            ) return null
            val chunkFile = chunkFile(directory, index)
            if (!validStoredChunk(chunkFile, expectedSize, expectedDigest)) {
                AttachmentLocalStore.storeBytes(bytes, chunkFile)
            }
        } finally {
            bytes.fill(0)
        }
        val missing = missingChunkIndices(directory, manifest)
        val receivedBytes = receivedBytes(manifest, missing)
        val progressPercent = PeerAttachmentTransferProgress.percent(
            receivedBytes,
            manifest.getLong("size_bytes")
        )
        val progress = if (shouldReportProgress(transferId, progressPercent)) {
            PeerAttachmentTransferProgress.event(
                manifest,
                sourceId,
                "inbound",
                progressPercent,
                PeerAttachmentTransferProgress.STATE_DOWNLOADING,
                receivedBytes
            )
        } else null
        if (missing.isNotEmpty()) {
            val requested = jsonIntList(manifest.optJSONArray("requested_indices"))
            if (requested.any(missing::contains)) return IngestResult(null, progress)
            val nextWindow = PeerAttachmentTransferProgress.requestWindow(
                missing,
                manifest.getLong("size_bytes"),
                manifest.getInt("chunk_size_bytes")
            )
            manifest.put("requested_indices", JSONArray(nextWindow))
            manifest.put("last_request_at", System.currentTimeMillis())
            writeJson(File(directory, MANIFEST), manifest)
            return IngestResult(
                receipt(manifest, "missing", receivedBytes).put(
                    "missing_ranges",
                    AgentAttachmentTransferProtocol.missingRanges(nextWindow)
                ),
                progress
            )
        }
        val destination = File(directory, DATA)
        val chunks = (0 until chunkCount).map { chunkIndex ->
            chunkFile(directory, chunkIndex)
        }
        val calculated = MessageDigest.getInstance("SHA-256")
        AttachmentLocalStore.openSequence(chunks).use { plaintext ->
            AttachmentLocalStore.storeStream(
                plaintext,
                manifest.getLong("size_bytes"),
                destination,
                onPlaintext = { buffer, count -> calculated.update(buffer, 0, count) }
            )
        }
        if (calculated.digest().joinToString("") { "%02x".format(it) } != manifest.getString("sha256")) {
            destination.delete()
            File(directory, CHUNKS).deleteRecursively()
            File(directory, CHUNKS).mkdirs()
            val resetWindow = PeerAttachmentTransferProgress.requestWindow(
                (0 until chunkCount).toList(),
                manifest.getLong("size_bytes"),
                manifest.getInt("chunk_size_bytes")
            )
            manifest.put("requested_indices", JSONArray(resetWindow))
            writeJson(File(directory, MANIFEST), manifest)
            return IngestResult(
                receipt(manifest, "missing", 0L).put(
                    "missing_ranges",
                    AgentAttachmentTransferProtocol.missingRanges(resetWindow)
                ),
                PeerAttachmentTransferProgress.event(
                    manifest,
                    sourceId,
                    "inbound",
                    0,
                    PeerAttachmentTransferProgress.STATE_DOWNLOADING
                )
            )
        }
        File(directory, CHUNKS).deleteRecursively()
        reportedProgress.remove(transferId)
        manifest.remove("requested_indices")
        manifest.remove("last_request_at")
        writeJson(File(directory, MANIFEST), manifest)
        val uri = LocalAttachmentUris.forFile(
            context,
            destination,
            manifest.optString("name", "attachment"),
            manifest.optString("mime_type", "application/octet-stream")
        ).toString()
        return IngestResult(
            receipt(manifest, "stored", manifest.getLong("size_bytes")),
            PeerAttachmentTransferProgress.event(
                manifest,
                sourceId,
                "inbound",
                100,
                PeerAttachmentTransferProgress.STATE_COMPLETE,
                manifest.getLong("size_bytes"),
                uri
            )
        )
    }

    private fun normalizedManifest(
        payload: JSONObject,
        sourceId: String,
        routes: GalaxySSILinkProtocol.Routes
    ): JSONObject? = runCatching {
        val transferId = payload.getString("transfer_id").lowercase()
        val digest = payload.getString("sha256").lowercase()
        val size = payload.getLong("size_bytes")
        val chunkCount = payload.getInt("chunk_count")
        val chunkSizeBytes = payload.optInt(
            "chunk_size_bytes",
            AgentOutboundAttachmentTransferStore.CHUNK_BYTES
        )
        require(transferId.matches(sha256Pattern) && digest.matches(sha256Pattern))
        require(size in 1..AgentOutboundAttachmentTransferStore.MAX_ATTACHMENT_BYTES)
        require(AgentOutboundAttachmentTransferStore.isSupportedChunkSize(chunkSizeBytes))
        require(chunkCount == ((size + chunkSizeBytes - 1) / chunkSizeBytes).toInt())
        require(chunkCount in 1..AgentOutboundAttachmentTransferStore.MAX_CHUNKS)
        require(payload.optString("client_route_id") == routes.clientRouteId)
        require(payload.optString("contact_id") == GalaxySSICrypto.localGalaxySSIId())
        require(sourceId.isNotBlank())
        JSONObject(payload.toString())
            .put("source_id", sourceId)
            .put("received_at", System.currentTimeMillis())
            .put("chunk_size_bytes", chunkSizeBytes)
            .put("name", safeName(payload.optString("name").ifBlank { "attachment" }))
            .put("mime_type", payload.optString("mime_type").ifBlank { "application/octet-stream" })
    }.getOrNull()

    private fun manifestMatchesPayload(
        manifest: JSONObject,
        payload: JSONObject,
        sourceId: String,
        routes: GalaxySSILinkProtocol.Routes
    ): Boolean = manifest.optString("source_id") == sourceId &&
        manifest.optString("client_route_id") == routes.clientRouteId &&
        manifest.optString("transfer_id") == payload.optString("transfer_id").lowercase() &&
        manifest.optString("sha256") == payload.optString("sha256").lowercase() &&
        manifest.optLong("size_bytes") == payload.optLong("size_bytes") &&
        manifest.optInt("chunk_count") == payload.optInt("chunk_count") &&
        manifest.optInt("chunk_size_bytes") == payload.optInt(
            "chunk_size_bytes",
            AgentOutboundAttachmentTransferStore.CHUNK_BYTES
        )

    private fun sameTransfer(first: JSONObject, second: JSONObject): Boolean =
        first.optString("source_id") == second.optString("source_id") &&
            first.optString("sha256") == second.optString("sha256") &&
            first.optLong("size_bytes") == second.optLong("size_bytes") &&
            first.optInt("chunk_count") == second.optInt("chunk_count") &&
            first.optInt("chunk_size_bytes") == second.optInt("chunk_size_bytes")

    private fun receipt(manifest: JSONObject, status: String, receivedBytes: Long): JSONObject = JSONObject()
        .put("type", "input_attachment_receipt")
        .put("status", status)
        .put("transfer_id", manifest.getString("transfer_id"))
        .put("sha256", manifest.getString("sha256"))
        .put("client_route_id", manifest.getString("client_route_id"))
        .put("conversation_id", manifest.getString("conversation_id"))
        .put("task_id", manifest.getString("task_id"))
        .put("turn_id", manifest.getString("turn_id"))
        .put("contact_id", GalaxySSICrypto.localGalaxySSIId())
        .put("source_message_id", manifest.optString("client_message_id"))
        .put("received_bytes", receivedBytes.coerceAtLeast(0L))
        .put(
            "progress",
            PeerAttachmentTransferProgress.percent(receivedBytes, manifest.optLong("size_bytes"))
        )
        .put("peer_chat", true)
        .put("time", System.currentTimeMillis())

    private fun receivedBytes(manifest: JSONObject, missing: List<Int>): Long {
        val missingBytes = missing.sumOf { index -> expectedChunkSize(manifest, index).toLong() }
        return (manifest.getLong("size_bytes") - missingBytes).coerceAtLeast(0L)
    }

    private fun jsonIntList(values: JSONArray?): List<Int> = buildList {
        if (values == null) return@buildList
        for (index in 0 until values.length()) {
            values.optInt(index, -1).takeIf { it >= 0 }?.let(::add)
        }
    }

    private fun shouldReportProgress(transferId: String, progress: Int): Boolean {
        val previous = reportedProgress[transferId] ?: -1
        if (progress <= previous && progress < 100) return false
        reportedProgress[transferId] = progress
        return true
    }

    private data class StoredAttachment(
        val name: String,
        val mimeType: String,
        val sizeBytes: Long,
        val sha256: String,
        val dataFile: File
    )

    private fun storedAttachment(context: Context, transferId: String, sourceId: String): StoredAttachment? {
        if (!transferId.matches(sha256Pattern)) return null
        val directory = transferDirectory(context, transferId)
        val manifest = readManifest(directory) ?: return null
        val data = File(directory, DATA)
        val plaintextLength = runCatching { AttachmentLocalStore.metadata(data).plaintextLength }
            .getOrNull() ?: return null
        if (manifest.optString("source_id") != sourceId ||
            plaintextLength != manifest.optLong("size_bytes")
        ) return null
        return StoredAttachment(
            manifest.optString("name", "attachment"),
            manifest.optString("mime_type", "application/octet-stream"),
            plaintextLength,
            manifest.getString("sha256"),
            data
        )
    }

    private fun missingChunkIndices(directory: File, manifest: JSONObject): List<Int> =
        (0 until manifest.getInt("chunk_count")).filter { index ->
            val file = chunkFile(directory, index)
            runCatching {
                AttachmentLocalStore.metadata(file).plaintextLength !=
                    expectedChunkSize(manifest, index).toLong()
            }.getOrDefault(true)
        }

    private fun expectedChunkSize(manifest: JSONObject, index: Int): Int = minOf(
        manifest.getInt("chunk_size_bytes").toLong(),
        manifest.getLong("size_bytes") - index.toLong() * manifest.getInt("chunk_size_bytes")
    ).toInt()

    private fun isDownloadRequested(manifest: JSONObject): Boolean =
        manifest.optBoolean("download_requested") || manifest.has("requested_indices")

    private fun root(context: Context): File = File(context.filesDir, ROOT).apply { mkdirs() }
    private fun transferDirectory(context: Context, transferId: String) = File(root(context), transferId)
    private fun chunkFile(directory: File, index: Int) =
        File(File(directory, CHUNKS).apply { mkdirs() }, "chunk-${index.toString().padStart(6, '0')}.sasie")

    private fun readManifest(directory: File): JSONObject? = runCatching {
        JSONObject(File(directory, MANIFEST).readText(Charsets.UTF_8))
    }.getOrNull()

    private fun writeJson(file: File, value: JSONObject) {
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        temporary.writeText(value.toString(), Charsets.UTF_8)
        file.delete()
        check(temporary.renameTo(file)) { "Attachment manifest could not be committed" }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256Stored(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        AttachmentLocalStore.openInput(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            try {
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            } finally {
                buffer.fill(0)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun validStoredChunk(file: File, expectedSize: Int, expectedDigest: String): Boolean =
        runCatching {
            AttachmentLocalStore.metadata(file).plaintextLength == expectedSize.toLong() &&
                sha256Stored(file) == expectedDigest
        }.getOrDefault(false)

    private fun safeName(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim().take(160).ifBlank { "attachment" }

    private fun prune(context: Context, now: Long = System.currentTimeMillis()) {
        root(context).listFiles().orEmpty().filter(File::isDirectory).forEach { directory ->
            val manifest = readManifest(directory)
            val receivedAt = manifest?.optLong("received_at", directory.lastModified()) ?: 0L
            if (PeerMessageAttachmentStore.shouldPruneIncoming(
                    receivedAt = receivedAt,
                    hasCompletedData = File(directory, DATA).isFile,
                    now = now,
                    maxAgeMillis = MAX_AGE_MILLIS
                )
            ) {
                directory.deleteRecursively()
            }
        }
    }
}
