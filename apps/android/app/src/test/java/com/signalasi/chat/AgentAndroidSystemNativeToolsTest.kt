package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentAndroidSystemNativeToolsTest {
    @Test
    fun catalogUsesUniqueStableNamespacedIds() {
        val ids = AgentAndroidSystemNativeTools.toolIds

        assertEquals(32, ids.size)
        assertTrue(ids.all { it.startsWith("signalasi.android.") })
        assertTrue(ids.none { it.contains(' ') })
    }

    @Test
    fun catalogCoversRequestedSystemDomains() {
        val ids = AgentAndroidSystemNativeTools.toolIds
        listOf(
            ".telephony.", ".sms.", ".contacts.", ".calendar.", ".wifi.",
            ".audio.", ".download.", ".biometric.", ".vpn.", ".device_policy."
        ).forEach { domain -> assertTrue("Missing $domain tools", ids.any { domain in it }) }
    }
}
