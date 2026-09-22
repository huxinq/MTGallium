# Argentum policy host

This module connects engine actions, player information, belief maintenance,
and policy sessions. `createSearch` supplies defaults to `InformationSetSearch`;
`SearchPolicySession` composes the configured leaf and value source.

Native sessions observe accepted actions and own their particle beliefs. Forking
a factual continuation copies that state alongside the world. Each search starts
with a fresh tree.

`ArgentumParticleBeliefBackend` publishes a `BeliefSnapshot` with aggregate
queries and independently materialized weighted worlds. See [conditioned
maintenance](../../docs/belief-maintenance.md).

Exact observed-action execution checks the child against the resolved world
revision, including history-reference state, before accepting the transition.
See [action correspondence](../../docs/action-correspondence.md).
