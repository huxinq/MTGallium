package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.gym.GameEnvironment
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.core.BoundedPolicyInputCompiler
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.searchteacher.SearchTeacherDeckManifest
import org.mtgallium.research.run.ResearchRunArtifacts
import org.mtgallium.research.run.ResearchRunBindings

@Tag("public-source")
class CloningRootRolloutPolicyTest {
    @Test
    fun `calibration binds a learned root rollout while preserving opponent continuation and defaults`() {
        val model = CandidateConditionedInteractionPolicy.initialize(NeuralBcInteractionModelConfig(), 73L)
        val (directory, identity) = fixture(model.artifact)
        val control = SearchTeacherCalibrationPolicy("control", 8, 64, 32, 1.4, true, 1.0)
        assertFalse("rootCloningFit" in evidenceJson.encodeToString(control))
        val candidate = control.copy(id = "learned-root", rootCloningFit = CloningFitReference(directory.toString(), identity, sha256File(directory.resolve(ResearchRunArtifacts.MANIFEST_FILE))))
        val policy = candidate.policy(1L)
        assertEquals(control.parameters(1L), policy.effectiveParameters(1L))
        assertFalse(policy.effectiveRootRolloutPolicy().requiresPolicyAnnotations)
        assertEquals(control.policy(1L).effectiveOpponentRolloutPolicy().behaviorSpecification,
            policy.effectiveOpponentRolloutPolicy().behaviorSpecification)
        assertFails { candidate.copy(rootCloningFit = candidate.rootCloningFit!!.copy(manifestSha256 = "0".repeat(64))).policy(1L) }
        assertFails { candidate.copy(rootRolloutPolicy = SearchTeacherCalibrationRolloutPolicy.UNIFORM) }
        assertFails { candidate.copy(rolloutHeuristicProbability = .5) }
        assertEquals(candidate, evidenceJson.decodeFromString<SearchTeacherCalibrationPolicy>(evidenceJson.encodeToString(candidate)))
    }

    @Test
    fun `verified model scores the supplied menu with exact encoder parity and accepted engine execution`() {
        val model = CandidateConditionedInteractionPolicy.initialize(NeuralBcInteractionModelConfig(), 73L)
        val (directory, identity) = fixture(model.artifact)
        val policy = CloningRootRolloutPolicy.fromVerifiedFit(directory, identity)
        val world = world()
        val actor = requireNotNull(world.actorToAct())
        val information = world.informationState(actor)
        val originalDigest = information.informationStateDigest
        val candidates = world.expandChoicesForPolicyAdmission().candidates.reversed()
        assertTrue(candidates.size > 1)
        val bounded = BoundedPolicyInputCompiler.compile(information)
        val encoder = NeuralBehavioralCloningFeatureEncoder()
        val expected = model.selectIndex(encoder.encodePolicyMenuForInference(bounded, candidates))
        val distribution = policy.distribution(information, candidates, 1L)
        assertFalse(policy.requiresPolicyAnnotations)
        assertTrue(policy.distributionIsSeedInvariant)
        assertEquals(candidates, distribution.entries.map { it.value })
        assertEquals(candidates[expected], distribution.entries.single { it.probability == 1.0 }.value)
        assertEquals(distribution.entries, policy.distribution(information, candidates, 99L).entries)
        assertEquals(originalDigest, information.informationStateDigest)
        // An admitted sub-menu is distinct from represented proposals, not a rewritten information state.
        val subset = candidates.take(1)
        assertEquals(subset.single(), policy.distribution(information, subset, 1L).entries.single().value)
        assertEquals(originalDigest, information.informationStateDigest)
        assertFails { policy.distribution(information, candidates + candidates.first(), 1L) }
        assertFails { policy.distribution(information, emptyList(), 1L) }
        val step = world.step(candidates[expected])
        assertTrue(step.accepted, step.diagnostic)
        assertEquals(identity, policy.behaviorSpecification.parameters["fitIdentity"])
    }

    @Test
    fun `model identity changes behavior while unsupported or altered artifacts are refused`() {
        val model = CandidateConditionedInteractionPolicy.initialize(NeuralBcInteractionModelConfig(), 7L)
        val first = fixture(model.artifact)
        val second = fixture(model.artifact.copy(trainingSeed = 8L, outputWeights = model.artifact.outputWeights.map { it + .1 }.toDoubleArray()))
        val policy = CloningRootRolloutPolicy.fromVerifiedFit(first.first, first.second)
        assertNotEquals(policy.behaviorSpecification,
            CloningRootRolloutPolicy.fromVerifiedFit(second.first, second.second).behaviorSpecification)
        Files.writeString(first.first.resolve("model.json"), "changed")
        assertFails { CloningRootRolloutPolicy.fromVerifiedFit(first.first, first.second) }
        // Loaded inference is detached from files; it never performs disk I/O per rollout choice.
        val world = world(); val information = world.informationState(requireNotNull(world.actorToAct()))
        assertTrue(policy.distribution(information, information.candidates, 1L).entries.isNotEmpty())
        val malformed = fixture(model.artifact.copy(stateWeights = doubleArrayOf(0.0)))
        assertFails { CloningRootRolloutPolicy.fromVerifiedFit(malformed.first, malformed.second) }
        val obsolete = fixture(model.artifact.copy(config = model.artifact.config.copy(featureSchema = "obsolete")))
        assertFails { CloningRootRolloutPolicy.fromVerifiedFit(obsolete.first, obsolete.second) }
        assertFails { CloningRootRolloutPolicy.fromVerifiedFit(second.first, "wrong-fit") }
    }

    private fun fixture(artifact: NeuralBcInteractionModelArtifact): Pair<Path, String> {
        val directory = Files.createTempDirectory("synthetic-cloning-fit-")
        val bindings = ResearchRunBindings(protocol = "calibration-reference-cloning-fit-v1", material = mapOf(
            "model-config" to sha256(evidenceJson.encodeToString(artifact.config)),
            "synthetic-model" to sha256(evidenceJson.encodeToString(artifact)),
        ))
        Files.writeString(directory.resolve("model.json"), evidenceJson.encodeToString(artifact))
        Files.writeString(directory.resolve("bindings.json"), evidenceJson.encodeToString(bindings))
        Files.writeString(directory.resolve("report.json"), "{\"researchRunIdentity\":\"${bindings.identity}\"}")
        ResearchRunArtifacts(directory, bindings.identity).also { registry ->
            listOf("model.json", "bindings.json", "report.json").forEach { registry.register(it) }
            registry.finalize()
        }
        return directory to bindings.identity
    }

    private fun world(): ArgentumSearchWorld {
        val deck = SearchTeacherDeckManifest("synthetic", "Synthetic", "synthetic", "2026-09-07",
            "public synthetic fixture", mapOf("Mountain" to 60), emptyMap())
        val registry = buildRegistry()
        val environment = GameEnvironment.create(registry).also { env ->
            env.reset(GameConfig(players = listOf(PlayerConfig("p0", deck.deck()), PlayerConfig("p1", deck.deck())),
                skipMulligans = false, useHandSmoother = false, startingPlayerIndex = 0, seed = 17L))
        }
        return ArgentumSearchWorld.create(environment = environment, gameId = "synthetic-cloning", seedBase = 99L,
            effectiveSetupSeed = 17L, expander = UnifiedSemanticExpander(
                actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1),
            knownDecks = mapOf("p0" to deck.mainDeck, "p1" to deck.mainDeck))
    }
}
