package com.signalasi.chat

object ScreenPerceptionState {
    @Volatile
    private var latestSnapshot: AccessibilityScreenSnapshot? = null

    fun update(snapshot: AccessibilityScreenSnapshot) {
        latestSnapshot = snapshot
    }

    fun current(defaultApp: String, defaultTitle: String): ScreenContext {
        val snapshot = latestSnapshot ?: return ScreenContext(
            foregroundApp = defaultApp,
            pageTitle = defaultTitle,
            isAccessibilityEnabled = false
        )
        return ScreenContext(
            foregroundApp = snapshot.packageName.ifBlank { defaultApp },
            activityName = snapshot.className,
            pageTitle = snapshot.pageTitle.ifBlank { defaultTitle },
            visibleTextCount = snapshot.visibleTexts.size,
            clickableNodeCount = snapshot.clickableElements.size,
            inputFieldCount = snapshot.inputFields.size,
            scrollableRegionCount = snapshot.scrollableRegions.size,
            sensitiveFlagCount = snapshot.sensitiveFlags.size,
            visibleTexts = snapshot.visibleTexts,
            clickableElements = snapshot.clickableElements,
            inputFields = snapshot.inputFields,
            scrollableRegions = snapshot.scrollableRegions,
            sensitiveFlags = snapshot.sensitiveFlags,
            isAccessibilityEnabled = true,
            snapshotAgeMillis = System.currentTimeMillis() - snapshot.timestampMillis
        )
    }
}

data class AccessibilityScreenSnapshot(
    val packageName: String,
    val className: String,
    val pageTitle: String,
    val visibleTexts: List<String>,
    val clickableElements: List<ScreenElement>,
    val inputFields: List<ScreenElement>,
    val scrollableRegions: List<ScreenElement>,
    val sensitiveFlags: List<String>,
    val timestampMillis: Long = System.currentTimeMillis()
)

data class ScreenElement(
    val label: String,
    val viewId: String,
    val className: String,
    val bounds: String
)
