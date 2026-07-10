package com.signalasi.chat

import android.graphics.Rect
import java.util.Locale
import kotlin.math.max

enum class AgentVisualRole {
    TITLE,
    BUTTON,
    INPUT,
    NAVIGATION,
    LIST_ITEM,
    TEXT,
    UNKNOWN
}

enum class AgentElementOrigin {
    ACCESSIBILITY,
    VISUAL_OCR,
    FUSED
}

data class AgentVisualElement(
    val text: String,
    val bounds: String,
    val confidence: Float = 1f,
    val role: AgentVisualRole = AgentVisualRole.UNKNOWN,
    val actionable: Boolean = false,
    val inputCandidate: Boolean = false
)

data class AgentVisualScene(
    val width: Int = 0,
    val height: Int = 0,
    val modelProfile: String = "none",
    val elements: List<AgentVisualElement> = emptyList(),
    val actionCandidateCount: Int = 0,
    val inputCandidateCount: Int = 0,
    val timestampMillis: Long = 0L
) {
    val available: Boolean get() = width > 0 && height > 0 && elements.isNotEmpty()
}

object AgentVisualGrounding {
    private const val MAX_VISUAL_ELEMENTS = 160
    private const val MAX_FUSED_ACTIONS = 80
    private const val MAX_FUSED_FIELDS = 30
    private const val MIN_VISUAL_ACTION_CONFIDENCE = 0.55f

    fun analyze(
        rawElements: List<AgentVisualElement>,
        width: Int,
        height: Int,
        timestampMillis: Long = System.currentTimeMillis()
    ): AgentVisualScene {
        if (width <= 0 || height <= 0) return AgentVisualScene()
        val elements = rawElements
            .asSequence()
            .mapNotNull { raw ->
                val text = raw.text.replace(Regex("\\s+"), " ").trim().take(500)
                val rect = parseBounds(raw.bounds) ?: return@mapNotNull null
                if (text.isBlank() || rect.width() <= 1 || rect.height() <= 1) return@mapNotNull null
                val role = inferRole(text, rect, width, height)
                raw.copy(
                    text = text,
                    confidence = raw.confidence.coerceIn(0f, 1f),
                    role = role,
                    actionable = role in ACTIONABLE_ROLES,
                    inputCandidate = role == AgentVisualRole.INPUT
                )
            }
            .distinctBy { "${it.text.lowercase(Locale.US)}:${it.bounds}" }
            .take(MAX_VISUAL_ELEMENTS)
            .toList()
        return AgentVisualScene(
            width = width,
            height = height,
            modelProfile = "mlkit-ocr-layout-v1",
            elements = elements,
            actionCandidateCount = elements.count { it.actionable },
            inputCandidateCount = elements.count { it.inputCandidate },
            timestampMillis = timestampMillis
        )
    }

    fun fuseClickableElements(
        accessibilityElements: List<ScreenElement>,
        scene: AgentVisualScene
    ): List<ScreenElement> = fuse(
        accessibilityElements = accessibilityElements,
        visualElements = scene.elements.filter { it.actionable },
        limit = MAX_FUSED_ACTIONS,
        requireInput = false
    )

    fun fuseInputFields(
        accessibilityElements: List<ScreenElement>,
        scene: AgentVisualScene
    ): List<ScreenElement> = fuse(
        accessibilityElements = accessibilityElements,
        visualElements = scene.elements.filter { it.inputCandidate },
        limit = MAX_FUSED_FIELDS,
        requireInput = true
    )

    private fun fuse(
        accessibilityElements: List<ScreenElement>,
        visualElements: List<AgentVisualElement>,
        limit: Int,
        requireInput: Boolean
    ): List<ScreenElement> {
        val visualPool = visualElements.toMutableList()
        val fused = accessibilityElements.map { accessibility ->
            val match = visualPool.maxByOrNull { visual -> matchScore(accessibility, visual) }
                ?.takeIf { visual -> matchScore(accessibility, visual) >= 0.45f }
            if (match == null) {
                accessibility
            } else {
                visualPool.remove(match)
                accessibility.copy(
                    label = accessibility.label.ifBlank { match.text },
                    origin = AgentElementOrigin.FUSED,
                    confidence = max(accessibility.confidence, match.confidence),
                    visualRole = match.role,
                    actionable = accessibility.actionable || match.actionable
                )
            }
        }.toMutableList()
        visualPool
            .asSequence()
            .filter { it.confidence >= MIN_VISUAL_ACTION_CONFIDENCE }
            .filter { !requireInput || it.inputCandidate }
            .filter { visual -> fused.none { overlapRatio(it.bounds, visual.bounds) >= 0.65f } }
            .mapIndexed { index, visual ->
                ScreenElement(
                    label = visual.text,
                    viewId = "visual:${visual.role.name.lowercase(Locale.US)}:$index",
                    className = "AgentVisual${visual.role.name.lowercase(Locale.US).replaceFirstChar { it.uppercase() }}",
                    bounds = visual.bounds,
                    origin = AgentElementOrigin.VISUAL_OCR,
                    confidence = visual.confidence,
                    visualRole = visual.role,
                    actionable = visual.actionable
                )
            }
            .take((limit - fused.size).coerceAtLeast(0))
            .forEach(fused::add)
        return fused.take(limit)
    }

    private fun inferRole(text: String, rect: Rect, width: Int, height: Int): AgentVisualRole {
        val normalized = text.lowercase(Locale.US)
        val centerY = rect.centerY().toFloat() / height
        val widthRatio = rect.width().toFloat() / width
        val shortLabel = text.length <= 36
        if (INPUT_TERMS.any(normalized::contains)) return AgentVisualRole.INPUT
        if (ACTION_TERMS.any(normalized::equals) ||
            (shortLabel && ACTION_TERMS.any { normalized.startsWith(it) })
        ) return AgentVisualRole.BUTTON
        if (centerY >= 0.82f && shortLabel) return AgentVisualRole.NAVIGATION
        if (centerY <= 0.18f && widthRatio >= 0.18f) return AgentVisualRole.TITLE
        if (shortLabel && widthRatio >= 0.22f && centerY in 0.18f..0.82f) return AgentVisualRole.LIST_ITEM
        return AgentVisualRole.TEXT
    }

    private fun matchScore(accessibility: ScreenElement, visual: AgentVisualElement): Float {
        val overlap = overlapRatio(accessibility.bounds, visual.bounds)
        val accessibilityLabel = accessibility.label.normalizedLabel()
        val visualLabel = visual.text.normalizedLabel()
        val labelScore = when {
            accessibilityLabel.isBlank() || visualLabel.isBlank() -> 0f
            accessibilityLabel == visualLabel -> 1f
            accessibilityLabel.contains(visualLabel) || visualLabel.contains(accessibilityLabel) -> 0.75f
            else -> 0f
        }
        return max(overlap, labelScore)
    }

    private fun overlapRatio(firstBounds: String, secondBounds: String): Float {
        val first = parseBounds(firstBounds) ?: return 0f
        val second = parseBounds(secondBounds) ?: return 0f
        val intersection = Rect()
        if (!intersection.setIntersect(first, second)) return 0f
        val intersectionArea = intersection.width().toLong() * intersection.height().toLong()
        val smallerArea = minOf(
            first.width().toLong() * first.height().toLong(),
            second.width().toLong() * second.height().toLong()
        )
        if (smallerArea <= 0L) return 0f
        return intersectionArea.toFloat() / smallerArea
    }

    private fun parseBounds(bounds: String): Rect? {
        val values = bounds.split(',').mapNotNull { it.trim().toIntOrNull() }
        if (values.size != 4 || values[2] <= values[0] || values[3] <= values[1]) return null
        return Rect(values[0], values[1], values[2], values[3])
    }

    private fun String.normalizedLabel(): String = lowercase(Locale.US)
        .replace(Regex("[^\\p{L}\\p{N}]+"), "")

    private val ACTIONABLE_ROLES = setOf(
        AgentVisualRole.BUTTON,
        AgentVisualRole.NAVIGATION,
        AgentVisualRole.LIST_ITEM
    )
    private val ACTION_TERMS = listOf(
        "ok", "yes", "no", "done", "next", "continue", "confirm", "cancel", "save", "send", "search",
        "open", "close", "add", "delete", "edit", "allow", "deny", "login", "sign in", "submit", "share",
        "\u786e\u5b9a", "\u53d6\u6d88", "\u4fdd\u5b58", "\u53d1\u9001", "\u641c\u7d22", "\u4e0b\u4e00\u6b65",
        "\u7ee7\u7eed", "\u786e\u8ba4", "\u5141\u8bb8", "\u62d2\u7edd", "\u767b\u5f55", "\u63d0\u4ea4",
        "\u6dfb\u52a0", "\u5220\u9664", "\u7f16\u8f91", "\u5173\u95ed", "\u6253\u5f00", "\u5206\u4eab"
    )
    private val INPUT_TERMS = listOf(
        "search", "type", "enter", "message", "email", "phone", "name", "password", "input",
        "\u641c\u7d22", "\u8f93\u5165", "\u6d88\u606f", "\u90ae\u7bb1", "\u624b\u673a\u53f7", "\u59d3\u540d", "\u5bc6\u7801"
    )
}

object AgentScreenElementMatcher {
    fun resolve(query: String, elements: List<ScreenElement>): ScreenElement? {
        val clean = query.normalizedElementLabel()
        if (clean.isBlank()) return null
        return elements
            .map { element -> element to score(clean, element) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(
                compareByDescending<Pair<ScreenElement, Int>> { it.second }
                    .thenByDescending { it.first.confidence }
                    .thenBy { it.first.origin == AgentElementOrigin.VISUAL_OCR }
            )
            .firstOrNull()
            ?.first
    }

    private fun score(query: String, element: ScreenElement): Int {
        val label = element.label.normalizedElementLabel()
        val viewId = element.viewId.normalizedElementLabel()
        val className = element.className.normalizedElementLabel()
        val role = element.visualRole.name.normalizedElementLabel()
        return when {
            viewId == query -> 140
            label == query -> 120
            label.startsWith(query) -> 100
            label.contains(query) -> 90
            query.contains(label) && label.length >= 2 -> 75
            viewId.contains(query) -> 65
            className.contains(query) -> 35
            role == query -> 25
            else -> 0
        }
    }

    private fun String.normalizedElementLabel(): String = lowercase(Locale.US)
        .replace(Regex("[^\\p{L}\\p{N}]+"), "")
}
