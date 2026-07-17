package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLearningEngineTest {
    @Test
    fun taskFamilyRemovesIncidentalValuesButKeepsIntent() {
        val left = AgentLearningAnalyzer.taskFamily("Summarize C:\\Work\\private\\alpha.pdf for 12 people")
        val right = AgentLearningAnalyzer.taskFamily("Summarize C:\\Temp\\beta.pdf for 28 people")

        assertEquals(left, right)
        assertFalse(left.contains("private"))
        assertFalse(left.contains("12"))
        assertTrue(AgentLearningAnalyzer.sameTaskFamily(left, right))
    }

    @Test
    fun explicitPreferenceIsLearnableButSecretsAreNot() {
        assertEquals("concise answers", AgentLearningAnalyzer.explicitPreference("I prefer concise answers"))
        assertEquals(null, AgentLearningAnalyzer.explicitPreference("Remember that api_key=secret-value"))
        assertTrue(AgentLearningAnalyzer.containsSensitiveData("Authorization: Bearer abc"))
    }

    @Test
    fun memoryMetadataSupportsConfidenceEvidenceAndExpiration() {
        val now = 10_000L
        val memory = AgentMemoryItem(
            kind = AgentMemoryKind.PREFERENCE,
            value = "Use concise answers",
            confidence = 0.9,
            evidenceCount = 4,
            autoLearned = true,
            expiresAtMillis = now + 1
        )

        assertFalse(memory.isExpired(now))
        assertTrue(memory.isExpired(now + 1))
        assertEquals(4, memory.evidenceCount)
    }

    @Test
    fun compilerRedactsTaskSpecificValuesFromSkillMetadata() {
        val runtime = AgentSkillRuntime(availableNativeToolIds = setOf(AGENT_ORCHESTRATION_TOOL_ID))
        val run = AgentRecordedRun(
            runId = "run",
            conversationId = "conversation",
            taskThreadId = "thread",
            originalRequest = "Summarize C:\\Users\\person\\private.pdf from https://example.com/private",
            status = AgentRecordedRunStatus.COMPLETED
        )

        val manifest = AgentConversationSkillCompiler(runtime) { emptyList() }.compile(listOf(run))
        val metadata = (manifest.instructions + manifest.triggerExamples.joinToString() + manifest.tests.joinToString())

        assertFalse(metadata.contains("person"))
        assertFalse(metadata.contains("example.com"))
        assertTrue(metadata.contains("[PATH]") || metadata.contains("item"))
    }

    @Test
    fun runtimeStatusRequiresBackendAndMatchingPack() {
        val readyPack = AgentRuntimePackStatus(
            id = "python-uv",
            state = AgentRuntimePackState.READY
        )
        val status = AgentOnDeviceRuntimeStatus(
            backend = AgentOnDeviceRuntimeBackend.QEMU_TCG,
            backendReady = true,
            reason = "ready",
            architecture = "arm64-v8a",
            enginePath = "/native/libsignalasi_qemu.so",
            avfAdvertised = false,
            packs = listOf(readyPack)
        )

        assertTrue(status.languageReady(AgentRuntimeLanguage.PYTHON))
        assertFalse(status.languageReady(AgentRuntimeLanguage.RUST))
    }

    @Test
    fun automaticLearningRejectsUnboundTaskValues() {
        assertTrue(AgentLearningAnalyzer.hasUnboundTaskValue(mapOf("path" to "/private/task.pdf")))
        assertFalse(AgentLearningAnalyzer.hasUnboundTaskValue(
            mapOf("request" to "{{parameters.request}}", "recursive" to true, "limit" to 10)
        ))
    }

    @Test
    fun runtimePackSigningPayloadIsDeterministic() {
        val left = runtimeManifest(capabilities = listOf("python", "shell"), dependencies = listOf("python-uv", "linux-base"))
        val right = runtimeManifest(capabilities = listOf("shell", "python"), dependencies = listOf("linux-base", "python-uv"))

        assertEquals(String(left.signingPayload()), String(right.signingPayload()))
    }

    private fun runtimeManifest(
        capabilities: List<String>,
        dependencies: List<String>
    ) = AgentRuntimePackManifest(
        id = "python-uv",
        version = "1.0.0",
        architecture = "arm64-v8a",
        imageFile = "python.img",
        imageSha256 = "a".repeat(64),
        capabilities = capabilities,
        dependencies = dependencies,
        installedSizeBytes = 1_024,
        license = "PSF-2.0",
        signatureKeyId = "b".repeat(64),
        signature = "signature",
        archiveSizeBytes = 512
    )
}
