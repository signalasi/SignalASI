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

    @Test
    fun persistentMemoryCreateUpdateAndDeleteReplaceWorldEvidence() {
        val original = AgentMemoryItem(
            id = "memory-1",
            kind = AgentMemoryKind.PREFERENCE,
            value = "Prefer concise engineering answers",
            key = "response style",
            confidence = 0.82,
            timestampMillis = 1_000L
        )
        val created = GlobalPersistentContextObservationExtractor.memoryMutations(
            emptyList(),
            listOf(original),
            1_000L
        ).single()
        val firstWorld = reduce(PersonalWorldModel(), created)

        assertEquals(GlobalConversationEventType.MEMORY_CREATED, created.type)
        assertTrue(firstWorld.items.any {
            it.kind == GlobalWorldItemKind.PREFERENCE &&
                it.layer == GlobalWorldLayer.USER &&
                it.value == original.value
        })

        val edited = original.copy(
            value = "Prefer short answers with concrete verification",
            version = 2,
            timestampMillis = 2_000L
        )
        val updated = GlobalPersistentContextObservationExtractor.memoryMutations(
            listOf(original),
            listOf(edited),
            2_000L
        ).single()
        val secondWorld = reduce(firstWorld, updated)

        assertEquals(GlobalConversationEventType.MEMORY_UPDATED, updated.type)
        assertTrue(updated.retractedEventIds.contains(created.id))
        assertFalse(secondWorld.items.any { it.value == original.value })
        assertTrue(secondWorld.items.any { it.value == edited.value })

        val deleted = GlobalPersistentContextObservationExtractor.memoryMutations(
            listOf(edited),
            emptyList(),
            3_000L
        ).single()
        val finalWorld = reduce(secondWorld, deleted)

        assertEquals(GlobalConversationEventType.MEMORY_DELETED, deleted.type)
        assertFalse(finalWorld.items.any { it.value == edited.value })
    }

    @Test
    fun memoryConflictProducesTwoConflictedWorldCandidates() {
        val existing = AgentMemoryItem(
            id = "memory-a",
            kind = AgentMemoryKind.PREFERENCE,
            value = "Use dark mode",
            key = "theme",
            timestampMillis = 1_000L
        )
        val groupId = "conflict-theme"
        val conflictedExisting = existing.copy(
            status = AgentMemoryStatus.CONFLICTED,
            conflictGroupId = groupId
        )
        val competing = existing.copy(
            id = "memory-b",
            value = "Use light mode",
            version = 2,
            status = AgentMemoryStatus.CONFLICTED,
            conflictGroupId = groupId,
            timestampMillis = 2_000L
        )
        var world = reduce(
            PersonalWorldModel(),
            GlobalPersistentContextObservationExtractor.memoryMutations(
                emptyList(),
                listOf(existing),
                1_000L
            ).single()
        )

        GlobalPersistentContextObservationExtractor.memoryMutations(
            listOf(existing),
            listOf(conflictedExisting, competing),
            2_000L
        ).forEach { event -> world = reduce(world, event) }

        val conflicts = world.items.filter { it.conflictGroupId == groupId }
        assertEquals(2, conflicts.size)
        assertTrue(conflicts.all { it.status == GlobalWorldItemStatus.CONFLICTED })
    }

    @Test
    fun localOnlyKnowledgeStaysInWorldButCannotEnterSharedPromptContext() {
        val item = knowledgeItem(
            cloudAccess = AgentKnowledgeCloudAccess.DENY,
            agentAccess = AgentKnowledgeAgentAccess.LOCAL_ONLY
        )
        val imported = GlobalPersistentContextObservationExtractor.knowledgeMutations(
            emptyList(),
            listOf(item),
            1_000L
        ).single()
        val world = reduce(PersonalWorldModel(), imported)

        assertEquals(GlobalConversationEventType.KNOWLEDGE_IMPORTED, imported.type)
        assertFalse(imported.metadata.values.any { it.contains("content://private.provider") })
        assertFalse(imported.content.contains("FULL_PRIVATE_DOCUMENT_BODY"))
        assertEquals(GlobalWorldContextVisibility.LOCAL_ONLY, world.items.single().contextVisibility)
        assertEquals("", GlobalAgentContextSelector.build(world, "runtime architecture", "conversation-a"))
        val graph = GlobalTopicProjectGraph(
            nodes = listOf(
                GlobalTopicNode(
                    stableKey = "runtime-notes",
                    name = "Runtime architecture notes",
                    worldItemIds = setOf(world.items.single().id),
                    confidence = 0.9
                )
            )
        )
        assertEquals(
            "",
            GlobalAgentContextSelector.buildWithGraph(world, graph, "runtime architecture", "conversation-a")
        )
    }

    @Test
    fun knowledgeAccessChangeMakesOnlyTheApprovedSummaryShareable() {
        val local = knowledgeItem(
            cloudAccess = AgentKnowledgeCloudAccess.DENY,
            agentAccess = AgentKnowledgeAgentAccess.LOCAL_ONLY
        )
        val shared = local.copy(
            cloudAccess = AgentKnowledgeCloudAccess.SUMMARY_ONLY,
            agentAccess = AgentKnowledgeAgentAccess.ANY_PAIRED_AGENT,
            updatedAtMillis = 2_000L
        )
        val imported = GlobalPersistentContextObservationExtractor.knowledgeMutations(
            emptyList(),
            listOf(local),
            1_000L
        ).single()
        val accessChanged = GlobalPersistentContextObservationExtractor.knowledgeMutations(
            listOf(local),
            listOf(shared),
            2_000L
        ).single()
        val world = reduce(reduce(PersonalWorldModel(), imported), accessChanged)
        val prompt = GlobalAgentContextSelector.build(world, "runtime architecture", "conversation-a")

        assertEquals(GlobalConversationEventType.KNOWLEDGE_ACCESS_CHANGED, accessChanged.type)
        assertTrue(accessChanged.retractedEventIds.contains(imported.id))
        assertEquals(GlobalWorldContextVisibility.SHAREABLE, world.items.single().contextVisibility)
        assertTrue(prompt.contains("Runtime architecture notes"))
        assertTrue(prompt.contains("Verified runtime design summary"))
        assertFalse(prompt.contains("FULL_PRIVATE_DOCUMENT_BODY"))
    }

    @Test
    fun chunkedKnowledgeSourceProducesOneLifecycleEventAndDeletionRetractsIt() {
        val first = knowledgeItem().copy(id = "chunk-1", title = "Runtime guide [1/2]", chunkIndex = 0, chunkCount = 2)
        val second = knowledgeItem().copy(id = "chunk-2", title = "Runtime guide [2/2]", chunkIndex = 1, chunkCount = 2)
        val imported = GlobalPersistentContextObservationExtractor.knowledgeMutations(
            emptyList(),
            listOf(first, second),
            1_000L
        )

        assertEquals(1, imported.size)
        val withKnowledge = reduce(PersonalWorldModel(), imported.single())
        val deleted = GlobalPersistentContextObservationExtractor.knowledgeMutations(
            listOf(first, second),
            emptyList(),
            2_000L
        ).single()
        val finalWorld = reduce(withKnowledge, deleted)

        assertEquals(GlobalConversationEventType.KNOWLEDGE_DELETED, deleted.type)
        assertTrue(finalWorld.items.isEmpty())
    }

    @Test
    fun knowledgeBodyChangeProducesAnUpdateEvenWhenSummaryAndAccessStayTheSame() {
        val original = knowledgeItem()
        val changed = original.copy(
            content = "A completely different document body beyond the retained summary",
            updatedAtMillis = 2_000L
        )

        val events = GlobalPersistentContextObservationExtractor.knowledgeMutations(
            listOf(original),
            listOf(changed),
            2_000L
        )

        assertEquals(1, events.size)
        assertEquals(GlobalConversationEventType.KNOWLEDGE_UPDATED, events.single().type)
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

    private fun knowledgeItem(
        cloudAccess: AgentKnowledgeCloudAccess = AgentKnowledgeCloudAccess.DENY,
        agentAccess: AgentKnowledgeAgentAccess = AgentKnowledgeAgentAccess.LOCAL_ONLY
    ) = AgentKnowledgeItem(
        id = "knowledge-1",
        kind = AgentKnowledgeKind.DOCUMENT,
        title = "Runtime architecture notes",
        content = "FULL_PRIVATE_DOCUMENT_BODY",
        source = "content://private.provider/documents/runtime-notes",
        tags = listOf("runtime", "architecture"),
        summary = "Verified runtime design summary",
        cloudAccess = cloudAccess,
        agentAccess = agentAccess,
        updatedAtMillis = 1_000L
    )
}
