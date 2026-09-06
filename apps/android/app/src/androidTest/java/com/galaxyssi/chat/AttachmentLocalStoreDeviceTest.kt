package com.galaxyssi.chat

import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AttachmentLocalStoreDeviceTest {
    @Test fun rawAttachmentProviderIsSeekableAndDoesNotExposeOtherPrivateFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "peer-incoming-attachments-v2/plain-test-${UUID.randomUUID()}")
        val file = File(directory, "video.bin")
        val bytes = ByteArray(300_123) { (it * 31).toByte() }
        try {
            AttachmentLocalStore.storeBytes(bytes, file)
            assertArrayEquals(bytes, file.readBytes())
            val uri = LocalAttachmentUris.forFile(context, file, "video.mp4", "video/mp4")
            assertEquals(file.canonicalFile, LocalAttachmentUris.resolve(context, uri))
            context.contentResolver.openFileDescriptor(uri, "r")!!.use { descriptor ->
                assertEquals(65_537L, Os.lseek(descriptor.fileDescriptor, 65_537L, OsConstants.SEEK_SET))
                val actual = ByteArray(32)
                assertEquals(32, Os.read(descriptor.fileDescriptor, actual, 0, actual.size))
                assertArrayEquals(bytes.copyOfRange(65_537, 65_569), actual)
            }
            val encoded = android.util.Base64.encodeToString("../outside".toByteArray(),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
            val traversal = uri.buildUpon().path(null).appendPath("file").appendPath(encoded).build()
            assertNull(LocalAttachmentUris.resolve(context, traversal))
            val unrelated = File(context.filesDir, "plain-test-private-${UUID.randomUUID()}").apply { writeText("private") }
            try {
                assertTrue(runCatching { LocalAttachmentUris.forFile(context, unrelated) }.isFailure)
            } finally { unrelated.delete() }
        } finally { directory.deleteRecursively() }
    }
}
