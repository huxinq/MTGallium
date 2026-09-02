package org.mtgallium.evaluation.argentum

import kotlinx.serialization.Serializable

@Serializable
data class DeckManifest(
    val id: String,
    val name: String,
    val format: String,
    val publishedDate: String,
    val source: String,
    val mainDeck: Map<String, Int>,
    val sideboard: Map<String, Int>,
)

@Serializable
enum class ProbeStatus { PASS, WARN, FAIL }

@Serializable
enum class Severity { INFO, MINOR, MAJOR, BLOCKER }

@Serializable
data class ProbeResult(
    val id: String,
    val component: String,
    val status: ProbeStatus,
    val severity: Severity,
    val summary: String,
    val evidence: Map<String, String> = emptyMap(),
    val durationMillis: Long? = null,
)

@Serializable
data class CorpusResult(
    val id: String,
    val requestedGames: Int,
    val completedGames: Int,
    val rejectedGames: Int,
    val truncatedGames: Int,
    val exceptions: Int,
    val totalSteps: Long,
    val wallClockMillis: Long,
)

@Serializable
data class Metric(
    val id: String,
    val value: Double,
    val unit: String,
    val samples: Int,
    val aggregation: String,
)

@Serializable
enum class Verdict { ADOPT, CONDITIONAL, REJECT }

@Serializable
data class ComponentDecision(
    val component: String,
    val verdict: Verdict,
    val reasons: List<String>,
)

@Serializable
data class EvaluationMetadata(
    val generatedAt: String,
    val argentumCommit: String,
    val gymSchemaHash: String,
    val javaVersion: String,
    val os: String,
    val suite: String,
    val baseSeed: Long,
    val deckId: String,
    val deckHash: String,
)

@Serializable
data class EvaluationReport(
    val metadata: EvaluationMetadata,
    val probes: List<ProbeResult>,
    val corpora: List<CorpusResult>,
    val metrics: List<Metric>,
    val decisions: List<ComponentDecision>,
    val overallVerdict: Verdict,
)
