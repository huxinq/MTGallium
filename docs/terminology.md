# Terminology

The vocabulary for reasoning about information, belief, value and search.
The [white paper](whitepapers/README.md) defines each term precisely and works
through an example. The [glossary](glossary.md) covers implementation and
experiment terms.

## Game and information

| Term | Meaning | Code |
| --- | --- | --- |
| State | The complete game configuration, including hidden cards, library order and the engine's random generator. | Argentum `GameState` |
| Chance | Randomness: the deal, shuffles, die rolls, coin flips and random discards. A shuffle is a single chance action. | `GameState.rng` |
| Player choice | Anything the rules ask a player to decide, including targets, ordering, blockers and mulligans. A transition runs automatic rules processing until the next choice. | `SearchWorld.step` |
| Observation | What one step discloses to one player; possibly nothing. | `PolicyHistoryEvent` |
| Information state | Everything one player has observed, in order, including their own choices. | represented by `InformationStateRepresentation` |
| Information set | The histories a player cannot rule out given their information state. | — |
| Knowledge | A fact true in every history of the information set. Only knowledge excludes histories on logical grounds; a belief can give a compatible history zero probability under its model. | `PolicyKnowledgeState` |
| World | A state together with both players' information states. Continuing a game requires a world, not just a state. | `SearchWorld` |

## Belief and value

| Term | Meaning | Code |
| --- | --- | --- |
| Policy | A rule giving a distribution over legal actions from its player's information state. | `ActionSelector`, `ActionDistributionModel` |
| Opponent model | The policy assumed for the opponent, in beliefs and in search. | `OpponentPolicy` |
| Belief | A probability distribution over a player's information set, or equivalently over its worlds. | `BeliefSnapshot` |
| Consistency belief | Deals unseen cards uniformly from the known decklists, subject to knowledge. It ignores what opponent choices suggest. | `BeliefMode.CONSISTENCY_ONLY_V1` |
| Policy-conditioned belief | Also conditioned on the opponent's policy: weights worlds by the opponent model's probability of each observed opponent choice. | `BeliefMode.POLICY_CONDITIONED_V1` |
| Particle | One weighted world in a finite approximation of a belief. | `Weighted<SearchWorld>` |
| Value, action value | Expected terminal payoff under a belief and continuation policies; an action value first forces one action. | — |
| Target | The question a value answers: whose payoff, which belief, which continuation policies. | — |
| Information-state evaluator | Scores one player's information state; it cannot use information unavailable to that player. | `InformationStateEvaluator` |
| World evaluator | Scores a complete world, hidden cards included. Legitimate when it predicts play that respects each player's information; inflated when its score assumes clairvoyant choices. | `LeafValueSource.SampledWorld` |

## Search

| Term | Meaning | Code |
| --- | --- | --- |
| Root player | The player the search chooses for. | `rootPlayer` |
| Determinization | A complete world sampled from a belief. | `SearchWorld` |
| Strategy fusion | Choosing differently in worlds the player cannot tell apart, for example by solving each determinization separately. It overvalues the position. | — |
| Tree node | One root-player information state with its admitted menu. Opponent and chance steps are sampled inside the world and add no node. | `PlanningContextKey` |
| Simulation | One pass: sample a world, descend the tree by UCT, settle a leaf, back up the value. | `InformationSetSearch` |
| Leaf | The position where tree descent stops for one simulation. | — |
| Rollout | Continued play under separate rollout policies for each seat, up to a decision or turn limit. | `LeafEvaluationConfig`, `RolloutTurnHorizon` |
| Quiet position | A condition: empty stack, no combat in progress, no pending combat-damage or ordering decision, no creature with lethal damage marked. It does not mean tactics have played out. | `isVolatile` in `InformationSetSearch` |
| Forced pass | A priority pass when passing is the only legal action. Search reaches a quiet position by taking only forced passes; optional variants also take profile-forced passes or rollout choices. | `QuiescencePassRule`, `RolloutCutoff` |
| Settlement | The single value one simulation backs up (adds to every edge it took), from the root player's perspective. | `SearchSettlement` |
| Settlement origin | Terminal payoff, heuristic settlement, learned outcome estimate or neutral (0). | `SearchSettlementOrigin` |
| Visits, search mean | Settlements added to an edge, and their average. The mean mixes changing later choices and settlement kinds, so it need not estimate the chosen action's terminal payoff. | `SearchCandidateStatistics` |
| Root value | The visit-weighted mean over all root edges; it describes the actions searched, not the one chosen. | `InformationSetSearchResult.rootValue` |
| Chance stream | The seed for a hypothetical world's future randomness, drawn from search randomness and never from the referee's generator. Simulations of the same particle within one search replay it. | `futureChanceStreamIdentity` |
| Perspective safety | Design rule: the chosen action depends on the actual game only through the root player's information state. An information boundary, not a claim about accuracy. | [information boundaries](architecture/information-and-decisions.md) |

## Representations and actions

| Term | Meaning | Code |
| --- | --- | --- |
| Representation | The encoding of an information state: snapshot, visible history, knowledge and, at a decision, the menu. | `InformationStateRepresentation` |
| Safe, complete, sufficient | Computed from the information state alone; loses nothing; keeps what one task needs. Complete implies sufficient for every task. | — |
| Semantic choice | An action described in terms the player can observe. | `SemanticChoice` |
| Rebinding | Resolving a semantic choice to the corresponding native action in one world. | — |
| Legal, proposed, admitted, accepted | Allowed by the rules; produced by action generation; on the menu considered; applied by the engine. | `PolicyExpansion` |

## Former names

Older notes and results may use these names.

| Former | Current |
| --- | --- |
| Player history | Information state |
| Compatible histories, compatibility class | Information set |
| Full epistemic state | World |
| Sampled-world evaluator | World evaluator |
| State evaluator | Information-state evaluator |
| Value target | Target |
| Search-conditioned response | Not used: search models the opponent with a fixed policy |
