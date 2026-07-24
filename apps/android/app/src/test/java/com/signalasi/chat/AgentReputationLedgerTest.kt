package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentReputationLedgerTest {
    private val now = 10_000_000L
    private val executor = TestIdentity("executor-host", "executor-secret")
    private val verifier = TestIdentity("verifier-host", "verifier-secret")
    private val identities = TestIdentityVerifier(executor, verifier)

    @Test
    fun acceptsAuthenticReceiptAndRejectsTampering() {
        val ledger = ledger()
        val receipt = receipt("run-1", AgentReputationOutcome.SUCCEEDED)

        assertTrue(ledger.record(receipt).accepted)
        assertFalse(ledger.record(receipt.copy(outcome = AgentReputationOutcome.FAILED)).accepted)
        assertEquals("signature_invalid", ledger.record(receipt.copy(agentId = "other")).reason)
        assertEquals(1, ledger.receipts().size)
    }

    @Test
    fun duplicateReceiptIsIdempotentAndIdCollisionIsRejected() {
        val ledger = ledger()
        val receipt = receipt("run-1", AgentReputationOutcome.SUCCEEDED)

        assertTrue(ledger.record(receipt).accepted)
        val duplicate = ledger.record(receipt)
        assertTrue(duplicate.accepted)
        assertTrue(duplicate.duplicate)

        val conflictingUnsigned = receipt.copy(
            outcome = AgentReputationOutcome.FAILED,
            signature = ""
        ).signedBy(executor).copy(receiptId = receipt.receiptId)
        val collision = ledger.record(conflictingUnsigned)
        assertFalse(collision.accepted)
        assertEquals("receipt_id_collision", collision.reason)
        assertEquals(1, ledger.receipts().size)
    }

    @Test
    fun independentPassRaisesConfidenceAndVerifiedCount() {
        val ledger = ledger()
        val receipt = receipt("run-1", AgentReputationOutcome.SUCCEEDED)
        ledger.record(receipt)
        val before = ledger.snapshot(receipt.agentId, nowMillis = now + 1_000L)

        val attestation = attestation(receipt, AgentReputationVerificationVerdict.PASSED)
        assertTrue(ledger.record(attestation).accepted)
        val after = ledger.snapshot(receipt.agentId, nowMillis = now + 1_000L)

        assertTrue(after.confidence > before.confidence)
        assertTrue(after.score >= before.score)
        assertEquals(1, after.independentlyVerifiedRuns)
        assertEquals(1, after.independentFailureDomains)
    }

    @Test
    fun verifierMustBeIndependentFromExecutorIdentityAndFailureDomain() {
        val ledger = ledger()
        val receipt = receipt("run-1", AgentReputationOutcome.SUCCEEDED)
        ledger.record(receipt)

        val sameAgent = attestation(receipt, AgentReputationVerificationVerdict.PASSED)
            .copy(verifierAgentId = receipt.agentId)
            .signedBy(verifier)
        assertEquals("independence_boundary_invalid", ledger.record(sameAgent).reason)

        val sameInstallation = attestation(receipt, AgentReputationVerificationVerdict.PASSED)
            .copy(verifierInstallationId = receipt.installationId)
            .signedBy(verifier)
        assertEquals("independence_boundary_invalid", ledger.record(sameInstallation).reason)

        val sameDomain = attestation(receipt, AgentReputationVerificationVerdict.PASSED)
            .copy(verifierFailureDomain = receipt.executorFailureDomain)
            .signedBy(verifier)
        assertEquals("independence_boundary_invalid", ledger.record(sameDomain).reason)

        val sameSigningIdentity = attestation(receipt, AgentReputationVerificationVerdict.PASSED)
            .signedBy(executor)
        assertEquals(
            "independence_boundary_invalid",
            ledger.record(sameSigningIdentity).reason
        )
    }

    @Test
    fun independentFailureOverridesSelfReportedSuccess() {
        val ledger = ledger()
        val receipt = receipt("run-1", AgentReputationOutcome.SUCCEEDED)
        ledger.record(receipt)
        val before = ledger.snapshot(receipt.agentId, nowMillis = now + 1_000L)

        ledger.record(attestation(receipt, AgentReputationVerificationVerdict.FAILED))
        val after = ledger.snapshot(receipt.agentId, nowMillis = now + 1_000L)

        assertTrue(after.score < before.score)
        assertEquals(1, after.disputedRuns)
        assertEquals(0, after.independentlyVerifiedRuns)
    }

    @Test
    fun timeoutAndBudgetOverrunAffectSeparateDimensions() {
        val ledger = ledger()
        val timeout = receipt("run-timeout", AgentReputationOutcome.TIMED_OUT)
            .copy(
                deadlineAtMillis = now - 500L,
                estimatedCostUnits = 2,
                actualCostUnits = 8
            )
            .signedBy(executor)
        ledger.record(timeout)
        val profile = ledger.snapshot(timeout.agentId, nowMillis = now + 1_000L)

        assertEquals(1, profile.timeoutRuns)
        assertTrue(profile.timeliness < 70)
        assertTrue(profile.costEfficiency < 70)
        assertTrue(profile.reliability < 70)
    }

    @Test
    fun capabilityProfilesDoNotLeakUnrelatedSuccess() {
        val ledger = ledger()
        repeat(4) { index ->
            ledger.record(
                receipt(
                    runId = "code-$index",
                    outcome = AgentReputationOutcome.SUCCEEDED,
                    capabilities = setOf(AgentCapability.CODE)
                )
            )
        }
        ledger.record(
            receipt(
                runId = "research-failure",
                outcome = AgentReputationOutcome.FAILED,
                capabilities = setOf(AgentCapability.RESEARCH)
            )
        )

        val code = ledger.snapshot(
            "codex-agent",
            setOf(AgentCapability.CODE),
            now + 1_000L
        )
        val research = ledger.snapshot(
            "codex-agent",
            setOf(AgentCapability.RESEARCH),
            now + 1_000L
        )

        assertTrue(code.score > research.score)
        assertEquals(4, code.evaluatedRuns)
        assertEquals(1, research.evaluatedRuns)
    }

    @Test
    fun lateCorrectionReplacesEarlierObservationForTheSameRun() {
        val ledger = ledger()
        val failed = receipt("run-1", AgentReputationOutcome.FAILED)
        val corrected = receipt("run-1", AgentReputationOutcome.SUCCEEDED, completedAt = now + 100L)
        ledger.record(failed)
        ledger.record(corrected)

        val profile = ledger.snapshot(failed.agentId, nowMillis = now + 100L)

        assertEquals(2, ledger.receipts().size)
        assertEquals(1, profile.evaluatedRuns)
        assertTrue(profile.score > 70)
    }

    @Test
    fun reputationRanksProvenAgentButDoesNotBlockColdStart() {
        val ledger = ledger()
        repeat(5) { index ->
            val completed = receipt(
                runId = "success-$index",
                outcome = AgentReputationOutcome.SUCCEEDED,
                agentId = "z-proven"
            )
            ledger.record(completed)
            ledger.record(attestation(completed, AgentReputationVerificationVerdict.PASSED))
        }
        val index = AgentNetworkIndex(
            listOf(
                registration("a-new"),
                registration("z-proven")
            ),
            ledger
        )

        val ranked = index.search(
            AgentNetworkSearchQuery(
                preferredCapabilities = setOf(AgentCapability.REASONING),
                pageSize = 10
            ),
            now
        )
        assertEquals("z-proven", ranked.hits.first().registration.agentId)

        val thresholded = index.search(
            AgentNetworkSearchQuery(
                minimumReputationScore = 80,
                minimumReputationConfidence = 40,
                pageSize = 10
            ),
            now
        )
        assertTrue(thresholded.hits.any { it.registration.agentId == "a-new" })
    }

    @Test
    fun confidentlyPoorAgentCanBeExcludedFromSensitiveRouting() {
        val ledger = ledger()
        repeat(8) { index ->
            val failed = receipt(
                runId = "failure-$index",
                outcome = AgentReputationOutcome.FAILED,
                agentId = "a-poor"
            )
            ledger.record(failed)
            ledger.record(attestation(failed, AgentReputationVerificationVerdict.FAILED))
        }
        val index = AgentNetworkIndex(
            listOf(registration("a-poor"), registration("z-new")),
            ledger
        )

        val page = index.search(
            AgentNetworkSearchQuery(
                minimumReputationScore = 55,
                minimumReputationConfidence = 40,
                pageSize = 10
            ),
            now
        )

        assertFalse(page.hits.any { it.registration.agentId == "a-poor" })
        assertTrue(page.hits.any { it.registration.agentId == "z-new" })
    }

    @Test
    fun reputationMutationInvalidatesAgentSearchCursor() {
        val ledger = ledger()
        val index = AgentNetworkIndex(
            listOf(registration("a-agent"), registration("z-agent")),
            ledger
        )
        val first = index.search(AgentNetworkSearchQuery(pageSize = 1), now)
        assertTrue(first.nextCursor.isNotBlank())

        ledger.record(
            receipt(
                runId = "new-evidence",
                outcome = AgentReputationOutcome.SUCCEEDED,
                agentId = "z-agent"
            )
        )
        val resumed = index.search(
            AgentNetworkSearchQuery(pageSize = 1, cursor = first.nextCursor),
            now
        )

        assertTrue(resumed.cursorReset)
        assertTrue(resumed.revision > first.revision)
    }

    @Test
    fun dynamicTeamLeadSelectionUsesBoundedReputationEvidence() {
        val ledger = ledger()
        repeat(6) { index ->
            val completed = receipt(
                runId = "verified-$index",
                outcome = AgentReputationOutcome.SUCCEEDED,
                agentId = "z-reliable"
            )
            ledger.record(completed)
            ledger.record(attestation(completed, AgentReputationVerificationVerdict.PASSED))
        }
        val result = AgentDynamicTeamCompiler(ledger).compile(
            AgentDynamicTeamRequest("Answer a difficult architecture question"),
            listOf(registration("a-unknown"), registration("z-reliable")),
            now
        )

        assertEquals("z-reliable", result.primaryAgentId)
        assertTrue(result.assignments.first().reasons.any { it.startsWith("reputation:") })
    }

    @Test
    fun terminalTeamSnapshotProducesSignedHostReceipts() {
        val store = InMemoryAgentReputationStore()
        val ledger = AgentReputationLedger(identities, store, executor, clock = { now })
        val snapshot = AgentTeamExecutionSnapshot(
            supervisorRunId = "team-run",
            teamId = "team",
            conversationId = "conversation",
            taskId = "task",
            primaryAgentId = "codex-agent",
            goal = "Implement and verify",
            visibilityMode = AgentTeamVisibilityMode.BACKGROUND,
            state = AgentTeamExecutionState.SUCCEEDED,
            members = listOf(
                AgentTeamMemberSnapshot(
                    agentId = "codex-agent",
                    role = "lead",
                    deliveryMode = AgentDeliveryMode.RESPOND,
                    status = AgentSubagentStatus.SUCCEEDED,
                    output = "done",
                    startedAtMillis = now - 1_000L,
                    completedAtMillis = now
                )
            ),
            finalOutput = "done",
            createdAtMillis = now - 1_000L,
            updatedAtMillis = now
        )

        assertEquals(1, ledger.record(snapshot, listOf(registration("codex-agent"))))
        val stored = ledger.receipts().single()
        assertEquals(AgentReputationReceiptProvenance.HOST_OBSERVED, stored.provenance)
        assertEquals(executor.signerId, stored.signerId)
        assertTrue(identities.verify(
            stored.signerId,
            stored.signatureKeyId,
            stored.canonicalPayload(),
            stored.signature
        ))
    }

    @Test
    fun nonTerminalTeamSnapshotDoesNotCreateReputationEvidence() {
        val ledger = ledger(signer = executor)
        val snapshot = AgentTeamExecutionSnapshot(
            supervisorRunId = "running",
            teamId = "team",
            conversationId = "conversation",
            taskId = "task",
            primaryAgentId = "codex-agent",
            goal = "Work",
            visibilityMode = AgentTeamVisibilityMode.BACKGROUND,
            state = AgentTeamExecutionState.RUNNING,
            members = emptyList()
        )

        assertEquals(0, ledger.record(snapshot, listOf(registration("codex-agent"))))
        assertTrue(ledger.receipts().isEmpty())
    }

    private fun ledger(signer: AgentReputationSigner? = null) = AgentReputationLedger(
        verifier = identities,
        store = InMemoryAgentReputationStore(),
        signer = signer,
        clock = { now + 1_000L }
    )

    private fun receipt(
        runId: String,
        outcome: AgentReputationOutcome,
        agentId: String = "codex-agent",
        capabilities: Set<AgentCapability> = setOf(AgentCapability.CHAT, AgentCapability.REASONING),
        completedAt: Long = now
    ): AgentSignedExecutionReceipt {
        val unsigned = AgentSignedExecutionReceipt(
            receiptId = agentReputationSha256("$agentId:$runId:$outcome:$completedAt".toByteArray()),
            runId = runId,
            taskIdHash = agentReputationSha256("task-$runId".toByteArray()),
            agentId = agentId,
            installationId = executor.signerId,
            executorFailureDomain = executor.signerId,
            capabilities = capabilities,
            outcome = outcome,
            provenance = AgentReputationReceiptProvenance.EXECUTOR_SIGNED,
            startedAtMillis = completedAt - 1_000L,
            completedAtMillis = completedAt,
            outputHash = if (outcome == AgentReputationOutcome.SUCCEEDED) {
                agentReputationSha256("output-$runId".toByteArray())
            } else {
                ""
            },
            signerId = executor.signerId,
            signatureKeyId = executor.signatureKeyId,
            signature = ""
        )
        return unsigned.signedBy(executor)
    }

    private fun attestation(
        receipt: AgentSignedExecutionReceipt,
        verdict: AgentReputationVerificationVerdict
    ): AgentSignedReputationAttestation {
        val unsigned = AgentSignedReputationAttestation(
            attestationId = agentReputationSha256(
                "${receipt.receiptId}:$verdict".toByteArray()
            ),
            receiptId = receipt.receiptId,
            receiptPayloadHash = agentReputationSha256(receipt.canonicalPayload()),
            verifierAgentId = "independent-verifier",
            verifierInstallationId = verifier.signerId,
            verifierFailureDomain = "phone-b",
            verdict = verdict,
            evidenceHash = agentReputationSha256("evidence-${receipt.receiptId}".toByteArray()),
            createdAtMillis = receipt.completedAtMillis + 100L,
            signerId = verifier.signerId,
            signatureKeyId = verifier.signatureKeyId,
            signature = ""
        )
        return unsigned.signedBy(verifier)
    }

    private fun registration(agentId: String) = AgentRegistration(
        agentId = agentId,
        installationId = "$agentId-installation",
        deviceId = "$agentId-device",
        providerId = agentId,
        displayName = agentId,
        kind = AgentConnectorKind.AGENT,
        location = AgentResourceLocation.TRUSTED_DESKTOP,
        status = AgentEndpointStatus.IDLE,
        capabilities = setOf(AgentCapability.CHAT, AgentCapability.REASONING),
        protocol = AgentProtocolRange("1.0", "1.0", "1.0"),
        connectionKind = AgentConnectionKind.SIGNALASI_LINK,
        trust = AgentResourceTrust.VERIFIED_PAIRED,
        failureDomain = "$agentId-domain",
        lastHeartbeatMillis = now
    )

    private data class TestIdentity(
        override val signerId: String,
        private val secret: String
    ) : AgentReputationSigner {
        override val signatureKeyId: String =
            agentReputationSha256("key:$secret".toByteArray())

        override fun sign(payload: ByteArray): String =
            agentReputationSha256(secret.toByteArray() + payload)
    }

    private class TestIdentityVerifier(vararg identities: TestIdentity) :
        AgentReputationSignatureVerifier {
        private val identities = identities.associateBy(TestIdentity::signerId)

        override fun verify(
            signerId: String,
            signatureKeyId: String,
            payload: ByteArray,
            signature: String
        ): Boolean {
            val identity = identities[signerId] ?: return false
            return identity.signatureKeyId == signatureKeyId &&
                identity.sign(payload) == signature
        }
    }

    private fun AgentSignedExecutionReceipt.signedBy(
        identity: AgentReputationSigner
    ): AgentSignedExecutionReceipt = copy(
        signerId = identity.signerId,
        signatureKeyId = identity.signatureKeyId,
        signature = ""
    ).let { unsigned ->
        unsigned.copy(signature = identity.sign(unsigned.canonicalPayload()))
    }

    private fun AgentSignedReputationAttestation.signedBy(
        identity: AgentReputationSigner
    ): AgentSignedReputationAttestation = copy(
        signerId = identity.signerId,
        signatureKeyId = identity.signatureKeyId,
        signature = ""
    ).let { unsigned ->
        unsigned.copy(signature = identity.sign(unsigned.canonicalPayload()))
    }
}
