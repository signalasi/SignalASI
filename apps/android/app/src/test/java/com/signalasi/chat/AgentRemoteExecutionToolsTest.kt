package com.signalasi.chat

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentRemoteExecutionToolsTest {
    @Test
    fun registryDefinesOnlyEncryptedTrustedDesktopTools() {
        val definitions = AgentRemoteExecutionTools.registry.definitions()

        assertEquals(
            setOf(
                AgentRemoteExecutionTool.SHELL.id,
                AgentRemoteExecutionTool.PYTHON.id,
                AgentRemoteExecutionTool.UV.id,
                AgentRemoteExecutionTool.FFMPEG.id
            ),
            definitions.map { it.id }.toSet()
        )
        assertTrue(definitions.all { it.location == "trusted_paired_desktop" })
        assertTrue(definitions.all { it.transport == "signalasi-link-encrypted" })
        assertTrue(definitions.all { it.supportsCancellation })
        assertTrue(definitions.none { it.localAndroidExecution })
        assertEquals(false, objectAt(definitions.first().toCatalogPayload(), "local_android_execution"))
    }

    @Test
    fun createsEncryptedLinkReadyRequestWithBoundedContract() {
        val request = request(
            AgentRemotePythonSpec(
                script = "print('ok')",
                arguments = listOf("--quiet"),
                cwd = "repo/tools",
                environment = mapOf("PYTHONUTF8" to "1"),
                timeoutMillis = 12_000,
                artifactPaths = listOf("outputs/report.json")
            )
        )

        val envelope = request.toEncryptedLinkPayload()
        val payload = objectAt(envelope, "payload") as Map<*, *>
        val target = objectAt(envelope, "target") as Map<*, *>

        assertEquals("signalasi.remote-execution", envelope["protocol"])
        assertEquals("agent_remote_execution_request", envelope["type"])
        assertEquals(true, envelope["encrypted_link_required"])
        assertEquals("desktop-1", target["desktop_id"])
        assertEquals(AgentRemoteExecutionTool.PYTHON.id, payload["tool_id"])
        assertEquals("trusted_paired_desktop", payload["execution_location"])
        assertEquals("repo/tools", payload["cwd"])
        assertEquals("print('ok')", payload["script"])
        assertEquals(12_000L, payload["timeout_ms"])
        assertEquals(request.cancellationId, payload["cancellation_id"])
    }

    @Test
    fun rejectsAbsoluteAndTraversalWorkspacePaths() {
        val paths = listOf(
            "/tmp/project",
            "C:\\Users\\agent\\project",
            "\\\\server\\share",
            "../project",
            "safe/../../escape",
            "file://workspace/project"
        )

        paths.forEach { cwd ->
            val result = AgentRemoteExecutionTools.registry.validate(
                request(AgentRemotePythonSpec(script = "print(1)", cwd = cwd))
            )
            assertFalse("Expected cwd rejection for $cwd", result.isValid)
            assertTrue(
                result.issues.any {
                    it.code in setOf("absolute_workspace_path", "workspace_traversal")
                }
            )
        }
    }

    @Test
    fun rejectsShellChainingUnlessShellScriptModeIsExplicitlyTyped() {
        val direct = request(
            AgentRemoteShellSpec(command = listOf("echo", "first && echo second"))
        )
        val implicitShell = request(
            AgentRemoteShellSpec(command = listOf("powershell.exe", "-Command", "Get-Date"))
        )
        val explicit = request(
            AgentRemoteShellSpec(
                script = "echo first && echo second",
                mode = AgentRemoteShellMode.SHELL_SCRIPT,
                dialect = AgentRemoteShellDialect.POSIX_SH
            )
        )

        assertTrue(
            AgentRemoteExecutionTools.registry.validate(direct).issues.any {
                it.code == "shell_chaining_not_typed"
            }
        )
        assertTrue(
            AgentRemoteExecutionTools.registry.validate(implicitShell).issues.any {
                it.code == "implicit_shell"
            }
        )
        assertTrue(AgentRemoteExecutionTools.registry.validate(explicit).isValid)
    }

    @Test
    fun rejectsSecretAndNonAllowlistedEnvironmentNames() {
        val secret = request(
            AgentRemotePythonSpec(
                script = "print(1)",
                environment = mapOf("OPENAI_API_KEY" to "not-a-real-key")
            )
        )
        val unknown = request(
            AgentRemotePythonSpec(
                script = "print(1)",
                environment = mapOf("SOME_FLAG" to "1")
            )
        )

        assertTrue(
            AgentRemoteExecutionTools.registry.validate(secret).issues.any {
                it.code == "secret_environment_name"
            }
        )
        assertTrue(
            AgentRemoteExecutionTools.registry.validate(unknown).issues.any {
                it.code == "environment_not_allowed"
            }
        )
    }

    @Test
    fun rejectsOversizedPythonAndUvScripts() {
        val oversized = "x".repeat(64 * 1024 + 1)

        listOf<AgentRemoteExecutionSpec>(
            AgentRemotePythonSpec(script = oversized),
            AgentRemoteUvSpec(script = oversized)
        ).forEach { spec ->
            val result = AgentRemoteExecutionTools.registry.validate(request(spec))
            assertTrue(result.issues.any { it.code == "script_size_limit" })
            assertThrows(AgentRemoteExecutionContractException::class.java) {
                request(spec).toEncryptedLinkPayload()
            }
        }
    }

    @Test
    fun rejectsUntrustedOrUnpairedTargetBeforeDispatcher() {
        val calls = AtomicInteger()
        val dispatcher = RecordingDispatcher(calls)
        val untrusted = trustedTarget().copy(trusted = false)
        val unpaired = trustedTarget().copy(paired = false)

        val first = AgentRemoteExecutionTools.registry.dispatch(
            request(AgentRemotePythonSpec("print(1)"), untrusted),
            dispatcher
        )
        val second = AgentRemoteExecutionTools.registry.dispatch(
            request(AgentRemotePythonSpec("print(1)"), unpaired),
            dispatcher
        )

        assertEquals(0, calls.get())
        assertEquals("rejected", objectAt(first, "payload").let { objectAt(it, "status") })
        assertEquals("rejected", objectAt(second, "payload").let { objectAt(it, "status") })
        assertEquals(
            "request_rejected",
            objectAt(objectAt(first, "payload"), "error")
                .let { objectAt(it, "code") }
        )
    }

    @Test
    fun enforcesTimeoutOutputAndArtifactLimits() {
        val request = request(
            AgentRemotePythonSpec(
                script = "print(1)",
                timeoutMillis = 16 * 60_000L,
                limits = AgentRemoteExecutionLimits(
                    maxStdoutBytes = 4,
                    maxStderrBytes = 4,
                    maxArtifactCount = 1,
                    maxArtifactBytes = 5,
                    maxTotalArtifactBytes = 5
                )
            )
        )
        val requestValidation = AgentRemoteExecutionTools.registry.validate(request)
        assertTrue(requestValidation.issues.any { it.code == "timeout_limit" })

        val boundedRequest = request.copy(
            spec = (request.spec as AgentRemotePythonSpec).copy(timeoutMillis = 1_000)
        )
        val response = successfulResponse(boundedRequest).copy(
            stdout = "12345",
            artifacts = listOf(
                AgentRemoteExecutionArtifact(
                    artifactId = "artifact-1",
                    relativePath = "outputs/large.bin",
                    mimeType = "application/octet-stream",
                    sizeBytes = 6,
                    sha256 = "a".repeat(64),
                    encryptedReference = "link-file:artifact-1",
                    expiresAtEpochMillis = 2_000
                )
            )
        )
        val responseValidation = AgentRemoteExecutionTools.registry.validateResponse(
            boundedRequest,
            response
        )

        assertTrue(responseValidation.issues.any { it.code == "stdout_limit" })
        assertTrue(responseValidation.issues.any { it.code == "artifact_size_limit" })
        assertTrue(responseValidation.issues.any { it.code == "artifact_total_limit" })
    }

    @Test
    fun cancellationBeforeDispatchProducesTerminalPayload() {
        val calls = AtomicInteger()
        val request = request(AgentRemotePythonSpec("print(1)"))

        val payload = AgentRemoteExecutionTools.registry.dispatch(
            request,
            RecordingDispatcher(calls),
            AgentRemoteExecutionCancellationToken { true }
        )

        assertEquals(0, calls.get())
        assertEquals("cancelled", objectAt(objectAt(payload, "payload"), "status"))
        assertEquals(
            "cancelled",
            objectAt(objectAt(objectAt(payload, "payload"), "error"), "code")
        )
        val cancellation = AgentRemoteExecutionCancellationRequest(request, reason = "user_cancelled")
            .toEncryptedLinkPayload()
        assertEquals("agent_remote_execution_cancel", cancellation["type"])
        assertEquals(
            request.cancellationId,
            objectAt(objectAt(cancellation, "payload"), "cancellation_id")
        )
    }

    @Test
    fun successfulDispatchReturnsProcessResultAndDesktopProvenance() {
        val calls = AtomicInteger()
        val request = request(
            AgentRemoteFfmpegSpec(
                arguments = listOf("-i", "inputs/source.wav", "outputs/source.mp3"),
                artifactPaths = listOf("outputs/source.mp3")
            )
        )

        val envelope = AgentRemoteExecutionTools.registry.dispatch(
            request,
            RecordingDispatcher(calls)
        )
        val payload = objectAt(envelope, "payload") as Map<*, *>
        val provenance = payload["provenance"] as Map<*, *>

        assertEquals(1, calls.get())
        assertEquals("succeeded", payload["status"])
        assertEquals(0, payload["exit_code"])
        assertEquals("done", payload["stdout"])
        assertEquals("", payload["stderr"])
        assertEquals(25L, payload["duration_ms"])
        assertEquals("desktop-1", provenance["desktop_id"])
        assertEquals("desktop-owned-test-executor", provenance["executor_id"])
        assertEquals("desktop", provenance["workspace_ownership"])
        assertEquals("signalasi-link-encrypted", provenance["transport"])
        assertEquals(false, provenance["executed_on_android"])
        assertNotNull(payload["artifacts"])
    }

    private class RecordingDispatcher(
        private val calls: AtomicInteger
    ) : AgentRemoteExecutionDispatcher {
        override fun dispatch(
            target: AgentRemoteExecutionTarget,
            encryptedLinkRequestPayload: AgentRemoteExecutionPayload,
            cancellationToken: AgentRemoteExecutionCancellationToken
        ): AgentRemoteExecutionResponse {
            calls.incrementAndGet()
            val payload = encryptedLinkRequestPayload["payload"] as Map<*, *>
            val request = AgentRemoteExecutionRequest(
                target = target,
                spec = when (payload["tool_id"]) {
                    AgentRemoteExecutionTool.FFMPEG.id -> AgentRemoteFfmpegSpec(listOf("-version"))
                    else -> AgentRemotePythonSpec("print(1)")
                },
                requestId = encryptedLinkRequestPayload["message_id"] as String,
                sessionId = encryptedLinkRequestPayload["session_id"] as String,
                conversationId = encryptedLinkRequestPayload["conversation_id"] as String,
                turnId = encryptedLinkRequestPayload["turn_id"] as String,
                taskId = encryptedLinkRequestPayload["task_id"] as String,
                workspaceId = encryptedLinkRequestPayload["workspace_id"] as String,
                cancellationId = payload["cancellation_id"] as String
            )
            return successfulResponse(request)
        }

        override fun dispatchCancellation(
            target: AgentRemoteExecutionTarget,
            encryptedLinkCancellationPayload: AgentRemoteExecutionPayload
        ): AgentRemoteExecutionResponse {
            throw UnsupportedOperationException("Cancellation dispatch is not used in this test")
        }
    }

    companion object {
        private fun trustedTarget() = AgentRemoteExecutionTarget(
            desktopId = "desktop-1",
            desktopFingerprint = "f".repeat(64),
            relationshipId = "relationship-1",
            paired = true,
            trusted = true
        )

        private fun request(
            spec: AgentRemoteExecutionSpec,
            target: AgentRemoteExecutionTarget = trustedTarget()
        ) = AgentRemoteExecutionRequest(
            target = target,
            spec = spec,
            requestId = "request-1",
            sessionId = "session-1",
            conversationId = "conversation-1",
            turnId = "turn-1",
            taskId = "task-1",
            workspaceId = "workspace-1",
            sentAtEpochMillis = 1_000,
            expiresAtEpochMillis = 2_000,
            cancellationId = "cancel-1"
        )

        private fun successfulResponse(request: AgentRemoteExecutionRequest) =
            AgentRemoteExecutionResponse(
                requestId = request.requestId,
                cancellationId = request.cancellationId,
                status = AgentRemoteExecutionStatus.SUCCEEDED,
                exitCode = 0,
                stdout = "done",
                stderr = "",
                durationMillis = 25,
                provenance = AgentRemoteExecutionProvenance.forDesktop(
                    request,
                    executorId = "desktop-owned-test-executor",
                    executorVersion = "1.0.0"
                )
            )

        private fun objectAt(payload: Any?, key: String): Any? = (payload as Map<*, *>)[key]
    }
}
