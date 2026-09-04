package org.mtgallium.integration.searchteacher

import org.springframework.boot.context.properties.ConfigurationProperties

/** MTGallium-owned local-host configuration. */
@ConfigurationProperties(prefix = "game.ai.search-teacher")
data class SearchTeacherHostProperties(
    val particles: Int = 8,
    val simulations: Int = 64,
    val maxPolicyDecisions: Int = 32,
    val explorationConstant: Double = 1.4,
    val baseSeed: Long = 20260825L,
)

/** A search candidate stripped to perspective-safe local diagnostics. */
data class SearchTeacherCandidateInsight(
    val label: String,
    val signature: String,
    val visits: Int,
    val meanValue: Double,
    val policyProbability: Double,
    val chosen: Boolean,
)

/** Read-only Search Teacher diagnostics containing no authoritative state or hidden-card data. */
data class SearchTeacherInsight(
    val actionIndex: Int,
    val chosenLabel: String? = null,
    val chosenSignature: String? = null,
    val candidates: List<SearchTeacherCandidateInsight> = emptyList(),
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

/** A local Search Teacher policy failure, never a strategic outcome. */
class SearchTeacherControllerFailure(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
