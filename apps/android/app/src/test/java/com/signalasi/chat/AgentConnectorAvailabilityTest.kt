package com.signalasi.chat

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConnectorAvailabilityTest {
    @Test
    fun desktopAgentsRequireAnOperationalStatus() {
        val now = 1_000_000L
        fun contact(status: String, updatedAt: Long = now): JSONObject = JSONObject()
            .put("setup_status", status)
            .put("setup_updated_at", updatedAt)

        assertTrue(AgentConnectorAvailability.desktopAgentReady(contact("ready"), now))
        assertTrue(AgentConnectorAvailability.desktopAgentReady(contact("busy"), now))
        assertFalse(AgentConnectorAvailability.desktopAgentReady(contact("degraded"), now))
        assertFalse(AgentConnectorAvailability.desktopAgentReady(contact("needs_setup"), now))
        assertFalse(AgentConnectorAvailability.desktopAgentReady(contact("unavailable"), now))
        assertFalse(AgentConnectorAvailability.desktopAgentReady(contact("ready", now - 600_001L), now))
        assertFalse(AgentConnectorAvailability.desktopAgentReady(JSONObject().put("setup_status", "ready"), now))
    }

    @Test
    fun cloudModelsRequireTheCompleteEnabledConfiguration() {
        val complete = JSONObject()
            .put("setup_status", "ready")
            .put("cloud_provider", "deepseek")
            .put("cloud_model", "deepseek-v4")
            .put("cloud_endpoint", "https://api.example.test/v1/chat/completions")
            .put("cloud_api_key", "secret")
        assertTrue(AgentConnectorAvailability.cloudModelReady(complete))
        assertFalse(AgentConnectorAvailability.cloudModelReady(JSONObject(complete.toString()).put("cloud_api_key", "")))
        assertFalse(AgentConnectorAvailability.cloudModelReady(JSONObject(complete.toString()).put("cloud_model", "")))
        assertFalse(AgentConnectorAvailability.cloudModelReady(JSONObject(complete.toString()).put("cloud_endpoint", "")))
        assertFalse(AgentConnectorAvailability.cloudModelReady(JSONObject(complete.toString()).put("setup_status", "needs_setup")))
    }
}
