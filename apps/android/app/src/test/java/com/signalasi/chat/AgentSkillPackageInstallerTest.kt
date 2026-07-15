package com.signalasi.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AgentSkillPackageInstallerTest {
    private val manifest = AgentSkillManifest(
        id = "package.test",
        version = "1.0.0",
        title = "Package test",
        instructions = "Read battery status.",
        source = "third_party",
        nativeTools = setOf("phone.battery"),
        steps = listOf(AgentSkillStep("read", "phone.battery"))
    )

    @Test
    fun verifiesManifestIntegrityAndInstallsDisabled() {
        val runtime = AgentSkillRuntime(availableNativeToolIds = setOf("phone.battery"))
        val installer = AgentSkillPackageInstaller(runtime)
        val raw = AgentSkillManifestCodec.encode(manifest)
        val integrity = JSONObject()
            .put("manifest_sha256", sha256(raw.toByteArray()))
            .put("signer", "test")
            .toString()

        val inspected = installer.inspect(ByteArrayInputStream(zip("manifest.json" to raw, "integrity.json" to integrity)))
        val installed = installer.install(ByteArrayInputStream(zip("manifest.json" to raw, "integrity.json" to integrity)))

        assertTrue(inspected.integrityVerified)
        assertEquals("test", inspected.signer)
        assertEquals("third_party", installed.manifest.source)
        assertTrue(!installed.enabled)
    }

    @Test
    fun rejectsTraversalExecutableAndTamperedPackages() {
        val installer = AgentSkillPackageInstaller(AgentSkillRuntime(availableNativeToolIds = setOf("phone.battery")))
        val raw = AgentSkillManifestCodec.encode(manifest)
        assertThrows(AgentSkillPackageException::class.java) {
            installer.inspect(ByteArrayInputStream(zip("../manifest.json" to raw)))
        }
        assertThrows(AgentSkillPackageException::class.java) {
            installer.inspect(ByteArrayInputStream(zip("manifest.json" to raw, "run.js" to "alert(1)")))
        }
        val badIntegrity = JSONObject().put("manifest_sha256", "0".repeat(64)).toString()
        assertThrows(AgentSkillPackageException::class.java) {
            installer.inspect(ByteArrayInputStream(zip("manifest.json" to raw, "integrity.json" to badIntegrity)))
        }
    }

    private fun zip(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
