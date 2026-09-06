package com.galaxyssi.chat

import org.junit.Assert.*
import org.junit.Test

class AgentConnectorResponseCodecTest {
    private val response = AgentConnectorResponse(17, "codex", "\u8fde\u63a5\u6b63\u5e38", "conversation", "turn", "task")

    @Test fun fullContentAndOutcomeRoundTrip() {
        val original = response.copy(content = "\u957f\u671f\u4efb\u52a1".repeat(20000), success = false,
            resolvedContactId = "backup", inputTokens = 11, outputTokens = 12, costMicros = 13,
            receivedAtMillis = 1, richOutputJson = "{\"blocks\":[]}")
        assertEquals(original, AgentConnectorResponseCodec.decode(AgentConnectorResponseCodec.encode(original)))
    }

    @Test fun everyRoutingDimensionSeparatesDurableIdentity() {
        val variants = listOf(response, response.copy(sourceMessageId = 18), response.copy(contactId = "hermes"),
            response.copy(conversationId = "other"), response.copy(turnId = "other"), response.copy(taskId = "other"))
        assertEquals(variants.size, variants.map(AgentConnectorResponseCodec::identity).toSet().size)
        variants.drop(1).forEach { assertFalse(AgentConnectorResponseCodec.matches(response, it)) }
    }

    @Test fun delimitersCannotAliasTupleIdentity() {
        assertNotEquals(AgentConnectorResponseCodec.identity(response.copy(contactId = "a:b", conversationId = "c")),
            AgentConnectorResponseCodec.identity(response.copy(contactId = "a", conversationId = "b:c")))
    }

    @Test fun payloadChangesDoNotChangeIdentity() {
        val duplicate = response.copy(content = "changed", receivedAtMillis = 99, success = false,
            resolvedContactId = "resolved")
        assertTrue(AgentConnectorResponseCodec.matches(response, duplicate))
        assertEquals(AgentConnectorResponseCodec.identity(response), AgentConnectorResponseCodec.identity(duplicate))
    }

    @Test fun turnKeysIncludeConversation() {
        assertNotEquals(AgentConnectorResponseCodec.turnKey("one", "turn"), AgentConnectorResponseCodec.turnKey("two", "turn"))
    }

    @Test fun missingTaskIsNotWildcard() {
        assertFalse(AgentConnectorResponseCodec.matches(response, response.copy(taskId = "")))
        assertFalse(AgentConnectorResponseCodec.matches(response, response.copy(turnId = "")))
    }

    @Test(expected = IllegalArgumentException::class) fun invalidSourceRejected() {
        AgentConnectorResponseCodec.decode(AgentConnectorResponseCodec.encode(response.copy(sourceMessageId = 0)))
    }

    @Test fun attachmentFailureRetainsScopeAndCodeThroughDurableCodec() {
        val failed = response.copy(success = false, deliveryFailureCode = "blob_expired")
        val restored = AgentConnectorResponseCodec.decode(AgentConnectorResponseCodec.encode(failed))
        assertEquals(failed, restored)
        assertTrue(AgentConnectorResponseCodec.matches(failed, restored))
        assertFalse(restored.remoteFailure)
    }

    @Test fun malformedAttachmentObservationsCannotMasqueradeAsModelOutcomes() {
        val failed = response.copy(success = false, deliveryFailureCode = "blob_expired")
        listOf(failed.copy(success = true), failed.copy(deliveryFailureCode = "private server error"),
            failed.copy(taskStatus = "completed"), failed.copy(taskStatus = "failed")).forEach {
            assertThrows(IllegalArgumentException::class.java) {
                AgentConnectorResponseCodec.decode(AgentConnectorResponseCodec.encode(it))
            }
        }
    }
}
