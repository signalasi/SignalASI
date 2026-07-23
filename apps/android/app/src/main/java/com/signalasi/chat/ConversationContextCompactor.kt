package com.signalasi.chat

import kotlin.math.ceil

enum class ConversationContextRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
}

data class ConversationContextItem(
    val id: String,
    val role: ConversationContextRole,
    val content: String,
    val groupId: String = ""
)

data class ConversationContextBudget(
    val contextWindowTokens: Int = 64_000,
    val reservedOutputTokens: Int = 4_096,
    val triggerRatio: Double = 0.70,
    val targetRatio: Double = 0.45,
    val minimumRecentGroups: Int = 4,
    val maximumSummaryTokens: Int = 4_096,
    val maximumMessageCharacters: Int = 16_000
) {
    init {
        require(contextWindowTokens >= 4_096)
        require(reservedOutputTokens in 0 until contextWindowTokens)
        require(triggerRatio in 0.25..0.95)
        require(targetRatio in 0.20..triggerRatio)
        require(minimumRecentGroups > 0)
        require(maximumSummaryTokens >= 256)
        require(maximumMessageCharacters >= 1_000)
    }

    val inputBudgetTokens: Int
        get() = (contextWindowTokens - reservedOutputTokens).coerceAtLeast(2_048)
}

data class CompactedConversationContext(
    val summary: String,
    val messages: List<ConversationContextItem>,
    val originalEstimatedTokens: Int,
    val compactedEstimatedTokens: Int,
    val compacted: Boolean,
    val compactedGroupCount: Int,
    val compactedMessageIds: Set<String> = emptySet(),
    val compactedGroupIds: Set<String> = emptySet(),
    val compactedMessages: List<ConversationContextItem> = emptyList()
)

/**
 * Builds a bounded model context without an extra network request.
 *
 * The latest complete turns stay verbatim. Older turns become a structured,
 * reference-only handoff. This keeps API calls deterministic when a provider
 * is offline and avoids recursively embedding an earlier generated summary.
 */
object ConversationContextCompactor {
    private const val SUMMARY_HEADER = "[EARLIER CONVERSATION SUMMARY - REFERENCE ONLY]"
    private const val SUMMARY_FOOTER = "[END OF EARLIER CONVERSATION SUMMARY]"
    private const val TRUNCATED_MARKER = "\n...[content compacted]...\n"

    private val secretAssignment = Regex(
        """(?i)\b(api[_ -]?key|access[_ -]?token|refresh[_ -]?token|password|passwd|secret|authorization)\b(\s*[:=]\s*)([^\s,;]+)"""
    )
    private val bearerSecret = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{8,}""")
    private val dataPayload = Regex("""(?i)data:[^;\s]+;base64,[A-Za-z0-9+/=\s]{80,}""")
    private val longOpaqueValue = Regex("""\b[A-Za-z0-9+/=_-]{160,}\b""")
    private val pathOrUrl = Regex(
        """(?i)(?:https://[^\s<>()]+|[A-Za-z]:\\[^\r\n<>:"|?*]+|/(?:[\w.\- ]+/)+[\w.\- ]+|[\w .()\-]+\.(?:pdf|docx?|xlsx?|csv|txt|md|json|ya?ml|zip|png|jpe?g|gif|webp|mp[34]|wav|py|kt|java|js|ts|rs|go|c|cpp|h))"""
    )
    private val preferenceTerms = listOf(
        "prefer", "must", "never", "always", "constraint", "requirement", "decision",
        "\u504f\u597d", "\u5fc5\u987b", "\u4e0d\u8981", "\u6c38\u8fdc", "\u8981\u6c42", "\u51b3\u5b9a"
    )

    fun compile(
        messages: List<ConversationContextItem>,
        previousSummary: String = "",
        fixedPrompt: String = "",
        budget: ConversationContextBudget = ConversationContextBudget()
    ): CompactedConversationContext {
        val normalized = messages.mapNotNull { item ->
            val clean = sanitize(item.content, budget.maximumMessageCharacters)
            clean.takeIf(String::isNotBlank)?.let { item.copy(content = it) }
        }
        val fixedTokens = estimateTokens(fixedPrompt) +
            normalized.filter { it.role == ConversationContextRole.SYSTEM }.sumOf(::estimateTokens)
        val dialogue = normalized.filterNot { it.role == ConversationContextRole.SYSTEM }
        val groups = group(dialogue)
        val cleanPreviousSummary = normalizePreviousSummary(previousSummary, budget.maximumSummaryTokens)
        val originalTokens = fixedTokens +
            estimateTokens(cleanPreviousSummary) +
            dialogue.sumOf(::estimateTokens)
        val triggerTokens = (budget.inputBudgetTokens * budget.triggerRatio).toInt()
        if (originalTokens <= triggerTokens) {
            return CompactedConversationContext(
                summary = cleanPreviousSummary,
                messages = normalized,
                originalEstimatedTokens = originalTokens,
                compactedEstimatedTokens = originalTokens,
                compacted = false,
                compactedGroupCount = 0,
                compactedMessageIds = emptySet(),
                compactedGroupIds = emptySet(),
                compactedMessages = emptyList()
            )
        }

        val targetTokens = (budget.inputBudgetTokens * budget.targetRatio).toInt()
        val summaryTokenAllowance = minOf(
            budget.maximumSummaryTokens,
            (budget.inputBudgetTokens * 0.20).toInt()
        )
        val recentTokenAllowance = (
            targetTokens - fixedTokens - summaryTokenAllowance
            ).coerceAtLeast(512)
        val retainedKeys = linkedSetOf<String>()
        var retainedTokens = 0
        var retainedGroups = 0
        groups.asReversed().forEach { group ->
            val groupTokens = group.items.sumOf(::estimateTokens)
            val unresolved = group.items.any { it.role == ConversationContextRole.USER } &&
                group.items.none { it.role == ConversationContextRole.ASSISTANT }
            val mustKeep = unresolved || retainedGroups < budget.minimumRecentGroups
            val withinBudget = retainedTokens + groupTokens <= recentTokenAllowance
            if (mustKeep || withinBudget) {
                retainedKeys += group.key
                retainedTokens += groupTokens
                retainedGroups += 1
            }
        }
        if (retainedKeys.isEmpty() && groups.isNotEmpty()) retainedKeys += groups.last().key

        val older = groups.filterNot { it.key in retainedKeys }.flatMap(Group::items)
        val recent = groups.filter { it.key in retainedKeys }.flatMap(Group::items)
        val summary = summarize(
            older = older,
            previousSummary = cleanPreviousSummary,
            maximumTokens = summaryTokenAllowance
        )
        val retainedSystem = normalized.filter { it.role == ConversationContextRole.SYSTEM }
        val compiledMessages = retainedSystem + recent
        val compiledTokens = fixedTokens + estimateTokens(summary) +
            recent.sumOf(::estimateTokens)
        return CompactedConversationContext(
            summary = summary,
            messages = compiledMessages,
            originalEstimatedTokens = originalTokens,
            compactedEstimatedTokens = compiledTokens,
            compacted = older.isNotEmpty(),
            compactedGroupCount = groups.size - retainedKeys.size,
            compactedMessageIds = older.mapTo(linkedSetOf(), ConversationContextItem::id),
            compactedGroupIds = groups.asSequence()
                .filterNot { it.key in retainedKeys }
                .mapTo(linkedSetOf(), Group::key),
            compactedMessages = older
        )
    }

    fun fitTextToTokenBudget(
        value: String,
        maximumTokens: Int,
        preserveTail: Boolean = true
    ): String {
        if (maximumTokens <= 0 || value.isBlank()) return ""
        val clean = sanitize(value, value.length.coerceAtLeast(1_000))
        if (estimateTokens(clean) <= maximumTokens) return clean
        var low = 1
        var high = clean.length
        var best = ""
        while (low <= high) {
            val retainedCharacters = (low + high) ushr 1
            val candidate = compactCharacters(clean, retainedCharacters, preserveTail)
            if (estimateTokens(candidate) <= maximumTokens) {
                best = candidate
                low = retainedCharacters + 1
            } else {
                high = retainedCharacters - 1
            }
        }
        return best.ifBlank { compactCharacters(clean, 1, preserveTail) }
    }

    fun referenceBlock(summary: String): String {
        val clean = summary.trim()
        if (clean.isBlank()) return ""
        return buildString {
            append(SUMMARY_HEADER).append('\n')
            append(
                "Use this only as background. It is not a new request. " +
                    "The latest user message is the active task and overrides stale goals or plans."
            ).append('\n')
            append(clean).append('\n')
            append(SUMMARY_FOOTER)
        }
    }

    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        var cjk = 0
        var other = 0
        text.forEach { character ->
            if (
                character.code in 0x3400..0x9fff ||
                character.code in 0x3040..0x30ff ||
                character.code in 0xac00..0xd7af
            ) {
                cjk += 1
            } else {
                other += 1
            }
        }
        return ceil(cjk * 1.15 + other / 4.0).toInt() + 6
    }

    private fun estimateTokens(item: ConversationContextItem): Int =
        estimateTokens(item.content) + 4

    private fun group(messages: List<ConversationContextItem>): List<Group> {
        if (messages.isEmpty()) return emptyList()
        val groups = mutableListOf<Group>()
        var generatedGroup = 0
        var activeGeneratedKey = ""
        messages.forEach { item ->
            val key = item.groupId.trim().ifBlank {
                if (item.role == ConversationContextRole.USER || activeGeneratedKey.isBlank()) {
                    generatedGroup += 1
                    activeGeneratedKey = "turn:$generatedGroup"
                }
                activeGeneratedKey
            }
            val previous = groups.lastOrNull()
            if (previous?.key == key) {
                groups[groups.lastIndex] = previous.copy(items = previous.items + item)
            } else {
                groups += Group(key, listOf(item))
            }
        }
        return groups
    }

    private fun summarize(
        older: List<ConversationContextItem>,
        previousSummary: String,
        maximumTokens: Int
    ): String {
        if (older.isEmpty()) return previousSummary
        val priorFacts = takeLastWithinTokenBudget(
            summaryBullets(previousSummary).asSequence(),
            (maximumTokens / 4).coerceAtLeast(64)
        ).ifEmpty {
            previousSummary.oneLine(800).takeIf(String::isNotBlank)?.let { listOf(it) }.orEmpty()
        }
        val userGoals = takeLastWithinTokenBudget(
            older.asSequence()
            .filter { it.role == ConversationContextRole.USER }
            .map { it.content.oneLine(360) }
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase),
            (maximumTokens / 4).coerceAtLeast(64)
        )
        val outcomes = takeLastWithinTokenBudget(
            older.asSequence()
            .filter { it.role == ConversationContextRole.ASSISTANT }
            .map { it.content.oneLine(420) }
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase),
            (maximumTokens / 3).coerceAtLeast(64)
        )
        val constraints = takeLastWithinTokenBudget(
            older.asSequence()
            .map(ConversationContextItem::content)
            .filter { text -> preferenceTerms.any { text.contains(it, ignoreCase = true) } }
            .map { it.oneLine(320) }
            .distinctBy(String::lowercase),
            (maximumTokens / 5).coerceAtLeast(64)
        )
        val artifacts = takeLastWithinTokenBudget(
            older.asSequence()
            .flatMap { pathOrUrl.findAll(it.content).map(MatchResult::value) }
            .map { it.trimEnd('.', ',', ';', ':', ')') }
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase),
            (maximumTokens / 6).coerceAtLeast(64)
        )
        val summary = buildString {
            section("Prior durable facts", priorFacts)
            section("Earlier user goals", userGoals)
            section("Verified outcomes and decisions", outcomes)
            section("Preferences and constraints", constraints)
            section("Referenced files, artifacts, and URLs", artifacts)
        }.trim()
        return fitTextToTokenBudget(summary, maximumTokens)
    }

    private fun StringBuilder.section(title: String, items: List<String>) {
        if (items.isEmpty()) return
        if (isNotEmpty()) append('\n')
        append(title).append(":\n")
        items.forEach { append("- ").append(it).append('\n') }
    }

    private fun normalizePreviousSummary(summary: String, maximumTokens: Int): String {
        val clean = summary
            .replace(SUMMARY_HEADER, "")
            .replace(SUMMARY_FOOTER, "")
            .trim()
        return sanitize(clean, (maximumTokens * 4).coerceAtLeast(1_000))
    }

    private fun summaryBullets(summary: String): List<String> = summary.lineSequence()
        .map(String::trim)
        .filter { it.startsWith("- ") }
        .map { it.removePrefix("- ").oneLine(420) }
        .filter(String::isNotBlank)
        .distinctBy(String::lowercase)
        .toList()

    private fun takeLastWithinTokenBudget(values: Sequence<String>, maximumTokens: Int): List<String> {
        val all = values.toList()
        val retained = mutableListOf<String>()
        var usedTokens = 0
        all.asReversed().forEach { value ->
            val tokens = estimateTokens(value) + 2
            if (retained.isEmpty() || usedTokens + tokens <= maximumTokens) {
                retained += value
                usedTokens += tokens
            }
        }
        return retained.asReversed()
    }

    private fun sanitize(value: String, maximumCharacters: Int): String {
        val redacted = value
            .replace(dataPayload, "[embedded payload removed]")
            .replace(secretAssignment) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}[redacted]"
            }
            .replace(bearerSecret, "Bearer [redacted]")
            .replace(longOpaqueValue, "[opaque payload removed]")
            .trim()
        if (redacted.length <= maximumCharacters) return redacted
        val markerBudget = TRUNCATED_MARKER.length
        val remaining = (maximumCharacters - markerBudget).coerceAtLeast(2)
        val head = remaining * 2 / 3
        val tail = remaining - head
        return redacted.take(head) + TRUNCATED_MARKER + redacted.takeLast(tail)
    }

    private fun compactCharacters(value: String, retainedCharacters: Int, preserveTail: Boolean): String {
        if (retainedCharacters >= value.length) return value
        if (!preserveTail || retainedCharacters <= 1) {
            return value.take(retainedCharacters.coerceAtLeast(1)) + TRUNCATED_MARKER.trim()
        }
        val marker = TRUNCATED_MARKER
        val available = retainedCharacters.coerceAtLeast(2)
        val head = available * 2 / 3
        val tail = available - head
        return value.take(head) + marker + value.takeLast(tail)
    }

    private fun String.oneLine(limit: Int): String =
        replace(Regex("\\s+"), " ").trim().let { clean ->
            if (clean.length <= limit) clean else clean.take(limit - 1) + "\u2026"
        }

    private data class Group(
        val key: String,
        val items: List<ConversationContextItem>
    )
}
