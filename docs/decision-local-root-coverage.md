# Broader decision-root coverage

The `decision-local-root-coverage` suite tests whether more independent training
positions improve the fixed decision-local linear model. It retains the
historical Argentum revision `3eda577fdd10d08e0e62d66b4727ab53f1b41ff5`, frozen
learned opponent, deck, root selector, features, continuation policies, and
32 terminal samples per candidate. It is a historical compatibility experiment;
the public checkout's current engine pin is not changed for its execution.

## Frozen population and sequence

Global lineage indices 50 through 299 extend the original 0 through 49 seed
schedule. Each lineage generates one control-versus-learned game, with control
at p0 for even indices and p1 for odd indices. The unused opposite leg is not
generated. The existing result-blind selector chooses one control decision
per game using the global index, preserving its family, phase, candidate-band,
and tie-breaking schedule.

The protocol `decision-local-root-coverage-200-train-50-evaluation-v1` hashes
each global index with its fixed whole-pair split key. The first 200 ranked
indices are TRAIN; the remaining 50 form a fresh evaluation panel (represented
by the existing VALIDATION enum). Assignments are frozen before games. All
250 selected roots are bound and persisted before any new continuation labels.

The runner labels the 200 TRAIN roots, adds the original 34 TRAIN roots, and
fits the unchanged root-centered ridge model with equal root/sibling weights.
It writes that model before generating the fresh panel's labels. The original
34-root model and the expanded 234-root model are evaluated on the same panel,
alongside the cheap heuristic and uniform selection. The original six
validation and ten TEST roots are excluded from this experiment's evaluation.
There is no hyperparameter tuning or automatic promotion.

Metrics retain the [learnability pilot's definitions](decision-local-learnability-pilot.md).
Observed tied pairs supply no ordering evidence; all roots contribute to
payoff and regret. Regret against the best observed candidate is descriptive
because selection and evaluation use the same samples. This measures action
ranking under the fixed continuation policy, not deployed gameplay strength.

## Execution and evidence

Author and commit source in the public repository, then port it to a committed
historical-engine execution checkout. Run the `decision-local-root-coverage-preflight`
suite first with the same arguments as the full suite and a separate fresh
output. It verifies retained input identities, reproduces two historical root
bindings including an odd-index single-leg root and two old terminal outcomes,
and performs one learned-opponent search. Its deliberately stopped game is a
compatibility witness and never a terminal outcome or fresh assigned root.

```sh
bash tools/mtgallium-gradle :evaluation:search-teacher:run \
  --args='--suite decision-local-root-coverage --coverage-parent /private/decision-local-sibling-outcome --fixed-root-pilot /private/historical-pilot --outcome-corpus /private/historical-corpus --fixed-root-gate /private/historical-gate --deck-manifest /private/deck.json --threads 4 --output /private/root-coverage-run' \
  --console=plain
```

Use explicit external private evidence settings and durable execution for the
full run. The plan binds committed source, historical engine, retained input
manifests, opponent checkpoint, policies, allocation, and material settings.
Canonical replays, per-game/root/label checkpoints, the model, and reports
remain private and are registered through the research-run artifact authority.

A failed game, unsupported root, or nonterminal continuation stops the run;
it is never replaced or assigned a strategic payoff. In-flight workers finish
before failure evidence is finalized, and queued work stops. Interrupted,
unfinalized runs can resume only with the exact source and plan; finalized
success or failure directories are immutable. Progress reports distinguish
root games, training continuations, and evaluation continuations.

Focused synthetic verification is `publicSourceTest --tests '*DecisionLocalRootCoverageTest'`
in the Search Teacher evaluation module, with the existing reconstruction,
precision, and learnability tests covering reused paths.
