package org.mtgallium.evaluation.searchteacher

import java.time.Instant
import kotlinx.serialization.Serializable
import org.mtgallium.agent.infoset.argentum.ArgentumSearchWorld
import org.mtgallium.agent.infoset.core.PolicyCardView
import org.mtgallium.agent.infoset.core.PolicyInformationState
import org.mtgallium.agent.infoset.core.PolicyManaPool
import org.mtgallium.agent.infoset.core.SemanticChoice
import org.mtgallium.agent.infoset.core.SemanticOperationFamily

internal const val TACTICAL_HORIZON_CONFORMANCE_VERSION = "tactical-horizon-conformance-v1"

/**
 * Independently authored expectations for the public root state. These are deliberately separate
 * from the scenario factory so a setup regression cannot update its own test oracle.
 */
@Serializable
internal data class TacticalHorizonPlayerContract(
    val life: Int,
    val handSize: Int,
    val librarySize: Int? = null,
    val redMana: Int = 0,
    val colorlessMana: Int = 0,
    val speed: Int = 0,
    /** Null means the zone is hidden and only its size is asserted. */
    val visibleHandCards: Map<String, Int>? = emptyMap(),
    val battlefieldCards: Map<String, Int> = emptyMap(),
    val tappedBattlefieldCards: Map<String, Int> = emptyMap(),
    val damagedBattlefieldCards: Map<String, Int> = emptyMap(),
    /** Exact known-deck cards not assigned to a public/known object. */
    val unlocatedCardCounts: Map<String, Int>? = null,
)

@Serializable
internal data class TacticalHorizonRootContract(
    val caseId: String,
    val actor: String = "p0",
    val phase: String,
    val step: String,
    val activePlayer: String = "p0",
    val priorityPlayer: String = actor,
    val p0: TacticalHorizonPlayerContract,
    val p1: TacticalHorizonPlayerContract,
    val stackNames: List<String> = emptyList(),
    val pendingDecisionKind: String? = null,
    val pendingDecisionSourceName: String? = null,
    val attackingCards: Map<String, Int> = emptyMap(),
    val requireEpistemicallyComplete: Boolean = true,
)

internal object TacticalHorizonContractCatalog {
    val contracts: Map<String, TacticalHorizonRootContract> = listOf(
        root("immediate-01", "BEGINNING", "DRAW",
            p(2, 2, 0, red = 2, hand = cards("Shock", "Shock"),
                field = cards("Mountain", "Mountain"), tapped = cards("Mountain", "Mountain"),
                unlocated = emptyMap()),
            p(2, 1, 0, hand = null, field = cards("Mountain", "Mountain"),
                unlocated = cards("Shock"))),
        root("immediate-02", "PRECOMBAT_MAIN", "PRECOMBAT_MAIN",
            p(2, 1, red = 1, hand = cards("Shock")),
            p(2, 0, hand = null, field = cards("Mountain"), tapped = cards("Mountain")),
            stack = listOf("Shock")),
        root("immediate-03", "COMBAT", "DECLARE_ATTACKERS",
            p(1, 1, red = 1, hand = cards("Shock"), field = cards("Mountain", "Hired Claw"),
                tapped = cards("Mountain", "Hired Claw")),
            p(1, 0, hand = null, field = cards("Magebane Lizard")),
            stack = listOf("Hired Claw"), attacks = cards("Hired Claw")),
        root("immediate-04", "COMBAT", "BEGIN_COMBAT",
            p(4, 0, red = 2, field = cards("Hired Claw")),
            p(3, 0, hand = null, field = cards("Nova Hellkite"), tapped = cards("Nova Hellkite"))),
        root("immediate-05", "COMBAT", "BEGIN_COMBAT",
            p(4, 0, red = 2, speed = 4, field = cards("Burnout Bashtronaut")),
            p(3, 0, hand = null, field = cards("Nova Hellkite"), tapped = cards("Nova Hellkite"))),
        root("immediate-06", "POSTCOMBAT_MAIN", "POSTCOMBAT_MAIN",
            p(4, 1, red = 1, hand = cards("Shock"), field = cards("Ojer Axonil, Deepest Might"),
                tapped = cards("Ojer Axonil, Deepest Might")),
            p(4, 0, hand = null, field = cards("Nova Hellkite"), tapped = cards("Nova Hellkite"))),
        root("immediate-07", "COMBAT", "DECLARE_BLOCKERS",
            p(4, 0, 1, field = cards("Nova Hellkite", "Magebane Lizard"),
                unlocated = cards("Mountain")),
            p(4, 0, 2, hand = null, field = cards("Nova Hellkite", "Sunspine Lynx"),
                tapped = cards("Nova Hellkite", "Sunspine Lynx"), unlocated = cards("Mountain", "Mountain")),
            active = "p1", attacks = cards("Nova Hellkite", "Sunspine Lynx")),

        root("within-turn-01", "PRECOMBAT_MAIN", "PRECOMBAT_MAIN",
            p(4, 0, red = 1, field = cards("Hired Claw", "Rockface Village")),
            p(2, 0, hand = null, field = cards("Nova Hellkite"), tapped = cards("Nova Hellkite"))),
        root("within-turn-02", "PRECOMBAT_MAIN", "PRECOMBAT_MAIN",
            p(4, 1, red = 1, hand = cards("Burnout Bashtronaut"), field = cards("Howlsquad Heavy")),
            p(4, 0, hand = null, field = cards("Nova Hellkite"), tapped = cards("Nova Hellkite"))),
        root("within-turn-03", "PRECOMBAT_MAIN", "PRECOMBAT_MAIN",
            p(4, 2, red = 3, hand = cards("Nova Hellkite", "Shock")),
            p(4, 0, hand = null, field = cards("Nova Hellkite", "Burnout Bashtronaut"),
                tapped = cards("Nova Hellkite"))),
        root("within-turn-04", "PRECOMBAT_MAIN", "PRECOMBAT_MAIN",
            p(1, 3, red = 3, hand = cards("Mountain", "Soulstone Sanctuary", "Sunspine Lynx"),
                field = cards("Mountain", "Mountain", "Mountain"),
                tapped = cards("Mountain", "Mountain", "Mountain")),
            p(2, 0, hand = null, field = cards("Rockface Village", "Soulstone Sanctuary", "Nova Hellkite"),
                tapped = cards("Nova Hellkite"))),
        root("within-turn-05", "COMBAT", "DECLARE_ATTACKERS",
            p(4, 0, field = cards("Hired Claw", "Hexing Squelcher")),
            p(4, 0, hand = null, field = cards("Nova Hellkite"), tapped = cards("Nova Hellkite"))),
        root("within-turn-06", "PRECOMBAT_MAIN", "PRECOMBAT_MAIN",
            p(4, 0, field = cards("Hired Claw", "Hexing Squelcher", "Nova Hellkite")),
            p(8, 0, speed = 1, hand = null, field = cards("Burnout Bashtronaut", "Magebane Lizard", "Nova Hellkite"),
                tapped = cards("Magebane Lizard", "Nova Hellkite")),
            priority = "p1", pendingKind = "CHOOSE_TARGETS", pendingSource = "Nova Hellkite"),
        root("within-turn-07", "PRECOMBAT_MAIN", "PRECOMBAT_MAIN",
            p(4, 3, colorless = 1, hand = cards("Mountain", "Soulstone Sanctuary", "Lightning Strike")),
            p(3, 0, hand = null, field = cards("Nova Hellkite"), tapped = cards("Nova Hellkite"))),

        root("short-01", "COMBAT", "DECLARE_BLOCKERS",
            p(2, 0, 1, field = cards("Hired Claw", "Nova Hellkite"), unlocated = cards("Mountain")),
            p(4, 0, 2, hand = null, field = cards("Sunspine Lynx", "Hexing Squelcher"),
                tapped = cards("Sunspine Lynx", "Hexing Squelcher"), unlocated = cards("Mountain", "Mountain")),
            active = "p1", attacks = cards("Sunspine Lynx", "Hexing Squelcher")),
        root("short-02", "ENDING", "END",
            p(2, 1, 1, red = 1, hand = cards("Shock"), field = cards("Mountain", "Mountain"),
                tapped = cards("Mountain", "Mountain"), unlocated = cards("Shock")),
            p(2, 1, 0, hand = null, field = cards("Mountain", "Mountain"), unlocated = cards("Shock")),
            active = "p1"),
        root("short-03", "COMBAT", "DECLARE_BLOCKERS",
            p(6, 0, 1, field = cards("Hexing Squelcher"), unlocated = cards("Mountain")),
            p(2, 0, 2, hand = null, field = cards("Sunspine Lynx"), tapped = cards("Sunspine Lynx"),
                unlocated = cards("Mountain", "Mountain")),
            active = "p1", attacks = cards("Sunspine Lynx")),
        root("short-04", "ENDING", "END",
            p(4, 0, red = 2, field = cards("Hired Claw")),
            p(3, 0, hand = null, field = cards("Nova Hellkite"), tapped = cards("Nova Hellkite")),
            active = "p1"),
        root("short-05", "POSTCOMBAT_MAIN", "POSTCOMBAT_MAIN",
            p(1, 0, 1, field = cards("Nova Hellkite"), unlocated = cards("Mountain")),
            p(4, 0, 2, hand = null, field = cards("Nova Hellkite", "Magebane Lizard"),
                damaged = cards("Nova Hellkite"), unlocated = cards("Mountain", "Mountain")),
            priority = "p1", pendingKind = "CHOOSE_TARGETS", pendingSource = "Nova Hellkite"),
        root("short-06", "ENDING", "END",
            p(1, 1, 1, red = 1, hand = cards("Shock"), field = cards("Hired Claw", "Hexing Squelcher"),
                unlocated = cards("Mountain")),
            p(3, 0, 2, hand = null, field = cards("Razorkin Needlehead"),
                unlocated = cards("Mountain", "Mountain")), active = "p1"),
        root("short-07", "COMBAT", "BEGIN_COMBAT",
            p(5, 0, 1, colorless = 4, field = cards("Soulstone Sanctuary"), unlocated = cards("Mountain")),
            p(20, 0, 0, hand = null, field = cards("Sunspine Lynx"), unlocated = emptyMap()),
            active = "p1"),

        root("long-01", "PRECOMBAT_MAIN", "PRECOMBAT_MAIN",
            p(10, 0, 3, red = 2, field = cards("Hired Claw"), unlocated = cards("Mountain", "Mountain", "Mountain")),
            p(12, 0, 4, hand = null, unlocated = cards("Mountain", "Mountain", "Mountain", "Mountain"))),
        root("long-02", "PRECOMBAT_MAIN", "PRECOMBAT_MAIN",
            p(10, 1, 3, red = 3, hand = cards("Howlsquad Heavy"),
                unlocated = cards("Mountain", "Mountain", "Mountain")),
            p(16, 0, 4, hand = null, unlocated = cards("Mountain", "Mountain", "Mountain", "Mountain"))),
        root("long-03", "PRECOMBAT_MAIN", "PRECOMBAT_MAIN",
            p(10, 1, 2, red = 2, hand = cards("Razorkin Needlehead"),
                unlocated = cards("Mountain", "Mountain")),
            p(3, 0, 3, hand = null, unlocated = cards("Mountain", "Mountain", "Mountain"))),
        root("long-04", "PRECOMBAT_MAIN", "PRECOMBAT_MAIN",
            p(10, 0, 2, colorless = 4, field = cards("Soulstone Sanctuary"),
                unlocated = cards("Mountain", "Mountain")),
            p(9, 0, 3, hand = null, unlocated = cards("Mountain", "Mountain", "Mountain"))),
        root("long-05", "COMBAT", "DECLARE_BLOCKERS",
            p(6, 0, 3, field = cards("Magebane Lizard", "Razorkin Needlehead"),
                unlocated = cards("Mountain", "Mountain", "Mountain")),
            p(3, 0, 4, hand = null, field = cards("Hexing Squelcher", "Sunspine Lynx"),
                tapped = cards("Hexing Squelcher"),
                unlocated = cards("Mountain", "Mountain", "Mountain", "Mountain")),
            active = "p1", attacks = cards("Hexing Squelcher")),
        root("long-06", "PRECOMBAT_MAIN", "PRECOMBAT_MAIN",
            p(10, 1, 2, red = 3, hand = cards("Nova Hellkite"),
                field = List(5) { "Mountain" }.toCardCounts(), tapped = List(5) { "Mountain" }.toCardCounts(),
                unlocated = cards("Mountain", "Mountain")),
            p(12, 0, 3, hand = null, field = cards("Burnout Bashtronaut"),
                unlocated = cards("Mountain", "Mountain", "Mountain"))),
        root("long-07", "PRECOMBAT_MAIN", "PRECOMBAT_MAIN",
            p(4, 1, 4, red = 5, hand = cards("Nova Hellkite"),
                unlocated = cards("Mountain", "Mountain", "Mountain", "Mountain")),
            p(20, 0, 3, hand = null, field = cards("Nova Hellkite"), tapped = cards("Nova Hellkite"),
                unlocated = cards("Mountain", "Mountain", "Mountain"))),
    ).associateBy(TacticalHorizonRootContract::caseId)

    fun validateCatalog() {
        val ids = TacticalHorizonCatalog.cases.map(TacticalHorizonCase::id).toSet()
        require(contracts.keys == ids) {
            "Root contracts differ from suite: missing=${ids - contracts.keys}, extra=${contracts.keys - ids}"
        }
    }

    fun validate(case: TacticalHorizonCase, information: PolicyInformationState): List<String> {
        val contract = requireNotNull(contracts[case.id])
        val observation = information.observation
        val failures = mutableListOf<String>()
        fun check(name: String, expected: Any?, actual: Any?) {
            if (expected != actual) failures += "$name expected=$expected actual=$actual"
        }
        check("actor", contract.actor, information.actingPlayerId)
        check("phase", contract.phase, observation.phase)
        check("step", contract.step, observation.step)
        check("activePlayer", contract.activePlayer, observation.activePlayerId)
        check("priorityPlayer", contract.priorityPlayer, observation.priorityPlayerId)
        check("stackNames", contract.stackNames, observation.stack.map { it.name })
        check("pendingDecisionKind", contract.pendingDecisionKind, observation.pendingDecision?.decisionKind)
        check("pendingDecisionSourceName", contract.pendingDecisionSourceName, observation.pendingDecision?.sourceName)
        check("attackingCards", contract.attackingCards, attackingCardCounts(information))
        check("epistemicallyComplete", contract.requireEpistemicallyComplete, information.knowledge.epistemicallyComplete)
        validatePlayer("p0", contract.p0, information, failures)
        validatePlayer("p1", contract.p1, information, failures)
        return failures
    }

    private fun validatePlayer(
        playerId: String,
        contract: TacticalHorizonPlayerContract,
        information: PolicyInformationState,
        failures: MutableList<String>,
    ) {
        val observation = information.observation
        val player = observation.players.single { it.playerId == playerId }
        fun check(name: String, expected: Any?, actual: Any?) {
            if (expected != actual) failures += "$playerId.$name expected=$expected actual=$actual"
        }
        check("life", contract.life, player.life)
        check("handSize", contract.handSize, player.handSize)
        contract.librarySize?.let { check("librarySize", it, player.librarySize) }
        check("mana", PolicyManaPool(red = contract.redMana, colorless = contract.colorlessMana), player.mana)
        check("speed", contract.speed, player.speed)
        val visibleHand = zoneCards(information, playerId, "HAND")
        contract.visibleHandCards?.let { check("visibleHandCards", it, visibleHand.cardCounts()) }
        val battlefield = zoneCards(information, playerId, "BATTLEFIELD")
        check("battlefieldCards", contract.battlefieldCards, battlefield.cardCounts())
        check("tappedBattlefieldCards", contract.tappedBattlefieldCards,
            battlefield.filter(PolicyCardView::tapped).cardCounts())
        check("damagedBattlefieldCards", contract.damagedBattlefieldCards,
            battlefield.filter { it.damageMarked > 0 }.cardCounts())
        contract.unlocatedCardCounts?.let { expected ->
            check("unlocatedCardCounts", expected, information.knowledge.unlocatedCardCounts[playerId].orEmpty())
        }
    }

    private fun attackingCardCounts(information: PolicyInformationState): Map<String, Int> {
        val names = information.observation.zones.flatMap { it.cards }.associate { it.objectRef to it.name }
        return information.observation.combat?.attackers.orEmpty()
            .map { attacker -> names[attacker.attackerObjectRef] ?: "<unknown>" }
            .groupingBy { it }.eachCount().toSortedMap()
    }

    private fun zoneCards(information: PolicyInformationState, playerId: String, zone: String): List<PolicyCardView> =
        information.observation.zones.single { it.ownerId == playerId && it.zone == zone }.cards

    private fun List<PolicyCardView>.cardCounts(): Map<String, Int> =
        map(PolicyCardView::name).groupingBy { it }.eachCount().toSortedMap()

    private fun cards(vararg names: String): Map<String, Int> = names.toList().toCardCounts()
    private fun List<String>.toCardCounts(): Map<String, Int> = groupingBy { it }.eachCount().toSortedMap()

    private fun p(
        life: Int,
        handSize: Int,
        librarySize: Int? = null,
        red: Int = 0,
        colorless: Int = 0,
        speed: Int = 0,
        hand: Map<String, Int>? = emptyMap(),
        field: Map<String, Int> = emptyMap(),
        tapped: Map<String, Int> = emptyMap(),
        damaged: Map<String, Int> = emptyMap(),
        unlocated: Map<String, Int>? = null,
    ) = TacticalHorizonPlayerContract(
        life, handSize, librarySize, red, colorless, speed, hand, field, tapped, damaged, unlocated,
    )

    private fun root(
        id: String,
        phase: String,
        step: String,
        p0: TacticalHorizonPlayerContract,
        p1: TacticalHorizonPlayerContract,
        active: String = "p0",
        priority: String = "p0",
        stack: List<String> = emptyList(),
        pendingKind: String? = null,
        pendingSource: String? = null,
        attacks: Map<String, Int> = emptyMap(),
    ) = TacticalHorizonRootContract(
        id, phase = phase, step = step, activePlayer = active, priorityPlayer = priority,
        p0 = p0, p1 = p1, stackNames = stack, pendingDecisionKind = pendingKind,
        pendingDecisionSourceName = pendingSource, attackingCards = attacks,
    )
}

@Serializable
internal data class TacticalHorizonAuditedAction(
    val signature: String,
    val label: String,
    val outcome: TacticalTerminalOutcome,
    val continuation: List<String>,
)

@Serializable
internal data class TacticalHorizonCaseAudit(
    val caseId: String,
    val horizon: TacticalHorizon,
    val category: TacticalCategory,
    val informationStateDigest: String,
    val stateContractPassed: Boolean,
    val stateContractFailures: List<String>,
    val rootExpansionExhaustive: Boolean,
    val rootActionCount: Int,
    val expectedActionMatchCount: Int,
    val expectedActionSignature: String?,
    val reviewEligible: Boolean,
    val certificationStatus: TacticalCertificationStatus,
    val authority: TacticalEvidenceAuthority,
    val acceptedSignatures: Set<String>,
    val expectedSingletonCertified: Boolean,
    val nondiscriminating: Boolean,
    val actionValues: List<TacticalHorizonAuditedAction>,
    val diagnostics: TacticalProofOracleDiagnostics? = null,
    val diagnostic: String? = null,
)

@Serializable
internal data class TacticalHorizonConformanceReport(
    val schemaVersion: Int = 1,
    val documentKind: String = "tactical-horizon-conformance-v1",
    val conformanceVersion: String = TACTICAL_HORIZON_CONFORMANCE_VERSION,
    val suiteVersion: String = TACTICAL_HORIZON_SUITE_VERSION,
    val generatedAtUtc: String,
    val outerCommit: String,
    val argentumCommit: String,
    val sourcePacketSha256: String,
    val oracleMaxStrategicDepth: Int,
    val oracleMaxExpandedNodesPerCase: Int,
    val oracleMaxWallClockMillisPerCase: Long,
    val cases: List<TacticalHorizonCaseAudit>,
    val contractPassedCases: Int,
    val reviewEligibleCases: Int,
    val certifiedCases: Int,
    val diagnosticCases: Int,
    val readyForBlindReview: Boolean,
    val allCasesCertified: Boolean,
    val failureReasons: List<String>,
)

internal class TacticalHorizonConformanceRunner(
    registry: com.wingedsheep.engine.registry.CardRegistry,
    manifest: DeckManifest,
    private val sourcePacketSha256: String,
    private val maxStrategicDepth: Int = 128,
    private val maxExpandedNodesPerCase: Int = 100_000,
    private val maxWallClockMillisPerCase: Long = 60_000,
) {
    private val factory = TacticalHorizonScenarioFactory(registry, manifest)

    fun run(cases: List<TacticalHorizonCase> = TacticalHorizonCatalog.cases): TacticalHorizonConformanceReport {
        TacticalHorizonCatalog.validate()
        TacticalHorizonContractCatalog.validateCatalog()
        val audited = cases.map(::audit)
        val failures = audited.flatMap { result ->
            buildList {
                result.stateContractFailures.forEach { add("${result.caseId}:STATE:$it") }
                if (!result.rootExpansionExhaustive) add("${result.caseId}:ROOT_EXPANSION_NOT_EXHAUSTIVE")
                if (result.expectedActionMatchCount != 1) {
                    add("${result.caseId}:EXPECTED_ACTION_MATCHES_${result.expectedActionMatchCount}")
                }
                if (result.certificationStatus == TacticalCertificationStatus.CERTIFIED &&
                    !result.expectedSingletonCertified
                ) {
                    add("${result.caseId}:TERMINAL_PROOF_REJECTS_UNIQUE_AUTHORED_ACTION")
                }
            }
        }
        return TacticalHorizonConformanceReport(
            generatedAtUtc = Instant.now().toString(),
            outerCommit = currentOuterCommit(),
            argentumCommit = currentArgentumCommit(),
            sourcePacketSha256 = sourcePacketSha256,
            oracleMaxStrategicDepth = maxStrategicDepth,
            oracleMaxExpandedNodesPerCase = maxExpandedNodesPerCase,
            oracleMaxWallClockMillisPerCase = maxWallClockMillisPerCase,
            cases = audited,
            contractPassedCases = audited.count(TacticalHorizonCaseAudit::stateContractPassed),
            reviewEligibleCases = audited.count(TacticalHorizonCaseAudit::reviewEligible),
            certifiedCases = audited.count { it.authority == TacticalEvidenceAuthority.CERTIFIED },
            diagnosticCases = audited.count { it.authority == TacticalEvidenceAuthority.DIAGNOSTIC },
            readyForBlindReview = failures.isEmpty() && audited.all(TacticalHorizonCaseAudit::reviewEligible),
            allCasesCertified = audited.all {
                it.authority == TacticalEvidenceAuthority.CERTIFIED && it.expectedSingletonCertified
            },
            failureReasons = failures,
        )
    }

    private fun audit(case: TacticalHorizonCase): TacticalHorizonCaseAudit {
        println("Tactical horizon conformance ${case.id}")
        val world = factory.create(case)
        val information = world.informationState("p0")
        val contractFailures = TacticalHorizonContractCatalog.validate(case, information)
        val expansion = world.expandChoices(2_048)
        val expected = expansion.candidates.filter { case.expectedAction.matches(it, information) }
        val reviewEligible = contractFailures.isEmpty() && expansion.isExhaustive && expected.size == 1
        if (!reviewEligible) return TacticalHorizonCaseAudit(
            case.id, case.horizon, case.category, information.informationStateDigest,
            contractFailures.isEmpty(), contractFailures, expansion.isExhaustive,
            expansion.candidates.size, expected.size, expected.singleOrNull()?.signature, false,
            TacticalCertificationStatus.NOT_REQUESTED, TacticalEvidenceAuthority.DIAGNOSTIC,
            emptySet(), false, false, emptyList(),
            diagnostic = "ROOT_CONFORMANCE_FAILED",
        )

        val proof = runCatching {
            TacticalProofOracle(maxStrategicDepth, maxExpandedNodesPerCase, maxWallClockMillisPerCase).evaluate(
                case.asTerminalProofCase(), world, 1,
            )
        }
        return proof.fold(
            onSuccess = { result ->
                val expectedSignature = expected.single().signature
                val singleton = result.acceptedSignatures == setOf(expectedSignature)
                val nondiscriminating = result.actionValues.map(TacticalProofActionValue::outcome).distinct().size == 1
                TacticalHorizonCaseAudit(
                    case.id, case.horizon, case.category, information.informationStateDigest,
                    true, emptyList(), true, expansion.candidates.size, 1, expectedSignature, true,
                    TacticalCertificationStatus.CERTIFIED,
                    // This runner searches the authored concrete engine state. It does not
                    // enumerate every hidden state compatible with p0's information state, so
                    // even an exhaustive singleton result remains diagnostic during review.
                    TacticalEvidenceAuthority.DIAGNOSTIC,
                    result.acceptedSignatures, singleton && !nondiscriminating, nondiscriminating,
                    result.actionValues.map { it.toAuditedAction() },
                    result.diagnostics,
                    diagnostic = when {
                        nondiscriminating -> "ALL_ROOT_ACTIONS_HAVE_THE_SAME_TERMINAL_OUTCOME"
                        !singleton -> "AUTHORED_ACTION_DISAGREES_WITH_TERMINAL_ARGMAX"
                        else -> "CONCRETE_STATE_ONLY_INFORMATION_SET_NOT_ENUMERATED"
                    },
                )
            },
            onFailure = { failure -> TacticalHorizonCaseAudit(
                case.id, case.horizon, case.category, information.informationStateDigest,
                true, emptyList(), true, expansion.candidates.size, 1, expected.single().signature, true,
                TacticalCertificationStatus.UNCERTIFIED, TacticalEvidenceAuthority.DIAGNOSTIC,
                emptySet(), false, false, emptyList(),
                diagnostics = (failure as? TacticalProofUncertifiedException)?.diagnostics,
                diagnostic = failure.message,
            ) },
        )
    }

    private fun TacticalHorizonCase.asTerminalProofCase() = TacticalProofCase(
        id = id,
        category = TacticalProofCategory.RESTRAINT,
        description = description,
        acceptedPredicate = "private horizon expected action",
        proof = terminalJustification,
        opportunityExpiry = horizon.name,
        rootSeed = rootSeed,
        rootPlayer = "p0",
        expiry = TacticalProofExpiry.TERMINAL,
        acceptedPattern = TacticalProofAcceptedPattern(setOf(SemanticOperationFamily.PASS_PRIORITY)),
    )

    private fun TacticalProofActionValue.toAuditedAction() = TacticalHorizonAuditedAction(
        choice.signature,
        buildString {
            append(choice.display.label)
            if (choice.display.targetNames.isNotEmpty()) {
                append(" → ")
                append(choice.display.targetNames.joinToString())
            }
        },
        outcome,
        continuation,
    )
}
