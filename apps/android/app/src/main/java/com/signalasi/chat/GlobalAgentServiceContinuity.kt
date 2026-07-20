package com.signalasi.chat

class GlobalAgentRecoverySignalGate(
    private val cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS
) {
    private val lock = Any()
    private var pending = false
    private var lastAcceptedAtMillis = 0L

    fun tryAcquire(nowMillis: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        if (pending) return@synchronized false
        if (lastAcceptedAtMillis > 0L && nowMillis - lastAcceptedAtMillis < cooldownMillis) {
            return@synchronized false
        }
        pending = true
        lastAcceptedAtMillis = nowMillis
        true
    }

    fun release() = synchronized(lock) {
        pending = false
    }

    companion object {
        const val DEFAULT_COOLDOWN_MILLIS = 2_000L
    }
}

object GlobalAgentServiceContinuityPolicy {
    const val SERVICE_RECOVERY_DELAY_MILLIS = 60_000L

    fun recoveryWakeAt(
        nowMillis: Long,
        scheduledWorkWakeAtMillis: Long
    ): Long {
        val serviceRecovery = nowMillis + SERVICE_RECOVERY_DELAY_MILLIS
        return scheduledWorkWakeAtMillis.takeIf { it > nowMillis }
            ?.let { minOf(it, serviceRecovery) }
            ?: serviceRecovery
    }
}
