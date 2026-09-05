package org.mtgallium.evaluation.searchteacher

import org.mtgallium.agent.infoset.argentum.ArgentumRawTransition
import com.wingedsheep.engine.core.AbilityTriggeredEvent
import com.wingedsheep.engine.core.DecisionRequestedEvent
import com.wingedsheep.engine.core.DecisionSubmittedEvent
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.event.DelayedTriggeredAbility
import com.wingedsheep.engine.registry.CardRegistry
import org.mtgallium.evaluation.searchteacher.replay.CanonicalReplayHeader
import org.mtgallium.evaluation.searchteacher.replay.CanonicalReplayJson
import org.mtgallium.evaluation.searchteacher.replay.CanonicalReplayTerminal
import org.mtgallium.evaluation.searchteacher.replay.CanonicalReplayTransition
import org.mtgallium.evaluation.searchteacher.replay.ReplayCanonicalJson
import org.mtgallium.evaluation.searchteacher.replay.ReplayCompletionStatus
import org.mtgallium.evaluation.searchteacher.replay.ReplayTransitionOrigin
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.battlefield.LastKnownPermanentComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.stack.EntitySnapshot
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.AbilityIdentity
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.TriggeredAbility
import com.wingedsheep.sdk.scripting.references.Player
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.ArgentumStateFingerprint
import org.mtgallium.agent.infoset.core.INSPECTION_SCHEMA_CURRENT
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyInspectionBundle
import org.mtgallium.agent.infoset.core.PolicyInspectionOutcome
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PublicArtifactPrivacy
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.SearchStepResult
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.evaluation.searchteacher.evidence.RunProvenance
import org.mtgallium.research.run.ResearchRunArtifacts
import org.mtgallium.research.run.ResearchRunBindings
import org.mtgallium.research.run.ResearchRunCheckpoints
import org.mtgallium.research.run.ResearchRunFiles
import org.mtgallium.research.run.ResearchSourceProvenance

internal const val OUTCOME_STATE_CORPUS_PROTOCOL =
    "search-budget-frontier-outcome-state-corpus-v4"
internal const val OUTCOME_STATE_CORPUS_PARENT_IDENTITY =
    "research-run-v1-sha256:0a7d3f14a1244ead3548abeaa5b7ab2b9ce67aef44c27c5a64416a5dac92f981"
internal const val OUTCOME_STATE_CORPUS_HISTORICAL_OUTER_COMMIT =
    "1f9d1bfafcd361ecd6e3e44099dca34cf2e64131"
internal const val OUTCOME_STATE_CORPUS_ARGENTUM_COMMIT =
    "3eda577fdd10d08e0e62d66b4727ab53f1b41ff5"
internal const val OUTCOME_STATE_CORPUS_INFOSET_CORE_TREE =
    "bf19301b499d63b03367e983726b44f0e6c18706"
internal const val OUTCOME_STATE_CORPUS_INFOSET_ARGENTUM_TREE =
    "e2b4dd88ace8e0167f27dd93fae3658156cd50f2"
internal const val OUTCOME_STATE_CORPUS_CONTROL_POLICY_EVIDENCE_ID =
    "policy-evidence-v1-sha256:0ba8a48efb9b59709997d78b8f132b79ea3f3b690a03bc312732a13daebd5ccf"
internal const val OUTCOME_STATE_CORPUS_TREATMENT_POLICY_EVIDENCE_ID =
    "policy-evidence-v1-sha256:e3cd2da48d18a0b15c1828fa7cda92cbcd600409de381192264ff151671d3960"
internal const val OUTCOME_STATE_CORPUS_SPLIT_DOMAIN =
    "mtgallium-outcome-state-pair-split-v1"
internal const val OUTCOME_STATE_CORPUS_STATE_EQUIVALENCE =
    "argentum-routing-normalized-full-state-json-tree-v1"
internal const val OUTCOME_STATE_CORPUS_ACTION_EQUIVALENCE =
    "argentum-submit-decision-routing-normalized-action-v1"
internal const val OUTCOME_STATE_CORPUS_EVENT_EQUIVALENCE =
    "argentum-boundary-correlated-decision-routing-and-fixed-3eda-time-lord-type-line-events-v2"
internal const val OUTCOME_STATE_CORPUS_TRANSITION_STATE_EQUIVALENCE =
    "argentum-fixed-historical-boundary-correlated-step-delayed-ability-and-time-lord-lki-v2"

private const val OUTCOME_STATE_PAIR_CHECKPOINT_SCHEMA = "outcome-state-corpus-pair-v4"
private const val OUTCOME_STATE_MANIFEST_FILE = "corpus.json"
private const val OUTCOME_STATE_COMPATIBILITY_CONTRACT =
    "historical-projection-verifier-compatibility-v4"
private const val OUTCOME_STATE_COMPATIBLE_ARGENTUM_TREE =
    "6f83ec7b624ed3293221e00f4deded895c30421b"
private const val OUTCOME_STATE_ARGENTUM_MODULE_PREFIX = "agent/infoset-argentum/"
private const val OUTCOME_STATE_FINGERPRINT_SOURCE =
    "agent/infoset-argentum/src/main/kotlin/org/mtgallium/agent/infoset/argentum/ArgentumStateFingerprint.kt"
private const val OUTCOME_STATE_HISTORICAL_FINGERPRINT_BLOB =
    "3ca7cf71c448a0a2048cd5747c4f60db30575a5b"
private const val OUTCOME_STATE_COMPATIBLE_FINGERPRINT_BLOB =
    "27f58d44f3b45e16d64c79bf8387318b607c3cf8"
private const val OUTCOME_STATE_FINGERPRINT_TEST_SOURCE =
    "agent/infoset-argentum/src/test/kotlin/org/mtgallium/agent/infoset/argentum/ArgentumStateFingerprintTest.kt"
private const val OUTCOME_STATE_HISTORICAL_FINGERPRINT_TEST_BLOB =
    "fda623d67d6bc12628f91b5b6058011ef5a79609"
private const val OUTCOME_STATE_COMPATIBLE_FINGERPRINT_TEST_BLOB =
    "4cc2f260272fcdd30cf244bf1023bf9ef1827077"
private val lowerSha256 = Regex("[0-9a-f]{64}")
private val fullCommit = Regex("[0-9a-f]{40}")

@Serializable
internal enum class OutcomeStateCorpusSplit { TRAIN, VALIDATION, TEST }

@Serializable
internal data class OutcomeStatePairSplitAssignment(
    val pairIndex: Int,
    val outcomeBlindRankSha256: String,
    val split: OutcomeStateCorpusSplit,
) {
    init {
        require(pairIndex >= 0)
        require(outcomeBlindRankSha256.matches(lowerSha256))
    }
}

/** Exact whole-pair split; no game outcome or derived state participates in ranking. */
@Serializable
internal data class OutcomeStateCorpusSplitBinding(
    val schemaVersion: Int = 1,
    val algorithm: String = "sha256-rank-exact-counts-v1",
    val domain: String = OUTCOME_STATE_CORPUS_SPLIT_DOMAIN,
    val parentRunIdentity: String,
    val trainPairs: Int,
    val validationPairs: Int,
    val testPairs: Int,
    val assignments: List<OutcomeStatePairSplitAssignment>,
) {
    init {
        require(schemaVersion == 1)
        require(algorithm == "sha256-rank-exact-counts-v1")
        require(domain.isNotBlank() && parentRunIdentity.isNotBlank())
        require(listOf(trainPairs, validationPairs, testPairs).all { it > 0 })
        require(assignments.map { it.pairIndex } == assignments.map { it.pairIndex }.distinct().sorted())
        require(assignments.size == trainPairs + validationPairs + testPairs)
        require(assignments.count { it.split == OutcomeStateCorpusSplit.TRAIN } == trainPairs)
        require(assignments.count { it.split == OutcomeStateCorpusSplit.VALIDATION } == validationPairs)
        require(assignments.count { it.split == OutcomeStateCorpusSplit.TEST } == testPairs)
        require(assignments.map { it.outcomeBlindRankSha256 }.distinct().size == assignments.size)
    }

    fun splitFor(pairIndex: Int): OutcomeStateCorpusSplit =
        assignments.singleOrNull { it.pairIndex == pairIndex }?.split
            ?: error("Pair $pairIndex is absent from the outcome-state split")

    fun bindingSha256(): String = sha256(evidenceJson.encodeToString(serializer(), this))

    companion object {
        fun create(
            pairIndices: List<Int>,
            parentRunIdentity: String,
            trainPairs: Int,
            validationPairs: Int,
            testPairs: Int,
        ): OutcomeStateCorpusSplitBinding {
            require(pairIndices == pairIndices.distinct().sorted())
            require(pairIndices.size == trainPairs + validationPairs + testPairs)
            val ranked = pairIndices.map { pairIndex ->
                pairIndex to sha256(
                    "$OUTCOME_STATE_CORPUS_SPLIT_DOMAIN\u0000$parentRunIdentity\u0000$pairIndex"
                )
            }.sortedWith(compareBy<Pair<Int, String>> { it.second }.thenBy { it.first })
            val splitByPair = ranked.mapIndexed { rank, (pairIndex, digest) ->
                val split = when {
                    rank < trainPairs -> OutcomeStateCorpusSplit.TRAIN
                    rank < trainPairs + validationPairs -> OutcomeStateCorpusSplit.VALIDATION
                    else -> OutcomeStateCorpusSplit.TEST
                }
                pairIndex to OutcomeStatePairSplitAssignment(pairIndex, digest, split)
            }.toMap()
            return OutcomeStateCorpusSplitBinding(
                parentRunIdentity = parentRunIdentity,
                trainPairs = trainPairs,
                validationPairs = validationPairs,
                testPairs = testPairs,
                assignments = pairIndices.map(splitByPair::getValue),
            )
        }
    }
}

@Serializable
internal data class OutcomeStateProjectionAuthority(
    val outerCommit: String,
    val infosetCoreTree: String,
    val infosetArgentumTree: String,
    val argentumCommit: String,
) {
    init {
        require(outerCommit.matches(fullCommit))
        require(infosetCoreTree.matches(fullCommit))
        require(infosetArgentumTree.matches(fullCommit))
        require(argentumCommit.matches(fullCommit))
    }
}

@Serializable
internal data class OutcomeStateHistoricalAuthority(
    val parentRunIdentity: String,
    val parentArtifactManifestSha256: String,
    val projection: OutcomeStateProjectionAuthority,
    val controlPolicyId: String,
    val treatmentPolicyId: String,
    val controlPolicyEvidenceIdentity: String,
    val treatmentPolicyEvidenceIdentity: String,
) {
    init {
        require(parentRunIdentity.isNotBlank())
        require(parentArtifactManifestSha256.matches(lowerSha256))
        require(controlPolicyId.isNotBlank() && treatmentPolicyId.isNotBlank())
        require(controlPolicyEvidenceIdentity.isNotBlank() && treatmentPolicyEvidenceIdentity.isNotBlank())
    }
}

@Serializable
internal data class OutcomeStateProducerAuthority(
    val projection: OutcomeStateProjectionAuthority,
    val sourceProvenance: ResearchSourceProvenance,
) {
    init {
        require(sourceProvenance.outer.revision == projection.outerCommit)
        require(sourceProvenance.argentum.revision == projection.argentumCommit)
        require(sourceProvenance.expectedArgentumRevision == projection.argentumCommit)
    }
}

@Serializable
internal data class OutcomeStateSourceBlobDelta(
    val path: String,
    val historicalBlob: String,
    val producerBlob: String,
) {
    init {
        require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path)
        require(historicalBlob.matches(fullCommit) && producerBlob.matches(fullCommit))
        require(historicalBlob != producerBlob)
    }
}

/**
 * Closed compatibility proof for using the current verifier over historical canonical evidence.
 * Test changes are retained for audit but are expressly outside runtime projection semantics.
 */
@Serializable
internal data class OutcomeStateProjectionCompatibility(
    val schemaVersion: Int = 4,
    val contract: String = OUTCOME_STATE_COMPATIBILITY_CONTRACT,
    val stateEquivalenceAlgorithm: String = OUTCOME_STATE_CORPUS_STATE_EQUIVALENCE,
    val replayActionEquivalenceAlgorithm: String = OUTCOME_STATE_CORPUS_ACTION_EQUIVALENCE,
    val replayEventEquivalenceAlgorithm: String = OUTCOME_STATE_CORPUS_EVENT_EQUIVALENCE,
    val replayTransitionStateEquivalenceAlgorithm: String =
        OUTCOME_STATE_CORPUS_TRANSITION_STATE_EQUIVALENCE,
    val historicalInfosetCoreTree: String,
    val producerInfosetCoreTree: String,
    val historicalInfosetArgentumTree: String,
    val producerInfosetArgentumTree: String,
    val runtimeSourceDelta: OutcomeStateSourceBlobDelta,
    val nonRuntimeTestSourceDeltas: List<OutcomeStateSourceBlobDelta>,
) {
    init {
        require(schemaVersion == 4 && contract == OUTCOME_STATE_COMPATIBILITY_CONTRACT)
        require(stateEquivalenceAlgorithm == OUTCOME_STATE_CORPUS_STATE_EQUIVALENCE)
        require(replayActionEquivalenceAlgorithm == OUTCOME_STATE_CORPUS_ACTION_EQUIVALENCE)
        require(replayEventEquivalenceAlgorithm == OUTCOME_STATE_CORPUS_EVENT_EQUIVALENCE)
        require(
            replayTransitionStateEquivalenceAlgorithm ==
                OUTCOME_STATE_CORPUS_TRANSITION_STATE_EQUIVALENCE
        )
        require(historicalInfosetCoreTree == OUTCOME_STATE_CORPUS_INFOSET_CORE_TREE)
        require(producerInfosetCoreTree == historicalInfosetCoreTree)
        require(historicalInfosetArgentumTree == OUTCOME_STATE_CORPUS_INFOSET_ARGENTUM_TREE)
        require(producerInfosetArgentumTree == OUTCOME_STATE_COMPATIBLE_ARGENTUM_TREE)
        require(
            runtimeSourceDelta == OutcomeStateSourceBlobDelta(
                path = OUTCOME_STATE_FINGERPRINT_SOURCE,
                historicalBlob = OUTCOME_STATE_HISTORICAL_FINGERPRINT_BLOB,
                producerBlob = OUTCOME_STATE_COMPATIBLE_FINGERPRINT_BLOB,
            )
        )
        require(
            nonRuntimeTestSourceDeltas == listOf(
                OutcomeStateSourceBlobDelta(
                    path = OUTCOME_STATE_FINGERPRINT_TEST_SOURCE,
                    historicalBlob = OUTCOME_STATE_HISTORICAL_FINGERPRINT_TEST_BLOB,
                    producerBlob = OUTCOME_STATE_COMPATIBLE_FINGERPRINT_TEST_BLOB,
                )
            )
        )
    }

    fun requireAuthorities(
        historical: OutcomeStateProjectionAuthority,
        producer: OutcomeStateProjectionAuthority,
    ) {
        require(historical.infosetCoreTree == historicalInfosetCoreTree)
        require(producer.infosetCoreTree == producerInfosetCoreTree)
        require(historical.infosetArgentumTree == historicalInfosetArgentumTree)
        require(producer.infosetArgentumTree == producerInfosetArgentumTree)
        require(historical.argentumCommit == producer.argentumCommit)
    }

    fun bindingSha256(): String = sha256(evidenceJson.encodeToString(serializer(), this))
}

/** Identity a training consumer uses for the projection that produced every safe state. */
@Serializable
internal data class OutcomeStateTrainingProjectionAuthority(
    val schemaVersion: Int = 4,
    val historicalProjection: OutcomeStateProjectionAuthority,
    val compatibility: OutcomeStateProjectionCompatibility,
    val currentProducer: OutcomeStateProducerAuthority,
) {
    init {
        require(schemaVersion == 4)
        compatibility.requireAuthorities(historicalProjection, currentProducer.projection)
    }

    fun identity(): String =
        "outcome-state-training-projection-v4-sha256:${sha256(evidenceJson.encodeToString(serializer(), this))}"
}

@Serializable
internal data class OutcomeStateSyntheticAbilityMappingAudit(
    val creationRawOrdinal: Int,
    val retirementRawOrdinal: Int,
    val stackEntityId: String,
    val normalizedStatePath: String,
) {
    init {
        require(creationRawOrdinal >= 0 && retirementRawOrdinal > creationRawOrdinal)
        require(stackEntityId.isNotBlank())
        require(
            normalizedStatePath ==
                "/entities/${jsonPointerSegment(stackEntityId)}/" +
                "com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent/" +
                "abilityIdentity/abilityId"
        )
    }
}

@Serializable
internal data class OutcomeStateLegacyTypeLineNormalizationAudit(
    val rawOrdinal: Int,
    val eventIndex: Int,
    val entityId: String,
    val normalizedEventPath: String = "/events/$eventIndex/lastKnown/typeLine",
    val normalizedStatePath: String =
        "/entities/${jsonPointerSegment(entityId)}/" +
            "com.wingedsheep.engine.state.components.battlefield.LastKnownPermanentComponent/" +
            "snapshot/typeLine",
    val retirementRawOrdinal: Int? = null,
    val presentAtFinal: Boolean = retirementRawOrdinal == null,
) {
    init {
        require(rawOrdinal >= 0 && eventIndex >= 0)
        require(entityId.isNotBlank())
        require(normalizedEventPath == "/events/$eventIndex/lastKnown/typeLine")
        require(
            normalizedStatePath ==
                "/entities/${jsonPointerSegment(entityId)}/" +
                    "com.wingedsheep.engine.state.components.battlefield.LastKnownPermanentComponent/" +
                    "snapshot/typeLine"
        )
        require(retirementRawOrdinal == null || retirementRawOrdinal > rawOrdinal)
        require(presentAtFinal == (retirementRawOrdinal == null))
    }
}

@Serializable
internal data class OutcomeStateReplayCompatibilityAudit(
    val schemaVersion: Int = 2,
    val algorithm: String = OUTCOME_STATE_CORPUS_TRANSITION_STATE_EQUIVALENCE,
    val eventEquivalenceAlgorithm: String = OUTCOME_STATE_CORPUS_EVENT_EQUIVALENCE,
    val syntheticAbilityMappings: List<OutcomeStateSyntheticAbilityMappingAudit>,
    val syntheticAbilityMappingCount: Int = syntheticAbilityMappings.size,
    val legacyTimeLordTypeLineNormalizations: List<OutcomeStateLegacyTypeLineNormalizationAudit> =
        emptyList(),
    val legacyTimeLordTypeLineNormalizationCount: Int =
        legacyTimeLordTypeLineNormalizations.size,
    // Last-known information legitimately persists until that card's next zone change, including
    // through the terminal boundary. These are observable live compatibility episodes, not leaked
    // routing identities.
    val activeLegacyTimeLordTypeLineMappingsAtFinal: Int =
        legacyTimeLordTypeLineNormalizations.count { it.presentAtFinal },
    // Existing field for synthetic routing identities: those must retire before final validation.
    val activeMappingsAtFinal: Int = 0,
    val forbiddenOccurrenceCount: Int = 0,
) {
    init {
        require(schemaVersion == 2)
        require(algorithm == OUTCOME_STATE_CORPUS_TRANSITION_STATE_EQUIVALENCE)
        require(eventEquivalenceAlgorithm == OUTCOME_STATE_CORPUS_EVENT_EQUIVALENCE)
        require(syntheticAbilityMappingCount == syntheticAbilityMappings.size)
        require(syntheticAbilityMappings.map { it.stackEntityId }.distinct().size ==
            syntheticAbilityMappings.size)
        require(syntheticAbilityMappings == syntheticAbilityMappings.sortedWith(
            compareBy<OutcomeStateSyntheticAbilityMappingAudit> { it.creationRawOrdinal }
                .thenBy { it.stackEntityId }
        ))
        require(
            legacyTimeLordTypeLineNormalizationCount ==
                legacyTimeLordTypeLineNormalizations.size
        )
        require(
            legacyTimeLordTypeLineNormalizations ==
                legacyTimeLordTypeLineNormalizations.sortedWith(
                    compareBy<OutcomeStateLegacyTypeLineNormalizationAudit> { it.rawOrdinal }
                        .thenBy { it.eventIndex }
                        .thenBy { it.entityId }
                )
        )
        require(
            legacyTimeLordTypeLineNormalizations.distinctBy {
                Triple(it.rawOrdinal, it.eventIndex, it.entityId)
            }.size == legacyTimeLordTypeLineNormalizations.size
        )
        require(
            legacyTimeLordTypeLineNormalizations.map { it.entityId }.distinct().size ==
                legacyTimeLordTypeLineNormalizations.size
        )
        require(
            legacyTimeLordTypeLineNormalizations.distinctBy {
                it.rawOrdinal to it.eventIndex
            }.size == legacyTimeLordTypeLineNormalizations.size
        )
        require(
            activeLegacyTimeLordTypeLineMappingsAtFinal ==
                legacyTimeLordTypeLineNormalizations.count { it.presentAtFinal }
        )
        require(activeMappingsAtFinal == 0)
        require(forbiddenOccurrenceCount == 0)
    }

    fun requireForRawTransitionCount(rawTransitionCount: Int) {
        require(rawTransitionCount >= 0)
        require(syntheticAbilityMappings.all {
            it.creationRawOrdinal < rawTransitionCount && it.retirementRawOrdinal < rawTransitionCount
        })
        require(legacyTimeLordTypeLineNormalizations.all { it.rawOrdinal < rawTransitionCount })
        require(legacyTimeLordTypeLineNormalizations.all {
            it.retirementRawOrdinal == null || it.retirementRawOrdinal < rawTransitionCount
        })
    }
}

@Serializable
internal data class OutcomeStateGameArtifact(
    val pairIndex: Int,
    val leg: String,
    val split: OutcomeStateCorpusSplit,
    val historicalGameId: String,
    val historicalCreatedAtUtc: String,
    val historicalGameSeed: Long,
    val historicalSearchBaseSeed: Long,
    val historicalP0PolicyId: String,
    val historicalP1PolicyId: String,
    val rootPlayerId: String,
    val rootPolicyEvidenceIdentity: String,
    val trainingProjectionIdentity: String,
    val parentReplayReference: String,
    val parentReplaySha256: String,
    val parentReplayTerminalRecordDigest: String,
    val derivedBundleId: String,
    val bundleReference: String,
    val bundleSha256: String,
    val bundleBytes: Long,
    val semanticDecisions: Int,
    val rawTransitions: Int,
    val replayCompatibilityAudit: OutcomeStateReplayCompatibilityAudit,
    val decisionBoundaryStates: Int,
    val rootActorStates: Int,
    val opponentActorStates: Int,
    val privateOpponentResponses: Int,
    val actualTerminalPayoff: Double,
    val winnerId: String?,
) {
    init {
        require(pairIndex >= 0 && leg in setOf("a", "b"))
        require(historicalGameId.isNotBlank() && rootPlayerId in setOf("p0", "p1"))
        require((leg == "a" && rootPlayerId == "p0") || (leg == "b" && rootPlayerId == "p1"))
        require(
            historicalP0PolicyId == if (leg == "a") {
                SEARCH_BUDGET_FRONTIER_CONTROL_ID
            } else {
                SEARCH_BUDGET_FRONTIER_TREATMENT_ID
            }
        )
        require(
            historicalP1PolicyId == if (leg == "a") {
                SEARCH_BUDGET_FRONTIER_TREATMENT_ID
            } else {
                SEARCH_BUDGET_FRONTIER_CONTROL_ID
            }
        )
        require(rootPolicyEvidenceIdentity == OUTCOME_STATE_CORPUS_CONTROL_POLICY_EVIDENCE_ID)
        require(trainingProjectionIdentity.startsWith("outcome-state-training-projection-v4-sha256:"))
        listOf(parentReplaySha256, parentReplayTerminalRecordDigest, bundleSha256).forEach {
            require(it.matches(lowerSha256))
        }
        require(runCatching { UUID.fromString(derivedBundleId) }.isSuccess)
        require(parentReplayReference.startsWith("replays/") && !parentReplayReference.startsWith('/'))
        require(
            bundleReference.startsWith("pairs/pair-$pairIndex/bundles/") &&
                !bundleReference.startsWith('/')
        )
        require(bundleBytes > 0)
        require(semanticDecisions > 0 && rawTransitions >= semanticDecisions)
        require(replayCompatibilityAudit.algorithm == OUTCOME_STATE_CORPUS_TRANSITION_STATE_EQUIVALENCE)
        replayCompatibilityAudit.requireForRawTransitionCount(rawTransitions)
        require(decisionBoundaryStates > 0)
        require(rootActorStates > 0 && opponentActorStates > 0)
        require(rootActorStates + opponentActorStates == decisionBoundaryStates)
        require(privateOpponentResponses >= 0)
        require(actualTerminalPayoff in -1.0..1.0)
    }
}

@Serializable
internal data class OutcomeStateCorpusReplayCompatibilityAudit(
    val schemaVersion: Int = 1,
    val eventEquivalenceAlgorithm: String = OUTCOME_STATE_CORPUS_EVENT_EQUIVALENCE,
    val transitionStateEquivalenceAlgorithm: String =
        OUTCOME_STATE_CORPUS_TRANSITION_STATE_EQUIVALENCE,
    val gameCount: Int,
    val gamesWithSyntheticAbilityMappings: Int,
    val syntheticAbilityMappingCount: Int,
    val gamesWithLegacyTimeLordTypeLineNormalizations: Int,
    val legacyTimeLordTypeLineNormalizationCount: Int,
    val activeLegacyTimeLordTypeLineMappingsAtFinal: Int,
    val activeMappingsAtFinal: Int,
    val forbiddenOccurrenceCount: Int,
) {
    init {
        require(schemaVersion == 1)
        require(eventEquivalenceAlgorithm == OUTCOME_STATE_CORPUS_EVENT_EQUIVALENCE)
        require(
            transitionStateEquivalenceAlgorithm ==
                OUTCOME_STATE_CORPUS_TRANSITION_STATE_EQUIVALENCE
        )
        require(gameCount > 0)
        require(gamesWithSyntheticAbilityMappings in 0..gameCount)
        require(gamesWithLegacyTimeLordTypeLineNormalizations in 0..gameCount)
        require(syntheticAbilityMappingCount >= gamesWithSyntheticAbilityMappings)
        require(
            legacyTimeLordTypeLineNormalizationCount >=
                gamesWithLegacyTimeLordTypeLineNormalizations
        )
        require(
            activeLegacyTimeLordTypeLineMappingsAtFinal in
                0..legacyTimeLordTypeLineNormalizationCount
        )
        require(activeMappingsAtFinal == 0)
        require(forbiddenOccurrenceCount == 0)
    }

    companion object {
        fun from(games: List<OutcomeStateGameArtifact>): OutcomeStateCorpusReplayCompatibilityAudit {
            require(games.isNotEmpty())
            val audits = games.map { it.replayCompatibilityAudit }
            return OutcomeStateCorpusReplayCompatibilityAudit(
                gameCount = games.size,
                gamesWithSyntheticAbilityMappings = audits.count {
                    it.syntheticAbilityMappingCount > 0
                },
                syntheticAbilityMappingCount = audits.sumOf { it.syntheticAbilityMappingCount },
                gamesWithLegacyTimeLordTypeLineNormalizations = audits.count {
                    it.legacyTimeLordTypeLineNormalizationCount > 0
                },
                legacyTimeLordTypeLineNormalizationCount = audits.sumOf {
                    it.legacyTimeLordTypeLineNormalizationCount
                },
                activeLegacyTimeLordTypeLineMappingsAtFinal = audits.sumOf {
                    it.activeLegacyTimeLordTypeLineMappingsAtFinal
                },
                activeMappingsAtFinal = audits.sumOf { it.activeMappingsAtFinal },
                forbiddenOccurrenceCount = audits.sumOf { it.forbiddenOccurrenceCount },
            )
        }
    }
}

@Serializable
internal data class OutcomeStateCorpusManifest(
    val schemaVersion: Int = 4,
    val documentKind: String = "perspective-safe-outcome-state-corpus-v4",
    val researchRunIdentity: String,
    /** Operational derivation time; each bundle separately retains the historical game's time. */
    val generatedAtUtc: String,
    val historical: OutcomeStateHistoricalAuthority,
    val producer: OutcomeStateProducerAuthority,
    val trainingProjection: OutcomeStateTrainingProjectionAuthority,
    val inputInventory: List<OutcomeStateInputPairInventory>,
    val inputInventorySha256: String,
    val deckHash: String,
    val cardPoolHash: String,
    val actionSpaceProfile: SearchActionSpaceProfile,
    val inspectionSchemaVersion: Int = INSPECTION_SCHEMA_CURRENT,
    val split: OutcomeStateCorpusSplitBinding,
    val splitBindingSha256: String,
    val games: List<OutcomeStateGameArtifact>,
    val replayCompatibilityAudit: OutcomeStateCorpusReplayCompatibilityAudit =
        OutcomeStateCorpusReplayCompatibilityAudit.from(games),
) {
    init {
        require(schemaVersion == 4 && documentKind == "perspective-safe-outcome-state-corpus-v4")
        require(researchRunIdentity.isNotBlank())
        require(historical.parentRunIdentity == OUTCOME_STATE_CORPUS_PARENT_IDENTITY)
        require(historical.projection.outerCommit == OUTCOME_STATE_CORPUS_HISTORICAL_OUTER_COMMIT)
        require(inputInventory.map { it.pairIndex } ==
            (SEARCH_BUDGET_FRONTIER_EXTENSION_START until
                SEARCH_BUDGET_FRONTIER_EXTENSION_START + SEARCH_BUDGET_FRONTIER_EXTENSION_PAIRS).toList()
        )
        require(sha256(evidenceJson.encodeToString(inputInventory)) == inputInventorySha256)
        require(inputInventorySha256.matches(lowerSha256))
        require(deckHash.matches(lowerSha256) && cardPoolHash.matches(lowerSha256))
        require(inspectionSchemaVersion == INSPECTION_SCHEMA_CURRENT)
        require(split.bindingSha256() == splitBindingSha256)
        require(split.trainPairs == 70 && split.validationPairs == 15 && split.testPairs == 15)
        require(split.assignments.map { it.pairIndex } == inputInventory.map { it.pairIndex })
        require(trainingProjection.historicalProjection == historical.projection)
        require(trainingProjection.currentProducer == producer)
        trainingProjection.compatibility.requireAuthorities(historical.projection, producer.projection)
        require(games.size == SEARCH_BUDGET_FRONTIER_EXTENSION_PAIRS * 2)
        require(games.map { it.historicalGameId }.distinct().size == games.size)
        require(games.map { it.derivedBundleId }.distinct().size == games.size)
        require(games.groupBy { it.pairIndex }.values.all { pair ->
            pair.size == 2 && pair.map { it.leg }.toSet() == setOf("a", "b") &&
                pair.map { it.split }.distinct().size == 1
        })
        require(games.all { it.split == split.splitFor(it.pairIndex) })
        require(games.all { it.trainingProjectionIdentity == trainingProjection.identity() })
        require(replayCompatibilityAudit == OutcomeStateCorpusReplayCompatibilityAudit.from(games))
        OutcomeStateCorpusSplit.entries.forEach { partition ->
            val partitionGames = games.filter { it.split == partition }
            require(partitionGames.isNotEmpty())
            require(partitionGames.sumOf { it.semanticDecisions } > 0)
            require(partitionGames.sumOf { it.rawTransitions } > 0)
            require(partitionGames.sumOf { it.rootActorStates } > 0)
            require(partitionGames.sumOf { it.opponentActorStates } > 0)
        }
    }
}

internal data class OutcomeStateReplayExpectation(
    val pairIndex: Int,
    val leg: String,
    val split: OutcomeStateCorpusSplit,
    val historicalGameId: String,
    val historicalGameSeed: Long,
    val historicalP0PolicyId: String,
    val historicalP1PolicyId: String,
    val expectedWinnerId: String?,
    val rootPlayerId: String,
    val parentRunIdentity: String,
    val historicalOuterCommit: String,
    val argentumCommit: String,
    val deckHash: String,
    val cardPoolHash: String,
    val parentReplayReference: String,
    val parentReplaySha256: String,
    val profileManifestHash: String,
) {
    init {
        require(pairIndex in SEARCH_BUDGET_FRONTIER_EXTENSION_START until
            SEARCH_BUDGET_FRONTIER_EXTENSION_START + SEARCH_BUDGET_FRONTIER_EXTENSION_PAIRS)
        require((leg == "a" && rootPlayerId == "p0") || (leg == "b" && rootPlayerId == "p1"))
        require(historicalP0PolicyId == if (leg == "a") {
            SEARCH_BUDGET_FRONTIER_CONTROL_ID
        } else {
            SEARCH_BUDGET_FRONTIER_TREATMENT_ID
        })
        require(historicalP1PolicyId == if (leg == "a") {
            SEARCH_BUDGET_FRONTIER_TREATMENT_ID
        } else {
            SEARCH_BUDGET_FRONTIER_CONTROL_ID
        })
        require(parentReplaySha256.matches(lowerSha256))
        require(profileManifestHash.matches(lowerSha256))
    }
}

internal data class OutcomeStateInspectionIdentity(
    val producerOuterCommit: String,
    val controlPolicyEvidenceIdentity: String,
    val evaluatorVersion: String = "mono-red-visible-board-v2",
    val beliefVersion: String = "sequential_b_v1:consistency_only_v1",
    val opponentModelVersion: String = "mono-red-mixture-70-10-10-10-v2",
)

internal data class SemanticReplaySetup(
    val gameId: String,
    val gameSeed: Long,
    val searchBaseSeed: Long,
    val startingPlayerIndex: Int,
    val actionSpaceProfile: SearchActionSpaceProfile,
)

internal data class SemanticReplayStep(
    val result: SearchStepResult,
    val rawTransitions: List<ArgentumRawTransition>,
)

/** Narrow test seam around the trusted engine-backed replay world. */
internal interface SemanticReplayWorld {
    fun actorToAct(): String?
    fun informationState(viewer: String): PolicyInformationState
    fun expandChoices(): List<SemanticChoice>
    fun stepWithReplayTrace(choice: SemanticChoice): SemanticReplayStep
    fun authoritativeState(): GameState
    fun terminalPayoff(rootPlayer: String): Double?
}

internal fun interface SemanticReplayWorldFactory {
    fun create(setup: SemanticReplaySetup): SemanticReplayWorld
}

internal class ArgentumSemanticReplayWorldFactory(
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
) : SemanticReplayWorldFactory {
    override fun create(setup: SemanticReplaySetup): SemanticReplayWorld =
        ArgentumSemanticReplayWorld(
            createSemanticReplayWorld(
                registry = registry,
                manifest = manifest,
                gameId = setup.gameId,
                gameSeed = setup.gameSeed,
                searchBaseSeed = setup.searchBaseSeed,
                startingPlayerIndex = setup.startingPlayerIndex,
                profile = setup.actionSpaceProfile,
            )
        )
}

private class ArgentumSemanticReplayWorld(
    private val world: ArgentumSearchWorld,
) : SemanticReplayWorld {
    override fun actorToAct(): String? = world.actorToAct()
    override fun informationState(viewer: String): PolicyInformationState = world.informationState(viewer)
    override fun expandChoices(): List<SemanticChoice> = world.expandChoices().candidates
    override fun stepWithReplayTrace(choice: SemanticChoice): SemanticReplayStep =
        world.stepWithReplayTrace(choice).let { SemanticReplayStep(it.result, it.rawTransitions) }
    override fun authoritativeState(): GameState = world.authoritativeStateForHost()
    override fun terminalPayoff(rootPlayer: String): Double? = world.terminalPayoff(rootPlayer)
}

internal data class CanonicalSemanticDecision(
    val decisionIndex: Int,
    val choice: SemanticChoice,
    val transitions: List<CanonicalReplayTransition>,
)

internal data class VerifiedCanonicalSemanticReplay(
    val header: CanonicalReplayHeader,
    val terminal: CanonicalReplayTerminal,
    val states: List<GameState>,
    val decisions: List<CanonicalSemanticDecision>,
)

/** Authenticate the record chain and require a complete sequence of accepted semantic choices. */
internal fun readVerifiedCanonicalSemanticReplay(path: Path): VerifiedCanonicalSemanticReplay {
    require(Files.isRegularFile(path) && !Files.isSymbolicLink(path)) {
        "Canonical replay is not a regular non-link file: $path"
    }
    val replay = reconstructCanonicalTournamentReplay(path)
    require(replay.terminal.status == ReplayCompletionStatus.COMPLETE) {
        "Canonical replay ${replay.header.gameId} is incomplete"
    }
    val decisions = mutableListOf<CanonicalSemanticDecision>()
    var currentIndex: Int? = null
    var currentChoice: SemanticChoice? = null
    var currentTransitions = mutableListOf<CanonicalReplayTransition>()
    fun finishDecision() {
        val index = currentIndex ?: return
        decisions += CanonicalSemanticDecision(index, requireNotNull(currentChoice), currentTransitions.toList())
        currentIndex = null
        currentChoice = null
        currentTransitions = mutableListOf()
    }
    replay.transitions.forEach { transition ->
        require(transition.accepted) { "Canonical replay contains rejected transition ${transition.ordinal}" }
        require(transition.action != null && transition.systemMutation == null) {
            "Canonical policy replay contains a non-action transition at ${transition.ordinal}"
        }
        val encodedIndex = transition.extensions["mtgallium.decisionIndex"]
        val encodedChoice = transition.extensions["mtgallium.semanticChoice"]
        require((encodedIndex == null) == (encodedChoice == null)) {
            "Canonical replay transition ${transition.ordinal} has a partial semantic binding"
        }
        if (encodedIndex != null) {
            finishDecision()
            val index = (encodedIndex as? JsonPrimitive)?.content?.toInt()
                ?: error("Canonical decision index is not an integer at transition ${transition.ordinal}")
            require(index == decisions.size) { "Canonical decision $index is not contiguous" }
            currentIndex = index
            currentChoice = PolicyJson.format.decodeFromJsonElement(SemanticChoice.serializer(), encodedChoice!!)
        } else {
            require(currentIndex != null) {
                "Canonical replay transition ${transition.ordinal} precedes its semantic choice"
            }
        }
        currentTransitions += transition
    }
    finishDecision()
    require(decisions.isNotEmpty()) { "Canonical replay contains no semantic decisions" }
    require(decisions.sumOf { it.transitions.size } == replay.transitions.size)
    return VerifiedCanonicalSemanticReplay(
        header = replay.header,
        terminal = replay.terminal,
        states = replay.states.indices.map(replay::stateAt),
        decisions = decisions,
    )
}

internal data class ProjectedOutcomeStateGame(
    val bundle: PolicyInspectionBundle,
    val historicalSearchBaseSeed: Long,
    val parentReplayTerminalRecordDigest: String,
    val semanticDecisions: Int,
    val rawTransitions: Int,
    val replayCompatibilityAudit: OutcomeStateReplayCompatibilityAudit,
    val decisionBoundaryStates: Int,
    val rootActorStates: Int,
    val opponentActorStates: Int,
    val privateOpponentResponses: Int,
    val actualTerminalPayoff: Double,
)

/**
 * Canonical replay transitions retain recorded routing nonces. Reconstruction mints a fresh nonce,
 * so compare SubmitDecision payloads after using Argentum's typed nonce rebinding operation. No
 * other action field or action kind is normalized.
 */
internal fun recordedReplayActionEquals(expected: GameAction, reconstructed: GameAction): Boolean =
    if (expected is SubmitDecision && reconstructed is SubmitDecision) {
        expected == reconstructed.copy(
            response = reconstructed.response.withDecisionId(expected.response.decisionId),
        )
    } else {
        expected == reconstructed
    }

internal data class RecordedReplayEventDifference(
    val index: Int,
    val reason: String,
    val expected: GameEvent?,
    val actual: GameEvent?,
)

internal data class RecordedReplayLegacyTypeLineNormalization(
    val eventIndex: Int,
    val entityId: EntityId,
)

internal data class RecordedReplayEventComparison(
    val difference: RecordedReplayEventDifference?,
    val legacyTimeLordTypeLineNormalizations: List<RecordedReplayLegacyTypeLineNormalization>,
)

/**
 * Preserve the exact ordered typed event stream while ignoring only a decision nonce whose link to
 * the corresponding state/action boundary is independently proved for both runs, plus the one
 * known lossy TypeLine string round trip in fixed Argentum 3eda. That codec splits the official
 * multi-word subtype `Time Lord` into `Time` and `Lord` while decoding historical records. The
 * compatibility is directional, typed, and admits no other subtype regrouping or event change.
 */
internal fun recordedReplayEventDifference(
    expectedEvents: List<GameEvent>,
    actualEvents: List<GameEvent>,
    expectedAction: GameAction,
    actualAction: GameAction,
    expectedBefore: GameState,
    actualBefore: GameState,
    expectedAfter: GameState,
    actualAfter: GameState,
): RecordedReplayEventDifference? = recordedReplayEventComparison(
    expectedEvents = expectedEvents,
    actualEvents = actualEvents,
    expectedAction = expectedAction,
    actualAction = actualAction,
    expectedBefore = expectedBefore,
    actualBefore = actualBefore,
    expectedAfter = expectedAfter,
    actualAfter = actualAfter,
).difference

internal fun recordedReplayEventComparison(
    expectedEvents: List<GameEvent>,
    actualEvents: List<GameEvent>,
    expectedAction: GameAction,
    actualAction: GameAction,
    expectedBefore: GameState,
    actualBefore: GameState,
    expectedAfter: GameState,
    actualAfter: GameState,
): RecordedReplayEventComparison {
    val normalizations = mutableListOf<RecordedReplayLegacyTypeLineNormalization>()
    val sharedSize = minOf(expectedEvents.size, actualEvents.size)
    repeat(sharedSize) { index ->
        val expected = expectedEvents[index]
        val actual = actualEvents[index]
        val match = recordedReplayEventMatch(
            expected = expected,
            actual = actual,
            expectedAction = expectedAction,
            actualAction = actualAction,
            expectedBefore = expectedBefore,
            actualBefore = actualBefore,
            expectedAfter = expectedAfter,
            actualAfter = actualAfter,
        )
        if (match == null) {
            return RecordedReplayEventComparison(
                difference = RecordedReplayEventDifference(
                    index = index,
                    reason = if (expected::class == actual::class) {
                        "typed event fields or routing correlation differ"
                    } else {
                        "event types differ"
                    },
                    expected = expected,
                    actual = actual,
                ),
                legacyTimeLordTypeLineNormalizations = normalizations,
            )
        }
        if (match.legacyTimeLordTypeLineNormalized) {
            normalizations += RecordedReplayLegacyTypeLineNormalization(
                eventIndex = index,
                entityId = (actual as ZoneChangeEvent).entityId,
            )
        }
    }
    return if (expectedEvents.size == actualEvents.size) {
        RecordedReplayEventComparison(
            difference = null,
            legacyTimeLordTypeLineNormalizations = normalizations,
        )
    } else {
        RecordedReplayEventComparison(
            difference = RecordedReplayEventDifference(
                index = sharedSize,
                reason = "event counts differ: expected=${expectedEvents.size}, actual=${actualEvents.size}",
                expected = expectedEvents.getOrNull(sharedSize),
                actual = actualEvents.getOrNull(sharedSize),
            ),
            legacyTimeLordTypeLineNormalizations = normalizations,
        )
    }
}

private data class RecordedReplayEventMatch(
    val legacyTimeLordTypeLineNormalized: Boolean = false,
)

private fun recordedReplayEventMatch(
    expected: GameEvent,
    actual: GameEvent,
    expectedAction: GameAction,
    actualAction: GameAction,
    expectedBefore: GameState,
    actualBefore: GameState,
    expectedAfter: GameState,
    actualAfter: GameState,
): RecordedReplayEventMatch? {
    return when {
        expected is DecisionSubmittedEvent && actual is DecisionSubmittedEvent -> {
            val expectedSubmit = expectedAction as? SubmitDecision ?: return null
            val actualSubmit = actualAction as? SubmitDecision ?: return null
            val expectedPendingId = expectedBefore.pendingDecision?.id ?: return null
            val actualPendingId = actualBefore.pendingDecision?.id ?: return null
            (expected.decisionId == expectedSubmit.response.decisionId &&
                expected.decisionId == expectedPendingId &&
                actual.decisionId == actualSubmit.response.decisionId &&
                actual.decisionId == actualPendingId &&
                expected == actual.copy(decisionId = expected.decisionId))
                .takeIf { it }?.let { RecordedReplayEventMatch() }
        }
        expected is DecisionRequestedEvent && actual is DecisionRequestedEvent -> {
            val expectedPendingId = expectedAfter.pendingDecision?.id ?: return null
            val actualPendingId = actualAfter.pendingDecision?.id ?: return null
            (expected.decisionId == expectedPendingId &&
                actual.decisionId == actualPendingId &&
                expected == actual.copy(decisionId = expected.decisionId))
                .takeIf { it }?.let { RecordedReplayEventMatch() }
        }
        expected == actual -> RecordedReplayEventMatch()
        expected is ZoneChangeEvent && actual is ZoneChangeEvent &&
            fixedArgentumTimeLordTypeLineRoundTripEquals(expected, actual) ->
            RecordedReplayEventMatch(legacyTimeLordTypeLineNormalized = true)
        else -> null
    }
}

private fun fixedArgentumTimeLordTypeLineRoundTripEquals(
    historical: ZoneChangeEvent,
    reconstructed: ZoneChangeEvent,
): Boolean {
    val historicalSnapshot = historical.lastKnown ?: return false
    val reconstructedSnapshot = reconstructed.lastKnown ?: return false
    if (historical.fromZone != Zone.BATTLEFIELD || reconstructed.fromZone != Zone.BATTLEFIELD ||
        historicalSnapshot.entityId != historical.entityId ||
        reconstructedSnapshot.entityId != reconstructed.entityId
    ) {
        return false
    }
    if (!fixedArgentumTimeLordSnapshotRoundTripEquals(
            historicalSnapshot,
            reconstructedSnapshot,
        )
    ) {
        return false
    }
    return historical == reconstructed.copy(lastKnown = historicalSnapshot)
}

private fun fixedArgentumTimeLordSnapshotRoundTripEquals(
    historicalSnapshot: EntitySnapshot,
    reconstructedSnapshot: EntitySnapshot,
): Boolean {
    val historicalTypeLine = historicalSnapshot.typeLine ?: return false
    val reconstructedTypeLine = reconstructedSnapshot.typeLine ?: return false
    if ("Time Lord" !in reconstructedSnapshot.subtypes ||
        "Time" in reconstructedSnapshot.subtypes ||
        "Lord" in reconstructedSnapshot.subtypes ||
        reconstructedTypeLine.subtypes.map { it.value }.toSet() != reconstructedSnapshot.subtypes
    ) {
        return false
    }
    if (!isFixedArgentumTimeLordSplit(historicalTypeLine, reconstructedTypeLine)) return false
    return historicalSnapshot == reconstructedSnapshot.copy(typeLine = historicalTypeLine)
}

private fun isFixedArgentumTimeLordSplit(
    historical: TypeLine,
    reconstructed: TypeLine,
): Boolean {
    val timeLord = Subtype("Time Lord")
    val splitTime = Subtype("Time")
    val splitLord = Subtype("Lord")
    if (historical.supertypes != reconstructed.supertypes ||
        historical.cardTypes != reconstructed.cardTypes ||
        CardType.CREATURE !in reconstructed.cardTypes ||
        historical.toString() != reconstructed.toString() ||
        timeLord !in reconstructed.subtypes ||
        splitTime in reconstructed.subtypes ||
        splitLord in reconstructed.subtypes ||
        timeLord in historical.subtypes ||
        splitTime !in historical.subtypes ||
        splitLord !in historical.subtypes
    ) {
        return false
    }
    return historical.subtypes ==
        (reconstructed.subtypes - timeLord + splitTime + splitLord)
}

private fun canonicalReplayEventJson(event: GameEvent?): String =
    event?.let {
        CanonicalReplayJson.encodeToString(
            JsonElement.serializer(),
            ReplayCanonicalJson.canonicalize(
                CanonicalReplayJson.encodeToJsonElement(GameEvent.serializer(), it)
            ),
        )
    } ?: "null"

internal data class RecordedReplayStateDifference(
    val boundary: String,
    val path: String,
    val expected: String?,
    val actual: String?,
    val reason: String,
)

private data class SyntheticDelayedAbilityMapping(
    val stackEntityId: EntityId,
    val expectedAbilityId: AbilityId,
    val actualAbilityId: AbilityId,
) {
    init {
        require(expectedAbilityId != actualAbilityId)
    }

    val statePath: String =
        "/entities/${jsonPointerSegment(stackEntityId.value)}/" +
            "com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent/" +
            "abilityIdentity/abilityId"
}

private data class SyntheticDelayedAbilityLifecycle(
    val mapping: SyntheticDelayedAbilityMapping,
    val creationRawOrdinal: Int,
    var retirementRawOrdinal: Int? = null,
)

private data class LegacyTimeLordTypeLineLifecycle(
    val eventIndex: Int,
    val entityId: EntityId,
    val creationRawOrdinal: Int,
    var retirementRawOrdinal: Int? = null,
) {
    val statePath: String =
        "/entities/${jsonPointerSegment(entityId.value)}/" +
            "com.wingedsheep.engine.state.components.battlefield.LastKnownPermanentComponent/" +
            "snapshot/typeLine"
}

/**
 * Transition-scoped compatibility for two fixed Argentum 3eda serialization identities. A
 * synthetic delayed-ability mapping is admitted only at its typed creation transition. A `Time
 * Lord` TypeLine normalization is admitted only when an exact typed leave event also creates the
 * exact corresponding last-known component. Each remains scoped to that component's lifetime and
 * then becomes a game-lifetime tombstone, so an uncorrelated recurrence cannot reuse it.
 */
internal class RecordedReplayStateEquivalence(
    historicalAuthority: OutcomeStateProjectionAuthority,
) {
    init {
        require(historicalAuthority == historicalProjectionAuthority()) {
            "Replay compatibility is restricted to the fixed historical projection authority"
        }
    }

    private val admittedMappings = linkedMapOf<EntityId, SyntheticDelayedAbilityLifecycle>()
    private val admittedLegacyTypeLines =
        linkedMapOf<EntityId, LegacyTimeLordTypeLineLifecycle>()
    private val priorArtifactStrings = mutableSetOf<String>()
    private var nextRawOrdinal = 0
    private var finalValidated = false
    private var safeInspectionBundleValidated = false

    internal val admittedSyntheticAbilityMappings: Int get() = admittedMappings.size

    fun initialDifference(expected: GameState, actual: GameState): RecordedReplayStateDifference? {
        stateDifference("initial", expected, actual)?.let { return it }
        rememberState(expected)
        rememberState(actual)
        return null
    }

    fun transitionDifference(
        expectedAction: GameAction,
        actualAction: GameAction,
        expectedEvents: List<GameEvent>,
        actualEvents: List<GameEvent>,
        expectedBefore: GameState,
        actualBefore: GameState,
        expectedAfter: GameState,
        actualAfter: GameState,
        expectedAccepted: Boolean,
        actualAccepted: Boolean,
        rawOrdinal: Int,
        legacyTimeLordTypeLineNormalizations: List<RecordedReplayLegacyTypeLineNormalization> =
            emptyList(),
    ): RecordedReplayStateDifference? {
        require(!finalValidated) { "Replay state equivalence was already finalized" }
        require(rawOrdinal == nextRawOrdinal) {
            "Replay raw ordinal is not contiguous: expected=$nextRawOrdinal, actual=$rawOrdinal"
        }
        nextRawOrdinal++
        stateDifference("before", expectedBefore, actualBefore)?.let { return it }
        rememberState(expectedBefore)
        rememberState(actualBefore)

        val discovered = if (expectedAccepted && actualAccepted &&
            expectedAction is PassPriority && actualAction is PassPriority &&
            expectedAction == actualAction
        ) {
            discoverMappings(
                expectedEvents = expectedEvents,
                actualEvents = actualEvents,
                expectedBefore = expectedBefore,
                actualBefore = actualBefore,
                expectedAfter = expectedAfter,
                actualAfter = actualAfter,
            )
        } else {
            MappingDiscovery()
        }
        discovered.difference?.let { return it }
        discovered.mappings.forEach { mapping ->
            listOf(mapping.expectedAbilityId.value, mapping.actualAbilityId.value).forEach { id ->
                if (id in priorArtifactStrings) {
                    return RecordedReplayStateDifference(
                        boundary = "after",
                        path = mapping.statePath,
                        expected = jsonString(mapping.expectedAbilityId.value),
                        actual = jsonString(mapping.actualAbilityId.value),
                        reason = "synthetic ability id spelling appeared before its correlated creation",
                    )
                }
            }
            val collision = admittedMappings.values.map { it.mapping }.firstOrNull {
                it.stackEntityId == mapping.stackEntityId ||
                    it.expectedAbilityId == mapping.expectedAbilityId ||
                    it.actualAbilityId == mapping.actualAbilityId ||
                    it.expectedAbilityId == mapping.actualAbilityId ||
                    it.actualAbilityId == mapping.expectedAbilityId
            }
            if (collision != null) {
                return RecordedReplayStateDifference(
                    boundary = "after",
                    path = mapping.statePath,
                    expected = jsonString(mapping.expectedAbilityId.value),
                    actual = jsonString(mapping.actualAbilityId.value),
                    reason = "synthetic ability mapping is not one-to-one with admitted mapping $collision",
                )
            }
            admittedMappings[mapping.stackEntityId] = SyntheticDelayedAbilityLifecycle(
                mapping = mapping,
                creationRawOrdinal = rawOrdinal,
            )
        }

        legacyTimeLordTypeLineNormalizations.forEach { normalization ->
            val existing = admittedLegacyTypeLines[normalization.entityId]
            if (existing != null) {
                return RecordedReplayStateDifference(
                    boundary = "after",
                    path = existing.statePath,
                    expected = null,
                    actual = null,
                    reason = "legacy Time Lord type-line entity was normalized more than once",
                )
            }
            val lifecycle = LegacyTimeLordTypeLineLifecycle(
                eventIndex = normalization.eventIndex,
                entityId = normalization.entityId,
                creationRawOrdinal = rawOrdinal,
            )
            val expectedEvent = expectedEvents.getOrNull(normalization.eventIndex)
                as? ZoneChangeEvent
            val actualEvent = actualEvents.getOrNull(normalization.eventIndex)
                as? ZoneChangeEvent
            if (!expectedAccepted || !actualAccepted ||
                expectedEvent == null || actualEvent == null ||
                expectedEvent.entityId != normalization.entityId ||
                actualEvent.entityId != normalization.entityId ||
                !fixedArgentumTimeLordTypeLineRoundTripEquals(expectedEvent, actualEvent)
            ) {
                return RecordedReplayStateDifference(
                    boundary = "transition",
                    path = "/events/${normalization.eventIndex}/lastKnown/typeLine",
                    expected = null,
                    actual = null,
                    reason = "legacy Time Lord normalization does not identify its exact typed leave event",
                )
            }
            val expectedSnapshot = expectedAfter.getEntity(normalization.entityId)
                ?.get<LastKnownPermanentComponent>()?.snapshot
            val actualSnapshot = actualAfter.getEntity(normalization.entityId)
                ?.get<LastKnownPermanentComponent>()?.snapshot
            if (expectedSnapshot != expectedEvent.lastKnown || actualSnapshot != actualEvent.lastKnown) {
                return RecordedReplayStateDifference(
                    boundary = "after",
                    path = lifecycle.statePath,
                    expected = expectedEvent.lastKnown?.typeLine?.toString(),
                    actual = actualEvent.lastKnown?.typeLine?.toString(),
                    reason = "normalized leave event is not the exact source of its last-known state snapshot",
                )
            }
            admittedLegacyTypeLines[normalization.entityId] = lifecycle
        }

        transitionArtifactDifference(
            expectedAction = expectedAction,
            actualAction = actualAction,
            expectedEvents = expectedEvents,
            actualEvents = actualEvents,
        )?.let { return it }

        stateDifference("after", expectedAfter, actualAfter)?.let { return it }
        admittedMappings.values.filter { it.retirementRawOrdinal == null }.forEach { lifecycle ->
            val entityId = lifecycle.mapping.stackEntityId
            if (expectedAfter.getEntity(entityId)?.get<TriggeredAbilityOnStackComponent>() == null &&
                actualAfter.getEntity(entityId)?.get<TriggeredAbilityOnStackComponent>() == null
            ) {
                lifecycle.retirementRawOrdinal = rawOrdinal
            }
        }
        admittedLegacyTypeLines.values.filter { it.retirementRawOrdinal == null }
            .forEach { lifecycle ->
                val expectedSnapshot = expectedAfter.getEntity(lifecycle.entityId)
                    ?.get<LastKnownPermanentComponent>()
                val actualSnapshot = actualAfter.getEntity(lifecycle.entityId)
                    ?.get<LastKnownPermanentComponent>()
                if (expectedSnapshot == null && actualSnapshot == null) {
                    lifecycle.retirementRawOrdinal = rawOrdinal
                }
            }
        rememberTransitionArtifacts(expectedAction, actualAction, expectedEvents, actualEvents)
        rememberState(expectedAfter)
        rememberState(actualAfter)
        return null
    }

    fun finalDifference(
        expected: GameState,
        actual: GameState,
        rawTransitionCount: Int,
    ): RecordedReplayStateDifference? {
        require(!finalValidated) { "Replay state equivalence was already finalized" }
        require(rawTransitionCount == nextRawOrdinal)
        stateDifference("final", expected, actual)?.let { return it }
        admittedMappings.values.firstOrNull { it.retirementRawOrdinal == null }?.let { lifecycle ->
            return RecordedReplayStateDifference(
                boundary = "final",
                path = lifecycle.mapping.statePath,
                expected = null,
                actual = null,
                reason = "correlated synthetic ability mapping remains active at final boundary",
            )
        }
        finalValidated = true
        return null
    }

    fun completedAudit(): OutcomeStateReplayCompatibilityAudit {
        require(finalValidated) { "Replay state equivalence audit requested before final validation" }
        require(safeInspectionBundleValidated) {
            "Replay state equivalence audit requested before safe inspection-bundle validation"
        }
        return OutcomeStateReplayCompatibilityAudit(
            syntheticAbilityMappings = admittedMappings.values.map { lifecycle ->
                OutcomeStateSyntheticAbilityMappingAudit(
                    creationRawOrdinal = lifecycle.creationRawOrdinal,
                    retirementRawOrdinal = requireNotNull(lifecycle.retirementRawOrdinal),
                    stackEntityId = lifecycle.mapping.stackEntityId.value,
                    normalizedStatePath = lifecycle.mapping.statePath,
                )
            }.sortedWith(
                compareBy<OutcomeStateSyntheticAbilityMappingAudit> { it.creationRawOrdinal }
                    .thenBy { it.stackEntityId }
            ),
            legacyTimeLordTypeLineNormalizations = admittedLegacyTypeLines.values
                .map { lifecycle ->
                    OutcomeStateLegacyTypeLineNormalizationAudit(
                        rawOrdinal = lifecycle.creationRawOrdinal,
                        eventIndex = lifecycle.eventIndex,
                        entityId = lifecycle.entityId.value,
                        retirementRawOrdinal = lifecycle.retirementRawOrdinal,
                    )
                }.sortedWith(
                    compareBy<OutcomeStateLegacyTypeLineNormalizationAudit> { it.rawOrdinal }
                        .thenBy { it.eventIndex }
                        .thenBy { it.entityId }
                ),
        )
    }

    fun safeInspectionBundleDifference(
        bundle: PolicyInspectionBundle,
    ): RecordedReplayStateDifference? {
        require(finalValidated) { "Safe inspection bundle checked before final state validation" }
        require(!safeInspectionBundleValidated) { "Safe inspection bundle was already validated" }
        val artifact = PolicyJson.format.encodeToJsonElement(PolicyInspectionBundle.serializer(), bundle)
        for (mapping in admittedMappings.values.map { it.mapping }) {
            for (id in listOf(mapping.expectedAbilityId.value, mapping.actualAbilityId.value)) {
                val path = stringOccurrencePaths(artifact, id).firstOrNull() ?: continue
                return RecordedReplayStateDifference(
                    boundary = "safe-inspection-bundle",
                    path = path,
                    expected = jsonString(mapping.expectedAbilityId.value),
                    actual = jsonString(mapping.actualAbilityId.value),
                    reason = "privileged synthetic ability id appears in the derived safe inspection bundle",
                )
            }
        }
        safeInspectionBundleValidated = true
        return null
    }

    private fun rememberState(state: GameState) {
        collectArtifactStrings(ReplayCanonicalJson.state(state), priorArtifactStrings)
    }

    private fun rememberTransitionArtifacts(
        expectedAction: GameAction,
        actualAction: GameAction,
        expectedEvents: List<GameEvent>,
        actualEvents: List<GameEvent>,
    ) {
        listOf(expectedAction, actualAction).forEach { action ->
            collectArtifactStrings(
                CanonicalReplayJson.encodeToJsonElement(GameAction.serializer(), action),
                priorArtifactStrings,
            )
        }
        listOf(expectedEvents, actualEvents).forEach { events ->
            events.forEach { event ->
                collectArtifactStrings(
                    CanonicalReplayJson.encodeToJsonElement(GameEvent.serializer(), event),
                    priorArtifactStrings,
                )
            }
        }
    }

    private data class MappingDiscovery(
        val mappings: List<SyntheticDelayedAbilityMapping> = emptyList(),
        val difference: RecordedReplayStateDifference? = null,
    )

    private fun discoverMappings(
        expectedEvents: List<GameEvent>,
        actualEvents: List<GameEvent>,
        expectedBefore: GameState,
        actualBefore: GameState,
        expectedAfter: GameState,
        actualAfter: GameState,
    ): MappingDiscovery {
        val expectedTriggered = expectedEvents.filterIsInstance<AbilityTriggeredEvent>()
        val actualTriggered = actualEvents.filterIsInstance<AbilityTriggeredEvent>()
        if (expectedTriggered.size != actualTriggered.size) return MappingDiscovery()

        val candidates = expectedTriggered.zip(actualTriggered).mapNotNull { (expectedEvent, actualEvent) ->
            if (expectedEvent != actualEvent) return@mapNotNull null
            val entityId = expectedEvent.abilityEntityId ?: return@mapNotNull null
            if (expectedBefore.getEntity(entityId) != null || actualBefore.getEntity(entityId) != null) {
                return@mapNotNull null
            }
            val expectedContainer = expectedAfter.getEntity(entityId) ?: return@mapNotNull null
            val actualContainer = actualAfter.getEntity(entityId) ?: return@mapNotNull null
            if (expectedContainer.all().size != 1 || actualContainer.all().size != 1 ||
                expectedAfter.stack.count { it == entityId } != 1 ||
                actualAfter.stack.count { it == entityId } != 1
            ) {
                return@mapNotNull null
            }
            val expectedComponent = expectedContainer.get<TriggeredAbilityOnStackComponent>()
                ?: return@mapNotNull null
            val actualComponent = actualContainer.get<TriggeredAbilityOnStackComponent>()
                ?: return@mapNotNull null
            val expectedIdentity = expectedComponent.abilityIdentity ?: return@mapNotNull null
            val actualIdentity = actualComponent.abilityIdentity ?: return@mapNotNull null
            if (expectedIdentity.abilityId == actualIdentity.abilityId) return@mapNotNull null
            if (expectedComponent != actualComponent.copy(
                    abilityIdentity = actualIdentity.copy(abilityId = expectedIdentity.abilityId),
                )
            ) {
                return@mapNotNull null
            }
            SyntheticDelayedAbilityMapping(entityId, expectedIdentity.abilityId, actualIdentity.abilityId) to
                Triple(expectedEvent, expectedComponent, actualComponent)
        }
        if (candidates.isEmpty()) return MappingDiscovery()

        val expectedConsumed = consumedDelayedTriggers(expectedBefore, expectedAfter)
        val actualConsumed = consumedDelayedTriggers(actualBefore, actualAfter)
        if (expectedConsumed.size != actualConsumed.size) {
            return mappingRefusal(
                candidates.first().first,
                "consumed delayed-trigger counts differ: expected=${expectedConsumed.size}, actual=${actualConsumed.size}",
            )
        }
        val consumedPairs = expectedConsumed.zip(actualConsumed)
        consumedPairs.forEachIndexed { index, (expected, actual) ->
            if (expected != actual.copy(id = expected.id)) {
                return mappingRefusal(
                    candidates.first().first,
                    "consumed delayed trigger $index differs beyond its established routing id",
                )
            }
        }

        val usedConsumed = mutableSetOf<Int>()
        candidates.forEach { (mapping, payload) ->
            val (event, expectedComponent, actualComponent) = payload
            val matches = consumedPairs.indices.filter { index ->
                index !in usedConsumed &&
                    matchesConsumedStepDelayedTrigger(
                        delayed = consumedPairs[index].first,
                        component = expectedComponent,
                        event = event,
                        after = expectedAfter,
                    ) &&
                    matchesConsumedStepDelayedTrigger(
                        delayed = consumedPairs[index].second,
                        component = actualComponent,
                        event = event,
                        after = actualAfter,
                    )
            }
            if (matches.size != 1) {
                return mappingRefusal(
                    mapping,
                    "new stack ability is not uniquely correlated to one equal consumed step-delayed trigger",
                )
            }
            usedConsumed += matches.single()
        }
        return MappingDiscovery(mappings = candidates.map { it.first })
    }

    private fun consumedDelayedTriggers(before: GameState, after: GameState): List<DelayedTriggeredAbility> =
        before.delayedTriggers.filter { delayed -> after.delayedTriggers.none { it.id == delayed.id } }

    private fun matchesConsumedStepDelayedTrigger(
        delayed: DelayedTriggeredAbility,
        component: TriggeredAbilityOnStackComponent,
        event: AbilityTriggeredEvent,
        after: GameState,
    ): Boolean {
        if (delayed.trigger != null || delayed.fireAtStep != Step.END ||
            delayed.repeatAtEachMatchingStep || delayed.targetRequirement != null ||
            delayed.additionalTargetRequirements.isNotEmpty()
        ) {
            return false
        }
        val cardDefinitionId = after.getEntity(delayed.sourceId)
            ?.get<CardComponent>()?.cardDefinitionId ?: return false
        val identity = component.abilityIdentity ?: return false
        val notBeforeTurn = delayed.notBeforeTurn
        val generatedAbility = TriggeredAbility(
            id = identity.abilityId,
            trigger = EventPattern.StepEvent(Step.END, Player.Each),
            binding = TriggerBinding.ANY,
            effect = delayed.effect,
        )
        val generatedComponent = TriggeredAbilityOnStackComponent(
            sourceId = delayed.sourceId,
            sourceName = delayed.sourceName,
            controllerId = delayed.controllerId,
            effect = delayed.effect,
            description = generatedAbility.description,
            abilityIdentity = AbilityIdentity(cardDefinitionId, identity.abilityId),
            triggeringEntityId = delayed.fireOnPlayerId,
            triggeringPlayerId = delayed.fireOnPlayerId,
        )
        return delayed.fireAtStep == after.step &&
            (delayed.fireOnPlayerId == null || delayed.fireOnPlayerId == after.activePlayerId) &&
            (notBeforeTurn == null || after.turnNumber >= notBeforeTurn) &&
            component == generatedComponent &&
            event.abilityEntityId != null &&
            event.sourceId == component.sourceId &&
            event.sourceName == component.sourceName &&
            event.controllerId == component.controllerId &&
            event.description == component.description
    }

    private fun mappingRefusal(
        mapping: SyntheticDelayedAbilityMapping,
        reason: String,
    ): MappingDiscovery = MappingDiscovery(
        difference = RecordedReplayStateDifference(
            boundary = "after",
            path = mapping.statePath,
            expected = jsonString(mapping.expectedAbilityId.value),
            actual = jsonString(mapping.actualAbilityId.value),
            reason = reason,
        )
    )

    private fun transitionArtifactDifference(
        expectedAction: GameAction,
        actualAction: GameAction,
        expectedEvents: List<GameEvent>,
        actualEvents: List<GameEvent>,
    ): RecordedReplayStateDifference? {
        val artifacts = listOf(
            "expected action" to CanonicalReplayJson.encodeToJsonElement(GameAction.serializer(), expectedAction),
            "actual action" to CanonicalReplayJson.encodeToJsonElement(GameAction.serializer(), actualAction),
            "expected events" to JsonArray(expectedEvents.map {
                CanonicalReplayJson.encodeToJsonElement(GameEvent.serializer(), it)
            }),
            "actual events" to JsonArray(actualEvents.map {
                CanonicalReplayJson.encodeToJsonElement(GameEvent.serializer(), it)
            }),
        )
        for (mapping in admittedMappings.values.map { it.mapping }) {
            for (id in listOf(mapping.expectedAbilityId.value, mapping.actualAbilityId.value)) {
                for ((label, artifact) in artifacts) {
                    val path = stringOccurrencePaths(artifact, id).firstOrNull() ?: continue
                    return RecordedReplayStateDifference(
                        boundary = "transition",
                        path = "/$label$path",
                        expected = jsonString(mapping.expectedAbilityId.value),
                        actual = jsonString(mapping.actualAbilityId.value),
                        reason = "mapped synthetic ability id appears in an action or event",
                    )
                }
            }
        }
        return null
    }

    private fun stateDifference(
        boundary: String,
        expected: GameState,
        actual: GameState,
    ): RecordedReplayStateDifference? {
        admittedMappings.values.map { it.mapping }.forEach { mapping ->
            val expectedJson = ReplayCanonicalJson.state(expected)
            val actualJson = ReplayCanonicalJson.state(actual)
            val expectedOwn = stringOccurrencePaths(expectedJson, mapping.expectedAbilityId.value)
            val actualOwn = stringOccurrencePaths(actualJson, mapping.actualAbilityId.value)
            val expectedCross = stringOccurrencePaths(expectedJson, mapping.actualAbilityId.value)
            val actualCross = stringOccurrencePaths(actualJson, mapping.expectedAbilityId.value)
            val expectedStackExists = expected.getEntity(mapping.stackEntityId)
                ?.get<TriggeredAbilityOnStackComponent>() != null
            val actualStackExists = actual.getEntity(mapping.stackEntityId)
                ?.get<TriggeredAbilityOnStackComponent>() != null
            val expectedAllowed = if (expectedStackExists && actualStackExists) listOf(mapping.statePath) else emptyList()
            val actualAllowed = if (expectedStackExists && actualStackExists) listOf(mapping.statePath) else emptyList()
            if (expectedOwn != expectedAllowed || actualOwn != actualAllowed) {
                val path = (expectedOwn + actualOwn).firstOrNull { it != mapping.statePath } ?: mapping.statePath
                return RecordedReplayStateDifference(
                    boundary = boundary,
                    path = path,
                    expected = jsonString(mapping.expectedAbilityId.value),
                    actual = jsonString(mapping.actualAbilityId.value),
                    reason = if (expectedStackExists != actualStackExists) {
                        "correlated synthetic ability stack component exists on only one side"
                    } else {
                        "mapped synthetic ability id is absent from or occurs outside its correlated stack component"
                    },
                )
            }
            if (expectedCross.isNotEmpty() || actualCross.isNotEmpty()) {
                return RecordedReplayStateDifference(
                    boundary = boundary,
                    path = (expectedCross + actualCross).first(),
                    expected = jsonString(mapping.expectedAbilityId.value),
                    actual = jsonString(mapping.actualAbilityId.value),
                    reason = "synthetic ability mapping collides with another state identity",
                )
            }
        }

        val expectedJson = ReplayCanonicalJson.state(expected)
        val actualJson = ReplayCanonicalJson.state(actual)
        val expectedTypeLinePaths = legacyTimeLordTypeLinePaths(expectedJson).sorted()
        val actualTypeLinePaths = legacyTimeLordTypeLinePaths(actualJson).sorted()
        val allowedTypeLinePaths = mutableListOf<String>()
        admittedLegacyTypeLines.values.filter { it.retirementRawOrdinal == null }
            .forEach { lifecycle ->
                val expectedSnapshot = expected.getEntity(lifecycle.entityId)
                    ?.get<LastKnownPermanentComponent>()?.snapshot
                val actualSnapshot = actual.getEntity(lifecycle.entityId)
                    ?.get<LastKnownPermanentComponent>()?.snapshot
                if ((expectedSnapshot == null) != (actualSnapshot == null)) {
                    return RecordedReplayStateDifference(
                        boundary = boundary,
                        path = lifecycle.statePath,
                        expected = expectedSnapshot?.typeLine?.toString(),
                        actual = actualSnapshot?.typeLine?.toString(),
                        reason = "correlated legacy TypeLine snapshot exists on only one side",
                    )
                }
                if (expectedSnapshot != null && actualSnapshot != null) {
                    if (!fixedArgentumTimeLordSnapshotRoundTripEquals(
                            expectedSnapshot,
                            actualSnapshot,
                        )
                    ) {
                        return RecordedReplayStateDifference(
                            boundary = boundary,
                            path = lifecycle.statePath,
                            expected = expectedSnapshot.typeLine?.toString(),
                            actual = actualSnapshot.typeLine?.toString(),
                            reason = "correlated legacy TypeLine snapshot differs beyond Time Lord splitting",
                        )
                    }
                    allowedTypeLinePaths += lifecycle.statePath
                }
            }
        allowedTypeLinePaths.sort()
        if (expectedTypeLinePaths != allowedTypeLinePaths ||
            actualTypeLinePaths != allowedTypeLinePaths
        ) {
            return RecordedReplayStateDifference(
                boundary = boundary,
                path = (expectedTypeLinePaths + actualTypeLinePaths + allowedTypeLinePaths)
                    .firstOrNull { it !in allowedTypeLinePaths } ?: "/",
                expected = expectedTypeLinePaths.toString(),
                actual = actualTypeLinePaths.toString(),
                reason = "Time Lord TypeLine occurs outside a correlated normalized leave snapshot",
            )
        }

        val routingAdjustedActual = admittedMappings.values.map { it.mapping }.fold(actual) { state, mapping ->
            val container = state.getEntity(mapping.stackEntityId) ?: return@fold state
            val component = container.get<TriggeredAbilityOnStackComponent>() ?: return@fold state
            val identity = component.abilityIdentity ?: return@fold state
            if (identity.abilityId != mapping.actualAbilityId) return@fold state
            state.copy(
                entities = state.entities + (
                    mapping.stackEntityId to container.with(
                        component.copy(
                            abilityIdentity = identity.copy(abilityId = mapping.expectedAbilityId),
                        )
                    )
                )
            )
        }
        val adjustedActual = admittedLegacyTypeLines.values
            .filter { it.retirementRawOrdinal == null }
            .fold(routingAdjustedActual) { state, lifecycle ->
                val expectedTypeLine = expected.getEntity(lifecycle.entityId)
                    ?.get<LastKnownPermanentComponent>()?.snapshot?.typeLine ?: return@fold state
                val container = state.getEntity(lifecycle.entityId) ?: return@fold state
                val component = container.get<LastKnownPermanentComponent>() ?: return@fold state
                state.copy(
                    entities = state.entities + (
                        lifecycle.entityId to container.with(
                            component.copy(snapshot = component.snapshot.copy(typeLine = expectedTypeLine))
                        )
                    )
                )
            }
        val difference = ArgentumStateFingerprint.firstRoutingNormalizedDifference(expected, adjustedActual)
            ?: return null
        return RecordedReplayStateDifference(
            boundary = boundary,
            path = difference.path,
            expected = difference.expected,
            actual = difference.actual,
            reason = "routing-normalized full states differ",
        )
    }
}

private fun stringOccurrencePaths(
    element: JsonElement,
    value: String,
    path: String = "",
): List<String> = when (element) {
    is JsonArray -> element.flatMapIndexed { index, child ->
        stringOccurrencePaths(child, value, "$path/$index")
    }
    is JsonObject -> element.entries.flatMap { (key, child) ->
        buildList {
            if (key == value) add("$path/${jsonPointerSegment(key)}#key")
            addAll(stringOccurrencePaths(child, value, "$path/${jsonPointerSegment(key)}"))
        }
    }
    is JsonPrimitive -> if (element.content == value) listOf(path.ifEmpty { "/" }) else emptyList()
}

private fun legacyTimeLordTypeLinePaths(
    element: JsonElement,
    path: String = "",
): List<String> = when (element) {
    is JsonArray -> element.flatMapIndexed { index, child ->
        legacyTimeLordTypeLinePaths(child, "$path/$index")
    }
    is JsonObject -> element.entries.flatMap { (key, child) ->
        legacyTimeLordTypeLinePaths(child, "$path/${jsonPointerSegment(key)}")
    }
    is JsonPrimitive -> if (path.endsWith("/typeLine") && "Time Lord" in element.content) {
        listOf(path.ifEmpty { "/" })
    } else {
        emptyList()
    }
}

private fun collectArtifactStrings(element: JsonElement, destination: MutableSet<String>) {
    when (element) {
        is JsonArray -> element.forEach { collectArtifactStrings(it, destination) }
        is JsonObject -> element.forEach { (key, child) ->
            destination += key
            collectArtifactStrings(child, destination)
        }
        is JsonPrimitive -> destination += element.content
    }
}

private fun jsonPointerSegment(value: String): String = value.replace("~", "~0").replace("/", "~1")

private fun jsonString(value: String): String = JsonPrimitive(value).toString()

/**
 * Re-executes semantic choices from declared seeds, checks every full authoritative state against
 * the canonical record, then retains only the root perspective through the ordinary safe projector.
 */
internal class CanonicalOutcomeStateProjector(
    private val worldFactory: SemanticReplayWorldFactory,
    private val actionSpaceProfile: SearchActionSpaceProfile =
        SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
) {
    fun project(
        replayPath: Path,
        expectation: OutcomeStateReplayExpectation,
        inspection: OutcomeStateInspectionIdentity,
    ): ProjectedOutcomeStateGame {
        val historicalAuthority = historicalProjectionAuthority()
        require(expectation.parentRunIdentity == OUTCOME_STATE_CORPUS_PARENT_IDENTITY)
        require(expectation.historicalOuterCommit == historicalAuthority.outerCommit)
        require(expectation.argentumCommit == historicalAuthority.argentumCommit)
        require(sha256File(replayPath) == expectation.parentReplaySha256) {
            "Parent replay SHA-256 changed for ${expectation.historicalGameId}"
        }
        val replay = readVerifiedCanonicalSemanticReplay(replayPath)
        val header = replay.header
        require(header.gameId == expectation.historicalGameId)
        require(header.engineVersion == expectation.argentumCommit)
        require(header.players == listOf("p0", "p1"))
        require(header.requireExtensionString("mtgallium.runIdentity") == expectation.parentRunIdentity)
        require(header.requireExtensionString("mtgallium.outerCommit") == expectation.historicalOuterCommit)
        require(header.requireExtensionString("mtgallium.argentumCommit") == expectation.argentumCommit)
        require(header.requireExtensionString("mtgallium.deckHash") == expectation.deckHash)
        require(header.requireExtensionString("mtgallium.cardPoolHash") == expectation.cardPoolHash)
        val gameSeed = header.requireExtensionLong("mtgallium.gameSeed")
        val searchBaseSeed = header.requireExtensionLong("mtgallium.baseSeed")
        require(gameSeed == expectation.historicalGameSeed)
        require(replay.terminal.winnerId == expectation.expectedWinnerId)

        val world = worldFactory.create(
            SemanticReplaySetup(
                gameId = header.gameId,
                gameSeed = gameSeed,
                searchBaseSeed = searchBaseSeed,
                startingPlayerIndex = 0,
                actionSpaceProfile = actionSpaceProfile,
            )
        )
        val stateEquivalence = RecordedReplayStateEquivalence(historicalAuthority)
        val rawTransitionCount = replay.decisions.sumOf { it.transitions.size }
        requireNoStateDifference(
            difference = stateEquivalence.initialDifference(
                expected = replay.states.first(),
                actual = world.authoritativeState(),
            ),
            gameId = header.gameId,
            semanticDecisionIndex = -1,
            rawOrdinal = -1,
        )

        val derivedBundleId = outcomeStateBundleId(expectation.parentReplaySha256, expectation.rootPlayerId)
        val recorder = PolicyInspectionRecorder(
            gameId = derivedBundleId,
            createdAtUtc = header.createdAtUtc,
            outerCommit = inspection.producerOuterCommit,
            argentumCommit = expectation.argentumCommit,
            deckManifestHash = expectation.deckHash,
            cardPoolHash = expectation.cardPoolHash,
            profileManifestHash = expectation.profileManifestHash,
            perspectivePlayerId = expectation.rootPlayerId,
            policyVersion = inspection.controlPolicyEvidenceIdentity,
            evaluatorVersion = inspection.evaluatorVersion,
            beliefVersion = inspection.beliefVersion,
            opponentModelVersion = inspection.opponentModelVersion,
            runtimeLeaf = null,
            runtimeBeliefMode = null,
            runtimeBeliefArchitecture = null,
        )
        recorder.recordInitial(world.informationState(expectation.rootPlayerId))

        replay.decisions.forEach { decision ->
            val actor = requireNotNull(world.actorToAct()) {
                "Canonical semantic decision ${decision.decisionIndex} has no current actor"
            }
            val exact = world.expandChoices().singleOrNull { it.signature == decision.choice.signature }
                ?: error("Canonical semantic choice ${decision.decisionIndex} is no longer legal")
            require(exact == decision.choice) {
                "Canonical semantic choice ${decision.decisionIndex} changed meaning under current projection source"
            }
            val applied = world.stepWithReplayTrace(exact)
            require(applied.result.accepted) {
                "Canonical semantic choice ${decision.decisionIndex} was rejected: ${applied.result.diagnostic}"
            }
            require(applied.rawTransitions.size == decision.transitions.size) {
                "Raw transition count changed at semantic decision ${decision.decisionIndex}"
            }
            applied.rawTransitions.zip(decision.transitions).forEach { (actual, expected) ->
                requireRawTransitionMatch(
                    actual = actual,
                    expected = expected,
                    canonicalStates = replay.states,
                    gameId = header.gameId,
                    semanticDecisionIndex = decision.decisionIndex,
                    stateEquivalence = stateEquivalence,
                )
            }
            recorder.recordTransition(
                decisionIndex = decision.decisionIndex,
                actorId = actor,
                actualChoice = exact,
                privateToActor = applied.result.privateToActor,
                informationAfter = world.informationState(expectation.rootPlayerId),
            )
        }

        requireNoStateDifference(
            difference = stateEquivalence.finalDifference(
                expected = replay.states.last(),
                actual = world.authoritativeState(),
                rawTransitionCount = rawTransitionCount,
            ),
            gameId = header.gameId,
            semanticDecisionIndex = replay.decisions.last().decisionIndex,
            rawOrdinal = rawTransitionCount,
        )
        val rootPayoff = requireNotNull(world.terminalPayoff(expectation.rootPlayerId)) {
            "Complete canonical replay did not reconstruct an engine terminal payoff"
        }
        val expectedPayoff = when (expectation.expectedWinnerId) {
            expectation.rootPlayerId -> 1.0
            null -> 0.0
            else -> -1.0
        }
        require(rootPayoff == expectedPayoff)
        val resultByPlayer = mapOf(
            "p0" to if (expectation.expectedWinnerId == "p0") 1.0 else if (expectation.expectedWinnerId == null) 0.0 else -1.0,
            "p1" to if (expectation.expectedWinnerId == "p1") 1.0 else if (expectation.expectedWinnerId == null) 0.0 else -1.0,
        )
        val bundle = recorder.finish(
            PolicyInspectionOutcome(
                decisions = replay.decisions.size,
                terminated = true,
                truncated = false,
                winnerId = expectation.expectedWinnerId,
                resultByPlayer = resultByPlayer,
            )
        )
        requireNoStateDifference(
            difference = stateEquivalence.safeInspectionBundleDifference(bundle),
            gameId = header.gameId,
            semanticDecisionIndex = replay.decisions.last().decisionIndex,
            rawOrdinal = rawTransitionCount,
        )
        val decisionFrames = bundle.frames.filter { !it.terminated }
        require(decisionFrames.all { it.actingPlayerId != null })
        require(decisionFrames.all {
            it.observation.currentTurnStateComplete && it.knowledge.epistemicallyComplete
        }) { "Derived corpus requires complete current-turn and exact knowledge state" }
        require(decisionFrames.all { it.winnerId == null }) {
            "Actual outcome appeared before the terminal completion record"
        }
        require(bundle.frames.all { it.search == null }) {
            "Replay-derived state corpus must not copy search/planner evidence"
        }
        require(decisionFrames.all { it.actingPlayerId in setOf("p0", "p1") }) {
            "Replay ${header.gameId} retained an actor outside the policy player aliases"
        }
        val rootActorStates = decisionFrames.count { it.actingPlayerId == expectation.rootPlayerId }
        val opponentActorStates = decisionFrames.size - rootActorStates
        require(rootActorStates > 0 && opponentActorStates > 0) {
            "Replay ${header.gameId} does not cover both learned-value actor relations"
        }
        val privateOpponentResponses = bundle.frames.mapNotNull { it.transition }
            .count { it.privateResponse && it.actorId != expectation.rootPlayerId && it.observedChoice == null }
        bundle.frames.indices.forEach { index ->
            val state = bundle.informationState(index)
            require(state.observation.perspectivePlayerId == expectation.rootPlayerId)
            require(state.knowledge.perspectivePlayerId == expectation.rootPlayerId)
        }
        return ProjectedOutcomeStateGame(
            bundle = bundle,
            historicalSearchBaseSeed = searchBaseSeed,
            parentReplayTerminalRecordDigest = replay.terminal.recordDigest,
            semanticDecisions = replay.decisions.size,
            rawTransitions = rawTransitionCount,
            replayCompatibilityAudit = stateEquivalence.completedAudit(),
            decisionBoundaryStates = decisionFrames.size,
            rootActorStates = rootActorStates,
            opponentActorStates = opponentActorStates,
            privateOpponentResponses = privateOpponentResponses,
            actualTerminalPayoff = rootPayoff,
        )
    }

    private fun requireRawTransitionMatch(
        actual: ArgentumRawTransition,
        expected: CanonicalReplayTransition,
        canonicalStates: List<GameState>,
        gameId: String,
        semanticDecisionIndex: Int,
        stateEquivalence: RecordedReplayStateEquivalence,
    ) {
        // Current Argentum exposes each genuine choice as an explicit submitted transition.
        // Historical auto-origin records are not silently reclassified as current policy actions.
        require(expected.origin == ReplayTransitionOrigin.POLICY)
        require(recordedReplayActionEquals(requireNotNull(expected.action), actual.action))
        require(expected.actorId == actual.action.playerId.value)
        require(expected.submitterId == actual.action.playerId.value)
        require(expected.accepted == actual.accepted && expected.rejectionReason == actual.rejectionReason)
        val eventComparison = recordedReplayEventComparison(
            expectedEvents = expected.events,
            actualEvents = actual.events,
            expectedAction = requireNotNull(expected.action),
            actualAction = actual.action,
            expectedBefore = canonicalStates[expected.ordinal],
            actualBefore = actual.beforeState,
            expectedAfter = canonicalStates[expected.ordinal + 1],
            actualAfter = actual.afterState,
        )
        require(eventComparison.difference == null) {
            val difference = requireNotNull(eventComparison.difference)
            "Canonical replay event mismatch: gameId=$gameId, " +
                "semanticDecision=$semanticDecisionIndex, rawOrdinal=${expected.ordinal}, " +
                "eventIndex=${difference.index}, reason=${difference.reason}, " +
                "expectedType=${difference.expected?.let { it::class.simpleName } ?: "<missing>"}, " +
                "actualType=${difference.actual?.let { it::class.simpleName } ?: "<missing>"}, " +
                "expected=${canonicalReplayEventJson(difference.expected)}, " +
                "actual=${canonicalReplayEventJson(difference.actual)}"
        }
        requireNoStateDifference(
            difference = stateEquivalence.transitionDifference(
                expectedAction = requireNotNull(expected.action),
                actualAction = actual.action,
                expectedEvents = expected.events,
                actualEvents = actual.events,
                expectedBefore = canonicalStates[expected.ordinal],
                actualBefore = actual.beforeState,
                expectedAfter = canonicalStates[expected.ordinal + 1],
                actualAfter = actual.afterState,
                expectedAccepted = expected.accepted,
                actualAccepted = actual.accepted,
                rawOrdinal = expected.ordinal,
                legacyTimeLordTypeLineNormalizations =
                    eventComparison.legacyTimeLordTypeLineNormalizations,
            ),
            gameId = gameId,
            semanticDecisionIndex = semanticDecisionIndex,
            rawOrdinal = expected.ordinal,
        )
    }

    private fun requireNoStateDifference(
        difference: RecordedReplayStateDifference?,
        gameId: String,
        semanticDecisionIndex: Int,
        rawOrdinal: Int,
    ) {
        require(difference == null) {
            val mismatch = requireNotNull(difference)
            "Canonical replay state mismatch: gameId=$gameId, " +
                "semanticDecision=$semanticDecisionIndex, rawOrdinal=$rawOrdinal, " +
                "boundary=${mismatch.boundary}, path=${mismatch.path}, reason=${mismatch.reason}, " +
                "expected=${mismatch.expected ?: "<missing>"}, actual=${mismatch.actual ?: "<missing>"}"
        }
    }
}

internal fun outcomeStateBundleId(parentReplaySha256: String, rootPlayerId: String): String {
    require(parentReplaySha256.matches(lowerSha256) && rootPlayerId in setOf("p0", "p1"))
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest("outcome-state-bundle-v4\u0000$parentReplaySha256\u0000$rootPlayerId".toByteArray())
        .copyOf(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val high = bytes.take(8).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xff) }
    val low = bytes.drop(8).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xff) }
    return UUID(high, low).toString()
}

@Serializable
internal data class OutcomeStateInputGameInventory(
    val historicalGameId: String,
    val p0PolicyId: String,
    val p1PolicyId: String,
    val replaySha256: String,
) {
    init {
        require(historicalGameId.isNotBlank())
        require(p0PolicyId.isNotBlank() && p1PolicyId.isNotBlank())
        require(replaySha256.matches(lowerSha256))
    }
}

@Serializable
internal data class OutcomeStateInputPairInventory(
    val pairIndex: Int,
    val pairSeed: Long,
    val checkpointPayloadSha256: String,
    val games: List<OutcomeStateInputGameInventory>,
) {
    init {
        require(pairIndex >= 0)
        require(checkpointPayloadSha256.matches(lowerSha256))
        require(games.size == 2 && games.map { it.historicalGameId }.distinct().size == 2)
    }
}

internal data class OutcomeStateHistoricalGameInput(
    val pairIndex: Int,
    val leg: String,
    val game: GameRunResult,
    val rootPlayerId: String,
    val replayPath: Path,
    val replayReference: String,
    val replaySha256: String,
)

internal data class OutcomeStateHistoricalPairInput(
    val pairIndex: Int,
    val seed: Long,
    val checkpointPayloadSha256: String,
    val games: List<OutcomeStateHistoricalGameInput>,
)

private data class PreparedOutcomeStateInputs(
    val parentArtifactManifestSha256: String,
    val deckHash: String,
    val cardPoolHash: String,
    val inventory: List<OutcomeStateInputPairInventory>,
    val inputInventorySha256: String,
    val pairs: List<OutcomeStateHistoricalPairInput>,
)

@Serializable
internal data class OutcomeStatePairPreflightGame(
    val historicalGameId: String,
    val leg: String,
    val rootPlayerId: String,
    val parentReplaySha256: String,
    val parentReplayTerminalRecordDigest: String,
    val derivedBundleId: String,
    val historicalCreatedAtUtc: String,
    val semanticDecisions: Int,
    val rawTransitions: Int,
    val replayCompatibilityAudit: OutcomeStateReplayCompatibilityAudit,
    val decisionBoundaryStates: Int,
    val rootActorStates: Int,
    val opponentActorStates: Int,
    val privateOpponentResponses: Int,
    val actualTerminalPayoff: Double,
)

@Serializable
internal data class OutcomeStatePairPreflightReport(
    val schemaVersion: Int = 4,
    val protocol: String = "$OUTCOME_STATE_CORPUS_PROTOCOL-preflight",
    val generatedAtUtc: String,
    val parentRunIdentity: String,
    val parentArtifactManifestSha256: String,
    val parentCheckpointPayloadSha256: String,
    val pairIndex: Int,
    val split: OutcomeStateCorpusSplit,
    val historicalProjection: OutcomeStateProjectionAuthority,
    val producer: OutcomeStateProducerAuthority,
    val trainingProjection: OutcomeStateTrainingProjectionAuthority,
    val games: List<OutcomeStatePairPreflightGame>,
) {
    init {
        require(schemaVersion == 4 && protocol == "$OUTCOME_STATE_CORPUS_PROTOCOL-preflight")
        require(parentRunIdentity == OUTCOME_STATE_CORPUS_PARENT_IDENTITY)
        require(parentArtifactManifestSha256.matches(lowerSha256))
        require(parentCheckpointPayloadSha256.matches(lowerSha256))
        require(pairIndex in SEARCH_BUDGET_FRONTIER_EXTENSION_START until
            SEARCH_BUDGET_FRONTIER_EXTENSION_START + SEARCH_BUDGET_FRONTIER_EXTENSION_PAIRS)
        require(games.size == 2 && games.map { it.leg }.toSet() == setOf("a", "b"))
        require(games.all { it.semanticDecisions > 0 && it.rawTransitions >= it.semanticDecisions })
        require(games.all {
            it.replayCompatibilityAudit.algorithm == OUTCOME_STATE_CORPUS_TRANSITION_STATE_EQUIVALENCE
        })
        games.forEach { it.replayCompatibilityAudit.requireForRawTransitionCount(it.rawTransitions) }
        require(games.all { it.rootActorStates > 0 && it.opponentActorStates > 0 })
        require(trainingProjection.historicalProjection == historicalProjection)
        require(trainingProjection.currentProducer == producer)
        trainingProjection.compatibility.requireAuthorities(historicalProjection, producer.projection)
    }
}

@Serializable
private data class OutcomeStatePairCheckpoint(
    val schemaVersion: Int = 4,
    val pairIndex: Int,
    val split: OutcomeStateCorpusSplit,
    val parentCheckpointPayloadSha256: String,
    val games: List<OutcomeStateGameArtifact>,
) {
    init {
        require(schemaVersion == 4)
        require(pairIndex >= 0)
        require(parentCheckpointPayloadSha256.matches(lowerSha256))
        require(games.size == 2 && games.all { it.pairIndex == pairIndex && it.split == split })
    }
}

/**
 * Fixed retained-evidence derivation. It writes resumable work only to a sibling staging directory
 * and publishes the final corpus directory in one move after every pair and artifact verifies.
 */
internal class OutcomeStateCorpusProducer(
    private val root: Path,
    registry: CardRegistry,
    private val manifest: DeckManifest,
    private val projector: CanonicalOutcomeStateProjector = CanonicalOutcomeStateProjector(
        ArgentumSemanticReplayWorldFactory(registry, manifest),
    ),
) {
    fun preflightPair(
        parentArtifactManifest: Path,
        generatedAtUtc: String = Instant.now().toString(),
    ): OutcomeStatePairPreflightReport {
        // Preflight is deliberately fixed before any held-out partition can be decoded. Pair 50
        // is a TRAIN pair under the bound outcome-blind split; callers cannot select a witness.
        val pairIndex = SEARCH_BUDGET_FRONTIER_EXTENSION_START
        val split = OutcomeStateCorpusSplitBinding.create(
            pairIndices = (SEARCH_BUDGET_FRONTIER_EXTENSION_START until
                SEARCH_BUDGET_FRONTIER_EXTENSION_START + SEARCH_BUDGET_FRONTIER_EXTENSION_PAIRS).toList(),
            parentRunIdentity = OUTCOME_STATE_CORPUS_PARENT_IDENTITY,
            trainPairs = 70,
            validationPairs = 15,
            testPairs = 15,
        ).splitFor(pairIndex)
        require(split == OutcomeStateCorpusSplit.TRAIN) {
            "Outcome-state preflight witness must remain in TRAIN"
        }
        val prepared = prepareInputs(
            sourceDirectory = requireNotNull(parentArtifactManifest.parent).toAbsolutePath().normalize(),
            parentArtifactManifest = parentArtifactManifest,
            pairIndices = listOf(pairIndex),
        )
        val producerAuthority = captureProducerAuthority(root)
        val historicalProjection = historicalProjectionAuthority()
        val trainingProjection = OutcomeStateTrainingProjectionAuthority(
            historicalProjection = historicalProjection,
            compatibility = captureProjectionCompatibility(root, historicalProjection, producerAuthority),
            currentProducer = producerAuthority,
        )
        val inspection = OutcomeStateInspectionIdentity(
            producerOuterCommit = producerAuthority.projection.outerCommit,
            controlPolicyEvidenceIdentity = OUTCOME_STATE_CORPUS_CONTROL_POLICY_EVIDENCE_ID,
        )
        val input = prepared.pairs.single()
        val games = input.games.map { game ->
            val result = projectHistoricalGame(
                input = input,
                game = game,
                split = split,
                inspection = inspection,
                deckHash = prepared.deckHash,
                cardPoolHash = prepared.cardPoolHash,
            )
            OutcomeStatePairPreflightGame(
                historicalGameId = game.game.gameId,
                leg = game.leg,
                rootPlayerId = game.rootPlayerId,
                parentReplaySha256 = game.replaySha256,
                parentReplayTerminalRecordDigest = result.parentReplayTerminalRecordDigest,
                derivedBundleId = result.bundle.gameId,
                historicalCreatedAtUtc = result.bundle.createdAtUtc,
                semanticDecisions = result.semanticDecisions,
                rawTransitions = result.rawTransitions,
                replayCompatibilityAudit = result.replayCompatibilityAudit,
                decisionBoundaryStates = result.decisionBoundaryStates,
                rootActorStates = result.rootActorStates,
                opponentActorStates = result.opponentActorStates,
                privateOpponentResponses = result.privateOpponentResponses,
                actualTerminalPayoff = result.actualTerminalPayoff,
            )
        }
        return OutcomeStatePairPreflightReport(
            generatedAtUtc = generatedAtUtc,
            parentRunIdentity = OUTCOME_STATE_CORPUS_PARENT_IDENTITY,
            parentArtifactManifestSha256 = prepared.parentArtifactManifestSha256,
            parentCheckpointPayloadSha256 = input.checkpointPayloadSha256,
            pairIndex = pairIndex,
            split = split,
            historicalProjection = historicalProjection,
            producer = producerAuthority,
            trainingProjection = trainingProjection,
            games = games,
        )
    }

    fun run(
        parentArtifactManifest: Path,
        outputDirectory: Path,
        generatedAtUtc: String = Instant.now().toString(),
    ): OutcomeStateCorpusManifest {
        val sourceDirectory = requireNotNull(parentArtifactManifest.parent).toAbsolutePath().normalize()
        require(parentArtifactManifest.fileName.toString() == ResearchRunArtifacts.MANIFEST_FILE)
        val prepared = prepareInputs(sourceDirectory, parentArtifactManifest)
        val split = OutcomeStateCorpusSplitBinding.create(
            pairIndices = prepared.pairs.map { it.pairIndex },
            parentRunIdentity = OUTCOME_STATE_CORPUS_PARENT_IDENTITY,
            trainPairs = 70,
            validationPairs = 15,
            testPairs = 15,
        )
        val producerAuthority = captureProducerAuthority(root)
        val historicalAuthority = OutcomeStateHistoricalAuthority(
            parentRunIdentity = OUTCOME_STATE_CORPUS_PARENT_IDENTITY,
            parentArtifactManifestSha256 = prepared.parentArtifactManifestSha256,
            projection = historicalProjectionAuthority(),
            controlPolicyId = SEARCH_BUDGET_FRONTIER_CONTROL_ID,
            treatmentPolicyId = SEARCH_BUDGET_FRONTIER_TREATMENT_ID,
            controlPolicyEvidenceIdentity = OUTCOME_STATE_CORPUS_CONTROL_POLICY_EVIDENCE_ID,
            treatmentPolicyEvidenceIdentity = OUTCOME_STATE_CORPUS_TREATMENT_POLICY_EVIDENCE_ID,
        )
        val trainingProjection = OutcomeStateTrainingProjectionAuthority(
            historicalProjection = historicalAuthority.projection,
            compatibility = captureProjectionCompatibility(
                root,
                historicalAuthority.projection,
                producerAuthority,
            ),
            currentProducer = producerAuthority,
        )
        val bindings = outcomeStateCorpusBindings(
            historical = historicalAuthority,
            producer = producerAuthority,
            trainingProjection = trainingProjection,
            inputInventorySha256 = prepared.inputInventorySha256,
            deckHash = prepared.deckHash,
            cardPoolHash = prepared.cardPoolHash,
            splitBindingSha256 = split.bindingSha256(),
        )
        val identity = bindings.identity
        val finalDirectory = requireCorpusOutputDirectory(outputDirectory)
        if (Files.exists(finalDirectory)) {
            return loadCompletedCorpus(finalDirectory, identity)
        }
        Files.createDirectories(requireNotNull(finalDirectory.parent))
        val stage = finalDirectory.resolveSibling(
            ".${finalDirectory.fileName}.${identity.substringAfterLast(':').take(24)}.partial"
        )
        require(!Files.isSymbolicLink(stage)) { "Outcome-state staging path is a symbolic link: $stage" }
        Files.createDirectories(stage)

        val inspection = OutcomeStateInspectionIdentity(
            producerOuterCommit = producerAuthority.projection.outerCommit,
            controlPolicyEvidenceIdentity = OUTCOME_STATE_CORPUS_CONTROL_POLICY_EVIDENCE_ID,
        )
        val games = prepared.pairs.flatMap { pair ->
            val pairDirectory = ResearchRunFiles.resolveBelow(stage, "pairs/pair-${pair.pairIndex}")
            val checkpointPath = ResearchRunFiles.resolveBelow(pairDirectory, "checkpoint.json")
            if (Files.exists(checkpointPath)) {
                loadDerivedPairCheckpoint(
                    checkpointPath = checkpointPath,
                    outputRoot = stage,
                    researchRunIdentity = identity,
                    input = pair,
                    split = split.splitFor(pair.pairIndex),
                    trainingProjectionIdentity = trainingProjection.identity(),
                ).games
            } else {
                materializePair(
                    input = pair,
                    split = split.splitFor(pair.pairIndex),
                    pairDirectory = pairDirectory,
                    researchRunIdentity = identity,
                    inspection = inspection,
                    trainingProjectionIdentity = trainingProjection.identity(),
                    deckHash = prepared.deckHash,
                    cardPoolHash = prepared.cardPoolHash,
                ).games
            }
        }
        val corpus = OutcomeStateCorpusManifest(
            researchRunIdentity = identity,
            generatedAtUtc = generatedAtUtc,
            historical = historicalAuthority,
            producer = producerAuthority,
            trainingProjection = trainingProjection,
            inputInventory = prepared.inventory,
            inputInventorySha256 = prepared.inputInventorySha256,
            deckHash = prepared.deckHash,
            cardPoolHash = prepared.cardPoolHash,
            actionSpaceProfile = SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1,
            split = split,
            splitBindingSha256 = split.bindingSha256(),
            games = games.sortedWith(compareBy<OutcomeStateGameArtifact> { it.pairIndex }.thenBy { it.leg }),
        )
        val manifestPath = ResearchRunFiles.resolveBelow(stage, OUTCOME_STATE_MANIFEST_FILE)
        ResearchRunFiles.atomicWrite(manifestPath, evidenceJson.encodeToString(corpus) + "\n")
        ResearchRunArtifacts(stage, identity).also { artifacts ->
            artifacts.register(OUTCOME_STATE_MANIFEST_FILE)
            corpus.games.forEach { artifacts.register(it.bundleReference) }
            prepared.pairs.forEach { pair ->
                artifacts.register("pairs/pair-${pair.pairIndex}/checkpoint.json")
            }
            artifacts.finalize()
        }
        ResearchRunArtifacts.loadAndVerify(stage, identity)
        try {
            Files.move(stage, finalDirectory, StandardCopyOption.ATOMIC_MOVE)
        } catch (unsupported: AtomicMoveNotSupportedException) {
            throw IllegalStateException(
                "Outcome-state corpus publication requires an atomic same-filesystem directory move; " +
                    "verified staging remains at $stage",
                unsupported,
            )
        }
        return corpus
    }

    private fun prepareInputs(
        sourceDirectory: Path,
        parentArtifactManifest: Path,
        pairIndices: List<Int> = (SEARCH_BUDGET_FRONTIER_EXTENSION_START until
            SEARCH_BUDGET_FRONTIER_EXTENSION_START + SEARCH_BUDGET_FRONTIER_EXTENSION_PAIRS).toList(),
    ): PreparedOutcomeStateInputs {
        require(Files.isRegularFile(parentArtifactManifest) && !Files.isSymbolicLink(parentArtifactManifest))
        require(pairIndices == pairIndices.distinct().sorted())
        require(pairIndices.all {
            it in SEARCH_BUDGET_FRONTIER_EXTENSION_START until
                SEARCH_BUDGET_FRONTIER_EXTENSION_START + SEARCH_BUDGET_FRONTIER_EXTENSION_PAIRS
        })
        ResearchRunArtifacts.loadAndVerify(sourceDirectory, OUTCOME_STATE_CORPUS_PARENT_IDENTITY)
        val parentManifestSha = sha256File(parentArtifactManifest)
        val pairs = pairIndices.map { pairIndex ->
            val checkpointPath = sourceDirectory.resolve("pairs/pair-$pairIndex.json")
            require(Files.isRegularFile(checkpointPath) && !Files.isSymbolicLink(checkpointPath)) {
                "Retained extension pair checkpoint is missing: $checkpointPath"
            }
            val verified = readSearchBudgetFrontierExtensionCheckpoint(
                checkpointPath,
                OUTCOME_STATE_CORPUS_PARENT_IDENTITY,
                expectedPairIndex = pairIndex,
            )
            val pair = verified.pair.pair
            require(pair.valid && pair.invalidationReasons.isEmpty() && pair.games.size == 2) {
                "Retained extension pair $pairIndex is not a valid complete pair"
            }
            require(verified.pair.plannerArtifacts.size == 2) {
                "Retained extension pair $pairIndex lost its completeness sidecars"
            }
            require(verified.pair.plannerArtifacts.map { it.gameId }.toSet() ==
                pair.games.map { it.gameId }.toSet()) {
                "Retained extension pair $pairIndex sidecars do not cover both games"
            }
            val byLeg = pair.games.associateBy { game ->
                when {
                    game.gameId.endsWith("-$pairIndex-a") -> "a"
                    game.gameId.endsWith("-$pairIndex-b") -> "b"
                    else -> error("Historical game ${game.gameId} has no exact pair/leg identity")
                }
            }
            require(byLeg.keys == setOf("a", "b"))
            OutcomeStateHistoricalPairInput(
                pairIndex = pairIndex,
                seed = pair.seed,
                checkpointPayloadSha256 = verified.payloadSha256,
                games = listOf("a", "b").map { leg ->
                    val game = byLeg.getValue(leg)
                    require(game.seed == pair.seed)
                    require(game.disposition == GameRunDisposition.GAME_ENDED && game.terminal &&
                        game.replayVerified && game.exception == null)
                    require(searchBudgetFrontierInvalidationReasons(game).isEmpty())
                    require(game.unsupportedInformationEvents.isEmpty())
                    val expectedP0 = if (leg == "a") SEARCH_BUDGET_FRONTIER_CONTROL_ID
                        else SEARCH_BUDGET_FRONTIER_TREATMENT_ID
                    val expectedP1 = if (leg == "a") SEARCH_BUDGET_FRONTIER_TREATMENT_ID
                        else SEARCH_BUDGET_FRONTIER_CONTROL_ID
                    require(game.p0PolicyId == expectedP0 && game.p1PolicyId == expectedP1) {
                        "Historical pair $pairIndex leg $leg no longer names the fixed 8x64/8x32 seats"
                    }
                    val replayReference = "replays/${game.gameId}.privileged.replay.jsonl.gz"
                    val replayPath = sourceDirectory.resolve(replayReference)
                    val replaySha = requireNotNull(game.replaySha256)
                    require(replaySha.matches(lowerSha256))
                    require(game.replayPath?.replace('\\', '/')?.endsWith(replayReference) == true)
                    require(Files.isRegularFile(replayPath) && !Files.isSymbolicLink(replayPath))
                    require(sha256File(replayPath) == replaySha) {
                        "Historical replay changed for ${game.gameId}"
                    }
                    OutcomeStateHistoricalGameInput(
                        pairIndex = pairIndex,
                        leg = leg,
                        game = game,
                        rootPlayerId = if (leg == "a") "p0" else "p1",
                        replayPath = replayPath,
                        replayReference = replayReference,
                        replaySha256 = replaySha,
                    )
                },
            )
        }
        val deckHash = manifest.deckHash()
        val cardPoolHash = manifest.cardPoolHash()
        val inventory = pairs.map { pair ->
            OutcomeStateInputPairInventory(
                pairIndex = pair.pairIndex,
                pairSeed = pair.seed,
                checkpointPayloadSha256 = pair.checkpointPayloadSha256,
                games = pair.games.map { game ->
                    OutcomeStateInputGameInventory(
                        historicalGameId = game.game.gameId,
                        p0PolicyId = game.game.p0PolicyId,
                        p1PolicyId = game.game.p1PolicyId,
                        replaySha256 = game.replaySha256,
                    )
                },
            )
        }
        return PreparedOutcomeStateInputs(
            parentArtifactManifestSha256 = parentManifestSha,
            deckHash = deckHash,
            cardPoolHash = cardPoolHash,
            inventory = inventory,
            inputInventorySha256 = sha256(evidenceJson.encodeToString(inventory)),
            pairs = pairs,
        )
    }

    private fun materializePair(
        input: OutcomeStateHistoricalPairInput,
        split: OutcomeStateCorpusSplit,
        pairDirectory: Path,
        researchRunIdentity: String,
        inspection: OutcomeStateInspectionIdentity,
        trainingProjectionIdentity: String,
        deckHash: String,
        cardPoolHash: String,
    ): OutcomeStatePairCheckpoint {
        // Both replay projections complete before either bundle becomes a staged artifact.
        val projected = input.games.map { game ->
            game to projectHistoricalGame(input, game, split, inspection, deckHash, cardPoolHash)
        }
        return publishOutcomeStatePairAtomically(pairDirectory) { pendingPairDirectory ->
            val artifacts = projected.map { (source, result) ->
                val reference =
                    "pairs/pair-${input.pairIndex}/bundles/${result.bundle.gameId}.inspection.json.gz"
                val pendingPath = ResearchRunFiles.resolveBelow(
                    pendingPairDirectory,
                    "bundles/${result.bundle.gameId}.inspection.json.gz",
                )
                writeCompressedInspection(pendingPath, result.bundle)
                OutcomeStateGameArtifact(
                    pairIndex = input.pairIndex,
                    leg = source.leg,
                    split = split,
                    historicalGameId = source.game.gameId,
                    historicalCreatedAtUtc = result.bundle.createdAtUtc,
                    historicalGameSeed = source.game.seed,
                    historicalSearchBaseSeed = result.historicalSearchBaseSeed,
                    historicalP0PolicyId = source.game.p0PolicyId,
                    historicalP1PolicyId = source.game.p1PolicyId,
                    rootPlayerId = source.rootPlayerId,
                    rootPolicyEvidenceIdentity = OUTCOME_STATE_CORPUS_CONTROL_POLICY_EVIDENCE_ID,
                    trainingProjectionIdentity = trainingProjectionIdentity,
                    parentReplayReference = source.replayReference,
                    parentReplaySha256 = source.replaySha256,
                    parentReplayTerminalRecordDigest = result.parentReplayTerminalRecordDigest,
                    derivedBundleId = result.bundle.gameId,
                    bundleReference = reference,
                    bundleSha256 = sha256File(pendingPath),
                    bundleBytes = Files.size(pendingPath),
                    semanticDecisions = result.semanticDecisions,
                    rawTransitions = result.rawTransitions,
                    replayCompatibilityAudit = result.replayCompatibilityAudit,
                    decisionBoundaryStates = result.decisionBoundaryStates,
                    rootActorStates = result.rootActorStates,
                    opponentActorStates = result.opponentActorStates,
                    privateOpponentResponses = result.privateOpponentResponses,
                    actualTerminalPayoff = result.actualTerminalPayoff,
                    winnerId = source.game.winner,
                )
            }
            OutcomeStatePairCheckpoint(
                pairIndex = input.pairIndex,
                split = split,
                parentCheckpointPayloadSha256 = input.checkpointPayloadSha256,
                games = artifacts,
            ).also { checkpoint ->
                ResearchRunCheckpoints.persist(
                    path = ResearchRunFiles.resolveBelow(pendingPairDirectory, "checkpoint.json"),
                    researchRunIdentity = researchRunIdentity,
                    payloadSchema = OUTCOME_STATE_PAIR_CHECKPOINT_SCHEMA,
                    sequence = 2,
                    payload = evidenceJson.encodeToString(checkpoint).encodeToByteArray(),
                )
            }
        }
    }

    private fun projectHistoricalGame(
        input: OutcomeStateHistoricalPairInput,
        game: OutcomeStateHistoricalGameInput,
        split: OutcomeStateCorpusSplit,
        inspection: OutcomeStateInspectionIdentity,
        deckHash: String,
        cardPoolHash: String,
    ): ProjectedOutcomeStateGame {
        val expectation = OutcomeStateReplayExpectation(
            pairIndex = input.pairIndex,
            leg = game.leg,
            split = split,
            historicalGameId = game.game.gameId,
            historicalGameSeed = game.game.seed,
            historicalP0PolicyId = game.game.p0PolicyId,
            historicalP1PolicyId = game.game.p1PolicyId,
            expectedWinnerId = game.game.winner,
            rootPlayerId = game.rootPlayerId,
            parentRunIdentity = OUTCOME_STATE_CORPUS_PARENT_IDENTITY,
            historicalOuterCommit = OUTCOME_STATE_CORPUS_HISTORICAL_OUTER_COMMIT,
            argentumCommit = OUTCOME_STATE_CORPUS_ARGENTUM_COMMIT,
            deckHash = deckHash,
            cardPoolHash = cardPoolHash,
            parentReplayReference = game.replayReference,
            parentReplaySha256 = game.replaySha256,
            profileManifestHash = sha256(
                "$SEARCH_BUDGET_FRONTIER_EXTENSION_PROTOCOL:${game.game.p0PolicyId}:${game.game.p1PolicyId}"
            ),
        )
        return projector.project(game.replayPath, expectation, inspection)
    }

    private fun loadDerivedPairCheckpoint(
        checkpointPath: Path,
        outputRoot: Path,
        researchRunIdentity: String,
        input: OutcomeStateHistoricalPairInput,
        split: OutcomeStateCorpusSplit,
        trainingProjectionIdentity: String,
    ): OutcomeStatePairCheckpoint {
        val envelope = ResearchRunCheckpoints.load(checkpointPath)
        require(envelope.researchRunIdentity == researchRunIdentity)
        require(envelope.payloadSchema == OUTCOME_STATE_PAIR_CHECKPOINT_SCHEMA)
        val checkpoint = evidenceJson.decodeFromString<OutcomeStatePairCheckpoint>(
            envelope.payload().decodeToString()
        )
        require(checkpoint.pairIndex == input.pairIndex && checkpoint.split == split)
        require(checkpoint.parentCheckpointPayloadSha256 == input.checkpointPayloadSha256)
        require(checkpoint.games.all { it.trainingProjectionIdentity == trainingProjectionIdentity })
        checkpoint.games.forEach { game ->
            val path = ResearchRunFiles.resolveBelow(outputRoot, game.bundleReference)
            require(Files.isRegularFile(path) && !Files.isSymbolicLink(path))
            require(Files.size(path) == game.bundleBytes && sha256File(path) == game.bundleSha256)
            val bundle = readCompressedInspection(path)
            require(bundle.gameId == game.derivedBundleId)
            require(bundle.outcome.terminated && !bundle.outcome.truncated)
            require(bundle.outcome.decisions == game.semanticDecisions)
            require(bundle.outcome.resultByPlayer.getValue(game.rootPlayerId) == game.actualTerminalPayoff)
        }
        return checkpoint
    }
}

/** A pair is either wholly resumable or absent; half-written games never become corpus inputs. */
internal fun <T> publishOutcomeStatePairAtomically(
    finalDirectory: Path,
    build: (Path) -> T,
): T {
    require(!Files.exists(finalDirectory)) { "Outcome-state pair already exists: $finalDirectory" }
    val parent = requireNotNull(finalDirectory.parent)
    Files.createDirectories(parent)
    val pending = Files.createTempDirectory(parent, ".${finalDirectory.fileName}.")
    var published = false
    try {
        val result = build(pending)
        try {
            Files.move(pending, finalDirectory, StandardCopyOption.ATOMIC_MOVE)
        } catch (unsupported: AtomicMoveNotSupportedException) {
            throw IllegalStateException(
                "Outcome-state pair publication requires an atomic same-filesystem directory move",
                unsupported,
            )
        }
        published = true
        return result
    } finally {
        if (!published) pending.toFile().deleteRecursively()
    }
}

internal fun outcomeStateCorpusBindings(
    historical: OutcomeStateHistoricalAuthority,
    producer: OutcomeStateProducerAuthority,
    trainingProjection: OutcomeStateTrainingProjectionAuthority,
    inputInventorySha256: String,
    deckHash: String,
    cardPoolHash: String,
    splitBindingSha256: String,
): ResearchRunBindings = ResearchRunBindings(
    protocol = OUTCOME_STATE_CORPUS_PROTOCOL,
    material = mapOf(
        "parent-run" to historical.parentRunIdentity,
        "parent-artifact-manifest" to historical.parentArtifactManifestSha256,
        "parent-replay-inventory" to inputInventorySha256,
        "historical-outer" to historical.projection.outerCommit,
        "historical-argentum" to historical.projection.argentumCommit,
        "historical-infoset-core-tree" to historical.projection.infosetCoreTree,
        "historical-infoset-argentum-tree" to historical.projection.infosetArgentumTree,
        "control-policy-evidence" to historical.controlPolicyEvidenceIdentity,
        "treatment-policy-evidence" to historical.treatmentPolicyEvidenceIdentity,
        "training-projection" to trainingProjection.identity(),
        "verifier-compatibility" to trainingProjection.compatibility.bindingSha256(),
        "state-equivalence" to trainingProjection.compatibility.stateEquivalenceAlgorithm,
        "replay-action-equivalence" to trainingProjection.compatibility.replayActionEquivalenceAlgorithm,
        "replay-event-equivalence" to trainingProjection.compatibility.replayEventEquivalenceAlgorithm,
        "replay-transition-state-equivalence" to
            trainingProjection.compatibility.replayTransitionStateEquivalenceAlgorithm,
        "producer-source-provenance" to sha256(evidenceJson.encodeToString(producer.sourceProvenance)),
        "producer-outer" to producer.projection.outerCommit,
        "producer-infoset-core-tree" to producer.projection.infosetCoreTree,
        "producer-infoset-argentum-tree" to producer.projection.infosetArgentumTree,
        "deck" to deckHash,
        "card-pool" to cardPoolHash,
        "action-space-profile" to SearchActionSpaceProfile.MONO_RED_FAST_MANA_PRUNED_V1.profileId,
        "inspection-schema" to INSPECTION_SCHEMA_CURRENT.toString(),
        "split" to splitBindingSha256,
        "label" to "actual-completed-terminal-payoff-from-explicit-8x64-root-v4",
    ),
)

internal fun historicalProjectionAuthority(): OutcomeStateProjectionAuthority =
    OutcomeStateProjectionAuthority(
        outerCommit = OUTCOME_STATE_CORPUS_HISTORICAL_OUTER_COMMIT,
        infosetCoreTree = OUTCOME_STATE_CORPUS_INFOSET_CORE_TREE,
        infosetArgentumTree = OUTCOME_STATE_CORPUS_INFOSET_ARGENTUM_TREE,
        argentumCommit = OUTCOME_STATE_CORPUS_ARGENTUM_COMMIT,
    )

internal fun captureProducerAuthority(root: Path): OutcomeStateProducerAuthority {
    val provenance = RunProvenance.capture(root).also { it.requireReady() }
    require(!provenance.outerDirty && !provenance.engineDirty) {
        "Outcome-state evidence requires clean committed producer and engine source"
    }
    require(provenance.outerCommit.matches(fullCommit))
    require(provenance.checkedOutArgentumCommit == OUTCOME_STATE_CORPUS_ARGENTUM_COMMIT)
    val coreTree = gitOutput(root, "rev-parse", "HEAD:agent/infoset-core")
    val adapterTree = gitOutput(root, "rev-parse", "HEAD:agent/infoset-argentum")
    require(coreTree == OUTCOME_STATE_CORPUS_INFOSET_CORE_TREE) {
        "Current infoset-core tree $coreTree differs from the historical projection authority"
    }
    return OutcomeStateProducerAuthority(
        projection = OutcomeStateProjectionAuthority(
            outerCommit = provenance.outerCommit,
            infosetCoreTree = coreTree,
            infosetArgentumTree = adapterTree,
            argentumCommit = provenance.checkedOutArgentumCommit,
        ),
        sourceProvenance = provenance.sourceProvenance,
    )
}

internal fun captureProjectionCompatibility(
    root: Path,
    historical: OutcomeStateProjectionAuthority,
    producer: OutcomeStateProducerAuthority,
): OutcomeStateProjectionCompatibility {
    val changes = gitOutput(
        root,
        "diff",
        "--name-status",
        historical.infosetArgentumTree,
        producer.projection.infosetArgentumTree,
    ).lines().filter(String::isNotBlank)
    val fingerprintPath = OUTCOME_STATE_FINGERPRINT_SOURCE.removePrefix(OUTCOME_STATE_ARGENTUM_MODULE_PREFIX)
    val fingerprintTestPath =
        OUTCOME_STATE_FINGERPRINT_TEST_SOURCE.removePrefix(OUTCOME_STATE_ARGENTUM_MODULE_PREFIX)
    val testPrefix = "src/test/"
    val runtimeChanges = changes.filterNot { it.substringAfter('\t').startsWith(testPrefix) }
    val testChanges = changes.filter { it.substringAfter('\t').startsWith(testPrefix) }
    require(runtimeChanges == listOf("M\t$fingerprintPath")) {
        "Verifier compatibility permits only $OUTCOME_STATE_FINGERPRINT_SOURCE; found $runtimeChanges"
    }
    require(testChanges == listOf("M\t$fingerprintTestPath")) {
        "Verifier compatibility test audit changed unexpectedly: $testChanges"
    }

    fun delta(retainedPath: String, treePath: String): OutcomeStateSourceBlobDelta = OutcomeStateSourceBlobDelta(
        path = retainedPath,
        historicalBlob = gitOutput(root, "rev-parse", "${historical.infosetArgentumTree}:$treePath"),
        producerBlob = gitOutput(root, "rev-parse", "${producer.projection.infosetArgentumTree}:$treePath"),
    )

    return OutcomeStateProjectionCompatibility(
        historicalInfosetCoreTree = historical.infosetCoreTree,
        producerInfosetCoreTree = producer.projection.infosetCoreTree,
        historicalInfosetArgentumTree = historical.infosetArgentumTree,
        producerInfosetArgentumTree = producer.projection.infosetArgentumTree,
        runtimeSourceDelta = delta(OUTCOME_STATE_FINGERPRINT_SOURCE, fingerprintPath),
        nonRuntimeTestSourceDeltas = listOf(
            delta(OUTCOME_STATE_FINGERPRINT_TEST_SOURCE, fingerprintTestPath)
        ),
    ).also { it.requireAuthorities(historical, producer.projection) }
}

internal fun writeCompressedInspection(path: Path, bundle: PolicyInspectionBundle): Path {
    val encoded = PolicyJson.format.encodeToString(PolicyInspectionBundle.serializer(), bundle)
    PublicArtifactPrivacy.requireSafeJson(encoded, "replay-derived policy inspection")
    val bytes = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            writer.write(encoded)
            writer.newLine()
        }
    }.toByteArray()
    return ResearchRunFiles.atomicWrite(path, bytes)
}

internal fun readCompressedInspection(path: Path): PolicyInspectionBundle =
    GZIPInputStream(Files.newInputStream(path)).bufferedReader(StandardCharsets.UTF_8).use { reader ->
        PolicyJson.format.decodeFromString(PolicyInspectionBundle.serializer(), reader.readText())
    }

private fun requireCorpusOutputDirectory(path: Path): Path {
    val absolute = path.toAbsolutePath().normalize()
    val parent = requireNotNull(absolute.parent)
    return ResearchRunFiles.resolveBelow(parent, absolute.fileName.toString())
}

private fun loadCompletedCorpus(path: Path, identity: String): OutcomeStateCorpusManifest {
    ResearchRunArtifacts.loadAndVerify(path, identity)
    return evidenceJson.decodeFromString<OutcomeStateCorpusManifest>(
        Files.readString(ResearchRunFiles.resolveBelow(path, OUTCOME_STATE_MANIFEST_FILE))
    ).also { require(it.researchRunIdentity == identity) }
}
