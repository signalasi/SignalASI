package com.signalasi.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSystemToolPlannerTest {
    @Test
    fun routesSmallChinesePythonWorkToThePhoneRuntime() {
        val screen = ScreenContext(foregroundApp = "com.signalasi.chat", pageTitle = "SignalASI")
        val runtime = nativeDescriptor(
            AgentOnDeviceRuntimeTools.EXECUTE,
            "Execute in the on-device Linux sandbox",
            AgentNativeToolRisk.MEDIUM
        )
        val codex = AgentCallableTarget(
            id = "codex",
            title = "Codex",
            kind = AgentConnectorKind.AGENT,
            status = AgentConnectorStatus.AVAILABLE,
            capabilities = listOf(AgentCapability.CHAT, AgentCapability.CODE, AgentCapability.REASONING)
        )
        val plan = RuleBasedAgentPlanner().plan(
            request("\u8bf7\u5199\u4e00\u4e2a\u7b80\u5355\u7684python\u7a0b\u5e8f\uff0c\u5e76\u9a8c\u8bc1", screen, listOf(runtime), listOf(codex))
        )

        assertTrue(plan.validation.valid)
        assertEquals(listOf(AgentActionKind.CALL_CONNECTOR, AgentActionKind.CALL_NATIVE_TOOL), plan.actions.map { it.kind })
        val author = plan.actions.first()
        val execute = plan.actions.last()
        assertEquals(PHONE_DEVELOPMENT_CONNECTOR_MODE, author.parameters["connector_task_mode"])
        assertTrue(author.parameters["prompt"].orEmpty().contains("Do not run commands"))
        assertEquals(author.id, execute.parameters["depends_on"])
        assertEquals(author.id, execute.parameters["use_outputs_from"])
        assertTrue(execute.isPhoneDevelopmentRuntimeHandoff())
        assertEquals(AgentConfirmationTier.DIRECT, AgentConfirmationPolicy.tier(execute))

        val generatedSource = "values = [1, 2, 3]\n    # preserve indentation and / characters\nprint(sum(values) / len(values))\nassert sum(values) == 6"
        val manifest = JSONObject()
            .put("schema", "signalasi.phone-development-manifest.v1")
            .put("language", "python")
            .put("file_name", "simple_average.py")
            .put("source", generatedSource)
            .put("artifact_paths", emptyList<String>())
            .toString()
        val completed = plan.markAction(author.id, AgentActionStatus.COMPLETED, AgentActionResult(author.id, true, manifest))
        val materialized = completed.materializeToolInput(execute, allowOutputHandoff = false)
        val input = JSONObject(materialized.parameters.getValue("input_json"))

        assertEquals("python", input.getString("language"))
        assertTrue(input.getString("source").contains("simple_average.py"))
        val wrappedSource = input.getString("source")
        val encoded = java.util.Base64.getEncoder().encodeToString(generatedSource.toByteArray(Charsets.UTF_8))
        assertTrue(wrappedSource.contains(encoded))
        assertFalse(wrappedSource.contains(generatedSource))
        assertEquals("simple_average.py", input.getJSONArray("artifact_paths").getString(0))
        assertEquals("simple_average.py", materialized.parameters[PHONE_DEVELOPMENT_FILE_PARAMETER])
    }

    @Test
    fun materializesMultiFilePhoneProjectWithoutFlatteningDirectories() {
        val manifest = JSONObject()
            .put("schema", "signalasi.phone-development-manifest.v2")
            .put("language", "python")
            .put("entry_file", "src/main.py")
            .put("files", org.json.JSONArray()
                .put(JSONObject().put("path", "src/main.py").put("content", "from lib.maths import total\nprint(total([2, 3]))"))
                .put(JSONObject().put("path", "src/lib/maths.py").put("content", "def total(values):\n    return sum(values)")))
            .put("artifact_paths", org.json.JSONArray().put("README.md"))
        val parsed = AgentPhoneDevelopmentManifestCodec.parse(manifest.toString()).getOrThrow()
        val input = parsed.runtimeInput()

        assertEquals("src/main.py", parsed.entryFile)
        assertEquals(listOf("src/main.py", "src/lib/maths.py"), parsed.files.map { it.path })
        assertEquals(3, input.getJSONArray("artifact_paths").length())
        assertTrue(input.getString("source").contains("mkdir(parents=True"))
        assertTrue(input.getString("source").contains("runpy.run_path"))
    }

    @Test
    fun keepsLargeOrExplicitDesktopDevelopmentTasksOnDesktop() {
        assertFalse(AgentPhoneDevelopmentPolicy.shouldUsePhoneRuntime("Build the entire Android repository with Gradle"))
        assertFalse(AgentPhoneDevelopmentPolicy.shouldUsePhoneRuntime("Write a Python program on the desktop"))
        assertTrue(AgentPhoneDevelopmentPolicy.shouldUsePhoneRuntime("Write a simple Python program and verify it"))
        assertTrue(AgentPhoneDevelopmentPolicy.shouldUsePhoneRuntime("\u5728\u624b\u673a\u672c\u673a\u5199\u4e00\u4e2a Python \u811a\u672c\u5e76\u6d4b\u8bd5"))
    }

    @Test
    fun rendersUnavailablePackageAsNaturalActionableFailure() {
        val english = renderPackageUnavailable("com.signalasi.missing", zh = false)
        assertTrue(english.contains("com.signalasi.missing"))
        assertTrue(english.contains("Check the package name"))
        assertFalse(english.contains("expose"))

        val chinese = renderPackageUnavailable("com.signalasi.missing", zh = true)
        assertTrue(chinese.contains("com.signalasi.missing"))
        assertFalse(chinese.contains("SignalASI"))
    }

    @Test
    fun rendersPhoneWebSearchAsConciseLinkedResults() {
        val rendered = renderPhoneWebSearchResult(
            mapOf(
                "results" to listOf(
                    mapOf("title" to "First headline", "url" to "https://example.com/first"),
                    mapOf("title" to "Second headline", "url" to "https://example.com/second")
                )
            ),
            zh = false
        )

        assertEquals(
            "Latest web results:\n- [First headline](https://example.com/first)\n- [Second headline](https://example.com/second)",
            rendered
        )
        assertFalse(rendered.contains("provider"))
        assertFalse(rendered.contains("retrieved_at"))
    }

    @Test
    fun runsGenericWebToolsAtTheReasoningExecutionSite() {
        val screen = ScreenContext(foregroundApp = "com.signalasi.chat", pageTitle = "SignalASI")
        val webTool = nativeDescriptor(AgentWebMediaNativeTools.WEB_SEARCH, "Search the public web", AgentNativeToolRisk.LOW)
        val codex = AgentCallableTarget(
            id = "codex",
            title = "Codex",
            kind = AgentConnectorKind.AGENT,
            status = AgentConnectorStatus.AVAILABLE,
            capabilities = listOf(AgentCapability.CHAT, AgentCapability.RESEARCH, AgentCapability.LIVE_DATA, AgentCapability.TOOL_USE)
        )
        val remotePlan = RuleBasedAgentPlanner().plan(request("Latest technology news today", screen, listOf(webTool), listOf(codex)))
        assertEquals(1, remotePlan.actions.size)
        assertEquals(AgentActionKind.CALL_CONNECTOR, remotePlan.actions.single().kind)
        assertEquals("agent_host", remotePlan.actions.single().parameters["web_execution_location"])

        val cloud = codex.copy(id = "cloud-models", title = "Cloud Models", kind = AgentConnectorKind.MODEL)
        val cloudPlan = RuleBasedAgentPlanner().plan(request("Shanghai weather today", screen, listOf(webTool), listOf(cloud)))
        assertEquals(1, cloudPlan.actions.size)
        assertEquals("phone", cloudPlan.actions.single().parameters["web_execution_location"])

        val toolLessModel = cloud.copy(id = "local-llm", title = "Local LLM", capabilities = listOf(AgentCapability.CHAT))
        val fallbackPlan = RuleBasedAgentPlanner().plan(request("Latest technology news today", screen, listOf(webTool), listOf(toolLessModel)))
        assertEquals(listOf(AgentActionKind.CALL_NATIVE_TOOL, AgentActionKind.CALL_CONNECTOR), fallbackPlan.actions.map { it.kind })
        assertEquals(fallbackPlan.actions.first().id, fallbackPlan.actions.last().parameters["depends_on"])
        assertEquals(fallbackPlan.actions.first().id, fallbackPlan.actions.last().parameters["use_outputs_from"])
    }

    @Test
    fun ordinaryConnectorResearchDoesNotInheritPhoneToolConsentTerms() {
        val action = AgentAction(
            id = "connector-codex",
            kind = AgentActionKind.CALL_CONNECTOR,
            target = "Codex",
            risk = AgentRisk.LOW,
            status = AgentActionStatus.PENDING_CONFIRMATION,
            description = "Research current information",
            parameters = mapOf("connector_id" to "codex", "prompt" to "Find the current location of the event")
        )

        assertEquals(AgentConfirmationTier.DIRECT, AgentConfirmationPolicy.tier(action))
    }

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
            nativeDescriptor(AgentHardwareNativeTools.POWER_STATUS, "Read power status", AgentNativeToolRisk.LOW),
            nativeDescriptor(AgentHardwareNativeTools.STORAGE_STATUS, "Read storage status", AgentNativeToolRisk.LOW),
            nativeDescriptor(AgentHardwareNativeTools.NETWORK_STATUS, "Read network status", AgentNativeToolRisk.LOW),
            nativeDescriptor(AgentWebMediaNativeTools.WEB_SEARCH, "Search the public web", AgentNativeToolRisk.LOW),
            nativeDescriptor(AgentHardwareNativeTools.LOCATION_FOREGROUND_READ, "Read location", AgentNativeToolRisk.HIGH),
            nativeDescriptor(AgentHardwareNativeTools.SENSORS_LIST, "List sensors", AgentNativeToolRisk.LOW),
            nativeDescriptor(AgentHardwareNativeTools.SENSOR_SAMPLE, "Read sensor", AgentNativeToolRisk.MEDIUM),
            nativeDescriptor(AgentHardwareNativeTools.BLUETOOTH_STATUS, "Read Bluetooth status", AgentNativeToolRisk.LOW),
            nativeDescriptor(AgentHardwareNativeTools.NFC_STATUS, "Read NFC status", AgentNativeToolRisk.LOW),
            nativeDescriptor(AgentHardwareNativeTools.INSTALLED_APPS_LIST, "List installed apps", AgentNativeToolRisk.MEDIUM),
            nativeDescriptor(AgentHardwareNativeTools.PACKAGE_DETAIL, "Read package detail", AgentNativeToolRisk.MEDIUM),
            nativeDescriptor(AgentAndroidSystemNativeTools.AUDIO_VOLUME_SET, "Set Android stream volume", AgentNativeToolRisk.MEDIUM),
            nativeDescriptor(AgentAndroidSystemNativeTools.TELEPHONY_DIAL_HANDOFF, "Open Android dialer", AgentNativeToolRisk.HIGH),
            nativeDescriptor(AgentAndroidSystemNativeTools.SMS_SEND, "Send SMS message", AgentNativeToolRisk.HIGH)
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

        val englishFlashlightOn = planner.deterministicLocalAction(request("Turn on the flashlight", screen, nativeTools))
        assertEquals(AgentHardwareNativeTools.FLASHLIGHT_SET, englishFlashlightOn?.parameters?.get("tool_id"))
        assertTrue(JSONObject(englishFlashlightOn?.parameters?.get("input_json").orEmpty()).getBoolean("enabled"))

        val englishFlashlightOff = planner.deterministicLocalAction(request("Turn off the flashlight", screen, nativeTools))
        assertEquals(AgentHardwareNativeTools.FLASHLIGHT_SET, englishFlashlightOff?.parameters?.get("tool_id"))
        assertFalse(JSONObject(englishFlashlightOff?.parameters?.get("input_json").orEmpty()).getBoolean("enabled"))

        val chineseBattery = planner.deterministicLocalAction(request("\u67e5\u770b\u624b\u673a\u7535\u91cf", screen, nativeTools))
        assertEquals(
            AgentHardwareNativeTools.BATTERY_STATUS,
            chineseBattery?.parameters?.get("tool_id")
        )
        assertEquals("zh", chineseBattery?.parameters?.get("response_language"))
        listOf(
            "\u8bfb\u53d6\u8fd9\u53f0\u624b\u673a\u7684\u5f53\u524d\u7535\u91cf\uff0c\u7b80\u77ed\u56de\u7b54\u3002",
            "\u8fd9\u53f0\u624b\u673a\u8fd8\u6709\u591a\u5c11\u7535\u91cf\uff1f",
            "Read the current battery level on this phone."
        ).forEach { goal ->
            assertEquals(
                goal,
                AgentHardwareNativeTools.BATTERY_STATUS,
                planner.deterministicLocalAction(request(goal, screen, nativeTools))?.parameters?.get("tool_id")
            )
        }
        assertEquals(
            "en",
            planner.deterministicLocalAction(
                request("Read the current battery level on this phone.", screen, nativeTools)
            )?.parameters?.get("response_language")
        )
        assertEquals(
            AgentHardwareNativeTools.POWER_STATUS,
            planner.deterministicLocalAction(request("Check battery saver status", screen, nativeTools))?.parameters?.get("tool_id")
        )
        assertEquals(
            AgentAndroidSystemNativeTools.AUDIO_VOLUME_SET,
            planner.deterministicLocalAction(request("\u628a\u97f3\u91cf\u8bbe\u7f6e\u4e3a50", screen, nativeTools))?.parameters?.get("tool_id")
        )
        val englishVolume = requireNotNull(
            planner.deterministicLocalAction(request("Set media volume 30", screen, nativeTools))
        )
        assertEquals(AgentAndroidSystemNativeTools.AUDIO_VOLUME_SET, englishVolume.parameters["tool_id"])
        assertEquals("music", JSONObject(englishVolume.parameters.getValue("input_json")).getString("stream"))
        assertEquals(30, JSONObject(englishVolume.parameters.getValue("input_json")).getInt("percent"))
        assertEquals(AgentConfirmationTier.DIRECT, AgentConfirmationPolicy.tier(englishVolume))
        val dial = requireNotNull(planner.deterministicLocalAction(request("Dial 12345", screen, nativeTools)))
        assertEquals(AgentAndroidSystemNativeTools.TELEPHONY_DIAL_HANDOFF, dial.parameters["tool_id"])
        assertEquals(AgentConfirmationTier.CONFIRM_ALWAYS, AgentConfirmationPolicy.tier(dial))
        val sms = requireNotNull(planner.deterministicLocalAction(request("Send SMS to 12345: hello", screen, nativeTools)))
        assertEquals(AgentAndroidSystemNativeTools.SMS_SEND, sms.parameters["tool_id"])
        assertEquals(AgentConfirmationTier.CONFIRM_ALWAYS, AgentConfirmationPolicy.tier(sms))
        mapOf(
            "\u67e5\u770b\u7701\u7535\u6a21\u5f0f" to AgentHardwareNativeTools.POWER_STATUS,
            "\u67e5\u770b\u624b\u673a\u5b58\u50a8" to AgentHardwareNativeTools.STORAGE_STATUS,
            "\u67e5\u770b\u624b\u673a\u7f51\u7edc\u72b6\u6001" to AgentHardwareNativeTools.NETWORK_STATUS,
            "\u83b7\u53d6\u5f53\u524d\u4f4d\u7f6e" to AgentHardwareNativeTools.LOCATION_FOREGROUND_READ,
            "\u5217\u51fa\u624b\u673a\u4f20\u611f\u5668" to AgentHardwareNativeTools.SENSORS_LIST,
            "\u8bfb\u53d6\u9640\u87ba\u4eea\u4f20\u611f\u5668" to AgentHardwareNativeTools.SENSOR_SAMPLE,
            "\u67e5\u770b\u84dd\u7259\u72b6\u6001" to AgentHardwareNativeTools.BLUETOOTH_STATUS,
            "\u67e5\u770bNFC\u72b6\u6001" to AgentHardwareNativeTools.NFC_STATUS,
            "\u5217\u51fa\u5df2\u5b89\u88c5\u5e94\u7528" to AgentHardwareNativeTools.INSTALLED_APPS_LIST,
            "package detail com.signalasi.chat" to AgentHardwareNativeTools.PACKAGE_DETAIL
        ).forEach { (goal, expectedTool) ->
            assertEquals(
                goal,
                expectedTool,
                planner.deterministicLocalAction(request(goal, screen, nativeTools))?.parameters?.get("tool_id")
            )
        }
        val sensorInput = JSONObject(
            planner.deterministicLocalAction(request("\u8bfb\u53d6\u9640\u87ba\u4eea\u4f20\u611f\u5668", screen, nativeTools))
                ?.parameters?.get("input_json").orEmpty()
        )
        assertEquals("gyroscope", sensorInput.getString("type"))
        val appSearch = requireNotNull(
            planner.deterministicLocalAction(request("Search installed apps SignalASI", screen, nativeTools))
        )
        assertEquals(AgentHardwareNativeTools.INSTALLED_APPS_LIST, appSearch.parameters["tool_id"])
        assertEquals("SignalASI", JSONObject(appSearch.parameters.getValue("input_json")).getString("query"))
        assertEquals("read-device-status", planner.deterministicLocalAction(request("\u67e5\u770b\u624b\u673a\u72b6\u6001", screen, nativeTools))?.id)
        assertEquals("open-installed-app", planner.deterministicLocalAction(request("\u6253\u5f00WeChat", screen, nativeTools))?.id)
        assertEquals("open-camera", planner.deterministicLocalAction(request("\u6253\u5f00\u76f8\u673a", screen, nativeTools))?.id)
        assertEquals("set-timer", planner.deterministicLocalAction(request("\u8bbe\u7f6e3\u5206\u949f\u5012\u8ba1\u65f6", screen, nativeTools))?.id)
        assertNotNull(planner.deterministicLocalAction(request("\u5173\u95ed\u624b\u7535\u7b52", screen, nativeTools)))
    }

    private fun request(
        goal: String,
        screen: ScreenContext,
        nativeTools: List<AgentNativeToolDescriptor>,
        targets: List<AgentCallableTarget> = emptyList()
    ): AgentRequest = AgentRequest(
        goal = goal,
        screen = screen,
        targets = targets,
        memories = emptyList(),
        runtimeContext = AgentRuntimeContextBuilder.build(
            sessionId = "test",
            goal = goal,
            screen = screen,
            permissionMode = PermissionMode.AUTO_LOW_RISK,
            highRiskGuard = true,
            memoryCapture = false,
            callableTargets = targets,
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
