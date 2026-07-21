package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

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
    fun correctionFeedbackRequiresActionableNonSecretGuidance() {
        assertEquals(
            "No, use the local tool instead",
            AgentLearningAnalyzer.correctionFeedback("No, use the local tool instead")
        )
        assertEquals(null, AgentLearningAnalyzer.correctionFeedback("No"))
        assertEquals(null, AgentLearningAnalyzer.correctionFeedback("Use token=secret instead"))
    }

    @Test
    fun repeatedFailureLearningRequiresTwoMatchingFailures() {
        val first = failedRun("failure-1", "Summarize C:\\Work\\alpha.pdf")
        val second = failedRun("failure-2", "Summarize C:\\Temp\\beta.pdf")
        val unrelated = failedRun("failure-3", "Turn on the flashlight")

        assertEquals(null, AgentLearningAnalyzer.repeatedFailureFamily(first, listOf(first)))
        assertEquals(
            AgentLearningAnalyzer.taskFamily(second.originalRequest),
            AgentLearningAnalyzer.repeatedFailureFamily(second, listOf(first, second, unrelated))
        )
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
            state = AgentRuntimePackState.READY,
            manifest = runtimeManifest(
                capabilities = listOf("python.execute", "uv.sync"),
                dependencies = listOf("linux-base")
            )
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
        assertTrue(status.languageReady(AgentRuntimeLanguage.UV))
        assertFalse(status.languageReady(AgentRuntimeLanguage.RUST))
    }

    @Test
    fun runtimeStatusRejectsPackWithoutTheRequestedCapability() {
        val status = AgentOnDeviceRuntimeStatus(
            backend = AgentOnDeviceRuntimeBackend.QEMU_TCG,
            backendReady = true,
            reason = "ready",
            architecture = "arm64-v8a",
            enginePath = "/native/libsignalasi_qemu.so",
            avfAdvertised = false,
            packs = listOf(
                AgentRuntimePackStatus(
                    id = "python-uv",
                    state = AgentRuntimePackState.READY,
                    manifest = runtimeManifest(
                        capabilities = listOf("python.execute"),
                        dependencies = listOf("linux-base")
                    )
                )
            )
        )

        assertTrue(status.languageReady(AgentRuntimeLanguage.PYTHON))
        assertFalse(status.languageReady(AgentRuntimeLanguage.UV))
    }

    @Test
    fun automaticLearningRejectsUnboundTaskValues() {
        assertTrue(AgentLearningAnalyzer.hasUnboundTaskValue(mapOf("path" to "/private/task.pdf")))
        assertFalse(AgentLearningAnalyzer.hasUnboundTaskValue(
            mapOf("request" to "{{parameters.request}}", "recursive" to true, "limit" to 10)
        ))
    }

    @Test
    fun runtimeLearningRequiresACompletedHashedExecutionReceipt() {
        val missingReceipt = AgentToolCallRecord(
            id = "runtime-missing",
            toolName = AgentOnDeviceRuntimeTools.EXECUTE,
            status = AgentToolCallStatus.SUCCEEDED,
            resultJson = "{\"exit_code\":0}"
        )
        val trustedReceipt = missingReceipt.copy(
            id = "runtime-trusted",
            resultJson = JSONObject()
                .put("execution_receipt", JSONObject()
                    .put("request_id", "request-1")
                    .put("status", "completed")
                    .put("exit_code", 0)
                    .put("source_sha256", "a".repeat(64))
                    .put("stdout_sha256", "b".repeat(64))
                    .put("stderr_sha256", "c".repeat(64))
                    .put("created_at_millis", 1_000L)
                    .put("completed_at_millis", 1_100L))
                .toString()
        )

        assertFalse(AgentLearningAnalyzer.hasTrustedExecutionEvidence(missingReceipt))
        assertTrue(AgentLearningAnalyzer.hasTrustedExecutionEvidence(trustedReceipt))
        assertFalse(AgentLearningAnalyzer.hasTrustedExecutionEvidence(
            trustedReceipt.copy(resultJson = JSONObject(trustedReceipt.resultJson)
                .apply { getJSONObject("execution_receipt").put("exit_code", 7) }
                .toString())
        ))
    }

    @Test
    fun runtimeReceiptEvidenceDoesNotExposeHostArtifactPaths() {
        val evidence = AgentRuntimeExecutionReceipt(
            requestId = "request-1",
            workspaceId = "workspace",
            language = AgentRuntimeLanguage.PYTHON,
            sourceSha256 = "a".repeat(64),
            packVersions = mapOf("python-uv" to "1.0.0"),
            networkEnabled = false,
            allowedNetworkDomains = emptyList(),
            limits = AgentRuntimeResourceLimits(),
            status = AgentRuntimeReceiptStatus.COMPLETED,
            exitCode = 0,
            stdoutSha256 = "b".repeat(64),
            stderrSha256 = "c".repeat(64),
            artifacts = listOf(mapOf(
                "relative_path" to "result.json",
                "size_bytes" to 42L,
                "sha256" to "d".repeat(64),
                "host_path" to "C:/private/result.json"
            )),
            createdAtMillis = 1_000L,
            completedAtMillis = 1_100L
        ).toEvidenceMap()

        assertFalse(AgentNativeJsonCodec.stringify(evidence).contains("host_path"))
        assertFalse(AgentNativeJsonCodec.stringify(evidence).contains("C:/private"))
    }

    @Test
    fun runtimeToolTreatsNonzeroExitAsFailureWithoutDiscardingOutput() {
        val successful = AgentOnDeviceRuntimeTools.runtimeExecutionResult(
            AgentRuntimeExecutionResponse(0, "done", "", 10L)
        )
        val failed = AgentOnDeviceRuntimeTools.runtimeExecutionResult(
            AgentRuntimeExecutionResponse(7, "partial", "compile failed", 20L)
        )

        assertTrue(successful.isSuccess)
        assertFalse(failed.isSuccess)
        assertEquals(7, failed.output["exit_code"])
        assertEquals("partial", failed.output["stdout"])
        assertEquals("on_device_runtime_nonzero_exit", failed.error?.code)
    }

    @Test
    fun runtimePackSigningPayloadIsDeterministic() {
        val left = runtimeManifest(capabilities = listOf("python", "shell"), dependencies = listOf("python-uv", "linux-base"))
        val right = runtimeManifest(capabilities = listOf("shell", "python"), dependencies = listOf("linux-base", "python-uv"))

        assertEquals(String(left.signingPayload()), String(right.signingPayload()))
    }

    @Test
    fun selfModelLearnsEveryTerminalRunExactlyOnce() {
        val run = completedRun("run-1", "Debug C:\\Work\\alpha.py", "codex")

        val first = AgentSelfModelReducer.observeRun(AgentSelfModel(), run)
        val duplicate = AgentSelfModelReducer.observeRun(first.after, run)

        assertTrue(first.changed)
        assertFalse(duplicate.changed)
        assertEquals(1, first.after.totalRuns)
        assertEquals(1, first.after.successfulRuns)
        assertEquals("codex", first.belief?.resourceKey)
    }

    @Test
    fun selfModelCalibratesRoutingFromRepeatedEvidenceWithoutOverridingSafety() {
        var model = AgentSelfModel()
        repeat(3) { index ->
            model = AgentSelfModelReducer.observeRun(
                model,
                completedRun("success-$index", "Debug C:\\Work\\task-$index.py", "codex")
            ).after
        }
        var failedModel = AgentSelfModel()
        repeat(2) { index ->
            failedModel = AgentSelfModelReducer.observeRun(
                failedModel,
                failedRun("failure-$index", "Debug C:\\Work\\task-$index.py").copy(executionResourceId = "hermes")
            ).after
        }
        val requirements = AgentTaskRequirementAnalyzer.analyze("Debug C:\\Temp\\next.py")
        val positive = AgentSelfModelReducer.calibration(model, "Debug C:\\Temp\\next.py", "codex", requirements)
        val negative = AgentSelfModelReducer.calibration(failedModel, "Debug C:\\Temp\\next.py", "hermes", requirements)

        assertTrue(positive.scoreAdjustment > 0)
        assertTrue(negative.scoreAdjustment < 0)
        assertTrue(model.strengths().isNotEmpty())
        assertTrue(failedModel.limitations().isNotEmpty())
        assertTrue(positive.scoreAdjustment <= 120)
        assertTrue(negative.scoreAdjustment >= -180)
    }

    @Test
    fun selfModelStoresNoSensitiveRequestOrUnknownResourceIdentifier() {
        val request = "Use token=secret-value on private-device-name"
        val model = AgentSelfModelReducer.observeRun(
            AgentSelfModel(),
            completedRun("sensitive", request, "private-device-name")
        ).after
        val encoded = AgentSelfModelCodec.encode(model).toString()

        assertFalse(encoded.contains("secret-value"))
        assertFalse(encoded.contains("private-device-name"))
        assertTrue(model.beliefs.single().taskFamily.startsWith("capabilities:"))
        assertTrue(model.beliefs.single().resourceKey.startsWith("resource:"))
    }

    @Test
    fun selfModelTreatsUserCorrectionAsIdempotentCalibrationEvidence() {
        val run = completedRun("corrected", "Summarize C:\\Work\\alpha.pdf", "codex")
        val observed = AgentSelfModelReducer.observeRun(AgentSelfModel(), run).after
        val first = AgentSelfModelReducer.observeFeedback(observed, run, "No, use the local source instead")
        val duplicate = AgentSelfModelReducer.observeFeedback(first.after, run, "No, use the local source instead")

        assertTrue(first.changed)
        assertFalse(duplicate.changed)
        assertEquals(1, first.belief?.correctionCount)
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

    private fun failedRun(id: String, request: String) = AgentRecordedRun(
        runId = id,
        conversationId = "conversation",
        taskThreadId = "thread",
        originalRequest = request,
        status = AgentRecordedRunStatus.FAILED
    )

    private fun completedRun(id: String, request: String, resourceId: String) = AgentRecordedRun(
        runId = id,
        conversationId = "conversation",
        taskThreadId = "thread",
        originalRequest = request,
        executionResourceId = resourceId,
        status = AgentRecordedRunStatus.COMPLETED,
        createdAtMillis = 1_000L,
        completedAtMillis = 2_000L
    )
}
