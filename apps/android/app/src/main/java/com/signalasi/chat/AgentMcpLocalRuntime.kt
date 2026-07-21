package com.signalasi.chat

import android.content.Context
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

class AgentMcpLocalRuntimeClient(
    context: Context,
    private val registry: AgentMcpRegistry,
    private val packageRepository: AgentMcpPackageRepository,
    private val runtimeManager: AgentOnDeviceRuntimeManager = AgentOnDeviceRuntimeManager(context)
) {
    private val invocationLocks = ConcurrentHashMap<String, Any>()

    fun listTools(connection: AgentMcpConnection): List<AgentMcpTool> {
        val response = invoke(connection, "list_tools", "", emptyMap())
        val tools = response.optJSONArray("tools") ?: throw IllegalStateException("Local MCP server returned no tool list")
        require(tools.length() <= MAX_TOOLS) { "Local MCP server returned too many tools" }
        return (0 until tools.length()).map { index ->
            val rawJson = tools.optJSONObject(index) ?: throw IllegalStateException("Local MCP server returned an invalid tool")
            val raw = McpJson.parseObject(rawJson.toString())
            AgentMcpTool(
                name = raw.string("name")?.takeIf(String::isNotBlank)
                    ?: throw IllegalStateException("Local MCP tool name is missing"),
                title = raw.string("title"),
                description = raw.string("description"),
                inputSchema = raw.objectValue("inputSchema") ?: McpJsonObject.EMPTY,
                outputSchema = raw.objectValue("outputSchema"),
                annotations = raw.objectValue("annotations"),
                raw = raw
            )
        }
    }

    fun callTool(
        connection: AgentMcpConnection,
        toolName: String,
        arguments: AgentNativeJsonObject
    ): AgentNativeToolExecutionResult {
        val response = invoke(connection, "call_tool", toolName, arguments)
        val content = response.optJSONArray("content") ?: JSONArray()
        val normalizedContent = (0 until content.length()).mapNotNull { index ->
            content.optJSONObject(index)?.toNativeMap()
        }
        val message = normalizedContent.mapNotNull { item ->
            item["text"]?.toString()?.takeIf(String::isNotBlank)
        }.joinToString("\n").ifBlank { "MCP tool completed" }
        if (response.optBoolean("isError", false)) {
            return AgentNativeToolExecutionResult.failure(
                code = "mcp_tool_error",
                message = message,
                retryable = false,
                details = mapOf("connection_id" to connection.id, "tool_name" to toolName)
            )
        }
        return AgentNativeToolExecutionResult.success(
            output = linkedMapOf(
                "connection_id" to connection.id,
                "tool_name" to toolName,
                "content" to normalizedContent,
                "structured_content" to response.optJSONObject("structuredContent")?.toNativeMap()
            ),
            message = message,
            metadata = mapOf("transport" to AgentMcpTransportKind.LOCAL_STDIO.wireValue, "server" to connection.displayName)
        )
    }

    private fun invoke(
        connection: AgentMcpConnection,
        operation: String,
        toolName: String,
        arguments: AgentNativeJsonObject
    ): JSONObject = synchronized(invocationLocks.computeIfAbsent(connection.id) { Any() }) {
        invokeLocked(connection, operation, toolName, arguments)
    }

    private fun invokeLocked(
        connection: AgentMcpConnection,
        operation: String,
        toolName: String,
        arguments: AgentNativeJsonObject
    ): JSONObject {
        require(connection.transport == AgentMcpTransportKind.LOCAL_STDIO) { "MCP connection is not a local stdio server" }
        require(connection.isCallable(System.currentTimeMillis())) { "MCP connection requires authentication or setup" }
        val manifest = packageRepository.get(connection.id)
            ?: throw IllegalStateException("Local MCP package metadata is missing")
        val runtime = manifest.localRuntime ?: throw IllegalStateException("Local MCP runtime configuration is missing")
        val status = runtimeManager.status()
        check(status.languageReady(AgentRuntimeLanguage.PYTHON)) {
            "Local MCP requires the ${AgentRuntimeLanguage.PYTHON.requiredPack} runtime pack"
        }
        check(status.languageReady(runtime.language)) {
            "Local MCP server requires the ${runtime.language.requiredPack} runtime pack"
        }
        val requestId = UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("operation", operation)
            .put("entrypoint", runtime.entrypoint)
            .put("runtime", runtime.language.wireValue)
            .put("server_arguments", JSONArray(runtime.arguments))
            .put("tool_name", toolName)
            .put("arguments", JSONObject(arguments))
            .put("timeout_ms", runtime.timeoutMillis)
            .toString()
        val invocation = packageRepository.prepareLocalInvocation(connection.id, payload)
        registry.markConnecting(connection.id)
        return try {
            val execution = runtimeManager.execute(
                AgentRuntimeExecutionRequest(
                    language = AgentRuntimeLanguage.PYTHON,
                    source = BRIDGE_SOURCE,
                    arguments = listOf(invocation.requestPath),
                    timeoutMillis = runtime.timeoutMillis,
                    networkEnabled = runtime.allowedNetworkDomains.isNotEmpty(),
                    allowedNetworkDomains = runtime.allowedNetworkDomains,
                    artifactPaths = emptyList(),
                    workspaceId = invocation.workspaceId,
                    requestId = requestId,
                    secretEnvironment = renderEnvironment(runtime.environment, registry.secrets(connection.id))
                )
            )
            val decoded = AgentMcpLocalRuntimeResponseCodec.decode(execution.stdout)
            if (execution.exitCode != 0) {
                val reason = execution.stderr.trim().take(MAX_ERROR_CHARS).ifBlank { "Local MCP bridge exited with ${execution.exitCode}" }
                throw IllegalStateException(reason)
            }
            decoded
        } finally {
            packageRepository.completeLocalInvocation(invocation)
        }
    }

    private fun renderEnvironment(templates: Map<String, String>, secrets: Map<String, String>): Map<String, String> =
        templates.mapValues { (_, template) ->
            AUTH_PATTERN.replace(template) { match -> secrets[match.groupValues[1]].orEmpty() }
        }

    companion object {
        private const val MAX_TOOLS = 128
        private const val MAX_ERROR_CHARS = 4_096
        private val AUTH_PATTERN = Regex("\\{\\{auth\\.([A-Za-z0-9_.-]+)\\}\\}")

        private val BRIDGE_SOURCE = """
            import json
            import os
            import select
            import subprocess
            import sys
            import threading
            import time
            from pathlib import Path

            PREFIX = "__SIGNALASI_MCP_RESULT__"
            MAX_STDERR = 32768

            def emit(value):
                print(PREFIX + json.dumps(value, ensure_ascii=False, separators=(",", ":")), flush=True)

            def fail(message):
                emit({"ok": False, "error": str(message)[:4096]})
                raise SystemExit(1)

            if len(sys.argv) != 2:
                fail("Local MCP invocation path is missing")

            root = Path.cwd().resolve()
            request_path = (root / sys.argv[1]).resolve()
            if root not in request_path.parents or not request_path.is_file():
                fail("Local MCP invocation path is invalid")

            try:
                payload = json.loads(request_path.read_text(encoding="utf-8"))
                entrypoint = (root / str(payload["entrypoint"])).resolve()
                if root not in entrypoint.parents or not entrypoint.is_file():
                    fail("Local MCP entrypoint is unavailable")
                runtime = str(payload["runtime"])
                executables = {
                    "shell": ["sh"],
                    "python": ["python3"],
                    "javascript": ["node"],
                    "typescript": ["tsx"],
                }
                if runtime not in executables:
                    fail("Local MCP runtime is unsupported")
                command = executables[runtime] + [str(entrypoint)] + [str(value) for value in payload.get("server_arguments", [])]
                environment = os.environ.copy()
                environment["SIGNALASI_MCP_SANDBOX"] = "1"
                timeout_seconds = max(5.0, min(float(payload.get("timeout_ms", 60000)) / 1000.0, 180.0))
                process = subprocess.Popen(
                    command,
                    cwd=str(root),
                    env=environment,
                    stdin=subprocess.PIPE,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                    bufsize=1,
                )
                stderr_parts = []

                def drain_stderr():
                    for line in process.stderr:
                        if sum(len(item) for item in stderr_parts) < MAX_STDERR:
                            stderr_parts.append(line)

                threading.Thread(target=drain_stderr, daemon=True).start()

                def send(message):
                    process.stdin.write(json.dumps(message, ensure_ascii=False, separators=(",", ":")) + "\n")
                    process.stdin.flush()

                def request(identifier, method, params):
                    send({"jsonrpc": "2.0", "id": identifier, "method": method, "params": params})
                    deadline = time.monotonic() + timeout_seconds
                    while time.monotonic() < deadline:
                        if process.poll() is not None:
                            detail = "".join(stderr_parts).strip()
                            raise RuntimeError(detail or f"MCP server exited with {process.returncode}")
                        ready, _, _ = select.select([process.stdout], [], [], min(0.25, max(0.0, deadline - time.monotonic())))
                        if not ready:
                            continue
                        line = process.stdout.readline()
                        if not line:
                            continue
                        try:
                            message = json.loads(line)
                        except json.JSONDecodeError:
                            continue
                        if message.get("id") == identifier:
                            if "error" in message:
                                error = message.get("error") or {}
                                raise RuntimeError(str(error.get("message") or "MCP request failed"))
                            return message.get("result") or {}
                        if "method" in message and "id" in message:
                            send({"jsonrpc": "2.0", "id": message["id"], "error": {"code": -32601, "message": "Client method is unavailable"}})
                    raise TimeoutError("Local MCP server timed out")

                initialized = request(1, "initialize", {
                    "protocolVersion": "2025-11-25",
                    "capabilities": {},
                    "clientInfo": {"name": "signalasi-android", "version": "1.0.0"},
                })
                supported_protocols = {"2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"}
                if initialized.get("protocolVersion") not in supported_protocols:
                    raise RuntimeError("MCP server selected an unsupported protocol version")
                send({"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}})
                operation = payload.get("operation")
                if operation == "list_tools":
                    tools = []
                    cursor = None
                    for page_index in range(16):
                        params = {"cursor": cursor} if cursor else {}
                        page = request(2 + page_index, "tools/list", params)
                        page_tools = page.get("tools") or []
                        if not isinstance(page_tools, list):
                            raise RuntimeError("MCP server returned an invalid tool list")
                        tools.extend(page_tools)
                        cursor = page.get("nextCursor")
                        if not cursor:
                            break
                    else:
                        raise RuntimeError("MCP tool pagination exceeded the limit")
                    result = {"tools": tools}
                elif operation == "call_tool":
                    result = request(2, "tools/call", {
                        "name": str(payload.get("tool_name", "")),
                        "arguments": payload.get("arguments") or {},
                    })
                else:
                    raise RuntimeError("Local MCP operation is invalid")
                emit({"ok": True, "result": result})
            except SystemExit:
                raise
            except Exception as error:
                fail(error)
            finally:
                if "process" in locals() and process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=1.0)
                    except subprocess.TimeoutExpired:
                        process.kill()
        """.trimIndent()
    }
}

internal object AgentMcpLocalRuntimeResponseCodec {
    private const val PREFIX = "__SIGNALASI_MCP_RESULT__"

    fun decode(stdout: String): JSONObject {
        val line = stdout.lineSequence().map(String::trim).lastOrNull { it.startsWith(PREFIX) }
            ?: throw IllegalStateException("Local MCP bridge returned no structured result")
        val envelope = JSONObject(line.removePrefix(PREFIX))
        if (!envelope.optBoolean("ok", false)) {
            throw IllegalStateException(envelope.optString("error").ifBlank { "Local MCP bridge failed" })
        }
        return envelope.optJSONObject("result") ?: JSONObject()
    }
}

private fun JSONObject.toNativeMap(): Map<String, Any?> = keys().asSequence().associateWith { key -> opt(key).toNativeValue() }

private fun Any?.toNativeValue(): Any? = when (this) {
    null, JSONObject.NULL -> null
    is JSONObject -> toNativeMap()
    is JSONArray -> (0 until length()).map { index -> opt(index).toNativeValue() }
    is String, is Number, is Boolean -> this
    else -> toString()
}
