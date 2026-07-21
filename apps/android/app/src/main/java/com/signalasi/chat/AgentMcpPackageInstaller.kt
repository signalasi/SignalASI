package com.signalasi.chat

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

data class AgentMcpDeclarativeTool(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: AgentNativeJsonObject,
    val method: String,
    val pathTemplate: String,
    val headerTemplates: Map<String, String> = emptyMap(),
    val bodyTemplate: String = "",
    val resultJsonPath: String = "",
    val mutating: Boolean = false
)

data class AgentMcpLocalRuntimeSpec(
    val language: AgentRuntimeLanguage,
    val entrypoint: String,
    val arguments: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val allowedNetworkDomains: List<String> = emptyList(),
    val timeoutMillis: Long = 60_000L
)

data class AgentMcpPackageManifest(
    val id: String,
    val version: String,
    val name: String,
    val description: String,
    val catalogId: String = "",
    val endpoint: String,
    val transport: AgentMcpTransportKind,
    val authProfiles: List<AgentMcpAuthProfile>,
    val tools: List<AgentMcpDeclarativeTool>,
    val localRuntime: AgentMcpLocalRuntimeSpec? = null,
    val formatVersion: Int = SUPPORTED_FORMAT_VERSION,
    val author: String = "",
    val website: String = ""
) {
    companion object {
        const val SUPPORTED_FORMAT_VERSION = 1
    }
}

data class AgentMcpPackageInspection(
    val manifest: AgentMcpPackageManifest,
    val rawManifest: String,
    val packageSha256: String,
    val manifestSha256: String,
    val integrityVerified: Boolean,
    val archiveEntries: List<String>,
    val runtimeFiles: Map<String, ByteArray> = emptyMap()
)

class AgentMcpPackageInstaller {
    fun inspect(input: InputStream): AgentMcpPackageInspection {
        val archive = readBounded(input, MAX_PACKAGE_BYTES)
        val packageSha = sha256(archive)
        val files = readArchive(archive)
        val rawManifest = files[MANIFEST_PATH]?.toString(Charsets.UTF_8)
            ?: throw IllegalArgumentException("MCP package is missing $MANIFEST_PATH")
        require(rawManifest.toByteArray().size <= MAX_MANIFEST_BYTES) { "MCP package manifest is too large" }
        val manifestSha = sha256(rawManifest.toByteArray())
        val integrity = files[INTEGRITY_PATH]?.toString(Charsets.UTF_8)
        val verified = integrity?.let { verifyIntegrity(it, manifestSha) } ?: false
        val manifest = AgentMcpPackageManifestCodec.decode(rawManifest)
        val runtimeFiles = files.filterKeys { it.startsWith(RUNTIME_DIRECTORY) }
        if (manifest.transport == AgentMcpTransportKind.LOCAL_STDIO) {
            val entrypoint = requireNotNull(manifest.localRuntime).entrypoint
            require(entrypoint in runtimeFiles) { "Local MCP package is missing its runtime entrypoint: $entrypoint" }
        }
        return AgentMcpPackageInspection(
            manifest = manifest,
            rawManifest = rawManifest,
            packageSha256 = packageSha,
            manifestSha256 = manifestSha,
            integrityVerified = verified,
            archiveEntries = files.keys.sorted(),
            runtimeFiles = runtimeFiles
        )
    }

    private fun readArchive(bytes: ByteArray): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        var extractedBytes = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = normalizeEntryName(entry.name)
                if (!entry.isDirectory) {
                    require(files.size < MAX_ENTRIES) { "MCP package contains too many files" }
                    require(name !in files) { "MCP package contains a duplicate path: $name" }
                    require(isAllowedEntry(name)) { "MCP package contains unsupported executable content: $name" }
                    val maxBytes = if (name == MANIFEST_PATH || name == INTEGRITY_PATH) MAX_MANIFEST_BYTES else MAX_ASSET_BYTES
                    val content = readBounded(zip, maxBytes)
                    extractedBytes += content.size
                    require(extractedBytes <= MAX_EXTRACTED_BYTES) { "MCP package expands beyond the allowed size" }
                    files[name] = content
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return files
    }

    private fun normalizeEntryName(raw: String): String {
        val value = raw.replace('\\', '/').trimStart('/')
        require(value.isNotBlank()) { "MCP package contains an empty path" }
        require(!value.contains("\u0000")) { "MCP package path contains a null character" }
        require(value.split('/').none { it == ".." || it == "." }) { "MCP package path traversal is not allowed" }
        require(!Regex("^[A-Za-z]:").containsMatchIn(value)) { "MCP package path must be relative" }
        return value
    }

    private fun isAllowedEntry(name: String): Boolean {
        if (name == MANIFEST_PATH || name == INTEGRITY_PATH || name == "README.md" || name == "LICENSE") return true
        if (name.startsWith(RUNTIME_DIRECTORY)) {
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return extension in ALLOWED_RUNTIME_EXTENSIONS || name.substringAfterLast('/') in ALLOWED_RUNTIME_FILENAMES
        }
        if (!name.startsWith("assets/")) return false
        return name.substringAfterLast('.', "").lowercase(Locale.ROOT) in ALLOWED_ASSET_EXTENSIONS
    }

    private fun verifyIntegrity(document: String, manifestSha: String): Boolean {
        val json = JSONObject(document)
        val expected = json.optString("manifest_sha256").trim().lowercase(Locale.ROOT)
        require(expected.matches(Regex("[0-9a-f]{64}"))) { "MCP package integrity digest is invalid" }
        require(expected == manifestSha) { "MCP package manifest integrity check failed" }
        return true
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "MCP package content exceeds $maxBytes bytes" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        const val MAX_PACKAGE_BYTES = 8 * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 256 * 1024
        const val MAX_ASSET_BYTES = 2 * 1024 * 1024
        const val MAX_EXTRACTED_BYTES = 12 * 1024 * 1024
        const val MAX_ENTRIES = 64
        const val MANIFEST_PATH = "mcp.json"
        const val INTEGRITY_PATH = "integrity.json"
        const val RUNTIME_DIRECTORY = "runtime/"
        private val ALLOWED_ASSET_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "svg", "txt", "md")
        private val ALLOWED_RUNTIME_EXTENSIONS = setOf(
            "py", "js", "mjs", "cjs", "json", "toml", "yaml", "yml", "txt", "md", "sh", "lock"
        )
        private val ALLOWED_RUNTIME_FILENAMES = setOf("package-lock.json", "uv.lock")

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }
}

object AgentMcpPackageManifestCodec {
    fun decode(document: String): AgentMcpPackageManifest {
        val root = JSONObject(document)
        val formatVersion = root.optInt("format_version", 1)
        require(formatVersion == AgentMcpPackageManifest.SUPPORTED_FORMAT_VERSION) {
            "Unsupported MCP package format version: $formatVersion"
        }
        val id = root.requiredString("id")
        val version = root.requiredString("version")
        val name = root.requiredString("name")
        require(ID_PATTERN.matches(id)) { "Invalid MCP package id: $id" }
        require(VERSION_PATTERN.matches(version)) { "Invalid MCP package version: $version" }
        val transportObject = root.optJSONObject("transport") ?: throw IllegalArgumentException("MCP package transport is required")
        val transport = transportObject.optString("type", AgentMcpTransportKind.STREAMABLE_HTTP.wireValue)
            .let { value -> AgentMcpTransportKind.entries.firstOrNull { it.wireValue == value } }
            ?: throw IllegalArgumentException("Unsupported MCP package transport")
        val localRuntime = if (transport == AgentMcpTransportKind.LOCAL_STDIO) {
            decodeLocalRuntime(transportObject)
        } else {
            null
        }
        val endpoint = if (transport == AgentMcpTransportKind.LOCAL_STDIO) {
            "local-mcp:$id"
        } else {
            AgentMcpEndpointPolicy.normalize(transportObject.requiredString("endpoint"))
        }
        val authProfiles = decodeAuthProfiles(root.optJSONArray("authentication"))
        val tools = decodeTools(root.optJSONArray("tools"), transport)
        if (transport == AgentMcpTransportKind.DECLARATIVE_HTTP) {
            require(tools.isNotEmpty()) { "Declarative MCP package must declare at least one tool" }
        }
        if (transport == AgentMcpTransportKind.LOCAL_STDIO) {
            require(authProfiles.flatMap { it.steps }.none { it.exchange != null } &&
                authProfiles.none { it.refreshExchange != null }) {
                "Local stdio MCP authentication must be handled inside the sandboxed server"
            }
        }
        require(tools.map { it.name }.distinct().size == tools.size) { "MCP package tool names must be unique" }
        return AgentMcpPackageManifest(
            id = id,
            version = version,
            name = name,
            description = root.optString("description").take(MAX_TEXT_CHARS),
            catalogId = root.optString("catalog_id").take(MAX_ID_CHARS),
            endpoint = endpoint,
            transport = transport,
            authProfiles = authProfiles.ifEmpty { listOf(AgentMcpAuthProfile(AgentMcpAuthMethod.NONE)) },
            tools = tools,
            localRuntime = localRuntime,
            formatVersion = formatVersion,
            author = root.optString("author").take(MAX_TEXT_CHARS),
            website = root.optString("website").take(MAX_URL_CHARS)
        )
    }

    fun encode(manifest: AgentMcpPackageManifest): String = JSONObject().apply {
        put("format_version", manifest.formatVersion)
        put("id", manifest.id)
        put("version", manifest.version)
        put("name", manifest.name)
        put("description", manifest.description)
        put("catalog_id", manifest.catalogId)
        put("author", manifest.author)
        put("website", manifest.website)
        put("transport", JSONObject().apply {
            put("type", manifest.transport.wireValue)
            if (manifest.transport == AgentMcpTransportKind.LOCAL_STDIO) {
                requireNotNull(manifest.localRuntime).also { runtime ->
                    put("runtime", runtime.language.wireValue)
                    put("entrypoint", runtime.entrypoint)
                    put("arguments", JSONArray(runtime.arguments))
                    put("environment", JSONObject(runtime.environment))
                    put("allowed_network_domains", JSONArray(runtime.allowedNetworkDomains))
                    put("timeout_ms", runtime.timeoutMillis)
                }
            } else {
                put("endpoint", manifest.endpoint)
            }
        })
        put("authentication", JSONArray().apply { manifest.authProfiles.forEach { put(encodeAuthProfile(it)) } })
        put("tools", JSONArray().apply { manifest.tools.forEach { put(encodeTool(it)) } })
    }.toString()

    private fun decodeAuthProfiles(array: JSONArray?): List<AgentMcpAuthProfile> {
        if (array == null) return emptyList()
        require(array.length() <= MAX_AUTH_PROFILES) { "Too many MCP authentication profiles" }
        return (0 until array.length()).map { index ->
            val raw = array.optJSONObject(index) ?: throw IllegalArgumentException("Invalid MCP authentication profile")
            val method = AgentMcpAuthMethod.fromWireValue(raw.optString("method", "none"))
            val steps = decodeAuthSteps(raw.optJSONArray("steps"))
            AgentMcpAuthProfile(
                method = method,
                steps = steps.ifEmpty { AgentMcpAuthProfile.defaultSteps(method) },
                accessTokenTtlMillis = raw.optLong("access_token_ttl_seconds", 0L).coerceAtLeast(0L) * 1_000L,
                refreshLeadMillis = raw.optLong("refresh_lead_seconds", 300L).coerceAtLeast(0L) * 1_000L,
                supportsRefresh = raw.optBoolean("supports_refresh", false),
                refreshExchange = raw.optJSONObject("refresh_exchange")?.let(::decodeAuthExchange),
                authorizationUrl = raw.optString("authorization_url").take(MAX_URL_CHARS),
                tokenUrl = raw.optString("token_url").take(MAX_URL_CHARS),
                scopes = raw.optJSONArray("scopes").stringList(MAX_SCOPES)
            )
        }.distinctBy { it.method }
    }

    private fun decodeLocalRuntime(raw: JSONObject): AgentMcpLocalRuntimeSpec {
        val language = AgentRuntimeLanguage.entries.firstOrNull {
            it.wireValue == raw.requiredString("runtime").trim().lowercase(Locale.ROOT)
        } ?: throw IllegalArgumentException("Unsupported local MCP runtime")
        require(language in LOCAL_MCP_RUNTIME_LANGUAGES) { "Unsupported local MCP runtime: ${language.wireValue}" }
        val entrypoint = normalizeRuntimePath(raw.requiredString("entrypoint"))
        require(entrypoint.startsWith(AgentMcpPackageInstaller.RUNTIME_DIRECTORY)) {
            "Local MCP entrypoint must be stored under runtime/"
        }
        val arguments = raw.optJSONArray("arguments").stringList(MAX_LOCAL_ARGUMENTS).also { values ->
            require(values.all { it.length <= MAX_LOCAL_ARGUMENT_CHARS && '\u0000' !in it }) {
                "Local MCP runtime argument is invalid"
            }
        }
        val environment = raw.optJSONObject("environment")?.let { values ->
            values.keys().asSequence().associateWith { key ->
                require(ENVIRONMENT_KEY_PATTERN.matches(key)) { "Local MCP environment key is invalid: $key" }
                values.optString(key).also { value ->
                    require(value.length <= MAX_LOCAL_ENVIRONMENT_VALUE_CHARS && '\u0000' !in value) {
                        "Local MCP environment value is invalid"
                    }
                }
            }
        }.orEmpty()
        require(environment.size <= MAX_LOCAL_ENVIRONMENT_VALUES) { "Local MCP environment is too large" }
        val domains = raw.optJSONArray("allowed_network_domains").stringList(MAX_LOCAL_NETWORK_DOMAINS)
            .map { it.trim().lowercase(Locale.ROOT) }
        require(domains.all(DOMAIN_PATTERN::matches)) { "Local MCP network domain is invalid" }
        require(domains.isEmpty()) {
            "Local stdio MCP direct networking is unavailable; use a remote or declarative HTTP transport"
        }
        val timeout = raw.optLong("timeout_ms", 60_000L)
        require(timeout in MIN_LOCAL_TIMEOUT_MILLIS..MAX_LOCAL_TIMEOUT_MILLIS) {
            "Local MCP timeout is outside the allowed range"
        }
        return AgentMcpLocalRuntimeSpec(language, entrypoint, arguments, environment, domains.distinct(), timeout)
    }

    private fun normalizeRuntimePath(raw: String): String {
        val value = raw.replace('\\', '/').trimStart('/')
        require(value.isNotBlank() && value.length <= MAX_LOCAL_ENTRYPOINT_CHARS) { "Local MCP entrypoint is invalid" }
        require(value.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Local MCP entrypoint is unsafe" }
        require(!Regex("^[A-Za-z]:").containsMatchIn(value)) { "Local MCP entrypoint must be relative" }
        return value
    }

    private fun decodeAuthSteps(array: JSONArray?): List<AgentMcpAuthStepSpec> {
        if (array == null) return emptyList()
        require(array.length() <= MAX_AUTH_STEPS) { "Too many MCP authentication steps" }
        return (0 until array.length()).map { index ->
            val raw = array.optJSONObject(index) ?: throw IllegalArgumentException("Invalid MCP authentication step")
            val fieldsArray = raw.optJSONArray("fields") ?: JSONArray()
            require(fieldsArray.length() <= MAX_AUTH_FIELDS) { "Too many MCP authentication fields" }
            val fields = (0 until fieldsArray.length()).map { fieldIndex ->
                val field = fieldsArray.optJSONObject(fieldIndex)
                    ?: throw IllegalArgumentException("Invalid MCP authentication field")
                AgentMcpAuthFieldSpec(
                    id = field.requiredString("id"),
                    label = field.requiredString("label").take(MAX_TEXT_CHARS),
                    type = AgentMcpAuthFieldType.fromWireValue(field.optString("type", "text")),
                    required = field.optBoolean("required", true),
                    secret = field.optBoolean("secret", field.optString("type") in setOf("password", "api_key", "otp", "totp")),
                    placeholder = field.optString("placeholder").take(MAX_TEXT_CHARS),
                    options = field.optJSONArray("options").stringList(MAX_OPTIONS)
                )
            }
            AgentMcpAuthStepSpec(
                id = raw.requiredString("id"),
                title = raw.requiredString("title").take(MAX_TEXT_CHARS),
                description = raw.optString("description").take(MAX_TEXT_CHARS),
                fields = fields,
                expiresInSeconds = raw.optLong("expires_in_seconds", 0L).coerceAtLeast(0L),
                exchange = raw.optJSONObject("exchange")?.let(::decodeAuthExchange)
            )
        }
    }

    private fun decodeAuthExchange(raw: JSONObject): AgentMcpAuthExchangeSpec {
        val method = raw.optString("method", "POST").uppercase(Locale.ROOT)
        val headers = raw.optJSONObject("headers")?.let { objectValue ->
            objectValue.keys().asSequence().associateWith { key -> objectValue.optString(key).take(MAX_TEMPLATE_CHARS) }
        }.orEmpty()
        val mappings = raw.optJSONObject("response_mappings")?.let { objectValue ->
            objectValue.keys().asSequence().associateWith { key -> objectValue.optString(key).take(MAX_TEXT_CHARS) }
        }.orEmpty()
        val statusCodes = raw.optJSONArray("accepted_status_codes")?.let { values ->
            (0 until values.length()).map { values.optInt(it) }.filter { it in 200..299 }.toSet()
        }.orEmpty().ifEmpty { setOf(200) }
        return AgentMcpAuthExchangeSpec(
            method = method,
            pathTemplate = raw.requiredString("path").take(MAX_TEMPLATE_CHARS),
            headerTemplates = headers,
            bodyTemplate = raw.optString("body_template").take(MAX_BODY_TEMPLATE_CHARS),
            responseMappings = mappings,
            acceptedStatusCodes = statusCodes
        )
    }

    private fun decodeTools(array: JSONArray?, transport: AgentMcpTransportKind): List<AgentMcpDeclarativeTool> {
        if (array == null) return emptyList()
        require(array.length() <= MAX_TOOLS) { "MCP package declares too many tools" }
        return (0 until array.length()).map { index ->
            val raw = array.optJSONObject(index) ?: throw IllegalArgumentException("Invalid MCP package tool")
            val request = raw.optJSONObject("request") ?: JSONObject()
            val method = request.optString("method", "POST").uppercase(Locale.ROOT)
            require(method in ALLOWED_METHODS) { "Unsupported declarative MCP method: $method" }
            val path = request.optString("path", "/").trim()
            require(path.startsWith('/') && !path.contains("..") && !path.contains("://")) {
                "Declarative MCP tool path must be an endpoint-relative path"
            }
            if (transport == AgentMcpTransportKind.DECLARATIVE_HTTP) {
                require(request.has("path")) { "Declarative MCP tool request path is required" }
            }
            val headers = request.optJSONObject("headers")?.let { objectValue ->
                objectValue.keys().asSequence().associateWith { key ->
                    objectValue.optString(key).take(MAX_TEMPLATE_CHARS)
                }
            }.orEmpty()
            AgentMcpDeclarativeTool(
                name = raw.requiredString("name"),
                title = raw.optString("title").ifBlank { raw.requiredString("name") }.take(MAX_TEXT_CHARS),
                description = raw.optString("description").take(MAX_TEXT_CHARS),
                inputSchema = raw.optJSONObject("input_schema")?.toNativeMap() ?: emptyMap(),
                method = method,
                pathTemplate = path.take(MAX_TEMPLATE_CHARS),
                headerTemplates = headers,
                bodyTemplate = request.optString("body_template").take(MAX_BODY_TEMPLATE_CHARS),
                resultJsonPath = raw.optString("result_json_path").take(MAX_TEXT_CHARS),
                mutating = raw.optBoolean("mutating", method !in setOf("GET", "HEAD"))
            )
        }
    }

    private fun encodeAuthProfile(profile: AgentMcpAuthProfile): JSONObject = JSONObject().apply {
        put("method", profile.method.wireValue)
        put("access_token_ttl_seconds", profile.accessTokenTtlMillis / 1_000L)
        put("refresh_lead_seconds", profile.refreshLeadMillis / 1_000L)
        put("supports_refresh", profile.supportsRefresh)
        profile.refreshExchange?.let { exchange ->
            put("refresh_exchange", encodeAuthExchange(exchange))
        }
        put("authorization_url", profile.authorizationUrl)
        put("token_url", profile.tokenUrl)
        put("scopes", JSONArray(profile.scopes))
        put("steps", JSONArray().apply {
            profile.steps.forEach { step ->
                put(JSONObject().apply {
                    put("id", step.id)
                    put("title", step.title)
                    put("description", step.description)
                    put("expires_in_seconds", step.expiresInSeconds)
                    step.exchange?.let { exchange ->
                        put("exchange", JSONObject().apply {
                            put("method", exchange.method)
                            put("path", exchange.pathTemplate)
                            put("headers", JSONObject(exchange.headerTemplates))
                            put("body_template", exchange.bodyTemplate)
                            put("response_mappings", JSONObject(exchange.responseMappings))
                            put("accepted_status_codes", JSONArray(exchange.acceptedStatusCodes.sorted()))
                        })
                    }
                    put("fields", JSONArray().apply {
                        step.fields.forEach { field ->
                            put(JSONObject().apply {
                                put("id", field.id)
                                put("label", field.label)
                                put("type", field.type.wireValue)
                                put("required", field.required)
                                put("secret", field.secret)
                                put("placeholder", field.placeholder)
                                put("options", JSONArray(field.options))
                            })
                        }
                    })
                })
            }
        })
    }

    private fun encodeAuthExchange(exchange: AgentMcpAuthExchangeSpec): JSONObject = JSONObject().apply {
        put("method", exchange.method)
        put("path", exchange.pathTemplate)
        put("headers", JSONObject(exchange.headerTemplates))
        put("body_template", exchange.bodyTemplate)
        put("response_mappings", JSONObject(exchange.responseMappings))
        put("accepted_status_codes", JSONArray(exchange.acceptedStatusCodes.sorted()))
    }

    private fun encodeTool(tool: AgentMcpDeclarativeTool): JSONObject = JSONObject().apply {
        put("name", tool.name)
        put("title", tool.title)
        put("description", tool.description)
        put("input_schema", JSONObject(tool.inputSchema))
        put("result_json_path", tool.resultJsonPath)
        put("mutating", tool.mutating)
        put("request", JSONObject().apply {
            put("method", tool.method)
            put("path", tool.pathTemplate)
            put("headers", JSONObject(tool.headerTemplates))
            put("body_template", tool.bodyTemplate)
        })
    }

    private fun JSONObject.requiredString(name: String): String = optString(name).trim().takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("MCP package field '$name' is required")

    private fun JSONArray?.stringList(limit: Int): List<String> {
        if (this == null) return emptyList()
        require(length() <= limit) { "MCP package list is too large" }
        return (0 until length()).mapNotNull { optString(it).trim().takeIf(String::isNotBlank) }
    }

    private fun JSONObject.toNativeMap(): AgentNativeJsonObject = keys().asSequence().associateWith { key ->
        toNativeValue(opt(key))
    }

    private fun toNativeValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.toNativeMap()
        is JSONArray -> (0 until value.length()).map { toNativeValue(value.opt(it)) }
        is String, is Boolean, is Number -> value
        else -> value.toString()
    }

    private const val MAX_ID_CHARS = 128
    private const val MAX_TEXT_CHARS = 512
    private const val MAX_URL_CHARS = 2_048
    private const val MAX_TEMPLATE_CHARS = 4_096
    private const val MAX_BODY_TEMPLATE_CHARS = 64 * 1024
    private const val MAX_AUTH_PROFILES = 8
    private const val MAX_AUTH_STEPS = 8
    private const val MAX_AUTH_FIELDS = 24
    private const val MAX_OPTIONS = 64
    private const val MAX_SCOPES = 64
    private const val MAX_TOOLS = 128
    private const val MAX_LOCAL_ARGUMENTS = 32
    private const val MAX_LOCAL_ARGUMENT_CHARS = 2_048
    private const val MAX_LOCAL_ENVIRONMENT_VALUES = 32
    private const val MAX_LOCAL_ENVIRONMENT_VALUE_CHARS = 4_096
    private const val MAX_LOCAL_NETWORK_DOMAINS = 32
    private const val MAX_LOCAL_ENTRYPOINT_CHARS = 512
    private const val MIN_LOCAL_TIMEOUT_MILLIS = 5_000L
    private const val MAX_LOCAL_TIMEOUT_MILLIS = 180_000L
    private val ID_PATTERN = Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+")
    private val VERSION_PATTERN = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?")
    private val ALLOWED_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
    private val LOCAL_MCP_RUNTIME_LANGUAGES = setOf(
        AgentRuntimeLanguage.SHELL,
        AgentRuntimeLanguage.PYTHON,
        AgentRuntimeLanguage.JAVASCRIPT,
        AgentRuntimeLanguage.TYPESCRIPT
    )
    private val ENVIRONMENT_KEY_PATTERN = Regex("[A-Z_][A-Z0-9_]{0,63}")
    private val DOMAIN_PATTERN = Regex("(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
}

class AgentMcpPackageRepository(
    context: Context,
    preferencesName: String = PREFERENCES_NAME
) {
    private val appContext = context.applicationContext
    private val preferences = AgentEncryptedPreferences(appContext, preferencesName)
    private val packagesRoot = File(appContext.filesDir, PACKAGES_DIRECTORY)
    private val runtimeProjectsRoot = File(appContext.filesDir, RUNTIME_PROJECTS_DIRECTORY)

    @Synchronized
    fun save(inspection: AgentMcpPackageInspection) {
        persistPackageFiles(inspection)
        preferences.writeString(key(inspection.manifest.id), inspection.rawManifest)
    }

    fun get(id: String): AgentMcpPackageManifest? = preferences.readString(key(id), "")
        .takeIf(String::isNotBlank)
        ?.let { runCatching { AgentMcpPackageManifestCodec.decode(it) }.getOrNull() }

    @Synchronized
    fun prepareLocalInvocation(id: String, payload: String): AgentMcpLocalInvocation {
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_INVOCATION_BYTES) { "Local MCP invocation is too large" }
        val sourceRuntime = File(packageDirectory(id), AgentMcpPackageInstaller.RUNTIME_DIRECTORY)
        check(sourceRuntime.isDirectory) { "Local MCP runtime files are not installed" }
        val workspaceId = localWorkspaceId(id)
        val workspace = safeChild(runtimeProjectsRoot, workspaceId) ?: error("Local MCP workspace path is invalid")
        check(workspace.mkdirs() || workspace.isDirectory) { "Local MCP workspace is unavailable" }
        replaceDirectory(sourceRuntime, File(workspace, "runtime"))
        val control = File(workspace, CONTROL_DIRECTORY)
        check(control.mkdirs() || control.isDirectory) { "Local MCP control directory is unavailable" }
        control.listFiles().orEmpty().filter { it.name.startsWith("request-") }.forEach(File::delete)
        val request = File(control, "request-${UUID.randomUUID()}.json")
        request.writeText(payload, Charsets.UTF_8)
        return AgentMcpLocalInvocation(workspaceId, "$CONTROL_DIRECTORY/${request.name}")
    }

    @Synchronized
    fun completeLocalInvocation(invocation: AgentMcpLocalInvocation) {
        safeChild(File(runtimeProjectsRoot, invocation.workspaceId), invocation.requestPath)?.delete()
    }

    @Synchronized
    fun delete(id: String) {
        preferences.remove(key(id))
        packageDirectory(id).deleteRecursively()
        safeChild(runtimeProjectsRoot, localWorkspaceId(id))?.deleteRecursively()
    }

    @Synchronized
    fun clear() {
        preferences.clear()
        packagesRoot.deleteRecursively()
        runtimeProjectsRoot.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith(WORKSPACE_PREFIX) }
            .forEach(File::deleteRecursively)
    }

    private fun persistPackageFiles(inspection: AgentMcpPackageInspection) {
        val target = packageDirectory(inspection.manifest.id)
        val parent = target.parentFile ?: error("MCP package storage is invalid")
        check(parent.mkdirs() || parent.isDirectory) { "MCP package storage is unavailable" }
        val staging = File(parent, ".${target.name}.${UUID.randomUUID()}.staging")
        val backup = File(parent, ".${target.name}.${UUID.randomUUID()}.backup")
        staging.deleteRecursively()
        backup.deleteRecursively()
        check(staging.mkdirs()) { "MCP package staging directory could not be created" }
        try {
            File(staging, AgentMcpPackageInstaller.MANIFEST_PATH).writeText(inspection.rawManifest, Charsets.UTF_8)
            var totalBytes = 0L
            inspection.runtimeFiles.forEach { (relative, bytes) ->
                require(relative.startsWith(AgentMcpPackageInstaller.RUNTIME_DIRECTORY)) { "MCP runtime path is invalid" }
                totalBytes += bytes.size
                require(totalBytes <= AgentMcpPackageInstaller.MAX_EXTRACTED_BYTES) { "MCP runtime files exceed the package limit" }
                val output = safeChild(staging, relative) ?: error("MCP runtime path is unsafe")
                check(output.parentFile?.mkdirs() != false || output.parentFile?.isDirectory == true) {
                    "MCP runtime directory could not be created"
                }
                output.writeBytes(bytes)
            }
            if (target.exists()) check(target.renameTo(backup)) { "Previous MCP package could not be backed up" }
            if (!staging.renameTo(target)) {
                target.deleteRecursively()
                if (backup.exists()) backup.renameTo(target)
                error("MCP package could not be committed")
            }
            backup.deleteRecursively()
        } catch (error: Throwable) {
            staging.deleteRecursively()
            if (!target.exists() && backup.exists()) backup.renameTo(target)
            throw error
        }
    }

    private fun replaceDirectory(source: File, target: File) {
        val parent = target.parentFile ?: error("Local MCP runtime storage is invalid")
        val staging = File(parent, ".${target.name}.${UUID.randomUUID()}.staging")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Local MCP runtime staging directory could not be created" }
        source.walkTopDown().forEach { input ->
            val relative = input.relativeTo(source).path
            if (relative.isBlank()) return@forEach
            val output = safeChild(staging, relative) ?: error("Local MCP runtime path is unsafe")
            if (input.isDirectory) {
                check(output.mkdirs() || output.isDirectory) { "Local MCP runtime directory could not be created" }
            } else {
                check(input.isFile && input.length() <= AgentMcpPackageInstaller.MAX_ASSET_BYTES) {
                    "Local MCP runtime file is invalid"
                }
                check(output.parentFile?.mkdirs() != false || output.parentFile?.isDirectory == true) {
                    "Local MCP runtime directory could not be created"
                }
                input.copyTo(output, overwrite = true)
            }
        }
        target.deleteRecursively()
        check(staging.renameTo(target)) { "Local MCP runtime files could not be activated" }
    }

    private fun packageDirectory(id: String): File = File(packagesRoot, encodedId(id))

    private fun localWorkspaceId(id: String): String = WORKSPACE_PREFIX + AgentMcpPackageInstaller
        .sha256(id.toByteArray(Charsets.UTF_8))
        .take(32)

    private fun safeChild(parent: File, relative: String): File? {
        if (relative.isBlank() || File(relative).isAbsolute) return null
        val canonicalParent = parent.canonicalFile
        val candidate = File(canonicalParent, relative).canonicalFile
        return candidate.takeIf { it.path.startsWith(canonicalParent.path + File.separator) }
    }

    private fun encodedId(id: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray())

    private fun key(id: String): String = "package_${encodedId(id)}"

    companion object {
        const val PREFERENCES_NAME = "signalasi_mcp_packages"
        private const val PACKAGES_DIRECTORY = "agent-mcp-packages"
        private const val RUNTIME_PROJECTS_DIRECTORY = "agent-native-workspaces"
        private const val CONTROL_DIRECTORY = ".signalasi-mcp"
        private const val WORKSPACE_PREFIX = "mcp-"
        private const val MAX_INVOCATION_BYTES = 512 * 1024
    }
}

data class AgentMcpLocalInvocation(
    val workspaceId: String,
    val requestPath: String
)
