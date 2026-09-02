package org.mtgallium.agent.searchteacher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.mtgallium.agent.infoset.core.BeliefArchitecture
import org.mtgallium.agent.infoset.core.BeliefMode
import org.mtgallium.agent.infoset.core.PolicyExpansion
import org.mtgallium.agent.infoset.core.PolicyExpansionOmissionReason
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticChoiceDisplay
import org.mtgallium.agent.infoset.core.SemanticChoiceKind
import org.mtgallium.agent.infoset.core.SemanticOperationFamily

class SearchTeacherPolicyTest {
    @Test
    fun `runtime config maps every behavior-affecting field into policy parameters`() {
        val config = SearchTeacherRuntimeConfig(
            particles = 16,
            simulations = 256,
            maxPolicyDecisions = 47,
            explorationConstant = 0.75,
            beliefMode = BeliefMode.POLICY_CONDITIONED_V1,
            beliefArchitecture = BeliefArchitecture.HYBRID_C_V1,
            baseSeed = 91L,
            policyCompression = PolicyCompressionConfig(enabled = false),
            searchReuse = SearchReuseConfig(enabled = false, minimumFreshSimulations = 8),
            initialExpansionLimit = 32,
            wideningThresholds = listOf(40, 80),
            wideningLimits = listOf(64, 128),
            maxQuiescenceDecisions = 11,
            maxQuiescenceForcedPasses = 79,
            cacheSimulationTransitions = false,
            wallClockBudgetMillis = 500,
            minimumSimulations = 7,
        )

        val parameters = config.policyParameters()

        assertEquals(config.particles, parameters.particles)
        assertEquals(config.simulations, parameters.simulations)
        assertEquals(config.maxPolicyDecisions, parameters.maxPolicyDecisions)
        assertEquals(config.explorationConstant, parameters.explorationConstant)
        assertEquals(config.leaf, parameters.leaf)
        assertEquals(config.actionSpaceProfile, parameters.actionSpaceProfile)
        assertEquals(config.beliefMode, parameters.beliefMode)
        assertEquals(config.beliefArchitecture, parameters.beliefArchitecture)
        assertEquals(config.baseSeed, parameters.baseSeed)
        assertEquals(config.profileId, parameters.profileId)
        assertEquals(config.policyCompression, parameters.policyCompression)
        assertEquals(config.searchReuse, parameters.searchReuse)
        assertEquals(config.initialExpansionLimit, parameters.initialExpansionLimit)
        assertEquals(config.wideningThresholds, parameters.wideningThresholds)
        assertEquals(config.wideningLimits, parameters.wideningLimits)
        assertEquals(config.maxQuiescenceDecisions, parameters.maxQuiescenceDecisions)
        assertEquals(config.maxQuiescenceForcedPasses, parameters.maxQuiescenceForcedPasses)
        assertEquals(config.cacheSimulationTransitions, parameters.cacheSimulationTransitions)
        assertEquals(config.wallClockBudgetMillis, parameters.wallClockBudgetMillis)
        assertEquals(config.minimumSimulations, parameters.minimumSimulations)
        assertEquals(config.initialExpansionLimit, parameters.searchConfig().initialExpansionLimit)
        assertEquals(config.wideningThresholds, parameters.searchConfig().wideningThresholds)
        assertEquals(config.wideningLimits, parameters.searchConfig().wideningLimits)
    }

    @Test
    fun `canonical deck manifest is packaged once for production consumers`() {
        if (isPublicSource()) {
            assertFailsWith<IllegalArgumentException> { SearchTeacherDeckManifest.frozenMonoRed() }
            return
        }
        val manifest = SearchTeacherDeckManifest.frozenMonoRed()
        assertEquals("mono-red-standard-2026-07-30", manifest.id)
        assertEquals(60, manifest.mainDeck.values.sum())
    }

    @Test
    fun `policy parameters reject invalid search budgets`() {
        assertFailsWith<IllegalArgumentException> {
            SearchTeacherRuntimeConfig().policyParameters().copy(simulations = 0)
        }
    }

    @Test
    fun `legacy frozen profile remains parseable but cannot masquerade as current runtime`() {
        if (isPublicSource()) {
            assertFailsWith<IllegalStateException> { SearchTeacherPilotSpecification.frozenMonoRed() }
            return
        }
        val specification = SearchTeacherPilotSpecification.frozenMonoRed()
        val config = SearchTeacherRuntimeConfig()
        val parameters = config.policyParameters()

        assertTrue(specification.policyCompression.enabled)
        assertFailsWith<IllegalArgumentException> { specification.runtimeConfig() }
        assertEquals(SEARCH_TEACHER_UNPROFILED_RUNTIME_ID, config.profileId)
        assertTrue(!parameters.policyCompression.enabled)
        assertTrue(!parameters.searchReuse.enabled)
    }

    @Test
    fun `production configuration refuses retained-path reuse until probability weighting is repaired`() {
        assertFailsWith<IllegalArgumentException> {
            SearchReuseConfig(enabled = true)
        }
    }

    @Test
    fun `production configuration refuses profile-only singleton automation`() {
        assertFailsWith<IllegalArgumentException> {
            SearchTeacherRuntimeConfig(policyCompression = PolicyCompressionConfig(enabled = true))
        }
    }

    @Test
    fun `profile singleton pass never bypasses the responsible policy`() {
        val pass = SemanticChoice.create(
            kind = SemanticChoiceKind.ACTION,
            operationFamily = SemanticOperationFamily.PASS_PRIORITY,
            display = SemanticChoiceDisplay("Pass priority"),
            canonicalPayload = buildJsonObject { put("action", JsonPrimitive("pass")) },
        )
        val profiled = PolicyExpansion(
            candidates = listOf(pass),
            isExhaustive = false,
            estimatedCandidateCount = null,
            proposalVersion = "profile-singleton-test-v1",
            isProfileExhaustive = true,
            omissionReasons = setOf(
                PolicyExpansionOmissionReason.PROFILE_SUPPRESSED_STANDALONE_MANA
            ),
        )

        assertNull(SearchTeacherAutomaticSelection.classify(profiled))

        val rulesForced = SearchTeacherAutomaticSelection.classify(
            profiled.copy(
                isExhaustive = true,
                estimatedCandidateCount = 1,
                isProfileExhaustive = true,
                omissionReasons = emptySet(),
            ),
        )
        assertEquals(SearchTeacherSelectionKind.RULES_FORCED_PASS, rulesForced?.kind)
    }

    @Test
    fun `a land-available pass remains a searched strategic choice`() {
        val pass = choice("Pass priority", SemanticOperationFamily.PASS_PRIORITY)
        val land = choice("Play Mountain", SemanticOperationFamily.PLAY_LAND)
        val expansion = PolicyExpansion(
            candidates = listOf(pass, land),
            isExhaustive = true,
            estimatedCandidateCount = 2,
            proposalVersion = "land-hold-test-v1",
        )

        assertNull(
            SearchTeacherAutomaticSelection.classify(expansion)
        )
    }

    private fun choice(label: String, family: SemanticOperationFamily): SemanticChoice = SemanticChoice.create(
        kind = SemanticChoiceKind.ACTION,
        operationFamily = family,
        display = SemanticChoiceDisplay(label),
        canonicalPayload = buildJsonObject { put("action", JsonPrimitive(label)) },
    )

    private fun isPublicSource(): Boolean = System.getenv("MTGALLIUM_PUBLIC_SOURCE") == "1"
}
