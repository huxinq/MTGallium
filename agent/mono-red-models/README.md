# Mono-Red value models

Value functions over a player's information. This module depends on
`infoset-semantics`; it can score positions without loading Argentum or search.

- `MonoRedInformationEvaluator`: life, hand, battlefield and developed-mana heuristic.
- `ConfiguredMonoRedInformationEvaluator`: the same formula with supplied coefficients.
- `MonoRedTacticalEvaluator`: combat, burn and threat features.
- `LinearValueEvaluator`: sparse linear value over `ValueFeatures`.
- `ResidualValueEvaluator`: the information heuristic plus a sparse linear residual.

`LinearWeights` contains `bias` and a map of feature names to coefficients. Both
linear evaluators accept it directly or load its JSON. See [value models](../../docs/value-models.md)
for formulas, feature encoding and examples. Search composition lives in
`argentum-policy`.
