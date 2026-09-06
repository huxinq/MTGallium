package org.mtgallium.evaluation.searchteacher

import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.mtgallium.agent.infoset.core.BoundedPolicyInput
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.searchteacher.SearchTeacherEvaluatorRegistry

const val BEHAVIORAL_CLONING_EXAMPLE_SCHEMA_V1: Int = 1

/** Exact frozen scope which an otherwise well-formed public corpus must match before extraction. */
internal class BehavioralCloningAdmissionScope private constructor(
    val expectedOuterRevision: String,
    val expectedArgentumRevision: String,
    val deckManifestHash: String,
    val cardPoolHash: String,
    val profileId: String,
    val profileHash: String,
    val actionSpaceProfile: SearchActionSpaceProfile,
    val leaf: LeafEvaluationConfig,
    val particles: Int,
    val simulations: Int,
    val invokedEvaluatorConfigurationId: String,
    private val frozenMainDeckEntries: List<Pair<String, Int>>,
    private val authenticatedSharedTreeTeacherIdentity: String? = null,
) {
    init {
        require(expectedOuterRevision.isNotBlank())
        require(expectedArgentumRevision.isNotBlank())
        require(deckManifestHash.isNotBlank())
        require(cardPoolHash.isNotBlank())
        require(profileId.isNotBlank())
        require(profileHash.isNotBlank())
        require(actionSpaceProfile == SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1) {
            "Frozen Mono-Red behavioral cloning requires the declared pruned action profile"
        }
        require(frozenMainDeckEntries.isNotEmpty())
        require(particles > 0)
        require(simulations > 0)
        require(invokedEvaluatorConfigurationId.isNotBlank())
    }

    /** Admission reconstructs knowledge from this hash-bound deck, never from caller input. */
    fun knownDecks(): Map<String, Map<String, Int>> {
        val p0 = frozenMainDeckEntries.toMap()
        val p1 = frozenMainDeckEntries.toMap()
        return mapOf("p0" to p0, "p1" to p1)
    }

    /** A two-searcher game has no unique planner summary; its authenticated teacher supplies the authority. */
    fun requireSharedTreeTeacher(entry: CorpusEntry): SearchPlannerKind {
        if (authenticatedSharedTreeTeacherIdentity != null) {
            require(entry.policyEvidenceIdentity == authenticatedSharedTreeTeacherIdentity)
            require(entry.teacherSeat != null)
            if (entry.game.searchSeat == null) {
                require(entry.game.p0Policy == ArenaPolicyKind.SEARCH && entry.game.p1Policy == ArenaPolicyKind.SEARCH)
                require(entry.game.searchPlanner == null)
                return SearchPlannerKind.SHARED_TREE
            }
        }
        require(entry.game.searchPlanner == SearchPlannerKind.SHARED_TREE) {
            "entry did not use the admitted shared-tree Search Teacher"
        }
        return SearchPlannerKind.SHARED_TREE
    }

    companion object {
        /** The caller must first authenticate this retained report through its research-run manifest. */
        fun retainedCalibrationReference(
            deck: DeckManifest,
            report: RetainedCalibrationCloningSource,
        ): BehavioralCloningAdmissionScope {
            require(deck.deckHash() == report.deckHash && deck.cardPoolHash() == report.cardPoolHash)
            val control = report.teacher
            require(control.policy.kind == ArenaPolicyKind.SEARCH && control.policy.searchPlanner == SearchPlannerKind.SHARED_TREE)
            require(control.descriptor.evaluator == null) { "This admission path supports the recorded default evaluator only" }
            require(control.search.simulations == control.descriptor.simulations)
            val leaf = control.search.leaf
            return BehavioralCloningAdmissionScope(
                expectedOuterRevision = report.sourceProvenance.outer.revision,
                expectedArgentumRevision = report.sourceProvenance.argentum.revision,
                deckManifestHash = report.deckHash,
                cardPoolHash = report.cardPoolHash,
                authenticatedSharedTreeTeacherIdentity = control.binding.identity,
                profileId = "retained-calibration-reference-v1",
                profileHash = report.profileHash,
                actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
                leaf = leaf,
                particles = control.descriptor.particles,
                simulations = control.descriptor.simulations,
                invokedEvaluatorConfigurationId = SearchTeacherEvaluatorRegistry.strategy(leaf).source.invokedEvaluatorConfigurationId,
                frozenMainDeckEntries = deck.mainDeck.toSortedMap().map { it.key to it.value },
            )
        }

        fun frozenMonoRed(
            deck: DeckManifest,
            profile: FrozenSearchProfile,
        ): BehavioralCloningAdmissionScope {
            val evaluator = SearchTeacherEvaluatorRegistry.strategy(profile.leaf)
            return BehavioralCloningAdmissionScope(
                expectedOuterRevision = profile.outerCommit,
                expectedArgentumRevision = profile.argentumCommit,
                deckManifestHash = deck.deckHash(),
                cardPoolHash = deck.cardPoolHash(),
                profileId = profile.id,
                profileHash = sha256(evidenceJson.encodeToString(profile)),
                actionSpaceProfile = profile.actionSpaceProfile,
                leaf = profile.leaf,
                particles = profile.particles,
                simulations = profile.simulations,
                invokedEvaluatorConfigurationId = evaluator.source.invokedEvaluatorConfigurationId,
                frozenMainDeckEntries = deck.mainDeck.toSortedMap().map { it.key to it.value },
            )
        }
    }
}

/** Durable identity needed to interpret one admitted label without retaining teacher diagnostics. */
@Serializable
data class BehavioralCloningEvidenceIdentity(
    val datasetIdentity: String,
    val publicTrajectorySha256: String,
    val sourceProvenance: PolicySourceProvenance,
    val deckManifestHash: String,
    val cardPoolHash: String,
    val profileId: String,
    val profileHash: String,
    val trajectorySchemaVersion: Int,
    val observationSchemaVersion: Int,
    val boundedInputSchemaVersion: Int,
    val candidateSchemaVersion: Int,
    val historyCommitmentAlgorithm: String,
    val actionSpaceProfile: SearchActionSpaceProfile,
    val searchPlanner: SearchPlannerKind,
    val policyEvidenceIdentity: String,
    val behaviorIdentity: String,
    val behaviorSpecificationSha256: String,
    val evaluatorVersion: String,
    val invokedEvaluatorConfigurationId: String,
    val leaf: LeafEvaluationConfig,
    val particles: Int,
    val simulations: Int,
    val beliefVersion: String,
    val opponentModelVersion: String,
)

/** Trainable tuple: bounded student state, the accepted semantic teacher action, and identity only. */
@Serializable
data class BehavioralCloningExample(
    val schemaVersion: Int = BEHAVIORAL_CLONING_EXAMPLE_SCHEMA_V1,
    val gameId: String,
    val decisionIndex: Int,
    val actingPlayerId: String,
    val policyInput: BoundedPolicyInput,
    val teacherAction: SemanticChoice,
    val evidence: BehavioralCloningEvidenceIdentity,
) {
    init {
        require(schemaVersion == BEHAVIORAL_CLONING_EXAMPLE_SCHEMA_V1)
        require(decisionIndex >= 0)
        require(policyInput.actingPlayerId == actingPlayerId)
    }
}

internal data class BehavioralCloningAdmissionResult(
    val examples: List<BehavioralCloningExample>,
    val validation: CorpusValidationReport,
    val passed: Boolean,
    val failures: List<String>,
)

/**
 * The sole BC extraction entry point. It uses the public-corpus streaming validator and returns no
 * examples unless the complete manifest and every source trajectory satisfy the admission scope.
 */
internal class BehavioralCloningAdmission(
    private val root: Path,
    private val scope: BehavioralCloningAdmissionScope,
) {
    fun extract(manifestPath: Path): BehavioralCloningAdmissionResult {
        val inspected = PublicCorpusValidator(root, scope.knownDecks())
            .inspectForBehavioralCloning(manifestPath, scope)
        val failures = buildList {
            addAll(inspected.report.failures)
            if (inspected.report.passed && inspected.examples.isEmpty()) {
                add("corpus contains no admissible Search Teacher decisions")
            }
        }
        val passed = failures.isEmpty()
        return BehavioralCloningAdmissionResult(
            examples = inspected.examples.takeIf { passed }.orEmpty(),
            validation = inspected.report,
            passed = passed,
            failures = failures,
        )
    }
}
