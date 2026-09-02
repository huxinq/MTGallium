package org.mtgallium.evaluation.searchteacher

import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.LeafEvaluator

@Serializable
internal enum class EvaluatorStageStatus { PASSED, DIAGNOSTIC, FAILED }

@Serializable
internal data class EvaluatorStageResult(
    val stage: Int,
    val name: String,
    val status: EvaluatorStageStatus,
    val evidence: List<String>,
    val findings: List<String>,
    val limitations: List<String> = emptyList(),
)

@Serializable
internal data class EvaluatorStage0Evidence(
    val v2ReferenceStates: Int,
    val v2BitExactMatches: Int,
    val hiddenVariantPairs: Int,
    val v3HiddenInvariantPairs: Int,
    val perspectiveMismatchRejected: Boolean,
    val terminalInputRejected: Boolean,
    val v2P50Micros: Double,
    val v2P95Micros: Double,
    val v3P50Micros: Double,
    val v3P95Micros: Double,
    val v3ConfigurationId: String,
    val passed: Boolean,
)

@Serializable
internal data class EvaluatorFactorialConfiguration(
    val family: LeafEvaluator,
    val simulations: Int,
    val maxPolicyDecisions: Int,
    val maxQuiescenceDecisions: Int,
    val explorationConstant: Double,
    val outputScale: Double,
)
