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
