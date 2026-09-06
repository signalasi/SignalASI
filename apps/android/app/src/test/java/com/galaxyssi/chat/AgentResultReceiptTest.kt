package com.galaxyssi.chat

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentResultReceiptTest {
    private fun payload() = JSONObject().put("client_route_id", "route").put("conversation_id", "conversation")
        .put("task_id", "task").put("turn_id", "turn").put("contact_id", "contact").put("source_message_id", "42")
        .put("agent_id", "codex").put("execution_generation", 2).put("sha256", "a".repeat(64))

    @Test fun stableIdentityIgnoresOutputAndJsonKeyOrder() {
        val first = AgentResultReceipt.from(payload(), "desktop")!!
        val reordered = JSONObject()
        payload().keys().asSequence().toList().reversed().forEach { reordered.put(it, payload().get(it)) }
        reordered.put("content", "private answer").put("rich_output", "large")
        val next = AgentResultReceipt.from(reordered, "desktop")!!
        assertEquals(first, next)
        assertEquals(first.id, next.id)
        assertFalse(next.payload().toString().contains("private answer"))
        assertFalse(next.payload().has("rich_output"))
    }

    @Test fun everyIdentityDimensionChangesReceipt() {
        val first = AgentResultReceipt.from(payload(), "desktop")!!
        for (field in AgentResultRecoveryClient.FIELDS) {
            val value = payload().put(field, if (field == "source_message_id") "43" else "other")
            assertNotEquals(first.id, AgentResultReceipt.from(value, "desktop")!!.id)
        }
        assertNotEquals(first.id, AgentResultReceipt.from(payload(), "other")!!.id)
        assertNotEquals(first.id, AgentResultReceipt.from(payload().put("execution_generation", 3), "desktop")!!.id)
        assertNotEquals(first.id, AgentResultReceipt.from(payload().put("sha256", "b".repeat(64)), "desktop")!!.id)
    }

    @Test fun confirmationRequiresFullIdentityDigestAndAuthenticatedDesktop() {
        val receipt = AgentResultReceipt.from(payload(), "desktop")!!
        val confirmation = receipt.payload("agent_task_result_receipt_confirmed")
        assertEquals(receipt, AgentResultReceipt.confirmed(confirmation, "desktop"))
        assertNull(AgentResultReceipt.confirmed(confirmation, "other-desktop"))
        for (field in AgentResultRecoveryClient.FIELDS + listOf("sha256", "receipt_id", "type")) {
            assertNull(AgentResultReceipt.confirmed(JSONObject(confirmation.toString()).put(field, "wrong"), "desktop"))
        }
        assertNull(AgentResultReceipt.confirmed(confirmation.put("execution_generation", 3), "desktop"))
    }

    @Test fun invalidAndPrivateFieldsCannotCreateReceipts() {
        for (field in AgentResultRecoveryClient.FIELDS) assertNull(AgentResultReceipt.from(payload().put(field, ""), "desktop"))
        for (generation in listOf(0, -1, "2", 2.5)) assertNull(AgentResultReceipt.from(payload().put("execution_generation", generation), "desktop"))
        assertNull(AgentResultReceipt.from(payload().put("source_message_id", "-1"), "desktop"))
        assertNull(AgentResultReceipt.from(payload().put("conversation_id", "self-evolution:test"), "desktop"))
        assertNull(AgentResultReceipt.from(payload().put("sha256", "invalid"), "desktop"))
    }

    @Test fun receiptMustMatchTheReplyBeingCommitted() {
        val receipt = AgentResultReceipt.from(payload(), "desktop")!!
        val response = AgentConnectorResponse(42, "contact", "answer", "conversation", "turn", "task", executionGeneration = 2)
        assertTrue(receipt.matches(response))
        for (different in listOf(response.copy(sourceMessageId = 43), response.copy(contactId = "other"),
            response.copy(conversationId = "other"), response.copy(turnId = "other"), response.copy(taskId = "other"),
            response.copy(executionGeneration = 3))) assertFalse(receipt.matches(different))
    }

    @Test fun retryBackoffNeverDiscardsAReceiptAfterManyAttempts() {
        assertEquals(5_000L, AgentResultReceiptJournal.retryDelay(0))
        assertEquals(10_000L, AgentResultReceiptJournal.retryDelay(1))
        assertEquals(300_000L, AgentResultReceiptJournal.retryDelay(100000))
        assertEquals(5_000L, AgentResultReceiptJournal.retryDelay(-1))
    }
}
