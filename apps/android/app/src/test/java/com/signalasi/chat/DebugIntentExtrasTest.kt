package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugIntentExtrasTest {
    @Test
    fun consumesEveryOneShotExtraExactlyOnce() {
        val removed = mutableListOf<String>()

        DebugIntentExtras.consume(removed::add)

        assertEquals(DebugIntentExtras.oneShotKeys, removed)
        assertEquals(removed.size, removed.toSet().size)
    }

    @Test
    fun coversEveryDebugNavigationFamily() {
        val keys = DebugIntentExtras.oneShotKeys.toSet()

        assertTrue(
            keys.containsAll(
                setOf(
                    "signalasi_debug_open_protocol_quality",
                    "signalasi_debug_open_signal_link_protocol",
                    "signalasi_debug_open_advanced_options",
                    "signalasi_debug_open_security",
                    "signalasi_debug_open_contact_detail",
                    "signalasi_debug_open_cloud_provider",
                    "signalasi_debug_control_center_page",
                    "signalasi_debug_incoming_b64"
                )
            )
        )
    }
}
