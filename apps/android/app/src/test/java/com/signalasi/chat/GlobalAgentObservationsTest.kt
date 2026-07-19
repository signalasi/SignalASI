package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalAgentObservationsTest {
    private val pipeline = GlobalUnderstandingPipeline()

    @Test
    fun userAttachmentIsBoundedCausalAndNeverExposesInlineDataOrSecrets() {
        val inlineData = "very-sensitive-binary-payload".repeat(100)
        val entry = transcriptEntry(
            role = AgentTranscriptRole.USER,
            richBlocks = listOf(
                AgentRichBlock(
                    id = "attachment-1",
                    type = AgentRichBlockType.IMAGE,
                    title = "homework.png",
                    uri = "content://private.provider/items/secret-path",
                    dataB64 = inlineData,
                    mimeType = "image/png",
                    metadata = mapOf("token" to "secret-token", "size_bytes" to "4096")
                )
            )
        )

        val events = GlobalRichObservationExtractor.extract(conversation(), entry, "transcript:root")

        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(GlobalConversationEventType.ATTACHMENT_ADDED, event.type)
        assertEquals(setOf("transcript:root"), event.causalEventIds)
        assertTrue(event.content.contains("homework.png"))
        assertFalse(event.content.contains(inlineData))
        assertFalse(event.metadata.values.any { it.contains("secret-token") || it.contains("secret-path") })
        assertEquals("content", event.metadata["resource_scheme"])
        assertTrue(event.contentRef.startsWith("encrypted://agent-transcript/"))
    }

    @Test
    fun duplicateRichBlocksProduceOneObservation() {
        val block = AgentRichBlock(
            id = "artifact-1",
            type = AgentRichBlockType.FILE,
            title = "report.pdf",
            uri = "file:///private/report.pdf",
            mimeType = "application/pdf"
        )
        val entry = transcriptEntry(
            role = AgentTranscriptRole.ASSISTANT,
            richBlocks = listOf(block, block)
        )

        val events = GlobalRichObservationExtractor.extract(conversation(), entry, "transcript:root")

        assertEquals(1, events.size)
        assertEquals(GlobalConversationEventType.ARTIFACT_CREATED, events.single().type)
    }

    @Test
    fun privateOrPausedConversationsPublishNoRichObservations() {
        val entry = transcriptEntry(
            role = AgentTranscriptRole.USER,
            richBlocks = listOf(
                AgentRichBlock(
                    id = "private-file",
                    type = AgentRichBlockType.FILE,
                    title = "private.txt",
                    uri = "content://private/file"
                )
            )
        )

        assertTrue(
            GlobalRichObservationExtractor.extract(
                conversation().copy(privateMode = true),
                entry,
                "transcript:private"
            ).isEmpty()
        )
        assertTrue(
            GlobalRichObservationExtractor.extract(
                conversation().copy(trackingPaused = true),
                entry,
                "transcript:paused"
            ).isEmpty()
        )
    }

    @Test
    fun processRowsUseTerminalToolLifecycleTypesWhileReasoningRemainsAMessage() {
        val completed = transcriptEntry(
            role = AgentTranscriptRole.PROCESS,
            text = "Codex Agent completed",
            dedupeKey = "connector-task:task-1"
        )
        val failed = completed.copy(text = "Codex Agent failed: timeout")
        val narration = completed.copy(
            text = "Inspect the current project before editing",
            dedupeKey = "pending:plan:step"
        )

        assertEquals(
            GlobalConversationEventType.TOOL_COMPLETED,
            GlobalRichObservationExtractor.transcriptEventType(completed, updated = true)
        )
        assertEquals(
            GlobalConversationEventType.TOOL_FAILED,
            GlobalRichObservationExtractor.transcriptEventType(failed, updated = true)
        )
        assertEquals(
            GlobalConversationEventType.MESSAGE_UPDATED,
            GlobalRichObservationExtractor.transcriptEventType(narration, updated = true)
        )
    }

    @Test
    fun recordedRunPublishesTaskToolsAndArtifactsWithoutRawPathsOrSecretFields() {
        val run = AgentRecordedRun(
            runId = "run-1",
            conversationId = "conversation-a",
            taskThreadId = "thread-1",
            originalRequest = "Read the phone battery and create a report",
            toolCalls = listOf(
                AgentToolCallRecord(
                    id = "tool-1",
                    toolName = "android.device.battery",
                    status = AgentToolCallStatus.SUCCEEDED,
                    resultJson = "{\"battery_level\":42,\"token\":\"do-not-copy\"}",
                    startedAtMillis = 1_100L,
                    completedAtMillis = 1_200L
                )
            ),
            artifacts = listOf(
                AgentArtifactReference(
                    id = "artifact-1",
                    uri = "file:///data/user/0/private/report.json",
                    name = "report.json",
                    mimeType = "application/json",
                    metadataJson = "{\"size_bytes\":128,\"path\":\"/data/user/0/private/report.json\"}",
                    createdAtMillis = 1_250L
                )
            ),
            status = AgentRecordedRunStatus.COMPLETED,
            createdAtMillis = 1_000L,
            completedAtMillis = 1_300L
        )

        val events = GlobalRecordedRunObservationExtractor.completed(run, "Device status")

        assertEquals(3, events.size)
        val tool = events.first { it.type == GlobalConversationEventType.TOOL_COMPLETED }
        val artifact = events.first { it.type == GlobalConversationEventType.ARTIFACT_CREATED }
        assertTrue(tool.content.contains("battery_level=42"))
        assertFalse(tool.content.contains("do-not-copy"))
        assertFalse(artifact.metadata.values.any { it.contains("/data/user/0/private") })
        assertTrue(artifact.contentRef.startsWith("encrypted://agent-runs/"))
        assertEquals("true", tool.metadata["verified"])
    }

    @Test
    fun richObservationsBecomeWorldEvidenceAndToolTransitionsShareOneState() {
        val attachment = GlobalConversationEvent(
            id = "attachment-event",
            type = GlobalConversationEventType.ATTACHMENT_ADDED,
            conversationId = "conversation-a",
            actor = GlobalConversationActor.USER,
            content = "Attached file: requirements.pdf",
            conversationTitle = "SignalASI planning"
        )
        val started = GlobalConversationEvent(
            id = "tool-started",
            type = GlobalConversationEventType.TOOL_STARTED,
            conversationId = "conversation-a",
            actor = GlobalConversationActor.TOOL,
            content = "document parser running",
            conversationTitle = "SignalASI planning",
            metadata = mapOf("tool_key" to "parse-1")
        )
        val completed = started.copy(
            id = "tool-completed",
            type = GlobalConversationEventType.TOOL_COMPLETED,
            content = "document parser completed",
            metadata = mapOf("tool_key" to "parse-1", "verified" to "true")
        )

        val afterAttachment = reduce(PersonalWorldModel(), attachment)
        val afterStarted = reduce(afterAttachment, started)
        val afterCompleted = reduce(afterStarted, completed)

        assertTrue(afterCompleted.items.any {
            it.kind == GlobalWorldItemKind.FACT && it.value.contains("requirements.pdf")
        })
        val toolStates = afterCompleted.items.filter {
            it.kind == GlobalWorldItemKind.STATE && it.value.contains("document parser")
        }
        assertEquals(1, toolStates.size)
        assertEquals(GlobalWorldItemStatus.COMPLETED, toolStates.single().status)
        assertTrue(afterCompleted.items.any {
            it.kind == GlobalWorldItemKind.FACT && it.value.contains("parser completed")
        })
    }

    @Test
    fun deletingTheRootMessageRetractsDerivedArtifactEvidence() {
        val artifact = GlobalConversationEvent(
            id = "artifact-event",
            type = GlobalConversationEventType.ARTIFACT_CREATED,
            conversationId = "conversation-a",
            actor = GlobalConversationActor.ASSISTANT,
            content = "Created file: result.csv",
            conversationTitle = "Data cleanup",
            causalEventIds = setOf("transcript:root")
        )
        val withArtifact = reduce(PersonalWorldModel(), artifact)
        val deletion = GlobalConversationEvent(
            id = "delete-root",
            type = GlobalConversationEventType.MESSAGE_DELETED,
            conversationId = "conversation-a",
            actor = GlobalConversationActor.SYSTEM,
            metadata = mapOf("deleted_event_id" to "transcript:root"),
            retractedEventIds = setOf("transcript:root")
        )

        val afterDeletion = reduce(withArtifact, deletion)

        assertTrue(withArtifact.items.any { it.value.contains("result.csv") })
        assertFalse(afterDeletion.items.any { it.value.contains("result.csv") })
        assertTrue("transcript:root" in afterDeletion.retractedEventIds)
    }

    @Test
    fun failedToolCreatesDurableRiskAndCompletedRealtimeState() {
        val failure = GlobalConversationEvent(
            id = "tool-failed",
            type = GlobalConversationEventType.TOOL_FAILED,
            conversationId = "conversation-a",
            actor = GlobalConversationActor.TOOL,
            content = "Package installation failed: signature mismatch",
            conversationTitle = "Runtime setup",
            metadata = mapOf("tool_key" to "install-package")
        )

        val world = reduce(PersonalWorldModel(), failure)

        assertTrue(world.items.any {
            it.kind == GlobalWorldItemKind.RISK && it.layer == GlobalWorldLayer.TOPIC
        })
        assertTrue(world.items.any {
            it.kind == GlobalWorldItemKind.STATE && it.status == GlobalWorldItemStatus.COMPLETED
        })
    }

    @Test
    fun excludingAConversationRemovesItsWorldEvidenceButKeepsSharedEvidence() {
        val onlyPrivate = GlobalWorldItem(
            stableKey = "private-only",
            kind = GlobalWorldItemKind.FACT,
            layer = GlobalWorldLayer.TOPIC,
            topic = "Private topic",
            value = "Private fact",
            confidence = 0.9,
            conversationIds = setOf("conversation-a")
        )
        val shared = onlyPrivate.copy(
            id = "shared",
            stableKey = "shared",
            value = "Shared fact",
            conversationIds = setOf("conversation-a", "conversation-b")
        )
        val event = GlobalConversationEvent(
            id = "exclude-conversation-a",
            type = GlobalConversationEventType.CONVERSATION_UPDATED,
            conversationId = "conversation-a",
            actor = GlobalConversationActor.SYSTEM,
            metadata = mapOf("global_visibility" to "excluded")
        )

        val result = reduce(PersonalWorldModel(items = listOf(onlyPrivate, shared)), event)

        assertFalse(result.items.any { it.stableKey == "private-only" })
        val retained = result.items.single { it.stableKey == "shared" }
        assertEquals(setOf("conversation-b"), retained.conversationIds)
    }

    private fun reduce(world: PersonalWorldModel, event: GlobalConversationEvent): PersonalWorldModel =
        GlobalWorldModelReducer.reduce(world, event, pipeline.understand(event, world)).world

    private fun conversation() = AgentConversation(
        id = "conversation-a",
        title = "SignalASI planning",
        createdAt = 1_000L,
        updatedAt = 1_000L
    )

    private fun transcriptEntry(
        role: AgentTranscriptRole,
        text: String = "Attachment",
        dedupeKey: String = "",
        richBlocks: List<AgentRichBlock> = emptyList()
    ) = AgentTranscriptEntry(
        id = "entry-1",
        role = role,
        text = text,
        timestampMillis = 1_000L,
        dedupeKey = dedupeKey,
        conversationId = "conversation-a",
        turnId = "turn-1",
        taskId = "task-1",
        richOutputJson = AgentRichContentCodec.encode(richBlocks)
    )
}
