# Argentum information-set adapter

The trusted boundary between the Argentum engine and everything policy-facing.
This module reads full engine state; what it hands onward contains only the
acting player's information. It depends on `infoset-semantics`,
`infoset-planning` and Argentum.

- `ArgentumSearchWorld` implements `SearchWorld` over one Argentum game: it
  captures decision sites, applies rebound actions, forks, and records each
  player's history. `create` selects the history event-order and object-reference
  modes.
- `SafeObservationProjector` and `PerspectiveEventProjector` turn masked engine
  observations and events into player views with observation-scoped references
  in place of raw engine IDs.
- `UnifiedSemanticExpander` builds the admitted action menu, checking each
  candidate on a fork; `BoundedDecisionResponseProposer` and
  `BlockStructuredActionSpace` cover large decision and blocker spaces.
- `ArgentumKnownDeckBeliefWorldSource`, `ArgentumHybridBeliefWorldSource` and
  `KnownDeckWorldMaterializer` sample hidden worlds from known decks that agree
  with the viewer's represented knowledge; `ArgentumHandBeliefQueries` answers
  hand queries over them.
- `ArgentumActionCorrespondence` matches an observed action across worlds.
  `ArgentumStateFingerprint` digests full state for synchronization checks.

See [information and decisions](../../docs/architecture/information-and-decisions.md)
for the contracts these classes implement.
