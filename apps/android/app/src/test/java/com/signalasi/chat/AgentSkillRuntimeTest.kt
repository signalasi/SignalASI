package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSkillRuntimeTest {
    @Test
    fun installsVersionsAndControlsTheirLifecycle() {
        var now = 100L
        val store = InMemoryAgentSkillStore()
        val runtime = AgentSkillRuntime(store, setOf("phone.echo")) { now++ }
        val versionOne = manifest(version = "1.0.0")
        val versionTwo = manifest(version = "2.0.0", title = "Echo v2")

        val first = runtime.install(versionOne)
        runtime.install(versionTwo, enabled = false)

        assertEquals(2, runtime.list().size)
        assertEquals(1, runtime.list(enabledOnly = true).size)
        assertEquals(100L, first.installedAtMillis)
        assertFalse(runtime.get("example.echo", "2.0.0")!!.enabled)

        runtime.disable("example.echo", "1.0.0")
        assertThrows(IllegalStateException::class.java) {
            runtime.expand("example.echo", "1.0.0", mapOf("message" to "hello", "count" to 1))
        }
        assertTrue(runtime.enable("example.echo", "1.0.0").enabled)
        assertTrue(runtime.delete("example.echo", "2.0.0"))
        assertFalse(runtime.delete("example.echo", "2.0.0"))
        assertNull(runtime.get("example.echo", "2.0.0"))

        val changedSameVersion = versionOne.copy(instructions = "Changed without a version bump")
        assertThrows(AgentSkillConflictException::class.java) { runtime.install(changedSameVersion) }
    }

    @Test
    fun installsAvailableSkillsWithOneStoreReadAndOneAtomicWrite() {
        val store = CountingAgentSkillStore()
        val runtime = AgentSkillRuntime(store, setOf("phone.echo")) { 100L }
        val manifests = listOf(
            manifest(version = "1.0.0"),
            manifest(version = "2.0.0", title = "Echo v2")
        )

        assertEquals(2, runtime.installAvailable(manifests).size)
        assertEquals(1, store.listCalls)
        assertEquals(1, store.replaceAllCalls)
        assertEquals(2, store.values.size)

        assertEquals(2, runtime.installAvailable(manifests).size)
        assertEquals(2, store.listCalls)
        assertEquals(1, store.replaceAllCalls)

        val conflicting = manifests.first().copy(instructions = "Changed without a version bump")
        assertTrue(runtime.installAvailable(listOf(conflicting)).isEmpty())
        assertEquals(3, store.listCalls)
        assertEquals(1, store.replaceAllCalls)
    }

    @Test
    fun expandsTypedTemplatesAndTopologicallyOrdersDagSteps() {
        val runtime = AgentSkillRuntime(
            availableNativeToolIds = setOf("phone.prepare", "phone.send")
        )
        val skill = manifest(
            nativeTools = setOf("phone.prepare", "phone.send"),
            resources = listOf(AgentSkillResource("prompt", "prompts/echo.txt", "text/plain", 4_096)),
            steps = listOf(
                AgentSkillStep(
                    id = "send",
                    toolId = "phone.send",
                    input = mapOf("prepared" to true),
                    dependsOn = listOf("prepare")
                ),
                AgentSkillStep(
                    id = "prepare",
                    toolId = "phone.prepare",
                    input = mapOf(
                        "message" to "Hello {{parameters.message}}",
                        "count" to "{{parameters.count}}",
                        "resource" to "{{resources.prompt}}"
                    )
                )
            )
        )

        runtime.install(skill)
        val expansion = runtime.expand(
            skill.id,
            skill.version,
            mapOf("message" to "Signal", "count" to 3)
        )

        assertEquals(listOf("prepare", "send"), expansion.steps.map { it.id })
        assertEquals("Hello Signal", expansion.steps[0].input["message"])
        assertEquals(3, expansion.steps[0].input["count"])
        assertEquals("prompts/echo.txt", expansion.steps[0].input["resource"])
        assertEquals(setOf("android.permission.POST_NOTIFICATIONS"), expansion.permissions)
        assertEquals("text/plain", expansion.resources["prompt"]?.mimeType)

        assertThrows(AgentSkillValidationException::class.java) {
            runtime.expand(skill, mapOf("message" to "Signal", "count" to "three"))
        }
    }

    @Test
    fun rejectsUndeclaredUnavailableAndCyclicTools() {
        val undeclared = manifest(
            nativeTools = setOf("phone.echo"),
            steps = listOf(AgentSkillStep("run", "phone.shell"))
        )
        val unavailable = manifest(nativeTools = setOf("phone.missing"))
        val cyclic = manifest(
            steps = listOf(
                AgentSkillStep("first", "phone.echo", dependsOn = listOf("second")),
                AgentSkillStep("second", "phone.echo", dependsOn = listOf("first"))
            )
        )

        assertEquals(setOf("undeclared_tool"), codes(AgentSkillRuntime().validate(undeclared)))
        assertTrue("unknown_tool" in codes(AgentSkillRuntime(availableNativeToolIds = setOf("phone.echo")).validate(unavailable)))
        assertTrue("cycle" in codes(AgentSkillRuntime().validate(cyclic)))
        assertThrows(AgentSkillValidationException::class.java) { AgentSkillRuntime().install(cyclic) }
    }

    @Test
    fun rejectsTraversalMalformedTemplatesAndOversizedDocuments() {
        val unsafePaths = listOf(
            "../secret.txt",
            "prompts/../../secret.txt",
            "/absolute.txt",
            "C:/absolute.txt",
            "prompts\\secret.txt",
            "prompts/%2e%2e/secret.txt"
        )
        unsafePaths.forEach { path ->
            val result = AgentSkillRuntime().validate(
                manifest(resources = listOf(AgentSkillResource("prompt", path)))
            )
            assertTrue("Expected path_traversal for $path", "path_traversal" in codes(result))
        }

        val unsupportedTemplate = manifest(
            steps = listOf(
                AgentSkillStep("run", "phone.echo", mapOf("value" to "{{runtime.exec}}"))
            )
        )
        assertTrue("unsupported_template" in codes(AgentSkillRuntime().validate(unsupportedTemplate)))

        val malformedTemplate = manifest(
            steps = listOf(
                AgentSkillStep("run", "phone.echo", mapOf("value" to "{{parameters.message"))
            )
        )
        assertTrue("invalid_template" in codes(AgentSkillRuntime().validate(malformedTemplate)))

        val oversizedRaw = "x".repeat(AgentSkillLimits.MAX_MANIFEST_BYTES + 1)
        assertEquals(setOf("oversized_manifest"), codes(AgentSkillRuntime().validate(oversizedRaw)))
        val oversizedInput = manifest(
            steps = listOf(
                AgentSkillStep("run", "phone.echo", mapOf("value" to "x".repeat(AgentSkillLimits.MAX_STEP_INPUT_BYTES)))
            )
        )
        assertTrue("oversized_input" in codes(AgentSkillRuntime().validate(oversizedInput)))

        val rawWithExecutableField = AgentSkillManifestCodec.encode(manifest()).dropLast(1) +
            ",\"script\":\"Runtime.getRuntime().exec('no')\"}"
        assertEquals(setOf("malformed_manifest"), codes(AgentSkillRuntime().validate(rawWithExecutableField)))

        val invalidNumericSchema = manifest(
            parameters = AgentSkillParameterSchema.objectSchema(
                properties = mapOf("value" to AgentSkillParameterSchema.number(minimum = Double.NaN)),
                required = setOf("value")
            )
        )
        assertTrue("invalid_range" in codes(AgentSkillRuntime().validate(invalidNumericSchema)))
    }

    @Test
    fun codecAndInMemoryStorePreserveDeclarativeManifest() {
        val original = manifest(
            resources = listOf(AgentSkillResource("prompt", "prompts/default.txt", "text/plain"))
        )
        val encoded = AgentSkillManifestCodec.encode(original)
        val decoded = AgentSkillManifestCodec.decode(encoded)

        assertNotNull(decoded)
        assertEquals(encoded, AgentSkillManifestCodec.encode(requireNotNull(decoded)))
        assertNull(AgentSkillManifestCodec.decode(encoded.dropLast(1)))

        val store = InMemoryAgentSkillStore()
        val runtime = AgentSkillRuntime(store, setOf("phone.echo")) { 7L }
        runtime.install(encoded)

        assertTrue(store.serializedSnapshot().contains("\"manifest\""))
        assertEquals(original.id, store.list().single().manifest.id)
        assertEquals(7L, store.list().single().installedAtMillis)
    }

    @Test
    fun validatesNestedParameterSchemaAndTemplateDeclarations() {
        val nestedParameters = AgentSkillParameterSchema.objectSchema(
            properties = mapOf(
                "recipient" to AgentSkillParameterSchema.objectSchema(
                    properties = mapOf("name" to AgentSkillParameterSchema.string(minLength = 2, maxLength = 20)),
                    required = setOf("name")
                )
            ),
            required = setOf("recipient")
        )
        val skill = manifest(
            parameters = nestedParameters,
            steps = listOf(
                AgentSkillStep(
                    "run",
                    "phone.echo",
                    mapOf("message" to "Hello {{parameters.recipient.name}}")
                )
            )
        )
        val runtime = AgentSkillRuntime()

        assertTrue(runtime.validate(skill).isValid)
        assertEquals(
            "Hello Ada",
            runtime.expand(skill, mapOf("recipient" to mapOf("name" to "Ada"))).steps.single().input["message"]
        )
        assertThrows(AgentSkillValidationException::class.java) {
            runtime.expand(skill, mapOf("recipient" to mapOf("name" to "A", "extra" to true)))
        }

        val unknownParameter = skill.copy(
            steps = listOf(AgentSkillStep("run", "phone.echo", mapOf("value" to "{{parameters.missing}}")))
        )
        assertTrue("unknown_parameter" in codes(runtime.validate(unknownParameter)))
    }

    @Test
    fun installationSettingsDoNotMutateTheVersionedManifest() {
        val store = InMemoryAgentSkillStore()
        val runtime = AgentSkillRuntime(store, setOf("phone.echo")) { 42L }
        val installed = runtime.install(manifest())

        val updated = runtime.setAutoInvoke(installed.id, installed.version, true)

        assertTrue(updated.autoInvoke)
        assertFalse(updated.manifest.autoInvoke)
        assertTrue(store.list().single().autoInvoke)
        assertEquals(AgentSkillManifestCodec.encode(installed.manifest), AgentSkillManifestCodec.encode(updated.manifest))
    }

    private fun manifest(
        version: String = "1.0.0",
        title: String = "Echo",
        nativeTools: Set<String> = setOf("phone.echo"),
        resources: List<AgentSkillResource> = emptyList(),
        parameters: AgentSkillParameterSchema = AgentSkillParameterSchema.objectSchema(
            properties = mapOf(
                "message" to AgentSkillParameterSchema.string(minLength = 1, maxLength = 1_024),
                "count" to AgentSkillParameterSchema.integer(minimum = 1, maximum = 10)
            ),
            required = setOf("message", "count")
        ),
        steps: List<AgentSkillStep> = listOf(
            AgentSkillStep(
                id = "run",
                toolId = nativeTools.first(),
                input = mapOf("message" to "{{parameters.message}}", "count" to "{{parameters.count}}")
            )
        )
    ) = AgentSkillManifest(
        id = "example.echo",
        version = version,
        title = title,
        instructions = "Echo a bounded message using declared native tools.",
        nativeTools = nativeTools,
        permissions = setOf("android.permission.POST_NOTIFICATIONS"),
        resources = resources,
        parameters = parameters,
        steps = steps
    )

    private class CountingAgentSkillStore : AgentSkillStore {
        var values: List<AgentSkillInstallation> = emptyList()
        var listCalls: Int = 0
        var replaceAllCalls: Int = 0

        override fun list(): List<AgentSkillInstallation> {
            listCalls += 1
            return values
        }

        override fun upsert(installation: AgentSkillInstallation) {
            values = values.filterNot { it.id == installation.id && it.version == installation.version } + installation
        }

        override fun replaceAll(installations: List<AgentSkillInstallation>) {
            replaceAllCalls += 1
            values = installations.toList()
        }

        override fun delete(id: String, version: String): Boolean {
            val remaining = values.filterNot { it.id == id && it.version == version }
            if (remaining.size == values.size) return false
            values = remaining
            return true
        }

        override fun clear() {
            values = emptyList()
        }
    }

    private fun codes(result: AgentSkillValidationResult): Set<String> = result.issues.mapTo(mutableSetOf()) { it.code }
}
