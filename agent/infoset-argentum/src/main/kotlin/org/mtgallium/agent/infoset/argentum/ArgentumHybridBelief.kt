package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.sdk.core.Zone
import kotlin.math.ln
import org.mtgallium.agent.infoset.core.BeliefArchitecture
import org.mtgallium.agent.infoset.core.BeliefBatch
import org.mtgallium.agent.infoset.core.BeliefDiagnostics
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.BeliefStratumDiagnostic
import org.mtgallium.agent.infoset.core.BeliefWorldSource
import org.mtgallium.agent.infoset.core.ComponentSeeds
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyKnowledgeState
import org.mtgallium.agent.infoset.core.SearchWorld
import org.mtgallium.agent.infoset.core.Weighted

class HybridBeliefUnsupportedException(message: String) : IllegalStateException(message)

/**
 * Deck-local constraint-conditioned belief source: retain cheap card-count/zone constraints
 * exactly, then sample the complete hand and library residual needed by Argentum. This is not a
 * formal Rao-Blackwellized filter because the current implementation does not analytically
 * marginalize a tractable subset of latent variables.
 */
class ArgentumHybridBeliefWorldSource(
    private val root: ArgentumSearchWorld,
    proposalAuditSink: ArgentumBeliefProposalAuditSink = ArgentumBeliefProposalAuditSink.NONE,
    proposalContext: String = "hybrid-known-deck-construction",
) : BeliefWorldSource {
    private val delegate = ArgentumKnownDeckBeliefWorldSource(
        root,
        proposalAuditSink,
        proposalContext,
    )

    override fun sample(
        rootInformation: PolicyInformationState,
        knownDecks: Map<String, Map<String, Int>>,
        beliefSeed: Long,
        count: Int,
    ): BeliefBatch<Weighted<SearchWorld>> {
        require(count > 0)
        val knowledge = rootInformation.knowledge
        require(knowledge.deckCardCounts == knownDecks.mapValues { it.value.toSortedMap() }.toSortedMap()) {
            "Policy knowledge was not reduced from the requested known decks"
        }
        if (!knowledge.epistemicallyComplete) {
            throw HybridBeliefUnsupportedException(
                "Safe ledger is epistemically incomplete: ${knowledge.unsupportedReasons.joinToString()}"
            )
        }
        if (knowledge.knownLibraryOrders.any { it.top.isNotEmpty() || it.bottom.isNotEmpty() }) {
            throw HybridBeliefUnsupportedException(
                "Known library order requires a constraint-aware Argentum rewrite; refusing snapshot fallback"
            )
        }

        val viewer = rootInformation.observation.perspectivePlayerId
        val stratum = tacticalStratum(knowledge, viewer, count)
        val accepted = mutableListOf<Weighted<SearchWorld>>()
        var attempts = 0

        fun propose(requirePresent: Boolean?, outputWeight: Double) {
            while (true) {
                if (attempts >= MAX_PROPOSAL_ATTEMPTS) {
                    throw HybridBeliefUnsupportedException(
                        "Conditional sampler exhausted $MAX_PROPOSAL_ATTEMPTS proposals"
                    )
                }
                val proposal = delegate.sample(
                    rootInformation,
                    knownDecks,
                    ComponentSeeds.derive(beliefSeed, attempts, "hybrid-proposal"),
                    1,
                ).particles.single().value as ArgentumSearchWorld
                attempts++
                if (!satisfiesExactKnownZones(proposal, knowledge)) continue
                if (requirePresent != null && stratum != null &&
                    opponentHandContains(proposal, stratum.opponentId, stratum.cardName) != requirePresent
                ) continue
                accepted += Weighted(proposal, outputWeight)
                return
            }
        }

        val diagnostics = if (stratum == null) {
            repeat(count) { propose(null, 1.0 / count) }
            emptyList()
        } else {
            repeat(stratum.presentParticles) {
                propose(true, stratum.presentMass / stratum.presentParticles)
            }
            repeat(stratum.absentParticles) {
                propose(false, (1.0 - stratum.presentMass) / stratum.absentParticles)
            }
            listOf(
                BeliefStratumDiagnostic(
                    id = "${stratum.opponentId}:hand-contains:${stratum.cardName}",
                    exactMass = stratum.presentMass,
                    particles = stratum.presentParticles,
                ),
                BeliefStratumDiagnostic(
                    id = "${stratum.opponentId}:hand-excludes:${stratum.cardName}",
                    exactMass = 1.0 - stratum.presentMass,
                    particles = stratum.absentParticles,
                ),
            )
        }
        val weights = accepted.map { it.weight }
        ArgentumBeliefSupport.requireSupported(
            accepted.map { it.value },
            viewer,
            rootInformation,
            "Hybrid known-deck sampling",
        )
        return BeliefBatch(
            particles = accepted,
            diagnostics = BeliefDiagnostics(
                mode = BeliefMode.CONSISTENCY_ONLY_V1,
                requestedParticles = count,
                acceptedParticles = accepted.size,
                rejectedParticles = attempts - accepted.size,
                effectiveSampleSizeBefore = 1.0 / weights.sumOf { it * it },
                effectiveSampleSizeAfter = 1.0 / weights.sumOf { it * it },
                entropy = -weights.filter { it > 0.0 }.sumOf { it * ln(it) },
                resamplingCount = 0,
                architecture = BeliefArchitecture.HYBRID_C_V1,
                knowledgeDigest = knowledge.knowledgeDigest,
                strata = diagnostics,
                proposalAttempts = attempts,
            ),
        )
    }

    private fun satisfiesExactKnownZones(
        world: ArgentumSearchWorld,
        knowledge: PolicyKnowledgeState,
    ): Boolean {
        val rawPlayers = world.rawPlayerIds()
        val state = world.authoritativeState()
        return knowledge.zones.all { zone ->
            if (zone.knownCardCounts.isEmpty()) return@all true
            val owner = rawPlayers[zone.ownerId] ?: return@all false
            val ids = when (zone.zone) {
                Zone.HAND.name -> state.getHand(owner)
                Zone.LIBRARY.name -> state.getLibrary(owner)
                Zone.GRAVEYARD.name -> state.getGraveyard(owner)
                Zone.EXILE.name -> state.getExile(owner)
                Zone.BATTLEFIELD.name -> state.getBattlefield(owner)
                else -> return@all false
            }
            val counts = ids.mapNotNull { state.getEntity(it)?.get<CardComponent>()?.name }
                .groupingBy { it }.eachCount()
            zone.knownCardCounts.all { (name, required) -> (counts[name] ?: 0) >= required }
        }
    }

    private fun opponentHandContains(world: ArgentumSearchWorld, opponentAlias: String, cardName: String): Boolean {
        val opponent = world.rawPlayerIds().getValue(opponentAlias)
        return world.authoritativeState().getHand(opponent).any { id ->
            world.authoritativeState().getEntity(id)?.get<CardComponent>()?.name == cardName
        }
    }

    private fun tacticalStratum(
        knowledge: PolicyKnowledgeState,
        viewer: String,
        particles: Int,
    ): TacticalStratum? {
        if (particles < 2) return null
        val opponent = knowledge.deckCardCounts.keys.singleOrNull { it != viewer } ?: return null
        val hand = knowledge.zones.singleOrNull { it.ownerId == opponent && it.zone == Zone.HAND.name }
            ?: return null
        val knownInHand = hand.knownCardCounts.values.sum()
        val hiddenHandSize = (hand.size - knownInHand).coerceAtLeast(0)
        val unlocated = knowledge.unlocatedCardCounts[opponent].orEmpty()
        val unknownPool = unlocated.values.sum()
        if (hiddenHandSize <= 0 || unknownPool <= 0) return null

        val candidate = TACTICAL_CARDS.mapNotNull { cardName ->
            // The stratum describes residual uncertainty, not a card already certain in hand.
            if ((hand.knownCardCounts[cardName] ?: 0) > 0) return@mapNotNull null
            val copies = unlocated[cardName] ?: return@mapNotNull null
            if (copies <= 0) return@mapNotNull null
            val mass = probabilityAtLeastOne(copies, unknownPool, hiddenHandSize)
            if (mass <= 0.0 || mass >= 1.0) null else cardName to mass
        }.minByOrNull { (_, mass) -> mass } ?: return null
        val presentParticles = (candidate.second * particles).toInt().coerceIn(1, particles - 1)
        return TacticalStratum(
            opponentId = opponent,
            cardName = candidate.first,
            presentMass = candidate.second,
            presentParticles = presentParticles,
            absentParticles = particles - presentParticles,
        )
    }

    private fun probabilityAtLeastOne(copies: Int, pool: Int, draws: Int): Double {
        if (copies <= 0 || draws <= 0) return 0.0
        if (draws > pool - copies) return 1.0
        var none = 1.0
        repeat(draws) { index ->
            none *= (pool - copies - index).toDouble() / (pool - index).toDouble()
        }
        return 1.0 - none
    }

    private data class TacticalStratum(
        val opponentId: String,
        val cardName: String,
        val presentMass: Double,
        val presentParticles: Int,
        val absentParticles: Int,
    )

    private companion object {
        const val MAX_PROPOSAL_ATTEMPTS = 512
        val TACTICAL_CARDS = listOf("Shock", "Lightning Strike", "Burst Lightning")
    }
}
