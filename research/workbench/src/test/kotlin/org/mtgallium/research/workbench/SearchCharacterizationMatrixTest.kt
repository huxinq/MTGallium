package org.mtgallium.research.workbench

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CreatureStats
import com.wingedsheep.sdk.model.Deck
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.mtgallium.agent.argentum.policy.SearchPolicyConfig
import org.mtgallium.agent.argentum.policy.LivePolicySession
import org.mtgallium.agent.argentum.policy.PolicyDefaults
import org.mtgallium.agent.argentum.policy.SearchPolicySession
import org.mtgallium.agent.argentum.policy.ConditionedBeliefReconstructionRequired
import org.mtgallium.agent.argentum.policy.createSearch
import org.mtgallium.agent.argentum.policy.defaultMonoRedOpponentPolicy
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.ArgentumResolvedChoice
import org.mtgallium.agent.infoset.argentum.HistoryEventOrdering
import org.mtgallium.agent.infoset.argentum.HistoryObjectReferencing
import org.mtgallium.agent.infoset.argentum.ArgentumActionGenerator
import org.mtgallium.agent.infoset.core.BeliefApproximation
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.DecisionContext
import org.mtgallium.agent.infoset.planning.LeafEvaluationConfig
import org.mtgallium.agent.infoset.planning.LeafEvaluationMethod
import org.mtgallium.agent.infoset.planning.RootActionSelection
import org.mtgallium.agent.infoset.planning.RolloutCutoff
import org.mtgallium.agent.infoset.planning.RolloutTurnHorizon
import org.mtgallium.agent.infoset.planning.RolloutPolicySchedule
import org.mtgallium.agent.infoset.core.ActionSelector
import org.mtgallium.agent.infoset.core.OpponentPolicyDecision
import org.mtgallium.agent.infoset.planning.SearchPrior
import org.mtgallium.agent.infoset.planning.SearchWorld
import org.mtgallium.agent.infoset.planning.SimulationWorldSchedule
import org.mtgallium.agent.infoset.core.UniformOpponentPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** PR 1 characterization on public deck bytes. Capture is deliberately a failing operation. */
class SearchCharacterizationMatrixTest {
    private data class Scenario(
        val id: String,
        val config: SearchPolicyConfig,
        val order: HistoryEventOrdering = HistoryEventOrdering.LEGACY_ENGINE_ORDER_V1,
        val objects: HistoryObjectReferencing = HistoryObjectReferencing.LEGACY_SNAPSHOT_V1,
        val throughLive: Boolean = false,
        val priorAndSchedule: Boolean = false,
        val expectedCutoff: RolloutCutoff? = null,
        val horizon: RolloutTurnHorizon? = null,
    )

    @Test
    fun `S1 through S10 retain complete public-fixture search records`() {
        val compact = SearchPolicyConfig(particles = 2, simulations = 4, maxPolicyDecisions = 4)
        val scenarios = listOf(
            Scenario("S1-defaults", SearchPolicyConfig()),
            Scenario("S2-V2-order-objects", compact,
                HistoryEventOrdering.QUALIFIED_TURN_UNTAP_V2,
                HistoryObjectReferencing.QUALIFIED_OBSERVED_OBJECTS_V2, throughLive = true),
            Scenario("S3-conditioned", compact.copy(beliefMode = BeliefMode.POLICY_CONDITIONED_V1)),
            Scenario("S4-snapshot", compact.copy(beliefArchitecture = BeliefApproximation.SNAPSHOT_A_V1)),
            Scenario("S5-privileged", compact.copy(beliefArchitecture = BeliefApproximation.PRIVILEGED_O_V1)),
            Scenario("S6-widening", compact.copy(initialExpansionLimit = 1,
                wideningThresholds = listOf(1), wideningLimits = listOf(2))),
            Scenario("S7-prior-schedule", compact, priorAndSchedule = true),
            Scenario("S8-evaluate", compact.copy(leaf = LeafEvaluationConfig(LeafEvaluationMethod.BOUNDED_ROLLOUT,
                RolloutCutoff.EVALUATE)), expectedCutoff = RolloutCutoff.EVALUATE),
            Scenario("S8-quiescence", compact.copy(leaf = LeafEvaluationConfig(LeafEvaluationMethod.BOUNDED_ROLLOUT,
                RolloutCutoff.QUIESCENCE)), expectedCutoff = RolloutCutoff.QUIESCENCE),
            Scenario("S8-policy-quiescence", compact.copy(leaf = LeafEvaluationConfig(LeafEvaluationMethod.BOUNDED_ROLLOUT,
                RolloutCutoff.POLICY_QUIESCENCE)), expectedCutoff = RolloutCutoff.POLICY_QUIESCENCE),
            Scenario("S9-current-information", compact.copy(leaf = LeafEvaluationConfig(
                LeafEvaluationMethod.CURRENT_INFORMATION_STATE))),
            Scenario("S10-turn-horizon", compact, horizon = RolloutTurnHorizon(1, 64)),
        )
        val deck = publicDeck()
        val records = scenarios.map { scenario ->
            val first = characterize(scenario, deck)
            assertEquals(first, characterize(scenario, deck), scenario.id)
            first
        } + run {
            val first = conditionedRebuildRefusal()
            assertEquals(first, conditionedRebuildRefusal(), "S3-rebuild-required")
            first
        }
        val output = Path.of("build", "characterization")
        Files.createDirectories(output)
        val raw = records.joinToString("\n", postfix = "\n") { it.toString() }
        val behavior = records.joinToString("\n", postfix = "\n") {
            JsonObject(it - "policyIdentity").toString()
        }
        val identities = records.joinToString("\n", postfix = "\n") { record ->
            buildJsonObject {
                put("scenario", record.getValue("scenario"))
                put("policyIdentity", record.getValue("policyIdentity"))
            }.toString()
        }
        Files.writeString(output.resolve("search-matrix.raw.jsonl"), raw)
        Files.writeString(output.resolve("search-matrix.behavior.jsonl"), behavior)
        Files.writeString(output.resolve("search-matrix.policy-identity.jsonl"), identities)
        compareOrCaptureMatrixGoldens(behavior, identities)
    }

    private fun characterize(scenario: Scenario, deck: Map<String, Int>): JsonObject {
        val knownDecks = mapOf("p0" to deck, "p1" to deck)
        val world = world(scenario, deck, knownDecks)
        advanceToChoice(world)
        val actor = requireNotNull(world.actorToAct())
        val beforeCommitments = commitments(world)
        val parameters = scenario.config.copy(rolloutTurnHorizon = scenario.horizon)
        val priorCalls = mutableListOf<Int>()
        val rolloutScheduleCalls = mutableListOf<Int>()
        val rolloutSchedule = if (scenario.priorAndSchedule) object : RolloutPolicySchedule {
            override val id = "characterization-rollout-schedule"
            override fun atStep(step: Int): ActionSelector {
                rolloutScheduleCalls += step
                return UniformOpponentPolicy
            }
            override fun select(context: DecisionContext, policySeed: Long, sampleSeed: Long): OpponentPolicyDecision =
                UniformOpponentPolicy.select(context, policySeed, sampleSeed)
        } else null
        val prior = if (scenario.priorAndSchedule) object : SearchPrior {
            override val configurationId = "characterization-prior"
            override val candidateLimit = 64
            override val explorationConstant = 1.0
            override fun probabilities(context: DecisionContext): Map<String, Double> {
                priorCalls += context.menu.candidates.size
                val count = context.menu.candidates.size
                return context.menu.candidates.associate { it.signature to 1.0 / count }
            }
        } else null
        val session = SearchPolicySession(world, actor, knownDecks, parameters,
            defaultMonoRedOpponentPolicy(), "matrix-${scenario.id}",
            rolloutPolicy = rolloutSchedule ?: PolicyDefaults.rootRolloutPolicy(), searchPrior = prior)
        val beforeBelief = session.beliefSnapshot(world)
        val beforeBeliefDiagnostics = beforeBelief.hypotheses.materialize().batch.diagnostics
        assertEquals(scenario.config.beliefMode, beforeBeliefDiagnostics.mode, scenario.id)
        assertEquals(scenario.config.beliefArchitecture, beforeBeliefDiagnostics.architecture, scenario.id)
        val beforeLifecycle = session.beliefLifecycleDiagnostics.toString()
        val liveObservation = if (scenario.throughLive) {
            val liveWorld = world.fork() as ArgentumSearchWorld
            val live = LivePolicySession(liveWorld, actor, knownDecks, "matrix-${scenario.id}", scenario.config)
            val decision = live.choose()
            val before = commitments(liveWorld)
            val action = when (val resolved = decision.resolved) {
                is ArgentumResolvedChoice.Action -> resolved.value
                is ArgentumResolvedChoice.Decision -> SubmitDecision(
                    requireNotNull(liveWorld.trueState().pendingDecision).playerId, resolved.value)
            }
            assertTrue(live.applyObserved(action).result.accepted)
            assertEquals(1, live.appliedActions)
            buildJsonObject {
                put("choice", decision.choice.signature)
                put("belief", researchJson.encodeToJsonElement(decision.belief))
                put("commitmentsBefore", before)
                put("commitmentsAfter", commitments(liveWorld))
                put("observedUpdate", live.lastObservedUpdate?.toString() ?: "none")
            }
        } else null
        val selection = assertIs<RootActionSelection.Searched>(session.select(world, actor, 901L), scenario.id)
        val result = selection.search
        assertEquals(scenario.config.simulations, result.candidates.sumOf { it.visits }, scenario.id)
        assertEquals(0, result.diagnostics.rejectedTransitions, scenario.id)
        val serializedLeaf = stableSearch(result).getValue("diagnostics").jsonObject.getValue("leaf").jsonObject
        assertEquals(scenario.config.leaf.stateSource.name,
            serializedLeaf.getValue("stateSource").jsonPrimitive.content, scenario.id)
        scenario.expectedCutoff?.let { assertEquals(it.name,
            serializedLeaf.getValue("cutoff").jsonPrimitive.content, scenario.id) }
        if (scenario.id == "S6-widening") assertTrue(result.diagnostics.wideningEvents > 0)
        val scheduled = if (scenario.priorAndSchedule) {
            val scheduledSearch = createSearch(parameters.searchConfig(),
                rolloutPolicy = requireNotNull(rolloutSchedule), searchPrior = prior)
            val schedule = SimulationWorldSchedule(List(scenario.config.simulations) { world.fork() as SearchWorld })
            val paired = scheduledSearch.search(actor, session.beliefBatch(world), 901L, schedule)
            assertEquals(scenario.config.simulations, paired.candidates.sumOf { it.visits })
            assertTrue(priorCalls.isNotEmpty())
            assertTrue(rolloutScheduleCalls.isNotEmpty())
            stableSearch(paired)
        } else null
        val step = world.step(selection.choice)
        check(step.accepted) { "Selected ${selection.choice.signature} was rejected in ${scenario.id}" }
        session.observeAccepted(world, actor, selection.choice, 0, step.privateToActor)
        val afterBelief = session.beliefSnapshot(world)
        val afterCommitments = commitments(world)
        return buildJsonObject {
            put("scenario", scenario.id)
            put("fixture", "fixtures/decks/mono-red-standard-2026-07-30.json")
            put("deckSha256", MessageDigest.getInstance("SHA-256")
                .digest(deck.toSortedMap().entries.joinToString("\n", postfix = "\n") {
                    "${it.key}:${it.value}" }.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) })
            put("seed", 811)
            put("searchSeed", 901)
            put("historyEventOrder", scenario.order.name)
            put("historyObjectReference", scenario.objects.name)
            put("beliefMode", scenario.config.beliefMode.name)
            put("beliefArchitecture", scenario.config.beliefArchitecture.name)
            put("leafStateSource", scenario.config.leaf.stateSource.name)
            put("cutoff", scenario.config.leaf.cutoff.name)
            put("simulations", scenario.config.simulations)
            put("particles", scenario.config.particles)
            put("maxPolicyDecisions", scenario.config.maxPolicyDecisions)
            put("explorationConstant", scenario.config.explorationConstant)
            put("actionProfile", scenario.config.actionSpaceProfile.profileId)
            put("initialExpansionLimit", scenario.config.initialExpansionLimit)
            put("wideningThresholds", researchJson.encodeToJsonElement(scenario.config.wideningThresholds))
            put("wideningLimits", researchJson.encodeToJsonElement(scenario.config.wideningLimits))
            scenario.horizon?.let { put("completedTurns", it.completedTurns) }
            put("actor", actor)
            put("choice", selection.choice.signature)
            liveObservation?.let { put("liveObservation", it) }
            put("search", stableSearch(result))
            put("workDigest", workDigest(result))
            put("policyIdentity", session.policyIdentity)
            put("inferenceModelIdentity", beforeBelief.queries.binding.inferenceModelIdentity)
            put("inferenceModelIdentityAfter", afterBelief.queries.binding.inferenceModelIdentity)
            put("beliefBefore", researchJson.encodeToJsonElement(beforeBeliefDiagnostics))
            put("beliefAfter", researchJson.encodeToJsonElement(session.latestBeliefDiagnostics))
            put("beliefLifecycleBefore", beforeLifecycle)
            put("beliefLifecycleAfter", session.beliefLifecycleDiagnostics.toString())
            put("lastObservedUpdate", session.lastObservedUpdate?.toString() ?: "none")
            put("commitmentsBefore", beforeCommitments)
            put("commitmentsAfter", afterCommitments)
            put("priorCalls", priorCalls.size)
            put("rolloutScheduleSteps", researchJson.encodeToJsonElement(rolloutScheduleCalls))
            scheduled?.let { put("scheduledSearch", it) }
        }
    }

    private fun world(scenario: Scenario, deck: Map<String, Int>,
        knownDecks: Map<String, Map<String, Int>>): ArgentumSearchWorld {
        val registry = buildRegistry()
        val environment = GameEnvironment.create(registry)
        val cards = Deck.of(*deck.map { it.key to it.value }.toTypedArray())
        environment.reset(GameConfig(players = listOf(PlayerConfig("A", cards), PlayerConfig("B", cards)),
            seed = 811L, startingHandSize = 7, skipMulligans = true, startingPlayerIndex = 0))
        return ArgentumSearchWorld.create(environment, "matrix-${scenario.id}", 811L, 811L,
            expander = ArgentumActionGenerator(actionSpaceProfile = scenario.config.actionSpaceProfile),
            knownDecks = knownDecks, historyEventOrder = scenario.order,
            historyObjectReference = scenario.objects)
    }

    private fun advanceToChoice(world: ArgentumSearchWorld) {
        repeat(64) {
            val menu = world.decisionContext().menu.candidates
            if (menu.size > 1) return
            check(world.step(menu.single()).accepted)
        }
        error("No multi-action public-fixture decision within 64 steps")
    }

    private fun commitments(world: ArgentumSearchWorld) = buildJsonObject {
        for (player in listOf("p0", "p1")) {
            put(player, researchJson.encodeToJsonElement(world.informationState(player).historyCommitment))
        }
    }

    private fun stableSearch(result: org.mtgallium.agent.infoset.planning.InformationSetSearchResult): JsonObject {
        val raw = researchJson.encodeToJsonElement(result).jsonObject
        return JsonObject(raw + ("diagnostics" to JsonObject(raw.getValue("diagnostics").jsonObject - "evaluatorNanos")))
    }

    private fun workDigest(result: org.mtgallium.agent.infoset.planning.InformationSetSearchResult): String {
        val diagnostics = stableSearch(result).getValue("diagnostics").jsonObject
        val counters = diagnostics.filterKeys { it in setOf("searchWorldSteps", "evaluatorCalls",
            "transitionCacheHits", "transitionCacheMisses", "quiescenceForcedPasses",
            "rootRolloutDecisions", "opponentRolloutDecisions", "wideningEvents") }
        return MessageDigest.getInstance("SHA-256").digest(JsonObject(counters).toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun publicDeck(): Map<String, Int> {
        var directory: Path? = Path.of("").toAbsolutePath()
        while (directory != null) {
            val file = directory.resolve("fixtures/decks/mono-red-standard-2026-07-30.json")
            if (Files.exists(file)) return researchJson.parseToJsonElement(Files.readString(file))
                .jsonObject.getValue("mainDeck").jsonObject.mapValues { it.value.jsonPrimitive.content.toInt() }
            directory = directory.parent
        }
        error("Public Mono-Red fixture not found")
    }

    /** Authored zero-mass observation from the same public test mechanism as the backend refusal test. */
    private fun conditionedRebuildRefusal(): JsonObject {
        val registry = CardRegistry().apply {
            register(CardDefinition(name = "Characterization Bear", manaCost = ManaCost.parse("{0}"),
                typeLine = TypeLine.parse("Creature — Bear"), creatureStats = CreatureStats(1, 1)))
        }
        val deck = Deck.of("Characterization Bear" to 40)
        val environment = GameEnvironment.create(registry)
        environment.reset(GameConfig(players = listOf(PlayerConfig("A", deck), PlayerConfig("B", deck)),
            seed = 42613L, startingPlayerIndex = 0, skipMulligans = true, useHandSmoother = false))
        val known = mapOf("p0" to mapOf("Characterization Bear" to 40),
            "p1" to mapOf("Characterization Bear" to 40))
        val world = ArgentumSearchWorld.create(environment, "matrix-S3-rebuild", 42613L, 42613L,
            knownDecks = known,
            historyObjectReference = HistoryObjectReferencing.QUALIFIED_OBSERVED_OBJECTS_V2)
        fun pass() = PassPriority(requireNotNull(world.trueState().priorityPlayerId))
        fun accept(action: GameAction) { check(world.applyObservedAction(action).result.accepted) }
        fun advanceUntil(predicate: () -> Boolean) {
            repeat(256) {
                if (predicate()) return
                check(world.trueState().pendingDecision == null)
                accept(pass())
            }
            error("S3 authored fixture did not reach its declared boundary")
        }
        advanceUntil { world.trueState().step == Step.PRECOMBAT_MAIN }
        val players = world.trueState().turnOrder
        fun cast() = CastSpell(requireNotNull(world.trueState().priorityPlayerId),
            world.trueState().getHand(requireNotNull(world.trueState().priorityPlayerId)).first())
        accept(cast())
        advanceUntil { val state = world.trueState()
            state.activePlayerId == players[1] && state.step == Step.PRECOMBAT_MAIN &&
                state.priorityPlayerId == players[1] && state.stack.isEmpty() }
        accept(cast())
        advanceUntil { world.trueState().stack.isEmpty() &&
            world.trueState().priorityPlayerId == players[1] }
        accept(cast())
        advanceUntil { val state = world.trueState()
            state.activePlayerId == players[0] && state.step == Step.DECLARE_ATTACKERS }
        val attacker = world.trueState().getBattlefield(players[0]).single()
        accept(DeclareAttackers(players[0], mapOf(attacker to players[1])))
        advanceUntil { world.trueState().step == Step.DECLARE_BLOCKERS }
        val blockers = world.trueState().getBattlefield(players[1])
        assertEquals(2, blockers.size)
        val representative = world.expandChoices().candidates.mapNotNull {
            ((world.resolveChoice(it) as? org.mtgallium.agent.infoset.argentum.ArgentumResolvedChoice.Action)?.value
                as? DeclareBlockers)?.takeIf { action -> action.blockers.size == 1 }
        }.single()
        val omitted = blockers.single { it !in representative.blockers }
        val before = commitments(world)
        val config = SearchPolicyConfig(particles = 8, simulations = 4,
            beliefMode = BeliefMode.POLICY_CONDITIONED_V1,
            actionSpaceProfile = world.semanticExpansionSpecification().actionSpaceProfile)
        val metadataSession = SearchPolicySession(world, "p0", known, config,
            UniformOpponentPolicy, "matrix-S3-rebuild")
        val inferenceIdentity = metadataSession.beliefSnapshot(world).queries.binding.inferenceModelIdentity
        val live = LivePolicySession(world, "p0", known, "matrix-S3-rebuild", config = config,
            opponentModel = UniformOpponentPolicy)
        val failure = assertFailsWith<ConditionedBeliefReconstructionRequired> {
            live.applyObserved(DeclareBlockers(players[1], mapOf(omitted to listOf(attacker))))
        }
        assertEquals("EXACT_MEMBER_ZERO_MASS", failure.reasonCode)
        assertEquals(0, live.appliedActions)
        return buildJsonObject {
            put("scenario", "S3-rebuild-required")
            put("fixture", "authored-public-bear")
            put("seed", 42613)
            put("reasonCode", failure.reasonCode)
            put("policyIdentity", metadataSession.policyIdentity)
            put("inferenceModelIdentity", inferenceIdentity)
            put("exactObservationFailure", failure.exactObservationFailure?.counts?.toString() ?: "none")
            put("beforeCommitments", before)
            put("afterCommitments", commitments(world))
            put("appliedActions", live.appliedActions)
        }
    }

    private fun compareOrCaptureMatrixGoldens(behavior: String, identities: String) {
        val artifacts = mapOf(
            "search-matrix.behavior.jsonl" to behavior,
            "search-matrix.policy-identity.jsonl" to identities,
        )
        if (System.getenv("MTG_CAPTURE_GOLDENS") == "1") {
            val source = Path.of(requireNotNull(System.getenv("MTG_SOURCE_JSON")))
            val expectedSha = requireNotNull(System.getenv("MTG_GOLDEN_BASELINE_SHA"))
            val provenance = researchJson.parseToJsonElement(Files.readString(source)).jsonObject
            check(provenance.getValue("commit").jsonPrimitive.content == expectedSha)
            check(provenance.getValue("diff").jsonPrimitive.content.isEmpty() &&
                provenance.getValue("status").jsonPrimitive.content.isEmpty())
            val directory = Path.of("build", "golden-capture")
            Files.createDirectories(directory)
            for ((name, bytes) in artifacts) {
                val output = directory.resolve(name)
                Files.writeString(output, bytes)
                Files.writeString(output.resolveSibling("$name.source.json"), buildJsonObject {
                    put("sourceSha", expectedSha)
                    put("bytesSha256", MessageDigest.getInstance("SHA-256")
                        .digest(bytes.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) })
                }.toString() + "\n")
            }
            error("Captured both matrix goldens in $directory; review and pin before comparison. Capture is not verification.")
        }
        val expected = artifacts.keys.associateWith { name ->
            val pinned = requireNotNull(javaClass.getResourceAsStream("/goldens/$name")) {
                "Missing $name golden; capture and accept it on the recorded unretired baseline first"
            }.bufferedReader().use { it.readText() }
            val metadata = requireNotNull(javaClass.getResourceAsStream("/goldens/$name.source.json")) {
                "Missing $name.source.json; accept payload and provenance together"
            }.bufferedReader().use { researchJson.parseToJsonElement(it.readText()).jsonObject }
            val sourceSha = metadata.getValue("sourceSha").jsonPrimitive.content
            val bytesSha = metadata.getValue("bytesSha256").jsonPrimitive.content
            check(Regex("[0-9a-f]{40}").matches(sourceSha)) { "Invalid $name source SHA" }
            check(Regex("[0-9a-f]{64}").matches(bytesSha)) { "Invalid $name byte SHA-256" }
            val pinnedSha = MessageDigest.getInstance("SHA-256")
                .digest(pinned.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            assertEquals(bytesSha, pinnedSha, "$name provenance does not match pinned bytes")
            pinned
        }
        artifacts.forEach { (name, actual) -> assertEquals(expected.getValue(name), actual, name) }
    }
}
