# Policy and belief contracts

How search is composed, how beliefs over hidden information are maintained, and
how continuations run. Read [the architecture](../architecture.md) for module
ownership and [information and decisions](information-and-decisions.md) for
policy inputs.

## Search composition

Each `InformationSetSearch.search` call starts a fresh tree and transition cache.

`agent/argentum-policy` composes host defaults through `createSearch` and
`SearchPolicySession`. A supplied scorer enters as
`LeafValueSource.Information`; callers choose the leaf configuration and value
source. See [value models](../value-models.md#search-use).

Native policy sessions observe accepted actions and own their particle beliefs.

## Belief maintenance

`ArgentumParticleBeliefBackend` in `agent/argentum-policy` maintains a player's
particle population from an `ArgentumSearchWorld`, known decks, configured belief
mode, opponent action distribution, and private-choice selector. Particle
weights, order, RNG streams and policy identities belong to the belief state.

For `POLICY_CONDITIONED_V1`, each hypothetical world advances through the
observed action. The tracker weights each world by the action's likelihood, keeps
descendants that agree with the viewer's newly observed
`InformationStateRepresentation` and represented exact knowledge, normalizes the
surviving weights, then resamples by copying. A private opponent response is
selected from that opponent's information; only its observable consequences
condition the viewer's population.

`POLICY_CONDITIONED_V1` requires `SEQUENTIAL_B_V1`. A depleted population,
support mismatch, or reconstruction requirement raises a typed maintenance
failure. A [factual continuation](../glossary.md#factual-root-branch) forks
both its `ArgentumSearchWorld` and the tracker, and requires the same current
information-state digest; copying only the world does not copy the belief.

## Belief snapshots

The tracker publishes a `BeliefSnapshot` with query views and independently
materialized weighted worlds. A snapshot binds its query results and generated
worlds to one captured particle population. Aggregate hand queries evaluate a
joint requirement within each hypothesis rather than multiplying marginal
estimates, so correlations between cards are kept. Queries contain no mutable
worlds and consume no randomness.

## Continuations

`continueFirstUnvisitedEdgeToTerminal` continues an already-applied first edge
using the configured root and opponent policies for subsequent decisions.
