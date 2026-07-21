package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class AgentPermissionSubjectType {
    MODEL,
    AGENT,
    TOOL,
    ANDROID,
    DEVICE,
    FILE,
    APP,
    CONSEQUENTIAL_ACTION
}

enum class AgentPermissionGrantLifetime {
    SINGLE_USE,
    TEMPORARY,
    PERMANENT
}

enum class AgentPermissionGrantStatus {
    ACTIVE,
    CONSUMED,
    REVOKED,
    EXPIRED
}

enum class AgentPermissionGrantIssuer {
    USER,
    HOST_POLICY,
    ADMIN,
    IMPORT
}

data class AgentPermissionGrant(
    val grantId: String = UUID.randomUUID().toString(),
    val subjectType: AgentPermissionSubjectType,
    val subjectId: String,
    val scope: String,
    val action: String = "",
    val resource: String = "",
    val target: String = "",
    val constraintsJson: String = "{}",
    val issuer: AgentPermissionGrantIssuer,
    val evidence: String,
    val lifetime: AgentPermissionGrantLifetime,
    val status: AgentPermissionGrantStatus = AgentPermissionGrantStatus.ACTIVE,
    val maxUses: Int = if (lifetime == AgentPermissionGrantLifetime.SINGLE_USE) 1 else 0,
    val uses: Int = 0,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val expiresAtMillis: Long = 0L,
    val consumedAtMillis: Long = 0L,
    val revokedAtMillis: Long = 0L,
    val revocationReason: String = ""
) {
    fun isUsable(nowMillis: Long): Boolean = status == AgentPermissionGrantStatus.ACTIVE &&
        (expiresAtMillis <= 0L || nowMillis < expiresAtMillis) &&
        (maxUses <= 0 || uses < maxUses)
}

data class AgentPermissionRequest(
    val subjectType: AgentPermissionSubjectType,
    val subjectId: String,
    val scope: String,
    val action: String = "",
    val resource: String = "",
    val target: String = ""
)

data class AgentPermissionDecision(
    val granted: Boolean,
    val grant: AgentPermissionGrant? = null,
    val reason: String
)

data class AgentPermissionRevocation(
    val revokedGrantIds: Set<String>,
    val scopes: Set<String>,
    val reason: String,
    val revokedAtMillis: Long
)

interface AgentPermissionGrantStore {
    fun grant(grant: AgentPermissionGrant): AgentPermissionGrant
    fun authorize(request: AgentPermissionRequest, consume: Boolean = false): AgentPermissionDecision
    fun list(includeInactive: Boolean = true): List<AgentPermissionGrant>
    fun revokeGrant(grantId: String, reason: String): AgentPermissionRevocation
    fun revokeScope(scope: String, reason: String): AgentPermissionRevocation
    fun clear()
}

abstract class AbstractAgentPermissionGrantStore(
    private val clock: () -> Long
) : AgentPermissionGrantStore {
    protected abstract fun readPersisted(): List<AgentPermissionGrant>
    protected abstract fun writePersisted(grants: List<AgentPermissionGrant>)
    protected abstract fun clearPersisted()

    @Synchronized
    final override fun grant(grant: AgentPermissionGrant): AgentPermissionGrant {
        val now = now()
        val normalized = normalize(grant, now)
        val grants = refreshExpired(readPersisted(), now).toMutableList()
        val equivalent = grants.firstOrNull { existing ->
            existing.status == AgentPermissionGrantStatus.ACTIVE &&
                existing.subjectType == normalized.subjectType &&
                existing.subjectId == normalized.subjectId &&
                existing.scope == normalized.scope &&
                existing.action == normalized.action &&
                existing.resource == normalized.resource &&
                existing.target == normalized.target &&
                existing.constraintsJson == normalized.constraintsJson &&
                existing.lifetime == normalized.lifetime &&
                existing.expiresAtMillis == normalized.expiresAtMillis
        }
        if (equivalent != null) {
            writePersisted(bound(grants))
            return equivalent
        }
        val sameId = grants.indexOfFirst { it.grantId == normalized.grantId }
        require(sameId < 0) { "Permission grant id was already used" }
        grants += normalized
        writePersisted(bound(grants))
        return normalized
    }

    @Synchronized
    final override fun authorize(
        request: AgentPermissionRequest,
        consume: Boolean
    ): AgentPermissionDecision {
        val normalizedRequest = normalize(request)
        val now = now()
        val grants = refreshExpired(readPersisted(), now).toMutableList()
        val match = grants
            .filter { it.isUsable(now) && it.matches(normalizedRequest) }
            .maxWithOrNull(
                compareBy<AgentPermissionGrant> { it.matchSpecificity(normalizedRequest) }
                    .thenBy { it.createdAtMillis }
            )
        if (match == null) {
            writePersisted(bound(grants))
            return AgentPermissionDecision(false, reason = "no_matching_host_grant")
        }
        if (!consume) {
            writePersisted(bound(grants))
            return AgentPermissionDecision(true, match, "host_grant_active")
        }
        val updatedUses = match.uses + 1
        val consumed = match.copy(
            uses = updatedUses,
            status = if (match.maxUses > 0 && updatedUses >= match.maxUses) {
                AgentPermissionGrantStatus.CONSUMED
            } else {
                AgentPermissionGrantStatus.ACTIVE
            },
            consumedAtMillis = now
        )
        grants[grants.indexOfFirst { it.grantId == match.grantId }] = consumed
        writePersisted(bound(grants))
        return AgentPermissionDecision(true, consumed, "host_grant_consumed")
    }

    @Synchronized
    final override fun list(includeInactive: Boolean): List<AgentPermissionGrant> {
        val now = now()
        val refreshed = refreshExpired(readPersisted(), now)
        writePersisted(bound(refreshed))
        return refreshed
            .filter { includeInactive || it.status == AgentPermissionGrantStatus.ACTIVE }
            .sortedWith(compareByDescending<AgentPermissionGrant> { it.createdAtMillis }.thenBy { it.grantId })
    }

    @Synchronized
    final override fun revokeGrant(grantId: String, reason: String): AgentPermissionRevocation {
        val cleanId = grantId.trim()
        if (cleanId.isBlank()) return emptyRevocation(reason)
        return revoke(reason) { it.grantId == cleanId }
    }

    @Synchronized
    final override fun revokeScope(scope: String, reason: String): AgentPermissionRevocation {
        val cleanScope = scope.trim()
        if (cleanScope.isBlank()) return emptyRevocation(reason)
        return revoke(reason) { it.scope == cleanScope }
    }

    @Synchronized
    final override fun clear() = clearPersisted()

    private fun revoke(
        reason: String,
        predicate: (AgentPermissionGrant) -> Boolean
    ): AgentPermissionRevocation {
        val now = now()
        val cleanReason = reason.trim().take(MAX_REASON_CHARS).ifBlank { "revoked_by_host" }
        val grants = refreshExpired(readPersisted(), now)
        val revoked = grants.filter { it.status == AgentPermissionGrantStatus.ACTIVE && predicate(it) }
        if (revoked.isEmpty()) {
            writePersisted(bound(grants))
            return emptyRevocation(cleanReason, now)
        }
        val revokedIds = revoked.mapTo(linkedSetOf()) { it.grantId }
        val updated = grants.map { grant ->
            if (grant.grantId in revokedIds) grant.copy(
                status = AgentPermissionGrantStatus.REVOKED,
                revokedAtMillis = now,
                revocationReason = cleanReason
            ) else grant
        }
        writePersisted(bound(updated))
        return AgentPermissionRevocation(
            revokedGrantIds = revokedIds,
            scopes = revoked.mapTo(linkedSetOf()) { it.scope },
            reason = cleanReason,
            revokedAtMillis = now
        )
    }

    private fun normalize(grant: AgentPermissionGrant, now: Long): AgentPermissionGrant {
        val grantId = required(grant.grantId, MAX_ID_CHARS, "grant id")
        val subjectId = required(grant.subjectId, MAX_ID_CHARS, "subject id")
        val scope = required(grant.scope, MAX_SCOPE_CHARS, "scope")
        val evidence = required(grant.evidence, MAX_EVIDENCE_CHARS, "evidence")
        val createdAt = grant.createdAtMillis.takeIf { it > 0L } ?: now
        require(grant.status == AgentPermissionGrantStatus.ACTIVE) {
            "Only active permission grants can be issued"
        }
        require(grant.uses == 0 && grant.consumedAtMillis == 0L && grant.revokedAtMillis == 0L) {
            "A new permission grant cannot contain prior usage or revocation state"
        }
        when (grant.lifetime) {
            AgentPermissionGrantLifetime.SINGLE_USE -> require(grant.maxUses == 1) {
                "Single-use permission grants must allow exactly one use"
            }
            AgentPermissionGrantLifetime.TEMPORARY -> require(grant.expiresAtMillis > createdAt) {
                "Temporary permission grants require a future expiry"
            }
            AgentPermissionGrantLifetime.PERMANENT -> require(grant.expiresAtMillis == 0L) {
                "Permanent permission grants cannot expire"
            }
        }
        return grant.copy(
            grantId = grantId,
            subjectId = subjectId,
            scope = scope,
            action = grant.action.trim().take(MAX_SCOPE_CHARS),
            resource = grant.resource.trim().take(MAX_RESOURCE_CHARS),
            target = grant.target.trim().take(MAX_RESOURCE_CHARS),
            constraintsJson = normalizeJson(grant.constraintsJson),
            evidence = evidence,
            createdAtMillis = createdAt,
            revocationReason = ""
        )
    }

    private fun normalize(request: AgentPermissionRequest): AgentPermissionRequest = request.copy(
        subjectId = required(request.subjectId, MAX_ID_CHARS, "subject id"),
        scope = required(request.scope, MAX_SCOPE_CHARS, "scope"),
        action = request.action.trim().take(MAX_SCOPE_CHARS),
        resource = request.resource.trim().take(MAX_RESOURCE_CHARS),
        target = request.target.trim().take(MAX_RESOURCE_CHARS)
    )

    private fun refreshExpired(
        grants: List<AgentPermissionGrant>,
        now: Long
    ): List<AgentPermissionGrant> = grants.map { grant ->
        if (grant.status == AgentPermissionGrantStatus.ACTIVE &&
            grant.expiresAtMillis > 0L && now >= grant.expiresAtMillis
        ) grant.copy(status = AgentPermissionGrantStatus.EXPIRED) else grant
    }

    private fun AgentPermissionGrant.matches(request: AgentPermissionRequest): Boolean =
        subjectType == request.subjectType &&
            (subjectId == request.subjectId || subjectId == WILDCARD) &&
            (scope == request.scope || scope == WILDCARD) &&
            (action.isBlank() || action == request.action) &&
            (resource.isBlank() || resource == request.resource) &&
            (target.isBlank() || target == request.target)

    private fun AgentPermissionGrant.matchSpecificity(request: AgentPermissionRequest): Int =
        (if (subjectId == request.subjectId) 16 else 0) +
            (if (scope == request.scope) 8 else 0) +
            (if (action.isNotBlank()) 4 else 0) +
            (if (resource.isNotBlank()) 2 else 0) +
            (if (target.isNotBlank()) 1 else 0)

    private fun bound(grants: List<AgentPermissionGrant>): List<AgentPermissionGrant> = grants
        .sortedWith(compareBy<AgentPermissionGrant> { it.createdAtMillis }.thenBy { it.grantId })
        .takeLast(MAX_GRANTS)

    private fun required(value: String, limit: Int, label: String): String {
        val clean = value.trim().take(limit)
        require(clean.isNotBlank()) { "Permission $label must not be blank" }
        return clean
    }

    private fun normalizeJson(value: String): String = runCatching {
        JSONObject(value.ifBlank { "{}" }).toString()
    }.getOrElse { throw IllegalArgumentException("Permission grant constraints must be a JSON object") }

    private fun emptyRevocation(reason: String, now: Long = now()): AgentPermissionRevocation =
        AgentPermissionRevocation(emptySet(), emptySet(), reason.trim(), now)

    private fun now(): Long = clock().coerceAtLeast(0L)

    private companion object {
        const val MAX_GRANTS = 2_000
        const val MAX_ID_CHARS = 256
        const val MAX_SCOPE_CHARS = 256
        const val MAX_RESOURCE_CHARS = 2_048
        const val MAX_EVIDENCE_CHARS = 2_048
        const val MAX_REASON_CHARS = 1_024
        const val WILDCARD = "*"
    }
}

class InMemoryAgentPermissionGrantStore(
    serialized: String = "[]",
    clock: () -> Long = { System.currentTimeMillis() }
) : AbstractAgentPermissionGrantStore(clock) {
    private var document = serialized

    override fun readPersisted(): List<AgentPermissionGrant> = AgentPermissionGrantJsonCodec.decode(document)

    override fun writePersisted(grants: List<AgentPermissionGrant>) {
        document = AgentPermissionGrantJsonCodec.encode(grants)
    }

    override fun clearPersisted() {
        document = "[]"
    }

    fun serializedSnapshot(): String = document
}

class EncryptedAgentPermissionGrantStore(
    context: Context,
    clock: () -> Long = { System.currentTimeMillis() }
) : AbstractAgentPermissionGrantStore(clock) {
    private val database = AgentEncryptedDatabase(
        context.applicationContext,
        DATABASE,
        legacyPreferencesName = UNUSED_LEGACY_PREFERENCES
    )

    override fun readPersisted(): List<AgentPermissionGrant> =
        AgentPermissionGrantJsonCodec.decode(database.readString(KEY_GRANTS, "[]"))

    override fun writePersisted(grants: List<AgentPermissionGrant>) {
        database.writeString(KEY_GRANTS, AgentPermissionGrantJsonCodec.encode(grants))
    }

    override fun clearPersisted() = database.clear()

    private companion object {
        const val DATABASE = "signalasi_permission_grants_v1"
        const val UNUSED_LEGACY_PREFERENCES = "signalasi_permission_grants_v1_no_legacy"
        const val KEY_GRANTS = "grants"
    }
}

object AgentPermissionGrantJsonCodec {
    fun encode(grants: List<AgentPermissionGrant>): String = JSONArray().apply {
        grants.forEach { grant ->
            put(JSONObject()
                .put("grant_id", grant.grantId)
                .put("subject_type", grant.subjectType.name)
                .put("subject_id", grant.subjectId)
                .put("scope", grant.scope)
                .put("action", grant.action)
                .put("resource", grant.resource)
                .put("target", grant.target)
                .put("constraints_json", grant.constraintsJson)
                .put("issuer", grant.issuer.name)
                .put("evidence", grant.evidence)
                .put("lifetime", grant.lifetime.name)
                .put("status", grant.status.name)
                .put("max_uses", grant.maxUses)
                .put("uses", grant.uses)
                .put("created_at_millis", grant.createdAtMillis)
                .put("expires_at_millis", grant.expiresAtMillis)
                .put("consumed_at_millis", grant.consumedAtMillis)
                .put("revoked_at_millis", grant.revokedAtMillis)
                .put("revocation_reason", grant.revocationReason))
        }
    }.toString()

    fun decode(raw: String): List<AgentPermissionGrant> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val subjectType = enumOrNull<AgentPermissionSubjectType>(item.optString("subject_type")) ?: continue
                val issuer = enumOrNull<AgentPermissionGrantIssuer>(item.optString("issuer")) ?: continue
                val lifetime = enumOrNull<AgentPermissionGrantLifetime>(item.optString("lifetime")) ?: continue
                val status = enumOrNull<AgentPermissionGrantStatus>(item.optString("status")) ?: continue
                add(AgentPermissionGrant(
                    grantId = item.optString("grant_id"),
                    subjectType = subjectType,
                    subjectId = item.optString("subject_id"),
                    scope = item.optString("scope"),
                    action = item.optString("action"),
                    resource = item.optString("resource"),
                    target = item.optString("target"),
                    constraintsJson = item.optString("constraints_json", "{}"),
                    issuer = issuer,
                    evidence = item.optString("evidence"),
                    lifetime = lifetime,
                    status = status,
                    maxUses = item.optInt("max_uses"),
                    uses = item.optInt("uses"),
                    createdAtMillis = item.optLong("created_at_millis"),
                    expiresAtMillis = item.optLong("expires_at_millis"),
                    consumedAtMillis = item.optLong("consumed_at_millis"),
                    revokedAtMillis = item.optLong("revoked_at_millis"),
                    revocationReason = item.optString("revocation_reason")
                ))
            }
        }
    }.getOrDefault(emptyList())

    private inline fun <reified T : Enum<T>> enumOrNull(value: String): T? =
        runCatching { enumValueOf<T>(value) }.getOrNull()
}
