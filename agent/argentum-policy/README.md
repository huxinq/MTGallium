# Argentum policy host

This module connects engine actions, player information, belief maintenance,
and policy sessions. `createSearch` supplies defaults to `InformationSetSearch`;
`SearchPolicySession` composes the configured leaf and value source.

Native sessions observe accepted actions and own their particle beliefs. Forking
a factual continuation copies that state alongside the world. Each search starts
with a fresh tree.

`ArgentumParticleBeliefBackend` publishes a `BeliefSnapshot` with aggregate
queries and independently materialized weighted worlds. See [belief
maintenance](../../docs/architecture/policies-and-beliefs.md#belief-maintenance).

Exact observed-action execution checks the child against the resolved world
revision, including history-reference state, before accepting the transition.
See [exact actions](../../docs/architecture/information-and-decisions.md#exact-actions-and-search-groups).
