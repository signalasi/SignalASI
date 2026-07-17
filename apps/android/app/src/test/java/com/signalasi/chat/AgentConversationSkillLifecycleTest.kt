package com.signalasi.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationSkillLifecycleTest {
    @Test
    fun onlyExplicitCommandsRequestSkillCompilation() {
        assertFalse(AgentSkillCommandParser.isSaveCommand("Search today's news"))
        assertFalse(AgentSkillCommandParser.isSaveCommand("Remember this preference"))
        assertTrue(AgentSkillCommandParser.isSaveCommand("Save this as a Skill"))
        assertTrue(AgentSkillCommandParser.isSaveCommand("\u4fdd\u5b58\u6210 Skill"))
        assertTrue(AgentSkillCommandParser.isSaveCommand("\u4fdd\u5b58\u6210skill,\u4ee5\u540e\u6211\u8bf4\u67e5\u4ec0\u4e48\u5b57\u7b14\u987a\u7b14\u753b\u5c31\u8c03\u7528\u8fd9\u4e2askill"))
        assertTrue(AgentSkillCommandParser.isSaveCommand("\u628a\u8fd9\u4e2a\u4fdd\u5b58\u4e3a Skill\uff0c\u4ee5\u540e\u7ee7\u7eed\u4f7f\u7528"))
        assertFalse(AgentSkillCommandParser.isSaveCommand("\u4e0d\u8981\u4fdd\u5b58\u6210 Skill"))
        assertTrue(AgentSkillCommandParser.isUpgradeCommand("Upgrade this Skill"))
    }

    @Test
    fun matcherRequiresConfidenceAndHonorsNegativeExamples() {
        val runtime = AgentSkillRuntime(availableNativeToolIds = setOf("web.search"))
        val installed = runtime.install(
            AgentSkillManifest(
                id = "daily-news",
                version = "1.0.0",
                title = "Daily news",
                description = "Find current news",
                instructions = "Search current public news.",
                nativeTools = setOf("web.search"),
                parameters = AgentSkillParameterSchema.objectSchema(
                    properties = mapOf("request" to AgentSkillParameterSchema.string()),
                    required = setOf("request")
                ),
                steps = listOf(AgentSkillStep("search", "web.search", mapOf("query" to "{{parameters.request}}"))),
                autoInvoke = true,
                triggerExamples = listOf("Find today's technology news"),
                negativeExamples = listOf("Open my saved news file")
            )
        )
        val matcher = AgentSkillMatcher(runtime)

        assertNotNull(matcher.match("Find today's technology news"))
        assertNull(matcher.match("Open my saved news file"))
        assertNull(matcher.match("Turn on the flashlight"))
        assertNotNull(matcher.match("@${installed.id} find AI news"))
        assertNotNull(matcher.match("@Daily news find security news"))
    }

    @Test
    fun matcherReusesParameterizedSingleCharacterTask() {
        val argumentMarker = 0x5B57.toChar()
        val firstArgument = 0x7532.toChar()
        val secondArgument = 0x4E59.toChar()
        val savedRequest = "use provider lookup $firstArgument$argumentMarker mode alpha with detailed output"
        val currentRequest = "lookup $secondArgument$argumentMarker mode alpha"
        var now = 1_000L
        val runtime = AgentSkillRuntime(
            availableNativeToolIds = setOf(AGENT_ORCHESTRATION_TOOL_ID),
            clock = { now }
        )
        runtime.install(
            AgentSkillManifest(
                id = "parameterized-task-old",
                version = "1.0.0",
                title = "Parameterized task old",
                instructions = "Run the saved task with the current argument.",
                nativeTools = setOf(AGENT_ORCHESTRATION_TOOL_ID),
                steps = listOf(AgentSkillStep("run", AGENT_ORCHESTRATION_TOOL_ID)),
                autoInvoke = true,
                triggerExamples = listOf(savedRequest)
            )
        )

        now = 2_000L
        val latest = runtime.install(
            AgentSkillManifest(
                id = "parameterized-task-current",
                version = "1.0.0",
                title = "Parameterized task current",
                instructions = "Reuse the latest approved presentation.",
                nativeTools = setOf(AGENT_ORCHESTRATION_TOOL_ID),
                steps = listOf(AgentSkillStep("run", AGENT_ORCHESTRATION_TOOL_ID)),
                autoInvoke = true,
                triggerExamples = listOf(savedRequest)
            )
        )

        assertEquals(latest.id, matcher(runtime).match(currentRequest)?.installation?.id)
        now = 3_000L
        runtime.install(
            AgentSkillManifest(
                id = "narrow-parameterized-task",
                version = "1.0.0",
                title = "Narrow parameterized task",
                instructions = "Run a narrow task.",
                nativeTools = setOf(AGENT_ORCHESTRATION_TOOL_ID),
                steps = listOf(AgentSkillStep("run", AGENT_ORCHESTRATION_TOOL_ID)),
                autoInvoke = true,
                triggerExamples = listOf(currentRequest)
            )
        )
        assertEquals(
            latest.id,
            matcher(runtime).match(currentRequest)?.installation?.id
        )
        listOf(
            "lookup $secondArgument$argumentMarker mode",
            "lookup $secondArgument$argumentMarker mode alpha"
        ).forEach { request ->
            assertEquals(latest.id, matcher(runtime).match(request)?.installation?.id)
        }
        assertEquals(
            savedRequest.replace(firstArgument, secondArgument),
            AgentSkillRequestTransformer.transform(savedRequest, currentRequest)
        )
    }

    @Test
    fun remoteAgentRunCompilesAsOrchestrationSkill() {
        val runtime = AgentSkillRuntime(availableNativeToolIds = setOf(AGENT_ORCHESTRATION_TOOL_ID))
        val run = AgentRecordedRun(
            runId = "run-1",
            conversationId = "conversation-1",
            taskThreadId = "thread-1",
            originalRequest = "\u4f60\u5b57\u7b14\u987a\u7b14\u753b",
            normalizedIntent = "\u4f60\u5b57\u7b14\u987a\u7b14\u753b",
            finalOutputJson = "{\"text\":\"done\"}",
            status = AgentRecordedRunStatus.COMPLETED
        )

        val installed = AgentConversationSkillCompiler(runtime) { emptyList() }.install(listOf(run))

        assertEquals(setOf(AGENT_ORCHESTRATION_TOOL_ID), installed.manifest.nativeTools)
        assertEquals(AGENT_ORCHESTRATION_TOOL_ID, installed.manifest.steps.single().toolId)

        val next = AgentConversationSkillCompiler(runtime) { emptyList() }.install(listOf(run))
        assertEquals("1.1.0", next.version)

        val archive = AgentSkillPackageExporter.export(installed.manifest)
        val restoredRuntime = AgentSkillRuntime(availableNativeToolIds = setOf(AGENT_ORCHESTRATION_TOOL_ID))
        val inspection = AgentSkillPackageInstaller(restoredRuntime).inspect(archive.inputStream())
        assertTrue(inspection.integrityVerified)
        val restored = AgentSkillPackageInstaller(restoredRuntime)
            .install(archive.inputStream(), allowUnsignedLocalPackage = false)
        assertEquals(installed.id, restored.id)
    }

    @Test
    fun repeatedRunsCompileOnlyTheLatestRepresentativeWorkflow() {
        val descriptor = AgentNativeToolDescriptor(
            id = "test.echo",
            version = "1.0.0",
            title = "Echo",
            description = "Echoes a bounded value.",
            location = AgentNativeToolLocation.APPLICATION,
            inputSchema = AgentNativeJsonSchema.any(),
            outputSchema = AgentNativeJsonSchema.any(),
            risk = AgentNativeToolRisk.LOW
        )
        val runtime = AgentSkillRuntime(availableNativeToolIds = setOf(descriptor.id))
        val first = completedRun("run-1", "Do the first item", "first")
        val latest = completedRun("run-2", "Do the second item", "second")

        val manifest = AgentConversationSkillCompiler(runtime) { listOf(descriptor) }
            .compile(listOf(first, latest))

        assertEquals(1, manifest.steps.size)
        assertEquals("second", manifest.steps.single().input["value"])
    }

    @Test
    fun reviewedUpgradeBuildDoesNotInstallBeforeApproval() {
        val runtime = AgentSkillRuntime(availableNativeToolIds = setOf(AGENT_ORCHESTRATION_TOOL_ID))
        val installed = runtime.install(
            AgentSkillManifest(
                id = "reviewed-workflow",
                version = "1.0.0",
                title = "Reviewed workflow",
                instructions = "Use the original workflow.",
                nativeTools = setOf(AGENT_ORCHESTRATION_TOOL_ID),
                steps = listOf(AgentSkillStep("run", AGENT_ORCHESTRATION_TOOL_ID))
            )
        )
        val corrected = AgentRecordedRun(
            runId = "corrected-run",
            conversationId = "conversation",
            taskThreadId = "thread",
            originalRequest = "Run the workflow with the local source",
            userFeedback = listOf("Use the local source instead"),
            activeSkillId = installed.id,
            status = AgentRecordedRunStatus.COMPLETED
        )

        val proposal = AgentSkillVersionManager(runtime).buildUpgrade(installed, listOf(corrected))

        assertEquals("1.1.0", proposal.version)
        assertTrue(proposal.instructions.contains("Use the local source instead"))
        assertEquals(1, runtime.list().size)
        assertNull(runtime.list().firstOrNull { it.version == proposal.version })
    }

    private fun completedRun(id: String, request: String, value: String) = AgentRecordedRun(
        runId = id,
        conversationId = "conversation",
        taskThreadId = "thread",
        originalRequest = request,
        toolCalls = listOf(
            AgentToolCallRecord(
                id = "call-$id",
                toolName = "test.echo",
                status = AgentToolCallStatus.SUCCEEDED,
                argumentsJson = "{\"value\":\"$value\"}"
            )
        ),
        status = AgentRecordedRunStatus.COMPLETED
    )

    private fun matcher(runtime: AgentSkillRuntime) = AgentSkillMatcher(runtime)
}
