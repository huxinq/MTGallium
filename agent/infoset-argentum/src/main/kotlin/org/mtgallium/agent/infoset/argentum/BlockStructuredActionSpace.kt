package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.DeclareBlockers
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.sdk.model.EntityId
import org.mtgallium.agent.infoset.core.StructuredActionSpace
import org.mtgallium.agent.infoset.core.StructuredProposalPriority

/** Proposer-local state. It is deliberately not an engine state or a policy action. */
internal data class BlockPartial(
    val processedBlockers: Int,
    val assignments: List<List<EntityId>>,
)

internal data class BlockExtension(val attackers: List<EntityId>)

/**
 * Incremental blocker declaration grammar.
 *
 * The grammar enforces the public per-blocker caps and mandatory assignments supplied by legal
 * action enumeration. Interaction-heavy legality (evasion, taxes, maximum-block constraints,
 * and continuous effects) remains the engine's authority and is revalidated after finalization.
 */
internal class BlockStructuredActionSpace(
    private val template: DeclareBlockers,
    legal: LegalAction,
    attackers: List<EntityId>,
    private val refs: SafeReferenceMap,
    observation: TrainingObservation,
) : StructuredActionSpace<BlockPartial, BlockExtension, DeclareBlockers> {
    private data class BlockerSpec(
        val id: EntityId,
        val cap: Int,
        val required: List<EntityId>,
        val equivalenceKey: String,
        val stableKey: String,
    )

    private val visibleCards = observation.zones.flatMap { it.cards }.associateBy { it.entityId }
    private val attackerPower = attackers.associateWith { visibleCards[it]?.power?.coerceAtLeast(0) ?: 0 }
    private val orderedAttackers = attackers.distinct().sortedWith(
        compareByDescending<EntityId> { attackerPower.getValue(it) }
            .thenBy(refs::semanticReference)
            .thenBy(refs::objectRef)
    )
    private val attackerIndex = orderedAttackers.withIndex().associate { (index, id) -> id to index }
    private val blockers = legal.validBlockers.orEmpty().distinct().map { blocker ->
        val cap = (legal.blockerMaxBlockCounts?.get(blocker) ?: 1)
            .coerceAtLeast(0)
            .coerceAtMost(orderedAttackers.size)
        val required = legal.mandatoryBlockerAssignments?.get(blocker).orEmpty()
            .distinct()
            .sortedBy { attackerIndex[it] ?: Int.MAX_VALUE }
        val restrictionKey = required.joinToString(",") { refs.semanticReference(it) }
        val exactRestrictionKey = required.joinToString(",") { refs.objectRef(it) }
        val equivalenceKey = listOf(
            refs.semanticReference(blocker),
            cap.toString(),
            restrictionKey,
            exactRestrictionKey,
        ).joinToString("|")
        BlockerSpec(
            id = blocker,
            cap = cap,
            required = required,
            equivalenceKey = equivalenceKey,
            stableKey = listOf(
                equivalenceKey,
                refs.objectRef(blocker),
            ).joinToString("|"),
        )
    }.sortedBy(BlockerSpec::stableKey)

    override fun start(): BlockPartial = BlockPartial(0, emptyList())

    override fun legalExtensions(partial: BlockPartial): Sequence<BlockExtension> {
        require(!isComplete(partial)) { "A complete block declaration has no extensions" }
        val blocker = blockers[partial.processedBlockers]
        if (blocker.required.any { it !in attackerIndex } || blocker.required.size > blocker.cap) {
            return emptySequence()
        }
        val optional = orderedAttackers.filter { it !in blocker.required }
        val additionalCap = (blocker.cap - blocker.required.size).coerceAtMost(optional.size)
        val minimumSymmetricSelection = blockers.getOrNull(partial.processedBlockers - 1)
            ?.takeIf { it.equivalenceKey == blocker.equivalenceKey }
            ?.let { selectionKey(partial.assignments.last()) }
        return sequence {
            // Larger selections and high-power attackers appear first. The empty/minimum branch
            // is still exhaustive and is also represented by the restraint anchor below.
            for (additional in additionalCap downTo 0) {
                for (selected in combinations(optional, additional)) {
                    val full = (blocker.required + selected).sortedBy(attackerIndex::getValue)
                    if (minimumSymmetricSelection == null || selectionKey(full) >= minimumSymmetricSelection) {
                        yield(BlockExtension(full))
                    }
                }
            }
        }
    }

    override fun extend(partial: BlockPartial, extension: BlockExtension): BlockPartial {
        require(!isComplete(partial)) { "Cannot extend a complete block declaration" }
        val blocker = blockers[partial.processedBlockers]
        require(extension.attackers.size <= blocker.cap)
        require(blocker.required.all { it in extension.attackers })
        require(extension.attackers.all { it in attackerIndex })
        return BlockPartial(
            processedBlockers = partial.processedBlockers + 1,
            assignments = partial.assignments + listOf(extension.attackers),
        )
    }

    override fun isComplete(partial: BlockPartial): Boolean = partial.processedBlockers == blockers.size

    override fun finalize(partial: BlockPartial): DeclareBlockers {
        require(isComplete(partial)) { "Only complete block declarations can become engine actions" }
        val assignments = blockers.indices.mapNotNull { index ->
            partial.assignments[index].takeIf { it.isNotEmpty() }?.let { blockers[index].id to it }
        }.toMap()
        return template.copy(blockers = assignments)
    }

    override fun canonicalPartialKey(partial: BlockPartial): String = buildString {
        append(partial.processedBlockers)
        partial.assignments.forEachIndexed { index, selected ->
            append(';')
            append(refs.objectRef(blockers[index].id))
            append("->")
            append(selected.map(refs::objectRef).sorted().joinToString(","))
        }
    }

    override fun canonicalActionKey(partial: BlockPartial): String = canonicalPartialKey(partial)

    override fun priority(partial: BlockPartial): StructuredProposalPriority {
        val covered = partial.assignments.flatten().toSet()
        val protectedPower = covered.sumOf { attackerPower.getValue(it) }.toLong()
        val remainingCapacity = blockers.drop(partial.processedBlockers).sumOf { it.cap }
        val optimisticExtraPower = orderedAttackers.asSequence()
            .filter { it !in covered }
            .take(remainingCapacity)
            .sumOf { attackerPower.getValue(it) }
            .toLong()
        val assignedEdges = partial.assignments.sumOf(List<EntityId>::size).toLong()
        return StructuredProposalPriority(
            tiers = listOf(
                protectedPower + optimisticExtraPower,
                assignedEdges + remainingCapacity,
                partial.processedBlockers.toLong(),
                covered.size.toLong(),
            ),
            stableKey = canonicalPartialKey(partial),
        )
    }

    override fun preferredCompletions(): Sequence<BlockPartial> = sequence {
        if (blockers.any { it.required.any { attacker -> attacker !in attackerIndex } || it.required.size > it.cap }) {
            return@sequence
        }

        // Restraint/minimum anchor.
        yield(complete(blockers.map(BlockerSpec::required)))

        if (orderedAttackers.isEmpty() || blockers.isEmpty()) return@sequence

        // Spread across the highest-power attackers while preserving mandatory assignments.
        val spreadUsed = blockers.flatMap(BlockerSpec::required).toMutableSet()
        val spread = blockers.map { blocker ->
            val selected = blocker.required.toMutableList()
            val additions = orderedAttackers.asSequence()
                .filter { attacker -> attacker !in selected }
                .sortedWith(compareBy<EntityId> { if (it in spreadUsed) 1 else 0 }
                    .thenBy(attackerIndex::getValue))
                .take(blocker.cap - selected.size)
                .toList()
            selected += additions
            spreadUsed += additions
            selected.sortedBy(attackerIndex::getValue)
        }
        yield(complete(spread))

        // Pile anchors preserve the tactically distinct "all available blockers here" shapes.
        for (attacker in orderedAttackers) {
            val pile = blockers.map { blocker ->
                when {
                    attacker in blocker.required -> blocker.required
                    blocker.required.size < blocker.cap ->
                        (blocker.required + attacker).sortedBy(attackerIndex::getValue)
                    else -> blocker.required
                }
            }
            yield(complete(pile))
        }

        // Sparse matchup anchors prevent full-board assignments from monopolizing early prefixes.
        blockers.forEachIndexed { blockerIndex, blocker ->
            for (attacker in orderedAttackers) {
                if (attacker in blocker.required || blocker.required.size >= blocker.cap) continue
                val sparse = blockers.map(BlockerSpec::required).toMutableList()
                sparse[blockerIndex] = (blocker.required + attacker).sortedBy(attackerIndex::getValue)
                yield(complete(sparse))
            }
        }
    }

    private fun complete(assignments: List<List<EntityId>>): BlockPartial = BlockPartial(
        processedBlockers = blockers.size,
        assignments = assignments,
    )

    private fun selectionKey(attackers: List<EntityId>): String =
        attackers.map(refs::objectRef).sorted().joinToString(",")
}

private fun <T> combinations(values: List<T>, size: Int): Sequence<List<T>> = sequence {
    if (size == 0) {
        yield(emptyList())
        return@sequence
    }
    suspend fun SequenceScope<List<T>>.visit(start: Int, remaining: Int, prefix: MutableList<T>) {
        if (remaining == 0) {
            yield(prefix.toList())
            return
        }
        for (index in start..values.size - remaining) {
            prefix += values[index]
            visit(index + 1, remaining - 1, prefix)
            prefix.removeAt(prefix.lastIndex)
        }
    }
    if (size <= values.size) visit(0, size, mutableListOf())
}
