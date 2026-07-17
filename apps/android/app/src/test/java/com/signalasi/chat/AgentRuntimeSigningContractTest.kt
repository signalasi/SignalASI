package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class AgentRuntimeSigningContractTest {
    @Test
    fun `manifest canonical payload matches the release tool vector`() {
        val manifest = AgentRuntimePackManifest(
            id = "linux-base",
            version = "1.0.0",
            architecture = "arm64-v8a",
            imageFile = "linux.img",
            imageSha256 = "a".repeat(64),
            capabilities = listOf("shell.execute"),
            dependencies = emptyList(),
            installedSizeBytes = 2_048L,
            archiveSizeBytes = 1_024L,
            minimumHostVersionCode = 60L,
            guestApiVersion = 1,
            license = "Apache-2.0",
            signatureKeyId = "b".repeat(64),
            signature = ""
        )

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(manifest.signingPayload())
            .joinToString("") { "%02x".format(it) }

        assertEquals(
            "dfca26a4c789efa795be3bcc56c45615a6be67857a43adb5458410cbdb2be3f3",
            digest
        )
    }
}
