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
            foregroundApp = displayPackageName(snapshot.packageName, defaultApp),
            activityName = snapshot.className,
            pageTitle = snapshot.pageTitle.ifBlank { defaultTitle },
            visibleTextCount = snapshot.visibleTexts.size,
            clickableNodeCount = snapshot.clickableElements.size,
            inputFieldCount = snapshot.inputFields.size,
            scrollableRegionCount = snapshot.scrollableRegions.size,
            sensitiveFlagCount = snapshot.sensitiveFlags.size,
            visibleTexts = snapshot.visibleTexts,
            selectedText = snapshot.selectedText,
            focusedInputField = snapshot.focusedInputField,
            clickableElements = snapshot.clickableElements,
            inputFields = snapshot.inputFields,
            scrollableRegions = snapshot.scrollableRegions,
            sensitiveFlags = snapshot.sensitiveFlags,
            isAccessibilityEnabled = true,
            snapshotAgeMillis = System.currentTimeMillis() - snapshot.timestampMillis
        )
    }

    private fun displayPackageName(packageName: String, defaultApp: String): String {
        val resolvedPackage = packageName.ifBlank { defaultApp }
        return if (resolvedPackage == "com.signalasi.chat" && defaultApp == "SignalASI") {
            defaultApp
        } else {
            resolvedPackage
        }
    }
}

data class AccessibilityScreenSnapshot(
    val packageName: String,
    val className: String,
    val pageTitle: String,
    val visibleTexts: List<String>,
    val selectedText: String = "",
    val focusedInputField: ScreenElement? = null,
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
