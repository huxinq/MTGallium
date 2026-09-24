# Information and decision contracts

What a policy may see, how chosen actions reach the engine, and how history is
recorded. The [architecture](../architecture.md) locates the modules; the
[glossary](../glossary.md) defines the terms.

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

Decision views from one captured revision share a lazy epistemic source. Feature
projection is computed on demand: state features are reused for a captured
state, while menu normalization and centering are recomputed for the supplied menu.
`DecisionSiteRequest.semanticReferenceGroups` lazily projects and freezes the
acting player's visible action-reference relations from that same captured
revision, for factual encoders used within search or continuation policies.

## Live selection

`RootActionSelector` handles one admitted menu in this order: rules-forced pass,
enabled singleton action, direct policy, then the searched fallback.
`RootActionSelection.Searched` carries an `InformationSetSearchResult`.
`SingletonSelectionConfig.enabled` defaults to `false`; mulligans and decision
responses are always searched.

`ActionSelector` selects choices. `ActionDistributionModel` supplies likelihoods.
Continuation policies use selectors; observed-action conditioning uses a
distribution. `agent/argentum-policy` supplies concrete defaults and builds
`InformationSetSearch`.

A simulation ends at a terminal state, the next player decision, or a typed
non-game failure. Responses, targets, ordering, and mulligans are player decisions.

## Exact actions and search groups

Search groups reduce planning work, but an accepted native declaration remains a
particular action. Search selects an admitted semantic group and a declared
native representative. Observed submission decodes the supplied native
declaration independently. Both routes rebind through the current native menu
before the engine applies the action. A declaration absent from a planning
profile can still be observed.

`SemanticChoice.signature` identifies semantic intent and excludes
observation-scoped routing identifiers. Current legality, display, payload and
routing remain distinct. Native activation metadata comes from the engine's legal
action contract.

## Cross-world action correspondence

`captureObservedActionForHost` captures the acting decision site, an observer's
information state, native declaration, search group, and trusted object bindings
from one immutable revision. The bindings are diagnostic data.

`correspondObservedActionForHost` joins observer-local handles only when both
worlds have the same observer information and each handle's
[incarnation](../glossary.md#incarnation) is continuous in the local world.
Native IDs are world-local. Passes, attacker declarations, blocker declarations
and blocker ordering can be matched across worlds; other action families return
a typed refusal.

For group `g` and member `x`, exact likelihood is
`P(g | actor information) × K(x | g, actor information)`. The current member
rule assigns conditional mass one to the retained representative and zero to any
other legal member. Observer knowledge constrains correspondence; it is not an
input to the acting policy.

Exact observed-action execution checks the child against the resolved world
revision, including history-reference state, before accepting the transition.

## History event order

`ArgentumSearchWorld.create` selects a history event-order mode and an
object-reference mode. The defaults are `LEGACY_ENGINE_ORDER_V1` and
`LEGACY_SNAPSHOT_V1`. Forks keep the selected modes and their eligibility
state, so history commitments describe the projected event sequence for that mode.

### Simultaneous untaps

`QUALIFIED_TURN_UNTAP_V1` recognizes the automatic END/CLEANUP-to-UPKEEP
transition with one turn marker, at least two distinct untaps of the next active
player's tapped objects, and the final upkeep marker. The adapter projects each
recognized untap into player-visible vocabulary, sorts those projected entries,
then assigns event numbers and updates the history commitment. Raw engine events,
raw entity IDs, hidden identities, unrelated events, and ordered choices keep
their order.

`QUALIFIED_TURN_UNTAP_V2` also covers the path that resumes after cleanup. It
reorders only the later qualified untap group; completed cleanup events stay in
order. The earlier events qualify only when none of them changes a grouped
object's zone, tapped state, or phased presence.

### Remembered battlefield references

`REMEMBERED_BATTLEFIELD_V1` uses a viewer-local knowledge-object key when the
same battlefield incarnation is visible before and after a transition. Eligibility
is fixed before the event batch. Zone changes, continuity broken by a shuffle,
missing handles, and missing incarnation information fall back to the snapshot
reference.

`QUALIFIED_OBSERVED_OBJECTS_V2` adds qualified resolution sources and puts
unordered combat assignments in canonical order, while keeping distinct
remembered objects and ordered damage choices. Handles never rebind to a later
incarnation; raw entity IDs and incarnations stay inside the trusted adapter.
