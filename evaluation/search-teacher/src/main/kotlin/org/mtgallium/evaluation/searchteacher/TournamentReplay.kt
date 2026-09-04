package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.argentum.ArgentumStateFingerprint
import org.mtgallium.agent.infoset.argentum.UnifiedSemanticExpander
import org.mtgallium.agent.infoset.core.PolicyExpansion
import org.mtgallium.agent.infoset.core.PolicyExpansionOmissionReason
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.SearchActionSpaceProfile
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticOperationFamily
import org.mtgallium.agent.searchteacher.SearchTeacherSelectionKind

internal const val TOURNAMENT_REPLAY_SCHEMA_VERSION = 4
private const val TOURNAMENT_REPLAY_MINIMUM_SCHEMA_VERSION = 1

private fun requireSupportedReplaySchema(schemaVersion: Int) {
    require(schemaVersion in TOURNAMENT_REPLAY_MINIMUM_SCHEMA_VERSION..TOURNAMENT_REPLAY_SCHEMA_VERSION) {
        "Unknown privileged replay schema $schemaVersion"
    }
}

internal data class GameReplayOptions(
    val finalPath: Path,
    val referencePath: String = finalPath.toString(),
    val runIdentity: String,
    val outerCommit: String = currentOuterCommit(),
    val argentumCommit: String = currentArgentumCommit(),
) {
    init {
        require(finalPath.fileName.toString().endsWith(".privileged.replay.jsonl.gz"))
        require(referencePath.isNotBlank())
        require(runIdentity.isNotBlank())
    }
}

@Serializable
internal data class ReplayPolicyConfiguration(
    val id: String,
    val kind: ArenaPolicyKind,
    val profile: FrozenSearchProfile? = null,
    val beliefMode: org.mtgallium.agent.infoset.core.BeliefMode,
    val beliefArchitecture: org.mtgallium.agent.infoset.core.BeliefArchitecture,
    val searchPlanner: SearchPlannerKind,
    val policyCompression: org.mtgallium.agent.searchteacher.PolicyCompressionConfig,
    val searchReuse: org.mtgallium.agent.searchteacher.SearchReuseConfig,
)

@Serializable
internal data class PrivilegedReplayHeader(
    val schemaVersion: Int = TOURNAMENT_REPLAY_SCHEMA_VERSION,
    val gameId: String,
    val runIdentity: String,
    val createdAtUtc: String,
    val gameSeed: Long,
    val baseSeed: Long,
    val outerCommit: String,
    val argentumCommit: String,
    val deckHash: String,
    val cardPoolHash: String,
    val actionSpaceProfile: SearchActionSpaceProfile,
    val p0Policy: ReplayPolicyConfiguration,
    val p1Policy: ReplayPolicyConfiguration,
    val initialAuthoritativeFingerprint: String,
) {
    init { requireSupportedReplaySchema(schemaVersion) }
}

@Serializable
internal data class ReplayCandidateSummary(
    val signature: String,
    val operationFamily: SemanticOperationFamily,
    val label: String,
)

@Serializable
internal data class PrivilegedReplayTransition(
    val schemaVersion: Int = TOURNAMENT_REPLAY_SCHEMA_VERSION,
    val decisionIndex: Int,
    val actorId: String,
    val choice: SemanticChoice,
    val selectionKind: SearchTeacherSelectionKind? = null,
    val selectionLatencyMillis: Double,
    val searchLatencyMillis: Double? = null,
    val candidates: List<ReplayCandidateSummary>,
    val expansionExhaustive: Boolean,
    val profileExhaustive: Boolean,
    val estimatedCandidateCount: Long?,
    val proposalVersion: String,
    val omissionReasons: Set<PolicyExpansionOmissionReason>,
    val privateToActor: Boolean,
    /** Schema v2: full trusted state after this exact accepted action. */
    val authoritativeFingerprintAfter: String? = null,
    /** Schema v3: opaque root-component hashes for localized full-state replay diagnosis. */
    val authoritativeComponentDigestsAfter: Map<String, String> = emptyMap(),
    val p0InformationStateDigestAfter: String? = null,
    val p1InformationStateDigestAfter: String? = null,
    val mainPhasePassWithProactiveOption: Boolean = false,
    val cleanupDiscardPlayerIds: List<String> = emptyList(),
) {
    init {
        requireSupportedReplaySchema(schemaVersion)
        require(decisionIndex >= 0)
        require(selectionLatencyMillis >= 0.0 && selectionLatencyMillis.isFinite())
        require(searchLatencyMillis == null || searchLatencyMillis >= 0.0 && searchLatencyMillis.isFinite())
    }
}

@Serializable
internal data class PrivilegedReplayTerminal(
    val schemaVersion: Int = TOURNAMENT_REPLAY_SCHEMA_VERSION,
    val decisions: Int,
    val terminal: Boolean,
    val winnerId: String?,
    val stepLimit: Boolean,
    val exception: String? = null,
    val finalAuthoritativeFingerprint: String,
) {
    init {
        requireSupportedReplaySchema(schemaVersion)
        require(decisions >= 0)
    }
}

@Serializable
internal data class PrivilegedReplayLine(
    val header: PrivilegedReplayHeader? = null,
    val transition: PrivilegedReplayTransition? = null,
    val terminal: PrivilegedReplayTerminal? = null,
) {
    init {
        require(listOf(header, transition, terminal).count { it != null } == 1) {
            "A replay line must contain exactly one record"
        }
    }
}

internal data class ReplayArtifact(
    val referencePath: String,
    val sha256: String,
    val verified: Boolean,
    val verificationDiagnostic: String? = null,
)

internal data class ReplayVerificationResult(
    val verified: Boolean,
    val decisions: Int = 0,
    val diagnostic: String? = null,
)

/** Engine-only verification: reconstructs the game and applies logged choices without rerunning a policy. */
internal class TournamentReplayVerifier(
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
) {
    fun verify(path: Path): ReplayVerificationResult {
        if (isCanonicalReplay(path)) return CanonicalTournamentReplayVerifier.verify(path)
        return runCatching {
        val lines = readPrivilegedReplay(path)
        require(lines.size >= 2) { "Replay needs a header and terminal" }
        val header = requireNotNull(lines.first().header) { "First replay record is not a header" }
        val terminal = requireNotNull(lines.last().terminal) { "Last replay record is not terminal" }
        require(lines.drop(1).dropLast(1).all { it.transition != null }) {
            "Replay contains a non-transition record between header and terminal"
        }
        require(header.deckHash == manifest.deckHash()) { "Replay deck hash does not match manifest" }
        require(header.cardPoolHash == manifest.cardPoolHash()) { "Replay card-pool hash does not match manifest" }
        require(terminal.schemaVersion == header.schemaVersion) { "Replay terminal schema differs from header" }

        val environment = GameEnvironment.create(registry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Player 0", manifest.deck()),
                    PlayerConfig("Player 1", manifest.deck()),
                ),
                skipMulligans = false,
                useHandSmoother = false,
                startingPlayerIndex = 0,
                seed = header.gameSeed,
            )
        )
        val world = ArgentumSearchWorld.create(
            environment = environment,
            gameId = header.gameId,
            seedBase = header.baseSeed,
            effectiveSetupSeed = header.gameSeed,
           expander = UnifiedSemanticExpander(actionSpaceProfile = header.actionSpaceProfile),
           knownDecks = mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck),
        )
        require(replayEvidence(world, header.schemaVersion).fingerprint == header.initialAuthoritativeFingerprint) {
            "Initial authoritative fingerprint mismatch"
        }

        val transitions = lines.drop(1).dropLast(1).map { requireNotNull(it.transition) }
        transitions.forEachIndexed { index, record ->
            require(record.schemaVersion == header.schemaVersion) {
                "Replay transition schema differs from header at $index"
            }
            require(record.decisionIndex == index) {
                "Non-contiguous replay index ${record.decisionIndex}; expected $index"
            }
            require(world.actorToAct() == record.actorId) {
                "Actor mismatch at $index: ${world.actorToAct()} != ${record.actorId}"
            }
            val expansion = world.expandChoices()
            require(expansion.summary() == record.candidates) { "Candidate expansion mismatch at $index" }
            require(expansion.isExhaustive == record.expansionExhaustive) { "Rules exhaustiveness mismatch at $index" }
            require(expansion.isProfileExhaustive == record.profileExhaustive) { "Profile exhaustiveness mismatch at $index" }
            require(expansion.estimatedCandidateCount == record.estimatedCandidateCount) {
                "Estimated candidate count mismatch at $index"
            }
            require(expansion.proposalVersion == record.proposalVersion) { "Proposal version mismatch at $index" }
            require(expansion.omissionReasons == record.omissionReasons) { "Omission provenance mismatch at $index" }
            val accepted = world.step(record.choice)
            require(accepted.accepted) { "Choice rejected at $index: ${accepted.diagnostic}" }
            require(accepted.privateToActor == record.privateToActor) { "Privacy classification mismatch at $index" }
            record.authoritativeFingerprintAfter?.let { expected ->
                val actualEvidence = replayEvidence(world, header.schemaVersion)
                require(actualEvidence.fingerprint == expected) {
                    val actualComponents = actualEvidence.componentDigests
                    val differing = (record.authoritativeComponentDigestsAfter.keys + actualComponents.keys)
                        .toSortedSet()
                        .filter { key -> record.authoritativeComponentDigestsAfter[key] != actualComponents[key] }
                    val continuationDiagnostic = if ("continuationStack" in differing) {
                        world.authoritativeStateForHost().continuationStack
                            .joinToString(prefix = "; actual continuations=[", postfix = "]") { frame ->
                                frame::class.simpleName ?: "unknown"
                            }
                    } else ""
                    "Authoritative fingerprint mismatch at $index; differing components=$differing" +
                        continuationDiagnostic
                }
            }
            record.p0InformationStateDigestAfter?.let { expected ->
                require(world.informationState("p0").informationStateDigest == expected) {
                    "p0 information-state digest mismatch at $index"
                }
            }
            record.p1InformationStateDigestAfter?.let { expected ->
                require(world.informationState("p1").informationStateDigest == expected) {
                    "p1 information-state digest mismatch at $index"
                }
            }
        }
        require(terminal.decisions == transitions.size) { "Terminal decision count mismatch" }
        require((world.terminalPayoff("p0") != null) == terminal.terminal) { "Terminal-state mismatch" }
        val winner = when (world.terminalPayoff("p0")) {
            1.0 -> "p0"
            -1.0 -> "p1"
            else -> null
        }
        require(winner == terminal.winnerId) { "Winner mismatch" }
        require(replayEvidence(world, header.schemaVersion).fingerprint == terminal.finalAuthoritativeFingerprint) {
            "Final authoritative fingerprint mismatch"
        }
            ReplayVerificationResult(verified = true, decisions = transitions.size)
        }.getOrElse { error ->
            ReplayVerificationResult(
                verified = false,
                diagnostic = "${error::class.simpleName}: ${error.message}",
            )
        }
    }


    private fun replayEvidence(
        world: ArgentumSearchWorld,
        schemaVersion: Int,
    ) = if (schemaVersion >= 4) {
        ArgentumStateFingerprint.evidence(world.authoritativeStateForHost())
    } else {
        ArgentumStateFingerprint.legacyReplayEvidence(world.authoritativeStateForHost())
    }
}

private fun isCanonicalReplay(path: Path): Boolean =
    GZIPInputStream(Files.newInputStream(path)).bufferedReader(StandardCharsets.UTF_8).use { reader ->
        val first = reader.lineSequence().firstOrNull(String::isNotBlank) ?: return@use false
        runCatching {
            val element = PolicyJson.format.parseToJsonElement(first)
            (element as? kotlinx.serialization.json.JsonObject)
                ?.get("type") == kotlinx.serialization.json.JsonPrimitive("header")
        }.getOrDefault(false)
    }

internal fun PolicyExpansion.summary(): List<ReplayCandidateSummary> = candidates.map { candidate ->
    ReplayCandidateSummary(candidate.signature, candidate.operationFamily, candidate.display.label)
}

internal fun readPrivilegedReplay(path: Path): List<PrivilegedReplayLine> =
    GZIPInputStream(Files.newInputStream(path)).bufferedReader(StandardCharsets.UTF_8).use { reader ->
        reader.lineSequence().filter(String::isNotBlank).map {
            PolicyJson.format.decodeFromString(PrivilegedReplayLine.serializer(), it)
        }.toList()
    }

internal fun ArenaPolicySpec.replayConfiguration(): ReplayPolicyConfiguration = ReplayPolicyConfiguration(
    id = id,
    kind = kind,
    profile = profile,
    beliefMode = beliefMode,
    beliefArchitecture = beliefArchitecture,
    searchPlanner = searchPlanner,
    policyCompression = policyCompression,
    searchReuse = searchReuse,
)
