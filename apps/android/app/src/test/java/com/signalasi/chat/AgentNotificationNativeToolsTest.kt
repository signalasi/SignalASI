package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentNotificationNativeToolsTest {
    @Test
    fun listsBoundedNotificationsAndRedactsSensitiveContent() {
        val platform = FakeNotificationPlatform()
        val registry = registry(platform)
        val descriptor = requireNotNull(registry.lookup(AgentNotificationNativeTools.NOTIFICATIONS_LIST)).descriptor

        val result = registry.invoke(
            AgentNotificationNativeTools.NOTIFICATIONS_LIST,
            mapOf("limit" to 12),
            grantedContext(descriptor)
        )

        assertTrue(result.toJson(), result.isSuccess)
        val notifications = result.output["notifications"] as List<*>
        assertEquals(2, notifications.size)
        val normal = notifications[0] as Map<*, *>
        val sensitive = notifications[1] as Map<*, *>
        assertEquals("Ready", normal["text_preview"])
        assertEquals(false, normal["redacted"])
        assertEquals("", sensitive["notification_key"])
        assertEquals("", sensitive["title"])
        assertEquals("", sensitive["text_preview"])
        assertEquals(true, sensitive["redacted"])
        assertEquals(false, sensitive["can_reply"])
        assertEquals(false, result.metadata["raw_sensitive_content_exposed"])
    }

    @Test
    fun staleNotificationReturnsExplicitRetryableFailure() {
        val platform = FakeNotificationPlatform().apply {
            replyResult = AgentNotificationReplyResult(
                success = false,
                message = "The notification is no longer available",
                code = "notification_stale",
                retryable = true
            )
        }
        val registry = registry(platform)
        val descriptor = requireNotNull(registry.lookup(AgentNotificationNativeTools.NOTIFICATION_REPLY)).descriptor

        val result = registry.invoke(
            AgentNotificationNativeTools.NOTIFICATION_REPLY,
            mapOf("notification_key" to "stale-key", "reply_text" to "Hello"),
            grantedContext(descriptor, idempotencyKey = "reply-stale-1")
        )

        assertEquals(AgentNativeToolResultStatus.FAILED, result.status)
        assertEquals("notification_stale", result.error?.code)
        assertEquals(true, result.error?.retryable)
        assertFalse(result.toJson().contains("Hello"))
    }

    @Test
    fun replyIsIdempotentAndReportsDispatchNotDelivery() {
        val platform = FakeNotificationPlatform()
        val registry = registry(platform)
        val descriptor = requireNotNull(registry.lookup(AgentNotificationNativeTools.NOTIFICATION_REPLY)).descriptor
        val input = mapOf("notification_key" to "reply-key", "reply_text" to "Hello")
        val context = grantedContext(descriptor, idempotencyKey = "reply-once-1")

        val first = registry.invoke(AgentNotificationNativeTools.NOTIFICATION_REPLY, input, context)
        val replay = registry.invoke(
            AgentNotificationNativeTools.NOTIFICATION_REPLY,
            input,
            context.copy(invocationId = "reply-replay")
        )

        assertTrue(first.toJson(), first.isSuccess)
        assertEquals(true, first.output["dispatch_accepted"])
        assertEquals(false, first.output["delivery_verified"])
        assertEquals(true, first.metadata["handoff_only"])
        assertTrue(replay.receipt.replayed)
        assertEquals(1, platform.replyCalls)
    }

    @Test
    fun missingReplyConfirmationPreventsDispatch() {
        val platform = FakeNotificationPlatform()
        val registry = registry(platform)
        val descriptor = requireNotNull(registry.lookup(AgentNotificationNativeTools.NOTIFICATION_REPLY)).descriptor

        val result = registry.invoke(
            AgentNotificationNativeTools.NOTIFICATION_REPLY,
            mapOf("notification_key" to "reply-key", "reply_text" to "Hello"),
            grantedContext(descriptor, idempotencyKey = "reply-denied").copy(grantedConsents = emptySet())
        )

        assertEquals(AgentNativeToolResultStatus.REJECTED, result.status)
        assertEquals("missing_consents", result.error?.code)
        assertEquals(0, platform.replyCalls)
    }

    private fun registry(platform: AgentNotificationToolPlatform) =
        AgentNativeToolRegistry().registerAll(AgentNotificationNativeTools.definitions(platform))

    private fun grantedContext(
        descriptor: AgentNativeToolDescriptor,
        idempotencyKey: String? = null
    ) = AgentNativeToolInvocationContext(
        idempotencyKey = idempotencyKey,
        grantedPermissions = descriptor.requiredPermissions.map { it.id }.toSet(),
        grantedConsents = descriptor.requiredConsents.map { it.id }.toSet()
    )

    private class FakeNotificationPlatform : AgentNotificationToolPlatform {
        override val implementationId = "fake.notification.platform"
        var replyCalls = 0
        var replyResult = AgentNotificationReplyResult(
            success = true,
            message = "Reply dispatched",
            code = "notification_reply_dispatched",
            notificationPackage = "com.example.chat",
            notificationTitle = "Example"
        )

        override fun availability(): AgentNativeToolAvailability = AgentNativeToolAvailability.AVAILABLE

        override fun snapshot(limit: Int) = AgentNotificationContext(
            hasAccess = true,
            items = listOf(
                AgentNotificationItem(
                    key = "normal-key",
                    packageName = "com.example.chat",
                    title = "Build",
                    textPreview = "Ready",
                    category = "chat",
                    postedAtMillis = 100,
                    canReply = true
                ),
                AgentNotificationItem(
                    key = "secret-key",
                    packageName = "com.example.auth",
                    title = "Verification code",
                    textPreview = "Code 123456",
                    category = "sms",
                    postedAtMillis = 90,
                    canReply = true,
                    sensitiveFlags = listOf("verification_code")
                )
            ).take(limit),
            sensitiveFlags = listOf("verification_code"),
            totalCount = 2
        )

        override fun reply(notificationKey: String, text: String): AgentNotificationReplyResult {
            replyCalls += 1
            return replyResult
        }
    }
}
