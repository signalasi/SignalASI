package com.signalasi.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlobalProactiveInboxInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetBefore() {
        GlobalAgentRepository(context).clear()
    }

    @After
    fun resetAfter() {
        GlobalAgentRepository(context).clear()
    }

    @Test
    fun viewedReceiptSurvivesRepositoryRecreation() {
        val repository = GlobalAgentRepository(context)
        repository.saveProactiveMessages(listOf(GlobalProactiveMessage(
            id = "finding",
            sourceEventId = "event",
            sourceConversationId = "source",
            target = GlobalProactiveTarget.NEW_CONVERSATION,
            title = "Signal insight",
            content = "A durable result is ready.",
            topic = "Long-running project",
            urgent = false,
            status = GlobalProactiveMessageStatus.DELIVERED,
            createdAtMillis = 1_000L,
            deliveredAtMillis = 2_000L,
            deliveredConversationId = "destination",
            deliveryGroupId = "finding"
        )))

        assertEquals(1, repository.markProactiveViewed(setOf("finding"), 3_000L))

        val restored = GlobalAgentRepository(context).proactiveMessages().single()
        val inbox = GlobalProactiveInboxPolicy.project(listOf(restored), emptyList()).single()
        assertEquals(3_000L, restored.viewedAtMillis)
        assertFalse(inbox.isNew)
    }
}
