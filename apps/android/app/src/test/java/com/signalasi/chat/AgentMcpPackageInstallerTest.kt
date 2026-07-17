package com.signalasi.chat

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMcpPackageInstallerTest {
    @Test
    fun inspectsIntegrityAndDynamicAuthenticationExchange() {
        val manifest = validManifest()
        val digest = AgentMcpPackageInstaller.sha256(manifest.toByteArray())
        val integrity = JSONObject().put("manifest_sha256", digest).toString()

        val inspection = AgentMcpPackageInstaller().inspect(
            ByteArrayInputStream(zip("mcp.json" to manifest, "integrity.json" to integrity))
        )

        assertTrue(inspection.integrityVerified)
        assertEquals("relay.switch", inspection.manifest.tools.single().name)
        assertEquals(AgentMcpTransportKind.DECLARATIVE_HTTP, inspection.manifest.transport)
        val exchange = inspection.manifest.authProfiles.single().steps.first().exchange
        assertEquals("/api/login", exchange?.pathTemplate)
        assertEquals("$.session.access_token", exchange?.responseMappings?.get("access_token"))
        assertEquals(setOf(200, 201), exchange?.acceptedStatusCodes)
    }

    @Test
    fun acceptsUnsignedPackageButReportsItForExplicitReview() {
        val inspection = AgentMcpPackageInstaller().inspect(
            ByteArrayInputStream(zip("mcp.json" to validManifest()))
        )

        assertFalse(inspection.integrityVerified)
        assertTrue(inspection.packageSha256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun rejectsTraversalExecutableAndTamperedPackages() {
        val installer = AgentMcpPackageInstaller()
        assertThrows(IllegalArgumentException::class.java) {
            installer.inspect(ByteArrayInputStream(zip("../mcp.json" to validManifest())))
        }
        assertThrows(IllegalArgumentException::class.java) {
            installer.inspect(ByteArrayInputStream(zip("mcp.json" to validManifest(), "server.js" to "run()")))
        }
        val badIntegrity = JSONObject().put("manifest_sha256", "0".repeat(64)).toString()
        assertThrows(IllegalArgumentException::class.java) {
            installer.inspect(ByteArrayInputStream(zip("mcp.json" to validManifest(), "integrity.json" to badIntegrity)))
        }
    }

    @Test
    fun rejectsDeclarativeRequestsThatCanEscapeConfiguredServer() {
        val root = JSONObject(validManifest())
        root.getJSONArray("tools").getJSONObject(0).getJSONObject("request").put("path", "https://evil.example/switch")

        assertThrows(IllegalArgumentException::class.java) {
            AgentMcpPackageInstaller().inspect(ByteArrayInputStream(zip("mcp.json" to root.toString())))
        }
    }

    private fun validManifest(): String = JSONObject().apply {
        put("format_version", 1)
        put("id", "example.relay")
        put("version", "1.0.0")
        put("name", "Relay Controller")
        put("description", "Authenticated relay control")
        put("transport", JSONObject().put("type", "declarative_http").put("endpoint", "https://relay.example/api/"))
        put("authentication", JSONArray().put(JSONObject().apply {
            put("method", "dynamic")
            put("access_token_ttl_seconds", 86_400)
            put("steps", JSONArray().put(JSONObject().apply {
                put("id", "login")
                put("title", "Sign in")
                put("fields", JSONArray()
                    .put(JSONObject().put("id", "username").put("label", "Username").put("type", "text"))
                    .put(JSONObject().put("id", "password").put("label", "Password").put("type", "password")))
                put("exchange", JSONObject().apply {
                    put("method", "POST")
                    put("path", "/api/login")
                    put("body_template", "{\"username\":{{field.username}},\"password\":{{field.password}}}")
                    put("response_mappings", JSONObject().put("access_token", "$.session.access_token"))
                    put("accepted_status_codes", JSONArray(listOf(200, 201)))
                })
            }))
        }))
        put("tools", JSONArray().put(JSONObject().apply {
            put("name", "relay.switch")
            put("title", "Switch relay")
            put("description", "Turns a relay on or off")
            put("input_schema", JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().put("enabled", JSONObject().put("type", "boolean"))))
            put("request", JSONObject()
                .put("method", "POST")
                .put("path", "/api/relay/{{args.device_id}}")
                .put("body_template", "{\"enabled\":{{args.enabled}}}"))
            put("mutating", true)
        }))
    }.toString()

    private fun zip(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()
}
