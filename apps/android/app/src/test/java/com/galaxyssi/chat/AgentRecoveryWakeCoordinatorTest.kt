package com.galaxyssi.chat

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AgentRecoveryWakeCoordinatorTest {
    @Test fun recoveryWaitsForSubscriptionAcknowledgementAndRetainsWakeAcrossRotation() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val subscriptions = MqttSubscriptionRecoveryState()
            var calls = 0
            val wake = AgentRecoveryWakeCoordinator(scope, recover = { calls++ })
            val generation = subscriptions.begin(2)
            repeat(20) { wake.request(isConnected = subscriptions.isReady()) }
            subscriptions.complete(generation, true)
            wake.connectionChanged(subscriptions.isReady())
            assertEquals(0, calls)
            subscriptions.complete(generation, true)
            wake.connectionChanged(subscriptions.isReady())
            assertEquals(1, calls)
            val rotated = subscriptions.begin(1)
            wake.connectionChanged(subscriptions.isReady())
            wake.request(isConnected = subscriptions.isReady())
            subscriptions.complete(generation, true)
            wake.connectionChanged(subscriptions.isReady())
            assertEquals(1, calls)
            subscriptions.complete(rotated, true)
            wake.connectionChanged(subscriptions.isReady())
            assertEquals(2, calls)
        } finally { scope.cancel() }
    }

    @Test fun foregroundRequestCombinesConnectionAndWakeAtomically() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var calls = 0
            val wake = AgentRecoveryWakeCoordinator(scope, recover = { calls++ })
            wake.request(isConnected = true)
            assertEquals(1, calls)
            assertFalse(wake.isRunning)
        } finally { scope.cancel() }
    }

    @Test fun offlineEventsDoNotPublishAndCollapseIntoOneReconnectPass() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var calls = 0
            val wake = AgentRecoveryWakeCoordinator(scope, recover = { calls++ })
            repeat(1000) { wake.request() }
            assertEquals(0, calls)
            assertTrue(wake.hasPendingWake)
            wake.connectionChanged(true)
            assertEquals(1, calls)
            assertFalse(wake.isRunning)
            assertFalse(wake.hasPendingWake)
        } finally { scope.cancel() }
    }

    @Test fun eventsDuringObservationProduceOneFollowUpNotConcurrentWork(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val release = CompletableDeferred<Unit>()
            var calls = 0
            var active = 0
            var maximum = 0
            val wake = AgentRecoveryWakeCoordinator(scope, recover = {
                active++; maximum = maxOf(maximum, active)
                if (++calls == 1) release.await()
                active--
            })
            wake.connectionChanged(true)
            repeat(10000) { wake.request() }
            assertEquals(1, calls)
            release.complete(Unit)
            assertEquals(2, calls)
            assertEquals(1, maximum)
            assertFalse(wake.isRunning)
        } finally { scope.cancel() }
    }

    @Test fun disconnectDuringObservationPreservesWakeUntilConnectionReturns(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val wake = AgentRecoveryWakeCoordinator(scope, recover = { if (++calls == 1) release.await() })
            wake.connectionChanged(true)
            wake.connectionChanged(false)
            wake.request()
            release.complete(Unit)
            assertEquals(1, calls)
            assertFalse(wake.isRunning)
            assertTrue(wake.hasPendingWake)
            wake.connectionChanged(true)
            assertEquals(2, calls)
        } finally { scope.cancel() }
    }

    @Test fun unchangedConnectionStateDoesNotActAsPeriodicHeartbeat() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var calls = 0
            val wake = AgentRecoveryWakeCoordinator(scope, recover = { calls++ })
            repeat(1000) { wake.connectionChanged(true) }
            assertEquals(1, calls)
            wake.request()
            assertEquals(2, calls)
        } finally { scope.cancel() }
    }

    @Test fun failedObservationDoesNotCreateHotRetryLoop() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var failures = 0
            var calls = 0
            val wake = AgentRecoveryWakeCoordinator(scope, recover = { calls++; error("unavailable") },
                failed = { failures++ })
            wake.connectionChanged(true)
            assertEquals(1, calls)
            assertEquals(1, failures)
            assertFalse(wake.isRunning)
            wake.request()
            assertEquals(2, calls)
        } finally { scope.cancel() }
    }

    @Test fun eventArrivingBeforeFailedObservationFinishesIsNotLost(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val wake = AgentRecoveryWakeCoordinator(scope, recover = {
                if (++calls == 1) { release.await(); error("first attempt unavailable") }
            })
            wake.connectionChanged(true)
            wake.request()
            release.complete(Unit)
            assertEquals(2, calls)
            assertFalse(wake.isRunning)
        } finally { scope.cancel() }
    }

    @Test fun cancelledScopeDoesNotLeavePhantomWorker() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scope.cancel()
        val wake = AgentRecoveryWakeCoordinator(scope, recover = { error("Must not run") })
        wake.connectionChanged(true)
        wake.request()
        assertFalse(wake.isRunning)
    }

    @Test fun cancellingScopeDuringWaitReleasesWorker(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val wake = AgentRecoveryWakeCoordinator(scope, recover = { CompletableDeferred<Unit>().await() })
        wake.connectionChanged(true)
        assertTrue(wake.isRunning)
        scope.cancel()
        assertFalse(wake.isRunning)
    }

    @Test fun concurrentWakeStormKeepsOneActiveWorker(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            val calls = AtomicInteger()
            val wake = AgentRecoveryWakeCoordinator(scope, recover = {
                val value = active.incrementAndGet()
                maximum.updateAndGet { maxOf(it, value) }
                if (calls.incrementAndGet() == 1) { started.complete(Unit); release.await() }
                active.decrementAndGet()
            })
            wake.connectionChanged(true)
            withTimeout(10000) { started.await() }
            val threads = List(8) { Thread { repeat(500) { wake.request() } }.apply { start() } }
            threads.forEach { it.join() }
            release.complete(Unit)
            withTimeout(10000) { while (wake.isRunning) delay(1) }
            assertEquals(2, calls.get())
            assertEquals(1, maximum.get())
        } finally { scope.cancel() }
    }

    @Test fun observationCanQueueItsOwnFollowUpWithoutLosingIt() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var calls = 0
            lateinit var wake: AgentRecoveryWakeCoordinator
            wake = AgentRecoveryWakeCoordinator(scope, recover = { if (++calls == 1) wake.request() })
            wake.connectionChanged(true)
            assertEquals(2, calls)
        } finally { scope.cancel() }
    }
}
