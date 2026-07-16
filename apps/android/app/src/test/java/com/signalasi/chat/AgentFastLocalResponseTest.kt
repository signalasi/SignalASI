package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentFastLocalResponseTest {
    private val emptyContext = AgentConversationContext("test", "", emptyList(), false)

    @Test
    fun answersBoundedBinaryArithmeticLocally() {
        assertEquals("95", AgentFastLocalResponse.reply("\u53ea\u7ed9\u51fa 37 + 58 \u7684\u7ed3\u679c\u3002", emptyContext))
        assertEquals("6", AgentFastLocalResponse.reply("12 / 2", emptyContext))
        assertEquals("-21", AgentFastLocalResponse.reply("Calculate 3 x -7", emptyContext))
        assertNull(AgentFastLocalResponse.reply("Explain why 37 + 58 is useful in this example", emptyContext))
        assertNull(AgentFastLocalResponse.reply("Calculate 1 / 0", emptyContext))
    }

    @Test
    fun asksOneQuestionForObjectlessNewConversationRequest() {
        val goal = "\u5e2e\u6211\u5904\u7406\u4e00\u4e0b\u3002"
        val contextAfterUserAppend = emptyContext.copy(
            turns = listOf(AgentTranscriptEntry("current", AgentTranscriptRole.USER, goal, 1L))
        )
        val response = AgentFastLocalResponse.reply(goal, contextAfterUserAppend)
        assertEquals(
            "\u4f60\u60f3\u8ba9\u6211\u5904\u7406\u4ec0\u4e48\uff1f\u53ef\u4ee5\u53d1\u6587\u5b57\u3001\u6587\u4ef6\u6216\u56fe\u7247\uff0c\u6216\u76f4\u63a5\u8bf4\u8981\u6211\u67e5\u770b\u3001\u4fee\u6539\u3001\u603b\u7ed3\u8fd8\u662f\u6267\u884c\u3002",
            response
        )
    }

    @Test
    fun preservesContextualFollowUpForTheModel() {
        val context = emptyContext.copy(
            turns = listOf(AgentTranscriptEntry("1", AgentTranscriptRole.USER, "Prior task", 1L))
        )
        assertNull(AgentFastLocalResponse.reply("\u5e2e\u6211\u5904\u7406\u4e00\u4e0b\u3002", context))
    }
}
