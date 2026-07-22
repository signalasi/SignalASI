package com.signalasi.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real-device acceptance for an installed stdio MCP server inside the Android Linux sandbox. */
@RunWith(AndroidJUnit4::class)
class AgentMcpLocalRuntimeDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun installsDiscoversAndInvokesPythonMcpWithoutPersistingCredentials() = runAcceptance(
        packageId = "signalasi.acceptance.localmcp.secure",
        reportName = "local-mcp-secure-device.json",
        requireSecret = true
    )

    @Test
    fun installsDiscoversAndInvokesPythonMcpWithoutCredentials() = runAcceptance(
        packageId = "signalasi.acceptance.localmcp.plain",
        reportName = "local-mcp-device.json",
        requireSecret = false
    )

    private fun runAcceptance(
        packageId: String,
        reportName: String,
        requireSecret: Boolean
    ) = runBlocking {
        val secret = "device-secret-${System.currentTimeMillis()}"
        val repository = AgentMcpPackageRepository(
            context,
            preferencesName = "signalasi_mcp_device_acceptance"
        )
        val store = InMemoryAgentMcpStore()
        val registry = AgentMcpRegistry(store)
        val manager = AgentMcpClientManager(context, registry, repository)
        val report = linkedMapOf<String, Any?>()

        try {
            val bootstrap = AgentEmbeddedRuntimeBootstrap.ensureInstalled(context)
            assertTrue("The test APK does not contain the default runtime bundle", bootstrap.bundled)
            val lifecycle = AgentOnDeviceRuntimeLifecycle.ensureRunning(context)
            assertEquals(lifecycle.reason, AgentRuntimeLifecyclePhase.READY, lifecycle.phase)

            val manifest = AgentMcpPackageManifest(
                id = packageId,
                version = "1.0.0",
                name = "Device acceptance MCP",
                description = "Exercises the Android-local MCP production transport",
                endpoint = "local-mcp:$packageId",
                transport = AgentMcpTransportKind.LOCAL_STDIO,
                authProfiles = listOf(AgentMcpAuthProfile(AgentMcpAuthMethod.NONE)),
                tools = emptyList(),
                localRuntime = AgentMcpLocalRuntimeSpec(
                    language = AgentRuntimeLanguage.PYTHON,
                    entrypoint = ENTRYPOINT,
                    environment = if (requireSecret) {
                        mapOf("SIGNALASI_ACCEPTANCE_TOKEN" to "{{auth.token}}")
                    } else {
                        emptyMap()
                    },
                    timeoutMillis = 90_000L
                )
            )
            val rawManifest = AgentMcpPackageManifestCodec.encode(manifest)
            val serverBytes = SERVER_SOURCE.toByteArray(Charsets.UTF_8)
            repository.save(
                AgentMcpPackageInspection(
                    manifest = manifest,
                    rawManifest = rawManifest,
                    packageSha256 = AgentMcpPackageInstaller.sha256(serverBytes),
                    manifestSha256 = AgentMcpPackageInstaller.sha256(rawManifest.toByteArray()),
                    integrityVerified = true,
                    archiveEntries = listOf(AgentMcpPackageInstaller.MANIFEST_PATH, ENTRYPOINT),
                    runtimeFiles = mapOf(ENTRYPOINT to serverBytes)
                )
            )
            registry.installPackage(manifest, AgentMcpPackageInstaller.sha256(serverBytes))
            if (requireSecret) store.writeSecrets(packageId, mapOf("token" to secret))

            val startedAt = System.currentTimeMillis()
            val tools = manager.listTools(packageId)
            val discoveredAt = System.currentTimeMillis()
            assertEquals(listOf("acceptance.echo"), tools.map(AgentMcpTool::name))
            assertEquals(AgentMcpConnectionState.CONNECTED, registry.get(packageId)?.state)

            val result = manager.callTool(
                packageId,
                "acceptance.echo",
                mapOf("text" to "device acceptance", "language" to "zh-CN")
            )
            val completedAt = System.currentTimeMillis()
            assertTrue(result.message, result.isSuccess)
            assertEquals("echo: device acceptance", result.message)
            val structured = result.output["structured_content"] as? Map<*, *>
            assertEquals(requireSecret, structured?.get("secret_bound"))
            assertEquals("zh-CN", structured?.get("language"))

            val workspace = localWorkspace(packageId)
            val controlFiles = File(workspace, ".signalasi-mcp").listFiles().orEmpty()
            assertFalse("Local MCP request files were retained: ${controlFiles.toList()}", controlFiles.any(File::isFile))
            val leaked = workspace.walkTopDown()
                .filter { it.isFile && it.length() <= MAX_SECRET_SCAN_BYTES }
                .any { file -> runCatching { file.readText().contains(secret) }.getOrDefault(false) }
            assertFalse("Local MCP credentials were persisted in the runtime workspace", leaked)

            report += mapOf(
                "transport" to AgentMcpTransportKind.LOCAL_STDIO.wireValue,
                "runtime" to AgentRuntimeLanguage.PYTHON.wireValue,
                "bootstrap_installed" to bootstrap.installed,
                "bootstrap_retained" to bootstrap.retained,
                "lifecycle" to lifecycle.phase.name,
                "tools" to tools.map(AgentMcpTool::name),
                "discovery_duration_ms" to discoveredAt - startedAt,
                "call_duration_ms" to completedAt - discoveredAt,
                "result" to result.output,
                "request_files_retained" to controlFiles.count(File::isFile),
                "credential_required" to requireSecret,
                "credential_persisted" to leaked
            )
            writeReport(reportName, report)
        } finally {
            manager.closeAll()
            repository.delete(packageId)
        }
    }

    private fun localWorkspace(id: String): File {
        val suffix = AgentMcpPackageInstaller.sha256(id.toByteArray()).take(32)
        return File(context.filesDir, "agent-native-workspaces/mcp-$suffix")
    }

    private fun writeReport(name: String, report: Map<String, Any?>) {
        val output = File(
            requireNotNull(context.getExternalFilesDir("runtime-tests")),
            name
        )
        output.parentFile?.mkdirs()
        output.writeText(JSONObject(report).toString(2))
    }

    private companion object {
        const val ENTRYPOINT = "runtime/server.py"
        const val MAX_SECRET_SCAN_BYTES = 1024L * 1024L

        val SERVER_SOURCE = """
            import json
            import os
            import sys

            def send(identifier, result):
                print(json.dumps({"jsonrpc": "2.0", "id": identifier, "result": result}, ensure_ascii=False), flush=True)

            for line in sys.stdin:
                request = json.loads(line)
                method = request.get("method")
                identifier = request.get("id")
                if method == "initialize":
                    send(identifier, {
                        "protocolVersion": "2025-11-25",
                        "capabilities": {"tools": {}},
                        "serverInfo": {"name": "signalasi-device-acceptance", "version": "1.0.0"},
                    })
                elif method == "tools/list":
                    send(identifier, {"tools": [{
                        "name": "acceptance.echo",
                        "title": "Acceptance echo",
                        "description": "Returns bounded structured device evidence",
                        "inputSchema": {
                            "type": "object",
                            "properties": {
                                "text": {"type": "string"},
                                "language": {"type": "string"},
                            },
                            "required": ["text"],
                        },
                    }]})
                elif method == "tools/call":
                    arguments = request.get("params", {}).get("arguments", {})
                    token = os.environ.get("SIGNALASI_ACCEPTANCE_TOKEN", "")
                    text = str(arguments.get("text", ""))
                    send(identifier, {
                        "content": [{"type": "text", "text": "echo: " + text}],
                        "structuredContent": {
                            "secret_bound": token.startswith("device-secret-"),
                            "language": arguments.get("language", ""),
                            "sandbox": os.environ.get("SIGNALASI_MCP_SANDBOX") == "1",
                        },
                        "isError": False,
                    })
        """.trimIndent()
    }
}
