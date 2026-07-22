package com.signalasi.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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

    @Test
    fun promptParserProducesTypedEnglishAndChineseEntityServiceCalls() {
        val english = HomeAssistantDeviceClient.serviceCallForPrompt(
            "Turn on the office light",
            "light.office"
        )
        val chinese = HomeAssistantDeviceClient.serviceCallForPrompt(
            "\u5173\u95ed\u5ba2\u5385\u7684\u706f",
            "light.living_room"
        )
        val cover = HomeAssistantDeviceClient.serviceCallForPrompt(
            "\u6253\u5f00\u5ba2\u5385\u7a97\u5e18",
            "cover.living_room"
        )

        assertEquals("homeassistant", english?.serviceDomain)
        assertEquals("turn_on", english?.service)
        assertEquals("light.office", english?.entityId)
        assertEquals("turn_off", chinese?.service)
        assertEquals("open_cover", cover?.service)
        assertEquals("cover", cover?.serviceDomain)
    }
}
