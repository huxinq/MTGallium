# MTGallium architecture

MTGallium supplies perspective-safe policy information over a pinned Argentum
engine, an engine-backed Search Teacher, and evaluation code for a frozen
Mono-Red scope. It is not a claim that these contracts suffice for general
Magic or optimal play.

The [glossary](glossary.md) explains the terminology used here in plain language.

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

Replay reconstruction compares captured object references as part of the full
state. A uniquely consumed END-step delayed trigger must carry the same references
onto its new stack component. A fresh activated-ability resolution key may be
matched only at an equal accepted activation whose ordered non-mana stack events
match its repeat count. The corresponding fresh stack entries must share one
reference scope on each side, with every other component field equal. Its spelling
must remain confined to the live members of that scope, with no continuation
frames, action or event occurrences, or reuse after a member retires. The scope
ends only when every member has retired. New audits record the scope and its
ordered members under the v4 correspondence algorithm; historical v2 and v3 audits
retain their original algorithms and bytes. Unsupported correspondence remains a
reconstruction refusal.

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

### Practical strength acceptance

Sequential gameplay can prospectively select `practicalAcceptance` with an
explicit absolute score margin and objective. A `.02` margin binds the lower
and upper mean-score boundaries to `.48` and `.52`. `NON_INFERIOR` accepts a
lower-bound crossing; `EQUIVALENT` requires both bounds at the same inspected
prefix. The resulting dispositions are `NON_INFERIOR` and
`PRACTICALLY_EQUIVALENT`, distinct from superiority and from exact equality.
Budget exhaustion remains inconclusive. Directional futility is disabled for
this objective; crossing the upper band alone cannot stop an equivalence test.

Each observation is one complete independent-seed, seat-swapped pair's mean
game score, with a draw worth half a point. Inverting the frozen nonnegative
betting mixtures supplies a confidence sequence for the common conditional
mean. Its simultaneous coverage is at least one minus the two directional
error allocations; `.025` each gives `.95`. The maximum compatible distance
from parity is `max(.5 - lower, upper - .5)`, an uncertainty bound rather than
an estimated effect. Bootstrap intervals remain descriptive. Invalid pairs
stop inference without becoming scores, and completed worker overshoot cannot
change the first stopping prefix.

This is a new prospective protocol. Omission preserves the historical rule and
result serialization; adding it to an already inspected tournament does not
retroactively establish equivalence. Freeze the candidate, control, budget,
seeds, margin, bets and error allocations before new evaluation. In particular,
evidence for an earlier rollout policy cannot validate a new fast continuation.
An engineering acceptance decision additionally needs a separately declared,
measured runtime improvement over the relevant workload. Non-inferiority alone
does not pass the existing stronger-learned-play milestone or its superiority
gate. Per-process error control does not cover selecting among many candidates.

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

An explicitly configured fast kernel continuation instead uses the plain semantic
proposal menu without Production admission or its extra combat anchors. It applies
the frozen casting-context scorer when that menu contains a cast-spell action and
a declared cheap semantic heuristic otherwise. Root and opponent rollout settings
are independent and retain the fit and menu population in behavior identity.
This changes the continuation policy and requires new evaluation; it does not
relabel historical Production results. Tree selection, the belief opponent model,
terminal payoff and fixed search budgets retain their separate configuration.
An optional menu-only selection capability may defer full information construction in
bounded rollouts, terminal continuations and frontier refresh only when it returns the
same sampled action and component diagnostics for the same admitted menu and seeds.
Fast continuation uses this for non-casting menus; casting contexts still require the
full acting-player state. Transition caches retain information only when materialized.
This exact construction shortcut leaves policy identity and admission unchanged.
Existing policies still request Production admission by default. Annotation
consumers must also request admission; mixtures retain the requirements of their
positive-weight components.

The optional attack kernel changes only root rollout selection on profile-complete,
pure attack/decline menus with two through eight choices. Rollout selection carries
the adapter's completeness boolean alongside the supplied menu; menu size alone
does not certify completeness. Incomplete, larger and other menus use the frozen
fast casting/semantic continuation. Opponent continuation is separately configured.
The attack role refuses selection without that witness. Its fixed-kernel study
uses fresh terminal targets under frozen fast continuations, one development fit,
and held-out comparison against the incumbent's stochastic action distribution.
Passing that comparison requires subsequent fresh gameplay; it is not strength evidence.

An optional direct attack kernel selects the actual player's action on those same
complete pure attack/decline menus of two through eight choices. It receives only
the actual acting-player information and admitted expansion; every other menu
continues through the incumbent Search Teacher session. The returned action still
passes normal live rebinding and acceptance. A direct choice has a distinct
selection kind and no search result, visits or values. Its fit, scorer and scope
are bound into behavior identity, separately from rollout policy identity;
configuring this option never changes either rollout policy.

When either arena seat deploys a direct kernel, both seats retain a common wall-time
measurement from before actual information/expansion construction through selection,
including direct feature/scoring and ordinary search work. It includes interleaved
host preparation, excludes accepted transitions, belief advancement and session
construction, and is reported as accumulated decision computation per game.
Legacy search-only latency fields remain separate. Absent configuration preserves
historical policy identities and serialization; historical unmeasured cost is null.

Production tree reuse remains disabled until visits can be justified under the
current information-state search distribution. Terminal payoff,
information-state evaluation, sampled-world evaluation, and bounded-rollout
settlement have different meanings.

Within one search, the exact per-particle semantic-prefix transition cache also
serves bounded rollouts. It reuses world snapshots and their derived projections,
while recomputing rollout distributions, sampling seeds and policy diagnostics.
Rollout traversal itself does not record trace points or frontiers. A cached
snapshot becomes eligible for retained evidence only after tree traversal
reaches the same exact prefix.
At most 4,096 new rollout snapshots are retained per search; after that, a new
prefix proceeds uncached. Existing tree cache behavior and genuine decision
boundaries remain unchanged.

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

The CLI research workbench keeps editable design intent, frozen operational
attempts and native scientific evidence separate. Its native adapter decodes
existing plan types and performs bound preflight verification and primary
dispatch in one JVM, or invokes explicit workflows with their existing embedded
pilots/input admission. Python retains exact request/source/build/input bindings,
bounded durable execution and derived inspection records. Generic artifact
verification does not establish a scientific pass; packets must match a recorded
execution's evidence identity and manifest hash. Field retrieval preserves
recorded values and failures, while semantic feature generation continues through
the existing FEATURES screen. See [the CLI guide](research-workbench.md).

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
