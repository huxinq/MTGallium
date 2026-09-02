package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.core.PolicyExpansion
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyInspectionBundle
import org.mtgallium.agent.infoset.core.PolicyInspectionFrame
import org.mtgallium.agent.infoset.core.PolicyInspectionOutcome
import org.mtgallium.agent.infoset.core.PolicyInspectionPresentation
import org.mtgallium.agent.infoset.core.PublicArtifactPrivacy
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.evaluation.searchteacher.evidence.EvidenceStore

internal const val TACTICAL_AUTHORING_DOCUMENT_KIND = "tactical-scenario-authoring-v1"
private const val TACTICAL_AUTHORING_EXPANSION_LIMIT = 2_048

/** A blinded state-and-candidate packet for a human to supply the correct next move. */
@Serializable
data class TacticalAuthoringPacket(
    val schemaVersion: Int = 1,
    val documentKind: String = TACTICAL_AUTHORING_DOCUMENT_KIND,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val deckManifestHash: String,
    val cardPoolHash: String,
    val suiteVersion: String = "legacy-tactical-v1",
    val presentation: PolicyInspectionPresentation,
    val scenarios: List<TacticalAuthoringScenario>,
) {
    init {
        require(schemaVersion == 1)
        require(documentKind == TACTICAL_AUTHORING_DOCUMENT_KIND)
        require(scenarios.isNotEmpty())
        require(scenarios.map(TacticalAuthoringScenario::caseId).distinct().size == scenarios.size)
        require(scenarios.map(TacticalAuthoringScenario::scenarioId).distinct().size == scenarios.size)
    }
}

@Serializable
data class TacticalAuthoringScenario(
    val scenarioId: String,
    val caseId: String,
    val category: TacticalCategory,
    val title: String? = null,
    val horizon: TacticalHorizon? = null,
    val description: String,
    val startingStateRationale: String? = null,
    val mechanicallyVerifiable: Boolean,
    val informationState: PolicyInformationState,
    val candidateExpansion: PolicyExpansion,
) {
    init {
        require(runCatching { UUID.fromString(scenarioId) }.isSuccess)
        require(caseId.isNotBlank())
        if (category == TacticalCategory.FORCED_SURVIVAL || category == TacticalCategory.BLOCK) {
            require(!startingStateRationale.isNullOrBlank()) {
                "Blocking scenarios must explain how their starting state was reached"
            }
        }
        require(informationState.actingPlayerId == informationState.observation.perspectivePlayerId)
        require(!informationState.terminated)
        val fullSignatures = candidateExpansion.candidates.map(SemanticChoice::signature).toSet()
        require(informationState.candidates.all { it.signature in fullSignatures }) {
            "The authoring expansion must contain every policy-state candidate"
        }
        require((informationState.candidates + candidateExpansion.candidates).all {
            it.display.policyTags.isEmpty()
        }) { "Blinded tactical authoring candidates cannot carry policy annotations" }
    }
}

internal class TacticalAuthoringPacketGenerator(
    private val root: Path,
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
) {
    private val evidence = EvidenceStore(root)

    fun generate(caseLimit: Int = TacticalBenchmarkCatalog.cases.size): Pair<TacticalAuthoringPacket, Path> {
        require(caseLimit in 1..TacticalBenchmarkCatalog.cases.size)
        val generatedAt = Instant.now().toString()
        val scenarios = TacticalBenchmarkCatalog.cases.take(caseLimit).map { definition ->
            println("Tactical authoring scenario ${definition.id}")
            val world = TacticalScenarioFactory(registry, manifest).create(definition)
            val actor = requireNotNull(world.actorToAct()) { "Tactical scenario ${definition.id} has no actor" }
            val information = world.informationState(actor).blindPolicyAnnotations()
            val expansion = world.expandChoices(TACTICAL_AUTHORING_EXPANSION_LIMIT).blindPolicyAnnotations()
            TacticalAuthoringScenario(
                scenarioId = scenarioUuid(definition.id),
                caseId = definition.id,
                category = definition.category,
                description = definition.description,
                startingStateRationale = definition.startingStateRationale,
                mechanicallyVerifiable = definition.mechanicallyVerifiable,
                informationState = information,
                candidateExpansion = expansion,
            )
        }
        val resolver = InspectionCardPresentationResolver(
            registry,
            manifest.mainDeck.keys + manifest.sideboard.keys,
        )
        val presentations = scenarios.map { scenario ->
            resolver.safe(scenario.presentationBundle(generatedAt))
        }
        val packet = TacticalAuthoringPacket(
            generatedAtUtc = generatedAt,
            outerCommit = currentOuterCommit(),
            argentumCommit = currentArgentumCommit(),
            deckManifestHash = manifest.deckHash(),
            cardPoolHash = manifest.cardPoolHash(),
            presentation = mergePresentations(presentations),
            scenarios = scenarios,
        )
        val encoded = evidenceJson.encodeToString(packet)
        PublicArtifactPrivacy.requireSafeJson(encoded, "tactical authoring packet")
        val path = evidence.diagnostic(
            "tactical-authoring/tactical-scenarios.authoring.json",
            "the tactical authoring packet",
        )
        writeJsonAtomically(path, packet)
        return packet to path
    }

    fun generateHorizonSuite(caseLimit: Int = TacticalHorizonCatalog.cases.size): Pair<TacticalAuthoringPacket, Path> {
        TacticalHorizonCatalog.validate()
        require(caseLimit in 1..TacticalHorizonCatalog.cases.size)
        val generatedAt = Instant.now().toString()
        val factory = TacticalHorizonScenarioFactory(registry, manifest)
        val scenarios = TacticalHorizonCatalog.cases.take(caseLimit).map { definition ->
            println("Tactical horizon authoring scenario ${definition.id}")
            val world = factory.create(definition)
            val actor = requireNotNull(world.actorToAct()) { "Tactical scenario ${definition.id} has no actor" }
            val information = world.informationState(actor).blindPolicyAnnotations()
            val expansion = world.expandChoices(TACTICAL_AUTHORING_EXPANSION_LIMIT).blindPolicyAnnotations()
            TacticalAuthoringScenario(
                scenarioId = scenarioUuid(definition.id),
                caseId = definition.id,
                category = definition.category,
                title = definition.title,
                horizon = definition.horizon,
                description = definition.description,
                startingStateRationale = definition.startingStateRationale,
                mechanicallyVerifiable = false,
                informationState = information,
                candidateExpansion = expansion,
            )
        }
        val resolver = InspectionCardPresentationResolver(registry, manifest.mainDeck.keys + manifest.sideboard.keys)
        val packet = TacticalAuthoringPacket(
            generatedAtUtc = generatedAt,
            outerCommit = currentOuterCommit(),
            argentumCommit = currentArgentumCommit(),
            deckManifestHash = manifest.deckHash(),
            cardPoolHash = manifest.cardPoolHash(),
            suiteVersion = TACTICAL_HORIZON_SUITE_VERSION,
            presentation = mergePresentations(scenarios.map { resolver.safe(it.presentationBundle(generatedAt)) }),
            scenarios = scenarios,
        )
        val encoded = evidenceJson.encodeToString(packet)
        PublicArtifactPrivacy.requireSafeJson(encoded, "tactical horizon authoring packet")
        val path = evidence.diagnostic(
            "tactical-authoring/$TACTICAL_HORIZON_SUITE_VERSION.authoring.json",
            "the tactical-horizon authoring packet",
        )
        writeJsonAtomically(path, packet)
        return packet to path
    }

    private fun scenarioUuid(caseId: String): String = UUID.nameUUIDFromBytes(
        "mtgallium:$TACTICAL_AUTHORING_DOCUMENT_KIND:${currentOuterCommit()}:$caseId".toByteArray()
    ).toString()

    private fun TacticalAuthoringScenario.presentationBundle(generatedAt: String): PolicyInspectionBundle {
        val information = informationState
        return PolicyInspectionBundle(
            gameId = scenarioId,
            createdAtUtc = generatedAt,
            outerCommit = currentOuterCommit(),
            argentumCommit = currentArgentumCommit(),
            deckManifestHash = manifest.deckHash(),
            cardPoolHash = manifest.cardPoolHash(),
            profileManifestHash = TACTICAL_AUTHORING_DOCUMENT_KIND,
            perspectivePlayerId = information.observation.perspectivePlayerId,
            policyVersion = "human-authoring",
            evaluatorVersion = "not-run",
            beliefVersion = "not-run",
            opponentModelVersion = "not-run",
            ledger = information.history,
            frames = listOf(
                PolicyInspectionFrame(
                    frameIndex = 0,
                    afterDecisionIndex = null,
                    actingPlayerId = information.actingPlayerId,
                    observation = information.observation,
                    knowledge = information.knowledge,
                    candidates = candidateExpansion.candidates,
                    candidateSchemaVersion = information.candidateSchemaVersion,
                    historyLength = information.history.size,
                    historyCommitment = information.historyCommitment,
                    informationStateDigest = information.informationStateDigest,
                    terminated = false,
                    winnerId = null,
                )
            ),
            outcome = PolicyInspectionOutcome(
                decisions = 0,
                terminated = false,
                truncated = true,
                winnerId = null,
                resultByPlayer = emptyMap(),
            ),
        )
    }
}

private fun PolicyInformationState.blindPolicyAnnotations(): PolicyInformationState = copy(
    candidates = candidates.map(SemanticChoice::blindPolicyAnnotations),
)

private fun PolicyExpansion.blindPolicyAnnotations(): PolicyExpansion = copy(
    candidates = candidates.map(SemanticChoice::blindPolicyAnnotations),
)

private fun SemanticChoice.blindPolicyAnnotations(): SemanticChoice = copy(
    display = display.copy(policyTags = emptySet()),
)

private fun mergePresentations(
    presentations: List<PolicyInspectionPresentation>,
): PolicyInspectionPresentation {
    val images = presentations.flatMap(PolicyInspectionPresentation::cardImages)
        .associateBy { it.key }
        .values
        .sortedBy { it.key }
    val resolvedNames = images.map { it.cardName }.toSet()
    val unresolved = presentations.flatMap(PolicyInspectionPresentation::unresolvedCardNames)
        .filterNot(resolvedNames::contains)
        .distinct()
        .sorted()
    return PolicyInspectionPresentation(images, unresolved)
}
