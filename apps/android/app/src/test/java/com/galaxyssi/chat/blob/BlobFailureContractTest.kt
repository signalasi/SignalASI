package com.galaxyssi.chat.blob

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BlobFailureContractTest {
    private fun manifest() = JSONObject().put("client_route_id", "route").put("conversation_id", "conversation")
        .put("task_id", "task").put("turn_id", "turn").put("attachment_id", "attachment")
        .put("transfer_id", "a".repeat(64)).put("contact_id", "codex").put("sha256", "b".repeat(64))
        .put("size_bytes", 123).put("client_message_id", 7).put("attachment_request_id", "request")

    @Test fun everyTerminalReceiptRemainsBoundToOriginalAttachment() {
        for (code in BlobFailureContract.terminalCodes) {
            val manifest = manifest()
            val receipt = BlobFailureContract.receipt(manifest, code)
            assertTrue(code, BlobFailureContract.matches(manifest, receipt))
            assertFalse(BlobOutgoingContract.receiptMatches(manifest, receipt))
            for (field in BlobOutgoingContract.binding(manifest).keys + listOf("sha256", "source_message_id",
                "attachment_request_id", "size_bytes")) {
                assertFalse(field, BlobFailureContract.matches(manifest, JSONObject(receipt.toString()).put(field, "other")))
            }
        }
    }

    @Test fun transientErrorsAndUnknownTextAreNotAcceptedAsTerminalFailures() {
        for (code in listOf("relay_timeout", "relay_connection_failed", "chunk_not_ready", "relay_storage_capacity",
            "paired_identity_unavailable", "transfer_cancelled", "secret-token /private/path")) {
            assertFalse(code in BlobFailureContract.terminalCodes)
            assertTrue(runCatching { BlobFailureContract.receipt(manifest(), code) }.isFailure)
            val receipt = BlobFailureContract.receipt(manifest(), "blob_expired").put("error_code", code)
            assertFalse(BlobFailureContract.matches(manifest(), receipt))
        }
    }

    @Test fun failureReceiptDoesNotCopyCredentialsOrMessageText() {
        val manifest = manifest().put("secret", "never-copy").put("text", "private prompt")
            .put("blob_offer", JSONObject().put("read_token", "token"))
        val receipt = BlobFailureContract.receipt(manifest, "blob_expired")
        assertFalse(receipt.has("secret")); assertFalse(receipt.has("text")); assertFalse(receipt.has("blob_offer"))
        assertEquals("7", receipt.getString("source_message_id"))
    }

    @Test fun modelObservationIncludesOnlyValidatedFailureAndFreshTransferInstruction() {
        BlobFailureContract.terminalCodes.forEach { code ->
            val observation = BlobFailureContract.observation(code)
            assertTrue(observation.contains(code))
            assertTrue(observation.contains("fresh transfer"))
            assertTrue(observation.contains("No verified attachment was delivered"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BlobFailureContract.observation("private path or access token")
        }
    }
}
