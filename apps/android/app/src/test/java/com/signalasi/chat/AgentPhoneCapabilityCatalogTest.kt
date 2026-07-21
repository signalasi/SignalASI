package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPhoneCapabilityCatalogTest {
    @Test
    fun catalogCoversEveryCapabilityWithAnHonestBoundary() {
        val capabilities = AgentPhoneCapabilityCatalog.capabilities

        assertEquals(AgentPhoneCapabilityId.entries.toSet(), capabilities.map { it.id }.toSet())
        assertEquals(capabilities.size, capabilities.map { it.id }.distinct().size)
        capabilities.forEach { capability ->
            assertTrue("${capability.id} must state user-consent requirements", capability.userConsent.isNotEmpty())
            assertTrue("${capability.id} must state an honest limitation", capability.limitation.isNotBlank())
            assertFalse("${capability.id} limitation must be specific", capability.limitation.equals("none", ignoreCase = true))
        }
    }

    @Test
    fun blockedCapabilityCannotBePromotedByAReadyProbe() {
        val blocked = AgentPhoneCapabilityCatalog.find(AgentPhoneCapabilityId.ROOT)
        val availability = AgentPhoneCapabilityPolicy.resolve(
            blocked,
            AgentPhoneCapabilityObservation(
                platformSupported = true,
                implementationPresent = true,
                permissionsGranted = true,
                specialAccessGranted = true,
                userConsentGranted = true,
                configured = true
            )
        )
        val status = AgentPhoneCapabilityStatus(blocked, availability, "permissive test probe")

        assertEquals(AgentPhoneCapabilityAvailability.BLOCKED_BY_POLICY, availability)
        assertFalse(status.advertisedAsReady)
    }

    @Test
    fun privilegedAndNonNormalAppCapabilitiesAreNeverAdvertisedReady() {
        val permissiveProbe = AgentPhoneCapabilityObservation()
        val privileged = AgentPhoneCapabilityCatalog.capabilities.filter { !it.normalAppCanExecute }

        assertTrue(privileged.isNotEmpty())
        privileged.forEach { boundary ->
            val availability = AgentPhoneCapabilityPolicy.resolve(boundary, permissiveProbe)
            val status = AgentPhoneCapabilityStatus(boundary, availability, "permissive test probe")
            assertNotEquals(AgentPhoneCapabilityAvailability.READY, availability)
            assertFalse(status.advertisedAsReady)
        }
    }

    @Test
    fun missingRuntimeGateIsReportedBeforeReady() {
        val camera = AgentPhoneCapabilityCatalog.find(AgentPhoneCapabilityId.CAMERA)

        assertEquals(
            AgentPhoneCapabilityAvailability.NEEDS_RUNTIME_PERMISSION,
            AgentPhoneCapabilityPolicy.resolve(
                camera,
                AgentPhoneCapabilityObservation(permissionsGranted = false)
            )
        )
        assertEquals(
            AgentPhoneCapabilityAvailability.READY,
            AgentPhoneCapabilityPolicy.resolve(camera, AgentPhoneCapabilityObservation())
        )
    }

    @Test
    fun implementedForegroundLocationCanBecomeReady() {
        val location = AgentPhoneCapabilityCatalog.find(AgentPhoneCapabilityId.LOCATION)

        assertEquals(
            AgentPhoneCapabilityAvailability.READY,
            AgentPhoneCapabilityPolicy.resolve(
                location,
                AgentPhoneCapabilityObservation(
                    platformSupported = true,
                    implementationPresent = true,
                    permissionsGranted = true
                )
            )
        )
    }

    @Test
    fun boundedHardwareCapabilitiesDeclareRegisteredNativeCoverage() {
        val expected = setOf(
            AgentPhoneCapabilityId.LOCATION,
            AgentPhoneCapabilityId.SENSORS,
            AgentPhoneCapabilityId.BLUETOOTH,
            AgentPhoneCapabilityId.NFC,
            AgentPhoneCapabilityId.BATTERY,
            AgentPhoneCapabilityId.NETWORK,
            AgentPhoneCapabilityId.INSTALLED_APPS,
            AgentPhoneCapabilityId.MEDIA_PLAYBACK
        )

        assertEquals(expected, AgentPhoneCapabilityNativeCoverage.toolIdsByCapability.keys)
        assertTrue(AgentPhoneCapabilityNativeCoverage.toolIdsByCapability.values.all { it.isNotEmpty() })
        val registeredIds = AgentHardwareNativeTools.toolIds +
            AgentAndroidSystemNativeTools.toolIds +
            AgentWebMediaNativeTools.toolIds
        assertTrue(
            AgentPhoneCapabilityNativeCoverage.toolIdsByCapability.values.flatten().all { it in registeredIds }
        )
        assertEquals(AgentPhoneCapabilityAvailability.LIMITED, AgentPhoneCapabilityCatalog.find(AgentPhoneCapabilityId.SENSORS).availability)
        assertEquals(AgentPhoneCapabilityAvailability.LIMITED, AgentPhoneCapabilityCatalog.find(AgentPhoneCapabilityId.BLUETOOTH).availability)
        assertEquals(AgentPhoneCapabilityAvailability.LIMITED, AgentPhoneCapabilityCatalog.find(AgentPhoneCapabilityId.NFC).availability)
    }
}
