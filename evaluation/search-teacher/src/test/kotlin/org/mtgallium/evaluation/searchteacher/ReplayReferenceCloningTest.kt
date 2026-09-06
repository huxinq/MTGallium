package org.mtgallium.evaluation.searchteacher

import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.infoset.core.*
import org.mtgallium.agent.searchteacher.SearchTeacherDeckManifest
import org.mtgallium.evaluation.searchteacher.replay.*

@Tag("public-source")
class ReplayReferenceCloningTest {
    private val deck = SearchTeacherDeckManifest("synthetic", "Synthetic", "synthetic", "2026-09-07",
        "public replay projection fixture", mapOf("Mountain" to 40, "Burst Lightning" to 20), emptyMap())
    private fun world() = createSemanticReplayWorld(buildRegistry(), deck, "derived-fixture", 17, 99, 0,
        SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1)

    @Test
    fun `one replay traversal visits p1 with its exact preceding information and still checks later transitions`() {
        val original = world()
        val initial = original.authoritativeStateForHost()
        val recorder = CanonicalReplayRecorder("derived-fixture", "synthetic", "synthetic", "fixture",
            listOf("p0", "p1"), initial)
        val states = mutableListOf(initial)
        val expected = mutableListOf<PolicyInformationState>()
        val decisions = (0..1).map { index ->
            val actor = requireNotNull(original.actorToAct())
            expected += original.informationState(actor)
            val keep = original.expandChoices().candidates.single { it.actionIntent?.kind == SemanticActionIntentKind.KEEP_HAND }
            val applied = original.stepWithReplayTrace(keep)
            val transitions = applied.rawTransitions.map { raw ->
                states += raw.afterState
                recorder.appendAction(ReplayTransitionOrigin.POLICY, raw.action, raw.accepted, raw.afterState, raw.events)
            }
            CanonicalSemanticDecision(index, keep, transitions)
        }
        assertEquals(listOf("p0", "p1"), expected.map { it.actingPlayerId })
        val terminal = recorder.finish(ReplayCompletionStatus.INCOMPLETE, states.last(),
            incompleteReason = ReplayIncompleteReason.INTERRUPTED)
        // This fixture intentionally tests only verified-prefix traversal, not complete-game admission.
        val replay = VerifiedCanonicalSemanticReplay(recorder.header, terminal, states, decisions)
        val projected = world()
        val visited = mutableListOf<PolicyInformationState>()
        replayFixedRootPrefix(2, replay, projected, null) { index ->
            assertEquals(visited.size, index)
            visited += projected.informationState(requireNotNull(projected.actorToAct()))
        }
        assertEquals(expected, visited)
        assertEquals(original.authoritativeStateForHost(), projected.authoritativeStateForHost())
        assertFailsWith<IllegalArgumentException> { replayFixedRootPrefix(3, replay, world(), null) }
        val changed = decisions[1].transitions.first().copy(accepted = false, rejectionReason = "tampered fixture")
        val tampered = replay.copy(decisions = listOf(decisions[0], decisions[1].copy(
            transitions = listOf(changed) + decisions[1].transitions.drop(1))))
        assertFails { replayFixedRootPrefix(2, tampered, world(), null) {} }
    }

    @Test
    fun `derived p1 labels must be historical winners accepted exactly in the current safe menu`() {
        val actual = world()
        actual.stepWithReplayTrace(actual.expandChoices().candidates.single {
            it.actionIntent?.kind == SemanticActionIntentKind.KEEP_HAND })
        val info = actual.informationState("p1")
        val expansion = actual.expandChoices()
        val chosen = expansion.candidates.first()
        val stats = expansion.candidates.map { SearchCandidateStatistics(it, if (it == chosen) 8 else 0,
            0.0, if (it == chosen) 1.0 else 0.0) }
        val diagnostic = ArenaSearchDecisionDiagnostic(1, info.observation.turnNumber, info.observation.phase,
            info.observation.step, 0.0, InformationSetSearchDiagnostics(8, 2, 1, 1, 1, 0, 0,
                "synthetic", SearchTeacherCalibrationPolicy("synthetic", 2, 8, 4, 1.4, false, 1.0).parameters(99).leaf), chosen = chosen, candidateStatistics = stats)
        fun derive(d: ArenaSearchDecisionDiagnostic = diagnostic, actor: String = "p1",
            e: PolicyExpansion = expansion, accepted: SemanticChoice = chosen) =
            replayReferenceCloningExample("g", "same-pair", actor, info, e, d, accepted)
        val row = assertNotNull(derive())
        assertEquals(BoundedPolicyInputCompiler.compile(info), row.policyInput)
        assertEquals(chosen, row.teacherAction)
        val encoder = NeuralBehavioralCloningFeatureEncoder()
        val encoded = encoder.encode(row.policyInput, row.policyInput.candidates.indexOf(row.teacherAction), "g", 1)
        requireReplayCloningEncodingParity(encoded, encoder.encode(row.policyInput, encoded.labelIndex, "g", 1))
        assertFails { requireReplayCloningEncodingParity(encoded, encoded.copy(labelIndex = 1 - encoded.labelIndex)) }
        assertFails { requireReplayCloningEncodingParity(encoded, encoded.copy(state = encoded.state.copy(
            values = encoded.state.values.map { it + 1.0 }.toDoubleArray()))) }

        assertEquals("p1", row.policyInput.observation.perspectivePlayerId)
        assertFails { derive(actor = "p0") }
        assertFails { derive(accepted = expansion.candidates.last()) }
        assertFails { derive(d = diagnostic.copy(chosen = expansion.candidates.last())) }
        assertFails { derive(d = diagnostic.copy(candidateStatistics = stats.map { it.copy(visits = 0) })) }
        assertFails { derive(d = diagnostic.copy(candidateStatistics = stats.filter { it.choice == chosen })) }
        assertNull(derive(e = expansion.copy(isExhaustive = false, isProfileExhaustive = false,
            omissionReasons = expansion.omissionReasons + PolicyExpansionOmissionReason.RESPONSE_LIMIT)))
    }
}
