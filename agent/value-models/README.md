# Value models

Value functions over a player's information. This module depends on
`infoset-semantics`; it can score positions without loading Argentum or search.

- `MaterialEvaluator(weights = MaterialWeights.DEFAULT)`: life, hand, battlefield and developed-mana value.
  Equal copied or decoded weights retain the explicitly configured identity.
- `LinearValueEvaluator`: sparse linear value over `ValueFeatures`.

`LinearWeights` contains `bias` and a map of feature names to coefficients. `LinearValueEvaluator`
accepts it directly or loads its JSON. See [value models](../../docs/value-models.md)
for formulas, feature encoding and examples. Search composition lives in
`argentum-policy`.
