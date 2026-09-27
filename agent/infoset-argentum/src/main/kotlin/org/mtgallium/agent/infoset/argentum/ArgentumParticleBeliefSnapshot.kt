package org.mtgallium.agent.infoset.argentum

import kotlinx.serialization.encodeToString
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.infoset.planning.*

/** Finite-particle belief snapshot. */
class ArgentumParticleBeliefSnapshot private constructor(
    private val frozen: ParticleBelief,
    information: InformationStateRepresentation,
    private val diagnosticBytes: String,
    inferenceModelIdentity: String,
) : BeliefSnapshot(information.observation.viewerId,
    InformationState.capture(information).epistemicDigest, inferenceModelIdentity) {
    private val viewer = information.observation.viewerId
    private val estimates: OpponentHandBeliefQueries = ArgentumHandBeliefQueries.snapshot(frozen, viewer)

    init {
        ArgentumBeliefSupport.requireSupported(frozen.weightedWorlds().map { it.value }, viewer,
            information, "Search belief read")
    }

    override fun handQueries(): OpponentHandBeliefQueries = estimates
    override fun generateHypotheses(): ParticleSet<Weighted<SearchWorld>> = ParticleSet(
        // Retain represented worlds' weights, order, and chance streams without drawing cards.
        frozen.weightedWorlds().map { Weighted(it.value.fork(), it.weight) },
        CanonicalJson.format.decodeFromString<BeliefDiagnostics>(diagnosticBytes),
    )

    companion object {
        fun capture(belief: ParticleBelief, information: InformationStateRepresentation,
            diagnostics: BeliefDiagnostics, inferenceModelIdentity: String): ArgentumParticleBeliefSnapshot =
            ArgentumParticleBeliefSnapshot(belief.fork(), information,
                CanonicalJson.format.encodeToString(diagnostics), inferenceModelIdentity)
    }
}
