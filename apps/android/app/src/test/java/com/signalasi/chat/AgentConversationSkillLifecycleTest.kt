package com.signalasi.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationSkillLifecycleTest {
    @Test
    fun onlyExplicitCommandsRequestSkillCompilation() {
        assertFalse(AgentSkillCommandParser.isSaveCommand("Search today's news"))
        assertFalse(AgentSkillCommandParser.isSaveCommand("Remember this preference"))
        assertTrue(AgentSkillCommandParser.isSaveCommand("Save this as a Skill"))
        assertTrue(AgentSkillCommandParser.isSaveCommand("\u4fdd\u5b58\u6210 Skill"))
        assertTrue(AgentSkillCommandParser.isUpgradeCommand("Upgrade this Skill"))
    }

    @Test
    fun matcherRequiresConfidenceAndHonorsNegativeExamples() {
        val runtime = AgentSkillRuntime(availableNativeToolIds = setOf("web.search"))
        val installed = runtime.install(
            AgentSkillManifest(
                id = "daily-news",
                version = "1.0.0",
                title = "Daily news",
                description = "Find current news",
                instructions = "Search current public news.",
                nativeTools = setOf("web.search"),
                parameters = AgentSkillParameterSchema.objectSchema(
                    properties = mapOf("request" to AgentSkillParameterSchema.string()),
                    required = setOf("request")
                ),
                steps = listOf(AgentSkillStep("search", "web.search", mapOf("query" to "{{parameters.request}}"))),
                autoInvoke = true,
                triggerExamples = listOf("Find today's technology news"),
                negativeExamples = listOf("Open my saved news file")
            )
        )
        val matcher = AgentSkillMatcher(runtime)

        assertNotNull(matcher.match("Find today's technology news"))
        assertNull(matcher.match("Open my saved news file"))
        assertNull(matcher.match("Turn on the flashlight"))
        assertNotNull(matcher.match("@${installed.id} find AI news"))
        assertNotNull(matcher.match("@Daily news find security news"))
    }
}
