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

Replay-derived reference cloning combines a current safe projection with an
authenticated historical searched and accepted action. It retains both source
identities and requires the historical search menu to equal the current semantic
menu. Current generation limits exclude whole games; absent historical omission
metadata stays unknown. These derived examples do not manufacture historical
trajectory sidecars or relax the separate public-corpus admission contract.

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
```

Experimental root guidance scores only actual acting-player information and an
exhaustive admitted menu within the declared action profile. Frozen preferences order unvisited edges and add a
unit-weight, visit-decaying UCT bonus; they never become backed values or
initial visits. Policy identity and diagnostics retain the guidance identity;
zero scores preserve the existing search. The first version refuses tree reuse
and in-tree decision compression.

The frozen action kernel compiles to a bilinear scorer at load time and shares
the state projection across each supplied menu. The serialized fit and feature
meaning remain unchanged; guidance identity also records the scorer version
because changed floating-point summation order can affect near ties.

Saved-position rollout-selection diagnostics call the configured root
continuation policy with its required adapter annotations. They require the
admitted semantic menu to match the saved menu and refuse evidence-invalidating
policy replacements. These selections neither advance the game nor produce
search backups, values, or accepted-action evidence.

The experimental kernel root rollout scores the supplied acting-player view
when its admitted menu contains a cast-spell choice. It uses raw-score argmax
and delegates other menus to the production continuation, preserving its
annotation requirements and replacement diagnostics. The fit, scorer, scope,
and delegated policy are part of policy identity. Opponent continuation and
leaf evaluation remain separately configured; learned preferences are not
payoff labels.

Production tree reuse remains disabled until visits can be justified under the
current information-state search distribution. Terminal payoff,
information-state evaluation, sampled-world evaluation, and bounded-rollout
settlement have different meanings.

Terminal root-continuation evidence is separate from adaptive search estimates:
it forces an admitted root action in a support-checked hypothesis and follows
the declared rollout policies to terminal payoff, without leaf evaluation.
Records retain posterior weights, paired sampling coordinates, completed
outcomes, non-game failures and unexecuted work. Incomplete actions have no
value target, and terminal samples never create search visits or backups.

Terminal-target kernel fitting uses a distinct artifact protocol. It admits only
complete development rows, removes root-wide offsets, and freezes the fixed-ridge
model before generating held-out terminal targets. The target comparison holds
the prior model's development roots and safe features fixed and requires disjoint
held-out seed groups. It measures conditional action ordering, not deployed strength.

A terminal-target coverage comparison can reuse authenticated development labels
and append disjoint development roots with identical sampling and continuation
settings. It retains each input manifest, rejects duplicate root weighting and
validation group overlap, and freezes the expanded fit before reading reused
validation targets. Added positions within existing groups do not create new
independent game or seed-group evidence.

Position-bank admission can explicitly select the reference player from a completed
modern sequential trial. It verifies the original stopping rule, ordered prefix,
worker-chunk overshoot, and every executed checkpoint before deriving positions.
The bank retains executed games, including overshoot, while the source binding
keeps planned, inspected and unexecuted populations separate. An inconclusive
stop is admissible data provenance, not a strength result or a full schedule.

### Composed terminal research and campaign data use

The terminal-kernel study composes authenticated bank/target/fit APIs in a fixed
order, requires a verified local build and development-only preflight, freezes
the fit before validation-target access, and preserves historical identities on
explicit completed-stage reuse. A separate sensitivity command measures changed
conditional targets without changing the fitted target or selecting a winner.
Campaign records derive seed groups from verified banks and retain intended or
retrospective use; absence of a recorded overlap is not evidence of pristine
validation. The transfer audit joins exact model/role/budget identities and keeps
inconclusive gameplay distinct from transfer success or failure. Stage cost
records separate wall time, JVM CPU, overlapping component time and historical
reused work. See [the workflow contract](research-workflow.md).
