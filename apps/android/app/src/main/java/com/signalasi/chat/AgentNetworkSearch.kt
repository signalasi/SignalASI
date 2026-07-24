package com.signalasi.chat

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Base64
import java.util.Locale

data class AgentNetworkSearchQuery(
    val text: String = "",
    val requiredCapabilities: Set<AgentCapability> = emptySet(),
    val preferredCapabilities: Set<AgentCapability> = emptySet(),
    val kinds: Set<AgentConnectorKind> = emptySet(),
    val locations: Set<AgentResourceLocation> = emptySet(),
    val statuses: Set<AgentEndpointStatus> = emptySet(),
    val providerIds: Set<String> = emptySet(),
    val deviceIds: Set<String> = emptySet(),
    val excludedAgentIds: Set<String> = emptySet(),
    val trustedOnly: Boolean = false,
    val routableOnly: Boolean = true,
    val includeAtCapacity: Boolean = false,
    val maximumCost: AgentResourceCost? = null,
    val maximumLatency: AgentResourceLatency? = null,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    val cursor: String = ""
) {
    companion object {
        const val DEFAULT_PAGE_SIZE = 24
        const val MAX_PAGE_SIZE = 100
    }
}

data class AgentNetworkSearchHit(
    val registration: AgentRegistration,
    val score: Int,
    val matchedCapabilities: Set<AgentCapability>,
    val reasons: List<String>
)

data class AgentNetworkSearchPage(
    val queryId: String,
    val revision: Long,
    val hits: List<AgentNetworkSearchHit>,
    val totalMatches: Int,
    val nextCursor: String,
    val cursorReset: Boolean,
    val generatedAtMillis: Long
)

/**
 * Process-local search index for the federated Agent directory. Agent names and
 * identities remain first-class; this index only makes discovery and ranking
 * scalable enough for dynamic teams and large paired-device networks.
 */
class AgentNetworkIndex(registrations: Collection<AgentRegistration> = emptyList()) {
    private val registrationsById = linkedMapOf<String, AgentRegistration>()
    private val idsByToken = mutableMapOf<String, MutableSet<String>>()
    private val idsByCapability = mutableMapOf<AgentCapability, MutableSet<String>>()
    private val idsByKind = mutableMapOf<AgentConnectorKind, MutableSet<String>>()
    private val idsByLocation = mutableMapOf<AgentResourceLocation, MutableSet<String>>()
    private val idsByProvider = mutableMapOf<String, MutableSet<String>>()
    private val idsByDevice = mutableMapOf<String, MutableSet<String>>()
    private var currentRevision = 0L

    init {
        registrations.distinctBy(AgentRegistration::agentId).forEach { registration ->
            registrationsById[registration.agentId] = registration
            addToIndexes(registration)
        }
        if (registrationsById.isNotEmpty()) currentRevision = 1L
    }

    @Synchronized
    fun size(): Int = registrationsById.size

    @Synchronized
    fun revision(): Long = currentRevision

    @Synchronized
    fun get(agentId: String, nowMillis: Long = System.currentTimeMillis()): AgentRegistration? =
        registrationsById[agentId]?.withEffectiveNetworkStatus(nowMillis)

    @Synchronized
    fun upsert(registration: AgentRegistration): AgentRegistration? {
        val previous = registrationsById.put(registration.agentId, registration)
        if (previous == registration) return previous
        previous?.let(::removeFromIndexes)
        addToIndexes(registration)
        currentRevision += 1L
        return previous
    }

    @Synchronized
    fun remove(agentId: String): AgentRegistration? {
        val removed = registrationsById.remove(agentId) ?: return null
        removeFromIndexes(removed)
        currentRevision += 1L
        return removed
    }

    @Synchronized
    fun clear() {
        if (registrationsById.isEmpty()) return
        registrationsById.clear()
        idsByToken.clear()
        idsByCapability.clear()
        idsByKind.clear()
        idsByLocation.clear()
        idsByProvider.clear()
        idsByDevice.clear()
        currentRevision += 1L
    }

    @Synchronized
    fun snapshot(nowMillis: Long = System.currentTimeMillis()): List<AgentRegistration> =
        registrationsById.values
            .map { it.withEffectiveNetworkStatus(nowMillis) }
            .sortedWith(
                compareBy<AgentRegistration, String>(String.CASE_INSENSITIVE_ORDER) { it.displayName }
                    .thenBy { it.agentId }
            )

    @Synchronized
    fun search(
        query: AgentNetworkSearchQuery,
        nowMillis: Long = System.currentTimeMillis()
    ): AgentNetworkSearchPage {
        val normalizedQuery = query.copy(
            pageSize = query.pageSize.coerceIn(1, AgentNetworkSearchQuery.MAX_PAGE_SIZE)
        )
        val queryId = normalizedQuery.fingerprint()
        val requirements = normalizedQuery.text
            .takeIf(String::isNotBlank)
            ?.let(AgentTaskRequirementAnalyzer::analyze)
        val inferredCapabilities = requirements?.capabilities.orEmpty() - AgentCapability.CHAT
        val preferredCapabilities = normalizedQuery.preferredCapabilities + inferredCapabilities
        val queryTokens = searchTokens(normalizedQuery.text)
        val candidateIds = candidateIds(normalizedQuery, queryTokens, inferredCapabilities)
        val ranked = candidateIds.asSequence()
            .mapNotNull(registrationsById::get)
            .map { it.withEffectiveNetworkStatus(nowMillis) }
            .filter { it.matches(normalizedQuery, requirements) }
            .map { registration ->
                registration.toSearchHit(normalizedQuery, requirements, preferredCapabilities, queryTokens, nowMillis)
            }
            .sortedWith(
                compareByDescending<AgentNetworkSearchHit> { it.score }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.registration.displayName }
                    .thenBy { it.registration.agentId }
            )
            .toList()
        val decodedCursor = normalizedQuery.cursor.decodeCursor()
        val cursorValid = decodedCursor != null &&
            decodedCursor.revision == currentRevision &&
            decodedCursor.queryId == queryId
        val cursorIndex = if (cursorValid) {
            ranked.indexOfFirst { it.registration.agentId == decodedCursor?.lastAgentId }
        } else {
            -1
        }
        val cursorReset = normalizedQuery.cursor.isNotBlank() && (!cursorValid || cursorIndex < 0)
        val startIndex = if (cursorValid && cursorIndex >= 0) cursorIndex + 1 else 0
        val endIndex = (startIndex + normalizedQuery.pageSize).coerceAtMost(ranked.size)
        val hits = if (startIndex < ranked.size) ranked.subList(startIndex, endIndex) else emptyList()
        val nextCursor = if (endIndex < ranked.size && hits.isNotEmpty()) {
            AgentNetworkCursor(currentRevision, queryId, hits.last().registration.agentId).encode()
        } else {
            ""
        }
        return AgentNetworkSearchPage(
            queryId = queryId,
            revision = currentRevision,
            hits = hits,
            totalMatches = ranked.size,
            nextCursor = nextCursor,
            cursorReset = cursorReset,
            generatedAtMillis = nowMillis
        )
    }

    private fun candidateIds(
        query: AgentNetworkSearchQuery,
        queryTokens: Set<String>,
        inferredCapabilities: Set<AgentCapability>
    ): Set<String> {
        var candidates: MutableSet<String>? = null
        query.requiredCapabilities.forEach { capability ->
            candidates = candidates.intersectWith(idsByCapability[capability].orEmpty())
        }
        if (query.kinds.isNotEmpty()) {
            candidates = candidates.intersectWith(query.kinds.flatMapTo(mutableSetOf()) {
                idsByKind[it].orEmpty()
            })
        }
        if (query.locations.isNotEmpty()) {
            candidates = candidates.intersectWith(query.locations.flatMapTo(mutableSetOf()) {
                idsByLocation[it].orEmpty()
            })
        }
        if (query.providerIds.isNotEmpty()) {
            candidates = candidates.intersectWith(query.providerIds.flatMapTo(mutableSetOf()) {
                idsByProvider[normalizeSearchText(it)].orEmpty()
            })
        }
        if (query.deviceIds.isNotEmpty()) {
            candidates = candidates.intersectWith(query.deviceIds.flatMapTo(mutableSetOf()) {
                idsByDevice[normalizeSearchText(it)].orEmpty()
            })
        }
        if (query.text.isNotBlank()) {
            val semanticCandidates = mutableSetOf<String>()
            queryTokens.forEach { semanticCandidates += idsByToken[it].orEmpty() }
            inferredCapabilities.forEach { semanticCandidates += idsByCapability[it].orEmpty() }
            if (semanticCandidates.isNotEmpty()) candidates = candidates.intersectWith(semanticCandidates)
        }
        return candidates ?: registrationsById.keys.toSet()
    }

    private fun MutableSet<String>?.intersectWith(incoming: Set<String>): MutableSet<String> =
        this?.apply { retainAll(incoming) } ?: incoming.toMutableSet()

    private fun addToIndexes(registration: AgentRegistration) {
        registration.indexTokens().forEach { token -> idsByToken.addId(token, registration.agentId) }
        registration.capabilities.forEach { capability -> idsByCapability.addId(capability, registration.agentId) }
        idsByKind.addId(registration.kind, registration.agentId)
        idsByLocation.addId(registration.location, registration.agentId)
        idsByProvider.addId(normalizeSearchText(registration.providerId), registration.agentId)
        idsByDevice.addId(normalizeSearchText(registration.deviceId), registration.agentId)
    }

    private fun removeFromIndexes(registration: AgentRegistration) {
        registration.indexTokens().forEach { token -> idsByToken.removeId(token, registration.agentId) }
        registration.capabilities.forEach { capability -> idsByCapability.removeId(capability, registration.agentId) }
        idsByKind.removeId(registration.kind, registration.agentId)
        idsByLocation.removeId(registration.location, registration.agentId)
        idsByProvider.removeId(normalizeSearchText(registration.providerId), registration.agentId)
        idsByDevice.removeId(normalizeSearchText(registration.deviceId), registration.agentId)
    }

    private fun <K> MutableMap<K, MutableSet<String>>.addId(key: K, agentId: String) {
        getOrPut(key) { linkedSetOf() } += agentId
    }

    private fun <K> MutableMap<K, MutableSet<String>>.removeId(key: K, agentId: String) {
        val ids = get(key) ?: return
        ids -= agentId
        if (ids.isEmpty()) remove(key)
    }
}

private fun AgentRegistration.matches(
    query: AgentNetworkSearchQuery,
    requirements: AgentTaskRequirements?
): Boolean {
    if (agentId in query.excludedAgentIds) return false
    if (!capabilities.containsAll(query.requiredCapabilities)) return false
    if (query.kinds.isNotEmpty() && kind !in query.kinds) return false
    if (query.locations.isNotEmpty() && location !in query.locations) return false
    if (query.statuses.isNotEmpty() && status !in query.statuses) return false
    if (query.providerIds.isNotEmpty() &&
        normalizeSearchText(providerId) !in query.providerIds.map(::normalizeSearchText)
    ) return false
    if (query.deviceIds.isNotEmpty() &&
        normalizeSearchText(deviceId) !in query.deviceIds.map(::normalizeSearchText)
    ) return false
    if (query.trustedOnly && trust == AgentResourceTrust.UNKNOWN) return false
    if (query.maximumCost != null && cost.ordinal > query.maximumCost.ordinal) return false
    if (query.maximumLatency != null && latency.ordinal > query.maximumLatency.ordinal) return false
    if (requirements?.localOnly == true && location == AgentResourceLocation.CLOUD) return false
    if (query.routableOnly && status !in ROUTABLE_AGENT_NETWORK_STATES) return false
    if (query.routableOnly && !query.includeAtCapacity && !hasCapacity) return false
    return true
}

private fun AgentRegistration.toSearchHit(
    query: AgentNetworkSearchQuery,
    requirements: AgentTaskRequirements?,
    preferredCapabilities: Set<AgentCapability>,
    queryTokens: Set<String>,
    nowMillis: Long
): AgentNetworkSearchHit {
    val reasons = mutableListOf<String>()
    var score = 0
    val normalizedText = normalizeSearchText(query.text)
    val searchableFields = listOf(agentId, displayName, providerId, deviceId, adapterType)
        .map(::normalizeSearchText)
        .filter(String::isNotBlank)
    if (normalizedText.isNotBlank()) {
        when {
            searchableFields.any { it == normalizedText } -> {
                score += 1_200
                reasons += "identity_exact"
            }
            searchableFields.any { it.startsWith(normalizedText) } -> {
                score += 760
                reasons += "identity_prefix"
            }
            searchableFields.any { it.contains(normalizedText) } -> {
                score += 520
                reasons += "identity_contains"
            }
        }
        val tokenMatches = indexTokens().intersect(queryTokens)
        if (tokenMatches.isNotEmpty()) {
            score += tokenMatches.size * 90
            reasons += "text_tokens:${tokenMatches.size}"
        }
    }
    val matchedCapabilities = capabilities.intersect(preferredCapabilities)
    val missingPreferred = preferredCapabilities - capabilities
    score += matchedCapabilities.size * 130
    score -= missingPreferred.size * 45
    if (matchedCapabilities.isNotEmpty()) {
        reasons += "capabilities:${matchedCapabilities.sortedBy { it.name }.joinToString(",") { it.name }}"
    }
    score += when (status) {
        AgentEndpointStatus.IDLE -> 240
        AgentEndpointStatus.ONLINE -> 220
        AgentEndpointStatus.BUSY -> 120
        AgentEndpointStatus.DEGRADED -> -80
        AgentEndpointStatus.UPDATING -> -140
        AgentEndpointStatus.PERMISSION_REQUIRED -> -180
        AgentEndpointStatus.OFFLINE -> -300
        AgentEndpointStatus.UNREACHABLE -> -420
    }
    score += if (hasCapacity) 90 else -260
    score += when (trust) {
        AgentResourceTrust.PHONE_SYSTEM -> 180
        AgentResourceTrust.VERIFIED_PAIRED -> 160
        AgentResourceTrust.PRIVATE_CONFIGURED -> 110
        AgentResourceTrust.CLOUD_CONFIGURED -> 55
        AgentResourceTrust.UNKNOWN -> -160
    }
    score -= cost.ordinal * 35
    score -= latency.ordinal * 30
    when (requirements?.mode) {
        AgentRoutingMode.FAST -> if (latency <= AgentResourceLatency.FAST) score += 180
        AgentRoutingMode.ECONOMY -> if (cost <= AgentResourceCost.LOW) score += 180
        AgentRoutingMode.PRIVATE -> when (location) {
            AgentResourceLocation.PHONE -> score += 220
            AgentResourceLocation.TRUSTED_DESKTOP,
            AgentResourceLocation.PRIVATE_NETWORK -> score += 120
            AgentResourceLocation.CLOUD -> score -= 500
        }
        AgentRoutingMode.QUALITY -> if (AgentCapability.REASONING in capabilities) score += 180
        AgentRoutingMode.BALANCED, null -> Unit
    }
    if (lastHeartbeatMillis > 0L) {
        val heartbeatAge = (nowMillis - lastHeartbeatMillis).coerceAtLeast(0L)
        score += when {
            heartbeatAge <= 30_000L -> 90
            heartbeatAge <= 120_000L -> 55
            heartbeatAge <= AGENT_NETWORK_HEARTBEAT_TTL_MILLIS -> 20
            else -> -120
        }
        reasons += "heartbeat_age_ms:$heartbeatAge"
    }
    reasons += "status:${status.name.lowercase(Locale.US)}"
    reasons += "trust:${trust.name.lowercase(Locale.US)}"
    reasons += "latency:${latency.name.lowercase(Locale.US)}"
    reasons += "cost:${cost.name.lowercase(Locale.US)}"
    return AgentNetworkSearchHit(
        registration = this,
        score = score,
        matchedCapabilities = matchedCapabilities,
        reasons = reasons.distinct()
    )
}

private fun AgentRegistration.indexTokens(): Set<String> = buildSet {
    listOf(agentId, installationId, deviceId, providerId, displayName, adapterType)
        .forEach { addAll(searchTokens(it)) }
    capabilities.forEach { addAll(searchTokens(it.name)) }
    toolIds.forEach { addAll(searchTokens(it)) }
}

internal fun AgentRegistration.withEffectiveNetworkStatus(
    nowMillis: Long,
    heartbeatTtlMillis: Long = AGENT_NETWORK_HEARTBEAT_TTL_MILLIS
): AgentRegistration {
    val stale = location != AgentResourceLocation.PHONE &&
        lastHeartbeatMillis > 0L &&
        nowMillis - lastHeartbeatMillis > heartbeatTtlMillis &&
        status !in setOf(AgentEndpointStatus.OFFLINE, AgentEndpointStatus.UNREACHABLE)
    return if (stale) copy(status = AgentEndpointStatus.UNREACHABLE) else this
}

internal fun agentNetworkStorageKey(agentId: String): String =
    "agent:${sha256(agentId).take(40)}"

private fun AgentNetworkSearchQuery.fingerprint(): String {
    val canonical = listOf(
        normalizeSearchText(text),
        requiredCapabilities.sortedBy { it.name }.joinToString(",") { it.name },
        preferredCapabilities.sortedBy { it.name }.joinToString(",") { it.name },
        kinds.sortedBy { it.name }.joinToString(",") { it.name },
        locations.sortedBy { it.name }.joinToString(",") { it.name },
        statuses.sortedBy { it.name }.joinToString(",") { it.name },
        providerIds.map(::normalizeSearchText).sorted().joinToString(","),
        deviceIds.map(::normalizeSearchText).sorted().joinToString(","),
        excludedAgentIds.sorted().joinToString(","),
        trustedOnly.toString(),
        routableOnly.toString(),
        includeAtCapacity.toString(),
        maximumCost?.name.orEmpty(),
        maximumLatency?.name.orEmpty(),
        pageSize.coerceIn(1, AgentNetworkSearchQuery.MAX_PAGE_SIZE).toString()
    ).joinToString("\u001f")
    return sha256(canonical).take(24)
}

private data class AgentNetworkCursor(
    val revision: Long,
    val queryId: String,
    val lastAgentId: String
) {
    fun encode(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        "$revision\u001f$queryId\u001f$lastAgentId".toByteArray(Charsets.UTF_8)
    )
}

private fun String.decodeCursor(): AgentNetworkCursor? {
    if (isBlank()) return null
    return runCatching {
        val decoded = String(Base64.getUrlDecoder().decode(this), Charsets.UTF_8).split('\u001f')
        require(decoded.size == 3)
        AgentNetworkCursor(decoded[0].toLong(), decoded[1], decoded[2])
    }.getOrNull()
}

private fun searchTokens(value: String): Set<String> {
    val normalized = normalizeSearchText(value)
    if (normalized.isBlank()) return emptySet()
    return buildSet {
        SEARCH_TOKEN_REGEX.findAll(normalized).forEach { match ->
            val token = match.value.trim('.', '_', ':', '-')
            if (token.isBlank()) return@forEach
            add(token)
            if (token.any(::isCjkCharacter) && token.length <= MAX_CJK_INDEX_LENGTH) {
                token.forEach { character -> add(character.toString()) }
                token.windowed(size = 2, step = 1).forEach(::add)
            }
        }
    }
}

private fun normalizeSearchText(value: String): String = Normalizer
    .normalize(value.trim(), Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT)

private fun isCjkCharacter(character: Char): Boolean =
    Character.UnicodeScript.of(character.code) in CJK_SCRIPTS

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }

private val SEARCH_TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+(?:[._:-][\\p{L}\\p{N}]+)*")
private val CJK_SCRIPTS = setOf(
    Character.UnicodeScript.HAN,
    Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA,
    Character.UnicodeScript.HANGUL
)
private val ROUTABLE_AGENT_NETWORK_STATES = setOf(
    AgentEndpointStatus.ONLINE,
    AgentEndpointStatus.IDLE,
    AgentEndpointStatus.BUSY
)
private const val MAX_CJK_INDEX_LENGTH = 32
internal const val AGENT_NETWORK_HEARTBEAT_TTL_MILLIS = 10 * 60_000L
