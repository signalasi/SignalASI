package com.signalasi.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlobalProactiveDiscoveryInstrumentedTest {
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
    fun noEventScanQueuesOnceAndPersistsItsDedupeState() {
        val now = 10_000_000L
        val repository = GlobalAgentRepository(context)
        repository.saveSettings(GlobalAgentSettings(
            proactiveDiscoveryEnabled = true,
            modelUnderstandingEnabled = true,
            dailyDiscoveryTaskBudget = 3,
            discoveryIntervalMillis = 60L * 60L * 1_000L
        ))
        repository.saveWorld(PersonalWorldModel(
            items = listOf(GlobalWorldItem(
                stableKey = "risk:runtime",
                kind = GlobalWorldItemKind.RISK,
                layer = GlobalWorldLayer.TOPIC,
                topic = "On-device runtime",
                value = "A platform change may break the native runtime",
                confidence = 0.88,
                evidenceCount = 2,
                conversationIds = setOf("conversation-runtime"),
                evidenceEventIds = listOf("event-a", "event-b")
            )),
            updatedAtMillis = now - 1_000L
        ))
        val coordinator = GlobalProactiveDiscoveryCoordinator(context)

        val first = coordinator.processDue(nowMillis = now)
        val second = coordinator.processDue(nowMillis = now + 1_000L, force = true)

        assertTrue(first.scanned)
        assertEquals(1, first.queuedTaskCount)
        assertTrue(second.scanned)
        assertEquals(0, second.queuedTaskCount)
        assertEquals(1, repository.cognitionTasks().count {
            it.sourceEvent.metadata["origin"] == GlobalProactiveDiscoveryPolicy.ORIGIN
        })
        assertTrue(coordinator.state().nextScanAtMillis > now)
    }
}
