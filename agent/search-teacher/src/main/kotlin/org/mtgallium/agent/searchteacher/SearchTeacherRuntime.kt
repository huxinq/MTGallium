package org.mtgallium.agent.searchteacher

import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.registry.CardRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.mtgallium.agent.infoset.argentum.ArgentumObservedStep
import org.mtgallium.agent.infoset.argentum.ArgentumResolvedChoice
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.BeliefDiagnostics
import org.mtgallium.agent.infoset.core.BeliefArchitecture
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.InformationSetSearchResult
import org.mtgallium.agent.infoset.core.LeafEvaluationConfig
import org.mtgallium.agent.infoset.core.LeafEvaluator
import org.mtgallium.agent.infoset.core.LeafStateSource
import org.mtgallium.agent.infoset.core.OpponentPolicy
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile

private val strictRuntimeJson = Json { ignoreUnknownKeys = false }

@Serializable
data class SearchTeacherDeckManifest(
    val id: String,
    val name: String,
    val format: String,
    val publishedDate: String,
    val source: String,
    val mainDeck: Map<String, Int>,
    val sideboard: Map<String, Int>,
) {
    init {
        require(mainDeck.values.sum() == 60) { "Search Teacher requires an exact 60-card main deck" }
    }

    companion object {
        fun frozenMonoRed(): SearchTeacherDeckManifest {
            val stream = requireNotNull(
                SearchTeacherDeckManifest::class.java.getResourceAsStream(
                    "/decks/mono-red-standard-2026-07-30.json"
                )
            ) { "Frozen Search Teacher deck manifest is missing" }
            return stream.bufferedReader().use {
                strictRuntimeJson.decodeFromString<SearchTeacherDeckManifest>(it.readText())
            }
        }
    }
}

data class SearchTeacherRuntimeConfig(
    val profileId: String = SEARCH_TEACHER_UNPROFILED_RUNTIME_ID,
    val particles: Int = 8,
    val simulations: Int = 64,
    val maxPolicyDecisions: Int = 32,
    val explorationConstant: Double = 1.4,
    val leaf: LeafEvaluationConfig = LeafEvaluationConfig(
        LeafStateSource.BOUNDED_ROLLOUT,
        LeafEvaluator.MTGALLIUM_VISIBLE_V2,
    ),
    val actionSpaceProfile: SearchActionSpaceProfile =
        SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
    val beliefMode: BeliefMode = BeliefMode.CONSISTENCY_ONLY_V1,
    val beliefArchitecture: BeliefArchitecture = BeliefArchitecture.SEQUENTIAL_B_V1,
    val baseSeed: Long = 20260825L,
    val policyCompression: PolicyCompressionConfig = PolicyCompressionConfig(enabled = false),
    val searchReuse: SearchReuseConfig = SearchReuseConfig(enabled = false),
    val initialExpansionLimit: Int = 64,
    val wideningThresholds: List<Int> = listOf(64, 256, 1024),
    val wideningLimits: List<Int> = listOf(128, 256, 512),
    val maxQuiescenceDecisions: Int = 32,
    val maxQuiescenceForcedPasses: Int = 256,
    val cacheSimulationTransitions: Boolean = true,
    val wallClockBudgetMillis: Long? = null,
    val minimumSimulations: Int = 1,
) {
    init {
        require(particles in setOf(8, 16, 32, 64))
        require(simulations in setOf(64, 256, 1024, 4096))
        require(maxPolicyDecisions > 0)
        require(explorationConstant >= 0.0 && explorationConstant.isFinite())
        require(!policyCompression.enabled) {
            "Profile-only singleton automation is disabled because it can remove genuine player decisions"
        }
    }

    val displayName: String get() = "$profileId · ${particles}×${simulations} · ${actionSpaceProfile.profileId}"

    fun policyParameters(): SearchTeacherPolicyParameters = SearchTeacherPolicyParameters(
        particles = particles,
        simulations = simulations,
        maxPolicyDecisions = maxPolicyDecisions,
        explorationConstant = explorationConstant,
        leaf = leaf,
        actionSpaceProfile = actionSpaceProfile,
        beliefMode = beliefMode,
        beliefArchitecture = beliefArchitecture,
        baseSeed = baseSeed,
        profileId = profileId,
        policyCompression = policyCompression,
        searchReuse = searchReuse,
        initialExpansionLimit = initialExpansionLimit,
        wideningThresholds = wideningThresholds,
        wideningLimits = wideningLimits,
        maxQuiescenceDecisions = maxQuiescenceDecisions,
        maxQuiescenceForcedPasses = maxQuiescenceForcedPasses,
        cacheSimulationTransitions = cacheSimulationTransitions,
        wallClockBudgetMillis = wallClockBudgetMillis,
        minimumSimulations = minimumSimulations,
    )
}

@Serializable
data class SearchTeacherPilotSpecification(
    val schemaVersion: Int,
    val id: String,
    val deckId: String,
    val particles: Int,
    val simulations: Int,
    val maxPolicyDecisions: Int,
    val explorationConstant: Double,
    val leaf: LeafEvaluationConfig,
    val actionSpaceProfile: SearchActionSpaceProfile,
    val beliefMode: BeliefMode,
    val beliefArchitecture: BeliefArchitecture,
    val policyCompression: PolicyCompressionConfig,
    val searchReuse: SearchReuseConfig,
    val opponentPolicyId: String,
    val humanTacticalReviewWaived: Boolean,
) {
    init {
        require(schemaVersion == 1)
        require(id == PROFILE_ID)
        require(deckId == SearchTeacherDeckManifest.frozenMonoRed().id)
        require(actionSpaceProfile == SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1)
        require(leaf == LeafEvaluationConfig(LeafStateSource.BOUNDED_ROLLOUT, LeafEvaluator.MTGALLIUM_VISIBLE_V2))
        require(particles == 8 && simulations == 64 && maxPolicyDecisions == 32)
        require(policyCompression.enabled) {
            "The frozen v1 profile must retain its historical policy-compression setting"
        }
        require(!searchReuse.enabled)
        require(opponentPolicyId == "mono-red-mixture-70-10-10-10-v2")
        require(humanTacticalReviewWaived)
    }

    fun runtimeConfig(baseSeed: Long = 20260825L): SearchTeacherRuntimeConfig {
        require(!policyCompression.enabled) {
            "Frozen profile $id is retained for historical comparison and cannot run under the " +
                "current player-decision semantics because it enables profile-only singleton automation"
        }
        return SearchTeacherRuntimeConfig(
            profileId = id,
            particles = particles,
            simulations = simulations,
            maxPolicyDecisions = maxPolicyDecisions,
            explorationConstant = explorationConstant,
            leaf = leaf,
            actionSpaceProfile = actionSpaceProfile,
            beliefMode = beliefMode,
            beliefArchitecture = beliefArchitecture,
            baseSeed = baseSeed,
            policyCompression = policyCompression,
            searchReuse = searchReuse,
        )
    }

    companion object {
        const val PROFILE_ID = "mono-red-bo1-pilot-teacher-v1"

        fun frozenMonoRed(): SearchTeacherPilotSpecification {
            val stream = SearchTeacherPilotSpecification::class.java.getResourceAsStream(
                "/profiles/$PROFILE_ID.json"
            ) ?: error("Missing frozen Search Teacher pilot specification")
            return stream.bufferedReader().use {
                strictRuntimeJson.decodeFromString<SearchTeacherPilotSpecification>(it.readText())
            }
        }
    }
}

data class SearchTeacherDecision(
    val choice: SemanticChoice,
    val resolved: ArgentumResolvedChoice,
    val search: InformationSetSearchResult?,
    val belief: BeliefDiagnostics,
    val latencyMillis: Double,
    val decisionIndex: Int,
    val selectionKind: SearchTeacherSelectionKind,
)

/**
 * Stateful, perspective-safe Search Teacher policy for a live shadow world.
 *
 * The host owns reconstruction. This class owns only policy state: it consumes accepted raw
 * actions, advances the semantic history and particle belief, and resolves (but does not apply)
 * the next selected choice.
 */
class SearchTeacherRuntimeSession(
    private val world: ArgentumSearchWorld,
    private val teacher: String,
    registry: CardRegistry,
    knownDecks: Map<String, Map<String, Int>>,
    private val gameId: String,
    private val config: SearchTeacherRuntimeConfig = SearchTeacherRuntimeConfig(),
    private val opponentModel: OpponentPolicy = defaultMonoRedOpponentPolicy(),
) {
    private val parameters = config.policyParameters()
    private val policy = SearchTeacherPolicySession(
        root = world,
        viewer = teacher,
        registry = registry,
        knownDecks = knownDecks,
        parameters = parameters,
        opponentPolicy = opponentModel,
        gameId = gameId,
    )
    private var decisionIndex = 0

    val appliedActions: Int get() = decisionIndex
    val authoritativeFingerprint: String get() = world.authoritativeFingerprint()
    val canChoose: Boolean get() = world.actorToAct() == teacher

    fun applyObserved(action: GameAction): ArgentumObservedStep {
        val actor = requireNotNull(world.actorToAct()) { "Observed action after terminal state" }
        val observed = world.applyObservedAction(action)
        check(observed.result.accepted) { observed.result.diagnostic ?: "Observed semantic action was rejected" }
        policy.observeAccepted(
            actual = world,
            actor = actor,
            choice = observed.choice,
            decisionIndex = decisionIndex,
            privateToActor = observed.result.privateToActor,
        )
        decisionIndex++
        return observed
    }

    fun choose(): SearchTeacherDecision {
        check(canChoose) {
            "Search Teacher $teacher cannot choose for ${world.actorToAct()}"
        }
        val started = System.nanoTime()
        val selection = policy.select(
            world = world,
            actor = teacher,
            searchSeed = ComponentSeeds.derive(gameId, decisionIndex, config.baseSeed, "live-search"),
        )
        return SearchTeacherDecision(
            choice = selection.choice,
            resolved = world.resolveChoice(selection.choice),
            search = selection.search,
            belief = policy.latestBeliefDiagnostics,
            latencyMillis = (System.nanoTime() - started) / 1_000_000.0,
            decisionIndex = decisionIndex,
            selectionKind = selection.kind,
        )
    }
}
