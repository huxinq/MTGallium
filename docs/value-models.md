# Value models

The [Mono-Red module](../agent/mono-red-models/README.md) scores a nonterminal
position from a specified player's perspective. Positive values favor that player.

## Sparse linear values

```kotlin
val weights = LinearWeights(bias = 0.1, weights = mapOf(featureName to 0.25))
val evaluator = LinearValueEvaluator(weights)
val value = evaluator.evaluate(information, playerId)
val restored = LinearValueEvaluator.load(weights.toJson())
```

The JSON format is `{"bias":0.1,"weights":{"feature-name":0.25}}`.
`weights` is required, `bias` defaults to zero, and unknown fields are rejected.
`{"weights":{}}` is an explicit zero model. Coefficients must be finite; missing
feature weights contribute zero.

`ValueFeatures.compile(information, playerId)` builds a sparse map from the
current view, exact knowledge, pending decision and supplied game history.
Supply a complete contiguous history. It requires two players, complete represented
knowledge and current-turn state.
Terminal inputs and mismatched player perspectives raise `ValueEvaluationException`.

Feature keys have the form `namespace/component/...`; each component uses
unpadded URL-safe Base64 of its UTF-8 text. Namespaces are `state`, `player`,
`mana`, `zone`, `card`, `stack`, `combat`, `decision`, `knowledge`, and `history`.
Player references become `root` or `opponent`. Numeric contributions to each
key are summed before applying `sign(x) * log(1 + abs(x))`; zero entries are
omitted. The emission code in `ValueFeatures.kt` defines the feature vocabulary.

`LinearValueEvaluator` accumulates `bias + sum(weight * feature)` in JVM string
key order, then clips to `[-1, 1]`. `evaluateDetailed` returns both the raw score
and clipped value.

`ResidualValueEvaluator` adds its linear score to `MonoRedInformationEvaluator`
before clipping. Residual coefficients accumulate in UTF-8 byte order. An
all-zero residual returns the heuristic directly, preserving its exact result
and avoiding feature extraction. Detailed results include the heuristic,
residual, sum and clipped value. Nonfinite arithmetic raises an exception.

## Search use

Pass the leaf route directly to `createSearch` or `SearchPolicySession` as
`valueSource`: `LeafValueSource.Information(evaluator)` evaluates the root
player's represented information, while `LeafValueSource.SampledWorld(id)` asks
the trusted sampled-world route for the named evaluation. Search returns terminal
payoffs directly and records every backed-up value's origin.
`observedEvaluationBy` attaches a callback to the detailed calculation for
diagnostics.

`LeafEvaluationConfig` selects `CURRENT_INFORMATION_STATE`,
`CURRENT_SAMPLED_WORLD`, or `BOUNDED_ROLLOUT` as its `stateSource`. Its `cutoff`
is `EVALUATE`, `QUIESCENCE`, or `POLICY_QUIESCENCE`; the latter two require a
bounded rollout. `unresolved` is explicit: `EVALUATE` evaluates an unresolved
leaf and `BACK_UP_NEUTRAL` records a neutral settlement. The workbench defaults to
`LeafEvaluationConfig(BOUNDED_ROLLOUT)`, with `EVALUATE` for both cutoff and unresolved handling.

`RolloutTurnHorizon(completedTurns, maxPolicyDecisions = 512)` is available only
with a bounded rollout and `EVALUATE` cutoff. It evaluates at the first player
decision with `turnNumber >= rootTurnNumber + completedTurns`.
`maxPolicyDecisions` is a safety limit for reaching that boundary.

The workbench uses `MonoRedInformationEvaluator` when `valueWeights` is absent;
supplying `LinearWeights` selects `LinearValueEvaluator` instead. See the
[value-search example](../examples/python-value-search.py) for a small live
comparison using hand weights and rollout horizons.

Historical checkpoint envelopes use their [producing source](history.md).
