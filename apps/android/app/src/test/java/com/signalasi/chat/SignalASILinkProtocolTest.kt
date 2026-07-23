package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class SignalASILinkProtocolTest {
    @Test
    fun routeIdsAreOpaque128BitBase64UrlValues() {
        val first = SignalASILinkProtocol.newRouteId()
        val second = SignalASILinkProtocol.newRouteId()
        assertEquals(22, first.length)
        assertTrue(SignalASILinkProtocol.validRouteId(first))
        assertNotEquals(first, second)
    }

    @Test
    fun relationshipTopicsAreDerivedFromBothRoutes() {
        val server = SignalASILinkProtocol.newRouteId()
        val client = SignalASILinkProtocol.newRouteId()
        val routes = SignalASILinkProtocol.Routes(server, client)
        assertEquals("signalasichat/v1/$server/pair", routes.pairing)
        assertEquals("signalasichat/v1/$server/$client/up", routes.up)
        assertEquals("signalasichat/v1/$server/$client/down", routes.down)
        assertEquals("signalasichat/v1/$server/$client/control", routes.control)
    }

    @Test
    fun rotatingRelationshipDropsOnlyMessagesForItsOldTopics() {
        val oldRoutes = SignalASILinkProtocol.Routes(
            SignalASILinkProtocol.newRouteId(),
            SignalASILinkProtocol.newRouteId()
        )
        val otherRoutes = SignalASILinkProtocol.Routes(
            SignalASILinkProtocol.newRouteId(),
            SignalASILinkProtocol.newRouteId()
        )
        val source = JSONArray()
            .put(outboxMessage("old-up", oldRoutes.up))
            .put(outboxMessage("old-control", oldRoutes.control))
            .put(outboxMessage("other-up", otherRoutes.up))

        val kept = SignalASILinkDeliveryStore.retainMessagesOutsideTopics(
            source,
            setOf(oldRoutes.up, oldRoutes.down, oldRoutes.control, oldRoutes.pairing)
        )

        assertEquals(1, kept.length())
        assertEquals("other-up", kept.getJSONObject(0).getString("message_id"))
        assertEquals(3, source.length())
    }

    private fun outboxMessage(id: String, topic: String): JSONObject = JSONObject()
        .put("message_id", id)
        .put("topic", topic)
        .put("wire_payload", "{}")
}
