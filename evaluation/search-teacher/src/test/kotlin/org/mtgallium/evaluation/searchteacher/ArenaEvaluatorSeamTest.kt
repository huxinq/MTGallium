package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.infoset.core.ConfiguredInformationStateEvaluator
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.infoset.core.PolicySourceTreeState
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.searchteacher.SearchTeacherDeckManifest
import org.mtgallium.agent.searchteacher.SearchTeacherIntegrationSpecification
import org.mtgallium.agent.searchteacher.SearchTeacherPolicyIdentity
import org.mtgallium.agent.searchteacher.defaultMonoRedOpponentPolicy

@Tag("public-source")
class ArenaEvaluatorSeamTest {
    @Test
    fun `arena binds configured evaluator identity into the Search Teacher behavior`() {
        val profile = profile()
        val arena = arena(profile)
        val source = sourceProvenance()
        val firstEvaluator = ConstantConfiguredEvaluator("constant-evaluator:a")
        val secondEvaluator = ConstantConfiguredEvaluator("constant-evaluator:b")
        val firstPolicy = ArenaPolicySpec(
            id = "configured-evaluator-a",
            kind = ArenaPolicyKind.SEARCH,
            profile = profile,
            informationEvaluator = firstEvaluator,
        )
        val secondPolicy = firstPolicy.copy(
            informationEvaluator = secondEvaluator,
        )

        val firstBinding = arena.evidenceBinding(
            firstPolicy,
            maxSearchDecisions = 1,
            sourceProvenance = source,
        )
        val secondBinding = arena.evidenceBinding(
            secondPolicy,
            maxSearchDecisions = 1,
            sourceProvenance = source,
        )
        val expected = firstPolicy.effectiveParameters(41L).behaviorSpecification(
            knownDecks = mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck),
            opponentPolicy = defaultMonoRedOpponentPolicy(),
            informationEvaluator = firstEvaluator,
            integration = SearchTeacherIntegrationSpecification(
                hostMode = "evaluation-arena-v1",
                searchPlanner = SearchPlannerKind.SHARED_TREE.name,
                maximumGameDecisions = SearchTeacherArena.MAX_GAME_DECISIONS,
                maximumSearchDecisions = 1,
                additionalBindings = mapOf(
                    "arenaPolicyId" to firstPolicy.id,
                    "arenaPolicyKind" to firstPolicy.kind.name,
                ),
            ),
        )

        assertEquals(firstEvaluator.configurationId, expected.evaluator.invokedEvaluatorConfigurationId)
        assertEquals(SearchTeacherPolicyIdentity.identity(expected), firstBinding.behaviorIdentity)
        assertNotEquals(firstBinding.behaviorIdentity, secondBinding.behaviorIdentity)
    }

    @Test
    fun `arena accepts configured evaluators only on matching current-information shared-tree policies`() {
        val evaluator = ConstantConfiguredEvaluator("constant-evaluator:valid")

        assertFailsWith<IllegalArgumentException> {
            ArenaPolicySpec(
                id = "direct-invalid",
                kind = ArenaPolicyKind.HEURISTIC,
                informationEvaluator = evaluator,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ArenaPolicySpec(
                id = "planner-invalid",
                kind = ArenaPolicyKind.SEARCH,
                profile = profile(),
                searchPlanner = SearchPlannerKind.INDEPENDENT_DETERMINIZATION,
                informationEvaluator = evaluator,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ArenaPolicySpec(
                id = "source-invalid",
                kind = ArenaPolicyKind.SEARCH,
                profile = profile().copy(
                    leaf = LeafEvaluationConfig(
                        LeafStateSource.BOUNDED_ROLLOUT,
                        LeafEvaluator.MTGALLIUM_TACTICAL_V3,
                    )
                ),
                informationEvaluator = evaluator,
            ).effectiveParameters(41L)
        }
        assertFailsWith<IllegalArgumentException> {
            ArenaPolicySpec(
                id = "id-invalid",
                kind = ArenaPolicyKind.SEARCH,
                profile = profile(),
                informationEvaluator = ConstantConfiguredEvaluator(
                    configurationId = "constant-evaluator:mismatched",
                    id = LeafEvaluator.MTGALLIUM_VISIBLE_V2.evaluatorId,
                ),
            ).effectiveParameters(41L)
        }
    }

    private fun arena(profile: FrozenSearchProfile): SearchTeacherArena = SearchTeacherArena(
        registry = CardRegistry(),
        manifest = manifest,
        profile = profile,
        baseSeed = 41L,
    )

    private fun profile(): FrozenSearchProfile = FrozenSearchProfile(
        id = "fast-arena-v1",
        generatedAtUtc = "synthetic",
        outerCommit = "synthetic-outer",
        argentumCommit = "synthetic-argentum",
        host = "synthetic",
        particles = 8,
        simulations = 64,
        leaf = LeafEvaluationConfig(
            LeafStateSource.CURRENT_INFORMATION_STATE,
            LeafEvaluator.MTGALLIUM_TACTICAL_V3,
        ),
        actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
        maxPolicyDecisions = 32,
        explorationConstant = 1.4,
        measuredP95Millis = 0.0,
        tacticalScore = 0.0,
        standardError = 0.0,
        calibrationReportHash = "synthetic",
    )

    private fun sourceProvenance(): PolicySourceProvenance {
        val empty = PolicyJson.sha256("")
        return PolicySourceProvenance(
            expectedArgentumRevision = "synthetic-argentum",
            outer = PolicySourceTreeState("synthetic-outer", empty, empty, empty),
            argentum = PolicySourceTreeState("synthetic-argentum", empty, empty, empty),
        )
    }

    private class ConstantConfiguredEvaluator(
        override val configurationId: String,
        override val id: String = LeafEvaluator.MTGALLIUM_TACTICAL_V3.evaluatorId,
    ) : ConfiguredInformationStateEvaluator {
        override fun evaluate(information: PolicyInformationState, rootPlayer: String): Double = 0.0
    }

    private companion object {
        val manifest = SearchTeacherDeckManifest(
            id = "synthetic-mono-red",
            name = "Synthetic Mono-Red",
            format = "synthetic",
            publishedDate = "2026-09-04",
            source = "public synthetic fixture",
            mainDeck = mapOf("Mountain" to 60),
            sideboard = emptyMap(),
        )
    }
}
