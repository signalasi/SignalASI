package com.signalasi.chat

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConnectorAvailabilityTest {
    @Test
    fun desktopAgentsRequireAnOperationalStatus() {
        assertTrue(AgentConnectorAvailability.desktopAgentReady(JSONObject().put("setup_status", "ready")))
        assertTrue(AgentConnectorAvailability.desktopAgentReady(JSONObject().put("setup_status", "busy")))
        assertFalse(AgentConnectorAvailability.desktopAgentReady(JSONObject().put("setup_status", "degraded")))
        assertFalse(AgentConnectorAvailability.desktopAgentReady(JSONObject().put("setup_status", "needs_setup")))
        assertFalse(AgentConnectorAvailability.desktopAgentReady(JSONObject().put("setup_status", "unavailable")))
        assertFalse(AgentConnectorAvailability.desktopAgentReady(JSONObject()))
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
