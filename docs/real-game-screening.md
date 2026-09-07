# Real-game Search Teacher screening

These opt-in suites shorten development by reusing decisions from authenticated
games. They do not change production policy defaults or grade a recorded move as
correct merely because a policy played it. All outputs are private research
evidence, tied to committed source, the engine pin and explicit plans.

Each suite takes `--profile PLAN.json --output PRIVATE_DIRECTORY
--deck-manifest DECK.json`; search and gameplay also accept `--threads N`.

## Bank and screening

`--suite real-game-position-bank` accepts a `RealGamePositionBankPlan`: source
directories with expected research-run identities, total root limit, per-game
cap, selection seed and validation fraction `0.25`. The first version admits
completed calibration runs with the exact current engine revision. Both valid
and invalid source populations are inventoried; roots from invalid pairs are
excluded with reasons. Sequential runs are not yet admitted.

Selection balances decision families without consulting outcomes, values or
policy agreement. Keep/mulligan and bottom-card choices are separate. All
comparisons and seats sharing deck, card pool and library seed share a fixed
partition, independent of bank version and selection seed. New bank versions
can add stronger-policy games without moving old seed groups between partitions.

Selected roots are reconstructed from authenticated canonical replay. The bank
retains represented information, numeric visible-v2 features, source-admitted
candidates, current expansion and observed source choices as distinct fields.
It records every selection, exclusion and reconstruction refusal. An incomplete
bank cannot be screened.

`--suite position-bank-screen` accepts a `PositionBankScreenPlan` with bank
directory and identity, explicit partition, root limit, policies and repetitions.
`FEATURES` rescales cached visible-v2 features cheaply; it supplies no training
labels or counterfactual outcome. `SEARCH` reconstructs each root's history and
sequential belief, then compares actual search choices on matched root/repetition
seeds. It records values, settlements, diagnostics and reconstruction/selection
cost separately. The actual replayed hidden world never becomes a belief particle.

`ACTION_CONDITIONAL` reconstructs the same legitimate information and belief,
then spends the configured simulation budget on each initially admitted root
action separately. Only the first edge is forced; normal tree selection,
opponent decisions, rollout policies and horizon/settlement rules continue
afterward. The complete represented candidate family remains intact. The
diagnostic rejects trace reuse, wall-clock cutoffs and singleton-pass compression.
Actions outside the initially admitted menu are refused; this is not a claim
of exhaustive rules-legal coverage.

Each action retains its adaptive mean backed value, visits, typed settlement
counts and diagnostics in `rootActionEstimates`. A forced-action estimate is
neither a policy recommendation nor a terminal outcome. The root/repetition
seed and particle batch are shared across actions; divergent continuations
need not consume corresponding chance events. Internal tree backups are not
independent uncertainty samples. Use independent repetitions and preserve
whole-game grouping when comparing values; more simulations do not remove
evaluator or opponent-policy bias. A reference-valued comparison between
baseline and modified search choices is a surrogate pending gameplay validation.
Set a separate `searchSeedDomain` for reference scoring to avoid reusing the
candidate-selection search randomness. Omission preserves historical seed
derivation and plan bytes. Repetitions vary search sampling over the reconstructed
particle batch; they do not independently rebuild that posterior approximation.

Both search modes prepare a root once per policy and reuse its world/session
across that policy's repetitions on one worker. Each search uses a fresh tree;
no accepted action advances the prepared root. Different roots and policies
remain separate. Preparation is held only for that worker group and is not a
durable world snapshot. `reconstructionMillis` charges the work to repetition
zero; later repetitions report zero and `reusedRootPreparation=true`. Selection
time still includes each repetition's belief support check and search. A failed
preparation refuses every requested repetition; it does not drop rows.

Both search modes support the bound [research preflight](research-preflight.md)
with `work.type = "position-screen"`. Reuse the finalized bank and reference
estimates across candidates only while their source, configuration, action
coverage and population meaning remain applicable.

`MonoRedVisibleEvaluatorConfig` parameterizes the existing visible-v2 formula.
Its default coefficients preserve that evaluator's numerical behavior; a
configuration identity distinguishes overrides. Formula changes, learned labels,
automated parameter optimization and tactical certification are separate work.
Search disagreement can nominate positions for investigation but is not an
accuracy score. Accessing validation roots must be disclosed in later claims.
Repeated searches at one unchanged root/policy reconstruct the session once, with
tree reuse and wall-clock budgets disabled. Later repetitions record zero added
reconstruction time explicitly; they still run fresh search at their assigned seeds.

## Sequential gameplay

Use this suite for head-to-head strength experiments that should stop when
evidence is decisive. Start from the public
[example plan](../examples/search-teacher-sequential.json): it compares an
8-particle, 32-simulation candidate with an 8-particle, 64-simulation reference,
holding the other declared settings fixed. This is an example intervention,
not a recommended stronger policy or an existing experiment result.

From the repository root, set real absolute paths outside the checkout:

```bash
export MTGALLIUM_PUBLIC_SOURCE=1
export MTGALLIUM_PRIVATE_EVIDENCE_ROOT="/absolute/path/to/private-evidence"
DECK_MANIFEST="/absolute/path/to/deck-manifest.json"
mkdir -p "$MTGALLIUM_PRIVATE_EVIDENCE_ROOT"
cp examples/search-teacher-sequential.json "$MTGALLIUM_PRIVATE_EVIDENCE_ROOT/sequential-plan.json"
```

Edit that private plan to select the candidate, a fresh seed schedule and the
stopping rule before looking at outcomes. Keep `calibration.pairCount` equal to
`rule.maximumPairs`, and supply exactly one candidate. Commit treatment source
before substantial compute, then run:

```bash
bash tools/mtgallium-gradle :evaluation:search-teacher:run \
  --args="--suite search-teacher-sequential --profile \"$MTGALLIUM_PRIVATE_EVIDENCE_ROOT/sequential-plan.json\" --output \"$MTGALLIUM_PRIVATE_EVIDENCE_ROOT/search-teacher/work/sequential-trial\" --deck-manifest \"$DECK_MANIFEST\" --threads 2"
```

Use a new output directory beneath `search-teacher/work` in the private evidence
root for a new trial; the runner enforces this location. The deck manifest is an owner-supplied
input; the example contains no private deck, evidence or execution service.
Owner environments can run the same suite through their durable execution layer.

The example caps work at 24 pairs / 48 games. Equal `0.5` boundaries test both
directions around parity, with a 2.5% error allocation in each direction under
the model below. Distinct null/target boundaries are also supported. Freeze the
bet mixture prospectively; do not tune it after seeing losses or wins. Early
stopping is possible, not promised. Two workers can leave one extra completed
pair beyond the first stopping prefix.

For a practical acceptance objective, start from the
[non-inferiority example](../examples/search-teacher-non-inferiority.json).
It explicitly selects a two-percentage-point margin, `.48`/`.52` boundaries,
and `practicalAcceptance.objective = "NON_INFERIOR"`. Acceptance establishes
mean game score above `.48`; it does not establish superiority. Select
`"EQUIVALENT"` to require both bounds inside `.48`–`.52`. The two `.025`
directional error allocations yield a confidence sequence with at least 95%
simultaneous coverage across repeated progress checks. Its maximum compatible
distance from parity is an uncertainty bound, not a measured strength loss.
The 24-pair example is a syntax/workflow example, not a powered design for
this narrow margin. Choose the actual cap before executing the trial.

Practical acceptance disables directional futility and retains the ordinary
pair validity and overshoot rules. A faster implementation additionally needs
a separately declared runtime comparison over the relevant workload; both
policies share the elapsed duration of a head-to-head game, so that duration
cannot itself attribute a speedup to either policy. Freeze this new protocol
before observing its games. Historical superiority tests and games from an
earlier candidate retain their original meaning.

The example enables `stopForFutility`. After each complete valid pair, the rule
checks whether even winning every remaining pair could cross the upper boundary,
or losing every remaining pair could cross the lower boundary. If neither is
reachable within the planned cap, it reports `FUTILITY`: an inconclusive result,
not evidence of parity or equivalence. The scheduler finishes its already-running
worker chunk, retains any pairs beyond that first stopping prefix as overshoot,
and launches no further chunk. Borderline numerical bounds continue conservatively.
The setting is prospectively bound into the run identity. Omitted or false keeps
the historical behavior and serialized rule bytes; do not retrofit it onto a
running or completed experiment's original report.

After completion, verify the research-run manifest and checkpoints before using
`report.json` or `report.md`. Read the compact `report.md` first: it includes
policy configuration differences, W/L/draw counts, stopping disposition,
confidence sequence, population accounting and scoped timing. Reserve the full
JSON report for questions requiring individual games or decisions.
`sequentialResult` gives the stopping disposition;
`sequentialPopulation` separates planned, executed, inspected, unexecuted and
overshoot pairs. `comparisons[0].pairs` owns the inference prefix, while
`sequentialOvershootPairs` retains extra work. `BUDGET_EXHAUSTED` and `FUTILITY` are inconclusive;
`INVALID_PAIR` stops inference without turning a software failure into a loss.

The [plan and schedule](../evaluation/search-teacher/src/main/kotlin/org/mtgallium/evaluation/searchteacher/SearchTeacherSequentialPlan.kt),
[stopping rule](../evaluation/search-teacher/src/main/kotlin/org/mtgallium/evaluation/searchteacher/PairedSequentialTest.kt)
and [focused tests](../evaluation/search-teacher/src/test/kotlin/org/mtgallium/evaluation/searchteacher/PairedSequentialTestTest.kt)
are the source authorities. The public test lane also parses this example and
checks that decisive synthetic sequences stop before its cap.

`--suite search-teacher-sequential` accepts `SearchTeacherSequentialPlan`, which
wraps a one-candidate calibration plan and `PairedSequentialRule`. Freeze both
before inspecting outcomes. Each observation is a complete seat-swapped pair's
point rate, including draws. Parallel chunks are inspected in prescribed pair
order; the first stopping prefix determines the result and later completed work
is retained as operational overshoot. Invalid pairs never receive a score.
The report separates planned, executed and inspected populations. A failed
post-stop pair remains an operational failure without changing the earlier
first-prefix inference.

The fixed mixture of nonnegative betting processes tests a bounded pair mean
under the declared independent-seed, common conditional-mean model. It does not
make a confidence claim about a finite committed seed list. Upper and lower
boundaries respectively reject a mean at or below the null rate and at or above
the target rate. Exhausting the pair cap is inconclusive; stopping does not
automatically promote a policy or establish a cost improvement. Ordinary paired
bootstrap intervals remain descriptive after optional stopping. Error budgets
are per test, so adaptive development still needs separate confirmation or an
explicit allocation across tests.

The original fixed-size calibration suite remains available. Existing source
plans omit the new optional evaluator and stopping-rule fields, preserving
their research identities.

### Fixed pairs against the original Argentum heuristic

The `search-teacher-calibration` suite executes the complete configured pair
count without a sequential stopping rule. A policy descriptor may set
`directArgentumHeuristic: true` to dispatch the existing direct Argentum
heuristic adapter. Its required numeric search fields are inactive; evaluator
and rollout interventions must be absent. The report records no search or
rollout configuration for this policy and zero configured simulations. Omission
preserves existing search policy behavior and serialized plan identity.

At least one policy in each matchup must use search. Safe trajectory and planner
evidence follow the search seat (`p0` when both seats use search), with the seat
in each artifact filename. Original-heuristic adapter replacement remains a
reported evidence failure rather than an accepted original-policy result.

## Matched terminal-continuation diagnostics

### Selecting either tactical V3 formula

The two retained V3 formulas coexist. An object-valued `tacticalEvaluator`
selects schema 1 (`MonoRedTacticalEvaluatorSettings`), including its ten weights
and `landConversion` term. Existing coefficient calibration remains bound to
that formula. The strings `"V3_DEFAULT"` and
`"V3_WITHOUT_ATTACK_AND_INITIATIVE"` select the screening branch's schema-2
formula and its nine weights. Their original configuration IDs and JSON forms
are preserved; an unspecified registry V3 evaluator still uses schema 1.

Schema-2 screen policies omit the outer cached visible-V2 `evaluator` field.
Existing schema-1 screen configurations retain their explicit default V2 object.
The formulas share an evaluator family ID but have distinct schema and annotation
versions in their configuration identities. Neither is promoted by integration.

Attack-influence recording cannot be combined with policy quiescence: its
observer covers bounded-rollout decisions and would omit later policy choices.

`--suite position-bank-terminal-continuations` accepts explicit development root
IDs, a bank identity, one reconstruction/rollout policy composition, sample count,
candidate cap, continuation decision cap and seed. The bank's authenticated replay
and sequential-belief reconstruction are shared with search screening.

Each replicate samples a belief particle by its weight, then applies every current
profile candidate to a separate fork with matched future-chance and continuation
seeds. The candidate cap refuses the entire root rather than dropping actions.
The actual replayed hidden world is never used as a belief particle. Both sides
then use the declared rollout policies through genuine player decisions until an
actual terminal state. This continuation invokes no leaf evaluator and does not
run the full Search Teacher at later decisions.

The report retains particle weights, coordinates, policy identities, terminal
payoffs and available policy-decision diagnostics. Reconstruction, candidate
rebinding, rejection and continuation failures stay explicit. A decision cap
supplies no payoff. Candidate means and paired gaps are emitted only for roots
whose entire assigned candidate/sample population reached valid terminals.
Root-player features immediately after the assigned first action are cached as
hypothetical observations, with their information digest and immediate-terminal
flag. They are action-specific inputs for later diagnostics, not authoritative
facts at the bank root or samples of the production leaf-settlement distribution.

These are descriptive finite-sample, rollout-policy-conditioned payoff gaps for
hypothetical continuations. They are not observed game results, exact information-
state values, correct-action labels, tactical proofs or evidence of stronger play.
Source-position policies, belief construction and continuation policies have
separate meanings. The descriptor retains its full composition, but fields used
only by ordinary search or leaf evaluation do not control this terminal API.
Adaptive tuning and any eventual strength claim still need an appropriate target,
validation discipline and gameplay evidence.

### Reviewing terminal reports

After official research-run and bank verification, the standard-library utility
[`position_bank_terminal_diagnostics.py`](../tools/analysis/position_bank_terminal_diagnostics.py)
produces a private review derivative from the finalized terminal and bank reports:

```sh
python3 tools/analysis/position_bank_terminal_diagnostics.py \
  --terminal-report "$TERMINAL_REPORT" --bank-report "$BANK_REPORT" \
  --verified-terminal-identity "$TERMINAL_IDENTITY" \
  --verified-bank-identity "$BANK_IDENTITY" \
  --official-verification-completed \
  --json-output "$MTGALLIUM_PRIVATE_EVIDENCE_ROOT/search-teacher/work/review/diagnostics.json" \
  --markdown-output "$MTGALLIUM_PRIVATE_EVIDENCE_ROOT/search-teacher/work/review/diagnostics.md"
```

The verification flag attests to checks already performed by the caller; this
utility does not perform official verification. It retains input and manifest
hashes, input source provenance, identities and its own script hash. The analysis
requires clean committed source and records that revision separately in a review
identity bound to its inputs. Outputs must be new
absolute paths outside source checkouts and finalized input directories.

The analysis accounts for every assigned root, preserves invalid populations,
and computes gaps only from complete paired candidate matrices. It counts exact
cached-feature collisions at matching coordinates. If every retained terminal
payoff is binary, it also reports two-sided paired sign tests with Holm adjustment
across all available candidate pairs in the full report. These tests assume the
paired replicates follow the declared sampling model; they do not correct for
earlier exploration or certify actions. Nonbinary outcomes retain descriptive
gaps without sign tests. Neither a small p-value nor a feature collision is a
playing-strength result.

## Decision-level rollout treatments

Calibration policy descriptors can opt into two independent treatments:

- `searchHeuristicProfile: "PRODUCTION_EXPIRING"` uses the pinned engine's
  expiring-grant timing guard for heuristic annotations inside simulated search.
  This affects the annotated tree opponent and both rollout seats. Represented
  belief updates retain their original opponent model. The default is
  `PRODUCTION`; the optional field participates in behavior identity.
- `rolloutHorizonSettlementOverride: "POLICY_QUIESCENCE_WITH_EVALUATION_FALLBACK"`
  continues an unsettled tactical-v3 rollout through explicit root/opponent
  rollout-policy choices. Responses, blockers, targets and ordering remain
  individual choices, with policy attribution and quiescence counters. The
  continuation shares one forced-pass budget and uses the configured quiescence
  decision cap. A quiet strategic decision is left for evaluation; exhaustion
  remains a heuristic fallback, never a terminal outcome.

The existing `QUIESCENCE_WITH_EVALUATION_FALLBACK` remains pass-only. Neither
option changes the default policy or justifies promoting a treatment based on
selected decision probes. Record the exact source, configuration and heuristic
versus terminal settlement populations when comparing these treatments.

### Completed-turn endpoints

`rolloutTurnHorizon: {"completedTurns": 3, "maxPolicyDecisions": 512}` replaces
rollout's fixed decision endpoint with the first genuine player decision after
three player turns finish. Count every player's turn, beginning with the current
root turn; the absolute endpoint is fixed at the original search root and is
shared across its siblings and tree depths. Turn completion includes cleanup and
expiration of until-end-of-turn effects. The engine may already have untapped
permanents or put new-turn triggers on the stack when it exposes that next
choice; the option does not claim all such states are tactically quiet.

The endpoint is evaluated directly with V2 or V3. It cannot be combined with a
quiescence override that moves beyond that boundary. `maxPolicyDecisions` inside
the horizon object is a rollout safety cap: failing to reach the boundary stops
search with a typed non-game failure, rather than scoring an earlier leaf.
The ordinary top-level decision budget still bounds tree depth, and simulation
counts remain independent of rollout length. Defaults and retained identities
without the optional horizon field are unchanged.

For a separate formula ablation, `tacticalEvaluator:
"V3_WITHOUT_ATTACK_AND_INITIATIVE"` zeros only the attack-capacity and
priority/attack-window initiative weights. All life, body, block, reach, hand and
mana terms retain the default formula; the effective weights identify the
ablation. It is not an automatically promoted successor to `V3_DEFAULT`.
