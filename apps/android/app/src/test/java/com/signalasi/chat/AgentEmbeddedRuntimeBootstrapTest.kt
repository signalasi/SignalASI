package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentEmbeddedRuntimeBootstrapTest {
    @Test
    fun `embedded runtime index requires Linux base and Python uv`() {
        val bundle = AgentEmbeddedRuntimeBundleCodec.decode(indexJson())

        assertEquals(listOf("linux-base", "python-uv"), bundle.packs.map { it.id })
        assertEquals(listOf("linux-base"), bundle.packs.last().dependencies)
    }

    @Test
    fun `embedded runtime index rejects an incomplete default environment`() {
        val invalid = """
            {"format_version":1,"architecture":"arm64-v8a","packs":[
              {"pack_id":"linux-base","version":"1.0.0","architecture":"arm64-v8a","asset_path":"runtime/bootstrap/linux-base.sarpack","archive_sha256":"${"a".repeat(64)}","archive_size_bytes":1024,"installed_size_bytes":2048,"dependencies":[]}
            ]}
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            AgentEmbeddedRuntimeBundleCodec.decode(invalid)
        }
    }

    @Test
    fun `embedded defaults never downgrade a newer installed pack`() {
        assertEquals(1, AgentEmbeddedRuntimeBootstrap.compareVersions("1.2.0", "1.1.9"))
        assertEquals(0, AgentEmbeddedRuntimeBootstrap.compareVersions("1.2.0", "1.2.0"))
        assertEquals(-1, AgentEmbeddedRuntimeBootstrap.compareVersions("1.1.9", "1.2.0"))
        assertEquals(1, AgentEmbeddedRuntimeBootstrap.compareVersions("1.2.0", "1.2.0-rc.1"))
        assertEquals(0, AgentEmbeddedRuntimeBootstrap.compareVersions("1.2.0+build.2", "1.2.0+build.1"))
    }

    private fun indexJson(): String = """
        {"format_version":1,"architecture":"arm64-v8a","packs":[
          {"pack_id":"linux-base","version":"1.0.0","architecture":"arm64-v8a","asset_path":"runtime/bootstrap/linux-base.sarpack","archive_sha256":"${"a".repeat(64)}","archive_size_bytes":1024,"installed_size_bytes":2048,"dependencies":[]},
          {"pack_id":"python-uv","version":"1.0.0","architecture":"arm64-v8a","asset_path":"runtime/bootstrap/python-uv.sarpack","archive_sha256":"${"b".repeat(64)}","archive_size_bytes":2048,"installed_size_bytes":4096,"dependencies":["linux-base"]}
        ]}
    """.trimIndent()
}
