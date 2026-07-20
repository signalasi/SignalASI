package com.signalasi.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalAutonomousToolsTest {
    @Test
    fun `catalog selects a phone battery tool for a Chinese goal`() {
        val selected = GlobalAutonomousToolCatalogPolicy.select(
            descriptors = listOf(
                descriptor(
                    id = "signalasi.hardware.battery.status",
                    title = "Battery status",
                    description = "Read battery level and charging state",
                    capabilities = setOf("battery.read")
                ),
                descriptor(
                    id = "signalasi.hardware.flashlight.set",
                    title = "Flashlight",
                    description = "Change the flashlight state",
                    capabilities = setOf("flashlight.write")
                )
            ),
            goal = "\u67e5\u770b\u624b\u673a\u7535\u91cf"
        )

        assertEquals(listOf("signalasi.hardware.battery.status"), selected.map { it.id })
    }

    @Test
    fun `catalog excludes unavailable and blocked tools`() {
        val selected = GlobalAutonomousToolCatalogPolicy.select(
            descriptors = listOf(
                descriptor(
                    id = "signalasi.web.search",
                    title = "Web search",
                    description = "Search the public web",
                    capabilities = setOf("web.search")
                ),
                descriptor(
                    id = "signalasi.web.unavailable",
                    title = "Unavailable web search",
                    description = "Search the public web",
                    capabilities = setOf("web.search"),
                    availability = AgentNativeToolAvailability(
                        AgentNativeToolAvailabilityStatus.UNAVAILABLE,
                        "Offline"
                    )
                ),
                descriptor(
                    id = "signalasi.web.blocked",
                    title = "Blocked web search",
                    description = "Search the public web",
                    capabilities = setOf("web.search"),
                    risk = AgentNativeToolRisk.BLOCKED
                )
            ),
            goal = "Search the web for current news"
        )

        assertEquals(listOf("signalasi.web.search"), selected.map { it.id })
    }

    @Test
    fun `tool prompt exposes exact schema while preserving host authority`() {
        val tool = descriptor(
            id = "signalasi.web.search",
            title = "Web search",
            description = "Search the public web",
            capabilities = setOf("web.search"),
            inputSchema = AgentNativeJsonSchema.objectSchema(
                properties = mapOf("query" to AgentNativeJsonSchema.string(minLength = 1)),
                required = setOf("query"),
                additionalProperties = false
            )
        )

        val prompt = GlobalAutonomousToolCatalogPolicy.promptBlock(listOf(tool))

        assertTrue(prompt.contains("id=signalasi.web.search"))
        assertTrue(prompt.contains("input_schema="))
        assertTrue(prompt.contains("\"required\":[\"query\"]"))
        assertTrue(prompt.contains("Android host independently validates"))
    }

    @Test
    fun `cognition parser preserves a complete structured tool proposal`() {
        val parsed = GlobalModelUnderstandingParser.parse(
            """{
              "topic":"Device health",
              "actions":[{
                "kind":"INVOKE_TOOL",
                "goal":"Read the phone battery",
                "tool_id":"signalasi.hardware.battery.status",
                "tool_input":{},
                "external_effect":true
              }],
              "confidence":0.9
            }"""
        )

        val action = requireNotNull(parsed).actions.single()
        assertEquals(GlobalAutonomousActionKind.INVOKE_TOOL, action.kind)
        assertEquals("signalasi.hardware.battery.status", action.toolId)
        assertEquals(0, JSONObject(action.toolInputJson).length())
    }

    @Test
    fun `cognition parser rejects an incomplete tool proposal`() {
        val parsed = GlobalModelUnderstandingParser.parse(
            """{
              "topic":"Device health",
              "actions":[{
                "kind":"INVOKE_TOOL",
                "goal":"Read the phone battery",
                "tool_id":"signalasi.hardware.battery.status"
              }],
              "confidence":0.9
            }"""
        )

        assertTrue(requireNotNull(parsed).actions.isEmpty())
    }

    @Test
    fun `planner never trusts model external effect flags for tool confirmation`() {
        val source = event("Read the phone battery")
        val task = GlobalCognitionTask(
            sourceEvent = source,
            baselineUnderstanding = understanding(source),
            status = GlobalCognitionTaskStatus.COMPLETED,
            result = GlobalModelUnderstanding(
                topic = "Device health",
                actions = listOf(GlobalAutonomousAction(
                    kind = GlobalAutonomousActionKind.INVOKE_TOOL,
                    goal = "Read the phone battery",
                    toolId = "signalasi.hardware.battery.status",
                    toolInputJson = "{}",
                    externalEffect = true,
                    confirmationGranted = true,
                    status = GlobalAutonomousActionStatus.WAITING_CONFIRMATION
                ))
            )
        )

        val action = requireNotNull(GlobalAutonomousRunPlanner.plan(task)).actions.single()
        assertEquals(GlobalAutonomousActionStatus.PENDING, action.status)
        assertFalse(action.confirmationGranted)
    }

    @Test
    fun `replan keeps distinct tool inputs and leaves host to decide confirmation`() {
        val decision = requireNotNull(GlobalRunReplanParser.parse(
            """{
              "goal_state":"ACTIVE",
              "summary":"Check both locations",
              "actions":[
                {
                  "kind":"INVOKE_TOOL",
                  "goal":"Read forecast",
                  "tool_id":"signalasi.web.search",
                  "tool_input":{"query":"Shanghai weather"}
                },
                {
                  "kind":"INVOKE_TOOL",
                  "goal":"Read forecast",
                  "tool_id":"signalasi.web.search",
                  "tool_input":{"query":"Beijing weather"}
                }
              ]
            }"""
        ))
        val run = GlobalAutonomousRun(
            sourceCognitionTaskId = "cognition-1",
            sourceEventId = "event-1",
            sourceConversationId = "conversation-1",
            topic = "Weather",
            goal = "Compare forecasts",
            actions = emptyList(),
            status = GlobalAutonomousRunStatus.REPLANNING
        )

        val updated = GlobalAutonomousReplanPolicy.applyDecision(run, decision, 1_000L)

        assertEquals(2, updated.actions.size)
        assertEquals(2, updated.actions.map { it.toolInputJson }.toSet().size)
        assertTrue(updated.actions.all { it.status == GlobalAutonomousActionStatus.PENDING })
        assertTrue(updated.actions.none { it.confirmationGranted })
    }

    @Test
    fun `native receipt satisfies the tool verification contract`() {
        val action = GlobalAutonomousAction(
            kind = GlobalAutonomousActionKind.INVOKE_TOOL,
            goal = "Read battery",
            toolId = "signalasi.hardware.battery.status",
            toolInputJson = "{}"
        )
        val contract = GlobalActionVerificationPolicy.defaultContract(action)
        val supported = GlobalActionVerificationPolicy.evaluate(
            contract,
            listOf(GlobalActionEvidence(
                kind = GlobalActionEvidenceKind.NATIVE_TOOL_RECEIPT,
                summary = "Battery level 80",
                confidence = 0.82,
                verified = false
            ))
        )
        val verified = GlobalActionVerificationPolicy.evaluate(
            contract,
            listOf(GlobalActionEvidence(
                kind = GlobalActionEvidenceKind.NATIVE_TOOL_RECEIPT,
                summary = "Battery level 80",
                confidence = 1.0,
                verified = true
            ))
        )

        assertEquals(GlobalActionVerificationStatus.SUPPORTED, supported)
        assertEquals(GlobalActionVerificationStatus.VERIFIED, verified)
    }

    private fun descriptor(
        id: String,
        title: String,
        description: String,
        capabilities: Set<String>,
        risk: AgentNativeToolRisk = AgentNativeToolRisk.LOW,
        availability: AgentNativeToolAvailability = AgentNativeToolAvailability.AVAILABLE,
        inputSchema: AgentNativeJsonSchema = AgentNativeJsonSchema.objectSchema(
            additionalProperties = false
        )
    ) = AgentNativeToolDescriptor(
        id = id,
        version = "1.0.0",
        title = title,
        description = description,
        location = AgentNativeToolLocation.PHONE,
        inputSchema = inputSchema,
        outputSchema = AgentNativeJsonSchema.objectSchema(),
        risk = risk,
        capabilities = capabilities,
        availability = availability
    )

    private fun event(content: String) = GlobalConversationEvent(
        id = "event-${content.hashCode()}",
        type = GlobalConversationEventType.MESSAGE_CREATED,
        conversationId = "conversation-a",
        actor = GlobalConversationActor.USER,
        content = content,
        conversationTitle = "SignalASI"
    )

    private fun understanding(event: GlobalConversationEvent) = GlobalUnderstanding(
        eventId = event.id,
        topic = "Device health",
        intent = "inspect",
        complexity = 0.8
    )
}
