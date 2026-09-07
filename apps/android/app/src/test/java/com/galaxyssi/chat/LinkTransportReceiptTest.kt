package com.galaxyssi.chat

import org.junit.Assert.*
import org.junit.Test

class LinkTransportReceiptTest {
    @Test fun everyRelationshipComponentFencesReceiptBinding() {
        val original = listOf("peer", "route", "local", "remote", "secret")
        fun binding(values: List<String>, phone: Boolean = false) = LinkTransportReceipt.binding(
            values[0], phone, values[1], values[2], values[3], values[4])
        val expected = binding(original)
        original.indices.forEach { index ->
            val changed = original.toMutableList().also { it[index] += "-new" }
            assertNotEquals(expected, binding(changed))
        }
        assertNotEquals(expected, binding(original, true))
        assertFalse(expected.contains("secret"))
    }

    @Test fun receiptEncodingRoundTripsAndSeparatesMessagesAndPeers() {
        val first = LinkTransportReceipt("peer", false, "a".repeat(64), "message")
        assertEquals(first, LinkTransportReceipt.from(first.json()))
        assertEquals(first.key, LinkTransportReceipt.from(first.json()).key)
        assertNotEquals(first.key, first.copy(peer = "other").key)
        assertNotEquals(first.key, first.copy(message = "other").key)
        assertNotEquals(first.key, first.copy(phone = true).key)
        assertNotEquals(first.key, first.copy(binding = "b".repeat(64)).key)
    }

    @Test fun ambiguousConcatenationCannotAliasBindings() {
        assertNotEquals(LinkTransportReceipt.binding("ab", false, "c", "d", "e", "f"),
            LinkTransportReceipt.binding("a", false, "bc", "d", "e", "f"))
    }

    @Test fun invalidReceiptIdentityCannotEnterTheJournal() {
        assertThrows(IllegalArgumentException::class.java) { LinkTransportReceipt("", false, "a".repeat(64), "message") }
        assertThrows(IllegalArgumentException::class.java) { LinkTransportReceipt("peer", false, "wrong", "message") }
        assertThrows(IllegalArgumentException::class.java) { LinkTransportReceipt("peer", false, "a".repeat(64), "") }
    }
}
