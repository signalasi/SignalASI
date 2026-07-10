package com.signalasi.chat

enum class AgentObservationDecision {
    ACTION_FAILED,
    NO_CHANGE_REQUIRED,
    CHANGED_AND_STABLE,
    CHANGED_BUT_UNSTABLE,
    TIMED_OUT
}

data class AgentObservationOutcome(
    val screen: ScreenContext,
    val decision: AgentObservationDecision,
    val sampleCount: Int,
    val durationMillis: Long,
    val screenChanged: Boolean,
    val screenStable: Boolean,
    val evidence: String
)

class AgentContinuousObservationController(
    private val maxSamples: Int = DEFAULT_MAX_SAMPLES,
    private val stableSampleCount: Int = DEFAULT_STABLE_SAMPLE_COUNT,
    private val sampleIntervalMillis: Long = DEFAULT_SAMPLE_INTERVAL_MILLIS
) {
    init {
        require(maxSamples in 1..MAX_ALLOWED_SAMPLES)
        require(stableSampleCount in 1..maxSamples)
        require(sampleIntervalMillis in 0L..MAX_SAMPLE_INTERVAL_MILLIS)
    }

    fun observe(
        beforeAction: ScreenContext,
        actionSucceeded: Boolean,
        changeExpected: Boolean,
        capture: () -> ScreenContext,
        sleep: (Long) -> Unit = { Thread.sleep(it) }
    ): AgentObservationOutcome {
        val startedAt = System.currentTimeMillis()
        var latest = capture()
        if (!actionSucceeded) {
            return outcome(
                latest,
                AgentObservationDecision.ACTION_FAILED,
                1,
                startedAt,
                changed = latest.fingerprint() != beforeAction.fingerprint(),
                stable = false
            )
        }
        if (!changeExpected) {
            return outcome(
                latest,
                AgentObservationDecision.NO_CHANGE_REQUIRED,
                1,
                startedAt,
                changed = latest.fingerprint() != beforeAction.fingerprint(),
                stable = true
            )
        }

        val beforeFingerprint = beforeAction.fingerprint()
        var previousFingerprint: AgentScreenFingerprint? = null
        var changed = false
        var stableSamples = 0
        var samples = 0
        repeat(maxSamples) { index ->
            if (index > 0) {
                runCatching { sleep(sampleIntervalMillis) }
                latest = capture()
            }
            samples++
            val fingerprint = latest.fingerprint()
            val differsFromBefore = fingerprint != beforeFingerprint
            changed = changed || differsFromBefore
            stableSamples = when {
                !differsFromBefore -> 0
                previousFingerprint == fingerprint -> stableSamples + 1
                else -> 1
            }
            previousFingerprint = fingerprint
            if (changed && stableSamples >= stableSampleCount) {
                return outcome(
                    latest,
                    AgentObservationDecision.CHANGED_AND_STABLE,
                    samples,
                    startedAt,
                    changed = true,
                    stable = true
                )
            }
        }
        return outcome(
            latest,
            if (changed) AgentObservationDecision.CHANGED_BUT_UNSTABLE else AgentObservationDecision.TIMED_OUT,
            samples,
            startedAt,
            changed = changed,
            stable = false
        )
    }

    private fun outcome(
        screen: ScreenContext,
        decision: AgentObservationDecision,
        sampleCount: Int,
        startedAt: Long,
        changed: Boolean,
        stable: Boolean
    ): AgentObservationOutcome {
        val duration = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        return AgentObservationOutcome(
            screen = screen,
            decision = decision,
            sampleCount = sampleCount,
            durationMillis = duration,
            screenChanged = changed,
            screenStable = stable,
            evidence = "decision=${decision.name}; samples=$sampleCount; duration_ms=$duration; changed=$changed; stable=$stable"
        )
    }

    private fun ScreenContext.fingerprint(): AgentScreenFingerprint = AgentScreenFingerprint(
        foregroundApp = foregroundApp,
        activityName = activityName,
        pageTitle = pageTitle,
        visibleTextHash = visibleTexts
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .take(MAX_FINGERPRINT_TEXT_ITEMS)
            .joinToString("\u001f")
            .hashCode(),
        visibleTextCount = visibleTextCount,
        clickableNodeCount = clickableNodeCount,
        inputFieldCount = inputFieldCount,
        scrollableRegionCount = scrollableRegionCount
    )

    private companion object {
        const val DEFAULT_MAX_SAMPLES = 10
        const val DEFAULT_STABLE_SAMPLE_COUNT = 2
        const val DEFAULT_SAMPLE_INTERVAL_MILLIS = 250L
        const val MAX_ALLOWED_SAMPLES = 30
        const val MAX_SAMPLE_INTERVAL_MILLIS = 2_000L
        const val MAX_FINGERPRINT_TEXT_ITEMS = 80
    }
}

private data class AgentScreenFingerprint(
    val foregroundApp: String,
    val activityName: String,
    val pageTitle: String,
    val visibleTextHash: Int,
    val visibleTextCount: Int,
    val clickableNodeCount: Int,
    val inputFieldCount: Int,
    val scrollableRegionCount: Int
)
