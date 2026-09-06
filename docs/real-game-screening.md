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

## Matched terminal-continuation diagnostics

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
