# Value models

The [value-model module](../agent/value-models/README.md) scores a nonterminal
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
key order, then clips to `[-1, 1]` by default. Pass `InverseLink.TANH` to use
`tanh(score)` for logistic-outcome deployments. `evaluateDetailed` returns both
the linear predictor (`linearPredictor`) and deployed value (`value`).

## Search use

`InformationStateByteEncoder.view(information)` exposes the same factual view bytes as
the decision encoder without requiring an acting-player menu. This supports
evaluators at nonterminal leaves where another player acts; the supplied
information must still belong to the evaluator's requested perspective.
Python's `game.value_snapshot(factual_schema=...)` can include these view tokens
alongside the existing features and V2 scores for both players, including the
nonacting player. Omitting the schema preserves the original snapshot fields.

Pass the leaf route directly to `createSearch` or `SearchPolicySession` as
`valueSource`: `LeafValueSource.Information(evaluator)` evaluates the root
player's represented information. Search returns terminal
payoffs directly and records every backed-up value's origin.
`observedEvaluationBy` attaches a callback to the detailed calculation for
diagnostics.

`LeafEvaluationConfig` selects `CURRENT_INFORMATION_STATE` or `BOUNDED_ROLLOUT`
as its `stateSource`. Its `cutoff` is `EVALUATE`, `QUIESCENCE`, or
`POLICY_QUIESCENCE`; the latter two require a bounded rollout. An unresolved
leaf is evaluated. The workbench defaults to `LeafEvaluationConfig(BOUNDED_ROLLOUT)`
with the `EVALUATE` cutoff. Recorded diagnostics retain historical leaf settings
as data so older records can still be decoded.

Quiescence passes a lone priority pass without making it a search decision.
`quiescencePasses` selects which passes qualify. `RULES_FORCED_V1`, the default,
passes only when the pass is the complete legal menu. An action-space profile that
omits standalone mana abilities, such as the Mono-Red profile, makes a lone pass
incomplete whenever the player has an untapped mana source; under this rule such
a pass becomes a quiescence decision or a fallback. `PROFILE_FORCED_WHILE_VOLATILE_V1`
also passes a lone pass that is the complete profile menu while the root player's
position is volatile, and still evaluates quiet positions where they stand.
`quiescenceProfileForcedPasses` counts those passes within `quiescenceForcedPasses`.
The rule applies only to leaves that settle through quiescence.

`RolloutTurnHorizon(completedTurns, maxPolicyDecisions = 512)` is available only
with a bounded rollout and `EVALUATE` cutoff. It evaluates at the first player
decision with `turnNumber >= rootTurnNumber + completedTurns`.
`maxPolicyDecisions` is a safety limit for reaching that boundary.

Search diagnostics count `nonQuietLeafEvaluations` (serialized as
`unsettledLeafEvaluations`): evaluator calls where `isQuiet` is false, meaning a nonempty stack, active combat,
a pending combat, damage or ordering decision, or lethal creature damage.
Terminal payoffs and neutral settlements are not counted. "Unsettled" here means
the position, not the backed-up value.

A [games plan](research-cli.md#games-plan) uses `MaterialEvaluator()`
when `valueWeights` is absent; supplying `LinearWeights` selects
`LinearValueEvaluator` instead. See the
[value-search example](../examples/python-value-search.py) for a small live
comparison using hand weights and rollout horizons.

Historical checkpoint envelopes use their [producing source](history.md).
