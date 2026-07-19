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
        append("Schema: {\"schema\":\"signalasi.phone-development-manifest.v1\",")
        append("\"language\":\"python\",\"file_name\":\"meaningful_name.py\",")
        append("\"source\":\"complete Python source\",\"artifact_paths\":[]}. ")
        append("The source must be self-contained, deterministic, print its useful result, verify the requested behavior ")
        append("with assertions or equivalent checks, and exit non-zero if verification fails. ")
        append("Do not use network access or external packages unless the user explicitly requires them. ")
        append("Keep file_name to one safe relative Python filename. ")
        append("User goal: ").append(goal.trim().take(4_000))
    }

    private const val MAX_INTERACTIVE_GOAL_CHARACTERS = 4_000
}

internal data class AgentPhoneDevelopmentManifest(
    val fileName: String,
    val source: String,
    val artifactPaths: List<String>
) {
    fun runtimeInput(): JSONObject {
        val encodedSource = Base64.getEncoder().encodeToString(source.toByteArray(Charsets.UTF_8))
        val wrappedSource = buildString {
            append("import base64\n")
            append("from pathlib import Path\n")
            append("_signalasi_source = base64.b64decode(\"").append(encodedSource)
                .append("\").decode(\"utf-8\")\n")
            append("_signalasi_file = Path(").append(JSONObject.quote(fileName)).append(")\n")
            append("_signalasi_file.write_text(_signalasi_source, encoding=\"utf-8\")\n")
            append("_signalasi_globals = {\"__name__\": \"__main__\", \"__file__\": str(_signalasi_file)}\n")
            append("exec(compile(_signalasi_source, str(_signalasi_file), \"exec\"), _signalasi_globals)\n")
        }
        return JSONObject()
            .put("language", AgentRuntimeLanguage.PYTHON.wireValue)
            .put("source", wrappedSource)
            .put("arguments", JSONArray())
            .put("timeout_ms", 180_000L)
            .put("network_enabled", false)
            .put("allowed_network_domains", JSONArray())
            .put("artifact_paths", JSONArray((listOf(fileName) + artifactPaths).distinct()))
    }
}

internal object AgentPhoneDevelopmentManifestCodec {
    fun parse(raw: String): Result<AgentPhoneDevelopmentManifest> = runCatching {
        val json = JSONObject(extractObject(raw))
        require(json.optString("schema") == SCHEMA) { "The code planner returned an unsupported manifest" }
        require(json.optString("language").equals("python", ignoreCase = true)) {
            "The phone development manifest must use Python"
        }
        val fileName = json.optString("file_name").trim()
        require(FILE_NAME_PATTERN.matches(fileName) && fileName.endsWith(".py", ignoreCase = true)) {
            "The code planner returned an unsafe Python filename"
        }
        val source = json.optString("source")
        require(source.isNotBlank()) { "The code planner did not return Python source" }
        require(source.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE_BYTES) {
            "The generated Python source is too large for the phone runtime"
        }
        val artifacts = json.optJSONArray("artifact_paths") ?: JSONArray()
        val artifactPaths = buildList {
            for (index in 0 until artifacts.length()) {
                val path = artifacts.optString(index).trim()
                if (SAFE_RELATIVE_PATH.matches(path) && path != fileName) add(path)
            }
        }.distinct().take(MAX_ARTIFACTS)
        AgentPhoneDevelopmentManifest(fileName, source, artifactPaths)
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

    private const val SCHEMA = "signalasi.phone-development-manifest.v1"
    private const val MAX_SOURCE_BYTES = 128 * 1024
    private const val MAX_ARTIFACTS = 16
    private val FILE_NAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
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
                PHONE_DEVELOPMENT_FILE_PARAMETER to manifest.fileName,
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
