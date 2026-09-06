package com.galaxyssi.chat

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

class AgentResultReceiptCoordinatorTest {
    @Test fun offlineEventsDoNotDrainAndReconnectDoes() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var calls = 0
            val coordinator = AgentResultReceiptCoordinator(scope, drain = { calls++; null })
            repeat(1000) { coordinator.request(false) }
            assertEquals(0, calls)
            coordinator.connectionChanged(true)
            assertEquals(1, calls)
            repeat(1000) { coordinator.connectionChanged(true) }
            assertEquals(1, calls)
        } finally { scope.cancel() }
    }

    @Test fun dueRetryRunsWithoutANewLifecycleEvent(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var calls = 0
            val retried = CompletableDeferred<Unit>()
            val coordinator = AgentResultReceiptCoordinator(scope, drain = {
                if (++calls == 1) System.currentTimeMillis() + 25 else { retried.complete(Unit); null }
            })
            coordinator.connectionChanged(true)
            withTimeout(2000) { retried.await() }
            delay(60)
            assertEquals(2, calls)
        } finally { scope.cancel() }
    }

    @Test fun eventsDuringDrainAreCoalescedAndNeverConcurrent(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val release = CompletableDeferred<Unit>()
            var calls = 0
            var active = 0
            var maximum = 0
            val coordinator = AgentResultReceiptCoordinator(scope, drain = {
                active++; maximum = maxOf(maximum, active)
                if (++calls == 1) release.await()
                active--; null
            })
            coordinator.connectionChanged(true)
            repeat(1000) { coordinator.request(true) }
            assertEquals(1, calls)
            release.complete(Unit)
            assertEquals(2, calls)
            assertEquals(1, maximum)
        } finally { scope.cancel() }
    }

    @Test fun disconnectPreventsDueRetryUntilReconnect(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var calls = 0
            val coordinator = AgentResultReceiptCoordinator(scope, drain = { calls++; System.currentTimeMillis() + 40 })
            coordinator.connectionChanged(true)
            coordinator.connectionChanged(false)
            delay(90)
            assertEquals(1, calls)
            coordinator.connectionChanged(true)
            assertEquals(2, calls)
        } finally { scope.cancel() }
    }

    @Test fun failedDrainDoesNotSpinAndManualEventCanRecover() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var calls = 0
            var failures = 0
            val coordinator = AgentResultReceiptCoordinator(scope,
                drain = { if (++calls == 1) error("disk unavailable"); null }, failed = { failures++ })
            coordinator.connectionChanged(true)
            assertEquals(1, calls)
            assertEquals(1, failures)
            coordinator.request(true)
            assertEquals(2, calls)
        } finally { scope.cancel() }
    }
}
