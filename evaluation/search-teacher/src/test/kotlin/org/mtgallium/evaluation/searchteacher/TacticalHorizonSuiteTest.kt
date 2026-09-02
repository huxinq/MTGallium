package org.mtgallium.evaluation.searchteacher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mtgallium.agent.infoset.core.SemanticOperationFamily

@ScenarioExecutionTest
class TacticalHorizonSuiteTest {
    @Test
    fun `horizon suite is balanced and every state is reviewable`() {
        TacticalHorizonCatalog.validate()
        TacticalHorizonContractCatalog.validateCatalog()
        val factory = TacticalHorizonScenarioFactory(buildRegistry(), loadDeckManifest())
        TacticalHorizonCatalog.cases.forEach { case ->
            val world = factory.create(case)
            val actor = world.actorToAct()
            assertEquals("p0", actor, case.id)
            val state = world.informationState(requireNotNull(actor))
            val expansion = world.expandChoices(2_048)
            assertFalse(state.terminated, case.id)
            assertTrue(expansion.isExhaustive, case.id)
            assertTrue(expansion.candidates.isNotEmpty(), case.id)
            assertTrue(expansion.proposalVersion.endsWith("rules-exact-v1"), case.id)
            assertEquals(
                emptyList(),
                TacticalHorizonContractCatalog.validate(case, state),
                case.id,
            )
        }
    }

    @Test
    fun `each authored singleton names exactly one raw legal action`() {
        val factory = TacticalHorizonScenarioFactory(buildRegistry(), loadDeckManifest())
        TacticalHorizonCatalog.cases.forEach { case ->
            val world = factory.create(case)
            val actor = requireNotNull(world.actorToAct())
            val information = world.informationState(actor)
            val expansion = world.expandChoices(2_048)
            val matches = expansion.candidates.filter { case.expectedAction.matches(it, information) }
            assertEquals(
                1,
                matches.size,
                "${case.id}: expected=${case.expectedAction}; candidates=" +
                    expansion.candidates.map { choice ->
                        "${choice.operationFamily}/${choice.display.sourceName}/${choice.display.targetNames}/${choice.display.label}"
                    },
            )
        }
    }

    @Test
    fun `independent root contracts detect fixture mutation`() {
        val case = TacticalHorizonCatalog.cases.single { it.id == "immediate-01" }
        val information = TacticalHorizonScenarioFactory(buildRegistry(), loadDeckManifest())
            .create(case)
            .informationState("p0")
        val mutated = information.copy(
            observation = information.observation.copy(
                players = information.observation.players.map { player ->
                    if (player.playerId == "p0") player.copy(life = player.life + 1) else player
                }
            )
        )

        assertTrue(
            TacticalHorizonContractCatalog.validate(case, mutated)
                .any { it.startsWith("p0.life expected=2 actual=3") },
        )
    }

    @Test
    fun `paired shock states expose the agreed resource horizons`() {
        val factory = TacticalHorizonScenarioFactory(buildRegistry(), loadDeckManifest())
        val draw = factory.create(TacticalHorizonCatalog.cases.single { it.id == "immediate-01" })
            .informationState("p0").observation
        val end = factory.create(TacticalHorizonCatalog.cases.single { it.id == "short-02" })
            .informationState("p0").observation

        assertEquals("DRAW", draw.step)
        assertEquals(2, draw.players.single { it.playerId == "p0" }.mana.red)
        assertEquals(2, draw.players.single { it.playerId == "p0" }.handSize)
        assertEquals("END", end.step)
        assertEquals(1, end.players.single { it.playerId == "p0" }.mana.red)
        assertEquals(1, end.players.single { it.playerId == "p0" }.handSize)
    }

    @Test
    fun `an end-step Shock gives the opponent a response window`() {
        val factory = TacticalHorizonScenarioFactory(buildRegistry(), loadDeckManifest())
        val world = factory.create(TacticalHorizonCatalog.cases.single { it.id == "short-02" })
        val cast = world.expandChoices(2_048).candidates.single {
            it.operationFamily == SemanticOperationFamily.CAST_SPELL &&
                it.display.targetNames == listOf("Player 1")
        }

        assertTrue(world.stepRaw(cast).accepted)
        assertNull(world.terminalPayoff("p0"), "Shock must not resolve before both players pass")
        assertEquals("p0", world.actorToAct(), "the caster receives priority after casting")
        val pass = world.expandChoices(2_048).candidates.single {
            it.operationFamily == SemanticOperationFamily.PASS_PRIORITY
        }
        assertTrue(world.stepRaw(pass).accepted)
        assertEquals("p1", world.actorToAct())
        assertTrue(
            world.expandChoices(2_048).candidates.any {
                it.operationFamily == SemanticOperationFamily.MANA_ABILITY ||
                    it.operationFamily == SemanticOperationFamily.CAST_SPELL
            },
            "the opponent must be able to produce red or cast the responding Shock",
        )
    }

    @Test
    fun `proof-directed search certifies pivotal horizon singletons`() {
        val factory = TacticalHorizonScenarioFactory(buildRegistry(), loadDeckManifest())
        listOf("immediate-04", "immediate-05", "immediate-06", "within-turn-07", "short-02", "short-05")
            .forEach { caseId ->
            val case = TacticalHorizonCatalog.cases.single { it.id == caseId }
            val world = factory.create(case)
            val information = world.informationState("p0")
            val expected = world.expandChoices(2_048).candidates.single {
                case.expectedAction.matches(it, information)
            }
            val result = TacticalProofOracle(
                maxStrategicDepth = 128,
                maxExpandedNodes = 100_000,
                maxWallClockMillis = 60_000,
            ).evaluate(
                TacticalProofCase(
                    id = case.id,
                    category = TacticalProofCategory.RESTRAINT,
                    description = case.description,
                    acceptedPredicate = "test-only private horizon expectation",
                    proof = case.terminalJustification,
                    opportunityExpiry = case.horizon.name,
                    rootSeed = case.rootSeed,
                    rootPlayer = "p0",
                    expiry = TacticalProofExpiry.TERMINAL,
                    acceptedPattern = TacticalProofAcceptedPattern(setOf(SemanticOperationFamily.PASS_PRIORITY)),
                ),
                world,
                1,
            )
            assertEquals(
                setOf(expected.signature),
                result.acceptedSignatures,
                "$caseId: ${result.actionValues.map { value ->
                    value.choice.display.targetNames to value.outcome
                }}",
            )
            assertTrue(result.diagnostics.exhaustive, case.id)
        }
    }

    @Test
    fun `supplemental mechanics remain distinct in the raw action display`() {
        val factory = TacticalHorizonScenarioFactory(buildRegistry(), loadDeckManifest())
        fun candidates(caseId: String) = factory.create(
            TacticalHorizonCatalog.cases.single { it.id == caseId }
        ).expandChoices(2_048).candidates

        val landPlays = candidates("within-turn-07")
            .filter { it.operationFamily == org.mtgallium.agent.infoset.core.SemanticOperationFamily.PLAY_LAND }
            .map { it.display.label }
            .toSet()
        assertEquals(setOf("Play Mountain", "Play Soulstone Sanctuary"), landPlays)

        val soulstoneLabels = candidates("short-07").map { it.display.label }
        assertTrue(soulstoneLabels.any { "Add {C}" in it })
        assertTrue(soulstoneLabels.any { "becomes a 3/3" in it })

        val novaCastLabels = candidates("long-07")
            .filter { it.operationFamily == org.mtgallium.agent.infoset.core.SemanticOperationFamily.CAST_SPELL }
            .map { it.display.label }
            .toSet()
        assertEquals(setOf("Cast Nova Hellkite", "Cast Nova Hellkite (Warp)"), novaCastLabels)
    }
}
