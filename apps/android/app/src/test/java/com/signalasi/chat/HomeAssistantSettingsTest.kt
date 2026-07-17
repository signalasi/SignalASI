package com.signalasi.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantSettingsTest {
    @Test
    fun configuredRequiresCredentialsAndEnabledState() {
        val missing = HomeAssistantSettings(enabled = true)
        assertFalse(missing.credentialsConfigured)
        assertFalse(missing.configured)

        val disabled = HomeAssistantSettings(
            enabled = false,
            baseUrl = "http://homeassistant.local:8123",
            accessToken = "token"
        )
        assertTrue(disabled.credentialsConfigured)
        assertFalse(disabled.configured)

        assertTrue(disabled.copy(enabled = true).configured)
    }
}
