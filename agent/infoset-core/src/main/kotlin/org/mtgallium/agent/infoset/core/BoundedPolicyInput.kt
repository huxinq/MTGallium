package org.mtgallium.agent.infoset.core

import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

const val BOUNDED_POLICY_INPUT_SCHEMA_V1: Int = 1
const val BOUNDED_POLICY_INPUT_SCHEMA_V2: Int = 2
const val BOUNDED_POLICY_INPUT_SCHEMA_V3: Int = 3
const val BOUNDED_POLICY_INPUT_SCHEMA_V4: Int = 4
const val BOUNDED_POLICY_INPUT_SCHEMA_V5: Int = 5
const val BOUNDED_POLICY_INPUT_SCHEMA_CURRENT: Int = BOUNDED_POLICY_INPUT_SCHEMA_V5
const val POLICY_BELIEF_SUMMARY_SCHEMA_V1: Int = 1

/** A canonical, bounded projection of belief diagnostics; complete particles never cross this boundary. */
@Serializable
data class PolicyBeliefSummary(
    val schemaVersion: Int = POLICY_BELIEF_SUMMARY_SCHEMA_V1,
    val architecture: BeliefArchitecture?,
    val mode: BeliefMode?,
    val knowledgeDigest: String,
    /** Named, information-safe probabilities such as card marginals or exact stratum masses. */
    val probabilities: Map<String, Double> = emptyMap(),
) {
    init {
        require(schemaVersion == POLICY_BELIEF_SUMMARY_SCHEMA_V1) { "Unknown policy-belief schema $schemaVersion" }
        require(probabilities.size <= MAX_PROBABILITIES) {
            "Belief summary has ${probabilities.size} probabilities; maximum is $MAX_PROBABILITIES"
        }
        require(probabilities.keys.all { it.isNotBlank() }) { "Belief-summary feature names must not be blank" }
        require(probabilities.values.all { it.isFinite() && it in 0.0..1.0 }) {
            "Belief-summary probabilities must be finite values in [0, 1]"
        }
        require(probabilities.keys.toList() == probabilities.keys.sorted()) {
            "Belief-summary probabilities must be canonically ordered"
        }
    }

    companion object {
        const val MAX_PROBABILITIES: Int = 128

        fun exactOnly(knowledgeDigest: String): PolicyBeliefSummary = PolicyBeliefSummary(
            architecture = null,
            mode = null,
            knowledgeDigest = knowledgeDigest,
        )

        fun from(diagnostics: BeliefDiagnostics, fallbackKnowledgeDigest: String): PolicyBeliefSummary {
            val probabilities = buildMap {
                diagnostics.marginalCardProbabilities.forEach { (id, probability) ->
                    put("marginal:$id", canonicalProbability("marginal:$id", probability))
                }
                diagnostics.strata.forEach { stratum ->
                    val feature = "stratum:${stratum.id}"
                    put(feature, canonicalProbability(feature, stratum.exactMass))
                }
            }.toSortedMap()
            require(probabilities.size <= MAX_PROBABILITIES) {
                "Belief diagnostics expose ${probabilities.size} probabilities; maximum is $MAX_PROBABILITIES"
            }
            return PolicyBeliefSummary(
                architecture = diagnostics.architecture,
                mode = diagnostics.mode,
                knowledgeDigest = diagnostics.knowledgeDigest ?: fallbackKnowledgeDigest,
                probabilities = probabilities,
            )
        }

        private fun canonicalProbability(feature: String, value: Double): Double {
            require(value.isFinite() && value >= -PROBABILITY_EPSILON && value <= 1.0 + PROBABILITY_EPSILON) {
                "Belief feature $feature has invalid probability $value"
            }
            return value.coerceIn(0.0, 1.0)
        }

        private const val PROBABILITY_EPSILON: Double = 1e-12
    }
}

/**
 * The only DTO intended for a bounded neural policy.
 *
 * The authoritative safe ledger remains in [PolicyInformationState] and durable per-game storage.
 * This object carries exact current knowledge, a bounded belief projection, a recent event suffix,
 * and a bounded semantic candidate family. It deliberately contains no learned recurrent state yet.
 */
@Serializable
data class BoundedPolicyInput(
    val schemaVersion: Int = BOUNDED_POLICY_INPUT_SCHEMA_CURRENT,
    val actingPlayerId: String?,
    val observation: PolicyObservation,
    val knowledge: PolicyKnowledgeState,
    val belief: PolicyBeliefSummary,
    val recentEvents: List<PolicyHistoryEvent>,
    val recentEventStartCursor: Int,
    val historyCommitment: PolicyHistoryCommitment,
    val informationStateDigest: String,
    val candidates: List<SemanticChoice>,
    val candidateSchemaVersion: Int,
    val terminated: Boolean,
    val winnerId: String?,
    val inputDigest: String,
) {
    init {
        require(schemaVersion == BOUNDED_POLICY_INPUT_SCHEMA_CURRENT) { "Unknown bounded-policy schema $schemaVersion" }
        require(observation.currentTurnStateComplete) {
            "Current bounded-policy input requires complete current-turn rules state"
        }
        val historyCursor = historyCommitment.cursor
        require(recentEventStartCursor in 0..historyCursor) { "Recent-event cursor is outside the ledger prefix" }
        require(recentEvents.size == historyCursor - recentEventStartCursor) {
            "Recent-event window does not match its ledger cursors"
        }
        require(candidates.map { it.signature }.distinct().size == candidates.size) {
            "Policy candidates must have unique semantic signatures"
        }
        require(belief.knowledgeDigest == knowledge.knowledgeDigest) {
            "Belief summary must bind to the exact knowledge supplied to the policy"
        }
        require(inputDigest.isEmpty() || inputDigest.matches(Regex("[0-9a-f]{64}"))) {
            "Bounded-policy digest must be empty while compiling or a lowercase SHA-256"
        }
    }

    val historyCursor: Int get() = historyCommitment.cursor
    val historyDigest: String get() = historyCommitment.digest

    /** Reconstruct the authoritative state only when the caller supplies the committed ledger. */
    fun toInformationState(ledger: List<PolicyHistoryEvent>): PolicyInformationState {
        requireValidDigest()
        require(historyCursor in 0..ledger.size) { "Bounded input cursor is outside the supplied ledger" }
        val prefix = ledger.take(historyCursor)
        val commitment = PolicyHistoryCommitment.replay(prefix)
        require(commitment == historyCommitment) { "Ledger prefix does not match bounded input history commitment" }
        require(prefix.drop(recentEventStartCursor) == recentEvents) {
            "Ledger prefix does not match bounded input recent-event window"
        }
        return PolicyInformationState(
            actingPlayerId = actingPlayerId,
            observation = observation,
            informationStateDigest = informationStateDigest,
            historyCommitment = historyCommitment,
            history = prefix,
            knowledge = knowledge,
            candidates = candidates,
            candidateSchemaVersion = candidateSchemaVersion,
            terminated = terminated,
            winnerId = winnerId,
        )
    }

    /** Fail closed if any serialized policy feature changed without rebinding the canonical digest. */
    fun requireValidDigest() {
        require(inputDigest.isNotBlank()) { "Bounded policy input has not been sealed" }
        require(inputDigest == canonicalDigest()) { "Bounded policy input digest does not match its contents" }
    }

    internal fun canonicalDigest(): String = PolicyJson.digest(
        PolicyJson.format.encodeToJsonElement(BoundedPolicyInput.serializer(), copy(inputDigest = "")),
    )
}

data class BoundedPolicyInputConfig(
    val recentEventLimit: Int = 64,
    val recentEventByteLimit: Int = 64 * 1024,
    val candidateLimit: Int = 2_048,
    val totalByteLimit: Int = 1024 * 1024,
) {
    init {
        require(recentEventLimit > 0)
        require(recentEventByteLimit > 0)
        require(candidateLimit > 0)
        require(totalByteLimit > 0)
    }
}

object BoundedPolicyInputCompiler {
    fun compile(
        information: PolicyInformationState,
        belief: PolicyBeliefSummary = PolicyBeliefSummary.exactOnly(information.knowledge.knowledgeDigest),
        config: BoundedPolicyInputConfig = BoundedPolicyInputConfig(),
    ): BoundedPolicyInput = compileWithMetrics(information, belief, config).input

    fun compileWithMetrics(
        information: PolicyInformationState,
        belief: PolicyBeliefSummary = PolicyBeliefSummary.exactOnly(information.knowledge.knowledgeDigest),
        config: BoundedPolicyInputConfig = BoundedPolicyInputConfig(),
    ): BoundedPolicyCompilation {
        require(information.candidates.size <= config.candidateLimit) {
            "Policy candidate family has ${information.candidates.size} entries; limit is ${config.candidateLimit}"
        }
        require(belief.knowledgeDigest == information.knowledge.knowledgeDigest) {
            "Belief summary does not describe the current exact knowledge state"
        }

        var bytes = 0
        var examined = 0
        val suffixReversed = mutableListOf<PolicyHistoryEvent>()
        for (event in information.history.asReversed()) {
            if (suffixReversed.size == config.recentEventLimit) break
            examined++
            val eventBytes = PolicyJson.format.encodeToString(PolicyHistoryEvent.serializer(), event)
                .toByteArray(StandardCharsets.UTF_8).size
            require(eventBytes <= config.recentEventByteLimit) {
                "One safe event requires $eventBytes bytes; window limit is ${config.recentEventByteLimit}"
            }
            if (bytes + eventBytes > config.recentEventByteLimit) break
            bytes += eventBytes
            suffixReversed += event
        }
        val recent = suffixReversed.asReversed()
        val provisional = BoundedPolicyInput(
            actingPlayerId = information.actingPlayerId,
            observation = information.observation,
            knowledge = information.knowledge,
            belief = belief,
            recentEvents = recent,
            recentEventStartCursor = information.history.size - recent.size,
            historyCommitment = information.historyCommitment,
            informationStateDigest = information.informationStateDigest,
            candidates = information.candidates,
            candidateSchemaVersion = information.candidateSchemaVersion,
            terminated = information.terminated,
            winnerId = information.winnerId,
            inputDigest = "",
        )
        val sealed = provisional.copy(inputDigest = provisional.canonicalDigest())
        val totalBytes = PolicyJson.format.encodeToString(BoundedPolicyInput.serializer(), sealed)
            .toByteArray(StandardCharsets.UTF_8).size
        require(totalBytes <= config.totalByteLimit) {
            "Bounded policy input requires $totalBytes bytes; limit is ${config.totalByteLimit}"
        }
        return BoundedPolicyCompilation(
            input = sealed,
            metrics = BoundedPolicyCompilationMetrics(
                historyCursor = information.historyCommitment.cursor,
                eventsExamined = examined,
                recentEventCount = recent.size,
                recentEventBytes = bytes,
                totalBytes = totalBytes,
            ),
        )
    }
}

data class BoundedPolicyCompilation(
    val input: BoundedPolicyInput,
    val metrics: BoundedPolicyCompilationMetrics,
)

data class BoundedPolicyCompilationMetrics(
    val historyCursor: Int,
    val eventsExamined: Int,
    val recentEventCount: Int,
    val recentEventBytes: Int,
    val totalBytes: Int,
)
