package org.mtgallium.evaluation.searchteacher

import java.nio.file.Path
import java.time.Instant
import com.wingedsheep.engine.registry.CardRegistry
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.argentum.ArgentumPrivilegedDebugSnapshot
import org.mtgallium.agent.infoset.core.INSPECTION_SCHEMA_V1
import org.mtgallium.agent.infoset.core.INSPECTION_SCHEMA_V2
import org.mtgallium.agent.infoset.core.INSPECTION_SCHEMA_CURRENT
import org.mtgallium.agent.infoset.core.BeliefArchitecture
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.InformationSetSearchResult
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.PolicyExpansion
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyInspectionBundle
import org.mtgallium.agent.infoset.core.PolicyInspectionExecutionBinding
import org.mtgallium.agent.infoset.core.PolicyInspectionFrame
import org.mtgallium.agent.infoset.core.PolicyInspectionOutcome
import org.mtgallium.agent.infoset.core.PolicyInspectionPresentation
import org.mtgallium.agent.infoset.core.PolicyInspectionSearch
import org.mtgallium.agent.infoset.core.PolicyInspectionTransition
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PublicArtifactPrivacy
import org.mtgallium.agent.infoset.core.SemanticChoice

/** Builds one normalized, perspective-safe replay directly from runtime policy DTOs. */
internal class PolicyInspectionRecorder(
    private val gameId: String,
    /** Time of the game represented by this bundle, not necessarily the later derivation time. */
    private val createdAtUtc: String = Instant.now().toString(),
    private val outerCommit: String,
    private val argentumCommit: String,
    private val deckManifestHash: String,
    private val cardPoolHash: String,
    private val profileManifestHash: String,
    private val perspectivePlayerId: String,
    private val policyVersion: String,
    private val evaluatorVersion: String,
    private val beliefVersion: String,
    private val opponentModelVersion: String,
    private val runtimeLeaf: LeafEvaluationConfig?,
    private val runtimeBeliefMode: BeliefMode?,
    private val runtimeBeliefArchitecture: BeliefArchitecture?,
) {
    private val ledger = mutableListOf<org.mtgallium.agent.infoset.core.PolicyHistoryEvent>()
    private val frames = mutableListOf<PolicyInspectionFrame>()

    fun recordInitial(information: PolicyInformationState) {
        check(frames.isEmpty())
        check(information.observation.perspectivePlayerId == perspectivePlayerId)
        ledger += information.history
        frames += frame(information, afterDecisionIndex = null, transition = null)
    }

    fun recordSearch(
        decisionIndex: Int,
        expansion: PolicyExpansion,
        result: InformationSetSearchResult,
        heuristicChoice: SemanticChoice,
        beliefDiagnostics: org.mtgallium.agent.infoset.core.BeliefDiagnostics,
    ) {
        check(frames.lastIndex == decisionIndex) { "Search result does not align with inspection frame" }
        check(result.diagnostics.leaf == runtimeLeaf) {
            "Inspection search diagnostics disagree with the perspective seat's runtime leaf configuration"
        }
        check(evaluatorVersion == result.diagnostics.leaf.evaluator.evaluatorId) {
            "Inspection evaluator version disagrees with the perspective seat's runtime"
        }
        check(beliefDiagnostics.mode == runtimeBeliefMode &&
            beliefDiagnostics.architecture == runtimeBeliefArchitecture
        ) {
            "Inspection belief diagnostics disagree with the perspective seat's runtime"
        }
        check(
            beliefVersion ==
                "${beliefDiagnostics.architecture.name.lowercase()}:${beliefDiagnostics.mode.name.lowercase()}"
        ) {
            "Inspection belief version disagrees with the perspective seat's runtime"
        }
        val current = frames.last()
        check(current.search == null)
        check(current.informationStateDigest.isNotBlank())
        frames[frames.lastIndex] = current.copy(
            search = PolicyInspectionSearch(
                decisionIndex = decisionIndex,
                expansion = expansion,
                candidates = result.candidates,
                chosen = result.chosen,
                heuristicChoice = heuristicChoice,
                rootValue = result.rootValue,
                beliefDiagnostics = beliefDiagnostics,
                searchDiagnostics = result.diagnostics,
            )
        )
    }

    fun recordTransition(
        decisionIndex: Int,
        actorId: String,
        actualChoice: SemanticChoice,
        privateToActor: Boolean,
        informationAfter: PolicyInformationState,
    ) {
        check(frames.lastIndex == decisionIndex)
        check(informationAfter.observation.perspectivePlayerId == perspectivePlayerId)
        check(informationAfter.history.take(ledger.size) == ledger) {
            "Perspective history ceased to be append-only at decision $decisionIndex"
        }
        val start = ledger.size
        val appended = informationAfter.history.drop(start)
        check(appended.isNotEmpty()) { "Decision $decisionIndex produced no perspective event" }
        ledger += appended
        val responseHidden = privateToActor && actorId != perspectivePlayerId
        frames += frame(
            information = informationAfter,
            afterDecisionIndex = decisionIndex,
            transition = PolicyInspectionTransition(
                decisionIndex = decisionIndex,
                actorId = actorId,
                observedChoice = actualChoice.takeUnless { responseHidden },
                privateResponse = responseHidden,
                eventStartInclusive = start,
                eventEndExclusive = ledger.size,
            ),
        )
    }

    fun finish(
        outcome: PolicyInspectionOutcome,
        executionBinding: PolicyInspectionExecutionBinding? = null,
    ): PolicyInspectionBundle {
        executionBinding?.let { binding ->
            check(binding.actualPolicyByPlayer[perspectivePlayerId]?.behaviorIdentity == policyVersion) {
                "Inspection policy version disagrees with its runtime execution binding"
            }
        }
        return PolicyInspectionBundle(
            gameId = gameId,
            createdAtUtc = createdAtUtc,
            outerCommit = outerCommit,
            argentumCommit = argentumCommit,
            deckManifestHash = deckManifestHash,
            cardPoolHash = cardPoolHash,
            profileManifestHash = profileManifestHash,
            perspectivePlayerId = perspectivePlayerId,
            policyVersion = policyVersion,
            evaluatorVersion = evaluatorVersion,
            beliefVersion = beliefVersion,
            opponentModelVersion = opponentModelVersion,
            executionBinding = executionBinding,
            ledger = ledger.toList(),
            frames = frames.toList(),
            outcome = outcome,
        ).also { bundle ->
            bundle.frames.forEach { frame ->
                check(bundle.informationState(frame.frameIndex).history == ledger.take(frame.historyLength))
            }
        }
    }

    private fun frame(
        information: PolicyInformationState,
        afterDecisionIndex: Int?,
        transition: PolicyInspectionTransition?,
    ) = PolicyInspectionFrame(
        frameIndex = frames.size,
        afterDecisionIndex = afterDecisionIndex,
        actingPlayerId = information.actingPlayerId,
        observation = information.observation,
        knowledge = information.knowledge,
        candidates = information.candidates,
        candidateSchemaVersion = information.candidateSchemaVersion,
        historyLength = information.history.size,
        historyCommitment = information.historyCommitment,
        informationStateDigest = information.informationStateDigest,
        terminated = information.terminated,
        winnerId = information.winnerId,
        transition = transition,
    )
}

@Serializable
internal data class PrivilegedInspectionBundle(
    val schemaVersion: Int = INSPECTION_SCHEMA_CURRENT,
    val gameId: String,
    val outerCommit: String,
    val argentumCommit: String,
    val sourcePublicSha256: String,
    val presentation: PolicyInspectionPresentation = PolicyInspectionPresentation(),
    val frames: List<PrivilegedInspectionFrame>,
) {
    init {
        require(schemaVersion in INSPECTION_SCHEMA_V1..INSPECTION_SCHEMA_CURRENT)
        require(schemaVersion != INSPECTION_SCHEMA_V1 || presentation == PolicyInspectionPresentation())
        if (schemaVersion >= org.mtgallium.agent.infoset.core.INSPECTION_SCHEMA_V4) {
            require(runCatching { java.util.UUID.fromString(gameId) }.isSuccess)
        }
        require(frames.isNotEmpty())
        require(frames.map { it.frameIndex } == frames.indices.toList())
        require(frames.first().afterDecisionIndex == null)
    }
}

@Serializable
internal data class PrivilegedInspectionFrame(
    val frameIndex: Int,
    val afterDecisionIndex: Int?,
    val actualChoice: SemanticChoice?,
    val snapshot: ArgentumPrivilegedDebugSnapshot,
)

internal class PrivilegedInspectionRecorder(
    private val gameId: String,
    private val outerCommit: String,
    private val argentumCommit: String,
) {
    private val frames = mutableListOf<PrivilegedInspectionFrame>()

    fun recordInitial(snapshot: ArgentumPrivilegedDebugSnapshot) {
        check(frames.isEmpty())
        frames += PrivilegedInspectionFrame(0, null, null, snapshot)
    }

    fun recordTransition(decisionIndex: Int, choice: SemanticChoice, snapshot: ArgentumPrivilegedDebugSnapshot) {
        check(frames.lastIndex == decisionIndex)
        frames += PrivilegedInspectionFrame(frames.size, decisionIndex, choice, snapshot)
    }

    fun finish(sourcePublicSha256: String): PrivilegedInspectionBundle = PrivilegedInspectionBundle(
        gameId = gameId,
        outerCommit = outerCommit,
        argentumCommit = argentumCommit,
        sourcePublicSha256 = sourcePublicSha256,
        frames = frames.toList(),
    )
}

internal fun writeInspectionPair(
    publicPath: Path,
    privilegedPath: Path?,
    publicBundle: PolicyInspectionBundle,
    privilegedRecorder: PrivilegedInspectionRecorder?,
    registry: CardRegistry,
    baseCardNames: Set<String>,
) {
    val resolver = InspectionCardPresentationResolver(registry, baseCardNames)
    val presentedPublic = publicBundle.copy(presentation = resolver.safe(publicBundle))
    PublicArtifactPrivacy.requireSafeJson(
        PolicyJson.format.encodeToString(PolicyInspectionBundle.serializer(), presentedPublic),
        "public inspection bundle",
    )
    writeJsonAtomically(publicPath, presentedPublic)
    if (privilegedPath != null) {
        val privileged = requireNotNull(privilegedRecorder) { "Privileged path requires a recorder" }
            .finish(sha256File(publicPath))
            .let { it.copy(presentation = resolver.privileged(it)) }
        writeJsonAtomically(privilegedPath, privileged)
    }
}

internal fun policyInspectionBundleDigest(bundle: PolicyInspectionBundle): String =
    PolicyJson.sha256(PolicyJson.format.encodeToString(PolicyInspectionBundle.serializer(), bundle))
