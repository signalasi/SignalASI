package com.signalasi.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real-device coverage for the persistent Android-local development loop. */
@RunWith(AndroidJUnit4::class)
class AgentOnDeviceDevelopmentLoopTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun executesRepairsAndPersistsArtifactsAcrossTurns() {
        val workspaceId = "runtime-device-${System.currentTimeMillis()}"
        val lifecycle = AgentOnDeviceRuntimeLifecycle.ensureRunning(context)
        assertEquals(lifecycle.reason, AgentRuntimeLifecyclePhase.READY, lifecycle.phase)
        val registry = AgentPhoneNativeToolCatalog.defaultRegistry(
            context = context,
            screenProvider = {
                ScreenContext(
                    foregroundApp = context.packageName,
                    pageTitle = "SignalASI runtime device test"
                )
            }
        )
        val report = linkedMapOf<String, Any?>()

        val first = invokeRuntime(
            registry = registry,
            workspaceId = workspaceId,
            source = """
                from pathlib import Path

                result = "\n".join(str(value) for value in range(1, 6)) + "\n"
                Path("result.txt").write_text(result, encoding="utf-8")
                print(result, end="")
            """.trimIndent(),
            artifacts = listOf("result.txt")
        )
        report["create"] = resultReport(first)
        writeReport(report)
        assertTrue(resultFailureMessage(first), first.isSuccess)
        assertEquals("1\n2\n3\n4\n5\n", first.output["stdout"])
        assertArtifact(first, "result.txt")

        val second = invokeRuntime(
            registry = registry,
            workspaceId = workspaceId,
            source = """
                from pathlib import Path

                source = Path("result.txt").read_text(encoding="utf-8")
                values = [int(value) for value in source.splitlines()]
                assert values == [1, 2, 3, 4, 5], values
                summary = f"count={len(values)} sum={sum(values)}\n"
                Path("summary.txt").write_text(summary, encoding="utf-8")
                print(summary, end="")
            """.trimIndent(),
            artifacts = listOf("result.txt", "summary.txt")
        )
        report["persist"] = resultReport(second)
        writeReport(report)
        assertTrue(resultFailureMessage(second), second.isSuccess)
        assertEquals("count=5 sum=15\n", second.output["stdout"])
        assertProjectArchive(second, setOf("result.txt", "summary.txt"))

        val failed = invokeRuntime(
            registry = registry,
            workspaceId = workspaceId,
            source = "raise RuntimeError('intentional-runtime-smoke')",
            artifacts = emptyList()
        )
        report["failure"] = resultReport(failed)
        writeReport(report)
        assertEquals(AgentNativeToolResultStatus.FAILED, failed.status)
        assertEquals("on_device_runtime_nonzero_exit", failed.error?.code)
        assertTrue(failed.output["stderr"].toString().contains("intentional-runtime-smoke"))

        val project = File(context.filesDir, "agent-native-workspaces/$workspaceId")
        assertEquals("1\n2\n3\n4\n5\n", File(project, "result.txt").readText())
        assertEquals("count=5 sum=15\n", File(project, "summary.txt").readText())
        report["workspace_id"] = workspaceId
        report["project_files"] = project.walkTopDown()
            .filter(File::isFile)
            .map { it.relativeTo(project).invariantSeparatorsPath }
            .sorted()
            .toList()

        writeReport(report)
    }

    private fun writeReport(report: Map<String, Any?>) {
        val reportFile = File(
            requireNotNull(context.getExternalFilesDir("runtime-tests")),
            "development-loop.json"
        )
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(JSONObject(report).toString(2))
    }

    private fun resultFailureMessage(result: AgentNativeToolResult): String = buildString {
        append("status=").append(result.status.wireValue)
        append(" message=").append(result.message)
        result.error?.let { error ->
            append(" error=").append(error.code).append(':').append(error.message)
            if (error.details.isNotEmpty()) append(" details=").append(error.details)
        }
        if (result.output.isNotEmpty()) append(" output=").append(result.output)
    }

    private fun invokeRuntime(
        registry: AgentNativeToolRegistry,
        workspaceId: String,
        source: String,
        artifacts: List<String>
    ): AgentNativeToolResult {
        val descriptor = requireNotNull(registry.lookup(AgentOnDeviceRuntimeTools.EXECUTE)).descriptor
        return registry.invoke(
            AgentOnDeviceRuntimeTools.EXECUTE,
            mapOf(
                "language" to AgentRuntimeLanguage.PYTHON.wireValue,
                "source" to source,
                "arguments" to emptyList<String>(),
                "timeout_ms" to 180_000L,
                "network_enabled" to false,
                "allowed_network_domains" to emptyList<String>(),
                "artifact_paths" to artifacts
            ),
            AgentNativeToolInvocationContext(
                invocationId = "device-${UUID.randomUUID()}",
                sessionId = workspaceId,
                conversationId = workspaceId,
                turnId = "turn-${UUID.randomUUID()}",
                idempotencyKey = "device-${UUID.randomUUID()}",
                grantedPermissions = descriptor.requiredPermissions.mapTo(linkedSetOf()) { it.id },
                grantedConsents = descriptor.requiredConsents.mapTo(linkedSetOf()) { it.id },
                attributes = mapOf("workspace_id" to workspaceId)
            )
        )
    }

    private fun assertArtifact(result: AgentNativeToolResult, suffix: String) {
        val artifacts = result.output["artifacts"] as? Iterable<*> ?: emptyList<Any?>()
        assertTrue(
            "Missing artifact $suffix in $artifacts",
            artifacts.any { item ->
                val row = item as? Map<*, *> ?: return@any false
                row["relative_path"]?.toString()?.replace('\\', '/')?.endsWith(suffix) == true
            }
        )
    }

    private fun assertProjectArchive(result: AgentNativeToolResult, expectedPaths: Set<String>) {
        val artifacts = result.output["artifacts"] as? Iterable<*> ?: emptyList<Any?>()
        val archive = artifacts.mapNotNull { it as? Map<*, *> }
            .singleOrNull { it["artifact_kind"] == "project_archive" }
        assertTrue("Missing project archive in $artifacts", archive != null)
        val file = File(requireNotNull(archive)["host_path"].toString())
        assertTrue("Project archive does not exist: $file", file.isFile)
        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue("Missing project files in $names", names.containsAll(expectedPaths))
        }
    }

    private fun resultReport(result: AgentNativeToolResult): Map<String, Any?> = linkedMapOf(
        "status" to result.status.wireValue,
        "message" to result.message,
        "duration_ms" to result.receipt.durationMillis,
        "error_code" to result.error?.code,
        "error_message" to result.error?.message,
        "output" to result.output
    )
}
