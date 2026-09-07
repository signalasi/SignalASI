package com.galaxyssi.chat.blob

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import javax.crypto.SecretKey

/** Each side effect is idempotent; the journal advances only after this step returns. */
internal class BlobArtifactReceivePipeline(
    private val stagingRoot: File,
    private val storage: BlobArtifactStorage,
    private val checkIdentity: (JSONObject) -> Unit,
    private val publish: (JSONObject) -> Boolean,
    private val sendReceipt: (JSONObject, JSONObject) -> Boolean,
    private val observeFailure: (JSONObject, String) -> Boolean,
    private val client: (String) -> BlobTransferClient = { BlobTransferClient(BlobHttp(it)) },
    private val storageKey: SecretKey? = null,
    private val progress: (JSONObject, Long, Long) -> Unit = { _, _, _ -> }
) {
    fun process(work: BlobArtifactReceiveWork, cancel: BlobCancellation, checkClaim: () -> Unit) {
        val body = BlobArtifactReceiveJob.validate(work.body)
        val offer = body.getJSONObject("offer")
        val manifest = offer.getJSONObject("manifest")
        if (work.id != manifest.getString("transfer_id")) BlobProtocol.fail("artifact_blob_checkpoint_invalid")
        val current = { cancel.check(); checkClaim(); checkIdentity(body) }
        val localCurrent = { cancel.check(); checkClaim() }
        when (work.phase) {
            BlobArtifactReceiveJournal.DOWNLOAD -> {
                current()
                if (storage.verified(manifest, current)) return
                val directory = directory(work.id, BlobArtifactReceiveJob.revision(body))
                if (!BlobStaging.exists(directory) && directory.exists()) removeStaging(directory)
                current()
                client(body.getString("origin")).download(offer.getJSONObject("blob_offer"), directory,
                    BlobArtifactContract.binding(manifest), storageKey, cancel,
                    progress = { done, total -> current(); progress(manifest, done, total) }).use { staged ->
                    storage.ingest(staged, manifest, current)
                }
                current()
            }
            BlobArtifactReceiveJournal.PUBLISH -> {
                requireStored(manifest, current)
                if (!publish(manifest)) throw BlobFailure("artifact_blob_publication_pending", 503)
                current()
            }
            BlobArtifactReceiveJournal.RECEIPT -> {
                requireStored(manifest, current)
                if (!sendReceipt(controlContext(body), BlobArtifactContract.storedReceipt(manifest))) {
                    throw BlobFailure("artifact_blob_receipt_pending", 503)
                }
                current()
            }
            BlobArtifactReceiveJournal.CLEANUP, BlobArtifactReceiveJournal.DISCARD -> {
                localCurrent()
                removeStaging(transferDirectory(work.id))
                localCurrent()
            }
            BlobArtifactReceiveJournal.OBSERVE_FAILURE -> {
                localCurrent()
                if (!observeFailure(controlContext(body), work.error)) throw BlobFailure("artifact_blob_observation_pending", 503)
                localCurrent()
            }
            else -> BlobProtocol.fail("artifact_blob_checkpoint_invalid")
        }
    }

    private fun controlContext(body: JSONObject): JSONObject {
        val context = JSONObject()
        listOf("route_id", "desktop_id", "origin", "peer_fingerprint", "local_fingerprint").forEach {
            context.put(it, body.getString(it))
        }
        val offer = body.getJSONObject("offer")
        return context.put("offer", JSONObject().put("type", offer.getString("type")).put("version", 1)
            .put("transport_revision", offer.getLong("transport_revision"))
            .put("manifest", JSONObject(offer.getJSONObject("manifest").toString())))
    }

    private fun requireStored(manifest: JSONObject, current: () -> Unit) {
        current()
        if (!storage.verified(manifest, current)) throw BlobFailure("artifact_blob_local_copy_missing", 409)
        current()
    }

    private fun transferDirectory(id: String): File {
        BlobProtocol.unhex(id, 32).fill(0)
        if (Files.isSymbolicLink(stagingRoot.toPath())) BlobProtocol.fail("invalid_transfer_path")
        check(stagingRoot.mkdirs() || stagingRoot.isDirectory)
        val target = File(stagingRoot, id)
        if (Files.isSymbolicLink(target.toPath()) || target.canonicalFile.parentFile != stagingRoot.canonicalFile) {
            BlobProtocol.fail("invalid_transfer_path")
        }
        return target
    }

    private fun directory(id: String, revision: Long): File {
        val parent = transferDirectory(id)
        check(parent.mkdirs() || parent.isDirectory)
        val target = File(parent, revision.toString())
        if (Files.isSymbolicLink(target.toPath()) || target.canonicalFile.parentFile != parent.canonicalFile) {
            BlobProtocol.fail("invalid_transfer_path")
        }
        return target
    }

    private fun removeStaging(directory: File) {
        val parent = directory.canonicalFile.parentFile
        check(parent == stagingRoot.canonicalFile || parent?.parentFile == stagingRoot.canonicalFile)
        if (!directory.exists()) return
        // Do not follow links if an interrupted staging directory contains one.
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes) =
                java.nio.file.FileVisitResult.CONTINUE.also { Files.delete(file) }
            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): java.nio.file.FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }
}
