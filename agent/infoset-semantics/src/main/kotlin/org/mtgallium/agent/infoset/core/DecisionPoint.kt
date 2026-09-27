package org.mtgallium.agent.infoset.core

import kotlinx.serialization.json.*

/** Candidate-free, deeply immutable represented player knowledge. */
class InformationState private constructor(
    val observation: PlayerObservationSnapshot,
    val history: ObservationHistory,
    val knowledge: PlayerKnowledge,
    val terminated: Boolean,
    val winnerId: String?,
) {
    val viewerId: String get() = observation.viewerId
    val historyCommitment: HistoryHashChain get() = history.commitment
    init {
        require(knowledge.viewerId == viewerId) { "Observation and represented knowledge must have the same perspective" }
    }
    /** Preserve the first epistemic digest's canonical material; compute it only when demanded. */
    val epistemicDigest: String by lazy {
        "epistemic-state-v1-sha256:" + CanonicalJson.digest(buildJsonObject {
            put("schemaVersion", 1)
            put("observation", CanonicalJson.format.encodeToJsonElement(observation.copy(observationDigest = "")))
            put("history", CanonicalJson.format.encodeToJsonElement(historyCommitment))
            put("knowledge", CanonicalJson.format.encodeToJsonElement(knowledge.copy(knowledgeDigest = "")))
            put("terminated", terminated)
            put("winnerId", winnerId?.let(::JsonPrimitive) ?: JsonNull)
        })
    }
    companion object {
        fun capture(observation: PlayerObservationSnapshot, history: List<ObservedEvent>,
            historyCommitment: HistoryHashChain, knowledge: PlayerKnowledge,
            terminated: Boolean, winnerId: String? = null): InformationState = InformationState(
                observation.semanticSnapshot(), ObservationHistory.capture(history, historyCommitment),
                knowledge.semanticSnapshot(), terminated, winnerId)

        fun capture(information: InformationStateRepresentation): InformationState = capture(information.observation,
            information.history, information.historyCommitment, information.knowledge, information.terminated, information.winnerId)
    }
}

enum class MenuSource {
    SEMANTIC,
    PRODUCTION;
}
data class MenuRequest(
    val limit: Int? = null,
    val admission: MenuSource = MenuSource.SEMANTIC,
    val annotations: Boolean = false,
) {
    init { require(limit == null || limit > 0); require(!annotations || admission == MenuSource.PRODUCTION) }
}

/** One immutable acting-player context, built from epistemic state and its selected expansion. */
class DecisionPoint private constructor(
    val epistemic: InformationState,
    val actor: String,
    val menu: ActionMenu,
) {
    init {
        require(!epistemic.terminated && actor == epistemic.viewerId)
        require(menu.candidates.isNotEmpty()) { "An acting decision site needs an admitted menu" }
    }
    val informationStateDigest: String by lazy { InformationStateRepresentationDigest.compute(
        epistemic.observation.observationDigest, epistemic.historyCommitment, epistemic.knowledge.knowledgeDigest,
        actor, menu.candidates.map { it.signature }, menu.proposalVersion) }
    val decisionSiteDigest: String by lazy {
        "decision-site-v1-sha256:" + CanonicalJson.digest(buildJsonObject {
            put("schemaVersion", 1); put("epistemicDigest", epistemic.epistemicDigest); put("actor", actor)
            put("expansion", CanonicalJson.format.encodeToJsonElement(menu))
        })
    }
    private val informationValue by lazy { epistemic.information(actor, menu.candidates, informationStateDigest) }
    fun information(): InformationStateRepresentation = informationValue

    companion object {
        fun create(epistemic: InformationState, actor: String, menu: ActionMenu): DecisionPoint =
            DecisionPoint(epistemic, actor, menu.semanticSnapshot())
        internal fun captured(epistemic: InformationState, actor: String, frozenExpansion: ActionMenu): DecisionPoint =
            DecisionPoint(epistemic, actor, frozenExpansion)
    }
}

/**
 * A captured decision with lazy projection. Native world adapters bind the supplier to a frozen
 * world revision; its information record and admitted expansion always come from that one capture.
 */
class DecisionContext private constructor(
    val menu: ActionMenu,
    val view: MenuRequest,
    val sourceContractIdentity: String,
    private val actorValue: String,
    private val epistemicSource: () -> InformationState,
    referenceGroups: () -> Map<String, List<String>>,
) {
    /** Safe action-reference groups captured at this decision's revision and actor perspective. */
    val semanticReferenceGroups: Map<String, List<String>> by lazy {
        java.util.Collections.unmodifiableMap(referenceGroups().mapValues {
            java.util.Collections.unmodifiableList(it.value.toList())
        })
    }
    private val admittedValue = lazy { DecisionPoint.captured(epistemicSource(), actorValue, menu) }
    private val admitted by admittedValue
    val informationDemanded: Boolean get() = admittedValue.isInitialized()
    val actor: String get() = actorValue
    fun site(): DecisionPoint = admitted
    fun information(): InformationStateRepresentation = site().information()

    companion object {
        fun capture(actor: String, menu: ActionMenu, epistemic: () -> InformationState,
            view: MenuRequest = MenuRequest(), sourceContractIdentity: String = menu.proposalVersion,
            referenceGroups: () -> Map<String, List<String>> = { emptyMap() }): DecisionContext {
            require(actor.isNotBlank() && sourceContractIdentity.isNotBlank())
            val state by lazy { epistemic() }
            return DecisionContext(menu.semanticSnapshot(), view, sourceContractIdentity, actor, { state }, referenceGroups)
        }
    }
}

/** Canonical projection is shallow over already immutable semantic values. */
internal fun InformationState.information(actor: String, candidates: List<SemanticChoice>, digest: String) =
    InformationStateRepresentation(actingPlayerId = actor, observation = observation, informationStateDigest = digest,
        historyCommitment = historyCommitment, history = history, knowledge = knowledge, candidates = candidates,
        terminated = terminated, winnerId = winnerId)
