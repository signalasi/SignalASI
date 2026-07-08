package com.signalasi.chat

object AppForegroundTracker {
    @Volatile
    private var resumedActivities = 0

    fun onActivityResumed() {
        resumedActivities += 1
    }

    fun onActivityPaused() {
        resumedActivities = (resumedActivities - 1).coerceAtLeast(0)
    }

    fun isForeground(): Boolean = resumedActivities > 0
}
