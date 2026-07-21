package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentVisibleCaptureNativeToolsTest {
    @Test
    fun registersExplicitUserVisibleCaptureContracts() {
        val definitions = AgentVisibleCaptureNativeTools.definitions(FakeCapturePlatform())

        assertEquals(AgentVisibleCaptureNativeTools.toolIds, definitions.map { it.descriptor.id }.toSet())
        definitions.forEach { definition ->
            assertEquals(AgentNativeToolRisk.HIGH, definition.descriptor.risk)
            assertTrue(
                definition.descriptor.requiredConsents.any {
                    it.id == AgentVisibleCaptureNativeTools.USER_VISIBLE_CAPTURE_CONSENT
                }
            )
            assertEquals("false", definition.provenanceMetadata["background_capture"])
        }
    }

    @Test
    fun returnsBoundedPhotoAndAudioArtifactReceipts() {
        val registry = registry(FakeCapturePlatform())
        val photoDescriptor = requireNotNull(registry.lookup(AgentVisibleCaptureNativeTools.CAMERA_CAPTURE)).descriptor
        val photo = registry.invoke(
            AgentVisibleCaptureNativeTools.CAMERA_CAPTURE,
            mapOf("facing" to "back"),
            grantedContext(photoDescriptor)
        )
        val audioDescriptor = requireNotNull(registry.lookup(AgentVisibleCaptureNativeTools.MICROPHONE_RECORD)).descriptor
        val audio = registry.invoke(
            AgentVisibleCaptureNativeTools.MICROPHONE_RECORD,
            mapOf("max_duration_seconds" to 2),
            grantedContext(audioDescriptor)
        )

        assertTrue(photo.toJson(), photo.isSuccess)
        assertEquals("photo", photo.output["kind"])
        assertEquals("content://signalasi.test/photo/1", photo.output["content_uri"])
        assertEquals(true, photo.output["user_visible"])
        assertEquals(AgentNativeVerificationStatus.PASSED, photo.verification?.status)
        assertTrue(audio.toJson(), audio.isSuccess)
        assertEquals("audio", audio.output["kind"])
        assertEquals(2_000L, audio.output["duration_ms"])
        assertEquals(true, audio.output["user_visible"])
    }

    @Test
    fun rejectsCaptureWithoutVisibleConsentAndDoesNotCallPlatform() {
        val platform = FakeCapturePlatform()
        val registry = registry(platform)
        val descriptor = requireNotNull(registry.lookup(AgentVisibleCaptureNativeTools.CAMERA_CAPTURE)).descriptor
        val context = grantedContext(descriptor).copy(
            grantedConsents = descriptor.requiredConsents.map { it.id }.toSet() -
                AgentVisibleCaptureNativeTools.USER_VISIBLE_CAPTURE_CONSENT
        )

        val result = registry.invoke(
            AgentVisibleCaptureNativeTools.CAMERA_CAPTURE,
            mapOf("facing" to "back"),
            context
        )

        assertEquals(AgentNativeToolResultStatus.REJECTED, result.status)
        assertEquals("missing_consents", result.error?.code)
        assertEquals(0, platform.photoCalls)
    }

    @Test
    fun unavailableHardwareIsNotAdvertisedOrInvoked() {
        val platform = FakeCapturePlatform(
            currentAvailability = AgentNativeToolAvailability(
                AgentNativeToolAvailabilityStatus.UNAVAILABLE,
                "No capture hardware"
            )
        )
        val registry = registry(platform)
        val descriptor = requireNotNull(registry.lookup(AgentVisibleCaptureNativeTools.CAMERA_CAPTURE)).descriptor

        assertEquals(AgentNativeToolAvailabilityStatus.UNAVAILABLE, registry.descriptors().first {
            it.id == AgentVisibleCaptureNativeTools.CAMERA_CAPTURE
        }.availability.status)
        val result = registry.invoke(
            AgentVisibleCaptureNativeTools.CAMERA_CAPTURE,
            emptyMap(),
            grantedContext(descriptor)
        )

        assertEquals(AgentNativeToolResultStatus.UNAVAILABLE, result.status)
        assertEquals(0, platform.photoCalls)
        assertFalse(result.isSuccess)
    }

    private fun registry(platform: AgentVisibleCapturePlatform) =
        AgentNativeToolRegistry().registerAll(AgentVisibleCaptureNativeTools.definitions(platform))

    private fun grantedContext(descriptor: AgentNativeToolDescriptor) = AgentNativeToolInvocationContext(
        grantedPermissions = descriptor.requiredPermissions.map { it.id }.toSet(),
        grantedConsents = descriptor.requiredConsents.map { it.id }.toSet()
    )

    private class FakeCapturePlatform(
        var currentAvailability: AgentNativeToolAvailability = AgentNativeToolAvailability.AVAILABLE
    ) : AgentVisibleCapturePlatform {
        override val implementationId = "fake.visible.capture"
        var photoCalls = 0

        override fun availability(kind: AgentVisibleCaptureKind): AgentNativeToolAvailability = currentAvailability

        override fun capturePhoto(
            facing: String,
            invocation: AgentNativeToolInvocation
        ): AgentVisibleCaptureOutcome {
            photoCalls += 1
            return AgentVisibleCaptureOutcome(
                AgentVisibleCaptureStatus.SUCCEEDED,
                AgentVisibleCaptureArtifact(
                    kind = AgentVisibleCaptureKind.PHOTO,
                    contentUri = "content://signalasi.test/photo/1",
                    mimeType = "image/jpeg",
                    sizeBytes = 8_192,
                    widthPixels = 1_920,
                    heightPixels = 1_080,
                    capturedAtEpochMillis = 1_000,
                    completedBy = "autofocus_capture"
                )
            )
        }

        override fun recordAudio(
            maxDurationSeconds: Int,
            invocation: AgentNativeToolInvocation
        ): AgentVisibleCaptureOutcome = AgentVisibleCaptureOutcome(
            AgentVisibleCaptureStatus.SUCCEEDED,
            AgentVisibleCaptureArtifact(
                kind = AgentVisibleCaptureKind.AUDIO,
                contentUri = "content://signalasi.test/audio/1",
                mimeType = "audio/mp4",
                sizeBytes = 4_096,
                durationMillis = maxDurationSeconds * 1_000L,
                capturedAtEpochMillis = 1_000,
                completedBy = "max_duration"
            )
        )
    }
}
