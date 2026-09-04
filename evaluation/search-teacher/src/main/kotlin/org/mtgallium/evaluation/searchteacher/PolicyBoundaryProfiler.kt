package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.GZIPInputStream
import kotlinx.serialization.encodeToString
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.BoundedPolicyInputCompiler
import org.mtgallium.agent.infoset.core.BoundedPolicyInputConfig
import org.mtgallium.agent.infoset.core.PolicyHistoryCommitment
import org.mtgallium.agent.infoset.core.PolicyHistoryEvent
import org.mtgallium.agent.infoset.core.PolicyJson
import org.mtgallium.agent.infoset.core.PolicyTrajectoryDecision
import org.mtgallium.agent.infoset.core.PolicyTrajectoryForcedTransition
import org.mtgallium.agent.infoset.core.PolicyTrajectoryRecord

internal class PolicyBoundaryProfiler(
    private val root: Path,
    private val registry: CardRegistry,
    private val deck: DeckManifest,
) {
    fun run(manifestPath: Path): PolicyBoundaryReport {
        val manifest = evidenceJson.decodeFromString<CorpusManifest>(Files.readString(manifestPath))
        val config = BoundedPolicyInputConfig()
        val timings = mutableListOf<Double>()
        var maximumBytes = 0
        var corpusDecisions = 0
        var template: Pair<PolicyTrajectoryDecision, List<PolicyHistoryEvent>>? = null
        var corpusEvents = 0
        var firstEvent: PolicyHistoryEvent? = null
        var compressedBytes = 0L
        var uncompressedBytes = 0L
        manifest.entries.forEach { entry ->
            val path = root.resolve(entry.publicTrajectory).normalize()
            compressedBytes += Files.size(path)
            val ledger = mutableListOf<PolicyHistoryEvent>()
            GZIPInputStream(Files.newInputStream(path)).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    uncompressedBytes += line.toByteArray(StandardCharsets.UTF_8).size + 1
                    when (val record = PolicyJson.format.decodeFromString(PolicyTrajectoryRecord.serializer(), line)) {
                        is PolicyTrajectoryDecision -> {
                            val information = record.informationState(ledger)
                            val start = System.nanoTime()
                            val compilation = BoundedPolicyInputCompiler.compileWithMetrics(
                                information,
                                record.policyInput.belief,
                                config,
                            )
                            timings += (System.nanoTime() - start) / 1_000.0
                            check(compilation.input == record.policyInput) { "Corpus bounded input is not reproducible" }
                            maximumBytes = maxOf(maximumBytes, compilation.metrics.totalBytes)
                            corpusDecisions++
                            if (template == null) template = record to ledger.toList()
                        }
                        is PolicyTrajectoryForcedTransition -> {
                            if (firstEvent == null) firstEvent = record.events.firstOrNull()
                            ledger += record.events
                            corpusEvents += record.events.size
                        }
                        else -> Unit
                    }
                }
            }
        }
        val templateInput = requireNotNull(template) { "Policy-boundary profiling requires at least one search decision" }
        val eventTemplate = firstEvent
            ?: error("Synthetic policy-boundary profiling requires one ledger event")
        val lengths = listOf(0, 64, 256, 1_024, 4_096)
        val synthetic = lengths.map { length ->
            val events = (0 until length).map { index -> eventTemplate.copy(eventId = index.toLong()) }
            val commitment = PolicyHistoryCommitment.replay(events)
            val information = templateInput.first.informationState(templateInput.second).copy(
                history = events,
                historyCommitment = commitment,
            )
            val start = System.nanoTime()
            val compilation = BoundedPolicyInputCompiler.compileWithMetrics(information, config = config)
            val micros = (System.nanoTime() - start) / 1_000.0
            maximumBytes = maxOf(maximumBytes, compilation.metrics.totalBytes)
            PolicyBoundarySyntheticPoint(
                historyLength = length,
                eventsExamined = compilation.metrics.eventsExamined,
                recentEventCount = compilation.metrics.recentEventCount,
                recentEventBytes = compilation.metrics.recentEventBytes,
                totalInputBytes = compilation.metrics.totalBytes,
                compileMicros = micros,
            )
        }
        val templateInformation = templateInput.first.informationState(templateInput.second)
        val sizes = listOf(256, 512, 1_024).associateWith {
            normalizedSyntheticSize(it, templateInput.first, templateInformation, eventTemplate, config)
        }
        val ratios = mapOf(
            "256_to_512" to sizes.getValue(512).toDouble() / sizes.getValue(256),
            "512_to_1024" to sizes.getValue(1_024).toDouble() / sizes.getValue(512),
        )
        val compilerBounded = synthetic.all { it.eventsExamined <= config.recentEventLimit }
        val inputCapPassed = maximumBytes <= config.totalByteLimit
        val growthPassed = ratios.values.all { it <= 2.2 }
        val commitmentAppendPassed = run {
            val base = PolicyHistoryCommitment.replay((0 until 4_096).map { eventTemplate.copy(eventId = it.toLong()) })
            base.append(eventTemplate.copy(eventId = 4_096L)).cursor == 4_097
        }
        val persistentForkPassed = initialWorld().persistentHistoryForkSharesPrefix("p0")
        val failures = buildList {
            if (!compilerBounded) add("compiler scanned beyond its recent-event limit")
            if (!inputCapPassed) add("bounded input exceeded ${config.totalByteLimit} bytes")
            if (!growthPassed) add("normalized trajectory growth exceeded 2.2x")
            if (!commitmentAppendPassed) add("incremental commitment append failed")
            if (!persistentForkPassed) add("history fork did not share its immutable prefix")
        }
        return PolicyBoundaryReport(
            generatedAtUtc = Instant.now().toString(),
            outerCommit = manifest.outerCommit,
            argentumCommit = manifest.argentumCommit,
            sourceManifest = root.relativize(manifestPath.toAbsolutePath().normalize()).toString(),
            sourceManifestHash = sha256File(manifestPath),
            profileHash = manifest.profileHash,
            corpusGames = manifest.entries.size,
            corpusDecisions = corpusDecisions,
            corpusEvents = corpusEvents,
            compressedBytes = compressedBytes,
            uncompressedBytes = uncompressedBytes,
            compileP50Micros = percentile(timings, 0.50),
            compileP95Micros = percentile(timings, 0.95),
            compileP99Micros = percentile(timings, 0.99),
            maximumInputBytes = maximumBytes,
            inputByteLimit = config.totalByteLimit,
            synthetic = synthetic,
            growthRatios = ratios,
            compilerBounded = compilerBounded,
            inputCapPassed = inputCapPassed,
            trajectoryGrowthPassed = growthPassed,
            commitmentAppendPassed = commitmentAppendPassed,
            persistentForkPassed = persistentForkPassed,
            passed = failures.isEmpty(),
            failures = failures,
        )
    }

    private fun normalizedSyntheticSize(
        count: Int,
        decisionTemplate: PolicyTrajectoryDecision,
        informationTemplate: org.mtgallium.agent.infoset.core.PolicyInformationState,
        eventTemplate: PolicyHistoryEvent,
        config: BoundedPolicyInputConfig,
    ): Long {
        val events = mutableListOf<PolicyHistoryEvent>()
        var commitment = PolicyHistoryCommitment.empty()
        var total = 0L
        repeat(count) { index ->
            val event = eventTemplate.copy(eventId = index.toLong())
            events += event
            commitment = commitment.append(event)
            val information = informationTemplate.copy(
                history = events,
                historyCommitment = commitment,
            )
            val input = BoundedPolicyInputCompiler.compile(information, config = config)
            val transition = PolicyTrajectoryForcedTransition(
                gameId = decisionTemplate.gameId,
                afterDecisionIndex = index,
                events = listOf(event),
            )
            val decision = decisionTemplate.copy(decisionIndex = index, policyInput = input)
            total += encodedBytes(transition) + encodedBytes(decision)
        }
        return total
    }

    private fun encodedBytes(record: PolicyTrajectoryRecord): Int =
        PolicyJson.format.encodeToString(PolicyTrajectoryRecord.serializer(), record)
            .toByteArray(StandardCharsets.UTF_8).size + 1

    private fun initialWorld(): ArgentumSearchWorld {
        val environment = GameEnvironment.create(registry)
        environment.reset(
            GameConfig(
                players = listOf(PlayerConfig("Player 0", deck.deck()), PlayerConfig("Player 1", deck.deck())),
                skipMulligans = false,
                useHandSmoother = false,
                startingPlayerIndex = 0,
                seed = 1L,
            )
        )
        return ArgentumSearchWorld.create(
            environment,
            "policy-boundary-probe",
            1L,
           effectiveSetupSeed = 1L,
           knownDecks = mapOf("p0" to deck.mainDeck, "p1" to deck.mainDeck),
        )
    }

    private fun percentile(values: List<Double>, fraction: Double): Double {
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * fraction).toInt()]
    }
}
