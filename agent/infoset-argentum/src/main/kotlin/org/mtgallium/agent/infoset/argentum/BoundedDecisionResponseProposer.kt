package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.AssignDamageDecision
import com.wingedsheep.engine.core.BatchYesNoDecision
import com.wingedsheep.engine.core.BatchYesNoResponse
import com.wingedsheep.engine.core.BudgetModalDecision
import com.wingedsheep.engine.core.BudgetModalResponse
import com.wingedsheep.engine.core.CancelDecisionResponse
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.ChooseColorDecision
import com.wingedsheep.engine.core.ChooseModeDecision
import com.wingedsheep.engine.core.ChooseNumberDecision
import com.wingedsheep.engine.core.ChooseOptionDecision
import com.wingedsheep.engine.core.ChooseReplacementDecision
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.core.CombatResolutionDecision
import com.wingedsheep.engine.core.CombatResolutionResponse
import com.wingedsheep.engine.core.DamageAssignmentResponse
import com.wingedsheep.engine.core.DamageEdgeAmount
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.DistributeDecision
import com.wingedsheep.engine.core.DistributionResponse
import com.wingedsheep.engine.core.ManaSourcesSelectedResponse
import com.wingedsheep.engine.core.ModesChosenResponse
import com.wingedsheep.engine.core.NumberChosenResponse
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.OrderObjectsDecision
import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.PendingDecision
import com.wingedsheep.engine.core.PilesSplitResponse
import com.wingedsheep.engine.core.ReorderLibraryDecision
import com.wingedsheep.engine.core.ReplacementChosenResponse
import com.wingedsheep.engine.core.SearchLibraryDecision
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.SelectManaSourcesDecision
import com.wingedsheep.engine.core.SplitPilesDecision
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.core.YesNoResponse
import com.wingedsheep.engine.handlers.actions.decision.DecisionValidators
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.model.EntityId
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A downstream proposal set; [isExhaustive] is true only after the source was fully enumerated. */
internal data class StructuredResponseProposal(
    val responses: List<DecisionResponse>,
    val isExhaustive: Boolean,
    val estimatedResponseCount: Long?,
)

/**
 * Deterministically proposes responses for every engine decision type without touching
 * [GameState.rng].
 *
 * Argentum's exact expander is consulted before this class. This policy exists only for unsupported
 * or larger response spaces: it retains stable boundary/default choices and fills to [maxResponses]
 * from a deterministic traversal. Every proposal is checked by [DecisionValidators], and a
 * truncated source never claims completeness.
 */
internal class BoundedDecisionResponseProposer(
    private val maxResponses: Int = 64,
    private val maxAttempts: Int = 2_048,
) {

    init {
        require(maxResponses > 0) { "maxResponses must be positive" }
        require(maxAttempts >= maxResponses) { "maxAttempts must be at least maxResponses" }
    }

    fun propose(state: GameState, decision: PendingDecision): StructuredResponseProposal {
        val source = candidates(decision)
        val valid = LinkedHashSet<DecisionResponse>()
        val iterator = source.responses.iterator()
        var attempts = 0
        var exhausted = false
        var lastError: String? = null

        while (attempts < maxAttempts) {
            if (!iterator.hasNext()) {
                exhausted = true
                break
            }
            val response = iterator.next()
            attempts += 1
            val error = DecisionValidators.validate(decision, response, state)
            if (error == null) valid += response else lastError = error
        }

        check(valid.isNotEmpty()) {
            "No valid expansion for ${decision::class.simpleName} after $attempts attempts" +
                (lastError?.let { ": $it" } ?: "")
        }

        val complete = exhausted && source.completeWhenExhausted
        val validList = valid.toList()
        // Candidate generators put legal boundaries/defaults first. Preserve the first two anchors,
        // then use a canonical decision-payload seed to spread the remaining cap across the sampled
        // response space without consuming game randomness.
        val anchors = validList.take(2)
        val seed = canonicalPayloadHash(decision)
        val stratified = validList.drop(anchors.size).sortedBy { response ->
            canonicalPayloadHash(response, seed)
        }
        val responses = (anchors + stratified).take(maxResponses)
        return StructuredResponseProposal(
            responses = responses,
            isExhaustive = complete && validList.size <= maxResponses,
            estimatedResponseCount = when {
                complete -> validList.size.toLong()
                source.estimatedCount != null -> source.estimatedCount
                source.estimateFromValidatedPrefix && validList.size > maxResponses -> validList.size.toLong()
                else -> null
            },
        )
    }

    private data class CandidateSource(
        val responses: Sequence<DecisionResponse>,
        val completeWhenExhausted: Boolean = true,
        val estimatedCount: Long? = null,
        val estimateFromValidatedPrefix: Boolean = true,
    )

    private data class CombatOrderOverrides(
        val orderedBlockers: Map<EntityId, List<EntityId>> = emptyMap(),
        val orderedAttackers: Map<EntityId, List<EntityId>> = emptyMap(),
    )

    private fun candidates(d: PendingDecision): CandidateSource = when (d) {
        is YesNoDecision -> sourceOf(
            YesNoResponse(d.id, false),
            YesNoResponse(d.id, true),
        )
        is BatchYesNoDecision -> sourceOf(
            BatchYesNoResponse(d.id, false, true),
            BatchYesNoResponse(d.id, true, true),
            BatchYesNoResponse(d.id, false, false),
            BatchYesNoResponse(d.id, true, false),
        )
        is ChooseNumberDecision -> CandidateSource(
            boundaryFirst(d.minValue..d.maxValue).map { NumberChosenResponse(d.id, it) },
            estimatedCount = rangeCount(d.minValue, d.maxValue),
        )
        is ChooseColorDecision -> CandidateSource(
            d.availableColors.sortedBy { it.ordinal }.asSequence().map { ColorChosenResponse(d.id, it) },
            estimatedCount = d.availableColors.size.toLong(),
        )
        is ChooseOptionDecision -> CandidateSource(
            sequence {
                if (d.canCancel) yield(CancelDecisionResponse(d.id))
                for (index in d.options.indices) yield(OptionChosenResponse(d.id, index))
            },
            estimatedCount = d.options.size.toLong() + if (d.canCancel) 1 else 0,
        )
        is ChooseReplacementDecision -> CandidateSource(
            sequence {
                val preferredFrom = boundaryFirst(d.fromOptions.indices).toList()
                for (from in preferredFrom) {
                    val allowed = d.allowedToByFrom.getOrNull(from)?.takeIf { it.isNotEmpty() }
                        ?: d.toOptions.indices.toList()
                    for (to in boundaryFirst(allowed)) yield(ReplacementChosenResponse(d.id, from, to))
                }
            },
        )
        is ChooseModeDecision -> {
            val modes = d.modes.filter { it.available }.map { it.index }
            CandidateSource(
                selectionLists(modes, d.minModes, d.maxModes, ordered = true, allowRepeats = true)
                    .map { ModesChosenResponse(d.id, it) },
            )
        }
        is SelectCardsDecision -> CandidateSource(
            selectionLists(d.options, d.minSelections, d.maxSelections, d.ordered, allowRepeats = false)
                .map { CardsSelectedResponse(d.id, it) },
        )
        is SearchLibraryDecision -> CandidateSource(
            selectionLists(d.options, d.minSelections, d.maxSelections, ordered = false, allowRepeats = false)
                .map { CardsSelectedResponse(d.id, it) },
        )
        is ChooseTargetsDecision -> CandidateSource(
            sequence {
                if (d.canCancel) yield(CancelDecisionResponse(d.id))
                val requirements = d.targetRequirements.sortedBy { it.index }
                val choices = requirements.map { requirement ->
                    selectionLists(
                        d.legalTargets[requirement.index].orEmpty(),
                        requirement.minTargets,
                        requirement.maxTargets,
                        ordered = false,
                        allowRepeats = false,
                    ).take(maxAttempts).toList()
                }
                for (selection in cartesianProduct(choices)) {
                    yield(TargetsResponse(d.id, requirements.mapIndexed { i, req -> req.index to selection[i] }.toMap()))
                }
            },
        )
        is DistributeDecision -> CandidateSource(distributions(d))
        is OrderObjectsDecision -> CandidateSource(
            permutations(d.objects).map { OrderedResponse(d.id, it) },
            estimatedCount = factorialOrNull(d.objects.size),
        )
        is ReorderLibraryDecision -> CandidateSource(
            permutations(d.cards).map { OrderedResponse(d.id, it) },
            estimatedCount = factorialOrNull(d.cards.size),
        )
        is SplitPilesDecision -> CandidateSource(splitPiles(d))
        is AssignDamageDecision -> CandidateSource(assignDamage(d))
        is CombatResolutionDecision -> combatResolution(d)
        is SelectManaSourcesDecision -> CandidateSource(manaSources(d))
        is BudgetModalDecision -> budgetModes(d)
    }

    private fun sourceOf(vararg responses: DecisionResponse) = CandidateSource(
        responses.asSequence(),
        estimatedCount = responses.size.toLong(),
    )

    private fun distributions(d: DistributeDecision): Sequence<DecisionResponse> = sequence {
        val targets = d.targets
        val totals = if (d.allowPartial) boundaryFirst(0..d.totalAmount) else sequenceOf(d.totalAmount)
        for (total in totals) {
            for (amounts in integerVectors(targets.size, total)) {
                yield(DistributionResponse(d.id, targets.indices.associate { targets[it] to amounts[it] }))
            }
        }
    }

    private fun splitPiles(d: SplitPilesDecision): Sequence<DecisionResponse> = sequence {
        if (d.numberOfPiles <= 0) return@sequence
        for (assignment in baseNVectors(d.cards.size, d.numberOfPiles)) {
            val piles = List(d.numberOfPiles) { mutableListOf<EntityId>() }
            d.cards.forEachIndexed { index, card -> piles[assignment[index]] += card }
            yield(PilesSplitResponse(d.id, piles))
        }
    }

    private fun assignDamage(d: AssignDamageDecision): Sequence<DecisionResponse> = sequence {
        yield(DamageAssignmentResponse(d.id, d.defaultAssignments))
        val targets = d.orderedTargets + listOfNotNull(d.defenderId)
        for (total in boundaryFirst(0..d.availablePower)) {
            for (amounts in integerVectors(targets.size, total)) {
                yield(DamageAssignmentResponse(d.id, targets.indices.associate { targets[it] to amounts[it] }))
            }
        }
    }

    private fun combatResolution(d: CombatResolutionDecision): CandidateSource {
        val editable = d.edges.filter { it.editableBy == d.playerId }
        val defaultEdges = editable.map { DamageEdgeAmount(it.id, it.amount) }
        val defaultResponse = CombatResolutionResponse(d.id, defaultEdges)
        val orderings = combatOrderings(d)
        val responseSequence = sequence {
            // Preserve the engine-supplied response as the stable first anchor.
            yield(defaultResponse)

            // Cross every non-default damage assignment with every simultaneous ordering choice.
            // Damage is outermost so the default and first boundary assignment retain their
            // historical ordering, while a changed order + changed assignment appears immediately
            // after that assignment instead of being omitted from a separate order-only pass.
            val damageValues = editable.map { edge -> boundaryFirst(0..edge.maximum) }
            for (amounts in lazyCartesianProduct(damageValues)) {
                val edges = editable.indices.map { DamageEdgeAmount(editable[it].id, amounts[it]) }
                if (edges == defaultEdges) continue
                for (ordering in orderings) {
                    yield(
                        CombatResolutionResponse(
                            decisionId = d.id,
                            edges = edges,
                            orderedBlockers = ordering.orderedBlockers,
                            orderedAttackers = ordering.orderedAttackers,
                        )
                    )
                }
            }

            // The default assignment must also be crossed with every non-default ordering.
            for (ordering in orderings.drop(1)) {
                yield(
                    defaultResponse.copy(
                        orderedBlockers = ordering.orderedBlockers,
                        orderedAttackers = ordering.orderedAttackers,
                    )
                )
            }
        }
        return CandidateSource(
            responses = responseSequence,
            // If maxAttempts stops the lazy product, the validated prefix is only a lower bound,
            // not an estimate of the full legal response count. Report null in that case.
            estimateFromValidatedPrefix = false,
        )
    }

    /**
     * Every simultaneous attacker/blocker ordering configuration. An empty map preserves the
     * decision's current order, keeping existing response payloads stable; other entries contain
     * only the rows whose order changed.
     */
    private fun combatOrderings(d: CombatResolutionDecision): Sequence<CombatOrderOverrides> {
        val dimensions = buildList<Sequence<CombatOrderOverrides>> {
            for (attacker in d.attackers) {
                if (attacker.blockedByIds.size <= 1) continue
                add(sequence {
                    yield(CombatOrderOverrides())
                    for (order in permutations(attacker.blockedByIds).drop(1)) {
                        yield(CombatOrderOverrides(orderedBlockers = mapOf(attacker.id to order)))
                    }
                })
            }
            for (blocker in d.blockers) {
                if (blocker.blockedAttackerIds.size <= 1) continue
                val currentOrder = blocker.orderedAttackers.takeIf { order ->
                    order.size == blocker.blockedAttackerIds.size &&
                        order.toSet() == blocker.blockedAttackerIds.toSet()
                } ?: blocker.blockedAttackerIds
                add(sequence {
                    yield(CombatOrderOverrides())
                    for (order in permutations(currentOrder).drop(1)) {
                        yield(CombatOrderOverrides(orderedAttackers = mapOf(blocker.id to order)))
                    }
                })
            }
        }

        return lazyCartesianProduct(dimensions).map { choices ->
            choices.fold(CombatOrderOverrides()) { combined, choice ->
                CombatOrderOverrides(
                    orderedBlockers = combined.orderedBlockers + choice.orderedBlockers,
                    orderedAttackers = combined.orderedAttackers + choice.orderedAttackers,
                )
            }
        }
    }

    private fun manaSources(d: SelectManaSourcesDecision): Sequence<DecisionResponse> = sequence {
        yield(ManaSourcesSelectedResponse(d.id, autoPay = true))
        if (d.canDecline) yield(ManaSourcesSelectedResponse(d.id, declined = true))
        val sources = d.availableSources.map { it.entityId }
        val waterbend = d.waterbendPermanents.map { it.entityId }
        for (selected in subsets(sources)) {
            for (waterbendSelected in subsets(waterbend)) {
                yield(ManaSourcesSelectedResponse(d.id, selected, waterbendPermanents = waterbendSelected.toSet()))
            }
        }
    }

    private fun budgetModes(d: BudgetModalDecision): CandidateSource {
        val hasZeroCost = d.modes.any { it.cost <= 0 }
        val sequence = sequence<DecisionResponse> {
            yield(BudgetModalResponse(d.id, emptyList()))
            suspend fun SequenceScope<DecisionResponse>.walk(prefix: List<Int>, spent: Int, depth: Int) {
                if (depth >= maxResponses) return
                for ((index, mode) in d.modes.withIndex()) {
                    val next = spent + mode.cost
                    if (mode.cost >= 0 && next <= d.budget) {
                        val selected = prefix + index
                        yield(BudgetModalResponse(d.id, selected))
                        walk(selected, next, depth + 1)
                    }
                }
            }
            walk(emptyList(), 0, 0)
        }
        return CandidateSource(sequence, completeWhenExhausted = !hasZeroCost)
    }

    private fun <T> boundaryFirst(values: Iterable<T>): Sequence<T> = sequence {
        val list = values.toList()
        if (list.isEmpty()) return@sequence
        yield(list.first())
        if (list.size > 1) yield(list.last())
        for (index in 1 until list.lastIndex) yield(list[index])
    }

    private fun boundaryFirst(values: IntRange): Sequence<Int> = sequence {
        if (values.isEmpty()) return@sequence
        yield(values.first)
        if (values.last != values.first) yield(values.last)
        var value = values.first.toLong() + 1L
        while (value < values.last.toLong()) {
            yield(value.toInt())
            value += 1L
        }
    }

    private fun <T> selectionLists(
        options: List<T>,
        min: Int,
        max: Int,
        ordered: Boolean,
        allowRepeats: Boolean,
    ): Sequence<List<T>> = sequence {
        val upper = max.coerceAtMost(if (allowRepeats) max else options.size)
        for (size in boundaryFirst(min.coerceAtLeast(0)..upper.coerceAtLeast(-1))) {
            when {
                size == 0 -> yield(emptyList())
                allowRepeats -> yieldAll(products(options, size))
                ordered -> yieldAll(permutationsOfSize(options, size))
                else -> yieldAll(combinations(options, size))
            }
        }
    }

    private fun <T> combinations(values: List<T>, size: Int): Sequence<List<T>> = sequence {
        suspend fun SequenceScope<List<T>>.walk(start: Int, remaining: Int, prefix: List<T>) {
            if (remaining == 0) {
                yield(prefix)
                return
            }
            for (index in start..values.size - remaining) walk(index + 1, remaining - 1, prefix + values[index])
        }
        if (size in 0..values.size) walk(0, size, emptyList())
    }

    private fun <T> permutations(values: List<T>): Sequence<List<T>> = permutationsOfSize(values, values.size)

    private fun <T> permutationsOfSize(values: List<T>, size: Int): Sequence<List<T>> = sequence {
        suspend fun SequenceScope<List<T>>.walk(remaining: List<T>, needed: Int, prefix: List<T>) {
            if (needed == 0) {
                yield(prefix)
                return
            }
            for (index in remaining.indices) {
                walk(remaining.toMutableList().also { it.removeAt(index) }, needed - 1, prefix + remaining[index])
            }
        }
        if (size in 0..values.size) walk(values, size, emptyList())
    }

    private fun <T> products(values: List<T>, size: Int): Sequence<List<T>> = sequence {
        suspend fun SequenceScope<List<T>>.walk(remaining: Int, prefix: List<T>) {
            if (remaining == 0) {
                yield(prefix)
                return
            }
            for (value in values) walk(remaining - 1, prefix + value)
        }
        if (size >= 0) walk(size, emptyList())
    }

    private fun <T> subsets(values: List<T>): Sequence<List<T>> = sequence {
        for (size in boundaryFirst(0..values.size)) yieldAll(combinations(values, size))
    }

    private fun <T> cartesianProduct(dimensions: List<List<T>>): Sequence<List<T>> = sequence {
        suspend fun SequenceScope<List<T>>.walk(index: Int, prefix: List<T>) {
            if (index == dimensions.size) {
                yield(prefix)
                return
            }
            for (value in dimensions[index]) walk(index + 1, prefix + value)
        }
        if (dimensions.none { it.isEmpty() }) walk(0, emptyList())
    }

    /** Cartesian traversal without materializing any dimension or the resulting product. */
    private fun <T> lazyCartesianProduct(dimensions: List<Sequence<T>>): Sequence<List<T>> = sequence {
        suspend fun SequenceScope<List<T>>.walk(index: Int, prefix: List<T>) {
            if (index == dimensions.size) {
                yield(prefix)
                return
            }
            for (value in dimensions[index]) walk(index + 1, prefix + value)
        }
        walk(0, emptyList())
    }

    private fun integerVectors(size: Int, total: Int): Sequence<List<Int>> = sequence {
        suspend fun SequenceScope<List<Int>>.walk(remainingSlots: Int, remaining: Int, prefix: List<Int>) {
            if (remainingSlots == 0) {
                if (remaining == 0) yield(prefix)
                return
            }
            for (value in boundaryFirst(0..remaining)) walk(remainingSlots - 1, remaining - value, prefix + value)
        }
        if (size == 0) {
            if (total == 0) yield(emptyList())
        } else {
            walk(size, total, emptyList())
        }
    }

    private fun baseNVectors(size: Int, base: Int): Sequence<List<Int>> = sequence {
        suspend fun SequenceScope<List<Int>>.walk(remaining: Int, prefix: List<Int>) {
            if (remaining == 0) {
                yield(prefix)
                return
            }
            for (digit in 0 until base) walk(remaining - 1, prefix + digit)
        }
        if (base > 0) walk(size, emptyList())
    }

    private fun rangeCount(min: Int, max: Int): Long = if (max < min) 0 else max.toLong() - min.toLong() + 1L

    private fun factorialOrNull(n: Int): Long? {
        var result = 1L
        for (i in 2..n) {
            if (result > Long.MAX_VALUE / i) return null
            result *= i
        }
        return result
    }

    private fun canonicalPayloadHash(value: Any, salt: String = ""): String {
        val encoded = when (value) {
            is PendingDecision -> Json.encodeToJsonElement(PendingDecision.serializer(), value)
            is DecisionResponse -> Json.encodeToJsonElement(DecisionResponse.serializer(), value)
            else -> error("Unsupported canonical payload: ${value::class.simpleName}")
        }
        val withoutRoutingNonce = if (encoded is JsonObject) {
            JsonObject(encoded.filterKeys { it != "id" && it != "decisionId" })
        } else {
            encoded
        }
        val canonical = buildString { appendCanonicalJson(this, withoutRoutingNonce) }
        val digest = MessageDigest.getInstance("SHA-256").digest((salt + canonical).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun appendCanonicalJson(out: StringBuilder, value: JsonElement) {
        when (value) {
            is JsonObject -> {
                out.append('{')
                value.entries.sortedBy { it.key }.forEachIndexed { index, (key, element) ->
                    if (index > 0) out.append(',')
                    out.append(JsonPrimitive(key)).append(':')
                    appendCanonicalJson(out, element)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                value.forEachIndexed { index, element ->
                    if (index > 0) out.append(',')
                    appendCanonicalJson(out, element)
                }
                out.append(']')
            }
            else -> out.append(value)
        }
    }
}
