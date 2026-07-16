package com.signalasi.chat

import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale

object AgentFastLocalResponse {
    private val binaryExpression = Regex("(-?\\d+(?:\\.\\d+)?)\\s*([+\\-xX*\\u00d7/\\u00f7])\\s*(-?\\d+(?:\\.\\d+)?)")
    private val bareExpression = Regex("^\\s*-?\\d+(?:\\.\\d+)?\\s*[+\\-xX*\\u00d7/\\u00f7]\\s*-?\\d+(?:\\.\\d+)?\\s*[?\\u3002\\uff1f!\\uff01]?\\s*$")
    private val vagueChinese = setOf(
        "\u5e2e\u6211\u5904\u7406\u4e00\u4e0b",
        "\u5e2e\u6211\u5f04\u4e00\u4e0b",
        "\u5904\u7406\u4e00\u4e0b",
        "\u4f60\u770b\u7740\u529e"
    )
    private val vagueEnglish = setOf(
        "help me with this",
        "handle this",
        "deal with this",
        "do something with this"
    )

    fun reply(goal: String, context: AgentConversationContext): String? {
        val clean = goal.trim()
        if (clean.isBlank()) return null
        arithmetic(clean)?.let { return it }
        val priorTurns = if (
            context.turns.lastOrNull()?.role == AgentTranscriptRole.USER &&
            context.turns.lastOrNull()?.text?.trim() == clean
        ) {
            context.turns.dropLast(1)
        } else {
            context.turns
        }
        if (priorTurns.isNotEmpty() || context.summary.isNotBlank()) return null
        val normalized = clean.trimEnd('.', '!', '?', '\u3002', '\uff01', '\uff1f').trim().lowercase(Locale.US)
        return when {
            normalized in vagueChinese ->
                "\u4f60\u60f3\u8ba9\u6211\u5904\u7406\u4ec0\u4e48\uff1f\u53ef\u4ee5\u53d1\u6587\u5b57\u3001\u6587\u4ef6\u6216\u56fe\u7247\uff0c\u6216\u76f4\u63a5\u8bf4\u8981\u6211\u67e5\u770b\u3001\u4fee\u6539\u3001\u603b\u7ed3\u8fd8\u662f\u6267\u884c\u3002"
            normalized in vagueEnglish ->
                "What should I work on? Send text, a file, or an image, or tell me whether to inspect, edit, summarize, or execute it."
            else -> null
        }
    }

    private fun arithmetic(goal: String): String? {
        if (goal.length > 100) return null
        val matches = binaryExpression.findAll(goal).toList()
        if (matches.size != 1) return null
        val lower = goal.lowercase(Locale.US)
        val explicit = bareExpression.matches(goal) || listOf(
            "calculate", "what is", "result", "answer",
            "\u8ba1\u7b97", "\u7b97\u4e00\u4e0b", "\u7ed3\u679c", "\u53ea\u7ed9\u51fa"
        ).any(lower::contains)
        if (!explicit) return null
        val match = matches.single()
        val left = match.groupValues[1].toBigDecimalOrNull() ?: return null
        val right = match.groupValues[3].toBigDecimalOrNull() ?: return null
        val result = when (match.groupValues[2]) {
            "+" -> left.add(right)
            "-" -> left.subtract(right)
            "x", "X", "*", "\u00d7" -> left.multiply(right)
            "/", "\u00f7" -> if (right.compareTo(BigDecimal.ZERO) == 0) return null else left.divide(right, MathContext.DECIMAL64)
            else -> return null
        }
        return result.stripTrailingZeros().toPlainString()
    }
}
