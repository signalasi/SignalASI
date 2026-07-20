package com.signalasi.chat

enum class GlobalBackgroundWorkKind {
    COGNITION,
    RESEARCH,
    AUTONOMOUS_WORK
}

enum class GlobalBackgroundDeferralReason {
    NONE,
    POWER_SAVE,
    CRITICAL_BATTERY,
    LOW_BATTERY,
    NETWORK_UNAVAILABLE,
    NETWORK_UNVALIDATED,
    METERED_NETWORK
}

data class GlobalBackgroundExecutionDecision(
    val allowed: Boolean,
    val nextEligibleAtMillis: Long,
    val reason: GlobalBackgroundDeferralReason = GlobalBackgroundDeferralReason.NONE
)

object GlobalBackgroundExecutionBudgetPolicy {
    fun decide(
        kind: GlobalBackgroundWorkKind,
        environment: AgentRuntimeEnvironment,
        settings: GlobalAgentSettings,
        nowMillis: Long,
        explicitUserOverride: Boolean = false
    ): GlobalBackgroundExecutionDecision {
        if (explicitUserOverride) return allowed(nowMillis)
        if (settings.protectBatteryForBackgroundWork) {
            if (environment.powerSaveMode) {
                return deferred(nowMillis, POWER_SAVE_RETRY_MILLIS, GlobalBackgroundDeferralReason.POWER_SAVE)
            }
            if (!environment.charging && environment.batteryPercent in 0..CRITICAL_BATTERY_PERCENT) {
                return deferred(
                    nowMillis,
                    CRITICAL_BATTERY_RETRY_MILLIS,
                    GlobalBackgroundDeferralReason.CRITICAL_BATTERY
                )
            }
            if (!environment.charging && environment.batteryPercent in
                (CRITICAL_BATTERY_PERCENT + 1)..LOW_BATTERY_PERCENT
            ) {
                val retry = if (kind == GlobalBackgroundWorkKind.RESEARCH) {
                    LOW_BATTERY_RESEARCH_RETRY_MILLIS
                } else LOW_BATTERY_REASONING_RETRY_MILLIS
                return deferred(nowMillis, retry, GlobalBackgroundDeferralReason.LOW_BATTERY)
            }
        }
        if (kind == GlobalBackgroundWorkKind.RESEARCH) {
            if (!environment.networkAvailable) {
                return deferred(
                    nowMillis,
                    NETWORK_RECOVERY_RETRY_MILLIS,
                    GlobalBackgroundDeferralReason.NETWORK_UNAVAILABLE
                )
            }
            if (!environment.networkValidated) {
                return deferred(
                    nowMillis,
                    NETWORK_RECOVERY_RETRY_MILLIS,
                    GlobalBackgroundDeferralReason.NETWORK_UNVALIDATED
                )
            }
            if (environment.networkMetered && !settings.allowMeteredBackgroundResearch) {
                return deferred(
                    nowMillis,
                    METERED_NETWORK_RETRY_MILLIS,
                    GlobalBackgroundDeferralReason.METERED_NETWORK
                )
            }
        }
        return allowed(nowMillis)
    }

    private fun allowed(nowMillis: Long) = GlobalBackgroundExecutionDecision(
        allowed = true,
        nextEligibleAtMillis = nowMillis
    )

    private fun deferred(
        nowMillis: Long,
        retryMillis: Long,
        reason: GlobalBackgroundDeferralReason
    ) = GlobalBackgroundExecutionDecision(
        allowed = false,
        nextEligibleAtMillis = nowMillis + retryMillis,
        reason = reason
    )

    const val CRITICAL_BATTERY_PERCENT = 14
    const val LOW_BATTERY_PERCENT = 24
    const val POWER_SAVE_RETRY_MILLIS = 30L * 60L * 1_000L
    const val CRITICAL_BATTERY_RETRY_MILLIS = 60L * 60L * 1_000L
    const val LOW_BATTERY_REASONING_RETRY_MILLIS = 20L * 60L * 1_000L
    const val LOW_BATTERY_RESEARCH_RETRY_MILLIS = 45L * 60L * 1_000L
    const val NETWORK_RECOVERY_RETRY_MILLIS = 10L * 60L * 1_000L
    const val METERED_NETWORK_RETRY_MILLIS = 60L * 60L * 1_000L
}
