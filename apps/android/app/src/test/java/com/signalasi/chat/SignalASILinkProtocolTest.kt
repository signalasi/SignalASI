package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
}
