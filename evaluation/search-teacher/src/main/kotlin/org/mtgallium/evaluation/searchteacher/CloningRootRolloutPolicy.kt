package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mtgallium.agent.infoset.core.OpponentPolicy
import org.mtgallium.agent.infoset.core.OpponentPolicyBehaviorSpecification
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.ProbabilityDistribution
import org.mtgallium.agent.infoset.core.ProbabilityMass
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.research.run.ResearchRunArtifacts
import org.mtgallium.research.run.ResearchRunBindings
import org.mtgallium.research.run.ResearchRunFiles
import org.mtgallium.research.run.researchSha256File

@Serializable
internal data class CloningFitReference(
    val directory: String,
    val researchRunIdentity: String,
    val manifestSha256: String,
) {
    init {
        require(Path.of(directory).isAbsolute)
        require(researchRunIdentity.startsWith("research-run-v1-sha256:"))
        require(manifestSha256.matches(Regex("[0-9a-f]{64}")))
    }

    fun load(): CloningRootRolloutPolicy {
        val path = Path.of(directory)
        require(researchSha256File(path.resolve(ResearchRunArtifacts.MANIFEST_FILE)) == manifestSha256) {
            "The fit manifest differs from the declared gameplay/preflight input"
        }
        return CloningRootRolloutPolicy.fromVerifiedFit(path, researchRunIdentity)
    }
}

/** Evaluation-owned experimental continuation policy; use only as ArenaPolicySpec.rootRolloutPolicy. */
internal class CloningRootRolloutPolicy private constructor(
    private val model: CandidateConditionedInteractionPolicy,
    fitIdentity: String,
    modelFileSha256: String,
) : OpponentPolicy {
    override val id = "cloning-interaction-greedy-v1"
    override val requiresPolicyAnnotations = false
    override val distributionIsSeedInvariant = true
    private val encoder = NeuralBehavioralCloningFeatureEncoder(
        model.artifact.config.stateDimension, model.artifact.config.candidateDimension)
    override val behaviorSpecification = OpponentPolicyBehaviorSpecification(
        implementationId = "calibration-cloning-root-rollout-v1", declaredId = id,
        distributionIsSeedInvariant = true, parameters = mapOf(
            "fitIdentity" to fitIdentity, "modelFileSha256" to modelFileSha256,
            "featureSchema" to model.artifact.config.featureSchema,
            "selection" to "argmax-first-menu-order-v1",
            "candidateSource" to "caller-admitted-policy-menu-v1",
        ))

    override fun distribution(
        opponentInformation: PolicyInformationState,
        candidates: List<SemanticChoice>,
        policySeed: Long,
    ): ProbabilityDistribution<SemanticChoice> {
        require(!opponentInformation.terminated && opponentInformation.actingPlayerId != null)
        require(opponentInformation.actingPlayerId == opponentInformation.observation.perspectivePlayerId)
        // The adapter's admitted menu can contain a combat anchor absent from represented proposals.
        // Score that supplied menu without rewriting the represented state's candidate commitment.
        val encoded = encoder.encodeLivePolicyMenuForInference(opponentInformation, candidates)
        val scores = model.scores(encoded)
        require(scores.size == candidates.size && scores.all(Double::isFinite))
        val selected = scores.indices.maxBy { scores[it] }
        return ProbabilityDistribution.normalized(candidates.mapIndexed { index, choice ->
            ProbabilityMass(choice, if (index == selected) 1.0 else 0.0)
        })
    }

    companion object {
        /** Verify and load once; inference never reopens evidence or consults full engine state. */
        fun fromVerifiedFit(directory: Path, expectedFitIdentity: String): CloningRootRolloutPolicy {
            val manifest = ResearchRunArtifacts.loadAndVerify(directory, expectedFitIdentity)
            val registered = manifest.artifacts.associateBy { it.relativePath }
            fun input(name: String): Path {
                val entry = requireNotNull(registered[name]) { "Fit input is not registered: $name" }
                return ResearchRunFiles.resolveBelow(directory, name).also {
                    require(researchSha256File(it) == entry.sha256) { "Fit artifact changed after verification: $name" }
                }
            }
            val bindings = evidenceJson.decodeFromString<ResearchRunBindings>(Files.readString(input("bindings.json")))
            require(bindings.identity == expectedFitIdentity && bindings.protocol in setOf("calibration-reference-cloning-fit-v1", CLONING_CORPUS_COMPARISON_PROTOCOL))
            val report = evidenceJson.parseToJsonElement(Files.readString(input("report.json"))).jsonObject
            require(report.getValue("researchRunIdentity").jsonPrimitive.content == expectedFitIdentity)
            val path = input("model.json")
            val artifact = evidenceJson.decodeFromString<NeuralBcInteractionModelArtifact>(Files.readString(path))
            require(artifact.protocol == "candidate-conditioned-interaction-mlp-v1" && artifact.schemaVersion == NEURAL_BC_INTERACTION_MODEL_SCHEMA)
            val config = artifact.config
            require(config.featureSchema == NEURAL_BC_FEATURE_SCHEMA)
            require(bindings.material["model-config"] == sha256(evidenceJson.encodeToString(config)))
            fun weights(values: DoubleArray, size: Int) { require(values.size == size && values.all(Double::isFinite)) }
            weights(artifact.stateWeights, Math.multiplyExact(config.stateDimension, config.projectionDimension))
            weights(artifact.stateBias, config.projectionDimension)
            weights(artifact.candidateWeights, Math.multiplyExact(config.candidateDimension, config.projectionDimension))
            weights(artifact.candidateBias, config.projectionDimension)
            weights(artifact.interactionWeights, Math.multiplyExact(config.interactionDimension, Math.multiplyExact(3, config.projectionDimension)))
            weights(artifact.interactionBias, config.interactionDimension)
            weights(artifact.outputWeights, config.interactionDimension)
            weights(artifact.outputBias, 1)
            return CloningRootRolloutPolicy(CandidateConditionedInteractionPolicy.fromArtifact(artifact),
                expectedFitIdentity, registered.getValue("model.json").sha256)
        }
    }
}
