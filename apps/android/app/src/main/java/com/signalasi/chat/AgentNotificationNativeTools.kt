package com.signalasi.chat

import android.Manifest
import android.content.Context

interface AgentNotificationToolPlatform {
    val implementationId: String
    fun availability(): AgentNativeToolAvailability
    fun snapshot(limit: Int): AgentNotificationContext
    fun reply(notificationKey: String, text: String): AgentNotificationReplyResult
}

class AgentAndroidNotificationToolPlatform(context: Context) : AgentNotificationToolPlatform {
    private val appContext = context.applicationContext

    override val implementationId: String = "signalasi.android.notification_listener"

    override fun availability(): AgentNativeToolAvailability {
        val notificationContext = SignalASINotificationListenerService.currentContext(MAX_NOTIFICATIONS)
        return if (notificationContext.hasAccess) {
            AgentNativeToolAvailability.AVAILABLE
        } else {
            AgentNativeToolAvailability(
                AgentNativeToolAvailabilityStatus.REQUIRES_SETUP,
                "Android notification-listener access is not connected"
            )
        }
    }

    override fun snapshot(limit: Int): AgentNotificationContext =
        SignalASINotificationListenerService.currentContext(limit)

    override fun reply(notificationKey: String, text: String): AgentNotificationReplyResult =
        SignalASINotificationListenerService.reply(notificationKey, text)

    private companion object {
        const val MAX_NOTIFICATIONS = 12
    }
}

/** Typed notification read/reply tools with redaction, stale-target errors, and delivery honesty. */
object AgentNotificationNativeTools {
    const val NOTIFICATIONS_LIST = "signalasi.notifications.list"
    const val NOTIFICATION_REPLY = "signalasi.notifications.reply"

    const val LISTENER_PERMISSION = Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE
    const val LISTENER_SPECIAL_ACCESS = "android.special_access.notification_listener"
    const val READ_CONSENT = "signalasi.consent.notification_read"
    const val REPLY_CONSENT = "signalasi.consent.sensitive_action_confirmation"

    val toolIds: Set<String> = setOf(NOTIFICATIONS_LIST, NOTIFICATION_REPLY)

    fun definitions(
        platform: AgentNotificationToolPlatform,
        clock: AgentNativeClock = AgentNativeClock.SYSTEM
    ): List<AgentNativeToolDefinition> = listOf(
        definition(
            platform = platform,
            descriptor = descriptor(
                id = NOTIFICATIONS_LIST,
                title = "List current notifications",
                description = "Reads bounded posted notification fields; sensitive content is redacted before it reaches the Agent.",
                inputSchema = objectSchema(
                    properties = mapOf(
                        "limit" to AgentNativeJsonSchema.integer(1, MAX_NOTIFICATIONS.toLong()),
                        "reply_capable_only" to AgentNativeJsonSchema.boolean()
                    )
                ),
                outputSchema = listOutputSchema(),
                capabilities = setOf("notifications.posted.read", "notifications.sensitive.redaction"),
                consentId = READ_CONSENT,
                idempotency = AgentNativeToolIdempotency.IDEMPOTENT
            )
        ) { invocation ->
            val limit = invocation.input.int("limit", DEFAULT_LIMIT)
            val replyOnly = invocation.input.bool("reply_capable_only")
            val context = platform.snapshot(MAX_NOTIFICATIONS)
            if (!context.hasAccess) {
                return@definition AgentNativeToolExecutionResult.failure(
                    "notification_access_unavailable",
                    "Android notification-listener access is not connected",
                    retryable = true
                )
            }
            val candidates = context.items.filter { !replyOnly || (it.canReply && it.sensitiveFlags.isEmpty()) }
            val selected = candidates.take(limit)
            AgentNativeToolExecutionResult.success(
                output = mapOf(
                    "notifications" to selected.map(::notificationValue),
                    "result_count" to selected.size,
                    "total_observed" to context.totalCount.coerceAtLeast(context.items.size),
                    "truncated" to (candidates.size > limit || context.totalCount > context.items.size),
                    "sensitive_count" to context.items.count { it.sensitiveFlags.isNotEmpty() },
                    "observed_at_epoch_ms" to clock.nowEpochMillis()
                ),
                message = "Listed ${selected.size} current notifications with sensitive content redacted",
                metadata = mapOf(
                    "raw_sensitive_content_exposed" to false,
                    "notification_limit" to MAX_NOTIFICATIONS
                )
            )
        },
        definition(
            platform = platform,
            descriptor = descriptor(
                id = NOTIFICATION_REPLY,
                title = "Reply to a current notification",
                description = "Dispatches text through one live free-form RemoteInput; system, call, sensitive, and stale targets are rejected.",
                inputSchema = objectSchema(
                    properties = mapOf(
                        "notification_key" to AgentNativeJsonSchema.string(minLength = 1, maxLength = MAX_KEY_CHARACTERS),
                        "reply_text" to AgentNativeJsonSchema.string(minLength = 1, maxLength = MAX_REPLY_CHARACTERS)
                    ),
                    required = setOf("notification_key", "reply_text")
                ),
                outputSchema = replyOutputSchema(),
                capabilities = setOf("notifications.remote_input.reply", "notifications.stale_target_guard"),
                consentId = REPLY_CONSENT,
                idempotency = AgentNativeToolIdempotency.IDEMPOTENCY_KEY_REQUIRED
            )
        ) { invocation ->
            val key = invocation.input.string("notification_key").take(MAX_KEY_CHARACTERS)
            val text = invocation.input.string("reply_text").take(MAX_REPLY_CHARACTERS)
            val result = platform.reply(key, text)
            if (!result.success) {
                AgentNativeToolExecutionResult.failure(
                    code = result.code.ifBlank { "notification_reply_failed" },
                    message = result.message,
                    retryable = result.retryable,
                    details = mapOf(
                        "notification_key_sha256" to AgentNativeJsonCodec.sha256(key),
                        "package_name" to result.notificationPackage.take(255)
                    )
                )
            } else {
                AgentNativeToolExecutionResult.success(
                    output = mapOf(
                        "dispatch_accepted" to true,
                        "delivery_verified" to false,
                        "package_name" to result.notificationPackage.take(255),
                        "target_title" to result.notificationTitle.take(MAX_TITLE_CHARACTERS),
                        "reply_length" to text.length,
                        "notification_key_sha256" to AgentNativeJsonCodec.sha256(key),
                        "dispatched_at_epoch_ms" to clock.nowEpochMillis()
                    ),
                    message = result.message,
                    metadata = mapOf(
                        "handoff_only" to true,
                        "delivery_receipt_available" to false,
                        "reply_text_retained" to false
                    )
                )
            }
        }
    )

    fun androidDefinitions(
        context: Context,
        clock: AgentNativeClock = AgentNativeClock.SYSTEM
    ): List<AgentNativeToolDefinition> = definitions(
        AgentAndroidNotificationToolPlatform(context.applicationContext),
        clock
    )

    private fun definition(
        platform: AgentNotificationToolPlatform,
        descriptor: AgentNativeToolDescriptor,
        execute: (AgentNativeToolInvocation) -> AgentNativeToolExecutionResult
    ) = AgentNativeToolDefinition(
        descriptor = descriptor.copy(availability = platform.availability()),
        executor = AgentNativeToolExecutor { invocation ->
            invocation.checkpoint()
            execute(invocation)
        },
        executorId = EXECUTOR_ID,
        provenanceMetadata = mapOf(
            "implementation" to platform.implementationId,
            "source" to "android_notification_listener",
            "sensitive_content_policy" to "redact",
            "reply_completion_semantics" to "pending_intent_dispatched_not_delivered"
        ),
        availabilityProvider = AgentNativeToolAvailabilityProvider { platform.availability() }
    )

    private fun descriptor(
        id: String,
        title: String,
        description: String,
        inputSchema: AgentNativeJsonSchema,
        outputSchema: AgentNativeJsonSchema,
        capabilities: Set<String>,
        consentId: String,
        idempotency: AgentNativeToolIdempotency
    ) = AgentNativeToolDescriptor(
        id = id,
        version = VERSION,
        title = title,
        description = description,
        location = AgentNativeToolLocation.ANDROID_SYSTEM,
        inputSchema = inputSchema,
        outputSchema = outputSchema,
        risk = AgentNativeToolRisk.HIGH,
        capabilities = capabilities,
        requiredPermissions = listOf(
            AgentNativePermissionRequirement(
                LISTENER_PERMISSION,
                "Notification listener service",
                "Android binds the user-enabled notification listener."
            ),
            AgentNativePermissionRequirement(
                LISTENER_SPECIAL_ACCESS,
                "Notification access",
                "The user must enable SignalASI in Android notification access settings."
            )
        ),
        requiredConsents = listOf(
            AgentNativeConsentRequirement(
                consentId,
                if (consentId == READ_CONSENT) "Read current notifications" else "Send notification reply",
                if (consentId == READ_CONSENT) {
                    "Allows this invocation to read bounded, redacted notification metadata."
                } else {
                    "Confirms dispatch of this exact external reply."
                }
            )
        ),
        timeoutMillis = 10_000L,
        idempotency = idempotency,
        availability = AgentNativeToolAvailability.AVAILABLE
    )

    private fun notificationValue(item: AgentNotificationItem): AgentNativeJsonObject {
        val redacted = item.sensitiveFlags.isNotEmpty()
        return linkedMapOf(
            "notification_key" to if (redacted) "" else item.key.take(MAX_KEY_CHARACTERS),
            "package_name" to item.packageName.take(255),
            "title" to if (redacted) "" else item.title.take(MAX_TITLE_CHARACTERS),
            "text_preview" to if (redacted) "" else item.textPreview.take(MAX_TEXT_PREVIEW_CHARACTERS),
            "category" to item.category.take(32),
            "posted_at_epoch_ms" to item.postedAtMillis.coerceAtLeast(0L),
            "can_reply" to (item.canReply && !redacted),
            "redacted" to redacted,
            "sensitive_flag_count" to item.sensitiveFlags.size
        )
    }

    private fun listOutputSchema() = objectSchema(
        properties = mapOf(
            "notifications" to AgentNativeJsonSchema.array(notificationSchema(), maxItems = MAX_NOTIFICATIONS),
            "result_count" to AgentNativeJsonSchema.integer(0, MAX_NOTIFICATIONS.toLong()),
            "total_observed" to AgentNativeJsonSchema.integer(minimum = 0),
            "truncated" to AgentNativeJsonSchema.boolean(),
            "sensitive_count" to AgentNativeJsonSchema.integer(minimum = 0),
            "observed_at_epoch_ms" to AgentNativeJsonSchema.integer(minimum = 0)
        ),
        required = setOf(
            "notifications",
            "result_count",
            "total_observed",
            "truncated",
            "sensitive_count",
            "observed_at_epoch_ms"
        )
    )

    private fun notificationSchema() = objectSchema(
        properties = mapOf(
            "notification_key" to AgentNativeJsonSchema.string(maxLength = MAX_KEY_CHARACTERS),
            "package_name" to AgentNativeJsonSchema.string(maxLength = 255),
            "title" to AgentNativeJsonSchema.string(maxLength = MAX_TITLE_CHARACTERS),
            "text_preview" to AgentNativeJsonSchema.string(maxLength = MAX_TEXT_PREVIEW_CHARACTERS),
            "category" to AgentNativeJsonSchema.string(maxLength = 32),
            "posted_at_epoch_ms" to AgentNativeJsonSchema.integer(minimum = 0),
            "can_reply" to AgentNativeJsonSchema.boolean(),
            "redacted" to AgentNativeJsonSchema.boolean(),
            "sensitive_flag_count" to AgentNativeJsonSchema.integer(minimum = 0, maximum = 32)
        ),
        required = setOf(
            "notification_key",
            "package_name",
            "title",
            "text_preview",
            "category",
            "posted_at_epoch_ms",
            "can_reply",
            "redacted",
            "sensitive_flag_count"
        )
    )

    private fun replyOutputSchema() = objectSchema(
        properties = mapOf(
            "dispatch_accepted" to AgentNativeJsonSchema.boolean(),
            "delivery_verified" to AgentNativeJsonSchema.boolean(),
            "package_name" to AgentNativeJsonSchema.string(maxLength = 255),
            "target_title" to AgentNativeJsonSchema.string(maxLength = MAX_TITLE_CHARACTERS),
            "reply_length" to AgentNativeJsonSchema.integer(1, MAX_REPLY_CHARACTERS.toLong()),
            "notification_key_sha256" to AgentNativeJsonSchema.string(minLength = 64, maxLength = 64),
            "dispatched_at_epoch_ms" to AgentNativeJsonSchema.integer(minimum = 0)
        ),
        required = setOf(
            "dispatch_accepted",
            "delivery_verified",
            "package_name",
            "target_title",
            "reply_length",
            "notification_key_sha256",
            "dispatched_at_epoch_ms"
        )
    )

    private fun objectSchema(
        properties: Map<String, AgentNativeJsonSchema> = emptyMap(),
        required: Set<String> = emptySet()
    ) = AgentNativeJsonSchema.objectSchema(
        properties = properties,
        required = required,
        additionalProperties = false
    )

    private fun AgentNativeJsonObject.string(key: String): String = this[key]?.toString().orEmpty().trim()
    private fun AgentNativeJsonObject.int(key: String, fallback: Int): Int =
        ((this[key] as? Number)?.toInt() ?: fallback).coerceIn(1, MAX_NOTIFICATIONS)
    private fun AgentNativeJsonObject.bool(key: String): Boolean = this[key] as? Boolean ?: false

    private const val VERSION = "1.0.0"
    private const val EXECUTOR_ID = "signalasi.android_notification_tools"
    private const val DEFAULT_LIMIT = 6
    private const val MAX_NOTIFICATIONS = 12
    private const val MAX_KEY_CHARACTERS = 8_192
    private const val MAX_REPLY_CHARACTERS = 2_000
    private const val MAX_TITLE_CHARACTERS = 160
    private const val MAX_TEXT_PREVIEW_CHARACTERS = 320
}
