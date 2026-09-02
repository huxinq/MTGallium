# MTGallium architecture

MTGallium supplies perspective-safe policy information over a pinned Argentum
engine, an engine-backed Search Teacher, and evaluation code for a frozen
Mono-Red scope. It is not a claim that these contracts suffice for general
Magic or optimal play.

## Information and action boundaries

The trusted Argentum adapter may inspect full engine state. It projects an
acting-player view that excludes opponent hand identities, unknown library
order, raw engine identifiers, referee random state, and sampled hidden truth.
`PolicyKnowledgeState` preserves represented exact knowledge and history;
uncertain inference remains uncertain and does not become a fact.

`SemanticChoice` separates semantic identity, display text, payload, routing
identity, and current legality. Legal engine actions, generated proposals,
search-admitted choices, and accepted rebound transitions are deliberately
different sets. A semantic signature represents sameness of player intent;
safe compression needs a demonstrated irrelevant distinction; strategic
similarity belongs to search or learning rather than identity.

Simulation stops at a terminal state, the next genuine player decision, or a
typed non-game failure. Responses, targets, ordering, mulligans, and other
choices are never silently consumed. Rejection, timeout, unsupported
representation, and stopped execution are not wins, losses, draws, or values.

## Evidence and dependencies

Canonical replay contains referee state and stays private. Perspective-safe
inspection and public artifacts are derived evidence, not a second canonical
record. A safe-looking artifact is not safe solely because a path guard accepts
it.

### Source and evidence authority

This public repository is the implementation authority for new first-party
MTGallium work. Private research evidence stays external to the checkout. Each
durable artifact remains bound to the exact MTGallium revision, Argentum
revision, material configuration, and evidence identity that generated it;
later source commits do not rewrite that historical identity. Public code may
generate private evidence without making the generated evidence public.

Public CI verifies explicitly self-contained public capabilities. Verification
whose meaning requires private historical evidence remains separate; generic
invariants should use synthetic or public-safe fixtures rather than treating a
private fixture as a substitute for history.

```text
agent/research-run
        ↑
agent/infoset-core
        ↑
agent/infoset-argentum
        ↑
agent/search-teacher
        ↑
evaluation/search-teacher

integration/argentum-search-teacher ──→ adapter + Search Teacher
tools ──→ serialized domain models and local services
```

Production tree reuse remains disabled until visits can be justified under the
current information-state search distribution. Terminal payoff,
information-state evaluation, sampled-world evaluation, and bounded-rollout
settlement have different meanings.
