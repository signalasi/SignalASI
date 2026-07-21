package com.signalasi.chat

import java.util.Locale

object GlobalConversationEventPolicy {
    fun normalize(event: GlobalConversationEvent): GlobalConversationEvent? {
        val id = boundedIdentifier(event.id)
        val conversationId = boundedIdentifier(event.conversationId)
        if (id.isBlank() || conversationId.isBlank()) return null
        val lifecycleOnly = event.sensitivity == GlobalConversationSensitivity.SESSION_PRIVATE ||
            GlobalEventPublisherContract.requiresLifecycleSanitization(event)
        if (lifecycleOnly && event.type !in PRIVATE_LIFECYCLE_EVENT_TYPES) return null
        if (!lifecycleOnly && !GlobalEventPublisherContract.isAuthorizedForGlobalStream(event)) return null
        val sourceMetadata = if (lifecycleOnly) {
            event.metadata
        } else {
            GlobalEventPublisherContract.canonicalMetadata(event)
        }
        return event.copy(
            id = id,
            conversationId = conversationId,
            messageId = boundedIdentifier(event.messageId),
            timestampMillis = event.timestampMillis.coerceAtLeast(0L),
            content = if (lifecycleOnly) "" else cleanText(event.content, MAX_CONTENT_CHARACTERS),
            contentRef = if (lifecycleOnly) "" else sanitizeContentRef(event.contentRef),
            conversationTitle = if (lifecycleOnly) "" else cleanText(event.conversationTitle, MAX_TITLE_CHARACTERS),
            topicHints = if (lifecycleOnly) emptySet() else event.topicHints.asSequence()
                .map { cleanText(it, MAX_TOPIC_HINT_CHARACTERS) }
                .filter(String::isNotBlank)
                .distinct()
                .take(MAX_TOPIC_HINTS)
                .toSet(),
            sensitivity = if (lifecycleOnly) {
                GlobalConversationSensitivity.SESSION_PRIVATE
            } else event.sensitivity,
            metadata = normalizeMetadata(sourceMetadata, lifecycleOnly),
            causalEventIds = normalizeIdentifiers(event.causalEventIds),
            retractedEventIds = normalizeIdentifiers(event.retractedEventIds)
        )
    }

    private fun normalizeMetadata(
        metadata: Map<String, String>,
        sessionPrivate: Boolean
    ): Map<String, String> = buildMap {
        metadata.entries.asSequence()
            .mapNotNull { (rawKey, rawValue) ->
                val key = rawKey.trim().take(MAX_METADATA_KEY_CHARACTERS)
                if (key.isBlank() || credentialKey(key)) return@mapNotNull null
                if (sessionPrivate && key.lowercase(Locale.ROOT) !in PRIVATE_LIFECYCLE_METADATA_KEYS) {
                    return@mapNotNull null
                }
                key to cleanText(rawValue, MAX_METADATA_VALUE_CHARACTERS)
            }
            .take(MAX_METADATA_ENTRIES)
            .forEach { (key, value) -> put(key, value) }
    }

    private fun normalizeIdentifiers(values: Set<String>): Set<String> = values.asSequence()
        .map(::boundedIdentifier)
        .filter(String::isNotBlank)
        .distinct()
        .take(MAX_CAUSAL_IDENTIFIERS)
        .toSet()

    private fun boundedIdentifier(value: String): String = value.trim().take(MAX_IDENTIFIER_CHARACTERS)

    private fun cleanText(value: String, maximumCharacters: Int): String = value
        .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), " ")
        .replace(Regex("[ \\t]+"), " ")
        .trim()
        .take(maximumCharacters)

    private fun sanitizeContentRef(value: String): String {
        val clean = cleanText(value, MAX_CONTENT_REF_CHARACTERS)
        if (clean.isBlank() || clean.startsWith("encrypted://")) return clean
        return clean.substringBefore('?').substringBefore('#').take(MAX_CONTENT_REF_CHARACTERS)
    }

    private fun credentialKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.ROOT).replace('-', '_')
        return normalized in CREDENTIAL_KEYS || CREDENTIAL_SUFFIXES.any(normalized::endsWith)
    }

    private val CREDENTIAL_KEYS = setOf(
        "access_token",
        "api_key",
        "apikey",
        "authorization",
        "bearer",
        "client_secret",
        "cookie",
        "credential",
        "credentials",
        "password",
        "passwd",
        "private_key",
        "refresh_token",
        "secret",
        "set_cookie",
        "token"
    )
    private val CREDENTIAL_SUFFIXES = setOf(
        "_access_token",
        "_api_key",
        "_apikey",
        "_client_secret",
        "_credential",
        "_password",
        "_private_key",
        "_refresh_token",
        "_secret",
        "_token"
    )
    private val PRIVATE_LIFECYCLE_METADATA_KEYS = setOf(
        "changed_fields",
        "conversation_status",
        "created_by_agent",
        "global_visibility",
        "merged_at_millis",
        "merged_into_conversation_id",
        "origin",
        "parent_conversation_id",
        "private_mode",
        "tracking_paused"
    )
    private val PRIVATE_LIFECYCLE_EVENT_TYPES = setOf(
        GlobalConversationEventType.CONVERSATION_DELETED,
        GlobalConversationEventType.CONVERSATION_UPDATED
    )
    private const val MAX_IDENTIFIER_CHARACTERS = 512
    private const val MAX_CONTENT_CHARACTERS = 12_000
    private const val MAX_CONTENT_REF_CHARACTERS = 1_024
    private const val MAX_TITLE_CHARACTERS = 160
    private const val MAX_TOPIC_HINTS = 16
    private const val MAX_TOPIC_HINT_CHARACTERS = 160
    private const val MAX_METADATA_ENTRIES = 48
    private const val MAX_METADATA_KEY_CHARACTERS = 64
    private const val MAX_METADATA_VALUE_CHARACTERS = 1_024
    private const val MAX_CAUSAL_IDENTIFIERS = 128
}
