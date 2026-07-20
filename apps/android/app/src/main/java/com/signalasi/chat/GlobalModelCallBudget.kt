package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class GlobalModelCallKind {
    COGNITION,
    RESEARCH_EVIDENCE,
    RESEARCH_SYNTHESIS,
    AUTONOMOUS_ACTION,
    PLAN_REVIEW
}

enum class GlobalModelCallBudgetDenial {
    DAILY_LIMIT,
    CONCURRENCY_LIMIT,
    DUPLICATE_DISPATCH
}

data class GlobalModelCallDispatch(
    val leaseId: String,
    val kind: GlobalModelCallKind,
    val startedAtMillis: Long
)

data class GlobalModelCallLease(
    val id: String,
    val kind: GlobalModelCallKind,
    val ownerKey: String,
    val startedAtMillis: Long,
    val expiresAtMillis: Long
)

data class GlobalModelCallBudgetState(
    val dispatches: List<GlobalModelCallDispatch> = emptyList(),
    val activeLeases: List<GlobalModelCallLease> = emptyList()
)

data class GlobalModelCallBudgetDecision(
    val granted: Boolean,
    val state: GlobalModelCallBudgetState,
    val leaseId: String = "",
    val denial: GlobalModelCallBudgetDenial? = null,
    val nextEligibleAtMillis: Long = 0L
)

data class GlobalModelCallBudgetSnapshot(
    val dispatchesInWindow: Int,
    val activeCalls: Int,
    val dailyLimit: Int,
    val concurrencyLimit: Int,
    val dispatchesByKind: Map<GlobalModelCallKind, Int>
)

object GlobalModelCallBudgetPolicy {
    const val WINDOW_MILLIS = 24L * 60L * 60L * 1_000L
    const val MIN_DAILY_LIMIT = 1
    const val MAX_DAILY_LIMIT = 200
    const val MIN_CONCURRENCY_LIMIT = 1
    const val MAX_CONCURRENCY_LIMIT = 6
    private const val MIN_LEASE_MILLIS = 30_000L
    private const val MAX_LEASE_MILLIS = 15L * 60L * 1_000L
    private const val RETRY_FLOOR_MILLIS = 1_000L
    private const val MAX_DISPATCH_HISTORY = 1_000
    private const val MAX_ACTIVE_LEASES = 64

    fun acquire(
        state: GlobalModelCallBudgetState,
        leaseId: String,
        kind: GlobalModelCallKind,
        ownerKey: String,
        leaseMillis: Long,
        dailyLimit: Int,
        concurrencyLimit: Int,
        nowMillis: Long
    ): GlobalModelCallBudgetDecision {
        val normalized = normalize(state, nowMillis)
        normalized.activeLeases.firstOrNull { it.id == leaseId }?.let { existing ->
            return GlobalModelCallBudgetDecision(
                granted = true,
                state = normalized,
                leaseId = existing.id
            )
        }
        normalized.dispatches.firstOrNull { it.leaseId == leaseId }?.let { existing ->
            return denied(
                normalized,
                GlobalModelCallBudgetDenial.DUPLICATE_DISPATCH,
                maxOf(nowMillis + RETRY_FLOOR_MILLIS, existing.startedAtMillis + WINDOW_MILLIS)
            )
        }
        val appliedDailyLimit = dailyLimit.coerceIn(MIN_DAILY_LIMIT, MAX_DAILY_LIMIT)
        if (normalized.dispatches.size >= appliedDailyLimit) {
            val oldest = normalized.dispatches.minOfOrNull(GlobalModelCallDispatch::startedAtMillis) ?: nowMillis
            return denied(
                normalized,
                GlobalModelCallBudgetDenial.DAILY_LIMIT,
                maxOf(nowMillis + RETRY_FLOOR_MILLIS, oldest + WINDOW_MILLIS)
            )
        }
        val appliedConcurrencyLimit = concurrencyLimit.coerceIn(
            MIN_CONCURRENCY_LIMIT,
            MAX_CONCURRENCY_LIMIT
        )
        if (normalized.activeLeases.size >= appliedConcurrencyLimit) {
            val earliestExpiry = normalized.activeLeases.minOfOrNull(GlobalModelCallLease::expiresAtMillis)
                ?: nowMillis + RETRY_FLOOR_MILLIS
            return denied(
                normalized,
                GlobalModelCallBudgetDenial.CONCURRENCY_LIMIT,
                maxOf(nowMillis + RETRY_FLOOR_MILLIS, earliestExpiry)
            )
        }
        val expiry = nowMillis + leaseMillis.coerceIn(MIN_LEASE_MILLIS, MAX_LEASE_MILLIS)
        val lease = GlobalModelCallLease(
            id = leaseId,
            kind = kind,
            ownerKey = ownerKey.take(500),
            startedAtMillis = nowMillis,
            expiresAtMillis = expiry
        )
        val updated = normalized.copy(
            dispatches = (normalized.dispatches + GlobalModelCallDispatch(leaseId, kind, nowMillis))
                .takeLast(MAX_DISPATCH_HISTORY),
            activeLeases = (normalized.activeLeases + lease).takeLast(MAX_ACTIVE_LEASES)
        )
        return GlobalModelCallBudgetDecision(true, updated, leaseId)
    }

    fun availability(
        state: GlobalModelCallBudgetState,
        dailyLimit: Int,
        concurrencyLimit: Int,
        nowMillis: Long
    ): GlobalModelCallBudgetDecision {
        val normalized = normalize(state, nowMillis)
        val appliedDailyLimit = dailyLimit.coerceIn(MIN_DAILY_LIMIT, MAX_DAILY_LIMIT)
        if (normalized.dispatches.size >= appliedDailyLimit) {
            val oldest = normalized.dispatches.minOfOrNull(GlobalModelCallDispatch::startedAtMillis) ?: nowMillis
            return denied(
                normalized,
                GlobalModelCallBudgetDenial.DAILY_LIMIT,
                maxOf(nowMillis + RETRY_FLOOR_MILLIS, oldest + WINDOW_MILLIS)
            )
        }
        val appliedConcurrencyLimit = concurrencyLimit.coerceIn(
            MIN_CONCURRENCY_LIMIT,
            MAX_CONCURRENCY_LIMIT
        )
        if (normalized.activeLeases.size >= appliedConcurrencyLimit) {
            val earliestExpiry = normalized.activeLeases.minOfOrNull(GlobalModelCallLease::expiresAtMillis)
                ?: nowMillis + RETRY_FLOOR_MILLIS
            return denied(
                normalized,
                GlobalModelCallBudgetDenial.CONCURRENCY_LIMIT,
                maxOf(nowMillis + RETRY_FLOOR_MILLIS, earliestExpiry)
            )
        }
        return GlobalModelCallBudgetDecision(granted = true, state = normalized)
    }

    fun release(
        state: GlobalModelCallBudgetState,
        leaseId: String,
        nowMillis: Long
    ): GlobalModelCallBudgetState {
        val normalized = normalize(state, nowMillis)
        return normalized.copy(activeLeases = normalized.activeLeases.filterNot { it.id == leaseId })
    }

    fun cancel(
        state: GlobalModelCallBudgetState,
        leaseId: String,
        nowMillis: Long
    ): GlobalModelCallBudgetState {
        val normalized = normalize(state, nowMillis)
        return normalized.copy(
            dispatches = normalized.dispatches.filterNot { it.leaseId == leaseId },
            activeLeases = normalized.activeLeases.filterNot { it.id == leaseId }
        )
    }

    fun normalize(
        state: GlobalModelCallBudgetState,
        nowMillis: Long
    ): GlobalModelCallBudgetState {
        val windowStart = nowMillis - WINDOW_MILLIS
        val dispatches = state.dispatches.asSequence()
            .filter { it.leaseId.isNotBlank() && it.startedAtMillis > 0L && it.startedAtMillis > windowStart }
            .distinctBy(GlobalModelCallDispatch::leaseId)
            .map { it.copy(startedAtMillis = it.startedAtMillis.coerceAtMost(nowMillis)) }
            .sortedBy(GlobalModelCallDispatch::startedAtMillis)
            .toList()
            .takeLast(MAX_DISPATCH_HISTORY)
        val active = state.activeLeases.asSequence()
            .filter { it.id.isNotBlank() && it.startedAtMillis > 0L && it.expiresAtMillis > nowMillis }
            .distinctBy(GlobalModelCallLease::id)
            .sortedBy(GlobalModelCallLease::startedAtMillis)
            .toList()
            .takeLast(MAX_ACTIVE_LEASES)
        return GlobalModelCallBudgetState(dispatches, active)
    }

    private fun denied(
        state: GlobalModelCallBudgetState,
        denial: GlobalModelCallBudgetDenial,
        nextEligibleAtMillis: Long
    ): GlobalModelCallBudgetDecision = GlobalModelCallBudgetDecision(
        granted = false,
        state = state,
        denial = denial,
        nextEligibleAtMillis = nextEligibleAtMillis
    )
}

class GlobalModelCallBudgetStore(context: Context) {
    private val database = AgentEncryptedDatabase(
        context.applicationContext,
        GlobalAgentRepository.DATABASE_NAME
    )

    fun acquire(
        kind: GlobalModelCallKind,
        ownerKey: String,
        leaseMillis: Long,
        settings: GlobalAgentSettings,
        nowMillis: Long = System.currentTimeMillis()
    ): GlobalModelCallBudgetDecision = synchronized(STORE_LOCK) {
        val state = load()
        val decision = GlobalModelCallBudgetPolicy.acquire(
            state = state,
            leaseId = leaseId(kind, ownerKey),
            kind = kind,
            ownerKey = ownerKey,
            leaseMillis = leaseMillis,
            dailyLimit = settings.dailyBackgroundModelCallBudget,
            concurrencyLimit = settings.maxConcurrentBackgroundModelCalls,
            nowMillis = nowMillis
        )
        save(decision.state)
        decision
    }

    fun availability(
        settings: GlobalAgentSettings,
        nowMillis: Long = System.currentTimeMillis()
    ): GlobalModelCallBudgetDecision = synchronized(STORE_LOCK) {
        val decision = GlobalModelCallBudgetPolicy.availability(
            state = load(),
            dailyLimit = settings.dailyBackgroundModelCallBudget,
            concurrencyLimit = settings.maxConcurrentBackgroundModelCalls,
            nowMillis = nowMillis
        )
        save(decision.state)
        decision
    }

    fun release(
        kind: GlobalModelCallKind,
        ownerKey: String,
        nowMillis: Long = System.currentTimeMillis()
    ) = synchronized(STORE_LOCK) {
        save(GlobalModelCallBudgetPolicy.release(load(), leaseId(kind, ownerKey), nowMillis))
    }

    fun cancel(
        kind: GlobalModelCallKind,
        ownerKey: String,
        nowMillis: Long = System.currentTimeMillis()
    ) = synchronized(STORE_LOCK) {
        save(GlobalModelCallBudgetPolicy.cancel(load(), leaseId(kind, ownerKey), nowMillis))
    }

    fun snapshot(
        settings: GlobalAgentSettings,
        nowMillis: Long = System.currentTimeMillis()
    ): GlobalModelCallBudgetSnapshot = synchronized(STORE_LOCK) {
        val state = GlobalModelCallBudgetPolicy.normalize(load(), nowMillis)
        save(state)
        GlobalModelCallBudgetSnapshot(
            dispatchesInWindow = state.dispatches.size,
            activeCalls = state.activeLeases.size,
            dailyLimit = settings.dailyBackgroundModelCallBudget.coerceIn(
                GlobalModelCallBudgetPolicy.MIN_DAILY_LIMIT,
                GlobalModelCallBudgetPolicy.MAX_DAILY_LIMIT
            ),
            concurrencyLimit = settings.maxConcurrentBackgroundModelCalls.coerceIn(
                GlobalModelCallBudgetPolicy.MIN_CONCURRENCY_LIMIT,
                GlobalModelCallBudgetPolicy.MAX_CONCURRENCY_LIMIT
            ),
            dispatchesByKind = GlobalModelCallKind.entries.associateWith { kind ->
                state.dispatches.count { it.kind == kind }
            }
        )
    }

    private fun load(): GlobalModelCallBudgetState = runCatching {
        val root = JSONObject(database.readString(KEY_STATE, ""))
        val dispatches = root.optJSONArray("dispatches") ?: JSONArray()
        val leases = root.optJSONArray("active_leases") ?: JSONArray()
        GlobalModelCallBudgetState(
            dispatches = buildList {
                for (index in 0 until dispatches.length()) {
                    val item = dispatches.optJSONObject(index) ?: continue
                    val kind = runCatching {
                        GlobalModelCallKind.valueOf(item.optString("kind"))
                    }.getOrNull() ?: continue
                    val id = item.optString("lease_id")
                    val startedAt = item.optLong("started_at_millis")
                    if (id.isNotBlank() && startedAt > 0L) add(GlobalModelCallDispatch(id, kind, startedAt))
                }
            },
            activeLeases = buildList {
                for (index in 0 until leases.length()) {
                    val item = leases.optJSONObject(index) ?: continue
                    val kind = runCatching {
                        GlobalModelCallKind.valueOf(item.optString("kind"))
                    }.getOrNull() ?: continue
                    val id = item.optString("id")
                    val startedAt = item.optLong("started_at_millis")
                    val expiresAt = item.optLong("expires_at_millis")
                    if (id.isNotBlank() && startedAt > 0L && expiresAt > 0L) add(
                        GlobalModelCallLease(
                            id = id,
                            kind = kind,
                            ownerKey = item.optString("owner_key").take(500),
                            startedAtMillis = startedAt,
                            expiresAtMillis = expiresAt
                        )
                    )
                }
            }
        )
    }.getOrDefault(GlobalModelCallBudgetState())

    private fun save(state: GlobalModelCallBudgetState) {
        val root = JSONObject()
            .put("version", 1)
            .put("dispatches", JSONArray().apply {
                state.dispatches.forEach { dispatch ->
                    put(JSONObject()
                        .put("lease_id", dispatch.leaseId)
                        .put("kind", dispatch.kind.name)
                        .put("started_at_millis", dispatch.startedAtMillis))
                }
            })
            .put("active_leases", JSONArray().apply {
                state.activeLeases.forEach { lease ->
                    put(JSONObject()
                        .put("id", lease.id)
                        .put("kind", lease.kind.name)
                        .put("owner_key", lease.ownerKey)
                        .put("started_at_millis", lease.startedAtMillis)
                        .put("expires_at_millis", lease.expiresAtMillis))
                }
            })
        database.writeString(KEY_STATE, root.toString())
    }

    companion object {
        private const val KEY_STATE = "model_call_budget_state"
        private val STORE_LOCK = Any()

        fun leaseId(kind: GlobalModelCallKind, ownerKey: String): String =
            "model-call:${GlobalAgentText.stableKey(kind.name, ownerKey)}"
    }
}
