package com.galaxyssi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingConfirmationDeliveryPolicyTest {
    @Test
    fun `replayed confirmation keeps one stable message identity`() {
        val first = PairingConfirmationDeliveryPolicy.messageId("", "desktop-a", "route-phone-a")
        val replay = PairingConfirmationDeliveryPolicy.messageId("", "desktop-a", "route-phone-a")

        assertEquals("pairing-confirmed:desktop-a:route-phone-a", first)
        assertEquals(first, replay)
    }

    @Test
    fun `desktop supplied message identity wins`() {
        assertEquals(
            "pairing-event-42",
            PairingConfirmationDeliveryPolicy.messageId(
                "pairing-event-42",
                "desktop-a",
                "route-phone-a"
            )
        )
    }

    @Test
    fun `existing Signal session is never rebuilt for a confirmation replay`() {
        assertTrue(PairingConfirmationDeliveryPolicy.needsSessionBootstrap(false))
        assertFalse(PairingConfirmationDeliveryPolicy.needsSessionBootstrap(true))
    }

    @Test
    fun `new pairing route bootstraps even when a previous route has a Signal session`() {
        assertTrue(PairingConfirmationDeliveryPolicy.needsSessionBootstrap(true, routePaired = false))
        assertTrue(PairingConfirmationDeliveryPolicy.needsSessionBootstrap(false, routePaired = false))
        assertFalse(PairingConfirmationDeliveryPolicy.needsSessionBootstrap(true, routePaired = true))
    }

    @Test
    fun `only the first durable confirmation starts bootstrap and UI delivery`() {
        assertTrue(
            PairingConfirmationDeliveryPolicy.isFirstDelivery(
                GalaxySSILinkDeliveryStore.IncomingStageResult.STAGED
            )
        )
        assertFalse(
            PairingConfirmationDeliveryPolicy.isFirstDelivery(
                GalaxySSILinkDeliveryStore.IncomingStageResult.PENDING
            )
        )
        assertFalse(
            PairingConfirmationDeliveryPolicy.isFirstDelivery(
                GalaxySSILinkDeliveryStore.IncomingStageResult.COMPLETED
            )
        )
    }
}
