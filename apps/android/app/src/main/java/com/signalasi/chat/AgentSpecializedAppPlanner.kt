package com.signalasi.chat

import android.content.Intent
import android.net.Uri
import java.util.Locale

data class AgentSpecializedPlan(
    val profile: String,
    val actions: List<AgentAction>
)

object AgentSpecializedAppPlanner {
    private const val WECHAT_PACKAGE = "com.tencent.mm"

    fun plan(request: AgentRequest): AgentSpecializedPlan? {
        val goal = request.goal.trim()
        if (goal.isBlank()) return null
        parseWechatSend(goal)?.let { task ->
            return AgentSpecializedPlan("wechat", listOf(nextWechatAction(request, task)))
        }
        parseWechatReply(goal)?.let { reply ->
            return AgentSpecializedPlan("wechat-notification", listOf(wechatNotificationReply(request, reply)))
        }
        parseSms(goal)?.let { task ->
            return AgentSpecializedPlan("sms", listOf(smsComposerAction(task)))
        }
        parseDial(goal)?.let { number ->
            return AgentSpecializedPlan("phone", listOf(dialAction(number)))
        }
        parseBrowser(goal)?.let { task ->
            return AgentSpecializedPlan("browser", listOf(browserAction(task)))
        }
        parseFile(goal)?.let { task ->
            return AgentSpecializedPlan("files", listOf(fileAction(task)))
        }
        return null
    }

    private fun nextWechatAction(request: AgentRequest, task: WechatTask): AgentAction {
        val screen = request.screen
        val history = request.executionHistory
        if (!screen.isPackage(WECHAT_PACKAGE)) {
            return openPackageAction(
                id = "special-wechat-open",
                target = "WeChat",
                packageName = WECHAT_PACKAGE,
                risk = AgentRisk.MEDIUM,
                description = "Open WeChat for an owner-confirmed messaging task"
            )
        }

        val messageField = resolveFirst(screen.inputFields, MESSAGE_FIELD_QUERIES)
        val contactVisible = screen.pageTitle.contains(task.contact, ignoreCase = true) ||
            screen.visibleTexts.any { visible -> visible.trim().equals(task.contact, ignoreCase = true) }
        if (messageField != null && contactVisible && !history.completed("special-wechat-message")) {
            return textAction(
                id = "special-wechat-message",
                target = task.contact,
                description = "Fill the WeChat message draft for ${task.contact}",
                field = messageField,
                text = task.message,
                risk = AgentRisk.HIGH
            )
        }
        if (history.completed("special-wechat-message")) {
            val send = resolveFirst(screen.clickableElements, SEND_QUERIES)
            return if (send != null) {
                tapAction(
                    id = "special-wechat-send",
                    target = task.contact,
                    description = "Send the prepared WeChat message to ${task.contact}",
                    element = send,
                    risk = AgentRisk.HIGH
                )
            } else {
                blockedStep(
                    "special-wechat-send-missing",
                    "WeChat Send Button",
                    "The final send control is not grounded on the current screen"
                )
            }
        }

        if (history.completed("special-wechat-search-query")) {
            val contact = AgentScreenElementMatcher.resolve(task.contact, screen.clickableElements)
            return if (contact != null) {
                tapAction(
                    id = "special-wechat-contact",
                    target = task.contact,
                    description = "Open the verified WeChat search result for ${task.contact}",
                    element = contact,
                    risk = AgentRisk.HIGH
                )
            } else {
                AgentAction(
                    id = "special-wechat-search-results-scroll",
                    kind = AgentActionKind.SWIPE,
                    target = "WeChat Search Results",
                    risk = AgentRisk.MEDIUM,
                    status = AgentActionStatus.PENDING_CONFIRMATION,
                    description = "Reveal more WeChat search results",
                    parameters = mapOf("from_x" to "540", "from_y" to "1550", "to_x" to "540", "to_y" to "700")
                )
            }
        }

        val searchField = resolveFirst(screen.inputFields, SEARCH_QUERIES)
        if (searchField != null) {
            return textAction(
                id = "special-wechat-search-query",
                target = "WeChat Search",
                description = "Search WeChat for ${task.contact}",
                field = searchField,
                text = task.contact,
                risk = AgentRisk.MEDIUM
            )
        }
        val searchButton = resolveFirst(screen.clickableElements, SEARCH_QUERIES)
        return if (searchButton != null) {
            tapAction(
                id = "special-wechat-search-open",
                target = "WeChat Search",
                description = "Open WeChat contact search",
                element = searchButton,
                risk = AgentRisk.MEDIUM
            )
        } else {
            blockedStep(
                "special-wechat-search-missing",
                "WeChat Search",
                "WeChat search is not grounded on the current screen"
            )
        }
    }

    private fun wechatNotificationReply(request: AgentRequest, reply: String): AgentAction {
        val notification = request.screen.notifications.items
            .filter { it.canReply && it.packageName == WECHAT_PACKAGE }
            .maxByOrNull { it.postedAtMillis }
        return if (notification == null) {
            blockedStep(
                "special-wechat-notification-missing",
                "WeChat Notification",
                "No reply-capable WeChat notification is available"
            )
        } else {
            AgentAction(
                id = "special-wechat-notification-reply",
                kind = AgentActionKind.REPLY_NOTIFICATION,
                target = notification.title.ifBlank { "WeChat" },
                risk = AgentRisk.HIGH,
                status = AgentActionStatus.PENDING_CONFIRMATION,
                description = "Reply to the latest verified WeChat notification",
                parameters = mapOf(
                    "notification_key" to notification.key,
                    "notification_package" to notification.packageName,
                    "reply_text" to reply
                ),
                requiresConfirmation = true
            )
        }
    }

    private fun smsComposerAction(task: SmsTask): AgentAction = AgentAction(
        id = "special-sms-compose",
        kind = AgentActionKind.OPEN_APP,
        target = "SMS Composer",
        risk = AgentRisk.HIGH,
        status = AgentActionStatus.PENDING_CONFIRMATION,
        description = "Open the SMS composer with the recipient and draft filled",
        parameters = mapOf(
            "intent_action" to Intent.ACTION_SENDTO,
            "uri" to "smsto:${Uri.encode(task.recipient)}",
            "sms_body" to task.message
        ),
        requiresConfirmation = true
    )

    private fun dialAction(number: String): AgentAction = AgentAction(
        id = "special-phone-dial",
        kind = AgentActionKind.OPEN_APP,
        target = "Phone Dialer",
        risk = AgentRisk.HIGH,
        status = AgentActionStatus.PENDING_CONFIRMATION,
        description = "Open the phone dialer with the number filled; the owner places the call",
        parameters = mapOf(
            "intent_action" to Intent.ACTION_DIAL,
            "uri" to "tel:${Uri.encode(number)}"
        ),
        requiresConfirmation = true
    )

    private fun browserAction(task: BrowserTask): AgentAction {
        val url = if (task.search) {
            "https://www.google.com/search?q=${Uri.encode(task.value)}"
        } else {
            task.value.safeHttpUrl() ?: return blockedStep(
                "special-browser-url-invalid",
                "Browser",
                "The requested address is not a valid HTTP or HTTPS URL"
            )
        }
        return AgentAction(
            id = if (task.search) "special-browser-search" else "special-browser-open",
            kind = AgentActionKind.OPEN_URL,
            target = if (task.search) "Web Search" else url,
            risk = AgentRisk.MEDIUM,
            status = AgentActionStatus.PENDING_CONFIRMATION,
            description = if (task.search) "Search the web in the default browser" else "Open the verified web address",
            parameters = mapOf("url" to url)
        )
    }

    private fun fileAction(task: FileTask): AgentAction = AgentAction(
        id = "special-file-picker",
        kind = AgentActionKind.OPEN_APP,
        target = task.title,
        risk = AgentRisk.LOW,
        status = AgentActionStatus.PENDING_CONFIRMATION,
        description = task.description,
        parameters = mapOf(
            "intent_action" to Intent.ACTION_OPEN_DOCUMENT,
            "type" to task.mimeType,
            "category" to Intent.CATEGORY_OPENABLE,
            "file_operation" to task.operation
        )
    )

    private fun textAction(
        id: String,
        target: String,
        description: String,
        field: ScreenElement,
        text: String,
        risk: AgentRisk
    ): AgentAction = AgentAction(
        id = id,
        kind = AgentActionKind.TYPE_TEXT,
        target = target,
        risk = risk,
        status = AgentActionStatus.PENDING_CONFIRMATION,
        description = description,
        parameters = mapOf(
            "text" to text,
            "field_bounds" to field.bounds,
            "matched_label" to field.label,
            "field_origin" to field.origin.name,
            "field_confidence" to field.confidence.toString()
        ),
        requiresConfirmation = true
    )

    private fun tapAction(
        id: String,
        target: String,
        description: String,
        element: ScreenElement,
        risk: AgentRisk
    ): AgentAction = AgentAction(
        id = id,
        kind = AgentActionKind.TAP,
        target = target,
        risk = risk,
        status = AgentActionStatus.PENDING_CONFIRMATION,
        description = description,
        parameters = mapOf(
            "bounds" to element.bounds,
            "matched_label" to element.label,
            "element_origin" to element.origin.name,
            "element_role" to element.visualRole.name,
            "element_confidence" to element.confidence.toString()
        ),
        requiresConfirmation = true
    )

    private fun openPackageAction(
        id: String,
        target: String,
        packageName: String,
        risk: AgentRisk,
        description: String
    ): AgentAction = AgentAction(
        id = id,
        kind = AgentActionKind.OPEN_APP,
        target = target,
        risk = risk,
        status = AgentActionStatus.PENDING_CONFIRMATION,
        description = description,
        parameters = mapOf("package" to packageName)
    )

    private fun blockedStep(id: String, target: String, reason: String): AgentAction = AgentAction(
        id = id,
        kind = AgentActionKind.DRAFT_PLAN,
        target = target,
        risk = AgentRisk.BLOCKED,
        status = AgentActionStatus.PENDING_CONFIRMATION,
        description = reason,
        parameters = mapOf("blocked_reason" to reason),
        requiresConfirmation = true
    )

    private fun resolveFirst(elements: List<ScreenElement>, queries: List<String>): ScreenElement? =
        queries.firstNotNullOfOrNull { query -> AgentScreenElementMatcher.resolve(query, elements) }

    private fun ScreenContext.isPackage(packageName: String): Boolean =
        foregroundApp == packageName || activityName.startsWith(packageName)

    private fun List<AgentAction>.completed(idToken: String): Boolean = any { action ->
        action.status == AgentActionStatus.COMPLETED && action.id.contains(idToken)
    }

    private fun parseWechatSend(goal: String): WechatTask? {
        val patterns = listOf(
            Regex("^(?:send|message|reply)\\s+wechat\\s+(?:to\\s+)?(.+?)\\s*::\\s*(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:send|reply)\\s+wechat\\s+(?:to\\s+)?(.+?)\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE),
            Regex("^\\u5fae\\u4fe1\\u53d1\\u7ed9(.+?)[::\\uff1a]+(.+)$"),
            Regex("^\\u7ed9(.+?)\\u53d1\\u5fae\\u4fe1[::\\uff1a]+(.+)$")
        )
        val match = patterns.firstNotNullOfOrNull { it.find(goal.trim()) } ?: return null
        val contact = match.groupValues.getOrNull(1)?.trim().orEmpty().take(120)
        val message = match.groupValues.getOrNull(2)?.trim().orEmpty().take(2_000)
        return WechatTask(contact, message).takeIf { contact.isNotBlank() && message.isNotBlank() }
    }

    private fun parseWechatReply(goal: String): String? {
        val prefixes = listOf("reply wechat ", "reply to wechat ", "\u56de\u590d\u5fae\u4fe1")
        val prefix = prefixes.firstOrNull { goal.startsWith(it, ignoreCase = true) } ?: return null
        return goal.drop(prefix.length).trim().removePrefix("::").trim().take(2_000).takeIf { it.isNotBlank() }
    }

    private fun parseSms(goal: String): SmsTask? {
        val patterns = listOf(
            Regex("^(?:send\\s+sms|text)\\s+(?:to\\s+)?([^:]+?)\\s*::\\s*(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:send\\s+sms|text)\\s+(?:to\\s+)?([^:]+?)\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE),
            Regex("^\\u53d1\\u77ed\\u4fe1\\u7ed9(.+?)[::\\uff1a]+(.+)$")
        )
        val match = patterns.firstNotNullOfOrNull { it.find(goal.trim()) } ?: return null
        val recipient = match.groupValues.getOrNull(1)?.trim().orEmpty().take(120)
        val message = match.groupValues.getOrNull(2)?.trim().orEmpty().take(2_000)
        return SmsTask(recipient, message).takeIf { recipient.isNotBlank() && message.isNotBlank() }
    }

    private fun parseDial(goal: String): String? {
        val prefixes = listOf("dial ", "call ", "\u62e8\u6253", "\u6253\u7535\u8bdd\u7ed9")
        val prefix = prefixes.firstOrNull { goal.startsWith(it, ignoreCase = true) } ?: return null
        val value = goal.drop(prefix.length).trim().take(120)
        val normalized = value.replace(Regex("[\\s()-]"), "")
        return value.takeIf {
            normalized.matches(Regex("[+*#0-9]{3,32}")) && normalized.count(Char::isDigit) >= 3
        }
    }

    private fun parseBrowser(goal: String): BrowserTask? {
        val searchPrefixes = listOf("search web ", "google ", "web search ", "\u7f51\u9875\u641c\u7d22", "\u641c\u7d22\u7f51\u9875")
        searchPrefixes.firstOrNull { goal.startsWith(it, ignoreCase = true) }?.let { prefix ->
            return goal.drop(prefix.length).trim().takeIf { it.isNotBlank() }?.let { BrowserTask(it, true) }
        }
        val openPrefixes = listOf("open url ", "open website ", "browse ", "\u6253\u5f00\u7f51\u5740")
        openPrefixes.firstOrNull { goal.startsWith(it, ignoreCase = true) }?.let { prefix ->
            return goal.drop(prefix.length).trim().takeIf { it.isNotBlank() }?.let { BrowserTask(it, false) }
        }
        return null
    }

    private fun parseFile(goal: String): FileTask? {
        val lower = goal.lowercase(Locale.US)
        return when {
            lower.contains("pick image") || lower.contains("select image") || lower.contains("choose photo") ||
                goal.contains("\u9009\u62e9\u56fe\u7247") ->
                FileTask("Image Picker", "Select an image using Android document access", "image/*", "pick_image")
            lower.contains("pick pdf") || lower.contains("select pdf") || goal.contains("\u9009\u62e9pdf", ignoreCase = true) ->
                FileTask("PDF Picker", "Select a PDF using Android document access", "application/pdf", "pick_pdf")
            lower.contains("pick file") || lower.contains("select file") || lower.contains("open files") ||
                lower.contains("open file manager") || goal.contains("\u9009\u62e9\u6587\u4ef6") ->
                FileTask("File Picker", "Open Android document access", "*/*", "pick_file")
            else -> null
        }
    }

    private fun String.safeHttpUrl(): String? {
        val normalized = if (startsWith("http://", true) || startsWith("https://", true)) this else "https://$this"
        val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return null
        return normalized.takeIf { uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() }
    }

    private data class WechatTask(val contact: String, val message: String)
    private data class SmsTask(val recipient: String, val message: String)
    private data class BrowserTask(val value: String, val search: Boolean)
    private data class FileTask(
        val title: String,
        val description: String,
        val mimeType: String,
        val operation: String
    )

    private val SEARCH_QUERIES = listOf("search", "\u641c\u7d22")
    private val MESSAGE_FIELD_QUERIES = listOf("message", "input", "\u8f93\u5165", "\u53d1\u6d88\u606f")
    private val SEND_QUERIES = listOf("send", "\u53d1\u9001")
}
