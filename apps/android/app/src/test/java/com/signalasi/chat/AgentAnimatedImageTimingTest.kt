package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AgentAnimatedImageTimingTest {
    @Test
    fun addsDelayToZeroDurationGifFrames() {
        val gif = byteArrayOf(
            'G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(),
            '8'.code.toByte(), '9'.code.toByte(), 'a'.code.toByte(),
            0x21, 0xF9.toByte(), 0x04, 0x00, 0x00, 0x00, 0x00, 0x00
        )

        val normalized = AgentAnimatedImageTiming.normalizeZeroFrameDelays(gif)

        assertEquals(8, normalized[10].toInt())
        assertEquals(0, normalized[11].toInt())
    }

    @Test
    fun preservesExistingGifTiming() {
        val gif = byteArrayOf(
            'G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(),
            '8'.code.toByte(), '9'.code.toByte(), 'a'.code.toByte(),
            0x21, 0xF9.toByte(), 0x04, 0x00, 0x0A, 0x00, 0x00, 0x00
        )

        assertSame(gif, AgentAnimatedImageTiming.normalizeZeroFrameDelays(gif))
    }
}
