package org.mtgallium.agent.infoset.argentum

import com.wingedsheep.engine.core.ConditionalSelectionMinimum
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.SearchCardInfo
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.json.*
import org.mtgallium.agent.infoset.core.PolicyDecisionChoiceSpec
import kotlin.test.*

/** Projection-component fixtures; retained gameplay separately verifies observation assimilation. */
class UnorderedCardOptionsTest {
    private fun references(): Pair<SafeReferenceMap, List<EntityId>> {
        val registry = CardRegistry().apply { register(PortalSet.cards); register(PortalSet.basicLands) }
        val env = GameEnvironment.create(registry)
        val deck = Deck.of("Raging Goblin" to 60)
        env.reset(GameConfig(players = listOf(PlayerConfig("Alice", deck), PlayerConfig("Bob", deck)),
            seed = 818L, startingHandSize = 8, startingPlayerIndex = 0,
            skipMulligans = true, useHandSmoother = false))
        val viewer = env.playerIds.first()
        val observation = ObservationBuilder(registry).build(env.state, viewer, emptyList()).observation as TrainingObservation
        val refs = SafeReferenceMap(observation)
        val ids = env.state.getHand(viewer).take(3)
        assertEquals(3, ids.size)
        assertEquals(3, ids.map(refs::reference).distinct().size)
        return refs to ids
    }
    private fun project(spec: CardsChoiceSpec, refs: SafeReferenceMap): PolicyDecisionChoiceSpec.Cards =
        assertIs<PolicyDecisionChoiceSpec.Cards>(SafeObservationProjector().projectChoice(spec, refs))
    private fun options(spec: PolicyDecisionChoiceSpec.Cards) =
        spec.constraints.getValue("options").jsonArray.map { it.jsonPrimitive.content }

    @Test fun `unordered options have one canonical representation without input mutation`() {
        val (refs, ids) = references()
        val supplied = ids.sortedByDescending(refs::reference)
        val spec = CardsChoiceSpec(supplied, 1, 1, ordered = false)
        val first = project(spec, refs)
        val reversed = project(spec.copy(options = supplied.reversed()), refs)
        assertEquals(first, reversed)
        assertEquals(ids.map(refs::reference).sorted(), first.options)
        assertEquals(first.options, options(first))
        assertEquals(supplied, spec.options)
        assertEquals(3, first.options.distinct().size, "Same-named cards remain separate options")
    }

    @Test fun `ordered card selectors and explicit ordering decisions retain their order`() {
        val (refs, ids) = references()
        val spec = CardsChoiceSpec(ids, 1, 3, ordered = true)
        val a = project(spec, refs)
        val b = project(spec.copy(options = ids.reversed()), refs)
        assertEquals(ids.map(refs::reference), a.options)
        assertEquals(a.options, options(a))
        assertNotEquals(a, b)
        val projector = SafeObservationProjector()
        assertNotEquals(projector.projectChoice(OrderChoiceSpec(ids), refs),
            projector.projectChoice(OrderChoiceSpec(ids.reversed()), refs))
    }

    @Test fun `keyed metadata remains attached to the right option and other constraints are preserved`() {
        val (refs, ids) = references()
        val metadata = ids.mapIndexed { i, id -> id to SearchCardInfo("label-$i", "{$i}", "Creature") }.toMap()
        val spec = CardsChoiceSpec(ids, 1, 2, ordered = false, cardInfo = metadata,
            selectedLabel = "e123->e456", remainderLabel = "Keep", nonSelectableOptions = listOf(ids.last()),
            onePerPower = true, maxTotalManaValue = 7,
            conditionalMinimums = listOf(ConditionalSelectionMinimum(1, 1, listOf(ids.first()), description = "first group")))
        val a = project(spec, refs)
        val b = project(spec.copy(options = ids.reversed(), cardInfo = metadata.entries.reversed().associate { it.toPair() }), refs)
        assertEquals(a, b)
        val projectedMetadata = requireNotNull(a.cardMetadata)
        for ((i, id) in ids.withIndex()) {
            assertTrue(projectedMetadata.getValue(refs.reference(id)).toString().contains("label-$i"))
        }
        assertEquals(a.constraints.getValue("cardInfo"), projectedMetadata)
        assertEquals("e123->e456", a.constraints.getValue("selectedLabel").jsonPrimitive.content)
        assertEquals(7, a.constraints.getValue("maxTotalManaValue").jsonPrimitive.int)
        assertTrue(a.constraints.getValue("onePerPower").jsonPrimitive.boolean)
        assertEquals(listOf(refs.reference(ids.last())), a.constraints.getValue("nonSelectableOptions").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(refs.reference(ids.first())), a.constraints.getValue("conditionalMinimums").jsonArray.single().jsonObject.getValue("matchingOptions").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun `membership multiplicity and selection constraints do not collapse`() {
        val (refs, ids) = references()
        val spec = CardsChoiceSpec(ids.take(2), 1, 1, ordered = false)
        val a = project(spec, refs)
        assertNotEquals(a, project(spec.copy(options = listOf(ids[0], ids[2])), refs))
        val duplicate = project(spec.copy(options = listOf(ids[0], ids[1], ids[1])), refs)
        assertNotEquals(a, duplicate)
        assertEquals(2, duplicate.options.count { it == refs.reference(ids[1]) })
        assertNotEquals(a, project(spec.copy(minSelections = 0), refs))
        assertNotEquals(a, project(spec.copy(maxSelections = 2), refs))
        assertNotEquals(a, project(spec.copy(ordered = true), refs))
    }
}
