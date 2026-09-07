package org.mtgallium.evaluation.searchteacher

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.RootSelectionPolicy
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.research.run.*

internal data class LoadedRootKernelModel(val model: RootActionKernelModel, val configurationId: String)

@Serializable
internal data class RootKernelFitReference(val directory: String, val researchRunIdentity: String, val manifestSha256: String) {
    init {
        require(Path.of(directory).isAbsolute)
        require(researchRunIdentity.startsWith("research-run-v1-sha256:"))
        require(manifestSha256.matches(Regex("[0-9a-f]{64}")))
    }

    fun load(): RootKernelSelectionPolicy = loadFrozenModel().let { RootKernelSelectionPolicy(it.model, it.configurationId) }

    fun loadFrozenModel(): LoadedRootKernelModel {
        val path = Path.of(directory)
        require(researchSha256File(path.resolve(ResearchRunArtifacts.MANIFEST_FILE)) == manifestSha256)
        val manifest = ResearchRunArtifacts.loadAndVerify(path, researchRunIdentity)
        val entries = manifest.artifacts.associateBy { it.relativePath }
        fun input(name: String): String {
            val entry = requireNotNull(entries[name]) { "Unregistered kernel input: $name" }
            val file = ResearchRunFiles.resolveBelow(path, name)
            require(researchSha256File(file) == entry.sha256)
            return Files.readString(file)
        }
        val bindings = evidenceJson.decodeFromString<ResearchRunBindings>(input("bindings.json"))
        require(bindings.identity == researchRunIdentity)
        val model = evidenceJson.decodeFromString<RootActionKernelModel>(input("model.json"))
        when (bindings.protocol) {
            "root-action-kernel-fit-v1" -> {
                val report = evidenceJson.decodeFromString<RootActionKernelReport>(input("report.json"))
                require(report.researchRunIdentity == researchRunIdentity)
                report.source.requireReady()
                require(!report.source.outerDirty && !report.source.engineDirty)
                require(bindings.material["source"] == sha256(evidenceJson.encodeToString(ResearchRunProvenance.serializer(), report.source)))
                require(bindings.material["plan"] == sha256(evidenceJson.encodeToString(RootActionKernelPlan.serializer(), report.plan)))
                require(model.ridge == report.plan.ridge && model.centers.size == report.development.actions)
            }
            "terminal-root-action-kernel-fit-v1" -> {
                val report = evidenceJson.decodeFromString<TerminalRootKernelFitReport>(input("report.json"))
                require(report.researchRunIdentity == researchRunIdentity)
                report.source.requireReady()
                require(!report.source.outerDirty && !report.source.engineDirty)
                require(bindings.material["source"] == sha256(evidenceJson.encodeToString(ResearchRunProvenance.serializer(), report.source)))
                require(bindings.material["plan"] == sha256(evidenceJson.encodeToString(TerminalRootKernelFitPlan.serializer(), report.plan)))
                require(bindings.material["target"] == "conditional-terminal-payoff-equal-repetition-mean-root-centered-v1")
                require(model.ridge == report.plan.ridge && model.centers.size == report.development.actions)
                require(report.accounting.refusedRows == 0 && report.accounting.completedTerminalSamples == report.accounting.requestedContinuations)
            }
            else -> error("Unsupported root kernel fit protocol: ${bindings.protocol}")
        }
        require(bindings.material["feature-schema"] == NEURAL_BC_FEATURE_SCHEMA)
        require(bindings.material["kernel"] == "l2-state-l2-candidate-root-centered-candidate-plus-state-tensor-candidate-v1")
        return LoadedRootKernelModel(model, "root-kernel-clipped-score-v1:$COMPILED_ROOT_ACTION_KERNEL_ID:$researchRunIdentity:$manifestSha256:${entries.getValue("model.json").sha256}")
    }
}

/** The frozen model sees only actual acting-player information and the caller's admitted menu. */
internal class RootKernelSelectionPolicy(
    model: RootActionKernelModel,
    override val configurationId: String,
) : RootSelectionPolicy {
    private val scorer = CompiledRootActionKernel(model)

    override fun scores(information: PolicyInformationState, candidates: List<SemanticChoice>): Map<String, Double> {
        require(!information.terminated && information.actingPlayerId != null)
        require(information.actingPlayerId == information.observation.perspectivePlayerId)
        require(candidates.isNotEmpty() && candidates.map { it.signature }.distinct().size == candidates.size)
        val scores = scorer.scores(rootActionKernelFeatures(information, candidates))
        require(scores.all(Double::isFinite))
        // Fixed saturation bounds the selection bonus; these are preferences, never payoff labels.
        return candidates.indices.associate { candidates[it].signature to scores[it].coerceIn(-1.0, 1.0) }
    }
}
