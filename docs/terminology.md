# Concepts and APIs

The [white paper](whitepapers/README.md) defines the mathematical model; the
[glossary](glossary.md) defines recurring terms.

| Concept | Current API |
| --- | --- |
| Current player view | `PlayerObservationSnapshot` |
| Snapshot, safe history, exact knowledge, and candidates | `InformationStateRepresentation` |
| Candidate-free represented information | `EpistemicState` |
| Captured information and ordered action menu | `DecisionSite` |
| Remembered facts | `PolicyKnowledgeState` |
| Trusted world for hypothetical continuation | `SearchWorld` |
| Action selection | `ActionSelector`, `RootActionSelector` |
| Probabilities over a supplied menu | `ActionDistributionModel` |
| Player-information score | `InformationStateEvaluator` |
| Origin of a backed value | `SearchSettlementOrigin` |
| Bias and sparse coefficients | `LinearWeights` |
| Live engine policy state | `LivePolicySession` |
| Search settings and state | `SearchPolicyConfig`, `SearchPolicySession` |

Use [information state](glossary.md#information-state) for the player's theoretical
observation history and **representation** for its concrete encoding.
`observationDigest` identifies a snapshot; `informationStateDigest` includes
the represented history, knowledge, and candidate expansion. The candidate-free
epistemic digest supports joins independent of menu expansion.

[Compatibility](glossary.md#compatibility-and-information-set) identifies legal
histories producing the observation history. [Belief](glossary.md#belief-and-particles)
assigns probabilities; [support](glossary.md#probabilistic-support) identifies
positive mass. `knowledgeConsistencyFailure` checks represented facts in a
hypothetical world.

Name the player perspective, belief, and continuation policies for an expected
payoff. Use [search mean](glossary.md#target-value-and-search-mean) for backed
settlement averages and [terminal payoff](glossary.md#value-payoff-and-settlement)
for the result of a completed game in the specified world.

[Legal, proposed, admitted, and accepted](glossary.md#legal-proposed-admitted-and-accepted-actions)
name action-processing stages. A selected `SemanticChoice` is rebound to the
current native objects before submission. `visits` counts successful backups.

See [architecture](architecture.md) for source ownership.
