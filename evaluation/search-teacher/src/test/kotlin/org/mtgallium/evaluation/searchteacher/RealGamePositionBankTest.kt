package org.mtgallium.evaluation.searchteacher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticActionIntent
import org.mtgallium.agent.infoset.core.SemanticActionIntentKind
import org.mtgallium.agent.infoset.core.PolicyBehaviorBinding
import org.mtgallium.agent.infoset.core.PolicySourceProvenance
import org.mtgallium.agent.infoset.core.PolicySourceTreeState
import org.mtgallium.agent.searchteacher.SearchTeacherSearchFactory
import org.mtgallium.agent.infoset.core.SemanticChoiceDisplay
import org.mtgallium.agent.infoset.core.SemanticChoiceKind
import org.mtgallium.agent.infoset.core.SemanticOperationFamily

@Tag("public-source")
class RealGamePositionBankTest {
    private val plan = RealGamePositionBankPlan(sources = listOf(RealGamePositionBankSource("/tmp/source", "source-run")),
        rootLimit = 6, maxRootsPerGame = 1, validationFraction = .25, selectionSeed = 17)

    @Test
    fun `development-only selection preserves inventory and original partition assignments`() {
        val development = row("dev", "g1", RealGamePositionDecisionFamily.PRIORITY).copy(partition = RealGamePositionPartition.DEVELOPMENT)
        val validation = row("val", "g2", RealGamePositionDecisionFamily.PRIORITY).copy(partition = RealGamePositionPartition.VALIDATION)
        val selected = selectRealGamePositionAssignments(plan.copy(selectionPartition = RealGamePositionPartition.DEVELOPMENT), listOf(development, validation))
        assertEquals(RealGamePositionAssignmentStatus.SELECTED, selected.first().status)
        assertEquals(listOf("unselected-partition"), selected.last().reasons)
        assertEquals(validation.partition, selected.last().partition)
        assertTrue(!evidenceJson.encodeToString(RealGamePositionBankPlan.serializer(), plan).contains("selectionPartition"))
    }

    @Test
    fun `library seed groups keep all comparisons seats and roots in the same split`() {
        val group = realGamePositionSeedGroup("deck", "pool", 72)
        val partition = realGamePositionPartition(group, .25)
        val assignments = (0..11).map { index -> row(index.toString(), "comparison-${index / 2}-leg-${index % 2}",
            RealGamePositionDecisionFamily.entries[index % RealGamePositionDecisionFamily.entries.size], group = group) }
        assertTrue(assignments.all { it.seedGroupId == group && it.partition == partition })
        assertNotEquals(group, realGamePositionSeedGroup("deck", "pool", 73))
        assertNotEquals(group, realGamePositionSeedGroup("other-deck", "pool", 72))
        assertNotEquals(group, realGamePositionSeedGroup("deck", "other-pool", 72))
        // Policy base seed is deliberately absent from the grouping API and cannot split reused libraries.
        val original = selectRealGamePositionAssignments(plan, assignments)
        val anotherVersion = selectRealGamePositionAssignments(plan.copy(selectionSeed = 91, rootLimit = 4), assignments)
        assertEquals(original.associate { it.rootId to it.partition }, anotherVersion.associate { it.rootId to it.partition })
    }

    @Test
    fun `selection is deterministic balanced capped and excludes ineligible decisions`() {
        val families = listOf(RealGamePositionDecisionFamily.KEEP_OR_MULLIGAN, RealGamePositionDecisionFamily.ATTACKERS,
            RealGamePositionDecisionFamily.PRIORITY)
        val inventory = families.flatMap { family -> (0..9).map { index ->
            row("$family-$index", "$family-game-${index / 2}", family) }
        } + row("invalid", "invalid-game", RealGamePositionDecisionFamily.RESPONSE).copy(reasons = listOf("invalid-source-pair"))
        val selected = selectRealGamePositionAssignments(plan, inventory)
        val picked = selected.filter { it.status == RealGamePositionAssignmentStatus.SELECTED }
        assertEquals(6, picked.size)
        assertEquals(setOf(2), picked.groupingBy { it.decisionFamily }.eachCount().values.toSet())
        assertTrue(picked.groupBy { it.sourceGameId }.values.all { it.size <= plan.maxRootsPerGame })
        assertTrue(picked.none { it.rootId == "invalid" })
        assertEquals(picked.map { it.rootId }.toSet(), selectRealGamePositionAssignments(plan, inventory.reversed())
            .filter { it.status == RealGamePositionAssignmentStatus.SELECTED }.map { it.rootId }.toSet())
        assertEquals(inventory.size, selected.size)
        assertTrue(selected.filter { it.status == RealGamePositionAssignmentStatus.EXCLUDED }.all { it.reasons.isNotEmpty() })
    }

    @Test
    fun `game cap exhausts without fabricated replacements and root limit allows larger banks`() {
        val sameGame = (0..4).map { row("root-$it", "one-game", RealGamePositionDecisionFamily.PRIORITY) }
        val selected = selectRealGamePositionAssignments(plan.copy(rootLimit = 1_000), sameGame)
        assertEquals(1, selected.count { it.status == RealGamePositionAssignmentStatus.SELECTED })
        assertEquals(4, selected.count { it.reasons == listOf("per-game-cap") })
        assertFailsWith<IllegalArgumentException> { selectRealGamePositionAssignments(plan, sameGame + sameGame.first()) }
        assertFailsWith<IllegalArgumentException> { plan.copy(validationFraction = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { plan.copy(validationFraction = 0.0) }
        assertFailsWith<IllegalArgumentException> { plan.copy(validationFraction = 0.20) }
        assertFailsWith<IllegalArgumentException> { realGamePositionPartition("group", 0.30) }
        assertFailsWith<IllegalArgumentException> { plan.copy(maxRootsPerGame = 0) }
    }

    @Test
    fun `decision family follows admitted operation meaning rather than display or chosen action`() {
        fun choice(family: SemanticOperationFamily, label: String) = SemanticChoice.create(
            kind = SemanticChoiceKind.ACTION, operationFamily = family,
            display = SemanticChoiceDisplay(label), canonicalPayload = JsonObject(mapOf("key" to JsonPrimitive(family.name))))
        val keep = choice(SemanticOperationFamily.MULLIGAN, "unrelated label")
        val pass = choice(SemanticOperationFamily.PASS_PRIORITY, "Declare attackers")
        assertEquals(RealGamePositionDecisionFamily.KEEP_OR_MULLIGAN, realGamePositionFamily(listOf(keep, pass)))
        assertEquals(RealGamePositionDecisionFamily.PRIORITY, realGamePositionFamily(listOf(pass)))
        assertEquals(RealGamePositionDecisionFamily.BLOCKERS,
            realGamePositionFamily(listOf(choice(SemanticOperationFamily.DECLARE_BLOCKERS, "Keep hand"))))
        val bottom = SemanticChoice.create(kind = SemanticChoiceKind.DECISION,
            operationFamily = SemanticOperationFamily.MULLIGAN,
            actionIntent = SemanticActionIntent(kind = SemanticActionIntentKind.BOTTOM_CARDS),
            display = SemanticChoiceDisplay("Keep hand"), canonicalPayload = JsonObject(emptyMap()))
        assertEquals(RealGamePositionDecisionFamily.BOTTOM_CARDS, realGamePositionFamily(listOf(bottom)))
        assertEquals(RealGamePositionDecisionFamily.OTHER, realGamePositionFamily(emptyList()))
    }

    @Test
    fun `source admission refuses changed calibration identity and unsupported protocol`() {
        val tree = PolicySourceTreeState("synthetic", "a".repeat(64), "b".repeat(64), "c".repeat(64))
        val source = PolicySourceProvenance(expectedArgentumRevision = "synthetic", outer = tree, argentum = tree)
        val control = SearchTeacherCalibrationPolicy("control", 8, 64, 32, 1.4, false, 1.0)
        val candidate = control.copy(id = "candidate", simulations = 32)
        val calibrationPlan = SearchTeacherCalibrationPlan(phase = SearchTeacherCalibrationPhase.DEVELOPMENT,
            baseSeed = 71, pairOffset = 0, pairCount = 1, control = control, candidates = listOf(candidate))
        val policies = listOf(control, candidate).map { descriptor ->
            val binding = PolicyBehaviorBinding.create("synthetic:${descriptor.id}",
                JsonObject(mapOf("synthetic" to JsonPrimitive(descriptor.id))), source)
            SearchTeacherCalibrationPolicyReport(descriptor, describeTournamentPolicy(descriptor.policy(71)),
                descriptor.parameters(71).searchConfig(), binding,
                SearchTeacherSearchFactory.rootRolloutPolicy().behaviorSpecification,
                SearchTeacherSearchFactory.opponentRolloutPolicy().behaviorSpecification)
        }
        val identity = searchTeacherCalibrationBindings(calibrationPlan, source,
            policies.associate { it.descriptor.id to it.binding.identity }, "deck", "pool", 1).identity
        // Identity-only witness: this object deliberately makes no claim to a completed gameplay population.
        val report = SearchTeacherCalibrationReport(runIdentity = identity, generatedAtUtc = "synthetic",
            sourceProvenance = source, deckHash = "deck", cardPoolHash = "pool", plan = calibrationPlan,
            workerThreads = 1, currentAttemptElapsedMillis = 0.0, policies = policies, comparisons = emptyList(), valid = false)
        requireRealGamePositionBankSourceIdentity(report, identity)
        assertFailsWith<IllegalArgumentException> { requireRealGamePositionBankSourceIdentity(report, "wrong-run") }
        assertFailsWith<IllegalArgumentException> { requireRealGamePositionBankSourceIdentity(report.copy(deckHash = "other"), identity) }
        assertFailsWith<IllegalArgumentException> { requireRealGamePositionBankSourceIdentity(report.copy(workerThreads = 2), identity) }
        assertFailsWith<IllegalArgumentException> { requireRealGamePositionBankSourceIdentity(report.copy(protocol = "legacy"), identity) }
        assertFailsWith<IllegalArgumentException> { requireRealGamePositionBankSourceIdentity(
            report.copy(plan = calibrationPlan.copy(pairOffset = 2)), identity) }
    }

    private fun row(id: String, game: String, family: RealGamePositionDecisionFamily,
        group: String = realGamePositionSeedGroup("deck", "pool", 72)) =
        RealGamePositionBankAssignment(id, "source-run", game, "source-policy", "p0", 0, 0, group,
            realGamePositionPartition(group, plan.validationFraction), family, RealGamePositionAssignmentStatus.EXCLUDED)
}
