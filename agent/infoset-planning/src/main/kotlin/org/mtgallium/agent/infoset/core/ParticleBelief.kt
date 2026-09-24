package org.mtgallium.agent.infoset.core

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

open class ParticleDepletionException(message: String) : IllegalStateException(message)

fun interface ParticleRejuvenator {
    fun rejuvenate(world: SearchWorld, duplicateIndex: Int, seed: Long): SearchWorld

    companion object {
        val FORK_ONLY = ParticleRejuvenator { world, _, _ -> world.fork() }
    }
}

data class ParticleBeliefUpdate(
    val belief: ParticleBelief,
    val diagnostics: BeliefDiagnostics,
    /** Present only when requested and the complete update passed its postconditions. */
    val ancestry: ParticleUpdateAncestry? = null,
)

/** Numeric, trusted diagnostic only: no worlds or hidden state cross this readout. */
data class ParticleAdvanceMass(
    val inputIndex: Int,
    val logUnnormalizedWeight: Double,
    val posteriorWeight: Double,
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

    /** Independent particle worlds with exact retained log weights and resampling provenance. */
    fun fork(): ParticleBelief = ParticleBelief(
        entries = entries.map { it.copy(world = it.world.fork()) },
        mode = mode, resamplingCount = resamplingCount, architecture = architecture,
        knowledgeDigest = knowledgeDigest, strata = strata, proposalAttempts = proposalAttempts,
    )

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
        captureAncestry: Boolean = false,
    ): ParticleBeliefUpdate {
        val ancestry = if (captureAncestry) ParticleAncestryRecorder(entries.size) else null
        val compatible = entries.filterIndexed { index, entry ->
            (entry.world.informationState(viewer).informationStateDigest == expectedInformationStateDigest).also {
                if (it) ancestry?.survived(index)
            }
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
            systematicResample(normalized, entries.size, updateSeed, rejuvenator, ancestry)
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
            ancestry = ancestry?.finish(needsResampling, nextEntries.size),
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
        opponentPolicy: ActionDistributionModel,
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
        opponentPolicy: ActionSelector,
        updateSeed: Long,
        rejuvenator: ParticleRejuvenator = ParticleRejuvenator.FORK_ONLY,
        observation: ParticleObservationCondition? = null,
        captureAncestry: Boolean = false,
    ): ParticleBeliefUpdate {
        require(!opponentPolicy.requiresPolicyAnnotations || opponentPolicy.requiresProductionAdmission)
        val advanced = mutableListOf<LogWeightedWorld>()
        val ancestry = if (captureAncestry) ParticleAncestryRecorder(entries.size) else null
        val opponentDecisionCounter = OpponentPolicyDecisionCounter()
        var rejected = 0
        var observationRejected = 0
        entries.forEachIndexed { index, entry ->
            // Private choices are still sampled from the actor's own safe information. Preserve
            // the declared menu population; only opted-in policies omit production admission.
            // Materialize annotations only for policies that declare that requirement.
            val context = entry.world.decisionContext(opponentPolicy.decisionView())
            val candidates = context.expansion.candidates
            if (candidates.isEmpty()) {
                rejected++
                return@forEachIndexed
            }
            require(context.actor == actor) { "Private-choice actor differs from the captured decision" }
            val decision = opponentPolicy.select(
                context = context,
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
            if (observation != null && !observation.matches(child)) {
                observationRejected++
                return@forEachIndexed
            }
            advanced += LogWeightedWorld(child, entry.logWeight)
            ancestry?.survived(index)
        }
        if (advanced.isEmpty()) throw ParticleDepletionException(
            if (observation == null) "Private choice could not advance any particle"
            else "Private choice and observed information left no compatible particle"
        )
        val normalized = normalizeLogs(advanced)
        val essBefore = effectiveSampleSize(normalized)
        val needsResampling = normalized.size != entries.size || essBefore < entries.size / 2.0
        val nextEntries = if (needsResampling) {
            systematicResample(normalized, entries.size, updateSeed, rejuvenator, ancestry)
        } else {
            normalized
        }
        val weights = normalizedWeights(nextEntries)
        check(observation == null || nextEntries.all { observation.matches(it.world) }) {
            "Particle rejuvenation changed the observed information it must preserve"
        }
        val nextKnowledgeDigest = if (observation == null) knowledgeDigest else observation.knowledgeDigest
        val next = ParticleBelief(
            entries = nextEntries,
            mode = mode,
            resamplingCount = resamplingCount + if (needsResampling) 1 else 0,
            architecture = architecture,
            knowledgeDigest = nextKnowledgeDigest,
            strata = strata,
            proposalAttempts = proposalAttempts,
        )
        return ParticleBeliefUpdate(
            ancestry = ancestry?.finish(needsResampling, nextEntries.size),
            belief = next,
            diagnostics = BeliefDiagnostics(
                mode = mode,
                requestedParticles = entries.size,
                acceptedParticles = nextEntries.size,
                rejectedParticles = rejected + observationRejected,
                effectiveSampleSizeBefore = essBefore,
                effectiveSampleSizeAfter = effectiveSampleSizeOfWeights(weights),
                entropy = entropyOfWeights(weights),
                resamplingCount = next.resamplingCount,
                failures = buildMap {
                    if (rejected > 0) put("privateChoiceRejectedParticles", rejected)
                    if (observationRejected > 0) put("observationMismatchParticles", observationRejected)
                },
                architecture = architecture,
                knowledgeDigest = nextKnowledgeDigest,
                strata = strata,
                proposalAttempts = proposalAttempts,
                opponentPolicyDecisions = opponentDecisionCounter.summary(),
            ),
        )
    }

    /**
     * Combine the observed-action likelihood and new observer information before
     * normalization/resampling.
     */
    fun advance(
        actor: String,
        observedSignature: String,
        conditioningPolicy: ActionDistributionModel? = null,
        updateSeed: Long,
        rejuvenator: ParticleRejuvenator = ParticleRejuvenator.FORK_ONLY,
        observation: ParticleObservationCondition? = null,
        exactAction: ExactObservedAction? = null,
        // Trusted submission provenance for signature propagation; never changes group likelihood.
        signatureStep: ((SearchWorld, SemanticChoice) -> SearchStepResult)? = null,
        // Called only for an admitted update, after actual normalization and before resampling.
        // Indices refer to input entries; absent entries were excluded. Not a policy input.
        preResamplingReadout: ((List<ParticleAdvanceMass>) -> Unit)? = null,
        // Trusted output-to-input indices recorded at selection, not reconstructed from states.
        captureAncestry: Boolean = false,
    ): ParticleBeliefUpdate {
        require(exactAction == null || signatureStep == null) {
            "Exact-member execution and signature propagation are distinct routes"
        }
        require(conditioningPolicy == null || mode == BeliefMode.POLICY_CONDITIONED_V1) {
            "Opponent-action conditioning is only defined for POLICY_CONDITIONED_V1"
        }
        val advanced = mutableListOf<LogWeightedWorld>()
        val ancestry = if (captureAncestry) ParticleAncestryRecorder(entries.size) else null
        var rejected = 0
        var observationRejected = 0
        var invalidWeights = 0
        var exactZeroMass = 0
        val survivingIndices = if (preResamplingReadout != null) mutableListOf<Int>() else null
        val unavailableReasons = linkedMapOf<String, Int>()
        fun recordUnavailable(reason: String) { unavailableReasons[reason] = (unavailableReasons[reason] ?: 0) + 1 }
        val probabilities = mutableListOf<Double>()
        for ((index, entry) in entries.withIndex()) {
            val exact = when (val resolved = exactAction?.resolve(entry.world)) {
                null -> null
                is ExactObservedActionResolution.Matched -> resolved.also {
                    require(it.groupSignature == observedSignature) { "Exact declaration belongs to a different group" }
                }
                is ExactObservedActionResolution.Unsupported -> { recordUnavailable(resolved.reason); continue }
                is ExactObservedActionResolution.NativeRejected -> { rejected++; continue }
            }
            // The likelihood policy sees the admission and annotations it declares, as in search.
            val context = exact?.context ?: contextContaining(entry.world, observedSignature,
                conditioningPolicy?.decisionView() ?: DecisionView())
            if (exact != null) require(context.actor == actor) { "Observed-choice actor differs from the captured decision" }
            val candidates = context.expansion.candidates
            val observed = candidates.singleOrNull { it.signature == observedSignature }
            if (observed == null) {
                if (exact != null) recordUnavailable("GROUP_MISSING_FROM_BOUND_MENU") else rejected++
                continue
            }
            val probability = if (conditioningPolicy == null) {
                1.0
            } else {
                require(context.actor == actor) { "Observed-choice actor differs from the captured decision" }
                val distribution = conditioningPolicy.distribution(context,
                    ComponentSeeds.derive(updateSeed, index, conditioningPolicy.id, "likelihood"))
                val rawProbability = distribution.probabilityOf { it.signature == observedSignature }
                val floor = 0.01 / candidates.size.coerceAtLeast(1)
                // Historical group smoothing never supplies mass to an unselected exact member.
                max(rawProbability, floor) * (exact?.memberProbability ?: 1.0)
            }
            if (probability == 0.0 && exact?.memberProbability == 0.0) {
                exactZeroMass++
                continue
            }
            if (!probability.isFinite() || probability <= 0.0) {
                invalidWeights++
                continue
            }
            val child = entry.world.fork()
            if (!(exact?.apply?.invoke(child) ?: signatureStep?.invoke(child, observed) ?: child.step(observed)).accepted) {
                rejected++
                continue
            }
            probabilities += probability
            if (observation != null && !observation.matches(child)) {
                observationRejected++
                continue
            }
            advanced += LogWeightedWorld(child, entry.logWeight + ln(probability))
            survivingIndices?.add(index)
            ancestry?.survived(index)
        }
        if (exactAction != null && (unavailableReasons.isNotEmpty() || advanced.isEmpty())) {
            val counts = ExactObservationFailureCounts(entries.size, unavailableReasons.values.sum(), exactZeroMass,
                rejected, observationRejected, invalidWeights, advanced.size)
            val kind = when {
                unavailableReasons.isNotEmpty() -> ExactObservationFailureKind.UNAVAILABLE_CORRESPONDENCE
                exactZeroMass == entries.size -> ExactObservationFailureKind.EXACT_MEMBER_ZERO_MASS
                rejected == entries.size -> ExactObservationFailureKind.NATIVE_REJECTION
                observationRejected == entries.size -> ExactObservationFailureKind.INCOMPATIBLE_SUCCESSOR
                invalidWeights == entries.size -> ExactObservationFailureKind.INVALID_WEIGHT
                else -> ExactObservationFailureKind.MIXED_EXHAUSTION
            }
            val report = ExactObservationFailureReport(kind, counts, unavailableReasons.toMap())
            if (unavailableReasons.isNotEmpty()) throw UnsupportedObservedActionException("UNAVAILABLE_CORRESPONDENCE", report)
            throw ExactObservationDepletionException(report)
        }
        if (advanced.isEmpty()) throw ParticleDepletionException(
            if (observation == null) "Observed action $observedSignature is incompatible with every particle"
            else "Observed action and observer information left no compatible particle"
        )
        val normalized = normalizeLogs(advanced)
        preResamplingReadout?.invoke(normalizedWeights(normalized).mapIndexed { index, weight ->
            ParticleAdvanceMass(requireNotNull(survivingIndices)[index], advanced[index].logWeight, weight)
        })
        val essBefore = effectiveSampleSize(normalized)
        val needsResampling = normalized.size != entries.size || essBefore < entries.size / 2.0
        val nextEntries = if (needsResampling) {
            systematicResample(normalized, entries.size, updateSeed, rejuvenator, ancestry)
        } else {
            normalized
        }
        val normalizedAfter = normalizedWeights(nextEntries)
        val sensitivity = if (probabilities.isEmpty()) null else {
            probabilities.max() - probabilities.min()
        }
        check(observation == null || nextEntries.all { observation.matches(it.world) }) {
            "Particle rejuvenation changed the observed information it must preserve"
        }
        val nextKnowledgeDigest = if (observation == null) knowledgeDigest else observation.knowledgeDigest
        val next = ParticleBelief(
            entries = nextEntries,
            mode = mode,
            resamplingCount = resamplingCount + if (needsResampling) 1 else 0,
            architecture = architecture,
            knowledgeDigest = nextKnowledgeDigest,
            strata = strata,
            proposalAttempts = proposalAttempts,
        )
        return ParticleBeliefUpdate(
            ancestry = ancestry?.finish(needsResampling, nextEntries.size),
            belief = next,
            diagnostics = BeliefDiagnostics(
                mode = mode,
                requestedParticles = entries.size,
                acceptedParticles = nextEntries.size,
                rejectedParticles = rejected + observationRejected + exactZeroMass,
                effectiveSampleSizeBefore = essBefore,
                effectiveSampleSizeAfter = effectiveSampleSizeOfWeights(normalizedAfter),
                entropy = entropyOfWeights(normalizedAfter),
                resamplingCount = next.resamplingCount,
                modelSensitivity = sensitivity,
                failures = buildMap {
                    if (invalidWeights > 0) put("invalidWeights", invalidWeights)
                    if (exactZeroMass > 0) put("exactMemberZeroMassParticles", exactZeroMass)
                    if (rejected > 0) put("incompatibleParticles", rejected)
                    if (observationRejected > 0) put("observationMismatchParticles", observationRejected)
                },
                architecture = architecture,
                knowledgeDigest = nextKnowledgeDigest,
                strata = strata,
                proposalAttempts = proposalAttempts,
            ),
        )
    }

    private fun contextContaining(world: SearchWorld, signature: String, view: DecisionView): DecisionSiteRequest {
        var context = world.decisionContext(view)
        if (context.expansion.candidates.any { it.signature == signature } || context.expansion.isExhaustive) return context
        if (world !is ProgressiveSearchWorld) return context
        for (limit in OBSERVED_ACTION_EXPANSION_LIMITS) {
            context = world.decisionContext(view.copy(limit = limit))
            if (context.expansion.candidates.any { it.signature == signature } || context.expansion.isExhaustive) break
        }
        return context
    }

    private fun systematicResample(
        normalized: List<LogWeightedWorld>,
        outputCount: Int,
        seed: Long,
        rejuvenator: ParticleRejuvenator,
        ancestry: ParticleAncestryRecorder?,
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
            ancestry?.copied(source, duplicateIndex)
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

        private fun entropyOfWeights(weights: List<Double>): Double =
            -weights.filter { it > 0.0 }.sumOf { it * ln(it) }
    }
}
