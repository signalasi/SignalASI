package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentImagePipelineTest {
    @Test
    fun samplingKeepsCameraImagesNearRequestedDimensions() {
        assertEquals(2, AgentImagePipeline.sampleSize(4032, 3024, 1600, 1600))
        assertEquals(16, AgentImagePipeline.sampleSize(4032, 3024, 224, 168))
        assertEquals(1, AgentImagePipeline.sampleSize(640, 480, 1600, 1600))
    }

    @Test
    fun transcodedImagesReceiveMatchingFileExtension() {
        val encoded = AgentTransportImage(byteArrayOf(1), "image/jpeg", lossless = false)

        assertEquals("photo.jpg", encoded.transportName("photo.png"))
        assertEquals("image.jpg", encoded.transportName(""))
    }

    @Test
    fun losslessImagesKeepOriginalNameAndTargetIsOneHundredKilobytes() {
        val encoded = AgentTransportImage(byteArrayOf(1), "image/png", lossless = true)

        assertEquals("diagram.png", encoded.transportName("diagram.png"))
        assertEquals(100 * 1024, AgentImagePipeline.TARGET_TRANSPORT_BYTES)
        assertTrue(encoded.lossless)
        assertFalse(encoded.bytes.isEmpty())
    }
}
