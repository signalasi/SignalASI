package com.galaxyssi.chat

import android.net.Uri
import android.util.Base64
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PeerOriginalAttachmentInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun peerImageTransferPreservesEveryOriginalByte() {
        val bytes = ByteArray(2 * 1024 * 1024 + 17) { index -> (index * 31).toByte() }
        val source = File(context.cacheDir, "peer-original-${UUID.randomUUID()}.jpg")
        source.writeBytes(bytes)
        var transferIds = emptyList<String>()
        try {
            val prepared = AgentOutboundAttachmentTransferStore.prepare(
                context = context,
                scope = AgentAttachmentTransferScope(
                    contactId = "galaxyssi:peer-test",
                    desktopId = "galaxyssi:peer-test",
                    clientRouteId = "b".repeat(22),
                    conversationId = "peer-original-test",
                    taskId = "peer-original-task",
                    turnId = "peer-original-turn",
                    clientMessageId = 1L
                ),
                attachments = listOf(
                    AgentInputAttachment(
                        id = UUID.randomUUID().toString(),
                        uri = Uri.fromFile(source),
                        displayName = source.name,
                        mimeType = "image/jpeg",
                        sizeBytes = bytes.size.toLong()
                    )
                ),
                mediaProfile = AgentMediaDeliveryProfile(
                    state = AgentMediaNetworkState.NORMAL,
                    id = "normal",
                    imageTargetBytes = 100_000,
                    audioSampleRateHz = 44_100,
                    audioBitRateBps = 96_000,
                    deferMediaUpload = false
                ),
                preserveOriginalBytes = true
            ).single()
            transferIds = listOf(prepared.transferId)

            assertEquals(bytes.size.toLong(), prepared.sizeBytes)
            assertEquals(bytes.size.toLong(), prepared.originalSizeBytes)
            assertEquals("image/jpeg", prepared.mimeType)
            assertEquals("peer-original", prepared.transportProfile)
            assertFalse(prepared.requiresValidatedNetwork)

            val restored = ByteArrayOutputStream(bytes.size)
            repeat(prepared.chunkCount) { index ->
                val chunk = Base64.decode(
                    prepared.chunkPayload(index).getString("data_b64"),
                    Base64.DEFAULT
                )
                try {
                    restored.write(chunk)
                } finally {
                    chunk.fill(0)
                }
            }
            assertArrayEquals(bytes, restored.toByteArray())
        } finally {
            AgentOutboundAttachmentTransferStore.discard(context, transferIds)
            source.delete()
            bytes.fill(0)
        }
    }

    @Test
    fun receivedImageCreatesBoundedPlainThumbnail() {
        val directory = File(context.filesDir, "peer-incoming-attachments-v2/thumbnail-test-${UUID.randomUUID()}")
        val encryptedSource = File(directory, "data.sasie")
        val sourceBitmap = Bitmap.createBitmap(900, 1_200, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(sourceBitmap.width * sourceBitmap.height) { index ->
            val red = index * 31 and 0xff
            val green = index * 17 and 0xff
            val blue = index * 7 and 0xff
            android.graphics.Color.rgb(red, green, blue)
        }
        sourceBitmap.setPixels(pixels, 0, sourceBitmap.width, 0, 0, sourceBitmap.width, sourceBitmap.height)
        pixels.fill(0)
        val encoded = ByteArrayOutputStream().use { output ->
            assertTrue(sourceBitmap.compress(Bitmap.CompressFormat.JPEG, 96, output))
            output.toByteArray()
        }
        sourceBitmap.recycle()
        try {
            AttachmentLocalStore.storeBytes(encoded, encryptedSource)
        } finally {
            encoded.fill(0)
        }
        val attachment = PeerChatAttachment(
            name = "photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = AttachmentLocalStore.metadata(encryptedSource).plaintextLength,
            uri = LocalAttachmentUris.forFile(
                context,
                encryptedSource,
                "photo.jpg",
                "image/jpeg"
            ).toString(),
            transferId = "c".repeat(64),
            transferProgress = 100,
            transferState = PeerAttachmentTransferProgress.STATE_COMPLETE
        )
        val loaded = arrayOfNulls<Bitmap>(2)
        val latch = CountDownLatch(2)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            PeerImageThumbnailRepository.load(context, attachment, 504, 504) {
                loaded[0] = it
                latch.countDown()
            }
            PeerImageThumbnailRepository.load(context, attachment, 420, 420) {
                loaded[1] = it
                latch.countDown()
            }
        }
        try {
            assertTrue(latch.await(20, TimeUnit.SECONDS))
            assertNotNull(loaded[0])
            assertNotNull(loaded[1])
            val thumbnail = directory.listFiles()?.singleOrNull {
                it.name == ".peer-image-thumbnail-v1.sasie"
            }
            assertNotNull(thumbnail)
            assertTrue(AttachmentLocalStore.isStored(requireNotNull(thumbnail)))
            assertTrue(AttachmentLocalStore.metadata(thumbnail).plaintextLength <= 100_000L)
        } finally {
            PeerImageThumbnailRepository.clearRuntimeCache()
            directory.deleteRecursively()
        }
    }

    @Test
    fun incompleteImageTransferIsDiscoverableAfterRuntimeReconnect() {
        GalaxySSICrypto.initialize(context)
        val transferId = "d".repeat(64)
        val sourceId = "galaxyssi:resume-test"
        val routes = GalaxySSILinkProtocol.Routes(
            clientRouteId = GalaxySSILinkProtocol.newRouteId(),
            linkSecret = GalaxySSILinkProtocol.newLinkSecret(),
            localFingerprint = "1".repeat(64),
            remoteFingerprint = "2".repeat(64)
        )
        val transferDirectory = File(
            context.filesDir,
            "peer-incoming-attachments-v2/$transferId"
        )
        val manifest = JSONObject()
            .put("type", "input_attachment_manifest")
            .put("transfer_id", transferId)
            .put("attachment_id", "resume-image")
            .put("attachment_ordinal", 0)
            .put("name", "resume.jpg")
            .put("original_name", "resume.jpg")
            .put("mime_type", "image/jpeg")
            .put("size_bytes", 1_001_475L)
            .put("original_size_bytes", 1_001_475L)
            .put("sha256", "e".repeat(64))
            .put("chunk_count", 4)
            .put("chunk_size_bytes", 256 * 1024)
            .put("contact_id", GalaxySSICrypto.localGalaxySSIId())
            .put("client_route_id", routes.clientRouteId)
            .put("conversation_id", "peer-resume")
            .put("task_id", "peer-resume-task")
            .put("turn_id", "peer-resume-turn")
            .put("client_message_id", 9L)
            .put("resume", false)
        try {
            val first = PeerIncomingAttachmentStore.ingest(
                context,
                manifest,
                sourceId,
                routes
            )
            assertEquals("missing", first?.receipt?.optString("status"))

            assertEquals(
                PeerIncomingAttachmentStore.PendingDownload(transferId, sourceId),
                PeerIncomingAttachmentStore.pendingDownloads(context).single { pending ->
                    pending.transferId == transferId
                }
            )
            val resumed = PeerIncomingAttachmentStore.requestDownload(
                context,
                transferId,
                sourceId
            )
            assertEquals("missing", resumed?.receipt?.optString("status"))
            assertEquals(4, AgentAttachmentTransferProtocol.expandMissingRanges(
                resumed?.receipt?.optJSONArray("missing_ranges"),
                4
            ).size)
        } finally {
            transferDirectory.deleteRecursively()
        }
    }
}
