package com.signalasi.chat

import android.content.Context
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

enum class AgentReputationOutcome {
    SUCCEEDED,
    PARTIAL,
    FAILED,
    TIMED_OUT,
    CANCELLED,
    REJECTED
}

enum class AgentReputationReceiptProvenance {
    EXECUTOR_SIGNED,
    HOST_OBSERVED
}

enum class AgentReputationVerificationVerdict {
    PASSED,
    FAILED,
    INCONCLUSIVE
}

data class AgentSignedExecutionReceipt(
    val receiptId: String,
    val runId: String,
    val taskIdHash: String,
    val agentId: String,
    val installationId: String,
    val executorFailureDomain: String,
    val capabilities: Set<AgentCapability>,
    val outcome: AgentReputationOutcome,
    val provenance: AgentReputationReceiptProvenance,
    val startedAtMillis: Long,
    val completedAtMillis: Long,
    val deadlineAtMillis: Long = 0L,
    val estimatedCostUnits: Int = 0,
    val actualCostUnits: Int = 0,
    val outputHash: String = "",
    val evidenceHash: String = "",
    val signerId: String,
    val signatureKeyId: String,
    val signature: String
) {
    fun canonicalPayload(): ByteArray = canonicalJson().toString().toByteArray(Charsets.UTF_8)

    internal fun canonicalJson(): JSONObject = JSONObject()
        .put("version", CURRENT_VERSION)
        .put("receipt_id", receiptId)
        .put("run_id", runId)
        .put("task_id_hash", taskIdHash.lowercase(Locale.US))
        .put("agent_id", agentId)
        .put("installation_id", installationId)
        .put("executor_failure_domain", executorFailureDomain)
        .put("capabilities", JSONArray(capabilities.map(AgentCapability::name).sorted()))
        .put("outcome", outcome.name)
        .put("provenance", provenance.name)
        .put("started_at_millis", startedAtMillis)
        .put("completed_at_millis", completedAtMillis)
        .put("deadline_at_millis", deadlineAtMillis)
        .put("estimated_cost_units", estimatedCostUnits)
        .put("actual_cost_units", actualCostUnits)
        .put("output_hash", outputHash.lowercase(Locale.US))
        .put("evidence_hash", evidenceHash.lowercase(Locale.US))
        .put("signer_id", signerId)
        .put("signature_key_id", signatureKeyId.lowercase(Locale.US))

    companion object {
        const val CURRENT_VERSION = 1
    }
}

data class AgentSignedReputationAttestation(
    val attestationId: String,
    val receiptId: String,
    val receiptPayloadHash: String,
    val verifierAgentId: String,
    val verifierInstallationId: String,
    val verifierFailureDomain: String,
    val verdict: AgentReputationVerificationVerdict,
    val evidenceHash: String,
    val createdAtMillis: Long,
    val signerId: String,
    val signatureKeyId: String,
    val signature: String
) {
    fun canonicalPayload(): ByteArray = canonicalJson().toString().toByteArray(Charsets.UTF_8)

    internal fun canonicalJson(): JSONObject = JSONObject()
        .put("version", CURRENT_VERSION)
        .put("attestation_id", attestationId)
        .put("receipt_id", receiptId)
        .put("receipt_payload_hash", receiptPayloadHash.lowercase(Locale.US))
        .put("verifier_agent_id", verifierAgentId)
        .put("verifier_installation_id", verifierInstallationId)
        .put("verifier_failure_domain", verifierFailureDomain)
        .put("verdict", verdict.name)
        .put("evidence_hash", evidenceHash.lowercase(Locale.US))
        .put("created_at_millis", createdAtMillis)
        .put("signer_id", signerId)
        .put("signature_key_id", signatureKeyId.lowercase(Locale.US))

    companion object {
        const val CURRENT_VERSION = 1
    }
}

data class AgentReputationSnapshot(
    val agentId: String,
    val score: Int,
    val confidence: Int,
    val reliability: Int,
    val quality: Int,
    val timeliness: Int,
    val costEfficiency: Int,
    val evaluatedRuns: Int,
    val independentlyVerifiedRuns: Int,
    val disputedRuns: Int,
    val timeoutRuns: Int,
    val independentFailureDomains: Int,
    val lastEvidenceAtMillis: Long,
    val routingAdjustment: Int
) {
    companion object {
        fun neutral(agentId: String) = AgentReputationSnapshot(
            agentId = agentId,
            score = 70,
            confidence = 0,
            reliability = 70,
            quality = 70,
            timeliness = 70,
            costEfficiency = 70,
            evaluatedRuns = 0,
            independentlyVerifiedRuns = 0,
            disputedRuns = 0,
            timeoutRuns = 0,
            independentFailureDomains = 0,
            lastEvidenceAtMillis = 0L,
            routingAdjustment = 0
        )
    }
}

data class AgentReputationRecordResult(
    val accepted: Boolean,
    val duplicate: Boolean = false,
    val reason: String,
    val snapshot: AgentReputationSnapshot? = null
)

fun interface AgentReputationSignatureVerifier {
    fun verify(
        signerId: String,
        signatureKeyId: String,
        payload: ByteArray,
        signature: String
    ): Boolean
}

interface AgentReputationSigner {
    val signerId: String
    val signatureKeyId: String
    fun sign(payload: ByteArray): String
}

class SignalASIReputationIdentity(context: Context) :
    AgentReputationSignatureVerifier,
    AgentReputationSigner {
    init {
        SignalASICrypto.initialize(context.applicationContext)
    }

    override val signerId: String
        get() = SignalASICrypto.localSignalasiId()

    override val signatureKeyId: String
        get() = SignalASICrypto.localIdentitySha256()

    override fun sign(payload: ByteArray): String = SignalASICrypto.signLocalIdentity(payload)

    override fun verify(
        signerId: String,
        signatureKeyId: String,
        payload: ByteArray,
        signature: String
    ): Boolean = SignalASICrypto.verifyIdentitySignature(
        identityName = signerId,
        expectedFingerprint = signatureKeyId,
        payload = payload,
        signature = signature
    )
}

interface AgentReputationSnapshotProvider {
    fun snapshot(
        agentId: String,
        capabilities: Set<AgentCapability> = emptySet(),
        nowMillis: Long = System.currentTimeMillis()
    ): AgentReputationSnapshot

    fun revision(): Long = 0L

    companion object {
        val NONE = object : AgentReputationSnapshotProvider {
            override fun snapshot(
                agentId: String,
                capabilities: Set<AgentCapability>,
                nowMillis: Long
            ): AgentReputationSnapshot = AgentReputationSnapshot.neutral(agentId)
        }
    }
}

enum class AgentReputationAppendResult {
    ADDED,
    DUPLICATE
}

interface AgentReputationStore {
    fun append(receipt: AgentSignedExecutionReceipt): AgentReputationAppendResult
    fun append(attestation: AgentSignedReputationAttestation): AgentReputationAppendResult
    fun receipts(): List<AgentSignedExecutionReceipt>
    fun attestations(): List<AgentSignedReputationAttestation>
    fun clear()
}

class InMemoryAgentReputationStore : AgentReputationStore {
    private val receipts = linkedMapOf<String, AgentSignedExecutionReceipt>()
    private val attestations = linkedMapOf<String, AgentSignedReputationAttestation>()

    @Synchronized
    override fun append(receipt: AgentSignedExecutionReceipt): AgentReputationAppendResult {
        receipts[receipt.receiptId]?.let { existing ->
            require(existing == receipt) { "Reputation receipt id collision" }
            return AgentReputationAppendResult.DUPLICATE
        }
        receipts[receipt.receiptId] = receipt
        return AgentReputationAppendResult.ADDED
    }

    @Synchronized
    override fun append(attestation: AgentSignedReputationAttestation): AgentReputationAppendResult {
        attestations[attestation.attestationId]?.let { existing ->
            require(existing == attestation) { "Reputation attestation id collision" }
            return AgentReputationAppendResult.DUPLICATE
        }
        attestations[attestation.attestationId] = attestation
        return AgentReputationAppendResult.ADDED
    }

    @Synchronized
    override fun receipts(): List<AgentSignedExecutionReceipt> = receipts.values.toList()

    @Synchronized
    override fun attestations(): List<AgentSignedReputationAttestation> = attestations.values.toList()

    @Synchronized
    override fun clear() {
        receipts.clear()
        attestations.clear()
    }
}

class EncryptedAgentReputationStore(context: Context) : AgentReputationStore {
    private val database = AgentEncryptedDatabase(context.applicationContext, DATABASE)
    private var loaded = false
    private var receipts = linkedMapOf<String, AgentSignedExecutionReceipt>()
    private var attestations = linkedMapOf<String, AgentSignedReputationAttestation>()

    @Synchronized
    override fun append(receipt: AgentSignedExecutionReceipt): AgentReputationAppendResult {
        ensureLoaded()
        receipts[receipt.receiptId]?.let { existing ->
            require(existing == receipt) { "Reputation receipt id collision" }
            return AgentReputationAppendResult.DUPLICATE
        }
        receipts[receipt.receiptId] = receipt
        persist()
        return AgentReputationAppendResult.ADDED
    }

    @Synchronized
    override fun append(attestation: AgentSignedReputationAttestation): AgentReputationAppendResult {
        ensureLoaded()
        attestations[attestation.attestationId]?.let { existing ->
            require(existing == attestation) { "Reputation attestation id collision" }
            return AgentReputationAppendResult.DUPLICATE
        }
        attestations[attestation.attestationId] = attestation
        persist()
        return AgentReputationAppendResult.ADDED
    }

    @Synchronized
    override fun receipts(): List<AgentSignedExecutionReceipt> {
        ensureLoaded()
        return receipts.values.toList()
    }

    @Synchronized
    override fun attestations(): List<AgentSignedReputationAttestation> {
        ensureLoaded()
        return attestations.values.toList()
    }

    @Synchronized
    override fun clear() {
        database.clear()
        receipts.clear()
        attestations.clear()
        loaded = true
    }

    private fun ensureLoaded() {
        if (loaded) return
        val document = AgentReputationCodec.decode(
            database.readString(KEY_DOCUMENT, EMPTY_DOCUMENT)
        )
        receipts = document.receipts.associateByTo(linkedMapOf()) {
            it.receiptId
        }
        attestations = document.attestations.associateByTo(linkedMapOf()) {
            it.attestationId
        }
        loaded = true
    }

    private fun persist() {
        database.writeString(
            KEY_DOCUMENT,
            AgentReputationCodec.encode(
                AgentReputationDocument(receipts.values.toList(), attestations.values.toList())
            )
        )
    }

    private companion object {
        const val DATABASE = "signalasi_agent_reputation_ledger_v1"
        const val KEY_DOCUMENT = "document"
        const val EMPTY_DOCUMENT = "{\"version\":1,\"receipts\":[],\"attestations\":[]}"
    }
}

/**
 * Append-only local reputation ledger. A valid signature proves provenance,
 * while correctness only receives full weight after an independent attestation.
 */
class AgentReputationLedger(
    private val verifier: AgentReputationSignatureVerifier,
    private val store: AgentReputationStore,
    private val signer: AgentReputationSigner? = null,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : AgentReputationSnapshotProvider {
    private val profileCache = ConcurrentHashMap<String, AgentReputationSnapshot>()
    private val ledgerRevision = AtomicLong(
        store.receipts().size.toLong() + store.attestations().size.toLong()
    )

    fun record(receipt: AgentSignedExecutionReceipt): AgentReputationRecordResult {
        val now = clock()
        validate(receipt, now)?.let { reason ->
            return AgentReputationRecordResult(false, reason = reason)
        }
        if (!verifier.verify(
                receipt.signerId,
                receipt.signatureKeyId,
                receipt.canonicalPayload(),
                receipt.signature
            )
        ) {
            return AgentReputationRecordResult(false, reason = "signature_invalid")
        }
        val append = runCatching { store.append(receipt) }.getOrElse {
            return AgentReputationRecordResult(false, reason = "receipt_id_collision")
        }
        if (append == AgentReputationAppendResult.ADDED) ledgerRevision.incrementAndGet()
        invalidate(receipt.agentId)
        return AgentReputationRecordResult(
            accepted = true,
            duplicate = append == AgentReputationAppendResult.DUPLICATE,
            reason = if (append == AgentReputationAppendResult.DUPLICATE) "duplicate" else "accepted",
            snapshot = snapshot(receipt.agentId, nowMillis = now)
        )
    }

    fun record(attestation: AgentSignedReputationAttestation): AgentReputationRecordResult {
        val now = clock()
        val receipt = store.receipts().firstOrNull { it.receiptId == attestation.receiptId }
            ?: return AgentReputationRecordResult(false, reason = "receipt_not_found")
        validate(attestation, receipt, now)?.let { reason ->
            return AgentReputationRecordResult(false, reason = reason)
        }
        if (!verifier.verify(
                attestation.signerId,
                attestation.signatureKeyId,
                attestation.canonicalPayload(),
                attestation.signature
            )
        ) {
            return AgentReputationRecordResult(false, reason = "signature_invalid")
        }
        val append = runCatching { store.append(attestation) }.getOrElse {
            return AgentReputationRecordResult(false, reason = "attestation_id_collision")
        }
        if (append == AgentReputationAppendResult.ADDED) ledgerRevision.incrementAndGet()
        invalidate(receipt.agentId)
        return AgentReputationRecordResult(
            accepted = true,
            duplicate = append == AgentReputationAppendResult.DUPLICATE,
            reason = if (append == AgentReputationAppendResult.DUPLICATE) "duplicate" else "accepted",
            snapshot = snapshot(receipt.agentId, nowMillis = now)
        )
    }

    fun record(snapshot: AgentTeamExecutionSnapshot, registrations: Collection<AgentRegistration>): Int {
        val effectiveSigner = signer ?: return 0
        return AgentReputationReceiptFactory.hostObserved(snapshot, registrations, effectiveSigner)
            .count { record(it).accepted }
    }

    override fun snapshot(
        agentId: String,
        capabilities: Set<AgentCapability>,
        nowMillis: Long
    ): AgentReputationSnapshot {
        val normalizedAgentId = agentId.trim()
        if (normalizedAgentId.isBlank()) return AgentReputationSnapshot.neutral("")
        val cacheBucket = nowMillis / PROFILE_CACHE_BUCKET_MILLIS
        val key = buildString {
            append(normalizedAgentId)
            append('\u001f')
            capabilities.map(AgentCapability::name).sorted().forEach {
                append(it).append(',')
            }
            append('\u001f').append(cacheBucket)
        }
        return profileCache[key] ?: calculate(normalizedAgentId, capabilities, nowMillis).also {
            profileCache[key] = it
        }
    }

    override fun revision(): Long = ledgerRevision.get()

    fun receipts(agentId: String = ""): List<AgentSignedExecutionReceipt> =
        store.receipts()
            .filter { agentId.isBlank() || it.agentId == agentId }
            .sortedByDescending(AgentSignedExecutionReceipt::completedAtMillis)

    fun attestations(receiptId: String = ""): List<AgentSignedReputationAttestation> =
        store.attestations()
            .filter { receiptId.isBlank() || it.receiptId == receiptId }
            .sortedByDescending(AgentSignedReputationAttestation::createdAtMillis)

    fun clear() {
        store.clear()
        profileCache.clear()
        ledgerRevision.incrementAndGet()
    }

    private fun calculate(
        agentId: String,
        capabilities: Set<AgentCapability>,
        nowMillis: Long
    ): AgentReputationSnapshot {
        val receipts = store.receipts()
            .asSequence()
            .filter { it.agentId == agentId }
            .filter { capabilities.isEmpty() || it.capabilities.intersect(capabilities).isNotEmpty() }
            .groupBy(AgentSignedExecutionReceipt::runId)
            .values
            .mapNotNull { versions ->
                versions.maxWithOrNull(
                    compareBy<AgentSignedExecutionReceipt> { it.completedAtMillis }
                        .thenBy { it.receiptId }
                )
            }
        if (receipts.isEmpty()) return AgentReputationSnapshot.neutral(agentId)
        val attestationsByReceipt = store.attestations().groupBy(AgentSignedReputationAttestation::receiptId)
        var reliabilityTotal = 0.0
        var qualityTotal = 0.0
        var timelinessTotal = 0.0
        var costTotal = 0.0
        var evidenceTotal = 0.0
        var verifiedRuns = 0
        var disputedRuns = 0
        var timeoutRuns = 0
        var lastEvidenceAt = 0L
        val verifierDomains = linkedSetOf<String>()
        receipts.forEach { receipt ->
            val attestations = attestationsByReceipt[receipt.receiptId].orEmpty()
            val failed = attestations.any { it.verdict == AgentReputationVerificationVerdict.FAILED }
            val passed = !failed &&
                attestations.any { it.verdict == AgentReputationVerificationVerdict.PASSED }
            if (passed) {
                verifiedRuns += 1
                attestations.filter { it.verdict == AgentReputationVerificationVerdict.PASSED }
                    .mapTo(verifierDomains, AgentSignedReputationAttestation::signerId)
            }
            if (failed) disputedRuns += 1
            if (receipt.outcome == AgentReputationOutcome.TIMED_OUT) timeoutRuns += 1
            val age = (nowMillis - receipt.completedAtMillis).coerceAtLeast(0L)
            val recency = exp(-ln(2.0) * age.toDouble() / REPUTATION_HALF_LIFE_MILLIS)
            val provenanceWeight = when (receipt.provenance) {
                AgentReputationReceiptProvenance.EXECUTOR_SIGNED -> 0.25
                AgentReputationReceiptProvenance.HOST_OBSERVED -> 0.60
            }
            val verificationWeight = when {
                failed -> 1.25
                passed -> 1.0
                else -> provenanceWeight
            }
            val outcomeWeight = when (receipt.outcome) {
                AgentReputationOutcome.CANCELLED -> 0.20
                AgentReputationOutcome.REJECTED -> 0.10
                else -> 1.0
            }
            val weight = recency * verificationWeight * outcomeWeight
            if (weight <= 0.0) return@forEach
            val reliability = if (failed) 0.0 else receipt.outcome.reliabilityValue()
            val quality = when {
                failed -> 0.0
                passed -> 1.0
                else -> receipt.outcome.qualityValue()
            }
            val timeliness = when {
                receipt.deadlineAtMillis <= 0L -> reliability
                receipt.completedAtMillis <= receipt.deadlineAtMillis -> 1.0
                else -> 0.0
            }
            val costEfficiency = when {
                receipt.estimatedCostUnits <= 0 -> if (reliability > 0.0) 0.75 else 0.0
                receipt.actualCostUnits <= receipt.estimatedCostUnits -> 1.0
                else -> receipt.estimatedCostUnits.toDouble() /
                    receipt.actualCostUnits.coerceAtLeast(1).toDouble()
            }
            reliabilityTotal += reliability * weight
            qualityTotal += quality * weight
            timelinessTotal += timeliness * weight
            costTotal += costEfficiency * weight
            evidenceTotal += weight
            lastEvidenceAt = maxOf(
                lastEvidenceAt,
                receipt.completedAtMillis,
                attestations.maxOfOrNull(AgentSignedReputationAttestation::createdAtMillis) ?: 0L
            )
        }
        if (evidenceTotal <= 0.0) return AgentReputationSnapshot.neutral(agentId)
        val reliability = posteriorPercent(reliabilityTotal, evidenceTotal)
        val quality = posteriorPercent(qualityTotal, evidenceTotal)
        val timeliness = posteriorPercent(timelinessTotal, evidenceTotal)
        val costEfficiency = posteriorPercent(costTotal, evidenceTotal)
        val score = (
            reliability * 0.45 +
                quality * 0.25 +
                timeliness * 0.15 +
                costEfficiency * 0.15
            ).roundToInt().coerceIn(0, 100)
        val confidence = ((1.0 - exp(-evidenceTotal / CONFIDENCE_SCALE)) * 100.0)
            .roundToInt().coerceIn(0, 100)
        val routingAdjustment = if (confidence < MIN_ROUTING_CONFIDENCE) {
            0
        } else {
            ((score - ROUTING_NEUTRAL_SCORE) * confidence / 100.0 * ROUTING_WEIGHT)
                .roundToInt().coerceIn(-MAX_ROUTING_ADJUSTMENT, MAX_ROUTING_ADJUSTMENT)
        }
        return AgentReputationSnapshot(
            agentId = agentId,
            score = score,
            confidence = confidence,
            reliability = reliability,
            quality = quality,
            timeliness = timeliness,
            costEfficiency = costEfficiency,
            evaluatedRuns = receipts.size,
            independentlyVerifiedRuns = verifiedRuns,
            disputedRuns = disputedRuns,
            timeoutRuns = timeoutRuns,
            independentFailureDomains = verifierDomains.size,
            lastEvidenceAtMillis = lastEvidenceAt,
            routingAdjustment = routingAdjustment
        )
    }

    private fun validate(receipt: AgentSignedExecutionReceipt, nowMillis: Long): String? {
        if (!validId(receipt.receiptId) || !validId(receipt.runId) ||
            !validId(receipt.agentId) || !validId(receipt.installationId) ||
            !validId(receipt.signerId)
        ) return "identity_invalid"
        if (!SHA256.matches(receipt.taskIdHash) ||
            receipt.outputHash.isNotBlank() && !SHA256.matches(receipt.outputHash) ||
            receipt.evidenceHash.isNotBlank() && !SHA256.matches(receipt.evidenceHash)
        ) return "hash_invalid"
        if (!SHA256.matches(receipt.signatureKeyId) || receipt.signature.isBlank() ||
            receipt.signature.length > MAX_SIGNATURE_CHARS
        ) return "signature_invalid"
        if (receipt.startedAtMillis <= 0L ||
            receipt.completedAtMillis < receipt.startedAtMillis ||
            receipt.completedAtMillis > nowMillis + MAX_CLOCK_SKEW_MILLIS
        ) return "time_boundary_invalid"
        if (receipt.deadlineAtMillis in 1 until receipt.startedAtMillis) return "deadline_invalid"
        if (receipt.estimatedCostUnits !in 0..MAX_COST_UNITS ||
            receipt.actualCostUnits !in 0..MAX_COST_UNITS
        ) return "cost_boundary_invalid"
        if (receipt.capabilities.size > MAX_CAPABILITIES) return "capability_boundary_invalid"
        if (receipt.executorFailureDomain.length > MAX_ID_CHARS) return "failure_domain_invalid"
        if (receipt.provenance == AgentReputationReceiptProvenance.EXECUTOR_SIGNED &&
            (receipt.installationId != receipt.signerId ||
                receipt.executorFailureDomain != receipt.signerId)
        ) return "signer_subject_mismatch"
        return null
    }

    private fun validate(
        attestation: AgentSignedReputationAttestation,
        receipt: AgentSignedExecutionReceipt,
        nowMillis: Long
    ): String? {
        if (!validId(attestation.attestationId) || !validId(attestation.verifierAgentId) ||
            !validId(attestation.verifierInstallationId) || !validId(attestation.signerId)
        ) return "identity_invalid"
        if (!SHA256.matches(attestation.receiptPayloadHash) ||
            !SHA256.matches(attestation.evidenceHash) ||
            !SHA256.matches(attestation.signatureKeyId)
        ) return "hash_invalid"
        if (attestation.receiptPayloadHash != agentReputationSha256(receipt.canonicalPayload())) {
            return "receipt_binding_invalid"
        }
        if (attestation.verifierAgentId == receipt.agentId ||
            attestation.verifierInstallationId == receipt.installationId ||
            attestation.signerId == receipt.signerId ||
            attestation.verifierFailureDomain.isBlank() ||
            attestation.verifierFailureDomain == receipt.executorFailureDomain
        ) return "independence_boundary_invalid"
        if (attestation.verifierInstallationId != attestation.signerId) {
            return "signer_subject_mismatch"
        }
        if (attestation.createdAtMillis < receipt.completedAtMillis ||
            attestation.createdAtMillis > nowMillis + MAX_CLOCK_SKEW_MILLIS
        ) return "time_boundary_invalid"
        if (attestation.signature.isBlank() || attestation.signature.length > MAX_SIGNATURE_CHARS) {
            return "signature_invalid"
        }
        return null
    }

    private fun invalidate(agentId: String) {
        profileCache.keys.removeAll { it.startsWith("$agentId\u001f") }
    }

    private fun posteriorPercent(success: Double, total: Double): Int =
        ((PRIOR_SUCCESS + success) / (PRIOR_WEIGHT + total) * 100.0)
            .roundToInt().coerceIn(0, 100)

    companion object {
        @Volatile private var processInstance: AgentReputationLedger? = null

        fun encrypted(context: Context): AgentReputationLedger =
            processInstance ?: synchronized(this) {
                processInstance ?: run {
                    val identity = SignalASIReputationIdentity(context)
                    AgentReputationLedger(
                        verifier = identity,
                        store = EncryptedAgentReputationStore(context),
                        signer = identity
                    ).also { processInstance = it }
                }
            }

        private const val PRIOR_WEIGHT = 2.0
        private const val PRIOR_SUCCESS = 1.4
        private const val CONFIDENCE_SCALE = 5.0
        private const val MIN_ROUTING_CONFIDENCE = 15
        private const val ROUTING_NEUTRAL_SCORE = 65
        private const val ROUTING_WEIGHT = 4.0
        private const val MAX_ROUTING_ADJUSTMENT = 180
        private const val REPUTATION_HALF_LIFE_MILLIS = 30L * 24L * 60L * 60L * 1_000L
        private const val PROFILE_CACHE_BUCKET_MILLIS = 5L * 60L * 1_000L
        private const val MAX_CLOCK_SKEW_MILLIS = 5L * 60L * 1_000L
        private const val MAX_ID_CHARS = 256
        private const val MAX_SIGNATURE_CHARS = 2_048
        private const val MAX_COST_UNITS = 1_000_000
        private const val MAX_CAPABILITIES = 64
        private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

        private fun validId(value: String): Boolean =
            value.isNotBlank() && value.length <= MAX_ID_CHARS
    }
}

object AgentReputationReceiptFactory {
    fun hostObserved(
        snapshot: AgentTeamExecutionSnapshot,
        registrations: Collection<AgentRegistration>,
        signer: AgentReputationSigner
    ): List<AgentSignedExecutionReceipt> {
        if (!snapshot.state.isTerminal) return emptyList()
        val registrationsById = registrations.associateBy(AgentRegistration::agentId)
        return snapshot.members.mapNotNull { member ->
            if (!member.status.isTerminal || member.status == AgentSubagentStatus.SKIPPED) {
                return@mapNotNull null
            }
            val registration = registrationsById[member.agentId]
            val startedAt = member.startedAtMillis.takeIf { it > 0L }
                ?: snapshot.createdAtMillis.coerceAtLeast(1L)
            val completedAt = member.completedAtMillis.takeIf { it >= startedAt }
                ?: snapshot.updatedAtMillis.coerceAtLeast(startedAt)
            val outcome = when (member.status) {
                AgentSubagentStatus.SUCCEEDED -> AgentReputationOutcome.SUCCEEDED
                AgentSubagentStatus.FAILED -> AgentReputationOutcome.FAILED
                AgentSubagentStatus.CANCELLED -> AgentReputationOutcome.CANCELLED
                AgentSubagentStatus.QUEUED,
                AgentSubagentStatus.RUNNING,
                AgentSubagentStatus.SKIPPED -> return@mapNotNull null
            }
            val outputHash = member.output.takeIf(String::isNotBlank)
                ?.toByteArray(Charsets.UTF_8)?.let(::agentReputationSha256).orEmpty()
            val evidenceHash = member.errorMessage.takeIf(String::isNotBlank)
                ?.toByteArray(Charsets.UTF_8)?.let(::agentReputationSha256).orEmpty()
            val receiptId = agentReputationSha256(
                listOf(
                    snapshot.supervisorRunId,
                    member.agentId,
                    member.status.name,
                    completedAt.toString(),
                    outputHash,
                    evidenceHash
                ).joinToString("\u001f").toByteArray(Charsets.UTF_8)
            )
            val unsigned = AgentSignedExecutionReceipt(
                receiptId = receiptId,
                runId = snapshot.supervisorRunId,
                taskIdHash = agentReputationSha256(snapshot.taskId.toByteArray(Charsets.UTF_8)),
                agentId = member.agentId,
                installationId = registration?.installationId.orEmpty().ifBlank { member.agentId },
                executorFailureDomain = registration?.effectiveTeamFailureDomain().orEmpty(),
                capabilities = registration?.capabilities.orEmpty(),
                outcome = outcome,
                provenance = AgentReputationReceiptProvenance.HOST_OBSERVED,
                startedAtMillis = startedAt,
                completedAtMillis = completedAt,
                outputHash = outputHash,
                evidenceHash = evidenceHash,
                signerId = signer.signerId,
                signatureKeyId = signer.signatureKeyId,
                signature = ""
            )
            unsigned.copy(signature = signer.sign(unsigned.canonicalPayload()))
        }
    }
}

private data class AgentReputationDocument(
    val receipts: List<AgentSignedExecutionReceipt>,
    val attestations: List<AgentSignedReputationAttestation>
)

private object AgentReputationCodec {
    fun encode(document: AgentReputationDocument): String = JSONObject()
        .put("version", 1)
        .put("receipts", JSONArray().apply {
            document.receipts.forEach { receipt ->
                put(receipt.canonicalJson().put("signature", receipt.signature))
            }
        })
        .put("attestations", JSONArray().apply {
            document.attestations.forEach { attestation ->
                put(attestation.canonicalJson().put("signature", attestation.signature))
            }
        })
        .toString()

    fun decode(raw: String): AgentReputationDocument = runCatching {
        val root = JSONObject(raw)
        AgentReputationDocument(
            receipts = root.optJSONArray("receipts").objects().mapNotNull(::decodeReceipt),
            attestations = root.optJSONArray("attestations").objects().mapNotNull(::decodeAttestation)
        )
    }.getOrDefault(AgentReputationDocument(emptyList(), emptyList()))

    private fun decodeReceipt(json: JSONObject): AgentSignedExecutionReceipt? = runCatching {
        AgentSignedExecutionReceipt(
            receiptId = json.getString("receipt_id"),
            runId = json.getString("run_id"),
            taskIdHash = json.getString("task_id_hash"),
            agentId = json.getString("agent_id"),
            installationId = json.getString("installation_id"),
            executorFailureDomain = json.optString("executor_failure_domain"),
            capabilities = json.optJSONArray("capabilities").strings()
                .mapNotNullTo(linkedSetOf()) { name ->
                    runCatching { AgentCapability.valueOf(name) }.getOrNull()
                },
            outcome = AgentReputationOutcome.valueOf(json.getString("outcome")),
            provenance = AgentReputationReceiptProvenance.valueOf(json.getString("provenance")),
            startedAtMillis = json.getLong("started_at_millis"),
            completedAtMillis = json.getLong("completed_at_millis"),
            deadlineAtMillis = json.optLong("deadline_at_millis"),
            estimatedCostUnits = json.optInt("estimated_cost_units"),
            actualCostUnits = json.optInt("actual_cost_units"),
            outputHash = json.optString("output_hash"),
            evidenceHash = json.optString("evidence_hash"),
            signerId = json.getString("signer_id"),
            signatureKeyId = json.getString("signature_key_id"),
            signature = json.getString("signature")
        )
    }.getOrNull()

    private fun decodeAttestation(json: JSONObject): AgentSignedReputationAttestation? = runCatching {
        AgentSignedReputationAttestation(
            attestationId = json.getString("attestation_id"),
            receiptId = json.getString("receipt_id"),
            receiptPayloadHash = json.getString("receipt_payload_hash"),
            verifierAgentId = json.getString("verifier_agent_id"),
            verifierInstallationId = json.getString("verifier_installation_id"),
            verifierFailureDomain = json.getString("verifier_failure_domain"),
            verdict = AgentReputationVerificationVerdict.valueOf(json.getString("verdict")),
            evidenceHash = json.getString("evidence_hash"),
            createdAtMillis = json.getLong("created_at_millis"),
            signerId = json.getString("signer_id"),
            signatureKeyId = json.getString("signature_key_id"),
            signature = json.getString("signature")
        )
    }.getOrNull()
}

private fun JSONArray?.objects(): List<JSONObject> = buildList {
    val array = this@objects ?: return@buildList
    for (index in 0 until array.length()) array.optJSONObject(index)?.let(::add)
}

private fun JSONArray?.strings(): Set<String> = buildSet {
    val array = this@strings ?: return@buildSet
    for (index in 0 until array.length()) array.optString(index).takeIf(String::isNotBlank)?.let(::add)
}

private fun AgentReputationOutcome.reliabilityValue(): Double = when (this) {
    AgentReputationOutcome.SUCCEEDED -> 1.0
    AgentReputationOutcome.PARTIAL -> 0.60
    AgentReputationOutcome.CANCELLED -> 0.50
    AgentReputationOutcome.FAILED,
    AgentReputationOutcome.TIMED_OUT,
    AgentReputationOutcome.REJECTED -> 0.0
}

private fun AgentReputationOutcome.qualityValue(): Double = when (this) {
    AgentReputationOutcome.SUCCEEDED -> 0.75
    AgentReputationOutcome.PARTIAL -> 0.50
    AgentReputationOutcome.CANCELLED -> 0.50
    AgentReputationOutcome.FAILED,
    AgentReputationOutcome.TIMED_OUT,
    AgentReputationOutcome.REJECTED -> 0.0
}

internal fun agentReputationSha256(value: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }
