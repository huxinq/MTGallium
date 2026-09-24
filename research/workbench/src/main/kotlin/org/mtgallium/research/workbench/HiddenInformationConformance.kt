package org.mtgallium.research.workbench

import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.*

@Serializable
data class HiddenInformationCheckPlan(
    val game: GamesPlan,
    val seeds: List<Long> = listOf(101, 102, 103, 104),
    val policies: List<String> = emptyList(),
    val permutations: Int = 4,
    val positionsPerCategory: Int = 2,
    val maximumCorpusDecisions: Int = 512,
) {
    init {
        require(seeds.isNotEmpty() && seeds.distinct().size == seeds.size)
        require(permutations > 0 && positionsPerCategory > 0 && maximumCorpusDecisions > 0)
        require(game.maximumSeconds == null) { "Conformance requires fixed work, without a time limit" }
        require(game.policies.all { it in setOf("heuristic", "production") })
    }
}

/** In-memory factual snapshot; game ID, seed and accepted decision index are retained. */
data class HiddenInformationPosition(
    val seed: Long,
    val gameId: String,
    val decisionIndex: Int,
    val actor: String,
    val category: String,
    val world: ArgentumSearchWorld,
)

/** Deterministic reservoir per seat and decision category, across the requested games. */
fun hiddenInformationCorpus(plan: HiddenInformationCheckPlan): List<HiddenInformationPosition> {
    val registry = buildRegistry()
    val buckets = sortedMapOf<String, MutableList<HiddenInformationPosition>>()
    val seen = mutableMapOf<String, Int>()
    for (seed in plan.seeds) {
        val id = "hidden-information-$seed"
        val game = PythonGame.create(plan.game.copy(seed = seed, shadowPolicies = emptyList()), registry, id)
        playGame(game.world, game.players, seed, plan.maximumCorpusDecisions, beforeChoice = { world, request, index ->
            val info = request.information()
            val observation = info.observation
            val families = request.expansion.candidates.map { it.operationFamily.name }
            val category = when {
                families.any { it.contains("MULLIGAN") || it.contains("KEEP_HAND") || it.contains("BOTTOM") } -> "MULLIGAN"
                families.any { it.contains("ATTACK") } -> "ATTACK"
                families.any { it.contains("BLOCK") } -> "BLOCK"
                observation.stack.isNotEmpty() -> "STACK_RESPONSE"
                observation.phase.contains("MAIN") -> observation.phase
                else -> observation.step
            }
            val key = "${request.actor}:$category"
            val count = seen.getOrDefault(key, 0) + 1
            seen[key] = count
            val bucket = buckets.getOrPut(key) { mutableListOf() }
            val slot = if (bucket.size < plan.positionsPerCategory) bucket.size else
                Random(ComponentSeeds.derive(seed, index, "corpus-reservoir")).nextInt(count)
            if (slot < plan.positionsPerCategory) {
                val position = HiddenInformationPosition(seed, id, index, request.actor, category,
                    world.fork() as ArgentumSearchWorld)
                if (slot == bucket.size) bucket.add(position) else bucket[slot] = position
            }
        })
    }
    return buckets.values.flatten().sortedWith(compareBy({ it.seed }, { it.decisionIndex }))
}

@Serializable
data class ConformanceCandidate(
    val signature: String, val visits: Int, val mean: Double,
    val settlements: SearchSettlementCounts,
)

@Serializable
data class ConformanceBudget(val simulations: Int, val particles: Int, val depth: Int)

@Serializable
data class ConformanceSelection(
    val choice: String,
    val menu: List<String>,
    val candidates: List<ConformanceCandidate>? = null,
    val simulations: Int? = null,
    val budget: ConformanceBudget? = null,
)

@Serializable
data class HiddenInformationFinding(
    val level: String,
    val seed: Long,
    val gameId: String,
    val decisionIndex: Int,
    val actor: String,
    val category: String,
    val permutationSeed: Long?,
    val original: ConformanceSelection?,
    val permuted: ConformanceSelection?,
    val error: String? = null,
)

@Serializable
data class HiddenInformationReport(
    val policy: String,
    val corpusPositions: Int,
    val positionsChecked: Int,
    val permutationsAccepted: Int,
    val permutationsRejected: Int,
    val rejectionReasons: Map<String, Int>,
    val coverage: Map<String, Int>,
    val budgets: List<ConformanceBudget>,
    val findings: List<HiddenInformationFinding>,
) {
    val passed: Boolean get() = findings.isEmpty() && positionsChecked > 0
}

/** Fresh providers/sessions on factual forks. Stateful policy history before this position is not replayed. */
fun checkHiddenInformation(
    plan: HiddenInformationCheckPlan,
    positions: List<HiddenInformationPosition> = hiddenInformationCorpus(plan),
    providers: List<NativePolicyProvider> = NativePolicies.installed.providers,
): List<HiddenInformationReport> {
    val policies = NativePolicies(providers)
    policies.checkSettings(plan.game)
    val names = plan.policies.ifEmpty { providers.flatMap { it.policies }.sorted() }
    require(names.distinct().size == names.size)
    return names.map { name ->
        var accepted = 0
        var checked = 0
        val rejections = sortedMapOf<String, Int>()
        val coverage = sortedMapOf<String, Int>()
        val findings = mutableListOf<HiddenInformationFinding>()
        val budgets = linkedSetOf<ConformanceBudget>()
        fun reject(reason: String) { rejections[reason] = rejections.getOrDefault(reason, 0) + 1 }
        for (position in positions) {
            fun finding(level: String, permutationSeed: Long?, a: ConformanceSelection?, b: ConformanceSelection?, error: String? = null) {
                findings += HiddenInformationFinding(level, position.seed, position.gameId,
                    position.decisionIndex, position.actor, position.category, permutationSeed, a, b, error)
            }
            fun select(source: ArgentumSearchWorld): ConformanceSelection {
                val world = source.fork() as ArgentumSearchWorld
                check(world.actorToAct() == position.actor && world.acceptedDecisionCountForHost == position.decisionIndex)
                val game = NativePolicyContext(plan.game.copy(seed = position.seed), world, position.gameId,
                    plan.game.decks.mapIndexed { i, deck -> "p$i" to deck }.toMap())
                val seed = ComponentSeeds.derive(position.seed, position.actor, position.decisionIndex.toString())
                return when (val policy = policies.create(name, game, position.actor)) {
                    is NativePolicy.Direct -> {
                        val request = world.decisionContext(policy.player.view)
                        val choice = policy.player.choose(request, seed)
                        check(choice in request.expansion.candidates) { "Choice outside admitted menu" }
                        ConformanceSelection(choice.signature, request.expansion.candidates.map { it.signature })
                    }
                    is NativePolicy.Search -> {
                        val specification = policy.session.behaviorSpecification
                        require(specification.search.wallClockBudgetMillis == null) { "Wall-clock search is not conformable" }
                        val menu = world.decisionContext().expansion.candidates.map { it.signature }
                        val selection = policy.session.select(world, position.actor, seed)
                        val result = (selection as? RootActionSelection.Searched)?.search
                        ConformanceSelection(selection.choice.signature, menu,
                            result?.candidates?.map { ConformanceCandidate(it.choice.signature, it.visits,
                                it.meanValue, result.settlementCountsFor(it.choice)) },
                            result?.diagnostics?.simulations, ConformanceBudget(specification.search.simulations,
                                specification.particles, specification.search.maxPolicyDecisions))
                    }
                }
            }
            val original = try { select(position.world) } catch (failure: Exception) {
                finding("ERROR", null, null, null, "${failure.javaClass.simpleName}: ${failure.message}")
                continue
            }
            original.budget?.let { budgets += it }
            val repeat = try { select(position.world) } catch (failure: Exception) {
                finding("ERROR", null, original, null, "${failure.javaClass.simpleName}: ${failure.message}")
                continue
            }
            if (original != repeat) {
                finding("NONDETERMINISTIC", null, original, repeat)
                continue
            }
            var positionAccepted = false
            for (index in 0 until plan.permutations) {
                val seed = ComponentSeeds.derive(position.seed, position.decisionIndex, "hidden-permutation-$index")
                val proposal = position.world.permuteHiddenTruthForHost(position.actor, seed)
                val world = proposal.world
                if (world == null) { reject(requireNotNull(proposal.rejection)); continue }
                accepted++
                val changed = try { select(world) } catch (failure: Exception) {
                    finding("ERROR", seed, original, null, "${failure.javaClass.simpleName}: ${failure.message}")
                    continue
                }
                if (original.menu != changed.menu) { accepted--; reject("POLICY_MENU_DIFFERS"); continue }
                positionAccepted = true
                if (original.choice != changed.choice) finding("DECISION_DIFFERS", seed, original, changed)
                else if (original != changed) finding("STATISTICS_DIFFER", seed, original, changed)
            }
            if (positionAccepted) {
                checked++
                val key = "${position.actor}:${position.category}"
                coverage[key] = coverage.getOrDefault(key, 0) + 1
            }
        }
        HiddenInformationReport(name, positions.size, checked, accepted, rejections.values.sum(), rejections,
            coverage, budgets.toList(), findings)
    }
}

fun runHiddenInformationCheck(plan: HiddenInformationCheckPlan, output: Path): List<HiddenInformationReport> {
    Files.createDirectories(output.toAbsolutePath().parent)
    Files.createDirectory(output)
    writeJson(output.resolve("plan.json"), plan)
    writeJson(output.resolve("context.json"), executionContext())
    val positions = hiddenInformationCorpus(plan)
    writeJson(output.resolve("corpus.json"), positions.map { position -> buildJsonObject {
        put("seed", position.seed); put("gameId", position.gameId)
        put("decisionIndex", position.decisionIndex); put("actor", position.actor); put("category", position.category)
        put("informationDigest", position.world.informationState(position.actor).informationStateDigest)
    } })
    val names = plan.policies.ifEmpty { NativePolicies.installed.providers.flatMap { it.policies }.sorted() }
    val reports = names.mapIndexed { index, name ->
        System.err.println("Checking $name on ${positions.size} positions with ${plan.permutations} permutations each")
        checkHiddenInformation(plan.copy(policies = listOf(name)), positions).single().also {
            writeJson(output.resolve("policy-$index.json"), it)
        }
    }
    writeJson(output.resolve("summary.json"), reports.map { buildJsonObject {
        put("policy", it.policy); put("passed", it.passed); put("positionsChecked", it.positionsChecked)
        put("permutationsAccepted", it.permutationsAccepted); put("permutationsRejected", it.permutationsRejected)
        put("findings", it.findings.size)
    } })
    return reports
}
