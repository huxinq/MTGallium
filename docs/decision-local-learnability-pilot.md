# Retained decision-local learnability pilot

The `decision-local-learnability-pilot` suite tests whether the existing linear
model can learn within-position action differences from retained terminal
continuations. It consumes the completed original eight-sample experiment and
its fixed 24-sample precision extension. It generates no game continuations or
features and never evaluates TEST assignments.

The protocol is `decision-local-retained-32-learnability-v1`. Inputs are bound
to their historical research-run identities; the output separately records the
current analysis source and the historical feature/label source and engine.
Running arithmetic on current source does not transfer the historical evidence
to the current engine.

## Fixed fitting and evaluation

The original whole-pair split supplies 34 TRAIN and six VALIDATION roots. Every
candidate uses all 32 terminal payoffs exactly once. The existing fitter uses
root-centered features and labels, retains candidate terminal offsets, gives
equal weight to each root and equal weight to siblings within it, and uses the
existing ridge of 0.01 and PCG tolerance of 1e-7. One model is fitted without
validation tuning. Its checkpoint is written before validation scoring.

Comparison methods are the learned model, the retained cheap visible-information
heuristic, and expected uniform selection. Nonuniform methods break score ties
by lexical-smallest semantic signature. Reports contain each candidate's score
and retained mean, each root's selections, and separate TRAIN/VALIDATION summaries.

- Ordering accuracy excludes observed tied pairs and gives predicted ties half
  credit. Summary accuracy weights each root with a non-tied pair equally; the
  denominator and tied-pair counts remain explicit. An all-tied population has
  null accuracy, not zero or perfect accuracy.
- Selected payoff, regret against the best retained mean, and best-selection
  rate include all roots, including ties, with equal root weight.
- Centered mean squared error compares the model with a zero-difference
  baseline. The heuristic is compared by ranking and selection because its
  scores are not calibrated terminal payoffs.
- Per-root model-minus-heuristic payoff uses matched sample indices. Its
  standard error describes retained within-root sampling, not generalization
  across positions. TRAIN values are fitted-data diagnostics.

The best retained action and sample-best regret use the same 32 outcomes. They
are descriptive quantities, not independent regret estimates. VALIDATION was
excluded from fitting, but these development outcomes were already inspected
during the signal experiments. Six validation roots cannot establish generalization.
The suite has no deployment gate, hyperparameter search, or automatic promotion.

## Execution

Commit source and use a fresh external private output directory. In a public
source environment, set `MTGALLIUM_PRIVATE_EVIDENCE_ROOT` outside the checkout.

```sh
bash tools/mtgallium-gradle :evaluation:search-teacher:run \
  --args='--suite decision-local-learnability-pilot --precision-parent /private/original-run --precision-run /private/precision-run --output /private/learnability-run' \
  --console=plain
```

Input manifests and checkpoint payloads are verified through the research-run
source authority before fitting. Root/split/candidate identities, historical
parent payload hashes, exact replicate counts, and scheduled particle and seed
coordinates must match. Incomplete or failed input populations stop execution;
they never become labels or silent exclusions. The plan, model, JSON report,
and Markdown report are registered in a finalized output manifest.

Focused synthetic verification:

```sh
bash tools/mtgallium-gradle :evaluation:search-teacher:publicSourceTest \
  --tests '*DecisionLocalLearnabilityPilotTest'
```
