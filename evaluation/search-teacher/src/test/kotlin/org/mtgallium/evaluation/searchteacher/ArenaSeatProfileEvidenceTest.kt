package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.BeliefArchitecture
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.PolicyInspectionBundle
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.infoset.core.PolicySourceTreeState
import org.mtgallium.agent.infoset.core.PolicyTrajectoryDecision
import org.mtgallium.agent.infoset.core.PolicyTrajectoryHeader
import org.mtgallium.agent.infoset.core.PolicyTrajectoryRecord
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile

class ArenaSeatProfileEvidenceTest {
    @ScenarioExecutionTest
    @Test
    fun `arena evidence follows the acting seat across evaluator and settlement combinations`() {
        val manifest = loadDeckManifest()
        val registry = buildRegistry()
        val root = createTempDirectory("mtgallium-issue-0015-")
        val base = SearchTeacherArena.smokeProfile().copy(
            particles = 8,
            simulations = 64,
            maxPolicyDecisions = 1,
        )
        val combinations = listOf(
            LeafEvaluationConfig(LeafStateSource.BOUNDED_ROLLOUT, LeafEvaluator.MTGALLIUM_VISIBLE_V2),
            LeafEvaluationConfig(
                LeafStateSource.CURRENT_INFORMATION_STATE,
                LeafEvaluator.MTGALLIUM_VISIBLE_V2,
            ),
            LeafEvaluationConfig(LeafStateSource.BOUNDED_ROLLOUT, LeafEvaluator.MTGALLIUM_TACTICAL_V3),
            LeafEvaluationConfig(
                LeafStateSource.CURRENT_INFORMATION_STATE,
                LeafEvaluator.MTGALLIUM_TACTICAL_V3,
            ),
        )
        val actingBeliefMode = BeliefMode.POLICY_CONDITIONED_V1
        val actingBeliefArchitecture = BeliefArchitecture.SNAPSHOT_A_V1
        val actingPlanner = SearchPlannerKind.SHARED_TREE
        val actingBeliefVersion = "snapshot_a_v1:policy_conditioned_v1"
        val source = sourceProvenance("outer-test", "argentum-test")

        combinations.forEachIndexed { index, actingLeaf ->
            val actingProfile = base.copy(leaf = actingLeaf)
            val staleDefaultLeaf = combinations[(index + 1) % combinations.size]
            val arenaDefault = base.copy(
                id = "deep-teacher-v1",
                particles = 16,
                simulations = 256,
                maxPolicyDecisions = 7,
                leaf = staleDefaultLeaf,
                actionSpaceProfile = SearchActionSpaceProfile.RULES_EXACT_V1,
            )
            val searchPolicy = ArenaPolicySpec(
                id = "acting-seat-${actingLeaf.stateSource.name.lowercase()}-${actingLeaf.evaluator.name.lowercase()}",
                kind = ArenaPolicyKind.SEARCH,
                profile = actingProfile,
                beliefMode = actingBeliefMode,
                beliefArchitecture = actingBeliefArchitecture,
                searchPlanner = actingPlanner,
            )
            val opponentPolicy = ArenaPolicySpec("asymmetric-p0-heuristic", ArenaPolicyKind.HEURISTIC)
            val gameId = "00000000-0000-4000-8000-${(1500 + index).toString().padStart(12, '0')}"
            val gameSeed = 15_000L + index
            val trajectoryPath = root.resolve("$index.trajectory.jsonl.gz")
            val inspectionPath = root.resolve("$index.inspection.json")
            val profileManifestHash = "arena-default-presentation-$index"
            val evidenceChoices = mutableListOf<String>()
            val arena = SearchTeacherArena(
                registry = registry,
                manifest = manifest,
                profile = arenaDefault,
                baseSeed = 15L,
                beliefMode = BeliefMode.CONSISTENCY_ONLY_V1,
                beliefArchitecture = BeliefArchitecture.SEQUENTIAL_B_V1,
                searchPlanner = SearchPlannerKind.INDEPENDENT_DETERMINIZATION,
            )
            val result = arena.playWithPolicies(
                gameId = gameId,
                gameSeed = gameSeed,
                p0Policy = opponentPolicy,
                p1Policy = searchPolicy,
                evidence = GameEvidenceOptions(
                    publicTrajectory = trajectoryPath,
                    inspection = inspectionPath,
                    inspectionPerspective = "p1",
                    outerCommit = "outer-test",
                    argentumCommit = "argentum-test",
                    profileHash = profileManifestHash,
                    sourceProvenance = source,
                    inspectionExecutionCommitment = executionCommitment(),
                ),
                maxSearchDecisions = 1,
                acceptedStepProbe = { _, _, _, choice, _ -> evidenceChoices += choice.signature },
            )

            val controlChoices = mutableListOf<String>()
            val controlArena = SearchTeacherArena(
                registry = registry,
                manifest = manifest,
                profile = actingProfile,
                baseSeed = 15L,
                beliefMode = actingBeliefMode,
                beliefArchitecture = actingBeliefArchitecture,
                searchPlanner = actingPlanner,
            )
            val control = controlArena.playWithPolicies(
                gameId = gameId,
                gameSeed = gameSeed,
                p0Policy = opponentPolicy,
                p1Policy = searchPolicy,
                maxSearchDecisions = 1,
                acceptedStepProbe = { _, _, _, choice, _ -> controlChoices += choice.signature },
            )

            assertEquals(controlChoices, evidenceChoices, actingLeaf.toString())
            assertEquals(control.disposition, result.disposition, actingLeaf.toString())
            assertEquals(actingPlanner, result.searchPlanner)
            assertNotEquals(staleDefaultLeaf, actingLeaf)

            val records = GZIPInputStream(Files.newInputStream(trajectoryPath)).bufferedReader().useLines { lines ->
                lines.filter(String::isNotBlank).map {
                    PolicyJson.format.decodeFromString<PolicyTrajectoryRecord>(it)
                }.toList()
            }
            val header = records.first() as PolicyTrajectoryHeader
            val decision = records.filterIsInstance<PolicyTrajectoryDecision>().single()
            val inspection = PolicyJson.format.decodeFromString<PolicyInspectionBundle>(
                Files.readString(inspectionPath),
            )
            val inspectionSearch = inspection.frames.mapNotNull { it.search }.single()
            val seatDiagnostic = result.seatDiagnostics.getValue("p1").searchDecisionsDetail.single()
            val expectedBinding = arena.evidenceBinding(searchPolicy, maxSearchDecisions = 1, source)
            val expectedOpponentBinding = controlArena.evidenceBinding(
                opponentPolicy,
                maxSearchDecisions = 1,
                source,
            )

            assertEquals(expectedBinding, header.behaviorBinding)
            assertEquals(expectedBinding.identity, header.policyVersion)
            assertEquals(profileManifestHash, header.profileManifestHash)
            assertEquals(actingLeaf.evaluator.evaluatorId, header.evaluatorVersion)
            assertEquals(actingLeaf, header.leaf)
            assertEquals(actingProfile.actionSpaceProfile, header.actionSpaceProfile)
            assertEquals(actingBeliefVersion, header.beliefVersion)

            assertEquals(header.policyVersion, decision.policyVersion)
            assertEquals(header.evaluatorVersion, decision.evaluatorVersion)
            assertEquals(header.leaf, decision.leaf)
            assertEquals(header.actionSpaceProfile, decision.actionSpaceProfile)
            assertEquals(header.beliefVersion, decision.beliefVersion)
            assertEquals(actingLeaf, decision.searchDiagnostics.leaf)
            assertEquals(actingLeaf.evaluator.evaluatorId, decision.searchDiagnostics.configuredEvaluatorId)
            assertEquals(actingBeliefMode, decision.beliefDiagnostics.mode)
            assertEquals(actingBeliefArchitecture, decision.beliefDiagnostics.architecture)

            assertEquals(header.policyVersion, inspection.policyVersion)
            assertEquals(profileManifestHash, inspection.profileManifestHash)
            assertEquals(header.evaluatorVersion, inspection.evaluatorVersion)
            assertEquals(header.beliefVersion, inspection.beliefVersion)
            assertEquals(actingLeaf, inspectionSearch.searchDiagnostics.leaf)
            assertEquals(actingBeliefMode, inspectionSearch.beliefDiagnostics.mode)
            assertEquals(actingBeliefArchitecture, inspectionSearch.beliefDiagnostics.architecture)
            assertEquals(decision.chosen.signature, inspectionSearch.chosen.signature)
            assertEquals(decision.chosen.signature, seatDiagnostic.chosen?.signature)
            assertEquals(actingLeaf, seatDiagnostic.searchDiagnostics.leaf)

            val execution = requireNotNull(inspection.executionBinding)
            assertEquals(header.policyVersion, execution.actualPolicyByPlayer.getValue("p1").behaviorIdentity)
            assertEquals(
                expectedOpponentBinding.identity,
                execution.actualPolicyByPlayer.getValue("p0").behaviorIdentity,
            )
            assertEquals(
                expectedOpponentBinding.behaviorSpecificationSha256,
                execution.actualPolicyByPlayer.getValue("p0").behaviorSpecificationSha256,
            )
            assertEquals(1L, execution.actualExecutionLimits.getValue("maximumSearchDepth"))
            assertEquals(8L, execution.actualExecutionLimits.getValue("particlesPerDecision"))
            assertEquals(64L, execution.actualExecutionLimits.getValue("simulationsPerDecision"))
            assertTrue(result.decisions > 0)
        }
    }

    private fun executionCommitment(): InspectionExecutionCommitment = InspectionExecutionCommitment(
        protocolId = "issue-0015-regression-v1",
        manifestSha256 = "1".repeat(64),
        randomizationUnitId = "issue-0015-seat-profile",
        declaredBehaviorSha256 = "2".repeat(64),
        declaredPopulationSha256 = "3".repeat(64),
        declaredLimitsSha256 = "4".repeat(64),
        executionLimits = InspectionExecutionLimits(
            maximumActionProposals = ArgentumSearchWorld.DEFAULT_EXPANSION_LIMIT,
            perDecisionTimeoutMillis = 600_000,
            wholeGameWallClockMillis = 600_000,
            maximumTurns = 32,
            maximumPolicyDecisions = 32,
            maximumTransitions = 256,
            concurrency = 1,
        ),
    )

    private fun sourceProvenance(
        outerRevision: String,
        argentumRevision: String,
    ): PolicySourceProvenance {
        val empty = PolicyJson.sha256("")
        return PolicySourceProvenance(
            expectedArgentumRevision = argentumRevision,
            outer = PolicySourceTreeState(outerRevision, empty, empty, empty),
            argentum = PolicySourceTreeState(argentumRevision, empty, empty, empty),
        )
    }
}
