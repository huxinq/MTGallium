package org.mtgallium.research.workbench

import java.util.ServiceLoader
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonObject
import org.mtgallium.agent.infoset.argentum.ARGENTUM_HEURISTIC_CHOICE_TAG_V1
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.infoset.planning.*
import org.mtgallium.agent.argentum.policy.*
import org.mtgallium.agent.value.MaterialEvaluator

/**
 * Named policies that play inside the JVM. Other modules on the research classpath add
 * policies by listing an implementation in
 * `META-INF/services/org.mtgallium.research.workbench.JvmPolicyProvider`.
 */
interface JvmPolicyProvider {
    /** Policy names this provider constructs; a name belongs to one provider. */
    val policies: Set<String>

    /** Plan settings this provider reads from [ResearchGameConfig.extensions]; no [ResearchGameConfig] field may be named. */
    val settings: Set<String> get() = emptySet()

    /** Construct [name] for [actor]; the game keeps a [JvmPolicy.SearchSession] session and asks again for others. */
    fun create(name: String, game: NativePolicyContext, actor: String): JvmPolicy
}

sealed interface JvmPolicy {
    /** A player without memory between decisions. */
    class Memoryless(val player: GameAgent) : JvmPolicy

    /** One session per actor, created once; the game observes accepted moves, forks it and reports its search. */
    class SearchSession(val session: SearchPolicySession) : JvmPolicy

    /** A player at an Argentum seat; the game hosts it as a browser seat (see [SeatHost]). */
    class Seat(val agent: SeatAgent) : JvmPolicy
}

/** The game a native policy is constructed for. */
class NativePolicyContext internal constructor(
    val plan: ResearchGameConfig,
    val world: ArgentumSearchWorld,
    val gameId: String,
    val knownDecks: Map<String, Map<String, Int>>,
) {
    /** The plan's opponent model for search. */
    fun opponentModel(): OpponentPolicy = when (plan.opponentModel) {
        "mixture" -> defaultMonoRedOpponentPolicy()
        "heuristic" -> DeterminizedArgentumHeuristicOpponentPolicy()
        "random" -> UniformOpponentPolicy
        else -> error("Unknown opponent model '${plan.opponentModel}'")
    }

    /** Decode a provider's settings from [ResearchGameConfig.extensions]; other providers' settings are ignored. */
    fun <T> settings(deserializer: DeserializationStrategy<T>): T =
        researchJson.decodeFromJsonElement(deserializer, JsonObject(plan.extensions))
}

/** The action Argentum's determinized heuristic marks in the menu; a missing mark stops the game. */
fun argentumAiChoice(context: DecisionContext): SemanticChoice =
    context.menu.candidates.single { ARGENTUM_HEURISTIC_CHOICE_TAG_V1 in it.display.policyTags }

internal object BuiltinNativePolicies : JvmPolicyProvider {
    override val policies = setOf("random", "heuristic", "production", "search")

    override fun create(name: String, game: NativePolicyContext, actor: String): JvmPolicy = when (name) {
        "random" -> JvmPolicy.Memoryless(selectorPlayer(UniformOpponentPolicy))
        "heuristic" -> JvmPolicy.Memoryless(selectorPlayer(DeterminizedArgentumHeuristicOpponentPolicy()))
        "production" -> JvmPolicy.Memoryless(GameAgent(MenuRequest(admission = MenuSource.PRODUCTION,
            annotations = true)) { context, _ -> argentumAiChoice(context) })
        "search" -> JvmPolicy.SearchSession(search(game, actor))
        else -> error("Unknown built-in policy '$name'")
    }

    private fun search(game: NativePolicyContext, actor: String): SearchPolicySession {
        val plan = game.plan
        return SearchPolicySession(game.world, actor, game.knownDecks,
            SearchPolicyConfig(plan.particles, plan.simulations, plan.searchDepth, plan.explorationConstant,
                plan.leaf, plan.actionProfile, baseSeed = plan.seed,
                rolloutTurnHorizon = plan.rolloutTurnHorizon),
            game.opponentModel(), game.gameId,
            rolloutPolicy = PolicyDefaults.rootRolloutPolicy(),
            rolloutOpponentPolicy = PolicyDefaults.opponentRolloutPolicy(),
            valueSource = LeafValueSource.Information(
                MaterialEvaluator()))
    }
}

/** The built-in policies and those found on the classpath. */
internal class NativePolicies(val providers: List<JvmPolicyProvider>) {
    private val owners: Map<String, JvmPolicyProvider> = buildMap {
        providers.forEach { provider ->
            provider.policies.forEach { name ->
                val previous = put(name, provider)
                check(previous == null) { "Native policy '$name' is provided by both ${previous?.javaClass?.name} and ${provider.javaClass.name}" }
            }
        }
    }

    /** Settings some provider reads; providers may share one. */
    val settings: Set<String> = providers.flatMap { it.settings }.toSet().also { names ->
        val fields = settingNames(ResearchGameConfig.serializer())
        check(names.none { it in fields }) { "Extension settings name plan fields: ${names.filter { it in fields }}" }
    }

    fun create(name: String, game: NativePolicyContext, actor: String): JvmPolicy =
        (owners[name] ?: error("Unknown native policy '$name'; Python policies supply their selected action directly"))
            .create(name, game, actor)

    fun checkSettings(plan: ResearchGameConfig) {
        val unknown = plan.extensions.keys - settings
        require(unknown.isEmpty()) { "Unknown plan settings: ${unknown.sorted()}" }
    }

    companion object {
        val installed: NativePolicies by lazy {
            NativePolicies(listOf(BuiltinNativePolicies) + ServiceLoader.load(
                JvmPolicyProvider::class.java, JvmPolicyProvider::class.java.classLoader).toList())
        }
    }
}
