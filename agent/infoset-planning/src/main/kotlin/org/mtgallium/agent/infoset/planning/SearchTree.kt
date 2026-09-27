package org.mtgallium.agent.infoset.planning

import org.mtgallium.agent.infoset.core.*

/** One root-player information state in the shared search tree. */
internal class SearchNode(context: DecisionContext, var expansionLimit: Int) {
    val edges = linkedMapOf<String, SearchEdge>()
    var visits: Int = 0
    var exhaustive: Boolean = context.menu.isExhaustive
    var profileExhaustive: Boolean = context.menu.isProfileExhaustive
    private val planning = PlanningDecisionContext(context)
    private var currentContext = context
    init { context.menu.candidates.forEach { edges[it.signature] = SearchEdge(it) } }

    fun requireCompatible(context: DecisionContext) = planning.requireInitial(context)
    fun merge(context: DecisionContext) {
        val refinement = planning.refine(currentContext, context)
        refinement.addedChoices.forEach { edges.putIfAbsent(it.signature, SearchEdge(it)) }
        exhaustive = refinement.menu.isExhaustive
        profileExhaustive = refinement.menu.isProfileExhaustive
        currentContext = context
    }
}

internal class SearchEdge(val choice: SemanticChoice) {
    var prior: Double = 0.0
    var visits: Int = 0
    var valueSum: Double = 0.0
    var settlementCounts: ReturnSourceCounts = ReturnSourceCounts()

    fun record(settlement: SimulationReturn) {
        visits++
        valueSum += settlement.backedValue
        settlementCounts = settlementCounts.plus(ReturnSourceCounts.one(settlement.origin))
    }

    fun meanValue(): Double = if (visits == 0) 0.0 else valueSum / visits
}
