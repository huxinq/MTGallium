package org.mtgallium.research.workbench

import java.util.ServiceLoader
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonObject
import org.mtgallium.agent.infoset.argentum.ARGENTUM_HEURISTIC_CHOICE_TAG_V1
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.argentum.policy.*
import org.mtgallium.agent.monored.LinearValueEvaluator
import org.mtgallium.agent.monored.LinearValueLink
import org.mtgallium.agent.monored.MonoRedInformationEvaluator

/**
 * Named policies that play inside the JVM. Other modules on the research classpath add
 * policies by listing an implementation in
 * `META-INF/services/org.mtgallium.research.workbench.NativePolicyProvider`.
 */
interface NativePolicyProvider {
    /** Policy names this provider constructs; a name belongs to one provider. */
    val policies: Set<String>

    /** Plan settings this provider reads from [GamesPlan.extensions]; no [GamesPlan] field may be named. */
    val settings: Set<String> get() = emptySet()

    /** Construct [name] for [actor]; the game keeps a [NativePolicy.Search] session and asks again for others. */
    fun create(name: String, game: NativePolicyContext, actor: String): NativePolicy
}

sealed interface NativePolicy {
    /** A player without memory between decisions. */
    class Direct(val player: Player) : NativePolicy

    /** One session per actor, created once; the game observes accepted moves, forks it and reports its search. */
    class Search(val session: SearchPolicySession) : NativePolicy
}

/** The game a native policy is constructed for. */
class NativePolicyContext internal constructor(
    val plan: GamesPlan,
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

    /** Decode a provider's settings from [GamesPlan.extensions]; other providers' settings are ignored. */
    fun <T> settings(deserializer: DeserializationStrategy<T>): T =
        researchJson.decodeFromJsonElement(deserializer, JsonObject(plan.extensions))
}

/** The action Argentum's determinized heuristic marks in the menu; a missing mark stops the game. */
fun productionChoice(context: DecisionSiteRequest): SemanticChoice =
    context.expansion.candidates.single { ARGENTUM_HEURISTIC_CHOICE_TAG_V1 in it.display.policyTags }

internal object BuiltinNativePolicies : NativePolicyProvider {
    override val policies = setOf("random", "heuristic", "production", "search")

    override fun create(name: String, game: NativePolicyContext, actor: String): NativePolicy = when (name) {
        "random" -> NativePolicy.Direct(selectorPlayer(UniformOpponentPolicy))
        "heuristic" -> NativePolicy.Direct(selectorPlayer(DeterminizedArgentumHeuristicOpponentPolicy()))
        "production" -> NativePolicy.Direct(Player(DecisionView(admission = DecisionAdmission.PRODUCTION,
            annotations = true)) { context, _ -> productionChoice(context) })
        "search" -> NativePolicy.Search(search(game, actor))
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
                plan.valueWeights?.let { LinearValueEvaluator(it, plan.valueLink ?: LinearValueLink.CLIP) }
                    ?: MonoRedInformationEvaluator))
    }
}

/** The built-in policies and those found on the classpath. */
internal class NativePolicies(val providers: List<NativePolicyProvider>) {
    private val owners: Map<String, NativePolicyProvider> = buildMap {
        providers.forEach { provider ->
            provider.policies.forEach { name ->
                val previous = put(name, provider)
                check(previous == null) { "Native policy '$name' is provided by both ${previous?.javaClass?.name} and ${provider.javaClass.name}" }
            }
        }
    }

    /** Settings some provider reads; providers may share one. */
    val settings: Set<String> = providers.flatMap { it.settings }.toSet().also { names ->
        val fields = settingNames(GamesPlan.serializer())
        check(names.none { it in fields }) { "Extension settings name plan fields: ${names.filter { it in fields }}" }
    }

    fun create(name: String, game: NativePolicyContext, actor: String): NativePolicy =
        (owners[name] ?: error("Unknown native policy '$name'; Python policies supply their selected action directly"))
            .create(name, game, actor)

    fun checkSettings(plan: GamesPlan) {
        val unknown = plan.extensions.keys - settings
        require(unknown.isEmpty()) { "Unknown plan settings: ${unknown.sorted()}" }
    }

    companion object {
        val installed: NativePolicies by lazy {
            NativePolicies(listOf(BuiltinNativePolicies) + ServiceLoader.load(
                NativePolicyProvider::class.java, NativePolicyProvider::class.java.classLoader).toList())
        }
    }
}
