package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentConfirmationPolicyTest {
    @Test
    fun directPhoneUtilitiesDoNotRequireConfirmation() {
        assertEquals(AgentConfirmationTier.DIRECT, tier("set-timer", AgentActionKind.SET_ALARM, "Set timer for 60 seconds"))
        assertEquals(AgentConfirmationTier.DIRECT, tier("open-camera", AgentActionKind.OPEN_APP, "Open camera and take photo"))
        assertEquals(AgentConfirmationTier.DIRECT, tier("set-volume", AgentActionKind.CALL_NATIVE_TOOL, "Set audio volume"))
        assertEquals(AgentConfirmationTier.DIRECT, tier("open-flashlight", AgentActionKind.CALL_NATIVE_TOOL, "Open flashlight"))
        assertEquals(AgentConfirmationTier.DIRECT, tier("open-notes", AgentActionKind.OPEN_APP, "Open Notes"))
        assertEquals(AgentConfirmationTier.DIRECT, nativeTier(AgentAndroidSystemNativeTools.AUDIO_VOLUME_SET, "Set Android stream volume"))
        assertEquals(AgentConfirmationTier.DIRECT, nativeTier(AgentAndroidSystemNativeTools.WIFI_HOTSPOT_PANEL_OPEN, "Open hotspot settings"))
        assertEquals(AgentConfirmationTier.DIRECT, nativeTier(AgentHardwareNativeTools.BLUETOOTH_PAIRING_HANDOFF, "Open Bluetooth pairing settings"))
    }

    @Test
    fun sensitiveCapabilitiesRequireOneRememberedConfirmation() {
        assertEquals(AgentConfirmationTier.CONFIRM_ONCE, tier("location", AgentActionKind.CALL_NATIVE_TOOL, "Read location"))
        assertEquals(AgentConfirmationTier.CONFIRM_ONCE, tier("download", AgentActionKind.CALL_NATIVE_TOOL, "Download file"))
        assertEquals(AgentConfirmationTier.CONFIRM_ONCE, tier("contact-upsert", AgentActionKind.CALL_NATIVE_TOOL, "Create contact"))
        assertEquals(
            AgentConfirmationTier.CONFIRM_ONCE,
            nativeTier(AgentHardwareNativeTools.BLUETOOTH_DISCOVERY_FOREGROUND, "Discover nearby Bluetooth devices once")
        )
        assertEquals(
            AgentConfirmationTier.CONFIRM_ONCE,
            nativeTier(AgentHardwareNativeTools.INSTALLED_APPS_LIST, "List query-visible installed apps")
        )
    }

    @Test
    fun rememberedNativeConsentKeysAreStableAcrossEquivalentRequests() {
        val first = nativeAction(
            AgentHardwareNativeTools.BLUETOOTH_DISCOVERY_FOREGROUND,
            "Discover nearby Bluetooth devices once",
            "native-first"
        )
        val second = nativeAction(
            AgentHardwareNativeTools.BLUETOOTH_DISCOVERY_FOREGROUND,
            "Scan for nearby Bluetooth devices",
            "native-second"
        )

        assertEquals("bluetooth_discovery", AgentConfirmationPolicy.consentKey(first))
        assertEquals(AgentConfirmationPolicy.consentKey(first), AgentConfirmationPolicy.consentKey(second))
    }

    @Test
    fun consequentialActionsAlwaysRequireConfirmation() {
        assertEquals(AgentConfirmationTier.CONFIRM_ALWAYS, tier("sms-send", AgentActionKind.CALL_NATIVE_TOOL, "Send SMS message"))
        assertEquals(AgentConfirmationTier.CONFIRM_ALWAYS, tier("delete-file", AgentActionKind.CALL_NATIVE_TOOL, "Delete file"))
        assertEquals(AgentConfirmationTier.CONFIRM_ALWAYS, tier("lock", AgentActionKind.LOCK_SCREEN, "Lock device"))
    }

    @Test
    fun priorConversationDoesNotEscalateTheCurrentRequest() {
        val action = AgentAction(
            id = "connector-codex",
            kind = AgentActionKind.CALL_CONNECTOR,
            target = "Codex",
            risk = AgentRisk.LOW,
            status = AgentActionStatus.PENDING_CONFIRMATION,
            description = "Ask Codex",
            parameters = mapOf(
                "prompt" to "Show an animated letter",
                "_signalasi_conversation_context" to "Earlier the user asked to send a message"
            )
        )

        assertEquals(AgentConfirmationTier.DIRECT, AgentConfirmationPolicy.tier(action))
    }

    private fun tier(id: String, kind: AgentActionKind, description: String): AgentConfirmationTier =
        AgentConfirmationPolicy.tier(
            AgentAction(
                id = id,
                kind = kind,
                target = "Android",
                risk = AgentRisk.MEDIUM,
                status = AgentActionStatus.PENDING_CONFIRMATION,
                description = description
            )
        )

    private fun nativeTier(toolId: String, description: String): AgentConfirmationTier =
        AgentConfirmationPolicy.tier(nativeAction(toolId, description, "native-${toolId.hashCode()}"))

    private fun nativeAction(toolId: String, description: String, id: String) = AgentAction(
        id = id,
        kind = AgentActionKind.CALL_NATIVE_TOOL,
        target = "Android",
        risk = AgentRisk.MEDIUM,
        status = AgentActionStatus.PENDING_CONFIRMATION,
        description = description,
        parameters = mapOf("tool_id" to toolId, "input_json" to "{}")
    )
}
