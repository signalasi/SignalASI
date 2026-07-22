package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentMcpLocalRuntimeTest {
    @Test
    fun decodesLastStructuredBridgeResultWithoutLeakingServerLogs() {
        val result = AgentMcpLocalRuntimeResponseCodec.decode(
            """
                server starting
                {"unrelated":true}
                __SIGNALASI_MCP_RESULT__{"ok":true,"result":{"tools":[{"name":"device.read"}]}}
            """.trimIndent()
        )

        assertEquals("device.read", result.getJSONArray("tools").getJSONObject(0).getString("name"))
    }

    @Test
    fun surfacesBoundedBridgeFailure() {
        val error = assertThrows(IllegalStateException::class.java) {
            AgentMcpLocalRuntimeResponseCodec.decode(
                "__SIGNALASI_MCP_RESULT__{\"ok\":false,\"error\":\"server authentication failed\"}"
            )
        }

        assertEquals("server authentication failed", error.message)
    }

    @Test
    fun rejectsUnstructuredRuntimeOutput() {
        assertThrows(IllegalStateException::class.java) {
            AgentMcpLocalRuntimeResponseCodec.decode("plain process output")
        }
    }
}
