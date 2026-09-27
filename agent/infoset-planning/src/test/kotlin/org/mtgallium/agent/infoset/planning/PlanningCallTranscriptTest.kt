package org.mtgallium.agent.infoset.planning

import org.mtgallium.agent.infoset.core.*

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.lang.reflect.Proxy
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A small, complete hypothesis records the planner's calls without an engine or private data. */
class PlanningCallTranscriptTest {
    @Test
    fun `same seed preserves the complete world-call transcript and result`() {
        fun run(): Pair<List<String>, String> {
            val calls = mutableListOf<String>()
            val search = planner()
            val result = search.search("p0", belief(TranscriptWorld(calls).world), 731L)
            assertEquals(6, result.candidates.sumOf { it.visits })
            assertEquals(6, result.candidateSettlementCounts.values.sumOf { it.successfulBackups })
            assertTrue(calls.any { it.startsWith("decisionContext:") })
            assertTrue(calls.any { it.startsWith("step:") })
            assertTrue(result.diagnostics.evaluatorCalls > 0)
            assertTrue(calls.any { it.startsWith("informationState:") })
            val stable = result.copy(diagnostics = result.diagnostics.copy(evaluatorNanos = 0))
            return calls.toList() to CanonicalJson.format.encodeToString(InformationSetSearchResult.serializer(), stable)
        }
        val baseline = run()
        assertEquals(baseline, run())
        val modes = retainedModesTranscript()
        assertEquals(modes, retainedModesTranscript())
        val transcript = "== baseline ==\n" + baseline.first.joinToString("\n", postfix = "\n") +
            baseline.second + "\n" + modes
        compareOrCapturePlanningGolden(transcript)
    }

    /** Every retained route contributes complete calls and result bytes to one guarded golden. */
    private fun retainedModesTranscript(): String {
        fun record(name: String, config: InformationSetSearchConfig, seed: Long,
            priorEnabled: Boolean = false, rolloutScheduleEnabled: Boolean = false,
            simulationScheduleEnabled: Boolean = false, annotatedOpponent: Boolean = false,
            profilePrunedPasses: Boolean = false, candidateCount: Int = 2): String {
            val calls = mutableListOf<String>()
            val prior: SearchPrior? = if (priorEnabled) object : SearchPrior {
                override val configurationId = "transcript-prior"
                override val candidateLimit = 64
                override val explorationConstant = 1.0
                override fun probabilities(context: DecisionContext): Map<String, Double> {
                    calls += "prior:${context.menu.candidates.joinToString { it.display.label }}"
                    return context.menu.candidates.associate { it.signature to
                        if (it.display.label == "B") 1.0 else 0.0 }
                }
            } else null
            val observer = TranscriptRolloutObserver(calls)
            val rollout: ActionSelector = if (rolloutScheduleEnabled) object : RolloutPolicySchedule {
                override val id = "transcript-rollout-schedule"
                override fun atStep(step: Int): ActionSelector {
                    calls += "rolloutPolicyAtStep:$step"
                    return observer
                }
                override fun select(context: DecisionContext, policySeed: Long,
                    sampleSeed: Long): OpponentPolicyDecision = error("atStep must select the policy")
            } else UniformOpponentPolicy
            val opponent: OpponentPolicy = if (annotatedOpponent) object : OpponentPolicy {
                override val id = "transcript-annotating-opponent"
                override val requiresArgentumAiChoiceTag = true
                override fun distribution(context: DecisionContext,
                    policySeed: Long): ProbabilityDistribution<SemanticChoice> {
                    calls += "annotatingOpponent:${context.view.annotations}:${context.menu.candidates.size}"
                    return ProbabilityDistribution.uniform(context.menu.candidates)
                }
            } else UniformOpponentPolicy
            val root = TranscriptWorld(calls, candidateCount = candidateCount,
                profilePrunedPasses = profilePrunedPasses).world
            val scheduledWorlds = if (simulationScheduleEnabled)
                SimulationWorldSchedule(List(config.simulations) {
                    TranscriptWorld(calls, candidateCount = candidateCount,
                        profilePrunedPasses = profilePrunedPasses).world
                }) else null
            val result = planner(config = config, prior = prior, rollout = rollout,
                opponent = opponent).search("p0", belief(root), seed, scheduledWorlds)
            if (rolloutScheduleEnabled) {
                assertTrue(observer.observations.isNotEmpty())
                assertEquals(observer.selectedCount, observer.observations.size)
                assertTrue(observer.observations.all { it.searchSeed == seed })
            }
            val stable = result.copy(diagnostics = result.diagnostics.copy(evaluatorNanos = 0))
            return "== $name ==\n" + calls.joinToString("\n", postfix = "\n") +
                CanonicalJson.format.encodeToString(InformationSetSearchResult.serializer(), stable) + "\n"
        }
        val information = LeafEvaluationConfig(LeafEvaluationMethod.CURRENT_INFORMATION_STATE)
        val bounded = LeafEvaluationConfig(LeafEvaluationMethod.BOUNDED_ROLLOUT)
        val short = InformationSetSearchConfig(simulations = 2, maxPolicyDecisions = 2, leaf = information)
        return buildString {
            append(record("PUCT", short, 733L, priorEnabled = true))
            append(record("widening", InformationSetSearchConfig(simulations = 4,
                maxPolicyDecisions = 1, leaf = information, initialExpansionLimit = 1,
                wideningThresholds = listOf(1), wideningLimits = listOf(3)), 734L, candidateCount = 3))
            for (cutoff in RolloutCutoff.entries) append(record("cutoff-${cutoff.name}",
                short.copy(leaf = LeafEvaluationConfig(LeafEvaluationMethod.BOUNDED_ROLLOUT, cutoff)), 735L))
            append(record("turn-horizon", short.copy(leaf = bounded, maxPolicyDecisions = 1,
                rolloutTurnHorizon = RolloutTurnHorizon(1, 8)), 736L))
            append(record("simulation-world-schedule", short, 737L, simulationScheduleEnabled = true))
            append(record("annotating-opponent", short.copy(simulations = 4,
                maxPolicyDecisions = 3), 738L, annotatedOpponent = true))
            append(record("rollout-policy-schedule", short.copy(simulations = 3,
                maxPolicyDecisions = 4, leaf = bounded), 739L, rolloutScheduleEnabled = true))
            for (rule in listOf(QuiescencePassRule.RULES_FORCED_V1,
                QuiescencePassRule.PROFILE_FORCED_WHILE_VOLATILE_V1)) {
                append(record("volatile-${rule.name}", short.copy(maxPolicyDecisions = 1,
                    leaf = LeafEvaluationConfig(LeafEvaluationMethod.BOUNDED_ROLLOUT,
                        RolloutCutoff.QUIESCENCE, quiescencePasses = rule)), 740L,
                    profilePrunedPasses = true))
            }
        }
    }

    @Test
    fun `PUCT asks the prior for the admitted root menu`() {
        val calls = mutableListOf<String>()
        val prior = object : SearchPrior {
            override val configurationId = "scripted-prior"
            override val candidateLimit = 64
            override val explorationConstant = 1.0
            override fun probabilities(context: DecisionContext): Map<String, Double> {
                calls += "prior:${context.menu.candidates.joinToString { it.display.label }}"
                return context.menu.candidates.associate { it.signature to
                    if (it.display.label == "B") 1.0 else 0.0 }
            }
        }
        val result = planner(simulations = 2, prior = prior).search("p0", belief(TranscriptWorld(calls).world), 733L)
        assertEquals("B", result.chosen.display.label)
        assertTrue(calls.any { it == "prior:A, B" })
        assertEquals(2, result.candidateSettlementCounts.values.sumOf { it.successfulBackups })
    }

    @Test
    fun `UCT widens a scripted non-exhaustive root after its first visit`() {
        val calls = mutableListOf<String>()
        val result = planner(config = InformationSetSearchConfig(
            simulations = 4, maxPolicyDecisions = 1,
            leaf = LeafEvaluationConfig(LeafEvaluationMethod.CURRENT_INFORMATION_STATE),
            initialExpansionLimit = 1, wideningThresholds = listOf(1), wideningLimits = listOf(3),
        )).search("p0", belief(TranscriptWorld(calls, candidateCount = 3).world), 734L)
        assertEquals(3, result.candidates.size)
        assertTrue(result.diagnostics.wideningEvents > 0)
        assertTrue(calls.any { it == "expandChoices:0:3" })
    }

    @Test
    fun `three retained cutoff modes keep their own world-call routes`() {
        for (cutoff in RolloutCutoff.entries) {
            val calls = mutableListOf<String>()
            val result = planner(config = InformationSetSearchConfig(
                simulations = 2, maxPolicyDecisions = 2,
                leaf = LeafEvaluationConfig(LeafEvaluationMethod.BOUNDED_ROLLOUT, cutoff),
            )).search("p0", belief(TranscriptWorld(calls).world), 735L)
            val leaf = CanonicalJson.format.encodeToJsonElement(
                InformationSetSearchDiagnostics.serializer(), result.diagnostics)
                .jsonObject.getValue("leaf").jsonObject
            assertEquals(cutoff.name, leaf.getValue("cutoff").jsonPrimitive.content)
            assertEquals(2, result.candidates.sumOf { it.visits })
            assertTrue(calls.any { it.startsWith("step:") }, cutoff.name)
        }
    }

    @Test
    fun `turn horizon reaches the next turn before evaluating`() {
        val calls = mutableListOf<String>()
        val result = planner(config = InformationSetSearchConfig(
            simulations = 2, maxPolicyDecisions = 1,
            leaf = LeafEvaluationConfig(LeafEvaluationMethod.BOUNDED_ROLLOUT),
            rolloutTurnHorizon = RolloutTurnHorizon(1, 8),
        )).search("p0", belief(TranscriptWorld(calls).world), 736L)
        assertEquals(2, result.candidates.sumOf { it.visits })
        assertTrue(calls.any { it.startsWith("step:1:") })
        assertTrue(calls.any { it.startsWith("informationState:2:") })
    }

    @Test
    fun `scheduled worlds are forked and evaluated during search`() {
        val calls = mutableListOf<String>()
        val root = TranscriptWorld(calls).world
        val schedule = SimulationWorldSchedule(List(2) { TranscriptWorld(calls).world })
        val result = planner(simulations = 2).search("p0", belief(root), 737L, schedule)
        assertEquals(2, result.diagnostics.simulations)
        assertTrue(calls.any { it == "informationState:1:p0" })
        assertTrue(calls.any { it.startsWith("fork:") })
    }

    @Test
    fun `rollout policy schedule observes each continuation step`() {
        val calls = mutableListOf<String>()
        val observer = TranscriptRolloutObserver(calls)
        val schedule = object : RolloutPolicySchedule {
            override val id = "scripted-rollout-schedule"
            override fun atStep(step: Int): ActionSelector {
                calls += "rolloutPolicyAtStep:$step"
                return observer
            }
            override fun select(context: DecisionContext, policySeed: Long,
                sampleSeed: Long): OpponentPolicyDecision = error("atStep must select the rollout policy")
        }
        val result = planner(rollout = schedule, config = InformationSetSearchConfig(
            simulations = 3, maxPolicyDecisions = 4,
            leaf = LeafEvaluationConfig(LeafEvaluationMethod.BOUNDED_ROLLOUT),
        )).search("p0", belief(TranscriptWorld(calls).world), 739L)
        assertEquals(3, result.candidates.sumOf { it.visits })
        assertTrue(calls.any { it == "rolloutPolicyAtStep:0" })
        assertTrue(calls.any { it == "rolloutPolicyAtStep:1" })
        assertEquals(observer.selectedCount, observer.observations.size)
        assertTrue(observer.observations.isNotEmpty())
        assertEquals(setOf(0, 1, 2), observer.observations.map { it.simulationIndex }.toSet())
        assertTrue(observer.observations.all { it.searchSeed == 739L && it.depth in 1..3 })
        assertTrue(observer.observations.any { it.depth == 1 && it.actor == "p1" })
        assertTrue(observer.observations.any { it.depth == 2 && it.actor == "p0" })
        assertTrue(calls.any { it == "informationState:1:p1" })
    }

    @Test
    fun `profile forced volatile pass advances before leaf evaluation`() {
        fun run(rule: QuiescencePassRule): Pair<InformationSetSearchResult, List<Int>> {
            val calls = mutableListOf<String>()
            val evaluatedTurns = mutableListOf<Int>()
            val result = planner(evaluatedTurns = evaluatedTurns, config = InformationSetSearchConfig(
                simulations = 2, maxPolicyDecisions = 1,
                leaf = LeafEvaluationConfig(LeafEvaluationMethod.BOUNDED_ROLLOUT,
                    RolloutCutoff.QUIESCENCE, quiescencePasses = rule),
            )).search("p0", belief(TranscriptWorld(calls, profilePrunedPasses = true).world), 740L)
            assertTrue(calls.any { it == "step:1:pass" } ==
                (rule == QuiescencePassRule.PROFILE_FORCED_WHILE_VOLATILE_V1))
            return result to evaluatedTurns
        }
        val (rules, rulesTurns) = run(QuiescencePassRule.RULES_FORCED_V1)
        assertEquals(listOf(1, 1), rulesTurns)
        assertEquals(2, rules.diagnostics.quiescenceFallbacks)
        val (profile, profileTurns) = run(QuiescencePassRule.PROFILE_FORCED_WHILE_VOLATILE_V1)
        assertEquals(listOf(2, 2), profileTurns)
        assertEquals(2, profile.diagnostics.quiescenceProfileForcedPasses)
        assertEquals(0, profile.diagnostics.quiescenceFallbacks)
    }

    @Test
    fun `annotating opponent receives the production menu`() {
        val calls = mutableListOf<String>()
        val policy = object : OpponentPolicy {
            override val id = "scripted-annotating-opponent"
            override val requiresArgentumAiChoiceTag = true
            override fun distribution(context: DecisionContext, policySeed: Long): ProbabilityDistribution<SemanticChoice> {
                assertTrue(context.view.annotations)
                assertTrue(context.menu.candidates.all { "scripted" in it.display.policyTags })
                calls += "annotatingOpponent:${context.menu.candidates.size}"
                return ProbabilityDistribution.uniform(context.menu.candidates)
            }
        }
        val result = planner(opponent = policy, config = InformationSetSearchConfig(
            simulations = 4, maxPolicyDecisions = 3,
            leaf = LeafEvaluationConfig(LeafEvaluationMethod.CURRENT_INFORMATION_STATE),
        )).search("p0", belief(TranscriptWorld(calls).world), 738L)
        assertEquals(4, result.diagnostics.simulations)
        assertTrue(result.diagnostics.policyAnnotatedExpansions > 0)
        assertTrue(calls.any { it.startsWith("annotatingOpponent:") })
    }

    private fun planner(
        simulations: Int = 6,
        prior: SearchPrior? = null,
        opponent: OpponentPolicy = UniformOpponentPolicy,
        rollout: ActionSelector = UniformOpponentPolicy,
        evaluatedTurns: MutableList<Int>? = null,
        config: InformationSetSearchConfig = InformationSetSearchConfig(simulations = simulations,
            maxPolicyDecisions = 1, leaf = LeafEvaluationConfig(LeafEvaluationMethod.CURRENT_INFORMATION_STATE)),
    ) = InformationSetSearch(
        config, opponent, rollout, rollout,
        LeafValueSource.Information(object : InformationStateEvaluator {
            override val id = "scripted-information-leaf"
            override fun evaluate(information: InformationStateRepresentation, rootPlayer: String): Double {
                evaluatedTurns?.add(information.observation.turnNumber)
                return if (information.observation.observationDigest.endsWith(":A")) 0.4 else -0.2
            }
        }), prior,
    )

    private fun belief(world: SearchWorld) = ParticleSet(
        listOf(Weighted(world, 1.0)),
        BeliefDiagnostics(BeliefMode.CONSISTENCY_ONLY_V1, 1, 1, 0, 1.0, 1.0, 0.0, 0),
    )
}

private fun compareOrCapturePlanningGolden(actual: String) {
    val name = "planning-call-transcript.txt"
    if (System.getenv("MTG_CAPTURE_GOLDENS") == "1") {
        val source = Path.of(requireNotNull(System.getenv("MTG_SOURCE_JSON")) {
            "Golden capture requires a tools/remote source snapshot"
        })
        val expectedSha = requireNotNull(System.getenv("MTG_GOLDEN_BASELINE_SHA")) {
            "Golden capture requires the recorded unretired baseline source SHA"
        }
        val provenance = CanonicalJson.format.parseToJsonElement(Files.readString(source)).jsonObject
        check(provenance.getValue("commit").jsonPrimitive.content == expectedSha) {
            "Capture source SHA differs from the recorded unretired baseline"
        }
        check(provenance.getValue("diff").jsonPrimitive.content.isEmpty() &&
            provenance.getValue("status").jsonPrimitive.content.isEmpty()) {
            "Capture requires a clean committed source snapshot"
        }
        val output = Path.of("build", "golden-capture", name)
        Files.createDirectories(output.parent)
        Files.writeString(output, actual)
        val digest = MessageDigest.getInstance("SHA-256").digest(actual.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        Files.writeString(output.resolveSibling("$name.source.json"), buildJsonObject {
            put("sourceSha", JsonPrimitive(expectedSha))
            put("bytesSha256", JsonPrimitive(digest))
        }.toString() + "\n")
        error("Captured $output; review and pin it before comparison. Capture is not verification.")
    }
    val expected = requireNotNull(PlanningCallTranscriptTest::class.java.getResourceAsStream("/goldens/$name")) {
        "Missing $name golden; capture and accept it on the recorded unretired baseline first"
    }.bufferedReader().use { it.readText() }
    val metadata = requireNotNull(PlanningCallTranscriptTest::class.java
        .getResourceAsStream("/goldens/$name.source.json")) {
        "Missing $name.source.json; accept payload and provenance together"
    }.bufferedReader().use { CanonicalJson.format.parseToJsonElement(it.readText()).jsonObject }
    val sourceSha = metadata.getValue("sourceSha").jsonPrimitive.content
    val bytesSha = metadata.getValue("bytesSha256").jsonPrimitive.content
    check(Regex("[0-9a-f]{40}").matches(sourceSha)) { "Invalid $name source SHA" }
    check(Regex("[0-9a-f]{64}").matches(bytesSha)) { "Invalid $name byte SHA-256" }
    val pinnedSha = MessageDigest.getInstance("SHA-256")
        .digest(expected.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    assertEquals(bytesSha, pinnedSha, "$name provenance does not match pinned bytes")
    assertEquals(expected, actual)
}

private data class TranscriptRolloutObservation(
    val actor: String,
    val searchSeed: Long,
    val simulationIndex: Int,
    val depth: Int,
)

/** Records production callbacks on the selector returned by atStep, not on the schedule wrapper. */
private class TranscriptRolloutObserver(private val calls: MutableList<String>) : ActionSelector, RolloutObserver {
    override val id = "scripted-observing-rollout"
    val observations = mutableListOf<TranscriptRolloutObservation>()
    var selectedCount = 0
        private set
    private var selectedContext: DecisionContext? = null
    private var selectedChoice: SemanticChoice? = null

    override fun select(context: DecisionContext, policySeed: Long, sampleSeed: Long): OpponentPolicyDecision {
        check(selectedContext == null) { "Previous selection did not receive its observer callback" }
        val decision = UniformOpponentPolicy.select(context, policySeed, sampleSeed)
        selectedContext = context
        selectedChoice = decision.choice
        selectedCount++
        return decision
    }

    override fun observeDecision(context: DecisionContext, choice: SemanticChoice,
        searchSeed: Long, simulationIndex: Int, depth: Int) {
        assertSame(selectedContext, context)
        assertEquals(selectedChoice, choice)
        assertTrue(choice in context.menu.candidates)
        assertEquals(if (depth % 2 == 0) "p0" else "p1", context.actor)
        assertEquals(context.actor, context.information().observation.viewerId)
        observations += TranscriptRolloutObservation(context.actor, searchSeed, simulationIndex, depth)
        calls += "observeDecision:${context.actor}:${choice.signature}:$searchSeed:$simulationIndex:$depth"
        selectedContext = null
        selectedChoice = null
    }
}

private class TranscriptWorld(
    private val calls: MutableList<String>,
    private var depth: Int = 0,
    private var firstChoice: String? = null,
    private val rootActor: String = "p0",
    private val candidateCount: Int = 2,
    private val profilePrunedPasses: Boolean = false,
) {
    /** Proxy keeps this test compiling when a retired SearchWorld method is removed in PR 2. */
    val world: SearchWorld by lazy {
        Proxy.newProxyInstance(SearchWorld::class.java.classLoader,
            arrayOf(ProgressiveSearchWorld::class.java, PolicyAnnotatedSearchWorld::class.java)) {
            self, method, arguments ->
            val args = arguments?.toList().orEmpty()
            when (method.name) {
                "actorToAct" -> actorToAct()
                "decisionContext" -> decisionContext(args.single() as MenuRequest)
                "informationState" -> informationState(args.single() as String)
                "informationStateWithoutMenu" -> {
                    val viewer = args.single() as String
                    calls += "epistemicState:$depth:$viewer"
                    InformationState.capture(informationState(viewer))
                }
                "expandChoices" -> expandChoices(args.firstOrNull() as? Int)
                "expandChoicesForPolicyAdmission" -> policyAdmission(args.firstOrNull() as? Int)
                "expandChoicesWithPolicyAnnotations" -> annotatedChoices(args.firstOrNull() as? Int)
                "step" -> step(args.single() as SemanticChoice)
                "fork" -> {
                    calls += "fork:$depth"
                    TranscriptWorld(calls, depth, firstChoice, rootActor, candidateCount,
                        profilePrunedPasses).world
                }
                "terminalPayoff" -> {
                    calls += "terminalPayoff:$depth:${args.single()}"
                    null
                }
                "sampledWorldLeafValue" -> error("The retained information evaluator must not consume a complete world")
                "equals" -> self === args.single()
                "hashCode" -> System.identityHashCode(self)
                "toString" -> "TranscriptWorld(depth=$depth)"
                else -> error("Unexpected SearchWorld call ${method.name}")
            }
        } as SearchWorld
    }

    private fun choices(limit: Int? = null, annotations: Boolean = false): ActionMenu {
        val allLabels = if (depth == 0) (0 until candidateCount).map { ('A' + it).toString() } else listOf("pass")
        val labels = allLabels.take(limit ?: allLabels.size)
        val prunedPass = profilePrunedPasses && depth == 1
        val exhausted = labels.size == allLabels.size && !prunedPass
        return ActionMenu(labels.map { label -> SemanticChoice.create(
            kind = SemanticChoiceKind.ACTION,
            operationFamily = if (prunedPass)
                SemanticOperationFamily.PASS_PRIORITY else SemanticOperationFamily.OTHER,
            display = SemanticChoiceDisplay(label, policyTags = if (annotations) setOf("scripted") else emptySet()),
            canonicalPayload = buildJsonObject { put("choice", JsonPrimitive(label)) },
        ) }, exhausted,
            allLabels.size.toLong(), "transcript-v1", 1L,
            isProfileExhaustive = labels.size == allLabels.size,
            omissionReasons = when {
                prunedPass -> setOf(ActionOmissionReason.PROFILE_SUPPRESSED_STANDALONE_MANA)
                !exhausted -> setOf(ActionOmissionReason.SOURCE_NON_EXHAUSTIVE)
                else -> emptySet()
            })
    }

    private fun actorToAct(): String {
        val actor = if (depth % 2 == 0) rootActor else if (rootActor == "p0") "p1" else "p0"
        calls += "actorToAct:$depth:$actor"
        return actor
    }

    private fun decisionContext(view: MenuRequest): DecisionContext {
        calls += "decisionContext:$depth:${view.limit}:${view.admission.name}:${view.annotations}"
        val actor = actorToAct()
        val menu = if (view.annotations) annotatedChoices(view.limit) else expandChoices(view.limit)
        return DecisionContext.capture(actor, menu,
            { InformationState.capture(informationState(actor)) }, view)
    }

    private fun informationState(viewer: String): InformationStateRepresentation {
        calls += "informationState:$depth:$viewer"
        val actor = if (depth % 2 == 0) rootActor else if (rootActor == "p0") "p1" else "p0"
        return InformationStateRepresentation(
            actingPlayerId = actor,
            observation = PlayerObservationSnapshot(
                viewerId = viewer, turnNumber = 1 + depth / 2,
                phase = if (profilePrunedPasses && depth == 1) "COMBAT" else "TEST",
                step = if (profilePrunedPasses && depth == 1) "COMBAT_DAMAGE" else "SCRIPT",
                activePlayerId = "p0", priorityPlayerId = actor, players = emptyList(),
                zones = emptyList(), stack = emptyList(), pendingDecision = null,
                observationDigest = "transcript:$rootActor:$depth:$firstChoice",
            ),
            informationStateDigest = CanonicalJson.sha256("$viewer:$rootActor:$depth:$firstChoice"),
            historyCommitment = HistoryHashChain.empty(), history = emptyList(),
            candidates = if (viewer == actor) choices().candidates else emptyList(), terminated = false,
        )
    }

    private fun expandChoices(limit: Int? = null): ActionMenu {
        calls += "expandChoices:$depth:$limit"
        return choices(limit)
    }

    private fun policyAdmission(limit: Int?): ActionMenu {
        calls += "expandChoicesForPolicyAdmission:$depth:$limit"
        return choices(limit)
    }

    private fun annotatedChoices(limit: Int?): ActionMenu {
        calls += "expandChoicesWithPolicyAnnotations:$depth:$limit"
        return choices(limit, annotations = true)
    }

    private fun step(choice: SemanticChoice): SearchStepResult {
        calls += "step:$depth:${choice.display.label}"
        if (choices().candidates.none { it.signature == choice.signature }) return SearchStepResult(false)
        if (depth == 0) firstChoice = choice.display.label
        depth++
        return SearchStepResult(true)
    }

}
