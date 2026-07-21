package com.signalasi.chat

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProductionAdaptersTest {
    @Test
    fun `factory creates dedicated production adapter for every supported family`() {
        val cases = listOf(
            registration("hermes", "hermes-cli") to HermesAgentAdapter::class.java,
            registration("codex", "codex-app-server-or-cli") to CodexAgentAdapter::class.java,
            registration("claude", "claude-code-cli") to ClaudeCodeAgentAdapter::class.java,
            registration("openclaw", "openclaw-cli") to OpenClawAgentAdapter::class.java,
            registration("cloud", "cloud-model-api", AgentConnectorKind.MODEL, AgentResourceLocation.CLOUD) to
                CloudModelAgentAdapter::class.java,
            registration("local", "local-model-api", AgentConnectorKind.MODEL, AgentResourceLocation.PHONE) to
                LocalModelAgentAdapter::class.java,
            registration("windows", "windows-host-tools", AgentConnectorKind.DEVICE) to
                WindowsHostAgentAdapter::class.java,
            registration("android", "android-device-tools", AgentConnectorKind.DEVICE, AgentResourceLocation.PHONE) to
                AndroidDeviceAgentAdapter::class.java,
            registration("home", "home-assistant-api", AgentConnectorKind.DEVICE, AgentResourceLocation.PRIVATE_NETWORK) to
                HomeAssistantAgentAdapter::class.java,
            registration("custom", "custom-agent") to CustomAgentAdapter::class.java
        )

        cases.forEach { (registration, expectedClass) ->
            val adapter = AgentProductionAdapterFactory.create(
                registration,
                SuccessfulTransport(registration),
                registration.protocol,
                InMemoryAgentRunStartReceiptStore(),
                InMemoryAgentProviderHealthLedger()
            )
            assertEquals(expectedClass, adapter.javaClass)
            assertEquals(registration.adapterFamily(), adapter.family)
            assertTrue(registration.independentlyUpgradeable)
        }
    }

    @Test
    fun `runtime crash isolates one adapter without poisoning peers or another installation`() = runBlocking {
        val ledger = InMemoryAgentProviderHealthLedger()
        val codex = registration(
            id = "desktop-a:codex",
            adapterType = "codex-app-server-or-cli",
            installation = "desktop-a",
            runtimeDomain = "desktop-a:codex"
        )
        val hermes = registration(
            id = "desktop-a:hermes",
            adapterType = "hermes-cli",
            installation = "desktop-a",
            runtimeDomain = "desktop-a:hermes"
        )
        val secondCodex = registration(
            id = "desktop-b:codex",
            adapterType = "codex-app-server-or-cli",
            installation = "desktop-b",
            runtimeDomain = "desktop-b:codex"
        )
        val broken = AgentProductionAdapterFactory.create(
            codex,
            FailingTransport(codex, IOException("Codex process crashed")),
            codex.protocol,
            InMemoryAgentRunStartReceiptStore(),
            ledger
        )

        runCatching { broken.connect() }
        assertEquals(AgentProviderCircuitState.OPEN, ledger.snapshot(codex).circuitState(System.currentTimeMillis()))
        assertFalse(ledger.acquire(codex, "start_run", System.currentTimeMillis()).allowed)
        assertTrue(ledger.acquire(hermes, "start_run", System.currentTimeMillis()).allowed)
        assertTrue(ledger.acquire(secondCodex, "start_run", System.currentTimeMillis()).allowed)
    }

    @Test
    fun `half open circuit permits one probe and success closes only that runtime`() {
        val ledger = InMemoryAgentProviderHealthLedger()
        val registration = registration("codex", "codex-app-server-or-cli")
        ledger.recordFailure(registration, "start_run", AgentProviderFailureKind.EXECUTION, 10L, 1_000L)
        ledger.recordFailure(registration, "start_run", AgentProviderFailureKind.EXECUTION, 10L, 2_000L)
        ledger.recordFailure(registration, "start_run", AgentProviderFailureKind.EXECUTION, 10L, 3_000L)

        val open = ledger.snapshot(registration)
        assertEquals(AgentProviderCircuitState.OPEN, open.circuitState(3_001L))
        assertFalse(ledger.acquire(registration, "start_run", 3_001L).allowed)

        val probeAt = open.circuitOpenUntilMillis + 1L
        val probe = ledger.acquire(registration, "start_run", probeAt)
        assertTrue(probe.allowed)
        assertTrue(probe.probe)
        assertFalse(ledger.acquire(registration, "start_run", probeAt + 1L).allowed)

        ledger.recordSuccess(registration, "start_run", 20L, probeAt + 2L)
        val recovered = ledger.snapshot(registration)
        assertEquals(0, recovered.consecutiveFailures)
        assertEquals(AgentProviderCircuitState.CLOSED, recovered.circuitState(probeAt + 3L))
        assertTrue(ledger.acquire(registration, "start_run", probeAt + 3L).allowed)
    }

    @Test
    fun `unrelated status success does not erase repeated execution failures`() {
        val ledger = InMemoryAgentProviderHealthLedger()
        val registration = registration("codex", "codex-app-server-or-cli")
        ledger.recordFailure(registration, "start_run", AgentProviderFailureKind.EXECUTION, 10L, 1_000L)
        ledger.recordFailure(registration, "start_run", AgentProviderFailureKind.EXECUTION, 10L, 2_000L)

        ledger.recordSuccess(registration, "status", 2L, 2_100L)

        assertEquals(2, ledger.snapshot(registration).consecutiveFailures)
    }

    @Test
    fun `provider enumerates descriptors and resolves named adapters from production directory`() = runBlocking {
        val registrations = listOf(
            registration("desktop:codex", "codex-app-server-or-cli"),
            registration("desktop:openclaw", "openclaw-cli")
        )
        val provider = ActionExecutorAgentProvider(
            registrationSource = { registrations },
            delegate = object : AgentActionExecutor {
                override fun execute(action: AgentAction, screen: ScreenContext): AgentActionResult =
                    AgentActionResult(action.id, true, "accepted")
            }
        )
        val directory = AgentAdapterDirectory().apply { register(provider) }

        assertEquals(registrations.map { it.agentId }.toSet(), directory.registrations().map { it.agentId }.toSet())
        assertTrue(directory.resolveAdapter("desktop:codex") is CodexAgentAdapter)
        assertTrue(directory.resolveAdapter("desktop:openclaw") is OpenClawAgentAdapter)
    }

    private fun registration(
        id: String,
        adapterType: String,
        kind: AgentConnectorKind = AgentConnectorKind.AGENT,
        location: AgentResourceLocation = AgentResourceLocation.TRUSTED_DESKTOP,
        installation: String = "desktop-a",
        runtimeDomain: String = "$installation:$id"
    ): AgentRegistration = AgentRegistration(
        agentId = id,
        installationId = installation,
        deviceId = installation,
        providerId = installation,
        displayName = id,
        kind = kind,
        location = location,
        status = AgentEndpointStatus.ONLINE,
        capabilities = setOf(AgentCapability.CHAT),
        protocol = AgentProtocolRange(
            preferred = "1.0",
            minimum = "1.0",
            maximum = "1.0",
            features = setOf("run.cancel", "run.recover", "run.events", "message.respond", "message.observe")
        ),
        connectionKind = AgentConnectionKind.SIGNALASI_LINK,
        failureDomain = installation,
        runtimeFailureDomain = runtimeDomain,
        adapterType = adapterType,
        independentlyUpgradeable = true
    )

    private open class SuccessfulTransport(private val registration: AgentRegistration) : AgentAdapterTransport {
        override suspend fun open(): AgentProtocolRange = registration.protocol
        override suspend fun close() = Unit
        override suspend fun status(): AgentRegistration = registration
        override suspend fun startRun(request: AgentRunRequest): AgentRunHandle = AgentRunHandle(
            runId = request.runId,
            taskId = request.taskId,
            agentId = registration.agentId
        )
        override suspend fun sendMessage(runId: String, message: AgentControlMessage) = Unit
        override suspend fun cancelRun(runId: String) = Unit
        override fun observeEvents(runId: String): Flow<AgentRunControlEvent> = emptyFlow()
        override suspend fun recoverRuns(): List<AgentRecoverableRun> = emptyList()
    }

    private class FailingTransport(
        registration: AgentRegistration,
        private val failure: Throwable
    ) : SuccessfulTransport(registration) {
        override suspend fun open(): AgentProtocolRange = throw failure
    }
}
