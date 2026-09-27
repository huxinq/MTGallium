package org.mtgallium.agent.infoset.planning

import org.mtgallium.agent.infoset.core.*

import kotlin.test.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Observed-action likelihoods see the same admitted, annotated menu as the model in search. */
class ParticleConditioningViewTest {
    @Test fun `conditioning policy receives its declared admission and annotations`() {
        val policy = TagFollowingModel()
        val belief = ParticleBelief.from(ParticleSet(listOf(Weighted<SearchWorld>(World(tagged = observed), 0.5),
            Weighted<SearchWorld>(World(tagged = other), 0.5)),
            BeliefDiagnostics(BeliefMode.POLICY_CONDITIONED_V1, 2, 2, 0, 2.0, 2.0, 0.0, 0)),
            BeliefMode.POLICY_CONDITIONED_V1)
        var masses = emptyList<ParticleAdvanceMass>()
        belief.propagateAndReweight(actor = "p1", observedSignature = observed.signature, conditioningPolicy = policy,
            updateSeed = 17L, preResamplingReadout = { masses = it })

        assertEquals(listOf(MenuRequest(admission = MenuSource.PRODUCTION, annotations = true)).toSet(),
            policy.views.toSet())
        // The tagged world assigns the observed action probability one; the other only the floor.
        val floor = 0.01 / 2
        assertEquals(listOf(0, 1), masses.map { it.inputIndex })
        assertEquals(1.0 / (1.0 + floor), masses[0].posteriorWeight, 1e-12)
        assertEquals(floor / (1.0 + floor), masses[1].posteriorWeight, 1e-12)
    }

    @Test fun `consistency updates keep the plain semantic view`() {
        val views = mutableListOf<MenuRequest>()
        val belief = ParticleBelief.from(ParticleSet(listOf(Weighted<SearchWorld>(World(observed, views), 1.0)),
            BeliefDiagnostics(BeliefMode.CONSISTENCY_ONLY_V1, 1, 1, 0, 1.0, 1.0, 0.0, 0)),
            BeliefMode.CONSISTENCY_ONLY_V1)
        belief.propagateAndReweight(actor = "p1", observedSignature = observed.signature, updateSeed = 17L)
        assertEquals(listOf(MenuRequest()), views)
    }

    /** Point mass on the annotated choice; without annotations it cannot tell the worlds apart. */
    private class TagFollowingModel : ActionDistributionModel {
        override val id = "tag-following-model"
        override val requiresArgentumAiChoiceTag = true
        val views = mutableListOf<MenuRequest>()
        override fun distribution(context: DecisionContext, policySeed: Long): ProbabilityDistribution<SemanticChoice> {
            views += context.view
            val candidates = context.menu.candidates
            val tagged = candidates.singleOrNull { TAG in it.display.policyTags }
                ?: return ProbabilityDistribution.uniform(candidates)
            return ProbabilityDistribution.normalized(candidates.map {
                ProbabilityMass(it, if (it.signature == tagged.signature) 1.0 else 0.0)
            })
        }
    }

    private class World(
        private val tagged: SemanticChoice,
        private val views: MutableList<MenuRequest> = mutableListOf(),
        private var done: Boolean = false,
    ) : SearchWorld {
        override fun actorToAct(): String? = "p1".takeUnless { done }
        override fun decisionContext(view: MenuRequest): DecisionContext {
            views += view
            val candidates = listOf(observed, other).map { choice ->
                if (view.annotations && choice.signature == tagged.signature)
                    choice.copy(display = choice.display.copy(policyTags = choice.display.policyTags + TAG))
                else choice
            }
            return DecisionContext.capture("p1", ActionMenu(candidates, true, 2, "conditioning-view-test-v1"),
                { error("The model reads only the menu") }, view)
        }
        override fun informationState(viewer: String): InformationStateRepresentation = error("Not observed")
        override fun expandChoices(): ActionMenu = error("Use the decision context")
        override fun step(choice: SemanticChoice): SearchStepResult {
            require(!done)
            done = true
            return SearchStepResult(true)
        }
        override fun fork(): SearchWorld = World(tagged, views, done)
        override fun terminalPayoff(rootPlayer: String): Double? = null
    }

    companion object {
        private const val TAG = "conditioning-view-test-tag"
        private fun choice(label: String) = SemanticChoice.create(SemanticChoiceKind.ACTION,
            SemanticOperationFamily.CAST_SPELL, display = SemanticChoiceDisplay(label),
            canonicalPayload = buildJsonObject { put("choice", label) })
        private val observed = choice("observed")
        private val other = choice("other")
    }
}
