package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudContextOverflowPolicyTest {
    @Test
    fun recognizesProviderContextErrorsWithoutTreatingAuthenticationAsOverflow() {
        assertTrue(
            CloudContextOverflowPolicy.isContextOverflow(
                CloudHttpException(400, """{"code":"context_length_exceeded"}""")
            )
        )
        assertTrue(
            CloudContextOverflowPolicy.isContextOverflow(
                CloudHttpException(413, "Request too large")
            )
        )
        assertFalse(
            CloudContextOverflowPolicy.isContextOverflow(
                CloudHttpException(401, "Too many tokens in the supplied credential")
            )
        )
        assertFalse(
            CloudContextOverflowPolicy.isContextOverflow(
                CloudHttpException(400, "Unknown model")
            )
        )
    }

    @Test
    fun retryWindowsShrinkByTokenCapacityAndNeverByMessageCount() {
        assertEquals(
            listOf(64_000, 32_000, 16_000, 8_000),
            CloudContextOverflowPolicy.retryWindows(64_000)
        )
        assertEquals(
            listOf(8_192, 4_096),
            CloudContextOverflowPolicy.retryWindows(8_192)
        )
    }
}
