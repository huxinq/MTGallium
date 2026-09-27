# Mono-Red value models

Value functions over a player's information. This module depends on
`infoset-semantics`; it can score positions without loading Argentum or search.

- `MonoRedInformationEvaluator`: life, hand, battlefield and developed-mana heuristic.
- `ConfiguredMonoRedInformationEvaluator`: the same formula with supplied coefficients.
- `LinearValueEvaluator`: sparse linear value over `ValueFeatures`.

`LinearWeights` contains `bias` and a map of feature names to coefficients. `LinearValueEvaluator`
accepts it directly or loads its JSON. See [value models](../../docs/value-models.md)
for formulas, feature encoding and examples. Search composition lives in
`argentum-policy`.
