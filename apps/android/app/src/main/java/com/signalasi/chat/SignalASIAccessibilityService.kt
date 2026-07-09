package com.signalasi.chat

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class SignalASIAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        val packageName = event?.packageName?.toString().orEmpty()
        val className = event?.className?.toString().orEmpty()
        ScreenPerceptionState.update(
            AccessibilityTreeReader.snapshot(
                root = root,
                packageName = packageName,
                className = className
            )
        )
    }

    override fun onServiceConnected() {
        rootInActiveWindow?.let { root ->
            ScreenPerceptionState.update(
                AccessibilityTreeReader.snapshot(
                    root = root,
                    packageName = root.packageName?.toString().orEmpty(),
                    className = root.className?.toString().orEmpty()
                )
            )
        }
    }

    override fun onInterrupt() = Unit
}

private object AccessibilityTreeReader {
    private const val MAX_NODES = 220
    private const val MAX_TEXT_ITEMS = 80
    private const val MAX_ELEMENT_ITEMS = 50
    private const val MAX_FLAG_ITEMS = 12

    private val sensitiveKeywords = listOf(
        "password",
        "passcode",
        "verification",
        "otp",
        "bank",
        "card",
        "secret",
        "token",
        "pin",
        "ssn"
    )

    fun snapshot(
        root: AccessibilityNodeInfo,
        packageName: String,
        className: String
    ): AccessibilityScreenSnapshot {
        val collector = TreeCollector(
            packageName = packageName.ifBlank { root.packageName?.toString().orEmpty() },
            className = className.ifBlank { root.className?.toString().orEmpty() }
        )
        collector.collect(root)
        return collector.toSnapshot()
    }

    private class TreeCollector(
        private val packageName: String,
        private val className: String
    ) {
        private var visitedNodes = 0
        private var firstTitle = ""
        private val visibleTexts = mutableListOf<String>()
        private val clickableElements = mutableListOf<ScreenElement>()
        private val inputFields = mutableListOf<ScreenElement>()
        private val scrollableRegions = mutableListOf<ScreenElement>()
        private val sensitiveFlags = mutableListOf<String>()

        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null || visitedNodes >= MAX_NODES) return
            visitedNodes += 1

            val label = node.text?.toString()?.trim().orEmpty()
            val description = node.contentDescription?.toString()?.trim().orEmpty()
            val displayLabel = label.ifBlank { description }
            if (displayLabel.isNotBlank()) {
                if (firstTitle.isBlank()) firstTitle = displayLabel
                addLimited(visibleTexts, displayLabel, MAX_TEXT_ITEMS)
                maybeFlagSensitive(displayLabel)
            }

            val element = ScreenElement(
                label = displayLabel.ifBlank { node.className?.toString().orEmpty() },
                viewId = node.viewIdResourceName.orEmpty(),
                className = node.className?.toString().orEmpty(),
                bounds = boundsOf(node)
            )
            if (node.isClickable) addLimited(clickableElements, element, MAX_ELEMENT_ITEMS)
            if (node.isEditable) addLimited(inputFields, element, MAX_ELEMENT_ITEMS)
            if (node.isScrollable) addLimited(scrollableRegions, element, MAX_ELEMENT_ITEMS)

            for (index in 0 until node.childCount) {
                collect(node.getChild(index))
            }
        }

        fun toSnapshot(): AccessibilityScreenSnapshot = AccessibilityScreenSnapshot(
            packageName = packageName,
            className = className,
            pageTitle = firstTitle,
            visibleTexts = visibleTexts.toList(),
            clickableElements = clickableElements.toList(),
            inputFields = inputFields.toList(),
            scrollableRegions = scrollableRegions.toList(),
            sensitiveFlags = sensitiveFlags.distinct().take(MAX_FLAG_ITEMS)
        )

        private fun maybeFlagSensitive(value: String) {
            val lower = value.lowercase()
            sensitiveKeywords.firstOrNull { keyword -> lower.contains(keyword) }?.let { keyword ->
                addLimited(sensitiveFlags, keyword, MAX_FLAG_ITEMS)
            }
        }

        private fun <T> addLimited(target: MutableList<T>, item: T, limit: Int) {
            if (target.size < limit) target.add(item)
        }

        private fun boundsOf(node: AccessibilityNodeInfo): String {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            return "${rect.left},${rect.top},${rect.right},${rect.bottom}"
        }
    }
}
