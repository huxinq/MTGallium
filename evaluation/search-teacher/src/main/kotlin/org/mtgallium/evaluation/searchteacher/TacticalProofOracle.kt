package org.mtgallium.evaluation.searchteacher

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.searchteacher.MonoRedInformationEvaluator
import org.mtgallium.agent.infoset.core.PolicyExpansion
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticOperationFamily

@Serializable
internal enum class TacticalTerminalOutcome { LOSS, DRAW, WIN }

@Serializable
internal enum class TacticalCertificationStatus { CERTIFIED, UNCERTIFIED, NOT_REQUESTED }

/**
 * Technical basis for a tactical accepted set. The serialized enum names predate the workflow
 * reset: CERTIFIED means an exact finite solver result, HUMAN_AUTHORITY means the supplied human
 * accepted set, and DIAGNOSTIC means neither. These values confer no project authority or approval.
 */
@Serializable
internal enum class TacticalEvidenceAuthority { CERTIFIED, HUMAN_AUTHORITY, DIAGNOSTIC }

@Serializable
internal data class TacticalProofActionValue(
    val choice: SemanticChoice,
    val outcome: TacticalTerminalOutcome,
    /** A winning witness or a loss/draw counterexample, never a claim of a uniquely best continuation. */
    val continuation: List<String>,
)

@Serializable
internal data class TacticalProofOracleDiagnostics(
    val expandedNodes: Int,
    val expandedEdges: Int,
    val compressedPasses: Int,
    val maximumStrategicDepth: Int,
    val transpositionHits: Int,
    val exhaustive: Boolean,
    val diagnostic: String? = null,
    val compressedForcedActions: Int = 0,
    val maximumRawDepth: Int = 0,
    val iterativeDeepeningPasses: Int = 0,
    val unresolvedBranchesEncountered: Int = 0,
    val cutoffReasons: List<String> = emptyList(),
)

@Serializable
internal data class TacticalProofVariantResult(
    val hiddenVariant: Int,
    val publicInformationStateDigest: String,
    val actionValues: List<TacticalProofActionValue>,
    val acceptedSignatures: Set<String>,
    val diagnostics: TacticalProofOracleDiagnostics,
)

@Serializable
internal data class TacticalProofCaseResult(
    val definition: TacticalProofCase,
    val variants: List<TacticalProofVariantResult>,
    val acceptedSignatures: Set<String>,
    val predicateSignatures: Set<String>,
    val predicateMatchesOracle: Boolean,
    val hiddenWorldsAgree: Boolean,
    val oraclePassed: Boolean,
    val authority: TacticalEvidenceAuthority = TacticalEvidenceAuthority.DIAGNOSTIC,
    val certificationStatus: TacticalCertificationStatus = TacticalCertificationStatus.UNCERTIFIED,
    val nondiscriminating: Boolean = false,
    val diagnostic: String? = null,
)

@Serializable
internal enum class TacticalProofReviewStatus { PENDING, COMPLETE }

@Serializable
internal data class TacticalProofHumanCaseReview(
    val caseId: String,
    val acceptableSignatures: Set<String>,
    val rationale: String,
)

@Serializable
internal data class TacticalProofHumanReview(
    val schemaVersion: Int = 1,
    val documentKind: String = "tactical-proof-human-review-v1",
    val suiteVersion: String = TACTICAL_PROOF_SUITE_VERSION,
    val blindedPacketSha256: String,
    val reviewerAlias: String,
    val completedAtUtc: String,
    val status: TacticalProofReviewStatus,
    val cases: List<TacticalProofHumanCaseReview>,
) {
    init {
        require(schemaVersion == 1 && documentKind == "tactical-proof-human-review-v1")
        require(suiteVersion == TACTICAL_PROOF_SUITE_VERSION)
        require(blindedPacketSha256.matches(Regex("[0-9a-f]{64}")))
        require(status == TacticalProofReviewStatus.COMPLETE)
        require(reviewerAlias.isNotBlank() && completedAtUtc.isNotBlank())
        require(cases.map { it.caseId }.distinct().size == cases.size)
        require(cases.all { it.rationale.isNotBlank() })
    }
}

@Serializable
internal data class TacticalProofAuthoringCase(
    val caseId: String,
    val category: TacticalProofCategory,
    val description: String,
    val informationState: PolicyInformationState,
    val expansion: PolicyExpansion,
)

@Serializable
internal data class TacticalProofAuthoringPacket(
    val schemaVersion: Int = 1,
    val documentKind: String = "tactical-proof-authoring-v1",
    val suiteVersion: String = TACTICAL_PROOF_SUITE_VERSION,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val cases: List<TacticalProofAuthoringCase>,
)

@Serializable
internal data class TacticalProofReport(
    val schemaVersion: Int = 1,
    val documentKind: String = "tactical-proof-report-v1",
    val suiteVersion: String = TACTICAL_PROOF_SUITE_VERSION,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val legacyDiagnosticSuite: String = "legacy-tactical-v1",
    val cases: List<TacticalProofCaseResult>,
    val oraclePassed: Boolean,
    val humanReviewStatus: TacticalProofReviewStatus,
    val humanReviewMatchesOracle: Boolean?,
    /** Legacy schema name: exact machine-proof/human-set agreement, not a workflow state. */
    val promotionPassed: Boolean,
    val failureReasons: List<String>,
)

internal data class TacticalProofRun(
    val packet: TacticalProofAuthoringPacket,
    val report: TacticalProofReport,
)

internal class TacticalProofUncertifiedException(
    message: String,
    val diagnostics: TacticalProofOracleDiagnostics,
) : IllegalStateException(message)

/**
 * Exact finite adversarial solver over the authored hidden-world set.
 *
 * It evaluates terminal WIN/DRAW/LOSS only. Equal actor information states must take the same
 * semantic action; policies may diverge only after their observations diverge. Resource limits,
 * cycles, non-exhaustive expansions, and nonterminal horizons fail closed as UNCERTIFIED.
 */
internal class TacticalProofOracle(
    private val maxStrategicDepth: Int = 96,
    private val maxExpandedNodes: Int = 250_000,
    private val maxWallClockMillis: Long? = null,
) {
    fun evaluate(case: TacticalProofCase, root: ArgentumSearchWorld, hiddenVariant: Int): TacticalProofVariantResult =
        evaluate(case, listOf(root)).single().copy(hiddenVariant = hiddenVariant)

    fun evaluate(case: TacticalProofCase, roots: List<ArgentumSearchWorld>): List<TacticalProofVariantResult> {
        require(roots.isNotEmpty())
        val expansions = roots.map { it.expandChoices(2_048) }
        require(expansions.all { it.isExhaustive }) { "${case.id} root expansion is not exhaustive" }
        val signatures = expansions.map { expansion -> expansion.candidates.map { it.signature }.toSet() }
        require(signatures.distinct().size == 1) { "${case.id} root actions differ across hidden worlds" }
        val counters = Counters()
        val memo = mutableMapOf<String, NodeBounds>()
        val firstChoices = expansions.first().candidates.associateBy { it.signature }
        val orderedRootSignatures = signatures.first().sortedWith(
            compareByDescending<String> { signature ->
                choicePriority(requireNotNull(firstChoices[signature]), stackPresent = false)
            }.thenBy { it }
        )
        val boundsBySignature = orderedRootSignatures.associateWith { NodeBounds.unknown() }.toMutableMap()

        for (depthLimit in depthSchedule()) {
            counters.iterativeDeepeningPasses++
            for (signature in orderedRootSignatures.filter { boundsBySignature.getValue(it).exactOutcome == null }) {
                if (counters.hardResourceLimitReached(maxExpandedNodes, maxWallClockMillis)) break
                val children = roots.zip(expansions).map { (root, expansion) ->
                    val choice = expansion.candidates.single { it.signature == signature }
                    val child = root.fork() as ArgentumSearchWorld
                    require(child.stepRaw(choice).accepted) { "${case.id} rejected root choice $signature" }
                    counters.expandedEdges++
                    child
                }
                boundsBySignature[signature] = solveForest(
                    case = case,
                    worlds = children,
                    strategicDepth = 1,
                    rawDepth = 1,
                    depthLimit = depthLimit,
                    counters = counters,
                    memo = memo,
                    active = mutableSetOf(),
                )
            }
            if (boundsBySignature.values.all { it.exactOutcome != null }) break
            if (counters.hardResourceLimitReached(maxExpandedNodes, maxWallClockMillis)) break
        }

        val unresolved = boundsBySignature.filterValues { it.exactOutcome == null }
        if (unresolved.isNotEmpty()) {
            val diagnostic = buildString {
                append("${case.id} has unresolved root bounds after proof search: ")
                append(unresolved.entries.joinToString { (signature, bounds) ->
                    val choice = requireNotNull(firstChoices[signature])
                    "${choice.display.label}=[${bounds.lower},${bounds.upper}]"
                })
                if (counters.cutoffReasons.isNotEmpty()) {
                    append("; cutoffs=")
                    append(counters.cutoffReasons.joinToString())
                }
            }
            throw TacticalProofUncertifiedException(
                diagnostic,
                counters.snapshot(exhaustive = false, diagnostic = diagnostic),
            )
        }

        val values = firstChoices.keys.sorted().map { signature ->
            val bounds = boundsBySignature.getValue(signature)
            TacticalProofActionValue(
                requireNotNull(firstChoices[signature]),
                requireNotNull(bounds.exactOutcome),
                listOf(signature) + bounds.continuation,
            )
        }
        val best = values.maxOf(TacticalProofActionValue::outcome)
        val accepted = values.filter { it.outcome == best }.map { it.choice.signature }.toSet()
        return roots.mapIndexed { index, root ->
            val choices = expansions[index].candidates.associateBy { it.signature }
            TacticalProofVariantResult(
                hiddenVariant = index + 1,
                publicInformationStateDigest = root.informationState(case.rootPlayer).informationStateDigest,
                actionValues = values.map { value -> value.copy(choice = requireNotNull(choices[value.choice.signature])) },
                acceptedSignatures = accepted,
                diagnostics = counters.snapshot(),
            )
        }
    }

    private fun solveForest(
        case: TacticalProofCase,
        worlds: List<ArgentumSearchWorld>,
        strategicDepth: Int,
        rawDepth: Int,
        depthLimit: Int,
        counters: Counters,
        memo: MutableMap<String, NodeBounds>,
        active: MutableSet<String>,
    ): NodeBounds {
        val terminal = worlds.mapNotNull { world -> world.terminalPayoff(case.rootPlayer)?.let(::terminalOutcome) }
        val live = worlds.filter { it.terminalPayoff(case.rootPlayer) == null }
        if (live.isEmpty()) {
            return NodeBounds.exact(terminal.minOrNull() ?: TacticalTerminalOutcome.DRAW)
        }
        counters.maximumRawDepth = maxOf(counters.maximumRawDepth, rawDepth)
        if (rawDepth > maxStrategicDepth * 8) {
            return counters.unknown("RAW_DEPTH_LIMIT:${maxStrategicDepth * 8}")
        }
        if (counters.expandedNodes >= maxExpandedNodes) {
            return counters.unknown("NODE_LIMIT:$maxExpandedNodes")
        }
        if (maxWallClockMillis != null && counters.elapsedMillis() >= maxWallClockMillis) {
            return counters.unknown("WALL_CLOCK_LIMIT:${maxWallClockMillis}ms")
        }

        val groups = live.groupBy(::decisionKey)
        if (groups.size > 1 || terminal.isNotEmpty()) {
            val values = buildList {
                terminal.forEach { add(NodeBounds.exact(it)) }
                groups.toSortedMap().values.forEach { group ->
                    add(solveForest(
                        case, group, strategicDepth, rawDepth, depthLimit, counters, memo, active,
                    ))
                    if (last().exactOutcome == TacticalTerminalOutcome.LOSS) return@buildList
                }
            }
            return combineBounds(values, maximizing = false)
        }

        val group = groups.values.single()
        val key = beliefKey(group)
        memo[key]?.let {
            counters.transpositionHits++
            return it
        }
        if (!active.add(key)) return counters.unknown("STRATEGIC_STATE_CYCLE")
        return try {
            counters.expandedNodes++
            val expansions = group.map { it.expandChoices(2_048) }
            if (expansions.any { !it.isExhaustive || it.candidates.isEmpty() }) {
                return counters.unknown("NONEXHAUSTIVE_OR_EMPTY_EXPANSION")
            }
            val signatures = expansions.map { expansion -> expansion.candidates.map { it.signature }.toSet() }
            if (signatures.distinct().size != 1) {
                return counters.unknown("EQUAL_INFORMATION_ACTION_MISMATCH")
            }
            val actor = requireNotNull(group.first().actorToAct())
            val maximizing = actor == case.rootPlayer
            val candidates = expansions.first().candidates

            if (candidates.size == 1) {
                val choice = candidates.single()
                val children = applyChoice(case, group, expansions, choice, counters)
                counters.compressedForcedActions++
                if (choice.operationFamily == SemanticOperationFamily.PASS_PRIORITY) counters.compressedPasses++
                val result = solveForest(
                    case, children, strategicDepth, rawDepth + 1, depthLimit, counters, memo, active,
                ).prepend(choice.signature)
                if (result.exactOutcome != null) memo[key] = result
                return result
            }

            if (strategicDepth > depthLimit) {
                return counters.unknown("STRATEGIC_DEPTH_LIMIT:$depthLimit")
            }
            counters.maximumStrategicDepth = maxOf(counters.maximumStrategicDepth, strategicDepth)
            val stackPresent = group.first().informationState(actor).observation.stack.isNotEmpty()
            val transitions = candidates.map { choice ->
                val children = applyChoice(case, group, expansions, choice, counters)
                OrderedTransition(
                    choice = choice,
                    children = children,
                    immediateRootOutcome = children.mapNotNull {
                        it.terminalPayoff(case.rootPlayer)?.let(::terminalOutcome)
                    }.takeIf { it.size == children.size }?.minOrNull(),
                    actorPolicyScore = children.minOfOrNull { child -> actorPolicyScore(child, actor) } ?: 0.0,
                    tacticalPriority = choicePriority(choice, stackPresent),
                )
            }.sortedWith(transitionComparator(maximizing))

            val evaluated = mutableListOf<Pair<SemanticChoice, NodeBounds>>()
            for (transition in transitions) {
                val childBounds = solveForest(
                    case,
                    transition.children,
                    strategicDepth + 1,
                    rawDepth + 1,
                    depthLimit,
                    counters,
                    memo,
                    active,
                )
                evaluated += transition.choice to childBounds
                val aggregate = combineChoiceBounds(evaluated, maximizing)
                if (maximizing && aggregate.exactOutcome == TacticalTerminalOutcome.WIN) break
                if (!maximizing && aggregate.exactOutcome == TacticalTerminalOutcome.LOSS) break
            }
            val result = combineChoiceBounds(evaluated, maximizing)
            if (result.exactOutcome != null) memo[key] = result
            result
        } finally {
            active.remove(key)
        }
    }

    private fun applyChoice(
        case: TacticalProofCase,
        worlds: List<ArgentumSearchWorld>,
        expansions: List<PolicyExpansion>,
        choice: SemanticChoice,
        counters: Counters,
    ): List<ArgentumSearchWorld> = worlds.zip(expansions).map { (world, expansion) ->
        val matching = expansion.candidates.single { it.signature == choice.signature }
        val child = world.fork() as ArgentumSearchWorld
        require(child.stepRaw(matching).accepted) {
            "${case.id} rejected ${choice.signature} during proof"
        }
        counters.expandedEdges++
        child
    }

    private fun terminalOutcome(payoff: Double): TacticalTerminalOutcome = when {
        payoff > 0.0 -> TacticalTerminalOutcome.WIN
        payoff < 0.0 -> TacticalTerminalOutcome.LOSS
        else -> TacticalTerminalOutcome.DRAW
    }

    private fun decisionKey(world: ArgentumSearchWorld): String {
        val actor = requireNotNull(world.actorToAct())
        return "$actor:${world.informationState(actor).informationStateDigest}"
    }

    /** Memo identity is built from player information states, never the authoritative full-truth fingerprint. */
    private fun beliefKey(worlds: List<ArgentumSearchWorld>): String = worlds.map { world ->
        val actor = requireNotNull(world.actorToAct())
        val players = world.informationState(actor).observation.players.map { it.playerId }.sorted()
        "$actor:" + players.joinToString(":") { player -> world.informationState(player).informationStateDigest }
    }.sorted().joinToString("|")

    private fun depthSchedule(): List<Int> = buildList {
        var depth = minOf(8, maxStrategicDepth)
        while (true) {
            add(depth)
            if (depth == maxStrategicDepth) break
            depth = minOf(maxStrategicDepth, depth * 2)
        }
    }

    private fun transitionComparator(maximizing: Boolean): Comparator<OrderedTransition> =
        compareByDescending<OrderedTransition> { transition ->
            transition.immediateRootOutcome?.let { outcome ->
                val actorOutcome = if (maximizing) {
                    outcome.ordinal
                } else {
                    TacticalTerminalOutcome.WIN.ordinal - outcome.ordinal
                }
                when (actorOutcome) {
                    TacticalTerminalOutcome.WIN.ordinal -> 3
                    TacticalTerminalOutcome.DRAW.ordinal -> 2
                    else -> -1
                }
            } ?: 0
        }.thenByDescending(OrderedTransition::tacticalPriority)
            .thenByDescending(OrderedTransition::actorPolicyScore)
            .thenBy { it.choice.signature }

    private fun choicePriority(choice: SemanticChoice, stackPresent: Boolean): Int {
        fun assignmentCount(field: String): Int = choice.canonicalPayload["body"]?.jsonObject
            ?.get(field)?.jsonObject?.size ?: 0
        return when (choice.operationFamily) {
            SemanticOperationFamily.DECLARE_ATTACKERS -> 1_000 + assignmentCount("attackers") * 10
            SemanticOperationFamily.DECLARE_BLOCKERS -> 950 + assignmentCount("blockers") * 10
            SemanticOperationFamily.DECISION_RESPONSE -> 900
            SemanticOperationFamily.CAST_SPELL -> 850
            SemanticOperationFamily.ACTIVATE_ABILITY -> 800
            SemanticOperationFamily.PLAY_LAND -> 700
            SemanticOperationFamily.MANA_ABILITY -> 600
            SemanticOperationFamily.PASS_PRIORITY -> if (stackPresent) 875 else 100
            else -> 500
        }
    }

    private fun actorPolicyScore(world: ArgentumSearchWorld, actor: String): Double {
        world.terminalPayoff(actor)?.let { return it * 1_000_000.0 }
        return runCatching {
            MonoRedInformationEvaluator.evaluate(world.informationState(actor), actor)
        }.getOrDefault(0.0)
    }

    private fun combineChoiceBounds(
        values: List<Pair<SemanticChoice, NodeBounds>>,
        maximizing: Boolean,
    ): NodeBounds {
        require(values.isNotEmpty())
        val combined = combineBounds(values.map(Pair<SemanticChoice, NodeBounds>::second), maximizing)
        val target = combined.exactOutcome
        val selected = (if (target != null) {
            values.firstOrNull { it.second.exactOutcome == target }
        } else if (maximizing) {
            values.maxWithOrNull(compareBy<Pair<SemanticChoice, NodeBounds>> { it.second.lower }
                .thenBy { it.second.upper })
        } else {
            values.minWithOrNull(compareBy<Pair<SemanticChoice, NodeBounds>> { it.second.upper }
                .thenBy { it.second.lower })
        }) ?: values.first()
        return combined.copy(continuation = listOf(selected.first.signature) + selected.second.continuation)
    }

    private fun combineBounds(values: List<NodeBounds>, maximizing: Boolean): NodeBounds {
        require(values.isNotEmpty())
        val lower = if (maximizing) values.maxOf(NodeBounds::lower) else values.minOf(NodeBounds::lower)
        val upper = if (maximizing) values.maxOf(NodeBounds::upper) else values.minOf(NodeBounds::upper)
        val exact = lower == upper
        val selected = (if (exact) {
            values.firstOrNull { it.exactOutcome == lower }
        } else if (maximizing) {
            values.maxWithOrNull(compareBy<NodeBounds> { it.lower }.thenBy { it.upper })
        } else {
            values.minWithOrNull(compareBy<NodeBounds> { it.upper }.thenBy { it.lower })
        }) ?: values.first()
        return NodeBounds(lower, upper, selected.continuation)
    }

    private data class NodeBounds(
        val lower: TacticalTerminalOutcome,
        val upper: TacticalTerminalOutcome,
        val continuation: List<String>,
    ) {
        init { require(lower <= upper) }
        val exactOutcome: TacticalTerminalOutcome? get() = lower.takeIf { it == upper }
        fun prepend(signature: String) = copy(continuation = listOf(signature) + continuation)

        companion object {
            fun exact(outcome: TacticalTerminalOutcome) = NodeBounds(outcome, outcome, emptyList())
            fun unknown() = NodeBounds(TacticalTerminalOutcome.LOSS, TacticalTerminalOutcome.WIN, emptyList())
        }
    }

    private data class OrderedTransition(
        val choice: SemanticChoice,
        val children: List<ArgentumSearchWorld>,
        val immediateRootOutcome: TacticalTerminalOutcome?,
        val actorPolicyScore: Double,
        val tacticalPriority: Int,
    )

    private class Counters {
        private val startedNanos: Long = System.nanoTime()
        var expandedNodes: Int = 0
        var expandedEdges: Int = 0
        var compressedPasses: Int = 0
        var compressedForcedActions: Int = 0
        var maximumStrategicDepth: Int = 0
        var maximumRawDepth: Int = 0
        var transpositionHits: Int = 0
        var iterativeDeepeningPasses: Int = 0
        var unresolvedBranchesEncountered: Int = 0
        val cutoffReasons: LinkedHashSet<String> = linkedSetOf()
        fun elapsedMillis(): Long = (System.nanoTime() - startedNanos) / 1_000_000L
        fun hardResourceLimitReached(maxNodes: Int, maxMillis: Long?): Boolean =
            expandedNodes >= maxNodes || maxMillis?.let { elapsedMillis() >= it } == true

        fun unknown(reason: String): NodeBounds {
            unresolvedBranchesEncountered++
            cutoffReasons += reason
            return NodeBounds.unknown()
        }

        fun snapshot(exhaustive: Boolean = true, diagnostic: String? = null) = TacticalProofOracleDiagnostics(
            expandedNodes,
            expandedEdges,
            compressedPasses,
            maximumStrategicDepth,
            transpositionHits,
            exhaustive = exhaustive,
            diagnostic = diagnostic,
            compressedForcedActions = compressedForcedActions,
            maximumRawDepth = maximumRawDepth,
            iterativeDeepeningPasses = iterativeDeepeningPasses,
            unresolvedBranchesEncountered = unresolvedBranchesEncountered,
            cutoffReasons = cutoffReasons.toList(),
        )
    }
}

internal class TacticalProofRunner(
    registry: com.wingedsheep.engine.registry.CardRegistry,
    manifest: DeckManifest,
    private val oracle: TacticalProofOracle = TacticalProofOracle(),
) {
    private val factory = TacticalProofScenarioFactory(registry, manifest)

    fun run(review: TacticalProofHumanReview? = null, blindedPacketSha256: String? = null): TacticalProofRun {
        TacticalProofCatalog.validate()
        val generatedAt = Instant.now().toString()
        val authoringCases = mutableListOf<TacticalProofAuthoringCase>()
        val results = TacticalProofCatalog.cases.map { case ->
            val worlds = (1..2).map { variant -> factory.create(case, variant) }
            val states = worlds.map { it.informationState(case.rootPlayer) }
            val expansions = worlds.map { it.expandChoices(2_048) }
            val publicAgreement = states.map(PolicyInformationState::informationStateDigest).distinct().size == 1 &&
                expansions.map { expansion -> expansion.candidates.map { it.signature }.toSet() }.distinct().size == 1
            val predicateSignatures = expansions.first().candidates
                .filter { case.acceptedPattern.matches(it, states.first()) }
                .map(SemanticChoice::signature)
                .toSet()
            require(predicateSignatures.isNotEmpty()) { "${case.id} accepted predicate matches no root action" }
            if (authoringCases.none { it.caseId == case.id }) {
                authoringCases += TacticalProofAuthoringCase(case.id, case.category, case.description, states[0], expansions[0])
            }
            if (case.expiry != TacticalProofExpiry.TERMINAL) {
                return@map TacticalProofCaseResult(
                    definition = case,
                    variants = emptyList(),
                    acceptedSignatures = emptySet(),
                    predicateSignatures = predicateSignatures,
                    predicateMatchesOracle = false,
                    hiddenWorldsAgree = publicAgreement,
                    oraclePassed = false,
                    authority = TacticalEvidenceAuthority.DIAGNOSTIC,
                    certificationStatus = TacticalCertificationStatus.NOT_REQUESTED,
                    diagnostic = "NONTERMINAL_AUTHORED_CUTOFF_HAS_NO_MACHINE_AUTHORITY",
                )
            }
            val variants = runCatching { oracle.evaluate(case, worlds) }
            variants.fold(
                onSuccess = { values ->
                    val accepted = values.first().acceptedSignatures
                    val agree = publicAgreement && values.all { it.acceptedSignatures == accepted } &&
                        values.map { value -> value.actionValues.associate { it.choice.signature to it.outcome } }.distinct().size == 1
                    val predicateMatches = accepted == predicateSignatures
                    val nondiscriminating = values.first().actionValues.map { it.outcome }.distinct().size == 1
                    val diagnostic = when {
                        !agree -> "HIDDEN_WORLD_DISAGREEMENT"
                        !predicateMatches -> "AUTHORED_PREDICATE_DISAGREES_WITH_CERTIFICATE"
                        nondiscriminating -> "ALL_ROOT_ACTIONS_HAVE_THE_SAME_TERMINAL_OUTCOME"
                        else -> null
                    }
                    TacticalProofCaseResult(
                        definition = case,
                        variants = values,
                        acceptedSignatures = accepted,
                        predicateSignatures = predicateSignatures,
                        predicateMatchesOracle = predicateMatches,
                        hiddenWorldsAgree = agree,
                        oraclePassed = agree && predicateMatches,
                        authority = if (agree && predicateMatches && !nondiscriminating) {
                            TacticalEvidenceAuthority.CERTIFIED
                        } else TacticalEvidenceAuthority.DIAGNOSTIC,
                        certificationStatus = TacticalCertificationStatus.CERTIFIED,
                        nondiscriminating = nondiscriminating,
                        diagnostic = diagnostic,
                    )
                },
                onFailure = { failure ->
                    TacticalProofCaseResult(
                        definition = case,
                        variants = emptyList(),
                        acceptedSignatures = emptySet(),
                        predicateSignatures = predicateSignatures,
                        predicateMatchesOracle = false,
                        hiddenWorldsAgree = publicAgreement,
                        oraclePassed = false,
                        authority = TacticalEvidenceAuthority.DIAGNOSTIC,
                        certificationStatus = TacticalCertificationStatus.UNCERTIFIED,
                        diagnostic = failure.message,
                    )
                },
            )
        }
        val packet = TacticalProofAuthoringPacket(
            generatedAtUtc = generatedAt,
            outerCommit = currentOuterCommit(),
            argentumCommit = currentArgentumCommit(),
            cases = authoringCases,
        )
        val requested = results.filter { it.definition.expiry == TacticalProofExpiry.TERMINAL }
        val oraclePassed = requested.isNotEmpty() && requested.all(TacticalProofCaseResult::oraclePassed)
        val reviewMatch = if (review == null) null else {
            requireNotNull(blindedPacketSha256) { "A human review requires the blinded packet digest" }
            review.blindedPacketSha256 == blindedPacketSha256 &&
                review.cases.map { it.caseId }.toSet() == results.map { it.definition.id }.toSet() &&
                results.filter { it.authority == TacticalEvidenceAuthority.CERTIFIED }.all { result ->
                    review.cases.single { it.caseId == result.definition.id }.acceptableSignatures == result.acceptedSignatures
                }
        }
        val failures = buildList {
            requested.filterNot(TacticalProofCaseResult::oraclePassed).forEach { add("${it.definition.id}:${it.diagnostic}") }
            if (review == null) add("BLINDED_HUMAN_REVIEW_PENDING")
            else if (reviewMatch != true) add("BLINDED_HUMAN_REVIEW_DISAGREES")
        }
        return TacticalProofRun(
            packet,
            TacticalProofReport(
                generatedAtUtc = generatedAt,
                outerCommit = currentOuterCommit(),
                argentumCommit = currentArgentumCommit(),
                cases = results,
                oraclePassed = oraclePassed,
                humanReviewStatus = review?.status ?: TacticalProofReviewStatus.PENDING,
                humanReviewMatchesOracle = reviewMatch,
                promotionPassed = oraclePassed && reviewMatch == true,
                failureReasons = failures,
            ),
        )
    }

    fun applyReview(
        run: TacticalProofRun,
        review: TacticalProofHumanReview,
        blindedPacketSha256: String,
    ): TacticalProofRun {
        val matches = review.blindedPacketSha256 == blindedPacketSha256 &&
            review.cases.map { it.caseId }.toSet() == run.report.cases.map { it.definition.id }.toSet() &&
            run.report.cases.filter { it.authority == TacticalEvidenceAuthority.CERTIFIED }.all { result ->
                review.cases.single { it.caseId == result.definition.id }.acceptableSignatures ==
                    result.acceptedSignatures
            }
        val failures = buildList {
            run.report.cases.filter { it.definition.expiry == TacticalProofExpiry.TERMINAL }
                .filterNot(TacticalProofCaseResult::oraclePassed).forEach {
                add("${it.definition.id}:${it.diagnostic}")
            }
            if (!matches) add("BLINDED_HUMAN_REVIEW_DISAGREES")
        }
        return run.copy(
            report = run.report.copy(
                humanReviewStatus = review.status,
                humanReviewMatchesOracle = matches,
                promotionPassed = run.report.oraclePassed && matches,
                failureReasons = failures,
            )
        )
    }
}
