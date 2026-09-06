package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Path
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.searchteacher.SearchTeacherPolicySession
import org.mtgallium.agent.searchteacher.defaultMonoRedOpponentPolicy
import org.mtgallium.research.run.ResearchRunFiles

/** Referee reconstruction stays evaluation-owned; only session belief particles may enter search. */
internal data class ReconstructedPositionBankRoot(
    val actual: ArgentumSearchWorld,
    val session: SearchTeacherPolicySession,
    val candidates: List<SemanticChoice>,
    val reconstructionMillis: Double,
)

/** Shared source-authenticated history and sequential-belief reconstruction for bank diagnostics. */
internal fun reconstructPositionBankRoot(
    position: RealGamePositionBankRoot,
    bank: RealGamePositionBankReport,
    registry: CardRegistry,
    manifest: DeckManifest,
    policy: PositionBankScreenPolicy,
): ReconstructedPositionBankRoot {
    val evaluator = policy.informationEvaluator()
    val reconstructionStarted = System.nanoTime()
    val sourceEntry = bank.plan.sources.single { it.expectedRunIdentity == position.sourceRunIdentity }
    val sourceDirectory = Path.of(sourceEntry.runDirectory)
    val replayPath = ResearchRunFiles.resolveBelow(sourceDirectory, position.replayRelativePath)
    require(sha256File(replayPath) == position.replaySha256)
    val replay = readVerifiedCanonicalSemanticReplay(replayPath)
    require(replay.header.gameId == position.sourceGameId)
    require(replay.header.requireExtensionString("mtgallium.runIdentity") == position.sourceRunIdentity)
    require(replay.header.requireExtensionString("mtgallium.deckHash") == manifest.deckHash())
    require(replay.header.requireExtensionString("mtgallium.cardPoolHash") == manifest.cardPoolHash())
    require(replay.header.requireExtensionLong("mtgallium.gameSeed") == position.gameSeed)
    require(replay.header.requireExtensionLong("mtgallium.baseSeed") == position.baseSeed)
    val prefix = replay.decisions.take(position.decisionIndex).map { it.choice }
    require(PolicyJson.sha256(prefix.joinToString("\u001f") { it.signature }) == position.semanticPrefixDigest)
    val parameters = policy.search.parameters(position.baseSeed)
    val arenaPolicy = policy.search.policy(position.baseSeed)
    val actual = createSemanticReplayWorld(registry, manifest, position.sourceGameId, position.gameSeed,
        position.baseSeed, 0, parameters.actionSpaceProfile)
    val session = SearchTeacherPolicySession(actual, position.actor,
        mapOf("p0" to manifest.mainDeck, "p1" to manifest.mainDeck), parameters,
        defaultMonoRedOpponentPolicy(), position.sourceGameId,
        arenaPolicy.effectiveRootRolloutPolicy(), arenaPolicy.effectiveOpponentRolloutPolicy(), evaluator)
    replayFixedRootPrefix(position.decisionIndex, replay, actual, session)
    require(actual.actorToAct() == position.actor)
    require(actual.informationState(position.actor).informationStateDigest == position.informationStateDigest)
    val candidates = actual.expandChoices().candidates
    require(candidates == position.reconstructedCandidates) { "Current candidate expansion changed" }
    val reconstructionMillis = (System.nanoTime() - reconstructionStarted) / 1_000_000.0
    return ReconstructedPositionBankRoot(actual, session, candidates, reconstructionMillis)
}
