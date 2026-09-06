package com.galaxyssi.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PeerMessageAttachmentStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `outgoing voice is moved from cache to durable message storage`() {
        val filesDir = temporaryFolder.newFolder("files")
        val cacheDir = temporaryFolder.newFolder("cache")
        val source = cacheDir.resolve("recording.m4a").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }

        val stored = PeerMessageAttachmentStore.persistOutgoingVoice(
            filesDir,
            cacheDir,
            source,
            messageId = 42L,
            extension = "m4a"
        ).getOrThrow()

        assertFalse(source.exists())
        assertTrue(stored.isFile)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), stored.readBytes())
        assertTrue(stored.path.replace('\\', '/').endsWith("peer-message-attachments-v2/outgoing/voice/msg_42.m4a.sasie"))
        assertNotNull(PeerMessageAttachmentStore.resolveOutgoingVoice(filesDir, "voice-42.m4a"))
    }

    @Test
    fun `completed incoming attachment follows chat lifetime instead of transfer age`() {
        val month = 30L * 24L * 60L * 60L * 1_000L
        val now = month * 3L

        assertFalse(PeerMessageAttachmentStore.shouldPruneIncoming(1L, true, now, month))
        assertTrue(PeerMessageAttachmentStore.shouldPruneIncoming(1L, false, now, month))
    }

    @Test
    fun `encoded opus bytes are stored without an AES envelope`() {
        val filesDir = temporaryFolder.newFolder("opus-files")
        val encoded = "OggS-OpusHead-encrypted-payload".toByteArray()

        val stored = PeerMessageAttachmentStore.persistOutgoingVoiceBytes(
            filesDir = filesDir,
            encoded = encoded,
            messageId = 84L,
            extension = "opus"
        ).getOrThrow()

        assertTrue(stored.path.replace('\\', '/').endsWith("peer-message-attachments-v2/outgoing/voice/msg_84.opus.sasie"))
        assertArrayEquals(encoded, stored.readBytes())
        assertNotNull(PeerMessageAttachmentStore.resolveOutgoingVoice(filesDir, "voice-84.opus"))
    }
}
