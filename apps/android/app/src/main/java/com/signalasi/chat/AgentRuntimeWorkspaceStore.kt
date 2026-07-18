package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

enum class AgentRuntimeReceiptStatus { RUNNING, COMPLETED, FAILED, CANCELLED, TIMED_OUT }

data class AgentRuntimeExecutionReceipt(
    val requestId: String,
    val workspaceId: String,
    val language: AgentRuntimeLanguage,
    val sourceSha256: String,
    val packVersions: Map<String, String>,
    val networkEnabled: Boolean,
    val allowedNetworkDomains: List<String>,
    val limits: AgentRuntimeResourceLimits,
    val status: AgentRuntimeReceiptStatus,
    val exitCode: Int? = null,
    val stdoutSha256: String = "",
    val stderrSha256: String = "",
    val artifacts: List<Map<String, Any?>> = emptyList(),
    val error: String = "",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val completedAtMillis: Long = 0L
)

fun AgentRuntimeExecutionReceipt.toEvidenceMap(): AgentNativeJsonObject = linkedMapOf(
    "request_id" to requestId,
    "workspace_id" to workspaceId,
    "language" to language.wireValue,
    "source_sha256" to sourceSha256,
    "pack_versions" to packVersions.toSortedMap(),
    "network_enabled" to networkEnabled,
    "allowed_network_domains" to allowedNetworkDomains.sorted(),
    "limits" to linkedMapOf(
        "wall_clock_ms" to limits.wallClockMillis,
        "cpu_ms" to limits.cpuMillis,
        "memory_bytes" to limits.memoryBytes,
        "disk_bytes" to limits.diskBytes,
        "max_processes" to limits.maxProcesses,
        "max_output_bytes" to limits.maxOutputBytes,
        "max_artifact_bytes" to limits.maxArtifactBytes
    ),
    "status" to status.name.lowercase(Locale.ROOT),
    "exit_code" to exitCode,
    "stdout_sha256" to stdoutSha256,
    "stderr_sha256" to stderrSha256,
    "artifacts" to artifacts.map { artifact ->
        linkedMapOf(
            "relative_path" to artifact["relative_path"],
            "size_bytes" to artifact["size_bytes"],
            "sha256" to artifact["sha256"]
        )
    },
    "error" to error,
    "created_at_millis" to createdAtMillis,
    "completed_at_millis" to completedAtMillis
)

class AgentRuntimeExecutionReceiptStore(context: Context) {
    private val database = AgentEncryptedDatabase(
        context.applicationContext,
        DATABASE,
        legacyPreferencesName = UNUSED_LEGACY_PREFERENCES
    )

    @Synchronized
    fun begin(request: AgentRuntimeExecutionRequest, packVersions: Map<String, String>): AgentRuntimeExecutionReceipt {
        check(find(request.requestId) == null) { "Runtime request id was already used" }
        val receipt = AgentRuntimeExecutionReceipt(
            requestId = request.requestId,
            workspaceId = request.workspaceId,
            language = request.language,
            sourceSha256 = sha256(request.source.toByteArray(Charsets.UTF_8)),
            packVersions = packVersions.toSortedMap(),
            networkEnabled = request.networkEnabled,
            allowedNetworkDomains = request.allowedNetworkDomains.sorted(),
            limits = request.resourceLimits,
            status = AgentRuntimeReceiptStatus.RUNNING
        )
        save((list() + receipt).takeLast(MAX_RECEIPTS))
        return receipt
    }

    @Synchronized
    fun complete(
        requestId: String,
        response: AgentRuntimeExecutionResponse,
        artifacts: List<Map<String, Any?>>
    ): AgentRuntimeExecutionReceipt? = update(requestId) { receipt ->
        receipt.copy(
            status = if (response.exitCode == 0) AgentRuntimeReceiptStatus.COMPLETED else AgentRuntimeReceiptStatus.FAILED,
            exitCode = response.exitCode,
            stdoutSha256 = sha256(response.stdout.toByteArray(Charsets.UTF_8)),
            stderrSha256 = sha256(response.stderr.toByteArray(Charsets.UTF_8)),
            artifacts = artifacts.take(MAX_ARTIFACTS),
            completedAtMillis = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun fail(requestId: String, error: Throwable): AgentRuntimeExecutionReceipt? = update(requestId) { receipt ->
        receipt.copy(
            status = when (error) {
                is AgentNativeToolCancelledException -> AgentRuntimeReceiptStatus.CANCELLED
                is AgentNativeToolTimeoutException -> AgentRuntimeReceiptStatus.TIMED_OUT
                else -> AgentRuntimeReceiptStatus.FAILED
            },
            error = error.message.orEmpty().take(MAX_ERROR_CHARS),
            completedAtMillis = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun find(requestId: String): AgentRuntimeExecutionReceipt? = list().firstOrNull { it.requestId == requestId }

    @Synchronized
    fun list(limit: Int = MAX_RECEIPTS): List<AgentRuntimeExecutionReceipt> = decode(
        database.readString(KEY_RECEIPTS, "[]")
    ).takeLast(limit.coerceIn(0, MAX_RECEIPTS)).asReversed()

    @Synchronized
    fun clear() = database.clear()

    private fun update(
        requestId: String,
        transform: (AgentRuntimeExecutionReceipt) -> AgentRuntimeExecutionReceipt
    ): AgentRuntimeExecutionReceipt? {
        val receipts = list(MAX_RECEIPTS).asReversed().toMutableList()
        val index = receipts.indexOfFirst { it.requestId == requestId }
        if (index < 0) return null
        val updated = transform(receipts[index])
        receipts[index] = updated
        save(receipts)
        return updated
    }

    private fun save(receipts: List<AgentRuntimeExecutionReceipt>) {
        database.writeString(KEY_RECEIPTS, JSONArray().apply {
            receipts.takeLast(MAX_RECEIPTS).forEach { put(it.toJson()) }
        }.toString())
    }

    private fun decode(raw: String): List<AgentRuntimeExecutionReceipt> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) array.optJSONObject(index)?.toReceipt()?.let(::add)
        }
    }.getOrDefault(emptyList())

    private fun AgentRuntimeExecutionReceipt.toJson(): JSONObject = JSONObject()
        .put("request_id", requestId)
        .put("workspace_id", workspaceId)
        .put("language", language.wireValue)
        .put("source_sha256", sourceSha256)
        .put("pack_versions", JSONObject(packVersions))
        .put("network_enabled", networkEnabled)
        .put("allowed_network_domains", JSONArray(allowedNetworkDomains))
        .put("limits", limits.toJson())
        .put("status", status.name)
        .put("exit_code", exitCode)
        .put("stdout_sha256", stdoutSha256)
        .put("stderr_sha256", stderrSha256)
        .put("artifacts", JSONArray(artifacts.map(::JSONObject)))
        .put("error", error)
        .put("created_at_millis", createdAtMillis)
        .put("completed_at_millis", completedAtMillis)

    private fun JSONObject.toReceipt(): AgentRuntimeExecutionReceipt? {
        val requestId = optString("request_id")
        val language = AgentRuntimeLanguage.entries.firstOrNull { it.wireValue == optString("language") }
        if (requestId.isBlank() || language == null) return null
        val packJson = optJSONObject("pack_versions") ?: JSONObject()
        val artifactsJson = optJSONArray("artifacts") ?: JSONArray()
        return AgentRuntimeExecutionReceipt(
            requestId = requestId,
            workspaceId = optString("workspace_id"),
            language = language,
            sourceSha256 = optString("source_sha256"),
            packVersions = buildMap {
                packJson.keys().asSequence().forEach { key -> put(key, packJson.optString(key)) }
            },
            networkEnabled = optBoolean("network_enabled"),
            allowedNetworkDomains = optJSONArray("allowed_network_domains").strings(),
            limits = optJSONObject("limits").toLimits(),
            status = runCatching { AgentRuntimeReceiptStatus.valueOf(optString("status")) }
                .getOrDefault(AgentRuntimeReceiptStatus.FAILED),
            exitCode = if (has("exit_code") && !isNull("exit_code")) optInt("exit_code") else null,
            stdoutSha256 = optString("stdout_sha256"),
            stderrSha256 = optString("stderr_sha256"),
            artifacts = buildList {
                for (index in 0 until artifactsJson.length()) {
                    artifactsJson.optJSONObject(index)?.let { artifact ->
                        add(buildMap {
                            artifact.keys().asSequence().forEach { key -> put(key, artifact.opt(key)) }
                        })
                    }
                }
            },
            error = optString("error"),
            createdAtMillis = optLong("created_at_millis"),
            completedAtMillis = optLong("completed_at_millis")
        )
    }

    private fun AgentRuntimeResourceLimits.toJson(): JSONObject = JSONObject()
        .put("wall_clock_ms", wallClockMillis)
        .put("cpu_ms", cpuMillis)
        .put("memory_bytes", memoryBytes)
        .put("disk_bytes", diskBytes)
        .put("max_processes", maxProcesses)
        .put("max_output_bytes", maxOutputBytes)
        .put("max_artifact_bytes", maxArtifactBytes)

    private fun JSONObject?.toLimits(): AgentRuntimeResourceLimits {
        val source = this ?: return AgentRuntimeResourceLimits()
        return AgentRuntimeResourceLimits(
            wallClockMillis = source.optLong("wall_clock_ms", 60_000L),
            cpuMillis = source.optLong("cpu_ms", 45_000L),
            memoryBytes = source.optLong("memory_bytes", 512L * 1024L * 1024L),
            diskBytes = source.optLong("disk_bytes", 512L * 1024L * 1024L),
            maxProcesses = source.optInt("max_processes", 64),
            maxOutputBytes = source.optLong("max_output_bytes", 512L * 1024L),
            maxArtifactBytes = source.optLong("max_artifact_bytes", 256L * 1024L * 1024L)
        )
    }

    private fun JSONArray?.strings(): List<String> = buildList {
        val source = this@strings ?: return@buildList
        for (index in 0 until source.length()) source.optString(index).takeIf(String::isNotBlank)?.let(::add)
    }

    companion object {
        private const val DATABASE = "signalasi_runtime_receipts_v1"
        private const val UNUSED_LEGACY_PREFERENCES = "signalasi_runtime_receipts_v1_no_legacy"
        private const val KEY_RECEIPTS = "receipts"
        private const val MAX_RECEIPTS = 1_000
        private const val MAX_ARTIFACTS = 32
        private const val MAX_ERROR_CHARS = 4_096
    }
}

data class AgentRuntimePreparedWorkspace(
    val requestId: String,
    val workspaceId: String,
    val directory: File,
    val sourceFile: File,
    val guestPath: String
)

class AgentRuntimeWorkspaceManager(context: Context) {
    private val root = File(context.applicationContext.filesDir, "agent-runtime/workspaces")

    @Synchronized
    fun prepare(request: AgentRuntimeExecutionRequest): AgentRuntimePreparedWorkspace {
        require(request.requestId.matches(ID_PATTERN)) { "Runtime request id is invalid" }
        require(request.workspaceId.isNotBlank() && request.workspaceId.length <= MAX_WORKSPACE_ID_CHARS) {
            "Runtime workspace id is invalid"
        }
        request.resourceLimits.validated()
        require(request.artifactPaths.distinct().size == request.artifactPaths.size) {
            "Runtime artifact paths must be unique"
        }
        request.artifactPaths.forEach(::validateRelativePath)
        request.allowedNetworkDomains.forEach(::validateDomain)
        if (!request.networkEnabled) require(request.allowedNetworkDomains.isEmpty()) {
            "Runtime network domains require network access"
        }
        cleanupExpired()
        check(root.mkdirs() || root.isDirectory) { "Runtime workspace storage is unavailable" }
        val workspaceDirectory = safeChild(root, sha256(request.workspaceId.toByteArray()).take(32))
            ?: error("Runtime workspace path is invalid")
        val runDirectory = safeChild(workspaceDirectory, request.requestId)
            ?: error("Runtime request path is invalid")
        check(!runDirectory.exists()) { "Runtime request workspace already exists" }
        check(runDirectory.mkdirs()) { "Runtime request workspace could not be created" }
        val sourceFile = File(runDirectory, sourceFileName(request.language))
        sourceFile.writeText(request.source, Charsets.UTF_8)
        File(runDirectory, "request.json").writeText(
            JSONObject()
                .put("request_id", request.requestId)
                .put("workspace_id_hash", sha256(request.workspaceId.toByteArray()))
                .put("language", request.language.wireValue)
                .put("source_sha256", sha256(request.source.toByteArray(Charsets.UTF_8)))
                .put("created_at_millis", System.currentTimeMillis())
                .toString(),
            Charsets.UTF_8
        )
        return AgentRuntimePreparedWorkspace(
            requestId = request.requestId,
            workspaceId = request.workspaceId,
            directory = runDirectory,
            sourceFile = sourceFile,
            guestPath = "/workspace/${workspaceDirectory.name}/${runDirectory.name}"
        )
    }

    @Synchronized
    fun collectArtifacts(
        prepared: AgentRuntimePreparedWorkspace,
        request: AgentRuntimeExecutionRequest
    ): List<Map<String, Any?>> {
        var totalBytes = 0L
        return request.artifactPaths.mapNotNull { relative ->
            val artifact = safeChild(prepared.directory, relative) ?: return@mapNotNull null
            if (!artifact.isFile) return@mapNotNull null
            val bytes = artifact.length()
            check(bytes <= request.resourceLimits.maxArtifactBytes) { "Runtime artifact exceeds its size limit" }
            totalBytes += bytes
            check(totalBytes <= request.resourceLimits.diskBytes) { "Runtime artifacts exceed the workspace quota" }
            mapOf(
                "relative_path" to relative.replace('\\', '/'),
                "size_bytes" to bytes,
                "sha256" to sha256File(artifact),
                "host_path" to artifact.absolutePath
            )
        }
    }

    @Synchronized
    fun markFinished(prepared: AgentRuntimePreparedWorkspace, status: AgentRuntimeReceiptStatus) {
        File(prepared.directory, "status.json").writeText(
            JSONObject()
                .put("status", status.name.lowercase(Locale.ROOT))
                .put("completed_at_millis", System.currentTimeMillis())
                .toString(),
            Charsets.UTF_8
        )
    }

    @Synchronized
    fun cleanupExpired(nowMillis: Long = System.currentTimeMillis()) {
        if (!root.isDirectory) return
        root.listFiles().orEmpty().filter(File::isDirectory).forEach { workspace ->
            workspace.listFiles().orEmpty().filter(File::isDirectory).forEach { run ->
                val age = nowMillis - run.lastModified().coerceAtLeast(0L)
                if (age > WORKSPACE_TTL_MILLIS) run.deleteRecursively()
            }
            if (workspace.listFiles().isNullOrEmpty()) workspace.delete()
        }
    }

    private fun safeChild(parent: File, relative: String): File? {
        if (relative.isBlank() || File(relative).isAbsolute) return null
        val canonicalParent = parent.canonicalFile
        val candidate = File(canonicalParent, relative).canonicalFile
        return candidate.takeIf { it.path.startsWith(canonicalParent.path + File.separator) }
    }

    private fun validateRelativePath(value: String) {
        require(value.isNotBlank() && value.length <= MAX_ARTIFACT_PATH_CHARS && !File(value).isAbsolute) {
            "Runtime artifact path is invalid"
        }
        require(value.replace('\\', '/').split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "Runtime artifact path is unsafe"
        }
    }

    private fun validateDomain(value: String) {
        require(DOMAIN_PATTERN.matches(value.lowercase(Locale.ROOT))) { "Runtime network domain is invalid" }
    }

    private fun sourceFileName(language: AgentRuntimeLanguage): String = when (language) {
        AgentRuntimeLanguage.SHELL -> "main.sh"
        AgentRuntimeLanguage.PYTHON, AgentRuntimeLanguage.UV -> "main.py"
        AgentRuntimeLanguage.JAVASCRIPT -> "main.js"
        AgentRuntimeLanguage.TYPESCRIPT -> "main.ts"
        AgentRuntimeLanguage.GO -> "main.go"
        AgentRuntimeLanguage.RUST -> "main.rs"
        AgentRuntimeLanguage.C -> "main.c"
        AgentRuntimeLanguage.CPP -> "main.cpp"
        AgentRuntimeLanguage.JAVA -> "Main.java"
        AgentRuntimeLanguage.FFMPEG -> "main.ffmpeg.json"
        AgentRuntimeLanguage.FFPROBE -> "main.ffprobe.json"
    }

    companion object {
        private const val MAX_WORKSPACE_ID_CHARS = 160
        private const val MAX_ARTIFACT_PATH_CHARS = 1_024
        private const val WORKSPACE_TTL_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val DOMAIN_PATTERN = Regex("(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
    }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

private fun sha256File(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
