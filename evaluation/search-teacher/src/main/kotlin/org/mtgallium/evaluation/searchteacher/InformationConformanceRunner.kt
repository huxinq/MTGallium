package org.mtgallium.evaluation.searchteacher

import com.wingedsheep.engine.registry.CardRegistry
import java.nio.file.Path
import java.time.Instant
import org.mtgallium.agent.infoset.argentum.PerspectiveProjectionAudit
import org.mtgallium.agent.infoset.argentum.PerspectiveProjectionAuditSink
import org.mtgallium.agent.infoset.core.ComponentSeeds

internal class InformationConformanceRunner(
    private val root: Path,
    private val registry: CardRegistry,
    private val manifest: DeckManifest,
    private val profile: FrozenSearchProfile,
    private val baseSeed: Long,
    private val representationBoundaryDetector: RepresentationBoundaryDetector? = null,
) {
    fun run(gameCount: Int = 256, workerThreads: Int = 1): InformationConformanceReport {
        require(gameCount >= 1)
        val arena = representationBoundaryDetector?.let { detector ->
            SearchTeacherArena(
                registry,
                manifest,
                profile,
                baseSeed,
                representationBoundaryDetector = detector,
            )
        } ?: SearchTeacherArena(registry, manifest, profile, baseSeed)
        // Search sessions exercise sequential particle ledgers and reuse paths that ordinary arena
        // policies never touch; excluding them previously left cleanup-discard coverage untested.
        val policies = ArenaPolicyKind.entries
        val games = parallelMapOrdered(gameCount, workerThreads) { index ->
            val first = policies[(index / 2) % policies.size]
            val second = policies[((index / 2) + 1) % policies.size]
            val p0 = if (index % 2 == 0) first else second
            val p1 = if (index % 2 == 0) second else first
            val audits = mutableListOf<PerspectiveProjectionAudit>()
            var probed = false
            var hiddenChecks = 0
            var hiddenFailures = 0
            var supportParticles = 0
            var supportFailures = 0
            val result = arena.play(
                gameId = "information-conformance-${index.toString().padStart(6, '0')}",
                gameSeed = ComponentSeeds.derive(baseSeed, index, "information-conformance"),
                p0Policy = p0,
                p1Policy = p1,
                projectionAuditSink = PerspectiveProjectionAuditSink { audits += it },
                rootProbe = { world, actor, decisionIndex ->
                    if (!probed) {
                        probed = true
                        val hidden = world.hiddenTruthConformanceProbe(actor)
                        if (hidden.unavailableReason == null) {
                            hiddenChecks++
                            if (!hidden.passed) hiddenFailures++
                        }
                        val support = world.verifyKnowledgeSupport(
                            actor,
                            ComponentSeeds.derive(baseSeed, index, "support:$decisionIndex"),
                        )
                        supportParticles += support.particlesChecked
                        supportFailures += support.failures
                    }
                },
            )
            InformationConformanceGame(
                gameId = result.gameId,
                p0Policy = p0,
                p1Policy = p1,
                terminal = result.terminal,
                disposition = result.disposition,
                evidenceStop = result.evidenceRunStopSummary(),
                decisions = result.decisions,
                ledgerComplete = result.informationLedgerComplete,
                failureCategory = result.exception?.substringBefore(':'),
                unsupportedReasons = result.unsupportedInformationEvents,
                eventCoverage = audits.groupingBy { it.rawEventType }.eachCount().toSortedMap(),
                projectionCoverage = audits.groupingBy { it.disposition.name }.eachCount().toSortedMap(),
                hiddenRootChecks = hiddenChecks,
                hiddenRootFailures = hiddenFailures,
                supportParticlesChecked = supportParticles,
                supportFailures = supportFailures,
            )
        }
        val eventCoverage = games.flatMap { it.eventCoverage.entries }
            .groupingBy { it.key }.fold(0) { total, entry -> total + entry.value }.toSortedMap()
        val projectionCoverage = games.flatMap { it.projectionCoverage.entries }
            .groupingBy { it.key }.fold(0) { total, entry -> total + entry.value }.toSortedMap()
        val failures = buildList {
            games.filterNot { it.terminal }.forEach { add("${it.gameId}: non-terminal ${it.failureCategory ?: "game"}") }
            games.filterNot { it.ledgerComplete }.forEach { add("${it.gameId}: incomplete ledger") }
            games.filter { it.unsupportedReasons.isNotEmpty() }.forEach { add("${it.gameId}: ${it.unsupportedReasons}") }
            if ((projectionCoverage["UNSUPPORTED"] ?: 0) != 0) add("projector reached unsupported event families")
            if (games.sumOf { it.hiddenRootFailures } != 0) add("hidden-truth noninterference failed")
            if (games.sumOf { it.supportFailures } != 0) add("belief support verification failed")
        }
        val hiddenChecks = games.sumOf { it.hiddenRootChecks }
        return InformationConformanceReport(
            generatedAtUtc = Instant.now().toString(),
            outerCommit = currentOuterCommit(),
            argentumCommit = currentArgentumCommit(),
            deckHash = manifest.deckHash(),
            cardPoolHash = manifest.cardPoolHash(),
            requestedGames = gameCount,
            terminalGames = games.count { it.terminal },
            perspectiveLedgers = games.size * 2,
            hiddenRootChecks = hiddenChecks,
            hiddenRootFailures = games.sumOf { it.hiddenRootFailures },
            supportParticlesChecked = games.sumOf { it.supportParticlesChecked },
            supportFailures = games.sumOf { it.supportFailures },
            eventCoverage = eventCoverage,
            projectionCoverage = projectionCoverage,
            games = games,
            passed = failures.isEmpty() && gameCount >= 256 && hiddenChecks >= 64,
            failures = failures + buildList {
                if (gameCount < 256) add("fewer than 256 games")
                if (hiddenChecks < 64) add("fewer than 64 hidden-truth roots")
            },
        )
    }
}
