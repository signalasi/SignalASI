package com.signalasi.chat

import android.content.Context
import java.nio.charset.StandardCharsets
import java.util.PriorityQueue

enum class AgentSkillParameterType(val wireValue: String) {
    OBJECT("object"),
    ARRAY("array"),
    STRING("string"),
    INTEGER("integer"),
    NUMBER("number"),
    BOOLEAN("boolean")
}

data class AgentSkillParameterSchema(
    val type: AgentSkillParameterType = AgentSkillParameterType.OBJECT,
    val properties: Map<String, AgentSkillParameterSchema> = emptyMap(),
    val required: Set<String> = emptySet(),
    val additionalProperties: Boolean = false,
    val items: AgentSkillParameterSchema? = null,
    val enumValues: List<Any?> = emptyList(),
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val minItems: Int? = null,
    val maxItems: Int? = null
) {
    companion object {
        fun objectSchema(
            properties: Map<String, AgentSkillParameterSchema> = emptyMap(),
            required: Set<String> = emptySet(),
            additionalProperties: Boolean = false
        ) = AgentSkillParameterSchema(
            type = AgentSkillParameterType.OBJECT,
            properties = properties,
            required = required,
            additionalProperties = additionalProperties
        )

        fun array(
            items: AgentSkillParameterSchema,
            minItems: Int? = null,
            maxItems: Int? = null
        ) = AgentSkillParameterSchema(
            type = AgentSkillParameterType.ARRAY,
            items = items,
            minItems = minItems,
            maxItems = maxItems
        )

        fun string(
            enumValues: List<String> = emptyList(),
            minLength: Int? = null,
            maxLength: Int? = null
        ) = AgentSkillParameterSchema(
            type = AgentSkillParameterType.STRING,
            enumValues = enumValues,
            minLength = minLength,
            maxLength = maxLength
        )

        fun integer(minimum: Long? = null, maximum: Long? = null) = AgentSkillParameterSchema(
            type = AgentSkillParameterType.INTEGER,
            minimum = minimum?.toDouble(),
            maximum = maximum?.toDouble()
        )

        fun number(minimum: Double? = null, maximum: Double? = null) = AgentSkillParameterSchema(
            type = AgentSkillParameterType.NUMBER,
            minimum = minimum,
            maximum = maximum
        )

        fun boolean() = AgentSkillParameterSchema(type = AgentSkillParameterType.BOOLEAN)
    }
}

data class AgentSkillResource(
    val id: String,
    val path: String,
    val mimeType: String = "application/octet-stream",
    val maxBytes: Long = AgentSkillLimits.DEFAULT_RESOURCE_MAX_BYTES
)

data class AgentSkillStep(
    val id: String,
    val toolId: String,
    val input: Map<String, Any?> = emptyMap(),
    val dependsOn: List<String> = emptyList()
)

data class AgentSkillManifest(
    val id: String,
    val version: String,
    val title: String,
    val instructions: String,
    val nativeTools: Set<String>,
    val permissions: Set<String> = emptySet(),
    val resources: List<AgentSkillResource> = emptyList(),
    val parameters: AgentSkillParameterSchema = AgentSkillParameterSchema.objectSchema(),
    val steps: List<AgentSkillStep>,
    val formatVersion: Int = AgentSkillLimits.SUPPORTED_FORMAT_VERSION
) {
    val nativeToolIds: Set<String> get() = nativeTools
}

data class AgentSkillInstallation(
    val manifest: AgentSkillManifest,
    val enabled: Boolean = true,
    val installedAtMillis: Long = 0L,
    val updatedAtMillis: Long = installedAtMillis
) {
    val id: String get() = manifest.id
    val version: String get() = manifest.version
}

data class AgentSkillValidationIssue(
    val path: String,
    val code: String,
    val message: String
)

data class AgentSkillValidationResult(val issues: List<AgentSkillValidationIssue> = emptyList()) {
    val isValid: Boolean get() = issues.isEmpty()

    companion object {
        val VALID = AgentSkillValidationResult()
    }
}

class AgentSkillValidationException(val result: AgentSkillValidationResult) : IllegalArgumentException(
    result.issues.joinToString(prefix = "Invalid Agent Skill manifest: ", separator = "; ") {
        "${it.path} [${it.code}] ${it.message}"
    }
)

class AgentSkillConflictException(id: String, version: String) : IllegalStateException(
    "Agent Skill $id@$version is already installed with different content; publish a new version"
)

data class AgentSkillExpandedStep(
    val id: String,
    val toolId: String,
    val input: Map<String, Any?>,
    val dependsOn: List<String>
)

data class AgentSkillExpansion(
    val skillId: String,
    val skillVersion: String,
    val title: String,
    val instructions: String,
    val permissions: Set<String>,
    val resources: Map<String, AgentSkillResource>,
    val steps: List<AgentSkillExpandedStep>
) {
    val orderedSteps: List<AgentSkillExpandedStep> get() = steps
}

object AgentSkillLimits {
    const val SUPPORTED_FORMAT_VERSION = 1
    const val MAX_MANIFEST_BYTES = 256 * 1024
    const val MAX_STORE_BYTES = 4 * 1024 * 1024
    const val MAX_INSTALLED_SKILLS = 128
    const val MAX_ID_CHARS = 128
    const val MAX_VERSION_CHARS = 64
    const val MAX_TITLE_CHARS = 160
    const val MAX_INSTRUCTIONS_CHARS = 32 * 1024
    const val MAX_NATIVE_TOOLS = 64
    const val MAX_PERMISSIONS = 64
    const val MAX_RESOURCES = 64
    const val MAX_STEPS = 128
    const val MAX_SCHEMA_DEPTH = 12
    const val MAX_SCHEMA_PROPERTIES = 128
    const val MAX_INPUT_DEPTH = 16
    const val MAX_JSON_DEPTH = 32
    const val MAX_STEP_INPUT_BYTES = 64 * 1024
    const val MAX_TEMPLATE_CHARS = 8 * 1024
    const val MAX_TEMPLATE_REFERENCES = 64
    const val MAX_RESOURCE_PATH_CHARS = 512
    const val MAX_MIME_TYPE_CHARS = 160
    const val DEFAULT_RESOURCE_MAX_BYTES = 1024L * 1024L
    const val MAX_RESOURCE_BYTES = 32L * 1024L * 1024L
}

interface AgentSkillStore {
    fun list(): List<AgentSkillInstallation>

    fun find(id: String, version: String): AgentSkillInstallation? =
        list().firstOrNull { it.id == id && it.version == version }

    fun upsert(installation: AgentSkillInstallation)
    fun delete(id: String, version: String): Boolean
    fun clear()
}

class InMemoryAgentSkillStore(
    initialSkills: List<AgentSkillInstallation> = emptyList()
) : AgentSkillStore {
    private var document = AgentSkillStoreCodec.encode(initialSkills)

    @Synchronized
    override fun list(): List<AgentSkillInstallation> = AgentSkillStoreCodec.decode(document)

    @Synchronized
    override fun upsert(installation: AgentSkillInstallation) {
        val current = list().filterNot { it.id == installation.id && it.version == installation.version }
        document = AgentSkillStoreCodec.encode(current + installation)
    }

    @Synchronized
    override fun delete(id: String, version: String): Boolean {
        val current = list()
        val remaining = current.filterNot { it.id == id.trim() && it.version == version.trim() }
        if (remaining.size == current.size) return false
        document = AgentSkillStoreCodec.encode(remaining)
        return true
    }

    @Synchronized
    override fun clear() {
        document = AgentSkillStoreCodec.emptyDocument()
    }

    @Synchronized
    fun serializedSnapshot(): String = document
}

class EncryptedAgentSkillStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME
) : AgentSkillStore {
    private val preferences = AgentEncryptedPreferences(context.applicationContext, preferencesName)

    @Synchronized
    override fun list(): List<AgentSkillInstallation> = AgentSkillStoreCodec.decode(
        preferences.readString(KEY_SKILLS, AgentSkillStoreCodec.emptyDocument())
    )

    @Synchronized
    override fun upsert(installation: AgentSkillInstallation) {
        val current = list().filterNot { it.id == installation.id && it.version == installation.version }
        preferences.writeString(KEY_SKILLS, AgentSkillStoreCodec.encode(current + installation))
    }

    @Synchronized
    override fun delete(id: String, version: String): Boolean {
        val current = list()
        val remaining = current.filterNot { it.id == id.trim() && it.version == version.trim() }
        if (remaining.size == current.size) return false
        preferences.writeString(KEY_SKILLS, AgentSkillStoreCodec.encode(remaining))
        return true
    }

    @Synchronized
    override fun clear() {
        preferences.remove(KEY_SKILLS)
    }

    companion object {
        const val PREFERENCES_NAME = "signalasi_agent_skills"
        const val KEY_SKILLS = "installed_skills"
    }
}

typealias AndroidEncryptedAgentSkillStore = EncryptedAgentSkillStore

class AgentSkillRuntime(
    private val store: AgentSkillStore = InMemoryAgentSkillStore(),
    availableNativeToolIds: Collection<String>? = null,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val availableTools = availableNativeToolIds?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()

    fun validate(manifest: AgentSkillManifest): AgentSkillValidationResult =
        AgentSkillManifestValidator.validate(manifest, availableTools)

    fun validate(rawManifest: String): AgentSkillValidationResult {
        if (rawManifest.toByteArray(StandardCharsets.UTF_8).size > AgentSkillLimits.MAX_MANIFEST_BYTES) {
            return AgentSkillValidationResult(
                listOf(issue("$", "oversized_manifest", "Manifest exceeds ${AgentSkillLimits.MAX_MANIFEST_BYTES} bytes"))
            )
        }
        val manifest = AgentSkillManifestCodec.decode(rawManifest)
            ?: return AgentSkillValidationResult(listOf(issue("$", "malformed_manifest", "Manifest JSON is invalid")))
        return validate(manifest)
    }

    @Synchronized
    fun install(manifest: AgentSkillManifest, enabled: Boolean = true): AgentSkillInstallation {
        validate(manifest).throwIfInvalid()
        val existing = store.find(manifest.id, manifest.version)
        if (existing != null) {
            if (AgentSkillManifestCodec.encode(existing.manifest) != AgentSkillManifestCodec.encode(manifest)) {
                throw AgentSkillConflictException(manifest.id, manifest.version)
            }
            return existing
        }
        require(store.list().size < AgentSkillLimits.MAX_INSTALLED_SKILLS) {
            "Agent Skill store is full"
        }
        val now = now()
        return AgentSkillInstallation(manifest, enabled, now, now).also(store::upsert)
    }

    fun install(rawManifest: String, enabled: Boolean = true): AgentSkillInstallation {
        validate(rawManifest).throwIfInvalid()
        return install(requireNotNull(AgentSkillManifestCodec.decode(rawManifest)), enabled)
    }

    @Synchronized
    fun enable(id: String, version: String): AgentSkillInstallation = setEnabled(id, version, true)

    @Synchronized
    fun disable(id: String, version: String): AgentSkillInstallation = setEnabled(id, version, false)

    @Synchronized
    fun delete(id: String, version: String): Boolean = store.delete(id.trim(), version.trim())

    fun list(enabledOnly: Boolean = false): List<AgentSkillInstallation> = store.list()
        .asSequence()
        .filter { !enabledOnly || it.enabled }
        .sortedWith(compareBy<AgentSkillInstallation> { it.id }.thenBy { it.version })
        .toList()

    fun get(id: String, version: String): AgentSkillInstallation? = store.find(id.trim(), version.trim())

    fun expand(
        id: String,
        version: String,
        parameters: Map<String, Any?> = emptyMap()
    ): AgentSkillExpansion {
        val installation = get(id, version)
            ?: throw NoSuchElementException("Agent Skill ${id.trim()}@${version.trim()} is not installed")
        check(installation.enabled) { "Agent Skill ${installation.id}@${installation.version} is disabled" }
        return expand(installation.manifest, parameters)
    }

    fun expand(
        manifest: AgentSkillManifest,
        parameters: Map<String, Any?> = emptyMap()
    ): AgentSkillExpansion {
        validate(manifest).throwIfInvalid()
        val parameterIssues = AgentSkillManifestValidator.validateParameters(manifest.parameters, parameters)
        AgentSkillValidationResult(parameterIssues).throwIfInvalid()

        val resources = manifest.resources.associateBy { it.id }
        val ordered = AgentSkillManifestValidator.topologicalSteps(manifest.steps)
        val expanded = ordered.map { step ->
            val resolved = AgentSkillTemplateExpander.expand(step.input, parameters, resources)
            val bytes = SkillJson.stringify(resolved).toByteArray(StandardCharsets.UTF_8).size
            if (bytes > AgentSkillLimits.MAX_STEP_INPUT_BYTES) {
                throw AgentSkillValidationException(
                    AgentSkillValidationResult(
                        listOf(issue("$.steps.${step.id}.input", "oversized_input", "Expanded input exceeds the byte limit"))
                    )
                )
            }
            AgentSkillExpandedStep(step.id, step.toolId, resolved, step.dependsOn.toList())
        }
        return AgentSkillExpansion(
            skillId = manifest.id,
            skillVersion = manifest.version,
            title = manifest.title,
            instructions = manifest.instructions,
            permissions = manifest.permissions.toSet(),
            resources = resources,
            steps = expanded
        )
    }

    @Synchronized
    private fun setEnabled(id: String, version: String, enabled: Boolean): AgentSkillInstallation {
        val current = get(id, version)
            ?: throw NoSuchElementException("Agent Skill ${id.trim()}@${version.trim()} is not installed")
        if (current.enabled == enabled) return current
        return current.copy(enabled = enabled, updatedAtMillis = maxOf(current.installedAtMillis, now()))
            .also(store::upsert)
    }

    private fun now(): Long = clock().coerceAtLeast(0L)
}

object AgentSkillManifestValidator {
    private val STABLE_ID = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]*")
    private val STABLE_VERSION = Regex("[a-zA-Z0-9][a-zA-Z0-9._+-]*")
    private val DECLARATION_ID = Regex("[a-zA-Z0-9][a-zA-Z0-9._:-]*")

    fun validate(
        manifest: AgentSkillManifest,
        availableNativeToolIds: Set<String>? = null
    ): AgentSkillValidationResult {
        val issues = mutableListOf<AgentSkillValidationIssue>()
        if (manifest.formatVersion != AgentSkillLimits.SUPPORTED_FORMAT_VERSION) {
            issues += issue("$.format_version", "unsupported_format", "Only format version 1 is supported")
        }
        stableToken(manifest.id, AgentSkillLimits.MAX_ID_CHARS, STABLE_ID, "$.id", "skill id", issues)
        stableToken(
            manifest.version,
            AgentSkillLimits.MAX_VERSION_CHARS,
            STABLE_VERSION,
            "$.version",
            "skill version",
            issues
        )
        boundedText(manifest.title, AgentSkillLimits.MAX_TITLE_CHARS, "$.title", "title", issues)
        boundedText(
            manifest.instructions,
            AgentSkillLimits.MAX_INSTRUCTIONS_CHARS,
            "$.instructions",
            "instructions",
            issues
        )
        if (manifest.nativeTools.size > AgentSkillLimits.MAX_NATIVE_TOOLS) {
            issues += issue("$.native_tools", "too_many_tools", "Too many native tool declarations")
        }
        manifest.nativeTools.forEach { tool ->
            stableToken(tool, AgentSkillLimits.MAX_ID_CHARS, DECLARATION_ID, "$.native_tools", "tool id", issues)
            if (availableNativeToolIds != null && tool !in availableNativeToolIds) {
                issues += issue("$.native_tools.$tool", "unknown_tool", "Native tool is not available")
            }
        }
        if (manifest.permissions.size > AgentSkillLimits.MAX_PERMISSIONS) {
            issues += issue("$.permissions", "too_many_permissions", "Too many permission declarations")
        }
        manifest.permissions.forEach { permission ->
            stableToken(
                permission,
                AgentSkillLimits.MAX_ID_CHARS,
                DECLARATION_ID,
                "$.permissions",
                "permission id",
                issues
            )
        }
        validateResources(manifest.resources, issues)
        validateSchema(manifest.parameters, "$.parameters", 0, issues)
        if (manifest.parameters.type != AgentSkillParameterType.OBJECT) {
            issues += issue("$.parameters", "root_schema_type", "Skill parameters must use an object schema")
        }
        validateSteps(manifest, issues)

        if (SkillJson.isCompatibleManifest(manifest)) {
            val encodedSize = AgentSkillManifestCodec.encode(manifest).toByteArray(StandardCharsets.UTF_8).size
            if (encodedSize > AgentSkillLimits.MAX_MANIFEST_BYTES) {
                issues += issue("$", "oversized_manifest", "Manifest exceeds ${AgentSkillLimits.MAX_MANIFEST_BYTES} bytes")
            }
        } else {
            issues += issue("$", "invalid_json_value", "Manifest contains a value JSON cannot represent")
        }
        return AgentSkillValidationResult(issues.distinct())
    }

    fun validateParameters(
        schema: AgentSkillParameterSchema,
        parameters: Map<String, Any?>
    ): List<AgentSkillValidationIssue> {
        val issues = mutableListOf<AgentSkillValidationIssue>()
        if (!SkillJson.isCompatible(parameters)) {
            issues += issue("$.parameters", "invalid_json_value", "Parameters contain a value JSON cannot represent")
            return issues
        }
        validateValue(schema, parameters, "$.parameters", issues, 0)
        val size = SkillJson.stringify(parameters).toByteArray(StandardCharsets.UTF_8).size
        if (size > AgentSkillLimits.MAX_STEP_INPUT_BYTES) {
            issues += issue("$.parameters", "oversized_parameters", "Parameters exceed the byte limit")
        }
        return issues
    }

    internal fun topologicalSteps(steps: List<AgentSkillStep>): List<AgentSkillStep> {
        if (steps.isEmpty()) return emptyList()
        val byId = steps.associateBy { it.id }
        val index = steps.mapIndexed { position, step -> step.id to position }.toMap()
        val indegree = steps.associate { it.id to it.dependsOn.distinct().count(byId::containsKey) }.toMutableMap()
        val dependants = mutableMapOf<String, MutableList<String>>()
        steps.forEach { step ->
            step.dependsOn.distinct().forEach { dependency ->
                if (dependency in byId) dependants.getOrPut(dependency) { mutableListOf() } += step.id
            }
        }
        val ready = PriorityQueue<String>(compareBy { index[it] ?: Int.MAX_VALUE })
        indegree.filterValues { it == 0 }.keys.forEach(ready::add)
        val ordered = mutableListOf<AgentSkillStep>()
        while (ready.isNotEmpty()) {
            val id = ready.remove()
            ordered += requireNotNull(byId[id])
            dependants[id].orEmpty().forEach { dependant ->
                val remaining = requireNotNull(indegree[dependant]) - 1
                indegree[dependant] = remaining
                if (remaining == 0) ready += dependant
            }
        }
        return ordered
    }

    private fun validateResources(
        resources: List<AgentSkillResource>,
        issues: MutableList<AgentSkillValidationIssue>
    ) {
        if (resources.size > AgentSkillLimits.MAX_RESOURCES) {
            issues += issue("$.resources", "too_many_resources", "Too many resource declarations")
        }
        duplicateValues(resources.map { it.id }).forEach { id ->
            issues += issue("$.resources.$id", "duplicate_resource", "Resource ids must be unique")
        }
        resources.forEachIndexed { index, resource ->
            val path = "$.resources[$index]"
            stableToken(resource.id, AgentSkillLimits.MAX_ID_CHARS, STABLE_ID, "$path.id", "resource id", issues)
            if (!isSafeRelativeResourcePath(resource.path)) {
                issues += issue("$path.path", "path_traversal", "Resource path must be a canonical relative path")
            }
            if (resource.mimeType.isBlank() || resource.mimeType.length > AgentSkillLimits.MAX_MIME_TYPE_CHARS) {
                issues += issue("$path.mime_type", "invalid_mime_type", "Resource MIME type is invalid")
            }
            if (resource.maxBytes !in 1..AgentSkillLimits.MAX_RESOURCE_BYTES) {
                issues += issue("$path.max_bytes", "invalid_resource_bound", "Resource byte bound is invalid")
            }
        }
    }

    private fun validateSteps(
        manifest: AgentSkillManifest,
        issues: MutableList<AgentSkillValidationIssue>
    ) {
        if (manifest.steps.isEmpty()) {
            issues += issue("$.steps", "empty_steps", "A Skill must declare at least one step")
            return
        }
        if (manifest.steps.size > AgentSkillLimits.MAX_STEPS) {
            issues += issue("$.steps", "too_many_steps", "Too many Skill steps")
        }
        val ids = manifest.steps.map { it.id }
        val idSet = ids.toSet()
        duplicateValues(ids).forEach { id ->
            issues += issue("$.steps.$id", "duplicate_step", "Step ids must be unique")
        }
        manifest.steps.forEachIndexed { index, step ->
            val path = "$.steps[$index]"
            stableToken(step.id, AgentSkillLimits.MAX_ID_CHARS, STABLE_ID, "$path.id", "step id", issues)
            stableToken(step.toolId, AgentSkillLimits.MAX_ID_CHARS, DECLARATION_ID, "$path.tool_id", "tool id", issues)
            if (step.toolId !in manifest.nativeTools) {
                issues += issue("$path.tool_id", "undeclared_tool", "Step tool must be declared in native_tools")
            }
            duplicateValues(step.dependsOn).forEach { dependency ->
                issues += issue("$path.depends_on", "duplicate_dependency", "Dependency $dependency is repeated")
            }
            step.dependsOn.forEach { dependency ->
                when {
                    dependency == step.id -> issues += issue(
                        "$path.depends_on",
                        "self_dependency",
                        "A step cannot depend on itself"
                    )
                    dependency !in idSet -> issues += issue(
                        "$path.depends_on",
                        "unknown_dependency",
                        "Dependency $dependency is not declared"
                    )
                }
            }
            if (!SkillJson.isCompatible(step.input)) {
                issues += issue("$path.input", "invalid_json_value", "Step input contains a non-JSON value")
            } else {
                val inputSize = SkillJson.stringify(step.input).toByteArray(StandardCharsets.UTF_8).size
                if (inputSize > AgentSkillLimits.MAX_STEP_INPUT_BYTES) {
                    issues += issue("$path.input", "oversized_input", "Step input exceeds the byte limit")
                }
                validateTemplates(step.input, path, manifest, issues, 0)
            }
        }
        if (ids.size == idSet.size && topologicalSteps(manifest.steps).size != manifest.steps.size) {
            issues += issue("$.steps", "cycle", "Step dependency graph contains a cycle")
        }
    }

    private fun validateTemplates(
        value: Any?,
        path: String,
        manifest: AgentSkillManifest,
        issues: MutableList<AgentSkillValidationIssue>,
        depth: Int
    ) {
        if (depth > AgentSkillLimits.MAX_INPUT_DEPTH) {
            issues += issue(path, "input_depth", "Step input nesting is too deep")
            return
        }
        when (value) {
            is String -> AgentSkillTemplateExpander.validate(value, path, manifest, issues)
            is Map<*, *> -> value.forEach { (key, nested) ->
                validateTemplates(nested, "$path.input.${key.toString()}", manifest, issues, depth + 1)
            }
            is Iterable<*> -> value.forEachIndexed { index, nested ->
                validateTemplates(nested, "$path.input[$index]", manifest, issues, depth + 1)
            }
            is Array<*> -> value.forEachIndexed { index, nested ->
                validateTemplates(nested, "$path.input[$index]", manifest, issues, depth + 1)
            }
        }
    }

    private fun validateSchema(
        schema: AgentSkillParameterSchema,
        path: String,
        depth: Int,
        issues: MutableList<AgentSkillValidationIssue>
    ) {
        if (depth > AgentSkillLimits.MAX_SCHEMA_DEPTH) {
            issues += issue(path, "schema_depth", "Parameter schema is too deeply nested")
            return
        }
        if (schema.properties.size > AgentSkillLimits.MAX_SCHEMA_PROPERTIES) {
            issues += issue(path, "schema_properties", "Parameter schema has too many properties")
        }
        schema.properties.keys.forEach { name ->
            stableToken(name, AgentSkillLimits.MAX_ID_CHARS, STABLE_ID, "$path.properties", "parameter name", issues)
        }
        schema.required.filterNot(schema.properties::containsKey).forEach { name ->
            issues += issue("$path.required", "unknown_required", "Required parameter $name is not declared")
        }
        if (!SkillJson.isCompatible(schema.enumValues)) {
            issues += issue("$path.enum", "invalid_json_value", "Schema enum contains a non-JSON value")
        }
        if (schema.minimum != null && (!schema.minimum.isFinite() ||
                schema.maximum != null && schema.minimum > schema.maximum)
        ) {
            issues += issue(path, "invalid_range", "Schema numeric range is invalid")
        }
        if (schema.maximum != null && !schema.maximum.isFinite()) {
            issues += issue(path, "invalid_range", "Schema numeric range is invalid")
        }
        if (!validBounds(schema.minLength, schema.maxLength) || !validBounds(schema.minItems, schema.maxItems)) {
            issues += issue(path, "invalid_bounds", "Schema size bounds are invalid")
        }
        when (schema.type) {
            AgentSkillParameterType.OBJECT -> schema.properties.forEach { (name, nested) ->
                validateSchema(nested, "$path.properties.$name", depth + 1, issues)
            }
            AgentSkillParameterType.ARRAY -> if (schema.items == null) {
                issues += issue("$path.items", "missing_items", "Array schema must declare its item schema")
            } else {
                validateSchema(schema.items, "$path.items", depth + 1, issues)
            }
            else -> Unit
        }
    }

    private fun validateValue(
        schema: AgentSkillParameterSchema,
        value: Any?,
        path: String,
        issues: MutableList<AgentSkillValidationIssue>,
        depth: Int
    ) {
        if (depth > AgentSkillLimits.MAX_INPUT_DEPTH) {
            issues += issue(path, "input_depth", "Parameter nesting is too deep")
            return
        }
        if (!matches(schema.type, value)) {
            issues += issue(path, "type_mismatch", "Expected ${schema.type.wireValue}")
            return
        }
        if (schema.enumValues.isNotEmpty() && schema.enumValues.none { jsonEquals(it, value) }) {
            issues += issue(path, "not_in_enum", "Value is not one of the allowed values")
        }
        when (value) {
            is Map<*, *> -> {
                schema.required.filterNot(value::containsKey).forEach { name ->
                    issues += issue("$path.$name", "required", "Required parameter is missing")
                }
                value.forEach { (rawName, nested) ->
                    val name = rawName as? String ?: return@forEach
                    val nestedSchema = schema.properties[name]
                    when {
                        nestedSchema != null -> validateValue(nestedSchema, nested, "$path.$name", issues, depth + 1)
                        !schema.additionalProperties -> issues += issue(
                            "$path.$name",
                            "additional_property",
                            "Additional parameters are not allowed"
                        )
                    }
                }
            }
            is Iterable<*> -> validateArrayValue(schema, value.toList(), path, issues, depth)
            is Array<*> -> validateArrayValue(schema, value.toList(), path, issues, depth)
            is String -> {
                if (schema.minLength != null && value.length < schema.minLength) {
                    issues += issue(path, "min_length", "String is shorter than ${schema.minLength}")
                }
                if (schema.maxLength != null && value.length > schema.maxLength) {
                    issues += issue(path, "max_length", "String is longer than ${schema.maxLength}")
                }
            }
            is Number -> {
                val number = value.toDouble()
                if (schema.minimum != null && number < schema.minimum) {
                    issues += issue(path, "minimum", "Number is below ${schema.minimum}")
                }
                if (schema.maximum != null && number > schema.maximum) {
                    issues += issue(path, "maximum", "Number is above ${schema.maximum}")
                }
            }
        }
    }

    private fun validateArrayValue(
        schema: AgentSkillParameterSchema,
        values: List<*>,
        path: String,
        issues: MutableList<AgentSkillValidationIssue>,
        depth: Int
    ) {
        if (schema.minItems != null && values.size < schema.minItems) {
            issues += issue(path, "min_items", "Array has fewer than ${schema.minItems} items")
        }
        if (schema.maxItems != null && values.size > schema.maxItems) {
            issues += issue(path, "max_items", "Array has more than ${schema.maxItems} items")
        }
        schema.items?.let { itemSchema ->
            values.forEachIndexed { index, item ->
                validateValue(itemSchema, item, "$path[$index]", issues, depth + 1)
            }
        }
    }

    private fun matches(type: AgentSkillParameterType, value: Any?): Boolean = when (type) {
        AgentSkillParameterType.OBJECT -> value is Map<*, *> && value.keys.all { it is String }
        AgentSkillParameterType.ARRAY -> value is Iterable<*> || value is Array<*>
        AgentSkillParameterType.STRING -> value is String
        AgentSkillParameterType.INTEGER -> value is Byte || value is Short || value is Int || value is Long ||
            value is Number && value.toDouble().isFinite() && value.toDouble() % 1.0 == 0.0
        AgentSkillParameterType.NUMBER -> value is Number && value.toDouble().isFinite()
        AgentSkillParameterType.BOOLEAN -> value is Boolean
    }

    private fun isSafeRelativeResourcePath(raw: String): Boolean {
        if (raw.isBlank() || raw != raw.trim() || raw.length > AgentSkillLimits.MAX_RESOURCE_PATH_CHARS) return false
        if (raw.startsWith('/') || raw.startsWith('\\') || '\\' in raw || ':' in raw || '%' in raw || '\u0000' in raw) {
            return false
        }
        val segments = raw.split('/')
        return segments.none { it.isBlank() || it == "." || it == ".." || it == "~" }
    }

    private fun stableToken(
        value: String,
        maxChars: Int,
        pattern: Regex,
        path: String,
        label: String,
        issues: MutableList<AgentSkillValidationIssue>
    ) {
        if (value.isBlank() || value != value.trim() || value.length > maxChars || !pattern.matches(value)) {
            issues += issue(path, "invalid_id", "$label is not a stable identifier")
        }
    }

    private fun boundedText(
        value: String,
        maxChars: Int,
        path: String,
        label: String,
        issues: MutableList<AgentSkillValidationIssue>
    ) {
        if (value.isBlank()) issues += issue(path, "blank_$label", "$label must not be blank")
        if (value.length > maxChars) issues += issue(path, "oversized_$label", "$label exceeds its character limit")
    }

    private fun validBounds(minimum: Int?, maximum: Int?): Boolean =
        (minimum == null || minimum >= 0) &&
            (maximum == null || maximum >= 0) &&
            (minimum == null || maximum == null || minimum <= maximum)

    private fun duplicateValues(values: List<String>): Set<String> = values.groupingBy { it }.eachCount()
        .filterValues { it > 1 }
        .keys

    private fun jsonEquals(left: Any?, right: Any?): Boolean = when {
        left is Number && right is Number -> left.toDouble() == right.toDouble()
        else -> left == right
    }
}

private object AgentSkillTemplateExpander {
    private val REFERENCE = Regex("\\{\\{\\s*([a-zA-Z][a-zA-Z0-9_.-]*)\\s*}}")

    fun validate(
        template: String,
        path: String,
        manifest: AgentSkillManifest,
        issues: MutableList<AgentSkillValidationIssue>
    ) {
        if (template.length > AgentSkillLimits.MAX_TEMPLATE_CHARS) {
            issues += issue(path, "oversized_template", "Template exceeds its character limit")
            return
        }
        val matches = REFERENCE.findAll(template).toList()
        if (matches.size > AgentSkillLimits.MAX_TEMPLATE_REFERENCES) {
            issues += issue(path, "too_many_templates", "Template has too many references")
        }
        val remainder = REFERENCE.replace(template, "")
        if ("{{" in remainder || "}}" in remainder) {
            issues += issue(path, "invalid_template", "Template expression is malformed or unsupported")
        }
        val resourceIds = manifest.resources.mapTo(mutableSetOf()) { it.id }
        matches.forEach { match ->
            val expression = match.groupValues[1]
            when {
                expression.startsWith("parameters.") -> {
                    val names = expression.removePrefix("parameters.").split('.')
                    if (!schemaMayContain(manifest.parameters, names)) {
                        issues += issue(path, "unknown_parameter", "Template parameter $expression is not declared")
                    }
                }
                expression.startsWith("resources.") -> {
                    val id = expression.removePrefix("resources.")
                    if (id !in resourceIds) {
                        issues += issue(path, "unknown_resource", "Template resource $id is not declared")
                    }
                }
                else -> issues += issue(
                    path,
                    "unsupported_template",
                    "Only parameters.* and resources.* references are supported"
                )
            }
        }
    }

    fun expand(
        input: Map<String, Any?>,
        parameters: Map<String, Any?>,
        resources: Map<String, AgentSkillResource>
    ): Map<String, Any?> = input.mapValues { (_, value) -> expandValue(value, parameters, resources, 0) }

    private fun expandValue(
        value: Any?,
        parameters: Map<String, Any?>,
        resources: Map<String, AgentSkillResource>,
        depth: Int
    ): Any? {
        require(depth <= AgentSkillLimits.MAX_INPUT_DEPTH) { "Skill input nesting is too deep" }
        return when (value) {
            is String -> expandString(value, parameters, resources)
            is Map<*, *> -> value.entries.associate { (key, nested) ->
                key as String to expandValue(nested, parameters, resources, depth + 1)
            }
            is Iterable<*> -> value.map { expandValue(it, parameters, resources, depth + 1) }
            is Array<*> -> value.map { expandValue(it, parameters, resources, depth + 1) }
            else -> value
        }
    }

    private fun expandString(
        template: String,
        parameters: Map<String, Any?>,
        resources: Map<String, AgentSkillResource>
    ): Any? {
        val matches = REFERENCE.findAll(template).toList()
        if (matches.isEmpty()) return template
        if (matches.size == 1 && matches.single().range == template.indices) {
            return resolve(matches.single().groupValues[1], parameters, resources)
        }
        return REFERENCE.replace(template) { match ->
            val resolved = resolve(match.groupValues[1], parameters, resources)
            when (resolved) {
                null -> "null"
                is Map<*, *>, is Iterable<*>, is Array<*> -> SkillJson.stringify(resolved)
                else -> resolved.toString()
            }
        }
    }

    private fun resolve(
        expression: String,
        parameters: Map<String, Any?>,
        resources: Map<String, AgentSkillResource>
    ): Any? = when {
        expression.startsWith("parameters.") -> {
            val names = expression.removePrefix("parameters.").split('.')
            var current: Any? = parameters
            names.forEach { name ->
                val map = current as? Map<*, *> ?: throw missingTemplate(expression)
                if (!map.containsKey(name)) throw missingTemplate(expression)
                current = map[name]
            }
            current
        }
        expression.startsWith("resources.") -> resources[expression.removePrefix("resources.")]?.path
            ?: throw missingTemplate(expression)
        else -> throw missingTemplate(expression)
    }

    private fun missingTemplate(expression: String) = AgentSkillValidationException(
        AgentSkillValidationResult(
            listOf(issue("$.steps.input", "missing_template_value", "No value is available for $expression"))
        )
    )

    private fun schemaMayContain(schema: AgentSkillParameterSchema, names: List<String>): Boolean {
        var current = schema
        names.forEach { name ->
            val next = current.properties[name]
            if (next == null) return current.additionalProperties
            current = next
        }
        return true
    }
}

object AgentSkillManifestCodec {
    fun encode(manifest: AgentSkillManifest): String = SkillJson.stringify(toMap(manifest))

    fun decode(raw: String): AgentSkillManifest? {
        if (raw.isBlank() || raw.toByteArray(StandardCharsets.UTF_8).size > AgentSkillLimits.MAX_MANIFEST_BYTES) {
            return null
        }
        val root = SkillJson.parseObject(raw, AgentSkillLimits.MAX_MANIFEST_BYTES) ?: return null
        return fromMap(root)
    }

    internal fun toMap(manifest: AgentSkillManifest): Map<String, Any?> = linkedMapOf(
        "format_version" to manifest.formatVersion,
        "id" to manifest.id,
        "version" to manifest.version,
        "title" to manifest.title,
        "instructions" to manifest.instructions,
        "native_tools" to manifest.nativeTools.sorted(),
        "permissions" to manifest.permissions.sorted(),
        "resources" to manifest.resources.map { resource ->
            linkedMapOf(
                "id" to resource.id,
                "path" to resource.path,
                "mime_type" to resource.mimeType,
                "max_bytes" to resource.maxBytes
            )
        },
        "parameters" to schemaToMap(manifest.parameters),
        "steps" to manifest.steps.map { step ->
            linkedMapOf(
                "id" to step.id,
                "tool_id" to step.toolId,
                "input" to step.input,
                "depends_on" to step.dependsOn
            )
        }
    )

    internal fun fromMap(root: Map<String, Any?>): AgentSkillManifest? = runCatching {
        requireKeys(
            root,
            setOf(
                "format_version", "id", "version", "title", "instructions", "native_tools",
                "permissions", "resources", "parameters", "steps"
            )
        )
        require(root.keys.containsAll(setOf("id", "version", "title", "instructions", "native_tools", "steps")))
        val resources = root.list("resources").map { raw ->
            val item = raw.stringMap()
            requireKeys(item, setOf("id", "path", "mime_type", "max_bytes"))
            AgentSkillResource(
                id = item.string("id"),
                path = item.string("path"),
                mimeType = item.string("mime_type", "application/octet-stream"),
                maxBytes = item.long("max_bytes", AgentSkillLimits.DEFAULT_RESOURCE_MAX_BYTES)
            )
        }
        val steps = root.list("steps").map { raw ->
            val item = raw.stringMap()
            requireKeys(item, setOf("id", "tool_id", "input", "depends_on"))
            AgentSkillStep(
                id = item.string("id"),
                toolId = item.string("tool_id"),
                input = item.map("input"),
                dependsOn = item.stringList("depends_on")
            )
        }
        AgentSkillManifest(
            id = root.string("id"),
            version = root.string("version"),
            title = root.string("title"),
            instructions = root.string("instructions"),
            nativeTools = root.stringList("native_tools").toSet(),
            permissions = root.stringList("permissions").toSet(),
            resources = resources,
            parameters = root["parameters"]?.stringMap()?.let(::schemaFromMap)
                ?: AgentSkillParameterSchema.objectSchema(),
            steps = steps,
            formatVersion = root.int("format_version", AgentSkillLimits.SUPPORTED_FORMAT_VERSION)
        )
    }.getOrNull()

    private fun schemaToMap(schema: AgentSkillParameterSchema): Map<String, Any?> = linkedMapOf<String, Any?>(
        "type" to schema.type.wireValue
    ).apply {
        if (schema.properties.isNotEmpty()) put(
            "properties",
            schema.properties.toSortedMap().mapValues { schemaToMap(it.value) }
        )
        if (schema.required.isNotEmpty()) put("required", schema.required.sorted())
        if (schema.type == AgentSkillParameterType.OBJECT) put("additional_properties", schema.additionalProperties)
        schema.items?.let { put("items", schemaToMap(it)) }
        if (schema.enumValues.isNotEmpty()) put("enum", schema.enumValues)
        schema.minLength?.let { put("min_length", it) }
        schema.maxLength?.let { put("max_length", it) }
        schema.minimum?.let { put("minimum", it) }
        schema.maximum?.let { put("maximum", it) }
        schema.minItems?.let { put("min_items", it) }
        schema.maxItems?.let { put("max_items", it) }
    }

    private fun schemaFromMap(map: Map<String, Any?>): AgentSkillParameterSchema {
        requireKeys(
            map,
            setOf(
                "type", "properties", "required", "additional_properties", "items", "enum",
                "min_length", "max_length", "minimum", "maximum", "min_items", "max_items"
            )
        )
        val typeName = map.string("type", AgentSkillParameterType.OBJECT.wireValue)
        val type = AgentSkillParameterType.entries.first { it.wireValue == typeName }
        return AgentSkillParameterSchema(
            type = type,
            properties = map.map("properties").mapValues { (_, value) -> schemaFromMap(value.stringMap()) },
            required = map.stringList("required").toSet(),
            additionalProperties = map.boolean("additional_properties"),
            items = map["items"]?.stringMap()?.let(::schemaFromMap),
            enumValues = map.list("enum"),
            minLength = map.nullableInt("min_length"),
            maxLength = map.nullableInt("max_length"),
            minimum = map.nullableDouble("minimum"),
            maximum = map.nullableDouble("maximum"),
            minItems = map.nullableInt("min_items"),
            maxItems = map.nullableInt("max_items")
        )
    }
}

private object AgentSkillStoreCodec {
    private const val VERSION = 1

    fun emptyDocument(): String = "{\"installations\":[],\"version\":1}"

    fun encode(skills: List<AgentSkillInstallation>): String {
        val deduplicated = LinkedHashMap<String, AgentSkillInstallation>()
        skills.sortedWith(compareBy<AgentSkillInstallation> { it.id }.thenBy { it.version }).forEach {
            deduplicated["${it.id}\u0000${it.version}"] = it
        }
        require(deduplicated.size <= AgentSkillLimits.MAX_INSTALLED_SKILLS) { "Agent Skill store is full" }
        val document = SkillJson.stringify(
            mapOf(
                "version" to VERSION,
                "installations" to deduplicated.values.map { installation ->
                    mapOf(
                        "manifest" to AgentSkillManifestCodec.toMap(installation.manifest),
                        "enabled" to installation.enabled,
                        "installed_at" to installation.installedAtMillis.coerceAtLeast(0L),
                        "updated_at" to installation.updatedAtMillis.coerceAtLeast(0L)
                    )
                }
            )
        )
        require(document.toByteArray(StandardCharsets.UTF_8).size <= AgentSkillLimits.MAX_STORE_BYTES) {
            "Agent Skill store exceeds its byte limit"
        }
        return document
    }

    fun decode(raw: String): List<AgentSkillInstallation> {
        if (raw.isBlank() || raw.toByteArray(StandardCharsets.UTF_8).size > AgentSkillLimits.MAX_STORE_BYTES) {
            return emptyList()
        }
        val root = SkillJson.parseObject(raw, AgentSkillLimits.MAX_STORE_BYTES) ?: return emptyList()
        if (root.int("version", VERSION) > VERSION) return emptyList()
        return root.list("installations").mapNotNull { value ->
            runCatching {
                val item = value.stringMap()
                val manifest = AgentSkillManifestCodec.fromMap(item["manifest"].stringMap()) ?: return@runCatching null
                if (!AgentSkillManifestValidator.validate(manifest).isValid) return@runCatching null
                AgentSkillInstallation(
                    manifest = manifest,
                    enabled = item.boolean("enabled", true),
                    installedAtMillis = item.long("installed_at").coerceAtLeast(0L),
                    updatedAtMillis = item.long("updated_at").coerceAtLeast(0L)
                )
            }.getOrNull()
        }.filterNotNull().distinctBy { it.id to it.version }.take(AgentSkillLimits.MAX_INSTALLED_SKILLS)
    }
}

private object SkillJson {
    fun isCompatibleManifest(manifest: AgentSkillManifest): Boolean =
        manifest.steps.all { isCompatible(it.input) } && schemaCompatible(manifest.parameters)

    fun isCompatible(value: Any?): Boolean = isCompatible(value, 0)

    private fun isCompatible(value: Any?, depth: Int): Boolean {
        if (depth > AgentSkillLimits.MAX_JSON_DEPTH) return false
        return when (value) {
            null, is String, is Boolean, is Byte, is Short, is Int, is Long -> true
            is Float -> value.isFinite()
            is Double -> value.isFinite()
            is Number -> value.toDouble().isFinite()
            is Map<*, *> -> value.keys.all { it is String } &&
                value.values.all { isCompatible(it, depth + 1) }
            is Iterable<*> -> value.all { isCompatible(it, depth + 1) }
            is Array<*> -> value.all { isCompatible(it, depth + 1) }
            else -> false
        }
    }

    fun stringify(value: Any?): String = buildString { appendValue(value) }

    fun parseObject(raw: String, maxBytes: Int): Map<String, Any?>? {
        if (raw.toByteArray(StandardCharsets.UTF_8).size > maxBytes) return null
        return runCatching { Parser(raw).parse().stringMap() }.getOrNull()
    }

    private fun schemaCompatible(schema: AgentSkillParameterSchema, depth: Int = 0): Boolean =
        depth <= AgentSkillLimits.MAX_SCHEMA_DEPTH + 1 && isCompatible(schema.enumValues) &&
            (schema.minimum?.isFinite() ?: true) && (schema.maximum?.isFinite() ?: true) &&
            schema.properties.values.all { schemaCompatible(it, depth + 1) } &&
            (schema.items?.let { schemaCompatible(it, depth + 1) } ?: true)

    private fun StringBuilder.appendValue(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> appendQuoted(value)
            is Boolean, is Byte, is Short, is Int, is Long -> append(value.toString())
            is Float -> {
                require(value.isFinite()) { "JSON numbers must be finite" }
                append(value)
            }
            is Double -> {
                require(value.isFinite()) { "JSON numbers must be finite" }
                append(value)
            }
            is Number -> {
                require(value.toDouble().isFinite()) { "JSON numbers must be finite" }
                append(value)
            }
            is Map<*, *> -> {
                require(value.keys.all { it is String }) { "JSON object keys must be strings" }
                append('{')
                value.entries.sortedBy { it.key as String }.forEachIndexed { index, entry ->
                    if (index > 0) append(',')
                    appendQuoted(entry.key as String)
                    append(':')
                    appendValue(entry.value)
                }
                append('}')
            }
            is Iterable<*> -> appendArray(value.toList())
            is Array<*> -> appendArray(value.toList())
            else -> throw IllegalArgumentException("Unsupported JSON value")
        }
    }

    private fun StringBuilder.appendArray(values: List<*>) {
        append('[')
        values.forEachIndexed { index, item ->
            if (index > 0) append(',')
            appendValue(item)
        }
        append(']')
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else append(character)
            }
        }
        append('"')
    }

    private class Parser(private val source: String) {
        private var index = 0

        fun parse(): Any? {
            val value = parseValue(0)
            skipWhitespace()
            require(index == source.length) { "Unexpected trailing JSON content" }
            return value
        }

        private fun parseValue(depth: Int): Any? {
            require(depth <= AgentSkillLimits.MAX_JSON_DEPTH) { "JSON nesting is too deep" }
            skipWhitespace()
            require(index < source.length) { "Unexpected end of JSON" }
            return when (source[index]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> error("Invalid JSON value")
            }
        }

        private fun parseObject(depth: Int): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            val values = LinkedHashMap<String, Any?>()
            if (consume('}')) return values
            while (true) {
                skipWhitespace()
                require(index < source.length && source[index] == '"') { "Expected JSON object key" }
                val name = parseString()
                skipWhitespace()
                expect(':')
                require(!values.containsKey(name)) { "Duplicate JSON object key" }
                values[name] = parseValue(depth)
                skipWhitespace()
                if (consume('}')) return values
                expect(',')
            }
        }

        private fun parseArray(depth: Int): List<Any?> {
            expect('[')
            skipWhitespace()
            val values = mutableListOf<Any?>()
            if (consume(']')) return values
            while (true) {
                values += parseValue(depth)
                skipWhitespace()
                if (consume(']')) return values
                expect(',')
            }
        }

        private fun parseString(): String {
            expect('"')
            val output = StringBuilder()
            while (index < source.length) {
                when (val character = source[index++]) {
                    '"' -> return output.toString()
                    '\\' -> {
                        require(index < source.length) { "Incomplete JSON escape" }
                        when (val escaped = source[index++]) {
                            '"', '\\', '/' -> output.append(escaped)
                            'b' -> output.append('\b')
                            'f' -> output.append('\u000c')
                            'n' -> output.append('\n')
                            'r' -> output.append('\r')
                            't' -> output.append('\t')
                            'u' -> {
                                require(index + 4 <= source.length) { "Incomplete unicode escape" }
                                val code = source.substring(index, index + 4).toIntOrNull(16)
                                requireNotNull(code) { "Invalid unicode escape" }
                                output.append(code.toChar())
                                index += 4
                            }
                            else -> error("Invalid JSON escape")
                        }
                    }
                    else -> {
                        require(character.code >= 0x20) { "Unescaped JSON control character" }
                        output.append(character)
                    }
                }
            }
            error("Unterminated JSON string")
        }

        private fun parseNumber(): Number {
            val start = index
            consume('-')
            require(index < source.length) { "Incomplete JSON number" }
            if (consume('0')) {
                require(index >= source.length || !source[index].isDigit()) { "Invalid JSON number" }
            } else {
                require(index < source.length && source[index] in '1'..'9') { "Invalid JSON number" }
                while (index < source.length && source[index].isDigit()) index++
            }
            var decimal = false
            if (consume('.')) {
                decimal = true
                require(index < source.length && source[index].isDigit()) { "Invalid JSON fraction" }
                while (index < source.length && source[index].isDigit()) index++
            }
            if (index < source.length && source[index] in setOf('e', 'E')) {
                decimal = true
                index++
                if (index < source.length && source[index] in setOf('+', '-')) index++
                require(index < source.length && source[index].isDigit()) { "Invalid JSON exponent" }
                while (index < source.length && source[index].isDigit()) index++
            }
            val raw = source.substring(start, index)
            return if (decimal) requireNotNull(raw.toDoubleOrNull()).also { require(it.isFinite()) }
            else requireNotNull(raw.toLongOrNull())
        }

        private fun parseLiteral(expected: String, value: Any?): Any? {
            require(source.regionMatches(index, expected, 0, expected.length)) { "Invalid JSON literal" }
            index += expected.length
            return value
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index] in setOf(' ', '\n', '\r', '\t')) index++
        }

        private fun expect(character: Char) {
            require(consume(character)) { "Expected '$character'" }
        }

        private fun consume(character: Char): Boolean {
            if (index >= source.length || source[index] != character) return false
            index++
            return true
        }
    }
}

private fun issue(path: String, code: String, message: String) =
    AgentSkillValidationIssue(path, code, message)

private fun AgentSkillValidationResult.throwIfInvalid() {
    if (!isValid) throw AgentSkillValidationException(this)
}

private fun requireKeys(map: Map<String, Any?>, allowed: Set<String>) {
    require(map.keys.all { it in allowed }) { "Unknown manifest field" }
}

private fun Any?.stringMap(): Map<String, Any?> = (this as? Map<*, *>)?.entries?.associate { (key, value) ->
    require(key is String) { "JSON object keys must be strings" }
    key to value
} ?: error("Expected JSON object")

private fun Map<String, Any?>.string(name: String, default: String = ""): String =
    this[name] as? String ?: default

private fun Map<String, Any?>.long(name: String, default: Long = 0L): Long =
    (this[name] as? Number)?.toLong() ?: default

private fun Map<String, Any?>.int(name: String, default: Int = 0): Int =
    (this[name] as? Number)?.toInt() ?: default

private fun Map<String, Any?>.nullableInt(name: String): Int? = (this[name] as? Number)?.toInt()

private fun Map<String, Any?>.nullableDouble(name: String): Double? = (this[name] as? Number)?.toDouble()

private fun Map<String, Any?>.boolean(name: String, default: Boolean = false): Boolean =
    this[name] as? Boolean ?: default

private fun Map<String, Any?>.list(name: String): List<Any?> = this[name] as? List<Any?> ?: emptyList()

private fun Map<String, Any?>.stringList(name: String): List<String> = list(name).map { it as String }

private fun Map<String, Any?>.map(name: String): Map<String, Any?> = this[name]?.stringMap() ?: emptyMap()
