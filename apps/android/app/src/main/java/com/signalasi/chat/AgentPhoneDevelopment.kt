package com.signalasi.chat

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.Locale

internal const val PHONE_DEVELOPMENT_MANIFEST_PARAMETER = "_signalasi_phone_development_manifest"
internal const val PHONE_DEVELOPMENT_FILE_PARAMETER = "_signalasi_phone_development_file"
internal const val PHONE_DEVELOPMENT_ERROR_PARAMETER = "_signalasi_phone_development_error"
internal const val PHONE_DEVELOPMENT_CONNECTOR_MODE = "phone_development_manifest_v1"

internal object AgentPhoneDevelopmentPolicy {
    private val developmentTerms = listOf(
        "python", "program", "programme", "code", "script", "app", "function", "algorithm",
        "write code", "create code", "run code", "verify", "validate", "test it", "execute it",
        "\u7a0b\u5e8f", "\u4ee3\u7801", "\u811a\u672c", "\u51fd\u6570", "\u7b97\u6cd5", "\u5f00\u53d1", "\u7f16\u7a0b", "\u8fd0\u884c", "\u9a8c\u8bc1", "\u6d4b\u8bd5"
    )
    private val creationTerms = listOf(
        "write", "create", "make", "implement", "build", "generate", "fix", "debug", "run", "verify", "test",
        "\u5199", "\u521b\u5efa", "\u751f\u6210", "\u5b9e\u73b0", "\u4fee\u590d", "\u8c03\u8bd5", "\u8fd0\u884c", "\u9a8c\u8bc1", "\u6d4b\u8bd5"
    )
    private val phoneTerms = listOf(
        "on this phone", "on the phone", "phone local", "on-device", "locally on phone",
        "\u624b\u673a\u672c\u673a", "\u5728\u624b\u673a", "\u672c\u673a\u6267\u884c", "\u672c\u5730\u6267\u884c", "\u672c\u4f53\u6267\u884c"
    )
    private val desktopTerms = listOf(
        "on desktop", "on the desktop", "on pc", "on the computer", "use codex desktop", "send to codex",
        "\u5728\u7535\u8111", "\u7535\u8111\u4e0a\u6267\u884c", "\u4ea4\u7ed9codex", "\u53d1\u7ed9codex", "\u4f7f\u7528codex desktop"
    )
    private val largeProjectTerms = listOf(
        "repository", "repo", "entire project", "whole project", "android project", "gradle", "xcode",
        "docker", "windows app", "desktop app", "compile apk", "build apk", "release build",
        "\u4ed3\u5e93", "\u6574\u4e2a\u9879\u76ee", "\u5927\u578b\u9879\u76ee", "\u7f16\u8bd1apk", "\u6253\u5305apk", "\u684c\u9762\u7a0b\u5e8f"
    )

    fun shouldUsePhoneRuntime(goal: String): Boolean {
        val normalized = goal.trim().lowercase(Locale.US)
        if (normalized.isBlank()) return false
        if (desktopTerms.any(normalized::contains)) return false
        val development = developmentTerms.any(normalized::contains) && creationTerms.any(normalized::contains)
        if (!development) return false
        if (phoneTerms.any(normalized::contains)) return true
        return largeProjectTerms.none(normalized::contains) && normalized.length <= MAX_INTERACTIVE_GOAL_CHARACTERS
    }

    fun planningPrompt(goal: String): String = buildString {
        append("Act only as a code author for SignalASI's Android-local Linux runtime. ")
        append("Do not run commands, create files, or modify anything on this desktop. ")
        append("Return exactly one JSON object and no markdown or commentary. ")
        append("Schema: {\"schema\":\"signalasi.phone-development-manifest.v2\",")
        append("\"language\":\"python\",\"entry_file\":\"meaningful_name.py\",")
        append("\"files\":[{\"path\":\"meaningful_name.py\",\"content\":\"complete source\"}],")
        append("\"artifact_paths\":[]}. Include every source, configuration, asset, and test file needed by the project in files. ")
        append("Use one file for a simple program and multiple files with relative directory paths for a project. ")
        append("The entry program must be deterministic, print its useful result, verify the requested behavior ")
        append("with assertions or equivalent checks, and exit non-zero if verification fails. ")
        append("Do not use network access or external packages unless the user explicitly requires them. ")
        append("Keep all paths safe and relative. Do not return archives or binary data when text project files are sufficient. ")
        append("User goal: ").append(goal.trim().take(4_000))
    }

    private const val MAX_INTERACTIVE_GOAL_CHARACTERS = 4_000
}

internal data class AgentPhoneDevelopmentManifest(
    val entryFile: String,
    val files: List<AgentPhoneDevelopmentFile>,
    val artifactPaths: List<String>
) {
    val source: String get() = files.firstOrNull { it.path == entryFile }?.content.orEmpty()

    fun runtimeInput(): JSONObject {
        val encodedFiles = files.map { file ->
            mapOf(
                "path" to file.path,
                "data" to Base64.getEncoder().encodeToString(file.content.toByteArray(Charsets.UTF_8))
            )
        }
        val wrappedSource = buildString {
            append("import base64, json, runpy, sys\n")
            append("from pathlib import Path\n")
            append("_signalasi_files = json.loads(")
                .append(JSONObject.quote(JSONArray(encodedFiles).toString())).append(")\n")
            append("for _signalasi_item in _signalasi_files:\n")
            append("    _signalasi_path = Path(_signalasi_item[\"path\"])\n")
            append("    _signalasi_path.parent.mkdir(parents=True, exist_ok=True)\n")
            append("    _signalasi_path.write_bytes(base64.b64decode(_signalasi_item[\"data\"]))\n")
            append("sys.path.insert(0, str(Path.cwd()))\n")
            append("runpy.run_path(").append(JSONObject.quote(entryFile)).append(", run_name=\"__main__\")\n")
        }
        return JSONObject()
            .put("language", AgentRuntimeLanguage.PYTHON.wireValue)
            .put("source", wrappedSource)
            .put("arguments", JSONArray())
            .put("timeout_ms", 180_000L)
            .put("network_enabled", false)
            .put("allowed_network_domains", JSONArray())
            .put("artifact_paths", JSONArray((files.map { it.path } + artifactPaths).distinct()))
    }
}

internal data class AgentPhoneDevelopmentFile(val path: String, val content: String)

internal object AgentPhoneDevelopmentManifestCodec {
    fun parse(raw: String): Result<AgentPhoneDevelopmentManifest> = runCatching {
        val json = JSONObject(extractObject(raw))
        val schema = json.optString("schema")
        require(schema == SCHEMA || schema == LEGACY_SCHEMA) { "The code planner returned an unsupported manifest" }
        require(json.optString("language").equals("python", ignoreCase = true)) {
            "The phone development manifest must use Python"
        }
        val entryFile = json.optString(if (schema == LEGACY_SCHEMA) "file_name" else "entry_file").trim()
        require(SAFE_RELATIVE_PATH.matches(entryFile) && entryFile.endsWith(".py", ignoreCase = true)) {
            "The code planner returned an unsafe Python entry filename"
        }
        val files = if (schema == LEGACY_SCHEMA) {
            listOf(AgentPhoneDevelopmentFile(entryFile, json.optString("source")))
        } else {
            val sourceFiles = json.optJSONArray("files") ?: JSONArray()
            buildList {
                for (index in 0 until sourceFiles.length()) {
                    val item = sourceFiles.optJSONObject(index) ?: continue
                    val path = item.optString("path").trim()
                    val content = item.optString("content")
                    require(SAFE_RELATIVE_PATH.matches(path)) { "The code planner returned an unsafe project path" }
                    require(content.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE_BYTES) {
                        "A generated project file is too large for the phone runtime"
                    }
                    add(AgentPhoneDevelopmentFile(path, content))
                }
            }
        }
        require(files.isNotEmpty() && files.any { it.path == entryFile && it.content.isNotBlank() }) {
            "The code planner did not return the Python entry source"
        }
        require(files.map { it.path }.distinct().size == files.size && files.size <= MAX_PROJECT_FILES) {
            "The generated project contains duplicate or too many files"
        }
        require(files.sumOf { it.content.toByteArray(Charsets.UTF_8).size } <= MAX_PROJECT_SOURCE_BYTES) {
            "The generated project is too large for the phone runtime"
        }
        val artifacts = json.optJSONArray("artifact_paths") ?: JSONArray()
        val artifactPaths = buildList {
            for (index in 0 until artifacts.length()) {
                val path = artifacts.optString(index).trim()
                if (SAFE_RELATIVE_PATH.matches(path) && files.none { it.path == path }) add(path)
            }
        }.distinct().take(MAX_ARTIFACTS)
        AgentPhoneDevelopmentManifest(entryFile, files, artifactPaths)
    }

    private fun extractObject(raw: String): String {
        val clean = raw.trim()
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```").trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        require(start >= 0 && end > start) { "The code planner did not return a JSON manifest" }
        return clean.substring(start, end + 1)
    }

    private const val SCHEMA = "signalasi.phone-development-manifest.v2"
    private const val LEGACY_SCHEMA = "signalasi.phone-development-manifest.v1"
    private const val MAX_SOURCE_BYTES = 128 * 1024
    private const val MAX_PROJECT_SOURCE_BYTES = 512 * 1024
    private const val MAX_PROJECT_FILES = 64
    private const val MAX_ARTIFACTS = 16
    private val SAFE_RELATIVE_PATH = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,159}")
}

internal fun AgentAction.isPhoneDevelopmentRuntimeHandoff(): Boolean =
    kind == AgentActionKind.CALL_NATIVE_TOOL &&
        parameters["tool_id"] == AgentOnDeviceRuntimeTools.EXECUTE &&
        parameters[PHONE_DEVELOPMENT_MANIFEST_PARAMETER] == "true"

internal fun AgentAction.materializePhoneDevelopmentRuntime(sourceResult: String): AgentAction {
    val parsed = AgentPhoneDevelopmentManifestCodec.parse(sourceResult)
    return parsed.fold(
        onSuccess = { manifest ->
            copy(parameters = parameters + mapOf(
                "input_json" to manifest.runtimeInput().toString(),
                PHONE_DEVELOPMENT_FILE_PARAMETER to manifest.entryFile,
                PHONE_DEVELOPMENT_ERROR_PARAMETER to ""
            ))
        },
        onFailure = { error ->
            copy(parameters = parameters + mapOf(
                PHONE_DEVELOPMENT_ERROR_PARAMETER to (error.message ?: "The code manifest is invalid")
            ))
        }
    )
}
