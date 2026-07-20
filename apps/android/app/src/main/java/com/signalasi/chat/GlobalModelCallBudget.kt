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
    TOKEN_LIMIT,
    REPORTED_COST_LIMIT,
    CONCURRENCY_LIMIT,
    DUPLICATE_DISPATCH
}

data class GlobalModelCallDispatch(
    val leaseId: String,
    val kind: GlobalModelCallKind,
    val startedAtMillis: Long,
    val resourceId: String = "",
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val reportedCostMicros: Long = 0L,
    val usageEstimated: Boolean = true,
    val completedAtMillis: Long = 0L
) {
    val totalTokens: Long get() = GlobalModelUsageEstimator.saturatingAdd(inputTokens, outputTokens)
}

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
    val dispatchesByKind: Map<GlobalModelCallKind, Int>,
    val inputTokensInWindow: Long,
    val outputTokensInWindow: Long,
    val totalTokensInWindow: Long,
    val dailyTokenLimit: Long,
    val reportedCostMicrosInWindow: Long,
    val dailyReportedCostLimitMicros: Long,
    val estimatedUsageDispatches: Int,
    val unpricedDispatches: Int
)

data class GlobalModelResourceUsageSnapshot(
    val resourceId: String,
    val dispatches: Int = 0,
    val averageInputTokens: Long = 0L,
    val averageOutputTokens: Long = 0L,
    val averageTotalTokens: Long = 0L,
    val averageReportedCostMicros: Long = 0L,
    val pricedDispatches: Int = 0,
    val estimatedUsageDispatches: Int = 0
)

object GlobalModelUsageEstimator {
    fun estimateTokens(vararg texts: String): Long {
        var asciiCharacters = 0L
        var nonAsciiCharacters = 0L
        texts.forEach { text ->
            text.codePoints().forEach { codePoint ->
                if (codePoint <= 0x7f) asciiCharacters += 1L else nonAsciiCharacters += 1L
            }
        }
        val asciiTokens = (asciiCharacters + 3L) / 4L
        return saturatingAdd(asciiTokens, nonAsciiCharacters).coerceAtLeast(1L)
    }

    fun saturatingAdd(left: Long, right: Long): Long {
        val safeLeft = left.coerceAtLeast(0L)
        val safeRight = right.coerceAtLeast(0L)
        return if (Long.MAX_VALUE - safeLeft < safeRight) Long.MAX_VALUE else safeLeft + safeRight
    }
}

object GlobalModelCallBudgetPolicy {
    const val WINDOW_MILLIS = 24L * 60L * 60L * 1_000L
    const val MIN_DAILY_LIMIT = 1
    const val MAX_DAILY_LIMIT = 200
    const val MIN_CONCURRENCY_LIMIT = 1
    const val MAX_CONCURRENCY_LIMIT = 6
    const val MIN_DAILY_TOKEN_LIMIT = 10_000L
    const val MAX_DAILY_TOKEN_LIMIT = 10_000_000L
    const val MIN_DAILY_REPORTED_COST_LIMIT_MICROS = 0L
    const val MAX_DAILY_REPORTED_COST_LIMIT_MICROS = 100_000_000L
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
        nowMillis: Long,
        resourceId: String = "",
        estimatedInputTokens: Long = 0L,
        dailyTokenLimit: Long = MAX_DAILY_TOKEN_LIMIT,
        dailyReportedCostLimitMicros: Long = 0L
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
        val appliedTokenLimit = dailyTokenLimit.coerceIn(MIN_DAILY_TOKEN_LIMIT, MAX_DAILY_TOKEN_LIMIT)
        val reservedInputTokens = estimatedInputTokens.coerceIn(0L, MAX_DAILY_TOKEN_LIMIT)
        val usedTokens = totalTokens(normalized.dispatches)
        if (usedTokens > 0L && GlobalModelUsageEstimator.saturatingAdd(usedTokens, reservedInputTokens) > appliedTokenLimit) {
            return denied(
                normalized,
                GlobalModelCallBudgetDenial.TOKEN_LIMIT,
                nextTokenEligibility(normalized.dispatches, reservedInputTokens, appliedTokenLimit, nowMillis)
            )
        }
        val appliedCostLimit = dailyReportedCostLimitMicros.coerceIn(
            MIN_DAILY_REPORTED_COST_LIMIT_MICROS,
            MAX_DAILY_REPORTED_COST_LIMIT_MICROS
        )
        val reportedCost = reportedCostMicros(normalized.dispatches)
        if (appliedCostLimit > 0L && reportedCost >= appliedCostLimit) {
            return denied(
                normalized,
                GlobalModelCallBudgetDenial.REPORTED_COST_LIMIT,
                nextCostEligibility(normalized.dispatches, appliedCostLimit, nowMillis)
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
        val dispatch = GlobalModelCallDispatch(
            leaseId = leaseId,
            kind = kind,
            startedAtMillis = nowMillis,
            resourceId = resourceId.take(500),
            inputTokens = reservedInputTokens,
            usageEstimated = true
        )
        val updated = normalized.copy(
            dispatches = (normalized.dispatches + dispatch).takeLast(MAX_DISPATCH_HISTORY),
            activeLeases = (normalized.activeLeases + lease).takeLast(MAX_ACTIVE_LEASES)
        )
        return GlobalModelCallBudgetDecision(true, updated, leaseId)
    }

    fun availability(
        state: GlobalModelCallBudgetState,
        dailyLimit: Int,
        concurrencyLimit: Int,
        nowMillis: Long,
        estimatedInputTokens: Long = 0L,
        dailyTokenLimit: Long = MAX_DAILY_TOKEN_LIMIT,
        dailyReportedCostLimitMicros: Long = 0L
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
        val appliedTokenLimit = dailyTokenLimit.coerceIn(MIN_DAILY_TOKEN_LIMIT, MAX_DAILY_TOKEN_LIMIT)
        val reservedInputTokens = estimatedInputTokens.coerceIn(0L, MAX_DAILY_TOKEN_LIMIT)
        val usedTokens = totalTokens(normalized.dispatches)
        if (usedTokens >= appliedTokenLimit ||
            (usedTokens > 0L && GlobalModelUsageEstimator.saturatingAdd(usedTokens, reservedInputTokens) > appliedTokenLimit)
        ) {
            return denied(
                normalized,
                GlobalModelCallBudgetDenial.TOKEN_LIMIT,
                nextTokenEligibility(normalized.dispatches, reservedInputTokens, appliedTokenLimit, nowMillis)
            )
        }
        val appliedCostLimit = dailyReportedCostLimitMicros.coerceIn(
            MIN_DAILY_REPORTED_COST_LIMIT_MICROS,
            MAX_DAILY_REPORTED_COST_LIMIT_MICROS
        )
        if (appliedCostLimit > 0L && reportedCostMicros(normalized.dispatches) >= appliedCostLimit) {
            return denied(
                normalized,
                GlobalModelCallBudgetDenial.REPORTED_COST_LIMIT,
                nextCostEligibility(normalized.dispatches, appliedCostLimit, nowMillis)
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

    fun complete(
        state: GlobalModelCallBudgetState,
        leaseId: String,
        inputTokens: Long,
        outputTokens: Long,
        reportedCostMicros: Long,
        responseText: String,
        nowMillis: Long
    ): GlobalModelCallBudgetState {
        val normalized = normalize(state, nowMillis)
        val dispatch = normalized.dispatches.firstOrNull { it.leaseId == leaseId }
            ?: return normalized.copy(activeLeases = normalized.activeLeases.filterNot { it.id == leaseId })
        val hasActualInput = inputTokens > 0L
        val hasActualOutput = outputTokens > 0L
        val resolvedInput = if (hasActualInput) inputTokens else dispatch.inputTokens
        val resolvedOutput = if (hasActualOutput) outputTokens else {
            responseText.takeIf(String::isNotBlank)?.let { GlobalModelUsageEstimator.estimateTokens(it) } ?: 0L
        }
        val updatedDispatch = dispatch.copy(
            inputTokens = resolvedInput.coerceAtLeast(0L),
            outputTokens = resolvedOutput.coerceAtLeast(0L),
            reportedCostMicros = reportedCostMicros.coerceAtLeast(0L),
            usageEstimated = !hasActualInput || !hasActualOutput,
            completedAtMillis = nowMillis.coerceAtLeast(dispatch.startedAtMillis)
        )
        return normalized.copy(
            dispatches = normalized.dispatches.map { if (it.leaseId == leaseId) updatedDispatch else it },
            activeLeases = normalized.activeLeases.filterNot { it.id == leaseId }
        )
    }

    fun release(
        state: GlobalModelCallBudgetState,
        leaseId: String,
        nowMillis: Long
    ): GlobalModelCallBudgetState {
        val normalized = normalize(state, nowMillis)
        return normalized.copy(
            dispatches = normalized.dispatches.map { dispatch ->
                if (dispatch.leaseId == leaseId && dispatch.completedAtMillis <= 0L) {
                    dispatch.copy(completedAtMillis = nowMillis.coerceAtLeast(dispatch.startedAtMillis))
                } else dispatch
            },
            activeLeases = normalized.activeLeases.filterNot { it.id == leaseId }
        )
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
            .map { dispatch ->
                dispatch.copy(
                    startedAtMillis = dispatch.startedAtMillis.coerceAtMost(nowMillis),
                    resourceId = dispatch.resourceId.take(500),
                    inputTokens = dispatch.inputTokens.coerceAtLeast(0L),
                    outputTokens = dispatch.outputTokens.coerceAtLeast(0L),
                    reportedCostMicros = dispatch.reportedCostMicros.coerceAtLeast(0L),
                    completedAtMillis = dispatch.completedAtMillis.coerceIn(0L, nowMillis)
                )
            }
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

    fun resourceUsage(
        dispatches: List<GlobalModelCallDispatch>,
        resourceId: String
    ): GlobalModelResourceUsageSnapshot {
        val matching = dispatches.filter { it.resourceId == resourceId && resourceId.isNotBlank() }
        if (matching.isEmpty()) return GlobalModelResourceUsageSnapshot(resourceId)
        val priced = matching.filter { it.reportedCostMicros > 0L }
        return GlobalModelResourceUsageSnapshot(
            resourceId = resourceId,
            dispatches = matching.size,
            averageInputTokens = totalInputTokens(matching) / matching.size,
            averageOutputTokens = totalOutputTokens(matching) / matching.size,
            averageTotalTokens = totalTokens(matching) / matching.size,
            averageReportedCostMicros = if (priced.isEmpty()) 0L else reportedCostMicros(priced) / priced.size,
            pricedDispatches = priced.size,
            estimatedUsageDispatches = matching.count(GlobalModelCallDispatch::usageEstimated)
        )
    }

    fun totalInputTokens(dispatches: List<GlobalModelCallDispatch>): Long = dispatches.fold(0L) { total, dispatch ->
        GlobalModelUsageEstimator.saturatingAdd(total, dispatch.inputTokens)
    }

    fun totalOutputTokens(dispatches: List<GlobalModelCallDispatch>): Long = dispatches.fold(0L) { total, dispatch ->
        GlobalModelUsageEstimator.saturatingAdd(total, dispatch.outputTokens)
    }

    fun totalTokens(dispatches: List<GlobalModelCallDispatch>): Long = dispatches.fold(0L) { total, dispatch ->
        GlobalModelUsageEstimator.saturatingAdd(total, dispatch.totalTokens)
    }

    fun reportedCostMicros(dispatches: List<GlobalModelCallDispatch>): Long = dispatches.fold(0L) { total, dispatch ->
        GlobalModelUsageEstimator.saturatingAdd(total, dispatch.reportedCostMicros)
    }

    private fun nextTokenEligibility(
        dispatches: List<GlobalModelCallDispatch>,
        reservedInputTokens: Long,
        limit: Long,
        nowMillis: Long
    ): Long {
        var remaining = totalTokens(dispatches)
        dispatches.sortedBy(GlobalModelCallDispatch::startedAtMillis).forEach { dispatch ->
            remaining = (remaining - dispatch.totalTokens).coerceAtLeast(0L)
            if (remaining == 0L || GlobalModelUsageEstimator.saturatingAdd(remaining, reservedInputTokens) <= limit) {
                return maxOf(nowMillis + RETRY_FLOOR_MILLIS, dispatch.startedAtMillis + WINDOW_MILLIS)
            }
        }
        return nowMillis + WINDOW_MILLIS
    }

    private fun nextCostEligibility(
        dispatches: List<GlobalModelCallDispatch>,
        limit: Long,
        nowMillis: Long
    ): Long {
        var remaining = reportedCostMicros(dispatches)
        dispatches.sortedBy(GlobalModelCallDispatch::startedAtMillis).forEach { dispatch ->
            remaining = (remaining - dispatch.reportedCostMicros).coerceAtLeast(0L)
            if (remaining < limit) {
                return maxOf(nowMillis + RETRY_FLOOR_MILLIS, dispatch.startedAtMillis + WINDOW_MILLIS)
            }
        }
        return nowMillis + WINDOW_MILLIS
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
        resourceId: String = "",
        estimatedInputTokens: Long = 0L,
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
            nowMillis = nowMillis,
            resourceId = resourceId,
            estimatedInputTokens = estimatedInputTokens,
            dailyTokenLimit = settings.dailyBackgroundTokenBudget,
            dailyReportedCostLimitMicros = settings.dailyBackgroundReportedCostBudgetMicros
        )
        save(decision.state)
        decision
    }

    fun availability(
        settings: GlobalAgentSettings,
        nowMillis: Long = System.currentTimeMillis(),
        estimatedInputTokens: Long = 0L
    ): GlobalModelCallBudgetDecision = synchronized(STORE_LOCK) {
        val decision = GlobalModelCallBudgetPolicy.availability(
            state = load(),
            dailyLimit = settings.dailyBackgroundModelCallBudget,
            concurrencyLimit = settings.maxConcurrentBackgroundModelCalls,
            nowMillis = nowMillis,
            estimatedInputTokens = estimatedInputTokens,
            dailyTokenLimit = settings.dailyBackgroundTokenBudget,
            dailyReportedCostLimitMicros = settings.dailyBackgroundReportedCostBudgetMicros
        )
        save(decision.state)
        decision
    }

    fun complete(
        kind: GlobalModelCallKind,
        ownerKey: String,
        inputTokens: Long,
        outputTokens: Long,
        reportedCostMicros: Long,
        responseText: String,
        nowMillis: Long = System.currentTimeMillis()
    ) = synchronized(STORE_LOCK) {
        save(GlobalModelCallBudgetPolicy.complete(
            state = load(),
            leaseId = leaseId(kind, ownerKey),
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            reportedCostMicros = reportedCostMicros,
            responseText = responseText,
            nowMillis = nowMillis
        ))
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
            },
            inputTokensInWindow = GlobalModelCallBudgetPolicy.totalInputTokens(state.dispatches),
            outputTokensInWindow = GlobalModelCallBudgetPolicy.totalOutputTokens(state.dispatches),
            totalTokensInWindow = GlobalModelCallBudgetPolicy.totalTokens(state.dispatches),
            dailyTokenLimit = settings.dailyBackgroundTokenBudget.coerceIn(
                GlobalModelCallBudgetPolicy.MIN_DAILY_TOKEN_LIMIT,
                GlobalModelCallBudgetPolicy.MAX_DAILY_TOKEN_LIMIT
            ),
            reportedCostMicrosInWindow = GlobalModelCallBudgetPolicy.reportedCostMicros(state.dispatches),
            dailyReportedCostLimitMicros = settings.dailyBackgroundReportedCostBudgetMicros.coerceIn(
                GlobalModelCallBudgetPolicy.MIN_DAILY_REPORTED_COST_LIMIT_MICROS,
                GlobalModelCallBudgetPolicy.MAX_DAILY_REPORTED_COST_LIMIT_MICROS
            ),
            estimatedUsageDispatches = state.dispatches.count(GlobalModelCallDispatch::usageEstimated),
            unpricedDispatches = state.dispatches.count { it.reportedCostMicros <= 0L }
        )
    }

    fun resourceUsageSnapshots(
        nowMillis: Long = System.currentTimeMillis()
    ): Map<String, GlobalModelResourceUsageSnapshot> = synchronized(STORE_LOCK) {
        val state = GlobalModelCallBudgetPolicy.normalize(load(), nowMillis)
        save(state)
        state.dispatches.asSequence()
            .map(GlobalModelCallDispatch::resourceId)
            .filter(String::isNotBlank)
            .distinct()
            .associateWith { resourceId -> GlobalModelCallBudgetPolicy.resourceUsage(state.dispatches, resourceId) }
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
                    if (id.isNotBlank() && startedAt > 0L) add(GlobalModelCallDispatch(
                        leaseId = id,
                        kind = kind,
                        startedAtMillis = startedAt,
                        resourceId = item.optString("resource_id").take(500),
                        inputTokens = item.optLong("input_tokens", 0L).coerceAtLeast(0L),
                        outputTokens = item.optLong("output_tokens", 0L).coerceAtLeast(0L),
                        reportedCostMicros = item.optLong("reported_cost_micros", 0L).coerceAtLeast(0L),
                        usageEstimated = item.optBoolean("usage_estimated", true),
                        completedAtMillis = item.optLong("completed_at_millis", 0L).coerceAtLeast(0L)
                    ))
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
            .put("version", 2)
            .put("dispatches", JSONArray().apply {
                state.dispatches.forEach { dispatch ->
                    put(JSONObject()
                        .put("lease_id", dispatch.leaseId)
                        .put("kind", dispatch.kind.name)
                        .put("started_at_millis", dispatch.startedAtMillis)
                        .put("resource_id", dispatch.resourceId)
                        .put("input_tokens", dispatch.inputTokens)
                        .put("output_tokens", dispatch.outputTokens)
                        .put("reported_cost_micros", dispatch.reportedCostMicros)
                        .put("usage_estimated", dispatch.usageEstimated)
                        .put("completed_at_millis", dispatch.completedAtMillis))
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
