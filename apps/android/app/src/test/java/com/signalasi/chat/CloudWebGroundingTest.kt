package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudWebGroundingTest {
    @Test
    fun exposesOnlyGenericWebTools() {
        val tools = CloudWebGrounding.openAiTools()
        val names = (0 until tools.length()).map { index ->
            tools.getJSONObject(index).getJSONObject("function").getString("name")
        }

        assertEquals(listOf("web_search", "web_fetch"), names)
        assertFalse(names.contains("get_weather"))
    }

    @Test
    fun detectsChangingInformationAndExplicitSearchWithoutDomainRouting() {
        assertTrue(CloudWebGrounding.requiresLiveData("What is the weather in Shanghai today?"))
        assertTrue(CloudWebGrounding.requiresLiveData("Latest technology news"))
        assertTrue(CloudWebGrounding.requiresLiveData("Search the web for SignalASI"))
        assertTrue(CloudWebGrounding.requiresLiveData("\u8054\u7f51\u641c\u7d22 SignalASI"))
        assertFalse(CloudWebGrounding.requiresLiveData("Explain binary search"))
    }
}
