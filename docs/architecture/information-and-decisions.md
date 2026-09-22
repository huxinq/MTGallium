# Information and decision contracts

The [architecture guide](../architecture.md) locates these contracts; the
[glossary](../glossary.md) defines their terms.

## Information

`PlayerObservationSnapshot` is the specified player's current view.
`InformationStateRepresentation` combines that view with represented exact
knowledge, player-visible history and candidates. `InformationStateRepresentationDigest`
computes its identity, including the ordered candidate signatures and proposal version.

`DecisionSite` derives its information record from its captured player state and
exact ordered expansion. `DecisionSiteRequest` captures that site lazily from one
engine revision, so later engine changes cannot alter a request already handed to
a policy. The trusted Argentum adapter may read referee state to construct this
view. A policy receives public information, its player's private information and
remembered facts. Unknown opponent cards and engine RNG state remain in the adapter.

## Actions and live selection

Rebinding resolves a `SemanticChoice` against the current native menu before
submitting the action to the engine.

`RootActionSelector` dispatches one admitted menu in this order: rules-forced
pass, enabled singleton action, direct policy, then the searched fallback.
`RootActionSelection.Searched` carries an `InformationSetSearchResult`.
`SingletonSelectionConfig.enabled` defaults to
`false`; mulligans and decision responses remain searched.

`ActionSelector` selects choices. `ActionDistributionModel` supplies likelihoods.
Continuation policies use selectors; observed-action conditioning uses a
distribution. `agent/argentum-policy` supplies concrete defaults and builds
`InformationSetSearch`.

## Simulation and projections

A simulation ends at a terminal state, the next genuine player decision, or a
typed non-game failure. Responses, targets, ordering, and mulligans remain
decisions.

Decision views from one captured revision share a lazy epistemic source. Feature
projection is demand-driven: state features are reused for a captured state, while
menu normalization and centering are recomputed for the supplied menu.
