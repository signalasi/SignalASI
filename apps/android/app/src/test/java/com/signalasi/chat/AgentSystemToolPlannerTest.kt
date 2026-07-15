package com.signalasi.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSystemToolPlannerTest {
    @Test
    fun recognizesChinesePhoneCameraCommands() {
        assertTrue(AgentSystemToolPlanner.isCameraCaptureGoal("\u8c03\u7528\u624b\u673a\u6444\u50cf\u5934\u62cd\u7167"))
        assertTrue(AgentSystemToolPlanner.isCameraCaptureGoal("\u6253\u5f00\u76f8\u673a\u62cd\u7167"))
        assertTrue(AgentSystemToolPlanner.isCameraCaptureGoal("\u4f7f\u7528\u6444\u50cf\u5934\u62cd\u7167"))
        assertTrue(AgentSystemToolPlanner.isCameraCaptureGoal("\u6253\u5f00\u76f8\u673a"))
        assertTrue(AgentSystemToolPlanner.isCameraCaptureGoal("\u62cd\u7167"))
    }

    @Test
    fun doesNotTreatCameraDiscussionAsAnAction() {
        assertFalse(AgentSystemToolPlanner.isCameraCaptureGoal("Explain how a camera sensor works"))
        assertFalse(AgentSystemToolPlanner.isCameraCaptureGoal("\u5206\u6790\u6444\u50cf\u5934\u7684\u539f\u7406"))
    }

    @Test
    fun parsesSpokenTimerDurations() {
        assertTrue(AgentSystemToolPlanner.timerSecondsForGoal("set timer one minute") == 60)
        assertTrue(AgentSystemToolPlanner.timerSecondsForGoal("set timer fifteen seconds") == 15)
        assertTrue(AgentSystemToolPlanner.timerSecondsForGoal("set timer 2 hours") == 7_200)
        assertTrue(AgentSystemToolPlanner.timerSecondsForGoal("\u8bbe\u7f6e3\u5206\u949f\u5012\u8ba1\u65f6") == 180)
    }

    @Test
    fun routesDirectChinesePhoneOperationsLocally() {
        val nativeTools = listOf(
            nativeDescriptor(AgentHardwareNativeTools.FLASHLIGHT_SET, "Request flashlight state", AgentNativeToolRisk.MEDIUM),
            nativeDescriptor(AgentHardwareNativeTools.BATTERY_STATUS, "Read battery status", AgentNativeToolRisk.LOW),
            nativeDescriptor(AgentAndroidSystemNativeTools.AUDIO_VOLUME_SET, "Set audio volume", AgentNativeToolRisk.MEDIUM)
        )
        val screen = ScreenContext(
            foregroundApp = "com.signalasi.chat",
            pageTitle = "SignalASI",
            installedApps = listOf(InstalledAppInfo("com.tencent.mm", "WeChat"))
        )
        val planner = RuleBasedAgentPlanner()

        val flashlight = planner.deterministicLocalAction(request("\u6253\u5f00\u624b\u7535\u7b52", screen, nativeTools))
        assertEquals(AgentActionKind.CALL_NATIVE_TOOL, flashlight?.kind)
        assertEquals(AgentHardwareNativeTools.FLASHLIGHT_SET, flashlight?.parameters?.get("tool_id"))
        assertTrue(JSONObject(flashlight?.parameters?.get("input_json").orEmpty()).getBoolean("enabled"))
        assertEquals(AgentConfirmationTier.DIRECT, AgentConfirmationPolicy.tier(requireNotNull(flashlight)))

        assertEquals(
            AgentHardwareNativeTools.BATTERY_STATUS,
            planner.deterministicLocalAction(request("\u67e5\u770b\u624b\u673a\u7535\u91cf", screen, nativeTools))?.parameters?.get("tool_id")
        )
        assertEquals(
            AgentAndroidSystemNativeTools.AUDIO_VOLUME_SET,
            planner.deterministicLocalAction(request("\u628a\u97f3\u91cf\u8bbe\u7f6e\u4e3a50", screen, nativeTools))?.parameters?.get("tool_id")
        )
        assertEquals("read-device-status", planner.deterministicLocalAction(request("\u67e5\u770b\u624b\u673a\u72b6\u6001", screen, nativeTools))?.id)
        assertEquals("open-installed-app", planner.deterministicLocalAction(request("\u6253\u5f00WeChat", screen, nativeTools))?.id)
        assertEquals("open-camera", planner.deterministicLocalAction(request("\u6253\u5f00\u76f8\u673a", screen, nativeTools))?.id)
        assertEquals("set-timer", planner.deterministicLocalAction(request("\u8bbe\u7f6e3\u5206\u949f\u5012\u8ba1\u65f6", screen, nativeTools))?.id)
        assertNotNull(planner.deterministicLocalAction(request("\u5173\u95ed\u624b\u7535\u7b52", screen, nativeTools)))
    }

    private fun request(
        goal: String,
        screen: ScreenContext,
        nativeTools: List<AgentNativeToolDescriptor>
    ): AgentRequest = AgentRequest(
        goal = goal,
        screen = screen,
        targets = emptyList(),
        memories = emptyList(),
        runtimeContext = AgentRuntimeContextBuilder.build(
            sessionId = "test",
            goal = goal,
            screen = screen,
            permissionMode = PermissionMode.AUTO_LOW_RISK,
            highRiskGuard = true,
            memoryCapture = false,
            callableTargets = emptyList(),
            memories = emptyList(),
            nativeTools = nativeTools
        )
    )

    private fun nativeDescriptor(
        id: String,
        title: String,
        risk: AgentNativeToolRisk
    ): AgentNativeToolDescriptor = AgentNativeToolDescriptor(
        id = id,
        version = "1.0.0",
        title = title,
        description = title,
        location = AgentNativeToolLocation.PHONE,
        inputSchema = AgentNativeJsonSchema(mapOf("type" to "object")),
        outputSchema = AgentNativeJsonSchema(mapOf("type" to "object")),
        risk = risk
    )
}
