package com.signalasi.chat

import java.util.Locale

enum class GlobalSemanticEventClass(val wireValue: String) {
    MESSAGE("message"),
    FILE("file"),
    DECISION("decision"),
    TASK("task"),
    TOOL("tool"),
    FEEDBACK("feedback"),
    CONVERSATION("conversation"),
    MEMORY("memory"),
    KNOWLEDGE("knowledge"),
    AUTHORIZATION("authorization"),
    RESOURCE("resource")
}

data class GlobalEventPublisherDescriptor(
    val publisherId: String,
    val semanticClass: GlobalSemanticEventClass,
    val eventTypes: Set<GlobalConversationEventType>
)

data class GlobalEventPublisherAudit(
    val missingEventTypes: Set<GlobalConversationEventType>,
    val multiplyOwnedEventTypes: Set<GlobalConversationEventType>,
    val missingRequiredSemanticClasses: Set<GlobalSemanticEventClass>,
    val duplicatePublisherIds: Set<String>
) {
    val complete: Boolean
        get() = missingEventTypes.isEmpty() &&
            multiplyOwnedEventTypes.isEmpty() &&
            missingRequiredSemanticClasses.isEmpty() &&
            duplicatePublisherIds.isEmpty()
}

/**
 * Canonical inventory for every event admitted to the Personal ASI event stream.
 *
 * Producers may attach an origin for diagnostics, but publisher identity and semantic class are
 * host-owned so a producer cannot spoof either field.
 */
object GlobalEventPublisherContract {
    const val SCHEMA_VERSION = "signalasi.global-event.v1"
    const val METADATA_SCHEMA_VERSION = "event_schema"
    const val METADATA_PUBLISHER_ID = "publisher_id"
    const val METADATA_SEMANTIC_CLASS = "semantic_class"

    val descriptors: List<GlobalEventPublisherDescriptor> = listOf(
        descriptor(
            "conversation.message",
            GlobalSemanticEventClass.MESSAGE,
            GlobalConversationEventType.MESSAGE_CREATED,
            GlobalConversationEventType.MESSAGE_UPDATED,
            GlobalConversationEventType.MESSAGE_DELETED
        ),
        descriptor(
            "conversation.lifecycle",
            GlobalSemanticEventClass.CONVERSATION,
            GlobalConversationEventType.CONVERSATION_CREATED,
            GlobalConversationEventType.CONVERSATION_UPDATED,
            GlobalConversationEventType.CONVERSATION_MERGED,
            GlobalConversationEventType.CONVERSATION_DELETED
        ),
        descriptor(
            "conversation.files",
            GlobalSemanticEventClass.FILE,
            GlobalConversationEventType.ATTACHMENT_ADDED,
            GlobalConversationEventType.ARTIFACT_CREATED
        ),
        descriptor(
            "run.tasks",
            GlobalSemanticEventClass.TASK,
            GlobalConversationEventType.TASK_UPDATED
        ),
        descriptor(
            "run.tools",
            GlobalSemanticEventClass.TOOL,
            GlobalConversationEventType.TOOL_STARTED,
            GlobalConversationEventType.TOOL_COMPLETED,
            GlobalConversationEventType.TOOL_CANCELLED,
            GlobalConversationEventType.TOOL_FAILED,
            GlobalConversationEventType.TOOL_RESULT
        ),
        descriptor(
            "cognition.decisions",
            GlobalSemanticEventClass.DECISION,
            GlobalConversationEventType.COGNITION_RESULT
        ),
        descriptor(
            "cognition.feedback",
            GlobalSemanticEventClass.FEEDBACK,
            GlobalConversationEventType.USER_FEEDBACK
        ),
        descriptor(
            "memory.lifecycle",
            GlobalSemanticEventClass.MEMORY,
            GlobalConversationEventType.MEMORY_CREATED,
            GlobalConversationEventType.MEMORY_UPDATED,
            GlobalConversationEventType.MEMORY_CONFLICTED,
            GlobalConversationEventType.MEMORY_DELETED
        ),
        descriptor(
            "knowledge.lifecycle",
            GlobalSemanticEventClass.KNOWLEDGE,
            GlobalConversationEventType.KNOWLEDGE_IMPORTED,
            GlobalConversationEventType.KNOWLEDGE_UPDATED,
            GlobalConversationEventType.KNOWLEDGE_ACCESS_CHANGED,
            GlobalConversationEventType.KNOWLEDGE_DELETED
        ),
        descriptor(
            "authorization.lifecycle",
            GlobalSemanticEventClass.AUTHORIZATION,
            GlobalConversationEventType.AUTHORIZATION_GRANTED,
            GlobalConversationEventType.AUTHORIZATION_REVOKED,
            GlobalConversationEventType.AUTHORIZATION_POLICY_CHANGED
        ),
        descriptor(
            "resource.lifecycle",
            GlobalSemanticEventClass.RESOURCE,
            GlobalConversationEventType.RESOURCE_REGISTERED,
            GlobalConversationEventType.RESOURCE_UPDATED,
            GlobalConversationEventType.RESOURCE_REMOVED,
            GlobalConversationEventType.RESOURCE_STATE_CHANGED,
            GlobalConversationEventType.CAPABILITY_SNAPSHOT_RESET
        )
    )

    private val descriptorsByType: Map<GlobalConversationEventType, List<GlobalEventPublisherDescriptor>> =
        GlobalConversationEventType.values().associateWith { type ->
            descriptors.filter { type in it.eventTypes }
        }

    fun descriptorFor(type: GlobalConversationEventType): GlobalEventPublisherDescriptor? =
        descriptorsByType[type]?.singleOrNull()

    fun canonicalMetadata(event: GlobalConversationEvent): Map<String, String> {
        val descriptor = descriptorFor(event.type) ?: return event.metadata
        return buildMap {
            put(METADATA_SCHEMA_VERSION, SCHEMA_VERSION)
            put(METADATA_PUBLISHER_ID, descriptor.publisherId)
            put(METADATA_SEMANTIC_CLASS, descriptor.semanticClass.wireValue)
            event.metadata.forEach { (key, value) ->
                if (key !in CANONICAL_METADATA_KEYS) put(key, value)
            }
        }
    }

    fun isAuthorizedForGlobalStream(event: GlobalConversationEvent): Boolean {
        if (event.type in CONTROL_EVENT_TYPES) return true
        val normalized = event.metadata.mapKeys { (key, _) ->
            key.trim().lowercase(Locale.ROOT).replace('-', '_')
        }
        val authorizationState = listOf(
            normalized["authorization_state"],
            normalized["permission_state"],
            normalized["consent_state"]
        ).filterNotNull().firstOrNull { it.isNotBlank() }?.lowercase(Locale.ROOT).orEmpty()
        if (authorizationState in DENIED_AUTHORIZATION_STATES) return false
        return true
    }

    fun requiresLifecycleSanitization(event: GlobalConversationEvent): Boolean {
        val normalized = event.metadata.mapKeys { (key, _) ->
            key.trim().lowercase(Locale.ROOT).replace('-', '_')
        }
        return normalized["global_visibility"]?.equals("excluded", ignoreCase = true) == true ||
            normalized["private_mode"]?.equals("true", ignoreCase = true) == true ||
            normalized["tracking_paused"]?.equals("true", ignoreCase = true) == true
    }

    fun audit(): GlobalEventPublisherAudit {
        val owners = GlobalConversationEventType.values().associateWith { type ->
            descriptors.count { type in it.eventTypes }
        }
        val publisherIds = descriptors.groupingBy(GlobalEventPublisherDescriptor::publisherId).eachCount()
        return GlobalEventPublisherAudit(
            missingEventTypes = owners.filterValues { it == 0 }.keys,
            multiplyOwnedEventTypes = owners.filterValues { it > 1 }.keys,
            missingRequiredSemanticClasses = REQUIRED_SEMANTIC_CLASSES -
                descriptors.mapTo(mutableSetOf(), GlobalEventPublisherDescriptor::semanticClass),
            duplicatePublisherIds = publisherIds.filterValues { it > 1 }.keys
        )
    }

    private fun descriptor(
        publisherId: String,
        semanticClass: GlobalSemanticEventClass,
        vararg eventTypes: GlobalConversationEventType
    ) = GlobalEventPublisherDescriptor(publisherId, semanticClass, eventTypes.toSet())

    private val REQUIRED_SEMANTIC_CLASSES = setOf(
        GlobalSemanticEventClass.MESSAGE,
        GlobalSemanticEventClass.FILE,
        GlobalSemanticEventClass.DECISION,
        GlobalSemanticEventClass.TASK,
        GlobalSemanticEventClass.TOOL,
        GlobalSemanticEventClass.FEEDBACK
    )
    private val CONTROL_EVENT_TYPES = setOf(
        GlobalConversationEventType.MESSAGE_DELETED,
        GlobalConversationEventType.CONVERSATION_UPDATED,
        GlobalConversationEventType.CONVERSATION_MERGED,
        GlobalConversationEventType.CONVERSATION_DELETED,
        GlobalConversationEventType.MEMORY_DELETED,
        GlobalConversationEventType.KNOWLEDGE_ACCESS_CHANGED,
        GlobalConversationEventType.KNOWLEDGE_DELETED,
        GlobalConversationEventType.AUTHORIZATION_GRANTED,
        GlobalConversationEventType.AUTHORIZATION_REVOKED,
        GlobalConversationEventType.AUTHORIZATION_POLICY_CHANGED,
        GlobalConversationEventType.RESOURCE_REMOVED,
        GlobalConversationEventType.CAPABILITY_SNAPSHOT_RESET
    )
    private val DENIED_AUTHORIZATION_STATES = setOf(
        "blocked", "denied", "disallowed", "expired", "rejected", "revoked", "unauthorized"
    )
    private val CANONICAL_METADATA_KEYS = setOf(
        METADATA_SCHEMA_VERSION,
        METADATA_PUBLISHER_ID,
        METADATA_SEMANTIC_CLASS
    )
}
