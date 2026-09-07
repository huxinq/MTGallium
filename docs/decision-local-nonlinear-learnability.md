# Decision-local nonlinear learnability

This offline capability asks whether a small nonlinear predictor can order
retained sibling actions better than the existing linear predictor. It uses
the same feature means and terminal continuation targets. It does not create
a deployed rollout policy or establish improved playing strength.

The reference remains `fitLearnabilityModel`: root-centered ridge with equal
weight per root and per sibling. Two new capabilities provide a bounded model
comparison:

- `fitDecisionLocalPhaseModel` retains every original feature and adds
  its interaction with the pre-choice game phase and first-turn status.
  Fitting the existing ridge model to this projection tests whether shared
  coefficients interfere across those contexts. Feature duplication also
  changes effective regularization, so this is not an isolated causal test
  of interference. Its separate checkpoint schema binds this projection;
  its root-aware scorer applies the projection automatically after loading.
- `fitDecisionLocalNonlinearModel` fits a single tanh hidden layer with a
  linear output. It consumes the uncentered original feature means plus a
  phase/first-turn context indicator. Only predictions and targets are centered
  within each root. Thus it can learn interactions between shared context
  and candidate differences that would disappear if inputs were centered
  before the nonlinear transformation.

The source-selected action family is excluded from both model inputs. It
describes the action that the historical control chose; it is not a general
pre-choice decision type. Phase and `turnNumber <= 1` come from the recorded
root observation. The latter includes engines whose opening choices start at
turn one. It also includes ordinary first-turn choices: these fields are
deliberately coarse and do not distinguish every opening or response decision.
Both context-dependent artifact schemas are version 2. They reject version 1
artifacts, whose turn-zero context keys had different meaning, rather than
silently scoring old weights with the new transform.

## Target and representation

Each target is the mean of 32 retained terminal payoffs under the declared
historical continuation policy. These are sampled policy-dependent outcomes,
not optimal action values. The features are a separate retained average over
the production feature schedule. A nonlinear function of this average is
not the average of a nonlinear state evaluator over its sampled worlds.

The original terminal feature offset is added unchanged. The nonlinear
residual is multiplied by the nonterminal fraction of the feature schedule,
so an entirely terminal schedule receives exactly its terminal offset.
The fitted objective centers `(offset + residual - observed target)` per
root, then averages squared error equally over siblings and roots.

Training computes its feature vocabulary and RMS scaling from TRAIN roots
only. Each scale is at least one, preventing rare coordinates from being
amplified merely because they occur rarely. Unseen evaluation coordinates
contribute zero. Whole-game groups must be distinct; validation/TEST rows
cannot enter the fitter. Default training uses 16 hidden units, 400 full-batch
Adam updates, learning rate 0.01, and weight L2 of 0.001. Biases are unpenalized.
The fixed epoch budget is not a convergence claim. The checkpoint retains
initial/final objectives, configuration, all parameters, training root IDs,
and a hash of the exact input rows. Its content identity includes all of these.

## Evaluation and limits

The score-list overload of `evaluateLearnabilityRoot` reuses the existing
action selection, lexical tie breaking, root-equal ordering, selected payoff,
regret and centered error calculations. It does not convert predictions to
outcome truth. A lower numerical error alone does not establish better action
selection. Retained-sample best-action regret remains selection-biased.

An experiment must bind its parent manifest, exact grouped folds, configurations,
model seeds, source and engine provenance, and produced artifacts through the
existing research-run APIs. Repeated folds share games and are not independent
replications. Model seeds measure optimization variability on those same games.
Historical generation identities remain attached to their inputs even when
current public source performs new arithmetic.

These features require post-action sampled-world materialization. They do not
provide the inexpensive current-candidate compiler needed by a rollout policy.
Successful offline results justify a separate integration experiment with
matched inputs and targets; deployed strength and cost still require fresh
gameplay. Failed offline results distinguish this tested model/configuration
from the reference, without proving that the representation or nonlinear
learning in general cannot work.

Focused synthetic regressions cover a context-dependent action preference
that a global linear model cannot express, phase-conditioned coefficients,
held-out exclusion, terminal offsets, forbidden metadata independence,
deterministic fitting, and checkpoint serialization parity.
