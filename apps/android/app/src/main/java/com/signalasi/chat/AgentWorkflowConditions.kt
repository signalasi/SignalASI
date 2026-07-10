package com.signalasi.chat

enum class AgentWorkflowStringMatch {
    EQUALS,
    CONTAINS
}

enum class AgentWorkflowBatteryComparison {
    BELOW,
    AT_MOST,
    AT_LEAST,
    ABOVE
}

sealed interface AgentWorkflowCondition {
    data class Text(
        val expected: String,
        val match: AgentWorkflowStringMatch = AgentWorkflowStringMatch.CONTAINS,
        val ignoreCase: Boolean = true
    ) : AgentWorkflowCondition {
        init {
            require(expected.isNotBlank()) { "Expected text must not be blank" }
        }
    }

    data class PackageName(
        val expected: String,
        val match: AgentWorkflowStringMatch = AgentWorkflowStringMatch.EQUALS,
        val ignoreCase: Boolean = true
    ) : AgentWorkflowCondition {
        init {
            require(expected.isNotBlank()) { "Expected package name must not be blank" }
        }
    }

    data class DeviceCharging(
        val required: Boolean = true
    ) : AgentWorkflowCondition

    data class BatteryThreshold(
        val percent: Int,
        val comparison: AgentWorkflowBatteryComparison
    ) : AgentWorkflowCondition {
        init {
            require(percent in MIN_PERCENT..MAX_PERCENT) { "Battery threshold must be between 0 and 100" }
        }
    }

    data class NetworkAvailable(
        val required: Boolean = true
    ) : AgentWorkflowCondition

    /**
     * A local-time window with an inclusive start and exclusive end.
     * A window whose start and end are equal matches the entire day.
     */
    data class TimeWindow(
        val startMinuteOfDay: Int,
        val endMinuteOfDay: Int
    ) : AgentWorkflowCondition {
        init {
            require(startMinuteOfDay in MIN_MINUTE_OF_DAY..MAX_MINUTE_OF_DAY) {
                "Time window start must be between 0 and 1439"
            }
            require(endMinuteOfDay in MIN_MINUTE_OF_DAY..MAX_MINUTE_OF_DAY) {
                "Time window end must be between 0 and 1439"
            }
        }
    }

    private companion object {
        const val MIN_PERCENT = 0
        const val MAX_PERCENT = 100
        const val MIN_MINUTE_OF_DAY = 0
        const val MAX_MINUTE_OF_DAY = 23 * 60 + 59
    }
}

/**
 * Immutable device and event state supplied to the condition evaluator.
 * Null values represent state that was not available to the caller.
 */
data class AgentWorkflowConditionSnapshot(
    val text: String? = null,
    val packageName: String? = null,
    val isDeviceCharging: Boolean? = null,
    val batteryPercent: Int? = null,
    val isNetworkAvailable: Boolean? = null,
    val minuteOfDay: Int? = null
) {
    init {
        require(batteryPercent == null || batteryPercent in 0..100) {
            "Battery percent must be between 0 and 100"
        }
        require(minuteOfDay == null || minuteOfDay in 0..(23 * 60 + 59)) {
            "Minute of day must be between 0 and 1439"
        }
    }
}

object AgentWorkflowConditionEvaluator {
    fun evaluate(
        condition: AgentWorkflowCondition,
        snapshot: AgentWorkflowConditionSnapshot
    ): Boolean = when (condition) {
        is AgentWorkflowCondition.Text -> matchesString(
            actual = snapshot.text,
            expected = condition.expected,
            match = condition.match,
            ignoreCase = condition.ignoreCase
        )

        is AgentWorkflowCondition.PackageName -> matchesString(
            actual = snapshot.packageName,
            expected = condition.expected,
            match = condition.match,
            ignoreCase = condition.ignoreCase
        )

        is AgentWorkflowCondition.DeviceCharging ->
            snapshot.isDeviceCharging == condition.required

        is AgentWorkflowCondition.BatteryThreshold ->
            matchesBatteryThreshold(snapshot.batteryPercent, condition)

        is AgentWorkflowCondition.NetworkAvailable ->
            snapshot.isNetworkAvailable == condition.required

        is AgentWorkflowCondition.TimeWindow ->
            matchesTimeWindow(snapshot.minuteOfDay, condition)
    }

    fun evaluateAll(
        conditions: Iterable<AgentWorkflowCondition>,
        snapshot: AgentWorkflowConditionSnapshot
    ): Boolean = conditions.all { evaluate(it, snapshot) }

    fun evaluateAny(
        conditions: Iterable<AgentWorkflowCondition>,
        snapshot: AgentWorkflowConditionSnapshot
    ): Boolean = conditions.any { evaluate(it, snapshot) }

    private fun matchesString(
        actual: String?,
        expected: String,
        match: AgentWorkflowStringMatch,
        ignoreCase: Boolean
    ): Boolean {
        actual ?: return false
        return when (match) {
            AgentWorkflowStringMatch.EQUALS -> actual.equals(expected, ignoreCase = ignoreCase)
            AgentWorkflowStringMatch.CONTAINS -> actual.contains(expected, ignoreCase = ignoreCase)
        }
    }

    private fun matchesBatteryThreshold(
        batteryPercent: Int?,
        condition: AgentWorkflowCondition.BatteryThreshold
    ): Boolean {
        batteryPercent ?: return false
        return when (condition.comparison) {
            AgentWorkflowBatteryComparison.BELOW -> batteryPercent < condition.percent
            AgentWorkflowBatteryComparison.AT_MOST -> batteryPercent <= condition.percent
            AgentWorkflowBatteryComparison.AT_LEAST -> batteryPercent >= condition.percent
            AgentWorkflowBatteryComparison.ABOVE -> batteryPercent > condition.percent
        }
    }

    private fun matchesTimeWindow(
        minuteOfDay: Int?,
        condition: AgentWorkflowCondition.TimeWindow
    ): Boolean {
        minuteOfDay ?: return false
        val start = condition.startMinuteOfDay
        val end = condition.endMinuteOfDay
        return when {
            start == end -> true
            start < end -> minuteOfDay in start until end
            else -> minuteOfDay >= start || minuteOfDay < end
        }
    }
}
