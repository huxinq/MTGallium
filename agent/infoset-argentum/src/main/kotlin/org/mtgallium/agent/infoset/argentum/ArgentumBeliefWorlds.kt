package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.RevealedToComponent
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.GameRng
import kotlin.math.ln
import org.mtgallium.agent.infoset.core.BeliefBatch
import org.mtgallium.agent.infoset.core.BeliefDiagnostics
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.BeliefWorldSource
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.ParticleBelief
import org.mtgallium.agent.infoset.core.ParticleRejuvenator
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.SearchWorld
import org.mtgallium.agent.infoset.core.Weighted

private val SAFE_BELIEF_SUPPORT_CODE = Regex("[A-Z][A-Z0-9_]*")

private fun redactedBeliefFailureCounts(failures: Map<String, Int>): Map<String, Int> = buildMap {
    failures.forEach { (rawCode, count) ->
        val supportCode = rawCode.removePrefix("KnowledgeSupport:")
            .takeIf { rawCode.startsWith("KnowledgeSupport:") && it.matches(SAFE_BELIEF_SUPPORT_CODE) }
            ?: "ENGINE_SAMPLER_REJECTED"
        put(supportCode, getOrDefault(supportCode, 0) + count)
    }
    if (isEmpty()) put("UNKNOWN_SUPPORT_REFUSAL", 1)
}.toSortedMap()

/**
 * Temporarily teaches the engine sampler about facts already present in the viewer-safe ledger.
 * The sampler's native visibility model otherwise treats a remembered hidden object as unknown
 * after its transient engine reveal marker disappears and may rewrite its identity. The markers
 * are restored byte-for-byte before a sampled world is exposed to any policy-facing code.
 */
internal data class RememberedObjectPins(
    val samplingState: GameState,
    private val originalReveals: Map<EntityId, RevealedToComponent?>,
) {
    fun restore(sampledState: GameState): GameState = originalReveals.entries.fold(sampledState) {
        state, (objectId, original) ->
        state.updateEntity(objectId) { entity ->
            if (original == null) entity.without<RevealedToComponent>() else entity.with(original)
        }
    }
}

internal fun pinRememberedObjects(
    state: GameState,
    viewer: EntityId,
    rememberedObjectIds: Set<EntityId>,
): RememberedObjectPins {
    val originals = rememberedObjectIds.mapNotNull { objectId ->
        state.getEntity(objectId)?.let { objectId to it.get<RevealedToComponent>() }
    }.toMap()
    val samplingState = originals.entries.fold(state) { current, (objectId, original) ->
        current.updateEntity(objectId) { entity ->
            entity.with((original ?: RevealedToComponent(emptySet())).withPlayer(viewer))
        }
    }
    return RememberedObjectPins(samplingState, originals)
}

/** Typed, redacted refusal when no requested known-deck particle can satisfy represented facts. */
class ArgentumBeliefSupportException(
    val viewerAlias: String,
    val requestedParticles: Int,
    val acceptedParticles: Int,
    val attempts: Int,
    failureCounts: Map<String, Int>,
) : IllegalStateException(
    "Known-deck sampling constructed $acceptedParticles/$requestedParticles " +
        "remembered-fact-consistent particles after $attempts attempts: " +
        redactedBeliefFailureCounts(failureCounts),
) {
    val reasonCounts: Map<String, Int> = redactedBeliefFailureCounts(failureCounts)
    val reasonCodes: List<String> = reasonCounts.keys.toList()

    init {
        require(viewerAlias.isNotBlank())
        require(requestedParticles > 0)
        require(acceptedParticles in 0 until requestedParticles)
        require(attempts > 0)
        require(reasonCodes.isNotEmpty())
        require(reasonCounts.values.all { it > 0 })
    }
}

/** Redacted verification shared by every adapter and production belief-construction path. */
object ArgentumBeliefSupport {
    fun failures(
        worlds: Iterable<SearchWorld>,
        viewerAlias: String,
        expected: PolicyInformationState,
    ): Map<String, Int> = buildMap {
        worlds.forEach { world ->
            val candidate = world as? ArgentumSearchWorld
            val code = if (candidate == null) {
                "WORLD_TYPE"
            } else {
                candidate.knowledgeSupportFailure(viewerAlias, expected)
            }
            if (code != null) put(code, getOrDefault(code, 0) + 1)
        }
    }

    fun completeFailures(
        worlds: Iterable<SearchWorld>,
        viewerAlias: String,
        expected: PolicyInformationState,
    ): Map<String, Int> {
        val candidates = worlds.toList()
        return buildMap {
            putAll(failures(candidates, viewerAlias, expected))
            val digestMismatches = candidates.count { world ->
                world.informationState(viewerAlias).informationStateDigest !=
                    expected.informationStateDigest
            }
            if (digestMismatches > 0) put("SAFE_INFORMATION_STATE_MISMATCH", digestMismatches)
        }
    }

    fun incompatibleWorldCount(
        worlds: Iterable<SearchWorld>,
        viewerAlias: String,
        expected: PolicyInformationState,
    ): Int = worlds.count { world ->
        val candidate = world as? ArgentumSearchWorld
        candidate == null || candidate.knowledgeSupportFailure(viewerAlias, expected) != null ||
            world.informationState(viewerAlias).informationStateDigest != expected.informationStateDigest
    }

    fun requireSupported(
        worlds: Iterable<SearchWorld>,
        viewerAlias: String,
        expected: PolicyInformationState,
        purpose: String,
    ) {
        val failures = completeFailures(worlds, viewerAlias, expected)
        check(failures.isEmpty()) {
            "$purpose produced hidden worlds that contradict the represented player input: $failures"
        }
    }
}

/**
 * Samples from a supplied decklist and refuses any complete world that contradicts the viewer's
 * current observation or remembered zone/library facts.
 */
class ArgentumKnownDeckBeliefWorldSource(
    private val root: ArgentumSearchWorld,
    cardRegistry: CardRegistry,
    private val proposalAuditSink: ArgentumBeliefProposalAuditSink = ArgentumBeliefProposalAuditSink.NONE,
    private val proposalContext: String = "known-deck-construction",
) : BeliefWorldSource {
    private val materializer = KnownDeckWorldMaterializer(cardRegistry)

    override fun sample(
        rootInformation: PolicyInformationState,
        knownDecks: Map<String, Map<String, Int>>,
        beliefSeed: Long,
        count: Int,
    ): BeliefBatch<Weighted<SearchWorld>> {
        require(count > 0) { "Particle count must be positive" }
        val viewerAlias = rootInformation.observation.perspectivePlayerId
        val expected = root.informationState(viewerAlias)
        require(expected.informationStateDigest == rootInformation.informationStateDigest) {
            val differences = buildList {
                if (expected.observation.observationDigest != rootInformation.observation.observationDigest) add("observation")
                if (expected.historyDigest != rootInformation.historyDigest) add("history")
                if (expected.knowledge.knowledgeDigest != rootInformation.knowledge.knowledgeDigest) add("knowledge")
                if (expected.actingPlayerId != rootInformation.actingPlayerId) add("actor")
                if (expected.candidates.map { it.signature } != rootInformation.candidates.map { it.signature }) add("candidates")
            }
            "Root information does not match the trusted world (${differences.joinToString()})"
        }
        val players = root.rawPlayerIds()
        require(knownDecks.keys == players.keys) { "Known decks must exactly cover ${players.keys}" }
        val rawDecks = knownDecks.mapKeys { (alias, _) -> players.getValue(alias) }
        val viewer = players.getValue(viewerAlias)
        val pins = pinRememberedObjects(
            root.authoritativeState(),
            viewer,
            root.rememberedKnowledgeObjectIds(viewerAlias, expected),
        )
        val accepted = mutableListOf<ArgentumSearchWorld>()
        val failures = mutableMapOf<String, Int>()
        var attempts = 0
        val maximumAttempts = count * ARGENTUM_KNOWN_DECK_MAX_PROPOSAL_ATTEMPTS_PER_PARTICLE
        while (accepted.size < count && attempts < maximumAttempts) {
            val seed = ComponentSeeds.derive(beliefSeed, attempts, "belief-world")
            when (val result = materializer.materialize(
                state = pins.samplingState,
                viewerId = viewer,
                decklists = rawDecks,
                beliefRng = GameRng.seeded(seed),
                futureRng = GameRng.seeded(ComponentSeeds.derive(seed, "known-deck-future")),
            )) {
                is KnownDeckWorldMaterializationResult.Materialized -> {
                    // The proposal seed continues to define hidden assignment exactly as before.
                    // withSampledState domain-separates a future game-chance stream from the same
                    // stable particle identity instead of retaining result.state.rng from the referee.
                    val candidate = root.withSampledState(
                        pins.restore(result.state),
                        futureChanceStreamIdentity = seed,
                    )
                    val candidateFailures = ArgentumBeliefSupport.completeFailures(
                        listOf(candidate),
                        viewerAlias,
                        rootInformation,
                    )
                    if (candidateFailures.isEmpty()) {
                        accepted += candidate
                        proposalAuditSink.record(
                            ArgentumBeliefProposalAudit(
                                source = ArgentumBeliefProposalSource.KNOWN_DECK_CONSTRUCTION,
                                context = proposalContext,
                                attemptIndex = attempts,
                                proposalSeed = seed,
                                disposition = ArgentumBeliefProposalDisposition.ACCEPTED,
                            )
                        )
                    } else {
                        val redactedReasons = candidateFailures.keys.sorted().map { "KnowledgeSupport:$it" }
                        candidateFailures.forEach { (failure, count) ->
                            val code = "KnowledgeSupport:$failure"
                            failures[code] = failures.getOrDefault(code, 0) + count
                        }
                        proposalAuditSink.record(
                            ArgentumBeliefProposalAudit(
                                source = ArgentumBeliefProposalSource.KNOWN_DECK_CONSTRUCTION,
                                context = proposalContext,
                                attemptIndex = attempts,
                                proposalSeed = seed,
                                disposition =
                                    ArgentumBeliefProposalDisposition.REJECTED_BY_REPRESENTED_FACT_SUPPORT,
                                redactedReasons = redactedReasons,
                            )
                        )
                    }
                }
                is KnownDeckWorldMaterializationResult.Unsupported -> {
                    val redactedReasons = result.reasons.map(KnownDeckWorldFailure::redactedCode).sorted()
                    redactedReasons.forEach { name ->
                        failures[name] = failures.getOrDefault(name, 0) + 1
                    }
                    proposalAuditSink.record(
                        ArgentumBeliefProposalAudit(
                            source = ArgentumBeliefProposalSource.KNOWN_DECK_CONSTRUCTION,
                            context = proposalContext,
                            attemptIndex = attempts,
                            proposalSeed = seed,
                            disposition = ArgentumBeliefProposalDisposition.REJECTED_BY_ENGINE_SAMPLER,
                            redactedReasons = redactedReasons,
                        )
                    )
                }
            }
            attempts++
        }
        if (accepted.size != count) {
            throw ArgentumBeliefSupportException(
                viewerAlias = viewerAlias,
                requestedParticles = count,
                acceptedParticles = accepted.size,
                attempts = attempts,
                failureCounts = failures,
            )
        }
        ArgentumBeliefSupport.requireSupported(
            accepted,
            viewerAlias,
            rootInformation,
            "Known-deck sampling",
        )
        val weight = 1.0 / accepted.size
        return BeliefBatch(
            particles = accepted.map { Weighted<SearchWorld>(it, weight) },
            diagnostics = BeliefDiagnostics(
                mode = BeliefMode.CONSISTENCY_ONLY_V1,
                requestedParticles = count,
                acceptedParticles = accepted.size,
                rejectedParticles = attempts - accepted.size,
                effectiveSampleSizeBefore = count.toDouble(),
                effectiveSampleSizeAfter = count.toDouble(),
                entropy = ln(count.toDouble()),
                resamplingCount = 0,
                marginalCardProbabilities = handMarginals(accepted, viewer),
                failures = failures,
                knowledgeDigest = rootInformation.knowledge.knowledgeDigest,
                proposalAttempts = attempts,
            ),
        )
    }

    private fun handMarginals(worlds: List<ArgentumSearchWorld>, viewer: EntityId): Map<String, Double> {
        val opponents = root.authoritativeState().turnOrder.filter { it != viewer }
        val counts = mutableMapOf<String, Int>()
        worlds.forEach { world ->
            opponents.forEach { opponent ->
                world.authoritativeState().getHand(opponent).mapNotNull { id ->
                    world.authoritativeState().getEntity(id)?.get<CardComponent>()?.name
                }.toSet().forEach { name -> counts[name] = counts.getOrDefault(name, 0) + 1 }
            }
        }
        return counts.toSortedMap().mapValues { (_, count) -> count.toDouble() / worlds.size }
    }
}

/** Re-samples only hidden identities after duplicate-particle resampling. */
class ArgentumConditionalRejuvenator(
    cardRegistry: CardRegistry,
    private val knownDecks: Map<String, Map<String, Int>>,
    private val viewerAlias: String,
    private val proposalAuditSink: ArgentumBeliefProposalAuditSink = ArgentumBeliefProposalAuditSink.NONE,
    private val proposalContext: String = "conditional-rejuvenation",
) : ParticleRejuvenator {
    private val materializer = KnownDeckWorldMaterializer(cardRegistry)

    override fun rejuvenate(world: SearchWorld, duplicateIndex: Int, seed: Long): SearchWorld {
        require(duplicateIndex > 0) { "Only duplicate particles require rejuvenation" }
        val trusted = world as? ArgentumSearchWorld
            ?: error("Argentum rejuvenation received an untrusted world implementation")
        val players = trusted.rawPlayerIds()
        require(knownDecks.keys == players.keys) { "Known decks must exactly cover ${players.keys}" }
        val expected = trusted.informationState(viewerAlias)
        val rawDecks = knownDecks.mapKeys { (alias, _) -> players.getValue(alias) }
        val viewer = players.getValue(viewerAlias)
        val pins = pinRememberedObjects(
            trusted.authoritativeState(),
            viewer,
            trusted.rememberedKnowledgeObjectIds(viewerAlias, expected),
        )
        val failures = mutableMapOf<String, Int>()
        repeat(ARGENTUM_CONDITIONAL_REJUVENATION_MAX_PROPOSAL_ATTEMPTS) { attempt ->
            val attemptSeed = if (attempt == 0) seed else {
                ComponentSeeds.derive(seed, attempt, "remembered-fact-rejuvenation")
            }
            when (val result = materializer.materialize(
                state = pins.samplingState,
                viewerId = viewer,
                decklists = rawDecks,
                beliefRng = GameRng.seeded(attemptSeed),
                futureRng = GameRng.seeded(ComponentSeeds.derive(attemptSeed, "known-deck-future")),
            )) {
                is KnownDeckWorldMaterializationResult.Materialized -> {
                    val candidate = trusted.withSampledState(
                        pins.restore(result.state),
                        futureChanceStreamIdentity = attemptSeed,
                    )
                    val candidateFailures = ArgentumBeliefSupport.completeFailures(
                        listOf(candidate),
                        viewerAlias,
                        expected,
                    )
                    if (candidateFailures.isEmpty()) {
                        proposalAuditSink.record(
                            ArgentumBeliefProposalAudit(
                                source = ArgentumBeliefProposalSource.CONDITIONAL_REJUVENATION,
                                context = proposalContext,
                                attemptIndex = attempt,
                                proposalSeed = attemptSeed,
                                disposition = ArgentumBeliefProposalDisposition.ACCEPTED,
                            )
                        )
                        return candidate
                    }
                    candidateFailures.forEach { (failure, count) ->
                        val code = "KnowledgeSupport:$failure"
                        failures[code] = failures.getOrDefault(code, 0) + count
                    }
                    proposalAuditSink.record(
                        ArgentumBeliefProposalAudit(
                            source = ArgentumBeliefProposalSource.CONDITIONAL_REJUVENATION,
                            context = proposalContext,
                            attemptIndex = attempt,
                            proposalSeed = attemptSeed,
                            disposition =
                                ArgentumBeliefProposalDisposition.REJECTED_BY_REPRESENTED_FACT_SUPPORT,
                            redactedReasons = candidateFailures.keys.sorted().map { "KnowledgeSupport:$it" },
                        )
                    )
                }
                is KnownDeckWorldMaterializationResult.Unsupported -> {
                    val redactedReasons = result.reasons.map(KnownDeckWorldFailure::redactedCode).sorted()
                    redactedReasons.forEach { code ->
                        failures[code] = failures.getOrDefault(code, 0) + 1
                    }
                    proposalAuditSink.record(
                        ArgentumBeliefProposalAudit(
                            source = ArgentumBeliefProposalSource.CONDITIONAL_REJUVENATION,
                            context = proposalContext,
                            attemptIndex = attempt,
                            proposalSeed = attemptSeed,
                            disposition = ArgentumBeliefProposalDisposition.REJECTED_BY_ENGINE_SAMPLER,
                            redactedReasons = redactedReasons,
                        )
                    )
                }
            }
        }
        throw ArgentumBeliefSupportException(
            viewerAlias = viewerAlias,
            requestedParticles = 1,
            acceptedParticles = 0,
            attempts = ARGENTUM_CONDITIONAL_REJUVENATION_MAX_PROPOSAL_ATTEMPTS,
            failureCounts = failures,
        )
    }
}

/** Trusted aggregation of sampled hidden hands; only aggregate probabilities cross the boundary. */
object ArgentumParticleDiagnostics {
    fun opponentHandMarginals(belief: ParticleBelief, viewerAlias: String): Map<String, Double> {
        val probabilities = mutableMapOf<String, Double>()
        belief.weightedWorlds().forEach { weighted ->
            val world = weighted.value as? ArgentumSearchWorld
                ?: error("Argentum belief diagnostics received an untrusted world")
            val viewer = world.rawPlayerIds().getValue(viewerAlias)
            world.authoritativeState().turnOrder
                .filter { it != viewer }
                .flatMap { opponent -> world.authoritativeState().getHand(opponent) }
                .mapNotNull { id -> world.authoritativeState().getEntity(id)?.get<CardComponent>()?.name }
                .toSet()
                .forEach { name ->
                    probabilities[name] = probabilities.getOrDefault(name, 0.0) + weighted.weight
                }
        }
        return probabilities.toSortedMap()
    }
}
