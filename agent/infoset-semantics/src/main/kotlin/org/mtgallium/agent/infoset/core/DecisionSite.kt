package org.mtgallium.agent.infoset.core

import kotlinx.serialization.json.*

/** Candidate-free, deeply immutable represented player knowledge. */
class EpistemicState private constructor(
    val observation: PlayerObservationSnapshot,
    val history: PolicyHistorySnapshot,
    val knowledge: PolicyKnowledgeState,
    val terminated: Boolean,
    val winnerId: String?,
) {
    val perspectivePlayerId: String get() = observation.perspectivePlayerId
    val historyCommitment: PolicyHistoryCommitment get() = history.commitment
    init {
        require(knowledge.perspectivePlayerId == perspectivePlayerId) { "Observation and represented knowledge must have the same perspective" }
    }
    /** Preserve the first epistemic digest's canonical material; compute it only when demanded. */
    val epistemicDigest: String by lazy {
        "epistemic-state-v1-sha256:" + PolicyJson.digest(buildJsonObject {
            put("schemaVersion", 1)
            put("observation", PolicyJson.format.encodeToJsonElement(observation.copy(observationDigest = "")))
            put("history", PolicyJson.format.encodeToJsonElement(historyCommitment))
            put("knowledge", PolicyJson.format.encodeToJsonElement(knowledge.copy(knowledgeDigest = "")))
            put("terminated", terminated)
            put("winnerId", winnerId?.let(::JsonPrimitive) ?: JsonNull)
        })
    }
    companion object {
        fun capture(observation: PlayerObservationSnapshot, history: List<PolicyHistoryEvent>,
            historyCommitment: PolicyHistoryCommitment, knowledge: PolicyKnowledgeState,
            terminated: Boolean, winnerId: String? = null): EpistemicState = EpistemicState(
                observation.semanticSnapshot(), PolicyHistorySnapshot.capture(history, historyCommitment),
                knowledge.semanticSnapshot(), terminated, winnerId)

        fun capture(information: InformationStateRepresentation): EpistemicState = capture(information.observation,
            information.history, information.historyCommitment, information.knowledge, information.terminated, information.winnerId)
    }
}

enum class DecisionAdmission { SEMANTIC, PRODUCTION }
data class DecisionView(
    val limit: Int? = null,
    val admission: DecisionAdmission = DecisionAdmission.SEMANTIC,
    val annotations: Boolean = false,
) {
    init { require(limit == null || limit > 0); require(!annotations || admission == DecisionAdmission.PRODUCTION) }
}

/** One immutable acting-player context, built from epistemic state and its selected expansion. */
class DecisionSite private constructor(
    val epistemic: EpistemicState,
    val actor: String,
    val expansion: PolicyExpansion,
) {
    init {
        require(!epistemic.terminated && actor == epistemic.perspectivePlayerId)
        require(expansion.candidates.isNotEmpty()) { "An acting decision site needs an admitted menu" }
    }
    val informationStateDigest: String by lazy { InformationStateRepresentationDigest.compute(
        epistemic.observation.observationDigest, epistemic.historyCommitment, epistemic.knowledge.knowledgeDigest,
        actor, expansion.candidates.map { it.signature }, expansion.proposalVersion) }
    val decisionSiteDigest: String by lazy {
        "decision-site-v1-sha256:" + PolicyJson.digest(buildJsonObject {
            put("schemaVersion", 1); put("epistemicDigest", epistemic.epistemicDigest); put("actor", actor)
            put("expansion", PolicyJson.format.encodeToJsonElement(expansion))
        })
    }
    private val informationValue by lazy { epistemic.information(actor, expansion.candidates, informationStateDigest) }
    fun information(): InformationStateRepresentation = informationValue

    companion object {
        fun create(epistemic: EpistemicState, actor: String, expansion: PolicyExpansion): DecisionSite =
            DecisionSite(epistemic, actor, expansion.semanticSnapshot())
        internal fun captured(epistemic: EpistemicState, actor: String, frozenExpansion: PolicyExpansion): DecisionSite =
            DecisionSite(epistemic, actor, frozenExpansion)
    }
}

/**
 * A captured decision with lazy projection. Native world adapters bind the supplier to a frozen
 * world revision; its information record and admitted expansion always come from that one capture.
 */
class DecisionSiteRequest private constructor(
    val expansion: PolicyExpansion,
    val view: DecisionView,
    val sourceContractIdentity: String,
    private val actorValue: String,
    private val epistemicSource: () -> EpistemicState,
) {
    private val admittedValue = lazy { DecisionSite.captured(epistemicSource(), actorValue, expansion) }
    private val admitted by admittedValue
    val informationDemanded: Boolean get() = admittedValue.isInitialized()
    val actor: String get() = actorValue
    fun site(): DecisionSite = admitted
    fun information(): InformationStateRepresentation = site().information()

    companion object {
        fun capture(actor: String, expansion: PolicyExpansion, epistemic: () -> EpistemicState,
            view: DecisionView = DecisionView(), sourceContractIdentity: String = expansion.proposalVersion): DecisionSiteRequest {
            require(actor.isNotBlank() && sourceContractIdentity.isNotBlank())
            val state by lazy { epistemic() }
            return DecisionSiteRequest(expansion.semanticSnapshot(), view, sourceContractIdentity, actor, { state })
        }
    }
}

/** Canonical projection is shallow over already immutable semantic values. */
internal fun EpistemicState.information(actor: String, candidates: List<SemanticChoice>, digest: String) =
    InformationStateRepresentation(actingPlayerId = actor, observation = observation, informationStateDigest = digest,
        historyCommitment = historyCommitment, history = history, knowledge = knowledge, candidates = candidates,
        terminated = terminated, winnerId = winnerId)
