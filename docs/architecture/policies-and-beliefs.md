# Policy, belief and continuation contracts

Read [the architecture](../architecture.md) for module ownership and
[information and decisions](information-and-decisions.md) for policy inputs.

## Search and settlement

Each `InformationSetSearch.search` call starts a fresh tree and transition cache.

`agent/argentum-policy` composes host defaults through `createSearch` and
`SearchPolicySession`. A supplied scorer enters as
`LeafValueSource.Information`; callers choose the leaf configuration and source.

## Belief

The Argentum belief tracker conditions particles on observed-action likelihood,
agreement with the viewer's new observation, and represented exact knowledge,
then normalizes and resamples by copying. Particle weights, order, RNG streams,
and policy identities belong to the belief state. A factual branch must fork both
the world and the policy's continuation state.

Belief snapshots bind query results and generated worlds to one captured particle
population. Aggregate hand queries preserve correlations by evaluating a joint
requirement within each hypothesis rather than multiplying marginal estimates.
The queries contain no mutable worlds and consume no randomness.

## Continuations

`continueFirstUnvisitedEdgeToTerminal` continues an already-applied first edge
using the configured root and opponent policies for subsequent decisions.
