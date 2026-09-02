package org.mtgallium.agent.infoset.core

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

class ParticleDepletionException(message: String) : IllegalStateException(message)

fun interface ParticleRejuvenator {
    fun rejuvenate(world: SearchWorld, duplicateIndex: Int, seed: Long): SearchWorld

    companion object {
        val FORK_ONLY = ParticleRejuvenator { world, _, _ -> world.fork() }
    }
}

data class ParticleBeliefUpdate(
    val belief: ParticleBelief,
    val diagnostics: BeliefDiagnostics,
)

/** Sequential weighted particle belief with log-space policy conditioning. */
class ParticleBelief private constructor(
    private val entries: List<LogWeightedWorld>,
    val mode: BeliefMode,
    val resamplingCount: Int,
    private val architecture: BeliefArchitecture,
    private val knowledgeDigest: String?,
    private val strata: List<BeliefStratumDiagnostic>,
    private val proposalAttempts: Int,
) {
    val size: Int get() = entries.size

    fun weightedWorlds(): List<Weighted<SearchWorld>> = normalizedWeights(entries).mapIndexed { index, weight ->
        Weighted(entries[index].world, weight)
    }

    /**
     * Bayesian conditioning on a newly observed information state without replaying an action.
     * This is used after forced/private-safe observations (for example, the viewer's own draw)
     * have already occurred inside every particle. Compatible worlds retain their exact private
     * state; depletion remains explicit so callers can fall back to a fresh proposal batch.
     */
    fun conditionOnInformationState(
        viewer: String,
        expectedInformationStateDigest: String,
        updateSeed: Long,
        rejuvenator: ParticleRejuvenator = ParticleRejuvenator.FORK_ONLY,
        updatedKnowledgeDigest: String? = knowledgeDigest,
    ): ParticleBeliefUpdate {
        val compatible = entries.filter { entry ->
            entry.world.informationState(viewer).informationStateDigest == expectedInformationStateDigest
        }
        val rejected = entries.size - compatible.size
        if (compatible.isEmpty()) {
            throw ParticleDepletionException(
                "Observed information state is incompatible with every particle"
            )
        }
        val normalized = normalizeLogs(compatible)
        val essBefore = effectiveSampleSize(normalized)
        val needsResampling = compatible.size != entries.size || essBefore < entries.size / 2.0
        val nextEntries = if (needsResampling) {
            systematicResample(normalized, entries.size, updateSeed, rejuvenator)
        } else {
            normalized
        }
        check(nextEntries.all { entry ->
            entry.world.informationState(viewer).informationStateDigest == expectedInformationStateDigest
        }) {
            "Particle rejuvenation changed the information state it was conditioned to preserve"
        }
        val weights = normalizedWeights(nextEntries)
        val next = ParticleBelief(
            entries = nextEntries,
            mode = mode,
            resamplingCount = resamplingCount + if (needsResampling) 1 else 0,
            architecture = architecture,
            knowledgeDigest = updatedKnowledgeDigest,
            strata = strata,
            proposalAttempts = proposalAttempts,
        )
        return ParticleBeliefUpdate(
            belief = next,
            diagnostics = BeliefDiagnostics(
                mode = mode,
                requestedParticles = entries.size,
                acceptedParticles = nextEntries.size,
                rejectedParticles = rejected,
                effectiveSampleSizeBefore = essBefore,
                effectiveSampleSizeAfter = effectiveSampleSizeOfWeights(weights),
                entropy = entropyOfWeights(weights),
                resamplingCount = next.resamplingCount,
                failures = if (rejected == 0) emptyMap() else {
                    mapOf("informationMismatchParticles" to rejected)
                },
                architecture = architecture,
                knowledgeDigest = updatedKnowledgeDigest,
                strata = strata,
                proposalAttempts = proposalAttempts,
            ),
        )
    }

    fun observeAndStep(
        actor: String,
        observedSignature: String,
        opponentPolicy: OpponentPolicy,
        updateSeed: Long,
        rejuvenator: ParticleRejuvenator = ParticleRejuvenator.FORK_ONLY,
    ): ParticleBeliefUpdate = advance(
        actor = actor,
        observedSignature = observedSignature,
        conditioningPolicy = opponentPolicy,
        updateSeed = updateSeed,
        rejuvenator = rejuvenator,
    )

    /**
     * Advance through a private opponent choice whose existence is public but whose response is not.
     * Each particle samples from the opponent's own safe information instead of conditioning on the
     * authoritative response, which would leak the hidden choice into the root player's belief.
     */
    fun advanceUnobserved(
        actor: String,
        opponentPolicy: OpponentPolicy,
        updateSeed: Long,
        rejuvenator: ParticleRejuvenator = ParticleRejuvenator.FORK_ONLY,
    ): ParticleBeliefUpdate {
        val advanced = mutableListOf<LogWeightedWorld>()
        val opponentDecisionCounter = OpponentPolicyDecisionCounter()
        var rejected = 0
        entries.forEachIndexed { index, entry ->
            // A declared opponent policy may require trusted adapter annotations (for example the
            // determinized production-heuristic tag). Private choices are still sampled from the
            // actor's own safe information, but they must use the same annotated candidate
            // contract as ordinary opponent decisions rather than silently invoking a replacement.
            val candidates = (entry.world as? PolicyAnnotatedSearchWorld)
                ?.expandChoicesWithPolicyAnnotations()
                ?.candidates
                ?: entry.world.expandChoices().candidates
            if (candidates.isEmpty()) {
                rejected++
                return@forEachIndexed
            }
            val information = entry.world.informationState(actor)
            val decision = opponentPolicy.select(
                opponentInformation = information,
                candidates = candidates,
                policySeed = ComponentSeeds.derive(updateSeed, index, opponentPolicy.id, "private-choice"),
                sampleSeed = ComponentSeeds.derive(updateSeed, index, "private-choice-sample"),
            )
            val selected = decision.choice
            opponentDecisionCounter.record(decision.diagnostic)
            val child = entry.world.fork()
            if (!child.step(selected).accepted) {
                rejected++
                return@forEachIndexed
            }
            advanced += LogWeightedWorld(child, entry.logWeight)
        }
        if (advanced.isEmpty()) throw ParticleDepletionException("Private choice could not advance any particle")
        val normalized = normalizeLogs(advanced)
        val essBefore = effectiveSampleSize(normalized)
        val needsResampling = normalized.size != entries.size || essBefore < entries.size / 2.0
        val nextEntries = if (needsResampling) {
            systematicResample(normalized, entries.size, updateSeed, rejuvenator)
        } else {
            normalized
        }
        val weights = normalizedWeights(nextEntries)
        val next = ParticleBelief(
            entries = nextEntries,
            mode = mode,
            resamplingCount = resamplingCount + if (needsResampling) 1 else 0,
            architecture = architecture,
            knowledgeDigest = knowledgeDigest,
            strata = strata,
            proposalAttempts = proposalAttempts,
        )
        return ParticleBeliefUpdate(
            belief = next,
            diagnostics = BeliefDiagnostics(
                mode = mode,
                requestedParticles = entries.size,
                acceptedParticles = nextEntries.size,
                rejectedParticles = rejected,
                effectiveSampleSizeBefore = essBefore,
                effectiveSampleSizeAfter = effectiveSampleSizeOfWeights(weights),
                entropy = entropyOfWeights(weights),
                resamplingCount = next.resamplingCount,
                failures = if (rejected == 0) emptyMap() else mapOf("privateChoiceRejectedParticles" to rejected),
                architecture = architecture,
                knowledgeDigest = knowledgeDigest,
                strata = strata,
                proposalAttempts = proposalAttempts,
                opponentPolicyDecisions = opponentDecisionCounter.summary(),
            ),
        )
    }

    /** Advance compatible particles, optionally weighting an observed opponent action. */
    fun advance(
        actor: String,
        observedSignature: String,
        conditioningPolicy: OpponentPolicy? = null,
        updateSeed: Long,
        rejuvenator: ParticleRejuvenator = ParticleRejuvenator.FORK_ONLY,
    ): ParticleBeliefUpdate {
        require(conditioningPolicy == null || mode == BeliefMode.POLICY_CONDITIONED_V1) {
            "Opponent-action conditioning is only defined for POLICY_CONDITIONED_V1"
        }
        val advanced = mutableListOf<LogWeightedWorld>()
        var rejected = 0
        var invalidWeights = 0
        val probabilities = mutableListOf<Double>()
        for ((index, entry) in entries.withIndex()) {
            val candidates = expansionContaining(entry.world, observedSignature).candidates
            val observed = candidates.singleOrNull { it.signature == observedSignature }
            if (observed == null) {
                rejected++
                continue
            }
            val probability = if (conditioningPolicy == null) {
                1.0
            } else {
                val info = entry.world.informationState(actor)
                val distribution = conditioningPolicy.distribution(
                    info,
                    candidates,
                    ComponentSeeds.derive(updateSeed, index, conditioningPolicy.id, "likelihood"),
                )
                val rawProbability = distribution.probabilityOf { it.signature == observedSignature }
                val floor = 0.01 / candidates.size.coerceAtLeast(1)
                max(rawProbability, floor)
            }
            if (!probability.isFinite() || probability <= 0.0) {
                invalidWeights++
                continue
            }
            val child = entry.world.fork()
            if (!child.step(observed).accepted) {
                rejected++
                continue
            }
            probabilities += probability
            advanced += LogWeightedWorld(child, entry.logWeight + ln(probability))
        }
        if (advanced.isEmpty()) throw ParticleDepletionException(
            "Observed action $observedSignature is incompatible with every particle"
        )
        val normalized = normalizeLogs(advanced)
        val essBefore = effectiveSampleSize(normalized)
        val entropyBefore = entropy(normalized)
        val needsResampling = normalized.size != entries.size || essBefore < entries.size / 2.0
        val nextEntries = if (needsResampling) {
            systematicResample(normalized, entries.size, updateSeed, rejuvenator)
        } else {
            normalized
        }
        val normalizedAfter = normalizedWeights(nextEntries)
        val sensitivity = if (probabilities.isEmpty()) null else {
            probabilities.max() - probabilities.min()
        }
        val next = ParticleBelief(
            entries = nextEntries,
            mode = mode,
            resamplingCount = resamplingCount + if (needsResampling) 1 else 0,
            architecture = architecture,
            knowledgeDigest = knowledgeDigest,
            strata = strata,
            proposalAttempts = proposalAttempts,
        )
        return ParticleBeliefUpdate(
            belief = next,
            diagnostics = BeliefDiagnostics(
                mode = mode,
                requestedParticles = entries.size,
                acceptedParticles = nextEntries.size,
                rejectedParticles = rejected,
                effectiveSampleSizeBefore = essBefore,
                effectiveSampleSizeAfter = effectiveSampleSizeOfWeights(normalizedAfter),
                entropy = if (needsResampling) entropyOfWeights(normalizedAfter) else entropyBefore,
                resamplingCount = next.resamplingCount,
                modelSensitivity = sensitivity,
                failures = buildMap {
                    if (invalidWeights > 0) put("invalidWeights", invalidWeights)
                    if (rejected > 0) put("incompatibleParticles", rejected)
                },
                architecture = architecture,
                knowledgeDigest = knowledgeDigest,
                strata = strata,
                proposalAttempts = proposalAttempts,
            ),
        )
    }

    private fun expansionContaining(world: SearchWorld, signature: String): PolicyExpansion {
        var expansion = world.expandChoices()
        if (expansion.candidates.any { it.signature == signature } || expansion.isExhaustive) return expansion
        val progressive = world as? ProgressiveSearchWorld ?: return expansion
        for (limit in OBSERVED_ACTION_EXPANSION_LIMITS) {
            expansion = progressive.expandChoices(limit)
            if (expansion.candidates.any { it.signature == signature } || expansion.isExhaustive) break
        }
        return expansion
    }

    private fun systematicResample(
        normalized: List<LogWeightedWorld>,
        outputCount: Int,
        seed: Long,
        rejuvenator: ParticleRejuvenator,
    ): List<LogWeightedWorld> {
        val weights = normalizedWeights(normalized)
        val cumulative = DoubleArray(weights.size)
        var running = 0.0
        for (index in weights.indices) {
            running += weights[index]
            cumulative[index] = running
        }
        cumulative[cumulative.lastIndex] = 1.0
        val random = SplitMix64(seed)
        val start = random.nextDouble() / outputCount
        val duplicates = IntArray(weights.size)
        return List(outputCount) { outputIndex ->
            val point = start + outputIndex.toDouble() / outputCount
            var source = cumulative.binarySearch(point)
            if (source < 0) source = -source - 1
            source = source.coerceAtMost(normalized.lastIndex)
            val duplicateIndex = duplicates[source]++
            val world = if (duplicateIndex == 0) {
                normalized[source].world.fork()
            } else {
                rejuvenator.rejuvenate(
                    normalized[source].world,
                    duplicateIndex,
                    ComponentSeeds.derive(seed, source, duplicateIndex, "rejuvenate"),
                )
            }
            LogWeightedWorld(world, -ln(outputCount.toDouble()))
        }
    }

    private data class LogWeightedWorld(val world: SearchWorld, val logWeight: Double)

    companion object {
        private val OBSERVED_ACTION_EXPANSION_LIMITS = listOf(128, 256, 512, 1_024, 2_048)

        fun from(batch: BeliefBatch<Weighted<SearchWorld>>, mode: BeliefMode): ParticleBelief {
            require(batch.particles.isNotEmpty())
            val entries = batch.particles.map { weighted ->
                require(weighted.weight > 0.0)
                LogWeightedWorld(weighted.value, ln(weighted.weight))
            }
            return ParticleBelief(
                normalizeLogs(entries),
                mode,
                batch.diagnostics.resamplingCount,
                batch.diagnostics.architecture,
                batch.diagnostics.knowledgeDigest,
                batch.diagnostics.strata,
                batch.diagnostics.proposalAttempts,
            )
        }

        private fun normalizeLogs(entries: List<LogWeightedWorld>): List<LogWeightedWorld> {
            val maximum = entries.maxOf { it.logWeight }
            val logTotal = maximum + ln(entries.sumOf { exp(it.logWeight - maximum) })
            return entries.map { it.copy(logWeight = it.logWeight - logTotal) }
        }

        private fun normalizedWeights(entries: List<LogWeightedWorld>): List<Double> {
            val normalized = normalizeLogs(entries)
            return normalized.map { exp(it.logWeight) }
        }

        private fun effectiveSampleSize(entries: List<LogWeightedWorld>): Double =
            effectiveSampleSizeOfWeights(normalizedWeights(entries))

        private fun effectiveSampleSizeOfWeights(weights: List<Double>): Double =
            1.0 / weights.sumOf { it * it }

        private fun entropy(entries: List<LogWeightedWorld>): Double = entropyOfWeights(normalizedWeights(entries))

        private fun entropyOfWeights(weights: List<Double>): Double =
            -weights.filter { it > 0.0 }.sumOf { it * ln(it) }

        private fun <T> sample(distribution: ProbabilityDistribution<T>, random: SplitMix64): T {
            val target = random.nextDouble()
            var cumulative = 0.0
            distribution.entries.forEach { entry ->
                cumulative += entry.probability
                if (target < cumulative) return entry.value
            }
            return distribution.entries.last().value
        }
    }
}

internal class SplitMix64(seed: Long) {
    private var state = seed

    fun nextLong(): Long {
        state += -7046029254386353131L
        var value = state
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        return value xor (value ushr 31)
    }

    fun nextDouble(): Double = nextLong().ushr(11).toDouble() / (1L shl 53).toDouble()
}
