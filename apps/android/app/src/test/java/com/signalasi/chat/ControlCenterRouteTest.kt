package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControlCenterRouteTest {
    @Test
    fun everyRouteRoundTripsThroughItsStableWireValue() {
        ControlCenterRoute.entries.forEach { route ->
            assertEquals(route, ControlCenterRoute.fromWireValue(route.wireValue))
            assertEquals(route, ControlCenterRoute.fromWireValue("  ${route.wireValue.uppercase()}  "))
        }
    }

    @Test
    fun unknownRouteIsRejected() {
        assertNull(ControlCenterRoute.fromWireValue("not-a-control-center-route"))
    }
}
