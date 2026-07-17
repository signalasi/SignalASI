package com.signalasi.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class AgentImagePipelineInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun largeCameraImageIsEncodedWithinTransportBudget() {
        val source = File(context.cacheDir, "transport-large-source.jpg")
        val width = 1600
        val height = 1200
        val pixels = IntArray(width * height)
        var state = 0x13579BDF
        for (index in pixels.indices) {
            state = state * 1103515245 + 12345
            pixels[index] = (0xFF shl 24) or (state and 0x00FFFFFF)
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        FileOutputStream(source).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 96, it) }
        bitmap.recycle()
        assertTrue(source.length() > AgentImagePipeline.TARGET_TRANSPORT_BYTES)

        val encoded = AgentImagePipeline.encodeForTransport(
            context,
            AgentInputAttachment(
                id = "large",
                uri = Uri.fromFile(source),
                displayName = "camera.jpg",
                mimeType = "image/jpeg",
                sizeBytes = source.length()
            )
        )

        assertNotNull(encoded)
        assertTrue(encoded!!.bytes.size <= AgentImagePipeline.TARGET_TRANSPORT_BYTES)
        assertFalse(encoded.lossless)
        assertNotNull(BitmapFactory.decodeByteArray(encoded.bytes, 0, encoded.bytes.size))
        source.delete()
    }

    @Test
    fun smallImageIsTransferredByteForByte() {
        val source = File(context.cacheDir, "transport-small-source.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF20A7D1.toInt())
        FileOutputStream(source).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val original = source.readBytes()

        val encoded = AgentImagePipeline.encodeForTransport(
            context,
            AgentInputAttachment(
                id = "small",
                uri = Uri.fromFile(source),
                displayName = "small.png",
                mimeType = "image/png",
                sizeBytes = source.length()
            )
        )

        assertNotNull(encoded)
        assertTrue(encoded!!.lossless)
        assertArrayEquals(original, encoded.bytes)
        source.delete()
    }
}
