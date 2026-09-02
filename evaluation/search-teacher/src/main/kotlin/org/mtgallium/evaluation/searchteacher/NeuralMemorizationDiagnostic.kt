package org.mtgallium.evaluation.searchteacher

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.PolicyJson

@Serializable
internal data class NeuralDecisionReference(
    val gameId: String,
    val decisionIndex: Int,
    val decisionFamily: String,
    val candidateCount: Int,
)

internal fun EncodedBcDecision.neuralDecisionReference(): NeuralDecisionReference = NeuralDecisionReference(
    gameId = gameId,
    decisionIndex = decisionIndex,
    decisionFamily = decisionFamily,
    candidateCount = candidateCount,
)

@Serializable
internal data class NeuralScorerInputOccurrence(
    val decision: NeuralDecisionReference,
    val candidateIndex: Int,
    val teacherCandidate: Boolean,
)

@Serializable
internal data class NeuralDuplicateScorerInputGroup(
    val scorerInputId: String,
    val occurrences: List<NeuralScorerInputOccurrence>,
)

@Serializable
internal data class NeuralRepeatedStateGroup(
    val encodedStateId: String,
    val decisions: List<NeuralDecisionReference>,
)

@Serializable
internal data class NeuralDuplicateDecisionInputGroup(
    val decisionInputId: String,
    val decisions: List<NeuralDecisionReference>,
    val teacherLabelIndices: List<Int>,
)

@Serializable
internal data class NeuralRankingConstraintEvidence(
    val loserScorerInputId: String,
    val winnerScorerInputId: String,
    val decision: NeuralDecisionReference,
    val alternativeCandidateIndex: Int,
    val teacherCandidateIndex: Int,
)

@Serializable
internal data class NeuralContradictoryRankingComponent(
    val scorerInputIds: List<String>,
    val constraints: List<NeuralRankingConstraintEvidence>,
    val affectedDecisions: List<NeuralDecisionReference>,
)

/**
 * Static consistency of the actual function seen by a shared scorer: one scalar for each exact
 * `(encoded state, encoded candidate)` pair. Strict teacher-over-alternative constraints are
 * realizable iff their directed graph is acyclic. The constructive scores are longest-path ranks
 * in that DAG; they say nothing about whether a particular neural parameterization can realize it.
 */
@Serializable
internal data class NeuralFullInputRealizability(
    val trainingDecisions: Int,
    val candidateOccurrences: Int,
    val distinctEncodedDecisionInputs: Int,
    val exactDuplicateDecisionInputGroups: Int,
    val decisionsInExactDuplicateInputGroups: Int,
    val duplicateDecisionInputs: List<NeuralDuplicateDecisionInputGroup>,
    val distinctEncodedStates: Int,
    val repeatedEncodedStateGroups: Int,
    val decisionsWithRepeatedEncodedState: Int,
    val repeatedEncodedStates: List<NeuralRepeatedStateGroup>,
    val distinctScorerInputs: Int,
    val exactDuplicateScorerInputGroups: Int,
    val scorerInputOccurrencesInDuplicateGroups: Int,
    val decisionsWithDuplicateScorerInputs: Int,
    val duplicateScorerInputs: List<NeuralDuplicateScorerInputGroup>,
    val rankingConstraints: Int,
    val distinctRankingConstraints: Int,
    val selfContradictoryConstraints: Int,
    val contradictoryRankingComponents: List<NeuralContradictoryRankingComponent>,
    val contradictoryConstraints: Int,
    val decisionsAffectedByContradictions: Int,
    val unrestrictedDeterministicScorerIsConsistent: Boolean,
    val constructiveStrictRankingCorrect: Int,
    val constructiveStrictRankingAccuracy: Double,
    val constructiveMinimumTeacherMargin: Int?,
)

@Serializable
internal data class NeuralActivationSaturationSeedAudit(
    val subsetDecisions: Int,
    val seed: Long,
    val modelEpoch: Int,
    val stateActivationValues: Int,
    val stateNearSaturatedValues: Int,
    val stateNearSaturatedFraction: Double,
    val stateExactlySaturatedValues: Int,
    val stateExactlySaturatedFraction: Double,
    val meanStateTanhDerivative: Double,
    val candidateActivationValues: Int,
    val candidateNearSaturatedValues: Int,
    val candidateNearSaturatedFraction: Double,
    val candidateExactlySaturatedValues: Int,
    val candidateExactlySaturatedFraction: Double,
    val meanCandidateTanhDerivative: Double,
    val effectiveHiddenInputRealizability: NeuralFullInputRealizability,
    val hardDecisionsInRepeatedProjectedStateGroups: Int,
    val repeatedProjectedStateGroupsContainingHardDecisions: List<NeuralRepeatedStateGroup>,
)

@Serializable
internal data class NeuralPreActivationDistribution(
    val values: Int,
    val meanAbsolutePreActivation: Double,
    val rmsPreActivation: Double,
    val medianAbsolutePreActivation: Double,
    val p90AbsolutePreActivation: Double,
    val p99AbsolutePreActivation: Double,
    val maximumAbsolutePreActivation: Double,
    val nearSaturatedValues: Int,
    val nearSaturatedFraction: Double,
    val exactlySaturatedValues: Int,
    val exactlySaturatedFraction: Double,
    val meanTanhDerivative: Double,
    val derivativeAtMostOnePercentValues: Int,
    val derivativeAtMostOnePercentFraction: Double,
)

@Serializable
internal data class NeuralParameterScaleSummary(
    val stateWeightRms: Double,
    val stateWeightMaximumAbsolute: Double,
    val stateBiasRms: Double,
    val stateBiasMaximumAbsolute: Double,
    val candidateWeightRms: Double,
    val candidateWeightMaximumAbsolute: Double,
    val candidateBiasRms: Double,
    val candidateBiasMaximumAbsolute: Double,
    val globalQueryRms: Double,
    val globalQueryMaximumAbsolute: Double,
)

@Serializable
internal data class NeuralProjectionCollapseSummary(
    val distinctProjectedStates: Int,
    val repeatedProjectedStateGroups: Int,
    val decisionsInRepeatedProjectedStateGroups: Int,
    val largestRepeatedProjectedStateGroup: Int,
    val contradictoryRankingComponents: Int,
    val decisionsAffectedByContradictions: Int,
)

/**
 * Exact learned candidate aliases inside one current decision. A learned collapse requires at
 * least two distinct raw candidate vectors to share an exact 32-value projected vector; raw
 * candidate aliases are counted separately and are not attributed to training.
 */
@Serializable
internal data class NeuralCandidateProjectionCollapseSummary(
    val decisionsWithLearnedCandidateCollapse: Int = 0,
    val learnedCandidateCollapseGroups: Int = 0,
    val candidateOccurrencesInLearnedCollapseGroups: Int = 0,
    val largestLearnedCandidateCollapseGroup: Int = 0,
    val teacherLabelsInLearnedCandidateCollapseGroups: Int = 0,
    val rawCandidateAliasGroups: Int = 0,
)

@Serializable
internal data class NeuralProjectionTracePoint(
    val epoch: Int,
    val meanCrossEntropy: Double,
    val strictRankingCorrect: Int,
    val strictRankingAccuracy: Double,
    val minimumTeacherMargin: Double,
    val state: NeuralPreActivationDistribution,
    val candidate: NeuralPreActivationDistribution,
    val parameters: NeuralParameterScaleSummary,
    val collapse: NeuralProjectionCollapseSummary,
    val candidateCollapse: NeuralCandidateProjectionCollapseSummary =
        NeuralCandidateProjectionCollapseSummary(),
)

internal data class NeuralProjectionValues(
    val preActivations: DoubleArray,
    val activations: DoubleArray,
)

private data class ActivationSummary(
    val values: Int,
    val nearSaturated: Int,
    val exactlySaturated: Int,
    val meanDerivative: Double,
) {
    val nearSaturatedFraction: Double get() = nearSaturated.toDouble() / values
    val exactlySaturatedFraction: Double get() = exactlySaturated.toDouble() / values
}

private data class ExactScorerInputKey(
    val state: String,
    val candidate: String,
)

private data class ExactDecisionInputKey(
    val state: String,
    val candidates: List<String>,
)

private data class RankingConstraint(
    val loser: Int,
    val winner: Int,
    val decision: NeuralDecisionReference,
    val alternativeCandidateIndex: Int,
    val teacherCandidateIndex: Int,
)

internal fun auditNeuralFullInputRealizability(
    decisions: List<EncodedBcDecision>,
): NeuralFullInputRealizability {
    require(decisions.isNotEmpty() && decisions.all { it.candidateCount >= PRIMARY_MIN_CANDIDATES })
    require(decisions.distinctBy { it.gameId to it.decisionIndex }.size == decisions.size)

    val ordered = decisions.sortedWith(compareBy(EncodedBcDecision::gameId, EncodedBcDecision::decisionIndex))
    val stateKeys = ordered.associateWith { neuralBcVectorKey(it.state) }
    val candidateKeys = ordered.associateWith { decision -> decision.candidates.map(::neuralBcVectorKey) }
    val inputKeys = linkedSetOf<ExactScorerInputKey>()
    ordered.forEach { decision ->
        candidateKeys.getValue(decision).forEach { candidate ->
            inputKeys += ExactScorerInputKey(stateKeys.getValue(decision), candidate)
        }
    }
    val inputIds = inputKeys.associateWith { key ->
        PolicyJson.sha256("${key.state}\u0000${key.candidate}")
    }
    require(inputIds.values.distinct().size == inputIds.size) {
        "Exact scorer-input evidence ids unexpectedly collided"
    }

    val occurrences = linkedMapOf<ExactScorerInputKey, MutableList<NeuralScorerInputOccurrence>>()
    ordered.forEach { decision ->
        val reference = decision.neuralDecisionReference()
        candidateKeys.getValue(decision).indices.forEach { candidateIndex ->
            val key = ExactScorerInputKey(
                state = stateKeys.getValue(decision),
                candidate = candidateKeys.getValue(decision)[candidateIndex],
            )
            occurrences.getOrPut(key, ::mutableListOf) += NeuralScorerInputOccurrence(
                decision = reference,
                candidateIndex = candidateIndex,
                teacherCandidate = candidateIndex == decision.labelIndex,
            )
        }
    }
    val duplicateScorerInputs = occurrences.filterValues { it.size > 1 }.map { (key, values) ->
        NeuralDuplicateScorerInputGroup(
            scorerInputId = inputIds.getValue(key),
            occurrences = values.sortedWith(
                compareBy(
                    { it.decision.gameId },
                    { it.decision.decisionIndex },
                    NeuralScorerInputOccurrence::candidateIndex,
                )
            ),
        )
    }.sortedBy(NeuralDuplicateScorerInputGroup::scorerInputId)

    val stateGroups = ordered.groupBy { stateKeys.getValue(it) }
    val repeatedStates = stateGroups.filterValues { it.size > 1 }.map { (key, rows) ->
        NeuralRepeatedStateGroup(
            encodedStateId = PolicyJson.sha256(key),
            decisions = rows.map(EncodedBcDecision::neuralDecisionReference),
        )
    }.sortedBy(NeuralRepeatedStateGroup::encodedStateId)

    val decisionGroups = ordered.groupBy { decision ->
        ExactDecisionInputKey(stateKeys.getValue(decision), candidateKeys.getValue(decision))
    }
    val duplicateDecisionInputs = decisionGroups.filterValues { it.size > 1 }.map { (key, rows) ->
        NeuralDuplicateDecisionInputGroup(
            decisionInputId = PolicyJson.sha256(
                key.state + "\u0000" + key.candidates.joinToString("\u0001")
            ),
            decisions = rows.map(EncodedBcDecision::neuralDecisionReference),
            teacherLabelIndices = rows.map(EncodedBcDecision::labelIndex),
        )
    }.sortedBy(NeuralDuplicateDecisionInputGroup::decisionInputId)

    val nodeKeys = inputKeys.toList()
    val nodeIndex = nodeKeys.withIndex().associate { (index, key) -> key to index }
    val constraints = ordered.flatMap { decision ->
        val keys = candidateKeys.getValue(decision)
        val state = stateKeys.getValue(decision)
        val winner = nodeIndex.getValue(ExactScorerInputKey(state, keys[decision.labelIndex]))
        decision.candidates.indices.filter { it != decision.labelIndex }.map { alternative ->
            RankingConstraint(
                loser = nodeIndex.getValue(ExactScorerInputKey(state, keys[alternative])),
                winner = winner,
                decision = decision.neuralDecisionReference(),
                alternativeCandidateIndex = alternative,
                teacherCandidateIndex = decision.labelIndex,
            )
        }
    }
    val adjacency = List(nodeKeys.size) { linkedSetOf<Int>() }
    constraints.forEach { constraint -> adjacency[constraint.loser] += constraint.winner }
    val components = stronglyConnectedComponents(adjacency)
    val contradictoryComponents = components.filter { component ->
        component.size > 1 || component.any { node -> node in adjacency[node] }
    }.map { component ->
        val nodes = component.toSet()
        val componentConstraints = constraints.filter { it.loser in nodes && it.winner in nodes }
        NeuralContradictoryRankingComponent(
            scorerInputIds = component.map { inputIds.getValue(nodeKeys[it]) }.sorted(),
            constraints = componentConstraints.map { it.evidence(nodeKeys, inputIds) }
                .sortedWith(compareBy({ it.decision.gameId }, { it.decision.decisionIndex })),
            affectedDecisions = componentConstraints.map(RankingConstraint::decision).distinct()
                .sortedWith(compareBy(NeuralDecisionReference::gameId, NeuralDecisionReference::decisionIndex)),
        )
    }.sortedBy { it.scorerInputIds.first() }
    val contradictoryDecisions = contradictoryComponents.flatMap { it.affectedDecisions }.distinct()
    val consistent = contradictoryComponents.isEmpty()

    val constructiveScores = if (consistent) longestPathScores(adjacency, nodeKeys, inputIds) else null
    val constructiveMargins = if (constructiveScores != null) ordered.map { decision ->
        val state = stateKeys.getValue(decision)
        val keys = candidateKeys.getValue(decision)
        val teacherScore = constructiveScores[nodeIndex.getValue(ExactScorerInputKey(state, keys[decision.labelIndex]))]
        val alternativeScore = decision.candidates.indices.filter { it != decision.labelIndex }.maxOf { alternative ->
            constructiveScores[nodeIndex.getValue(ExactScorerInputKey(state, keys[alternative]))]
        }
        teacherScore - alternativeScore
    } else emptyList()
    val constructiveCorrect = constructiveMargins.count { it > 0 }

    return NeuralFullInputRealizability(
        trainingDecisions = ordered.size,
        candidateOccurrences = ordered.sumOf(EncodedBcDecision::candidateCount),
        distinctEncodedDecisionInputs = decisionGroups.size,
        exactDuplicateDecisionInputGroups = duplicateDecisionInputs.size,
        decisionsInExactDuplicateInputGroups = duplicateDecisionInputs.sumOf { it.decisions.size },
        duplicateDecisionInputs = duplicateDecisionInputs,
        distinctEncodedStates = stateGroups.size,
        repeatedEncodedStateGroups = repeatedStates.size,
        decisionsWithRepeatedEncodedState = repeatedStates.flatMap { it.decisions }.distinct().size,
        repeatedEncodedStates = repeatedStates,
        distinctScorerInputs = inputKeys.size,
        exactDuplicateScorerInputGroups = duplicateScorerInputs.size,
        scorerInputOccurrencesInDuplicateGroups = duplicateScorerInputs.sumOf { it.occurrences.size },
        decisionsWithDuplicateScorerInputs = duplicateScorerInputs.flatMap { group ->
            group.occurrences.map(NeuralScorerInputOccurrence::decision)
        }.distinct().size,
        duplicateScorerInputs = duplicateScorerInputs,
        rankingConstraints = constraints.size,
        distinctRankingConstraints = adjacency.sumOf { it.size },
        selfContradictoryConstraints = constraints.count { it.loser == it.winner },
        contradictoryRankingComponents = contradictoryComponents,
        contradictoryConstraints = contradictoryComponents.sumOf { it.constraints.size },
        decisionsAffectedByContradictions = contradictoryDecisions.size,
        unrestrictedDeterministicScorerIsConsistent = consistent,
        constructiveStrictRankingCorrect = constructiveCorrect,
        constructiveStrictRankingAccuracy = constructiveCorrect.toDouble() / ordered.size,
        constructiveMinimumTeacherMargin = constructiveMargins.minOrNull(),
    )
}

private fun RankingConstraint.evidence(
    nodeKeys: List<ExactScorerInputKey>,
    inputIds: Map<ExactScorerInputKey, String>,
): NeuralRankingConstraintEvidence = NeuralRankingConstraintEvidence(
    loserScorerInputId = inputIds.getValue(nodeKeys[loser]),
    winnerScorerInputId = inputIds.getValue(nodeKeys[winner]),
    decision = decision,
    alternativeCandidateIndex = alternativeCandidateIndex,
    teacherCandidateIndex = teacherCandidateIndex,
)

private fun stronglyConnectedComponents(adjacency: List<Set<Int>>): List<List<Int>> {
    var nextIndex = 0
    val index = IntArray(adjacency.size) { -1 }
    val lowLink = IntArray(adjacency.size)
    val stack = ArrayDeque<Int>()
    val onStack = BooleanArray(adjacency.size)
    val result = mutableListOf<List<Int>>()

    fun visit(node: Int) {
        index[node] = nextIndex
        lowLink[node] = nextIndex
        nextIndex++
        stack.addLast(node)
        onStack[node] = true
        adjacency[node].forEach { target ->
            if (index[target] == -1) {
                visit(target)
                lowLink[node] = minOf(lowLink[node], lowLink[target])
            } else if (onStack[target]) {
                lowLink[node] = minOf(lowLink[node], index[target])
            }
        }
        if (lowLink[node] == index[node]) {
            val component = mutableListOf<Int>()
            var member: Int
            do {
                member = stack.removeLast()
                onStack[member] = false
                component += member
            } while (member != node)
            result += component
        }
    }

    adjacency.indices.forEach { node -> if (index[node] == -1) visit(node) }
    return result
}

private fun longestPathScores(
    adjacency: List<Set<Int>>,
    nodeKeys: List<ExactScorerInputKey>,
    inputIds: Map<ExactScorerInputKey, String>,
): IntArray {
    val indegree = IntArray(adjacency.size)
    adjacency.forEach { targets -> targets.forEach { indegree[it]++ } }
    val ready = PriorityQueue<Int>(compareBy { inputIds.getValue(nodeKeys[it]) })
    indegree.indices.filter { indegree[it] == 0 }.forEach(ready::add)
    val scores = IntArray(adjacency.size)
    var visited = 0
    while (ready.isNotEmpty()) {
        val node = ready.remove()
        visited++
        adjacency[node].forEach { target ->
            scores[target] = max(scores[target], scores[node] + 1)
            indegree[target]--
            if (indegree[target] == 0) ready += target
        }
    }
    require(visited == adjacency.size) { "Cannot construct scores for a cyclic ranking graph" }
    return scores
}

/**
 * The single post-ladder issue-0025 diagnostic. It applies the saved bilinear model's exact tanh
 * projections to the nested subset, then asks whether saturation has collapsed distinct encoded
 * states or scorer inputs. The raw pre-projection audit remains the semantic authority; this is a
 * learned training-dynamics observation, not a new feature-ceiling definition.
 */
internal fun auditNeuralBcActivationSaturation(
    policy: CandidateConditionedNeuralPolicy,
    decisions: List<EncodedBcDecision>,
    hardDecisions: Set<Pair<String, Int>>,
): NeuralActivationSaturationSeedAudit {
    require(decisions.isNotEmpty())
    val artifact = policy.artifact
    val config = artifact.config
    val stateActivations = decisions.associateWith { decision ->
        neuralBcDiagnosticProjection(
            vector = decision.state,
            weights = artifact.stateWeights,
            bias = artifact.stateBias,
            inputDimension = config.stateDimension,
            hiddenDimension = config.hiddenDimension,
        ).activations
    }
    val candidateActivations = decisions.associateWith { decision ->
        decision.candidates.map { candidate ->
            neuralBcDiagnosticProjection(
                vector = candidate,
                weights = artifact.candidateWeights,
                bias = artifact.candidateBias,
                inputDimension = config.candidateDimension,
                hiddenDimension = config.hiddenDimension,
            ).activations
        }
    }
    val projected = decisions.map { decision ->
        val query = stateActivations.getValue(decision).copyOf()
        query.indices.forEach { query[it] += artifact.globalQuery[it] }
        decision.copy(
            state = denseDiagnosticVector(query),
            candidates = candidateActivations.getValue(decision).map(::denseDiagnosticVector),
        )
    }
    val effectiveAudit = auditNeuralFullInputRealizability(projected)
    val hardGroups = effectiveAudit.repeatedEncodedStates.filter { group ->
        group.decisions.any { it.gameId to it.decisionIndex in hardDecisions }
    }
    val hardInGroups = hardGroups.flatMap(NeuralRepeatedStateGroup::decisions).filter {
        it.gameId to it.decisionIndex in hardDecisions
    }.distinct().size
    val stateSummary = activationSummary(stateActivations.values.asSequence().flatMap { it.asSequence() })
    val candidateSummary = activationSummary(
        candidateActivations.values.asSequence().flatMap { candidates ->
            candidates.asSequence().flatMap { it.asSequence() }
        }
    )
    return NeuralActivationSaturationSeedAudit(
        subsetDecisions = decisions.size,
        seed = artifact.trainingSeed,
        modelEpoch = artifact.bestEpoch,
        stateActivationValues = stateSummary.values,
        stateNearSaturatedValues = stateSummary.nearSaturated,
        stateNearSaturatedFraction = stateSummary.nearSaturatedFraction,
        stateExactlySaturatedValues = stateSummary.exactlySaturated,
        stateExactlySaturatedFraction = stateSummary.exactlySaturatedFraction,
        meanStateTanhDerivative = stateSummary.meanDerivative,
        candidateActivationValues = candidateSummary.values,
        candidateNearSaturatedValues = candidateSummary.nearSaturated,
        candidateNearSaturatedFraction = candidateSummary.nearSaturatedFraction,
        candidateExactlySaturatedValues = candidateSummary.exactlySaturated,
        candidateExactlySaturatedFraction = candidateSummary.exactlySaturatedFraction,
        meanCandidateTanhDerivative = candidateSummary.meanDerivative,
        effectiveHiddenInputRealizability = effectiveAudit,
        hardDecisionsInRepeatedProjectedStateGroups = hardInGroups,
        repeatedProjectedStateGroupsContainingHardDecisions = hardGroups,
    )
}

internal fun neuralBcDiagnosticProjection(
    vector: SparseFeatureVector,
    weights: DoubleArray,
    bias: DoubleArray,
    inputDimension: Int,
    hiddenDimension: Int,
): NeuralProjectionValues {
    val preActivations = DoubleArray(hiddenDimension) { hidden ->
        var value = bias[hidden]
        val offset = hidden * inputDimension
        vector.indices.indices.forEach { position ->
            value += weights[offset + vector.indices[position]] * vector.values[position]
        }
        value
    }
    return NeuralProjectionValues(
        preActivations = preActivations,
        activations = DoubleArray(hiddenDimension) { tanh(preActivations[it]) },
    )
}

internal fun traceNeuralBcProjection(
    policy: CandidateConditionedNeuralPolicy,
    decisions: List<EncodedBcDecision>,
    metric: NeuralMemorizationEpochMetric,
): NeuralProjectionTracePoint {
    require(decisions.isNotEmpty())
    val artifact = policy.artifact
    val config = artifact.config
    val states = decisions.associateWith { decision ->
        neuralBcDiagnosticProjection(
            decision.state,
            artifact.stateWeights,
            artifact.stateBias,
            config.stateDimension,
            config.hiddenDimension,
        )
    }
    val candidates = decisions.associateWith { decision ->
        decision.candidates.map { candidate ->
            neuralBcDiagnosticProjection(
                candidate,
                artifact.candidateWeights,
                artifact.candidateBias,
                config.candidateDimension,
                config.hiddenDimension,
            )
        }
    }
    val projected = decisions.map { decision ->
        val query = states.getValue(decision).activations.copyOf()
        query.indices.forEach { query[it] += artifact.globalQuery[it] }
        decision.copy(
            state = denseDiagnosticVector(query),
            candidates = candidates.getValue(decision).map { projection ->
                denseDiagnosticVector(projection.activations)
            },
        )
    }
    val effective = auditNeuralFullInputRealizability(projected)
    val repeated = effective.repeatedEncodedStates
    val candidateCollapse = candidateProjectionCollapse(decisions, candidates)
    return NeuralProjectionTracePoint(
        epoch = metric.epoch,
        meanCrossEntropy = metric.meanCrossEntropy,
        strictRankingCorrect = metric.strictRankingCorrect,
        strictRankingAccuracy = metric.strictRankingAccuracy,
        minimumTeacherMargin = metric.minimumTeacherMargin,
        state = preActivationDistribution(states.values.asSequence().flatMap {
            it.preActivations.asSequence()
        }),
        candidate = preActivationDistribution(candidates.values.asSequence().flatMap { projections ->
            projections.asSequence().flatMap { it.preActivations.asSequence() }
        }),
        parameters = parameterScaleSummary(artifact),
        collapse = NeuralProjectionCollapseSummary(
            distinctProjectedStates = effective.distinctEncodedStates,
            repeatedProjectedStateGroups = effective.repeatedEncodedStateGroups,
            decisionsInRepeatedProjectedStateGroups = effective.decisionsWithRepeatedEncodedState,
            largestRepeatedProjectedStateGroup = repeated.maxOfOrNull { it.decisions.size } ?: 0,
            contradictoryRankingComponents = effective.contradictoryRankingComponents.size,
            decisionsAffectedByContradictions = effective.decisionsAffectedByContradictions,
        ),
        candidateCollapse = candidateCollapse,
    )
}

private fun candidateProjectionCollapse(
    decisions: List<EncodedBcDecision>,
    projections: Map<EncodedBcDecision, List<NeuralProjectionValues>>,
): NeuralCandidateProjectionCollapseSummary {
    var decisionsWithCollapse = 0
    var learnedGroups = 0
    var occurrences = 0
    var largest = 0
    var teacherLabels = 0
    var rawAliasGroups = 0
    decisions.forEach { decision ->
        val rawKeys = decision.candidates.map(::neuralBcVectorKey)
        val projectedKeys = projections.getValue(decision).map { projection ->
            neuralBcVectorKey(denseDiagnosticVector(projection.activations))
        }
        val repeatedGroups = projectedKeys.indices.groupBy { projectedKeys[it] }.values.filter { it.size > 1 }
        rawAliasGroups += repeatedGroups.count { group -> group.map { rawKeys[it] }.distinct().size == 1 }
        val learned = repeatedGroups.filter { group -> group.map { rawKeys[it] }.distinct().size > 1 }
        if (learned.isNotEmpty()) decisionsWithCollapse++
        learnedGroups += learned.size
        occurrences += learned.sumOf(List<Int>::size)
        largest = maxOf(largest, learned.maxOfOrNull(List<Int>::size) ?: 0)
        if (learned.any { decision.labelIndex in it }) teacherLabels++
    }
    return NeuralCandidateProjectionCollapseSummary(
        decisionsWithLearnedCandidateCollapse = decisionsWithCollapse,
        learnedCandidateCollapseGroups = learnedGroups,
        candidateOccurrencesInLearnedCollapseGroups = occurrences,
        largestLearnedCandidateCollapseGroup = largest,
        teacherLabelsInLearnedCandidateCollapseGroups = teacherLabels,
        rawCandidateAliasGroups = rawAliasGroups,
    )
}

private fun preActivationDistribution(values: Sequence<Double>): NeuralPreActivationDistribution {
    val preActivations = values.toList()
    require(preActivations.isNotEmpty() && preActivations.all(Double::isFinite))
    val absolute = preActivations.map(::abs).sorted()
    val activations = preActivations.map(::tanh)
    val derivatives = activations.map { 1.0 - it * it }
    val near = activations.count { abs(it) >= 0.99 }
    val exact = activations.count { abs(it) == 1.0 }
    val weakDerivative = derivatives.count { it <= 0.01 }
    fun nearestRank(quantile: Double): Double {
        val index = ((absolute.size - 1) * quantile).toInt()
        return absolute[index]
    }
    return NeuralPreActivationDistribution(
        values = preActivations.size,
        meanAbsolutePreActivation = absolute.average(),
        rmsPreActivation = sqrt(preActivations.sumOf { it * it } / preActivations.size),
        medianAbsolutePreActivation = nearestRank(0.50),
        p90AbsolutePreActivation = nearestRank(0.90),
        p99AbsolutePreActivation = nearestRank(0.99),
        maximumAbsolutePreActivation = absolute.last(),
        nearSaturatedValues = near,
        nearSaturatedFraction = near.toDouble() / preActivations.size,
        exactlySaturatedValues = exact,
        exactlySaturatedFraction = exact.toDouble() / preActivations.size,
        meanTanhDerivative = derivatives.average(),
        derivativeAtMostOnePercentValues = weakDerivative,
        derivativeAtMostOnePercentFraction = weakDerivative.toDouble() / preActivations.size,
    )
}

private fun parameterScaleSummary(artifact: NeuralBcModelArtifact): NeuralParameterScaleSummary =
    NeuralParameterScaleSummary(
        stateWeightRms = rms(artifact.stateWeights),
        stateWeightMaximumAbsolute = artifact.stateWeights.maxOf(::abs),
        stateBiasRms = rms(artifact.stateBias),
        stateBiasMaximumAbsolute = artifact.stateBias.maxOf(::abs),
        candidateWeightRms = rms(artifact.candidateWeights),
        candidateWeightMaximumAbsolute = artifact.candidateWeights.maxOf(::abs),
        candidateBiasRms = rms(artifact.candidateBias),
        candidateBiasMaximumAbsolute = artifact.candidateBias.maxOf(::abs),
        globalQueryRms = rms(artifact.globalQuery),
        globalQueryMaximumAbsolute = artifact.globalQuery.maxOf(::abs),
    )

private fun rms(values: DoubleArray): Double = sqrt(values.sumOf { it * it } / values.size)

private fun denseDiagnosticVector(values: DoubleArray): SparseFeatureVector = SparseFeatureVector(
    indices = values.indices.toList().toIntArray(),
    values = values,
)

private fun activationSummary(values: Sequence<Double>): ActivationSummary {
    var count = 0
    var near = 0
    var exact = 0
    var derivative = 0.0
    values.forEach { value ->
        count++
        val magnitude = abs(value)
        if (magnitude >= 0.99) near++
        if (magnitude == 1.0) exact++
        derivative += 1.0 - value * value
    }
    require(count > 0)
    return ActivationSummary(count, near, exact, derivative / count)
}

internal fun deterministicNeuralMemorizationOrder(
    decisions: List<EncodedBcDecision>,
    datasetIdentity: String,
    protocol: String,
): List<EncodedBcDecision> = decisions.sortedWith(
    compareBy<EncodedBcDecision> {
        PolicyJson.sha256("$protocol\u0000$datasetIdentity\u0000${it.gameId}\u0000${it.decisionIndex}")
    }.thenBy(EncodedBcDecision::gameId).thenBy(EncodedBcDecision::decisionIndex)
)

@Serializable
internal data class NeuralMemorizationStageConfig(
    val decisions: Int,
    val maximumEpochs: Int,
) {
    init {
        require(decisions > 0 && maximumEpochs > 0)
    }
}

@Serializable
internal data class NeuralMemorizationEpochMetric(
    val epoch: Int,
    val meanCrossEntropy: Double,
    val strictRankingCorrect: Int,
    val strictRankingAccuracy: Double,
    val productionTieBreakCorrect: Int,
    val productionTieBreakAccuracy: Double,
    val minimumTeacherMargin: Double,
)

@Serializable
internal data class NeuralMemorizationDecisionFit(
    val decision: NeuralDecisionReference,
    val teacherCandidateIndex: Int,
    val predictedCandidateIndex: Int,
    val teacherIntent: String,
    val predictedIntent: String,
    val meanCrossEntropyContribution: Double,
    val teacherMargin: Double,
    val strictRankingCorrect: Boolean,
)

internal data class TrainedNeuralMemorizationModel(
    val policy: CandidateConditionedNeuralPolicy,
    val finalPolicy: CandidateConditionedNeuralPolicy,
    val epochsCompleted: Int,
    val stoppedAfterPerfectConfirmation: Boolean,
    val firstPerfectEpoch: Int?,
    val bestEpoch: Int,
    val bestStrictRankingCorrect: Int,
    val bestStrictRankingAccuracy: Double,
    val bestProductionTieBreakAccuracy: Double,
    val bestMeanCrossEntropy: Double,
    val bestMinimumTeacherMargin: Double,
    val finalStrictRankingAccuracy: Double,
    val finalMeanCrossEntropy: Double,
    val minimumObservedMeanCrossEntropy: Double,
    val firstEpochAtBestAccuracy: Int,
    val lossAtFirstBestAccuracy: Double,
    val minimumLossAfterFirstBestAccuracy: Double,
    val lossImprovementAfterAccuracyStalled: Double,
    val lossContinuedImprovingAfterAccuracyStalled: Boolean,
    val epochMetrics: List<NeuralMemorizationEpochMetric>,
    val retainedHardDecisions: List<NeuralMemorizationDecisionFit>,
    val optimizerUpdateExposure: SparseAdamUpdateExposure,
)

private data class MemorizationSnapshot(
    val meanLoss: Double,
    val strictCorrect: Int,
    val productionCorrect: Int,
    val minimumMargin: Double,
    val decisions: List<NeuralMemorizationDecisionFit>,
)

/** The issue-0022 bilinear model and exact SparseAdam updates, without validation selection. */
internal class NeuralBcMemorizationTrainer(
    private val modelConfig: NeuralBcModelConfig,
    private val trainingConfig: NeuralBcTrainingConfig,
    private val perfectConfirmationEpochs: Int = 20,
) {
    init {
        require(perfectConfirmationEpochs > 0)
    }

    fun train(
        decisions: List<EncodedBcDecision>,
        seed: Long,
        epochObserver: ((CandidateConditionedNeuralPolicy, NeuralMemorizationEpochMetric) -> Unit)? = null,
    ): TrainedNeuralMemorizationModel {
        require(decisions.isNotEmpty() && decisions.all { it.candidateCount >= PRIMARY_MIN_CANDIDATES })
        var policy = CandidateConditionedNeuralPolicy.initialize(modelConfig, seed)
        val adam = SparseAdam(policy.artifact, trainingConfig)
        val history = mutableListOf<NeuralMemorizationEpochMetric>()
        var snapshot = memorizationSnapshot(policy, decisions)
        history += snapshot.metric(epoch = 0, decisions = decisions.size)
        epochObserver?.invoke(policy, history.last())
        var bestSnapshot = snapshot
        var bestArtifact = copyNeuralBcModelArtifact(policy.artifact)
        var bestEpoch = 0
        var firstPerfectEpoch: Int? = if (snapshot.strictCorrect == decisions.size) 0 else null
        var perfectStreak = 0
        var epochsCompleted = 0
        var stoppedAfterPerfectConfirmation = false

        for (epoch in 1..trainingConfig.maximumEpochs) {
            decisions.shuffled(Random(seed xor epoch.toLong())).forEach(adam::step)
            snapshot = memorizationSnapshot(policy, decisions)
            history += snapshot.metric(epoch, decisions.size)
            epochObserver?.invoke(policy, history.last())
            epochsCompleted = epoch
            if (
                snapshot.strictCorrect > bestSnapshot.strictCorrect ||
                (snapshot.strictCorrect == bestSnapshot.strictCorrect &&
                    snapshot.meanLoss < bestSnapshot.meanLoss - 1e-12)
            ) {
                bestSnapshot = snapshot
                bestArtifact = copyNeuralBcModelArtifact(policy.artifact).copy(bestEpoch = epoch)
                bestEpoch = epoch
            }
            if (snapshot.strictCorrect == decisions.size) {
                if (firstPerfectEpoch == null) firstPerfectEpoch = epoch
                perfectStreak++
                if (perfectStreak >= perfectConfirmationEpochs) {
                    stoppedAfterPerfectConfirmation = true
                    break
                }
            } else {
                perfectStreak = 0
            }
        }

        val finalPolicy = CandidateConditionedNeuralPolicy.fromArtifact(
            copyNeuralBcModelArtifact(policy.artifact).copy(bestEpoch = epochsCompleted)
        )
        policy = CandidateConditionedNeuralPolicy.fromArtifact(bestArtifact)
        bestSnapshot = memorizationSnapshot(policy, decisions)
        val bestCorrect = history.maxOf(NeuralMemorizationEpochMetric::strictRankingCorrect)
        val firstBestIndex = history.indexOfFirst { it.strictRankingCorrect == bestCorrect }
        val firstBest = history[firstBestIndex]
        val minimumAfter = history.drop(firstBestIndex).minOf(NeuralMemorizationEpochMetric::meanCrossEntropy)
        val improvementAfter = firstBest.meanCrossEntropy - minimumAfter
        val misranked = bestSnapshot.decisions.filterNot(NeuralMemorizationDecisionFit::strictRankingCorrect)
        val highestLoss = bestSnapshot.decisions.sortedByDescending(
            NeuralMemorizationDecisionFit::meanCrossEntropyContribution
        ).take(25)
        val retained = (misranked + highestLoss).distinctBy {
            it.decision.gameId to it.decision.decisionIndex
        }.sortedWith(
            compareByDescending<NeuralMemorizationDecisionFit> { it.meanCrossEntropyContribution }
                .thenBy { it.decision.gameId }
                .thenBy { it.decision.decisionIndex }
        )
        val final = history.last()
        return TrainedNeuralMemorizationModel(
            policy = policy,
            finalPolicy = finalPolicy,
            epochsCompleted = epochsCompleted,
            stoppedAfterPerfectConfirmation = stoppedAfterPerfectConfirmation,
            firstPerfectEpoch = firstPerfectEpoch,
            bestEpoch = bestEpoch,
            bestStrictRankingCorrect = bestSnapshot.strictCorrect,
            bestStrictRankingAccuracy = bestSnapshot.strictCorrect.toDouble() / decisions.size,
            bestProductionTieBreakAccuracy = bestSnapshot.productionCorrect.toDouble() / decisions.size,
            bestMeanCrossEntropy = bestSnapshot.meanLoss,
            bestMinimumTeacherMargin = bestSnapshot.minimumMargin,
            finalStrictRankingAccuracy = final.strictRankingAccuracy,
            finalMeanCrossEntropy = final.meanCrossEntropy,
            minimumObservedMeanCrossEntropy = history.minOf(NeuralMemorizationEpochMetric::meanCrossEntropy),
            firstEpochAtBestAccuracy = firstBest.epoch,
            lossAtFirstBestAccuracy = firstBest.meanCrossEntropy,
            minimumLossAfterFirstBestAccuracy = minimumAfter,
            lossImprovementAfterAccuracyStalled = improvementAfter,
            lossContinuedImprovingAfterAccuracyStalled = improvementAfter > 1e-6,
            epochMetrics = history,
            retainedHardDecisions = retained,
            optimizerUpdateExposure = adam.updateExposureSnapshot(),
        )
    }
}

private fun memorizationSnapshot(
    policy: NeuralBcScoringPolicy,
    decisions: List<EncodedBcDecision>,
): MemorizationSnapshot {
    val fits = decisions.map { decision -> neuralMemorizationDecisionFit(policy, decision) }
    return MemorizationSnapshot(
        meanLoss = fits.map(NeuralMemorizationDecisionFit::meanCrossEntropyContribution).average(),
        strictCorrect = fits.count(NeuralMemorizationDecisionFit::strictRankingCorrect),
        productionCorrect = fits.count { it.teacherCandidateIndex == it.predictedCandidateIndex },
        minimumMargin = fits.minOf(NeuralMemorizationDecisionFit::teacherMargin),
        decisions = fits,
    )
}

internal fun neuralMemorizationDecisionFit(
    policy: NeuralBcScoringPolicy,
    decision: EncodedBcDecision,
): NeuralMemorizationDecisionFit {
    val scores = policy.scores(decision)
    require(scores.all(Double::isFinite)) {
        "Non-finite memorization score at ${decision.gameId}:${decision.decisionIndex}"
    }
    val teacherScore = scores[decision.labelIndex]
    val alternative = scores.indices.filter { it != decision.labelIndex }.maxBy { scores[it] }
    val predicted = scores.indices.maxBy { scores[it] }
    return NeuralMemorizationDecisionFit(
        decision = decision.neuralDecisionReference(),
        teacherCandidateIndex = decision.labelIndex,
        predictedCandidateIndex = predicted,
        teacherIntent = decision.candidateIntents[decision.labelIndex].name,
        predictedIntent = decision.candidateIntents[predicted].name,
        meanCrossEntropyContribution = neuralBcCrossEntropy(scores, decision.labelIndex),
        teacherMargin = teacherScore - scores[alternative],
        strictRankingCorrect = teacherScore > scores[alternative],
    )
}

private fun MemorizationSnapshot.metric(
    epoch: Int,
    decisions: Int,
): NeuralMemorizationEpochMetric = NeuralMemorizationEpochMetric(
    epoch = epoch,
    meanCrossEntropy = meanLoss,
    strictRankingCorrect = strictCorrect,
    strictRankingAccuracy = strictCorrect.toDouble() / decisions,
    productionTieBreakCorrect = productionCorrect,
    productionTieBreakAccuracy = productionCorrect.toDouble() / decisions,
    minimumTeacherMargin = minimumMargin,
)
