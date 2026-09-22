package org.mtgallium.integration.argentum.policy

import org.springframework.boot.context.properties.ConfigurationProperties

/** MTGallium-owned local-host configuration. */
@ConfigurationProperties(prefix = "game.ai.search-teacher")
data class SearchPolicyProperties(
    val particles: Int = 8,
    val simulations: Int = 64,
    val maxPolicyDecisions: Int = 32,
    val explorationConstant: Double = 1.4,
    val baseSeed: Long = 20260825L,
    /** Open-deck declarations for replay seats p0 and p1; these must match the live game decks. */
    val knownDecks: Map<String, Map<String, Int>> = emptyMap(),
)

/** A search candidate stripped to perspective-safe local diagnostics. */
data class SearchCandidateInsight(
    val label: String,
    val signature: String,
    val visits: Int,
    val meanValue: Double,
    val policyProbability: Double,
    val chosen: Boolean,
)

/** Read-only policy diagnostics containing no authoritative state or hidden-card data. */
data class SearchPolicyInsight(
    /** Exact recorded-prefix length when available; null when the host supplied no replay history. */
    val actionIndex: Int?,
    val chosenLabel: String? = null,
    val chosenSignature: String? = null,
    val candidates: List<SearchCandidateInsight> = emptyList(),
    val rootValue: Double? = null,
    val thinkTimeMs: Double = 0.0,
    val simulations: Int = 0,
    val particles: Int = 0,
    val nodes: Int = 0,
    val maximumDepth: Int = 0,
    val exhaustiveNodes: Int = 0,
    val nonExhaustiveNodes: Int = 0,
    val wideningEvents: Int = 0,
    val beliefEntropy: Double = 0.0,
    val effectiveSampleSize: Double = 0.0,
    val resamplingCount: Int = 0,
    val reconditioningCount: Int = 0,
    val failureCode: String? = null,
    val diagnostic: String? = null,
    val authoritativeFingerprint: String? = null,
    val shadowFingerprint: String? = null,
)

/** A local policy failure, never a strategic outcome. */
class PolicyControllerFailure(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
