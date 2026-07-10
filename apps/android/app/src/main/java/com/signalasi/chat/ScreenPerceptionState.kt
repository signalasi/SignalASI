package com.signalasi.chat

import java.util.concurrent.CopyOnWriteArraySet

data class AgentVisualScreenResult(
    val success: Boolean,
    val textLines: List<String> = emptyList(),
    val scene: AgentVisualScene = AgentVisualScene(),
    val width: Int = 0,
    val height: Int = 0,
    val sourcePackage: String = "",
    val error: String = "",
    val timestampMillis: Long = System.currentTimeMillis()
) {
    companion object {
        fun success(
            scene: AgentVisualScene,
            width: Int,
            height: Int,
            sourcePackage: String = ""
        ): AgentVisualScreenResult =
            AgentVisualScreenResult(
                success = true,
                textLines = scene.elements
                    .map { it.text.take(MAX_VISUAL_TEXT_LENGTH) }
                    .distinct()
                    .take(MAX_VISUAL_TEXT_ITEMS),
                scene = scene,
                width = width.coerceAtLeast(0),
                height = height.coerceAtLeast(0),
                sourcePackage = sourcePackage.take(MAX_PACKAGE_LENGTH)
            )

        fun failure(error: String): AgentVisualScreenResult =
            AgentVisualScreenResult(success = false, error = error.take(MAX_ERROR_LENGTH))

        private const val MAX_VISUAL_TEXT_ITEMS = 120
        private const val MAX_VISUAL_TEXT_LENGTH = 500
        private const val MAX_ERROR_LENGTH = 500
        private const val MAX_PACKAGE_LENGTH = 240
    }
}

fun interface AgentVisualScreenListener {
    fun onVisualScreenUpdated(result: AgentVisualScreenResult)
}

object ScreenPerceptionState {
    @Volatile
    private var latestSnapshot: AccessibilityScreenSnapshot? = null

    @Volatile
    private var latestVisualResult: AgentVisualScreenResult? = null

    private val visualListeners = CopyOnWriteArraySet<AgentVisualScreenListener>()

    fun update(snapshot: AccessibilityScreenSnapshot) {
        latestSnapshot = snapshot
    }

    fun updateVisual(result: AgentVisualScreenResult) {
        if (result.success) latestVisualResult = result
        visualListeners.forEach { it.onVisualScreenUpdated(result) }
    }

    fun clearVisual(error: String = "") {
        latestVisualResult = null
        if (error.isNotBlank()) {
            val result = AgentVisualScreenResult.failure(error)
            visualListeners.forEach { it.onVisualScreenUpdated(result) }
        }
    }

    fun addVisualListener(listener: AgentVisualScreenListener) {
        visualListeners += listener
    }

    fun removeVisualListener(listener: AgentVisualScreenListener) {
        visualListeners -= listener
    }

    fun hasRecentVisualCapture(now: Long = System.currentTimeMillis()): Boolean =
        recentVisualResult(now) != null

    fun currentPackageName(): String = latestSnapshot?.packageName.orEmpty()

    fun current(defaultApp: String, defaultTitle: String): ScreenContext {
        val now = System.currentTimeMillis()
        val visual = recentVisualResult(now)
        val snapshot = latestSnapshot
        if (snapshot == null || shouldPreferVisualOnly(snapshot, visual)) {
            return visualOnlyContext(visual, defaultApp, defaultTitle, now)
        }
        val matchingVisual = visual?.takeIf {
            it.sourcePackage.isBlank() || it.sourcePackage == snapshot.packageName
        }
        val visualTexts = matchingVisual?.textLines.orEmpty()
        val visualScene = matchingVisual?.scene ?: AgentVisualScene()
        val combinedTexts = snapshot.visibleTexts
            .plus(visualTexts)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_COMBINED_TEXT_ITEMS)
        val visualSensitiveFlags = sensitiveFlagsFor(visualTexts)
        val combinedSensitiveFlags = snapshot.sensitiveFlags
            .plus(visualSensitiveFlags)
            .distinct()
            .take(MAX_SENSITIVE_FLAGS)
        val clickableElements = AgentVisualGrounding.fuseClickableElements(
            snapshot.clickableElements,
            visualScene
        )
        val inputFields = AgentVisualGrounding.fuseInputFields(snapshot.inputFields, visualScene)
        return ScreenContext(
            foregroundApp = displayPackageName(snapshot.packageName, defaultApp),
            activityName = snapshot.className,
            pageTitle = snapshot.pageTitle.ifBlank { defaultTitle },
            visibleTextCount = combinedTexts.size,
            clickableNodeCount = clickableElements.size,
            inputFieldCount = inputFields.size,
            scrollableRegionCount = snapshot.scrollableRegions.size,
            sensitiveFlagCount = combinedSensitiveFlags.size,
            visibleTexts = combinedTexts,
            selectedText = snapshot.selectedText,
            focusedInputField = snapshot.focusedInputField,
            clickableElements = clickableElements,
            inputFields = inputFields,
            scrollableRegions = snapshot.scrollableRegions,
            sensitiveFlags = combinedSensitiveFlags,
            visualScene = visualScene,
            isAccessibilityEnabled = true,
            snapshotAgeMillis = minOf(
                now - snapshot.timestampMillis,
                matchingVisual?.let { now - it.timestampMillis } ?: Long.MAX_VALUE
            ).coerceAtLeast(0L)
        )
    }

    private fun visualOnlyContext(
        visual: AgentVisualScreenResult?,
        defaultApp: String,
        defaultTitle: String,
        now: Long
    ): ScreenContext {
        val lines = visual?.textLines.orEmpty()
        val scene = visual?.scene ?: AgentVisualScene()
        val clickableElements = AgentVisualGrounding.fuseClickableElements(emptyList(), scene)
        val inputFields = AgentVisualGrounding.fuseInputFields(emptyList(), scene)
        val sensitiveFlags = sensitiveFlagsFor(lines)
        return ScreenContext(
            foregroundApp = visual?.sourcePackage?.ifBlank { defaultApp } ?: defaultApp,
            pageTitle = visual?.sourcePackage?.substringAfterLast('.')?.ifBlank { defaultTitle } ?: defaultTitle,
            visibleTextCount = lines.size,
            visibleTexts = lines,
            clickableNodeCount = clickableElements.size,
            inputFieldCount = inputFields.size,
            clickableElements = clickableElements,
            inputFields = inputFields,
            sensitiveFlagCount = sensitiveFlags.size,
            sensitiveFlags = sensitiveFlags,
            visualScene = scene,
            isAccessibilityEnabled = false,
            snapshotAgeMillis = visual?.let { now - it.timestampMillis } ?: 0L
        )
    }

    private fun shouldPreferVisualOnly(
        snapshot: AccessibilityScreenSnapshot,
        visual: AgentVisualScreenResult?
    ): Boolean = visual != null &&
        visual.sourcePackage.isNotBlank() &&
        visual.sourcePackage != snapshot.packageName &&
        snapshot.packageName == SIGNALASI_PACKAGE

    private fun recentVisualResult(now: Long): AgentVisualScreenResult? = latestVisualResult
        ?.takeIf { it.success && now >= it.timestampMillis && now - it.timestampMillis <= MAX_VISUAL_AGE_MILLIS }

    private fun sensitiveFlagsFor(lines: List<String>): List<String> {
        val text = lines.joinToString(" ").lowercase()
        if (text.isBlank()) return emptyList()
        return SENSITIVE_VISUAL_TERMS.filter { text.contains(it) }.take(MAX_SENSITIVE_FLAGS)
    }

    private fun displayPackageName(packageName: String, defaultApp: String): String {
        val resolvedPackage = packageName.ifBlank { defaultApp }
        return if (resolvedPackage == "com.signalasi.chat" && defaultApp == "SignalASI") {
            defaultApp
        } else {
            resolvedPackage
        }
    }

    private const val MAX_VISUAL_AGE_MILLIS = 60_000L
    private const val MAX_COMBINED_TEXT_ITEMS = 120
    private const val MAX_SENSITIVE_FLAGS = 12
    private const val SIGNALASI_PACKAGE = "com.signalasi.chat"
    private val SENSITIVE_VISUAL_TERMS = listOf(
        "password",
        "passcode",
        "verification code",
        "otp",
        "bank",
        "card number",
        "private key",
        "secret",
        "token",
        "pin",
        "\u5bc6\u7801",
        "\u9a8c\u8bc1\u7801",
        "\u94f6\u884c\u5361",
        "\u79c1\u94a5",
        "\u652f\u4ed8"
    )
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
    val bounds: String,
    val origin: AgentElementOrigin = AgentElementOrigin.ACCESSIBILITY,
    val confidence: Float = 1f,
    val visualRole: AgentVisualRole = AgentVisualRole.UNKNOWN,
    val actionable: Boolean = true
)
