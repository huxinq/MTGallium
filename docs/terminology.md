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
| Observation | What one step discloses to one player; possibly nothing. | `ObservedEvent` |
| Information state | Everything one player has observed, in order, including their own choices. | represented by `InformationStateRepresentation` |
| Information set | The histories a player cannot rule out given their information state. | — |
| Knowledge | A fact true in every history of the information set. Only knowledge excludes histories on logical grounds; a belief can give a compatible history zero probability under its model. | `PlayerKnowledge` |
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
| World evaluator | Scores a complete world, hidden cards included. Legitimate when it predicts play that respects each player's information; inflated when its score assumes clairvoyant choices. | — |

## Search

| Term | Meaning | Code |
| --- | --- | --- |
| Root player | The player the search chooses for. | `rootPlayer` |
| Determinization | A complete world sampled from a belief. | `SearchWorld` |
| Strategy fusion | Choosing differently in worlds the player cannot tell apart, for example by solving each determinization separately. It overvalues the position. | — |
| Tree node | One root-player information state with its admitted menu. Opponent and chance steps are sampled inside the world and add no node. | `TreeNodeKey` |
| Simulation | One pass: sample a world, descend the tree by UCT, settle a leaf, back up the value. | `InformationSetSearch` |
| Leaf | The position where tree descent stops for one simulation. | — |
| Rollout | Continued play under separate rollout policies for each seat, up to a decision or turn limit. | `LeafEvaluationConfig`, `RolloutTurnHorizon` |
| Quiet position | A condition: empty stack, no combat in progress, no pending combat-damage or ordering decision, no creature with lethal damage marked. It does not mean tactics have played out. | `isVolatile` in `InformationSetSearch` |
| Forced pass | A priority pass when passing is the only legal action. Search reaches a quiet position by taking only forced passes; optional variants also take profile-forced passes or rollout choices. | `QuiescencePassRule`, `RolloutCutoff` |
| Settlement | The single value one simulation backs up (adds to every edge it took), from the root player's perspective. | `SimulationReturn` |
| Settlement origin | Terminal payoff, heuristic settlement, learned outcome estimate or neutral (0). | `ReturnSource` |
| Visits, search mean | Settlements added to an edge, and their average. The mean mixes changing later choices and settlement kinds, so it need not estimate the chosen action's terminal payoff. | `RootActionStatistics` |
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
| Legal, proposed, admitted, accepted | Allowed by the rules; produced by action generation; on the menu considered; applied by the engine. | `ActionMenu` |

## Former names

Older notes and results may use these names.

| Former | Current |
| --- | --- |
| `LuckCorrection` / `LuckCorrectionConfig` | `ChanceControlVariate` / `ChanceControlVariateConfig` (historical config descriptor and Python keys unchanged) |
| `LuckEvent.weightedLuck` | `LuckEvent.controlVariateTerm` (serialized key stays `weightedLuck`; thinning still retains unweighted terms) |
| `*ForHost` | Suffix dropped; privileged operations remain host-only |
| `authoritativeState` / `authoritativeStateForHost` | `trueState` |
| `authoritativeFingerprint` | `stateFingerprint` |
| `permuteHiddenTruthForHost` | `forkPermutingHiddenCards` |
| `forkForHypotheticalSearch` / `withSampledState` | `forkWithChanceStream` / `withDeterminizedState` |
| `captureObservedActionForHost` / `correspondObservedActionForHost` | `recordObservedAction` / `matchObservedAction` |
| `LinearValueLink.CLIP` | `InverseLink.CLIPPED_IDENTITY` (historical JSON and identity text pinned) |
| `rawScore` / `deployedValue` | `linearPredictor` / `value` (linear estimate and material-score APIs) |
| `ReturnSource.LEARNED_OUTCOME_ESTIMATE` | `ReturnSource.MODEL_ESTIMATE` (historical wire token pinned) |
| `perspectivePlayerId` | `viewerId` (MTGallium properties; serialized key and native engine field unchanged) |
| `epistemicallyComplete` | `isComplete` (serialized key unchanged) |
| `expansion` (an `ActionMenu` property, parameter, or local) | `menu` (generation results and specifications keep their names) |
| `requiresProductionAdmission` | `requiresArgentumAiChoiceOnMenu` (recorded JSON key unchanged) |
| `requiresPolicyAnnotations` | `requiresArgentumAiChoiceTag` |
| `epistemicState()` | `informationStateWithoutMenu()` (retained transcript label unchanged) |
| `PolicyKnownLibraryOrder` | `KnownLibraryOrder` |
| `PolicyAttackerView` | `AttackerView` |
| `PolicyBlockerView` | `BlockerView` |
| `PolicyManaPool` | `ManaPoolView` |
| `PolicyRestrictedMana` | `RestrictedManaView` |
| `PolicyAudience` | `EventAudience` |
| `PolicyAudienceScope` | `EventAudienceScope` |
| `PolicyExpansionOmissionReason` | `ActionOmissionReason` |
| `PolicyRecentEventWindow` | `RecentEventWindow` |
| `SafeObservationProjection` | `PlayerObservationProjection` |
| `UnifiedSemanticExpansionSpecification` | `ActionGenerationSpecification` |
| `UnifiedExpansionResult` | `ActionGenerationResult` |
| `QualifiedObservedObjectCorrespondence` | `ObservedObjectCorrespondence` |
| `BoundedPolicyInputConfig` | `PolicyInputLimits` |
| `BoundedPolicyInputCompiler` | `PolicyInputCompiler` |
| `ConfiguredInformationStateEvaluator` | `ParameterizedInformationStateEvaluator` |
| `BoundedRolloutObserver` | `RolloutObserver` |
| `DecisionAdmission` | `MenuSource` |
| `SearchActionSpaceProfile` | `ActionSpaceProfile` |
| `BeliefArchitecture` | `BeliefApproximation` |
| `LeafStateSource` | `LeafEvaluationMethod` |
| `PerspectiveHistoryEventOrder` | `HistoryEventOrdering` |
| `PerspectiveHistoryObjectReference` | `HistoryObjectReferencing` |
| `UnifiedSemanticExpander` | `ArgentumActionGenerator` |
| `BoundedDecisionResponseProposer` | `DecisionResponseGenerator` |
| `BlockStructuredActionSpace` | `BlockerDeclarationSpace` |
| `StructuredActionSpace` | `FactoredActionSpace` |
| `SafeObservationProjector` | `PlayerObservationProjector` |
| `SafeReferenceMap` | `ObservationReferenceMap` |
| `PerspectiveEventProjector` | `EventObservationProjector` |
| `PerspectiveHistory` | `InformationStateRecorder` |
| `ArgentumHeuristicAnnotator` | `ArgentumAiChoiceLabeler` |
| `ArgentumActionCorrespondence` | `ObservedActionMatch` |
| `PolicyKnowledgeState` | `PlayerKnowledge` |
| `PolicyZoneKnowledge` | `ZoneKnowledge` |
| `PolicyKnownObject` | `KnownObject` |
| `PolicyKnowledgeAccumulator` | `KnowledgeTracker` |
| `PolicyKnowledgeReducer` | `KnowledgeReplay` |
| `PolicyPlayerView` | `PlayerView` |
| `PolicyZoneView` | `ZoneView` |
| `PolicyCardView` | `ObjectView` |
| `PolicyStackItemView` | `StackObjectView` |
| `PolicyPendingDecisionView` | `PendingDecisionView` |
| `PolicyCombatView` | `CombatView` |
| `PolicyDecisionChoiceSpec` | `PendingDecisionOptions` |
| `PolicyHistoryEvent` | `ObservedEvent` |
| `PolicyHistoryEventKind` | `ObservedEventKind` |
| `PerspectiveEventDetail` | `ObservedEventDetail` |
| `PolicyHistorySnapshot` | `ObservationHistory` |
| `PolicyHistoryCommitment` | `HistoryHashChain` |
| `PolicyExpansion` | `ActionMenu` |
| `DecisionSiteRequest` | `DecisionContext` |
| `DecisionSite` | `DecisionPoint` |
| `DecisionView` | `MenuRequest` |
| `AdmittedMenuRefinement` | `MenuWidening` |
| `EpistemicState` | `InformationState` |
| `PolicyJson` | `CanonicalJson` |
| `SearchSettlement` | `SimulationReturn` |
| `SearchSettlementOrigin` | `ReturnSource` |
| `SearchSettlementCounts` | `ReturnSourceCounts` |
| `SearchCandidateStatistics` | `RootActionStatistics` |
| `PlanningContextKey` | `TreeNodeKey` |
| `SingletonSelectionConfig` | `SingletonMenuShortcutConfig` |
| `selectedSearchWinnerOrNull` | `mostVisitedActionOrNull` |
| Player history | Information state |
| Compatible histories, compatibility class | Information set |
| Full epistemic state | World |
| Sampled-world evaluator | World evaluator |
| State evaluator | Information-state evaluator |
| Value target | Target |
| Search-conditioned response | Not used: search models the opponent with a fixed policy |
| `RootActionStatistics.policyProbability` | `RootActionStatistics.visitFraction` (recorded JSON key unchanged) |
| `unsettledLeafEvaluations` | `nonQuietLeafEvaluations` (recorded JSON key unchanged) |
| `settleStaticLeaf` / `settleWithRolloutPolicies` | `evaluateStaticLeaf` / `evaluateWithRolloutPolicies` |
| `isVolatile` | `isQuiet` (predicate and consumers invert together) |
| `QuiescencePassRule.PROFILE_FORCED_WHILE_VOLATILE_V1` | `QuiescencePassRule.PROFILE_FORCED_WHEN_NOT_QUIET` (historical wire token pinned) |
| `agent/mono-red-models`, `org.mtgallium.agent.monored` | `agent/value-models`, `org.mtgallium.agent.value` |
| `MonoRedInformationEvaluator` / `ConfiguredMonoRedInformationEvaluator` | `MaterialEvaluator` |
| `MonoRedVisibleEvaluatorConfig` / `MonoRedVisibleFeatures` / `MonoRedVisiblePermanentFeatures` | `MaterialWeights` / `MaterialFeatures` / `MaterialPermanentFeatures` |
| `NativePolicyProvider` | `JvmPolicyProvider` (live service descriptor filename follows the interface) |
| `NativeValueProvider` | `JvmValueModelProvider` (live service descriptor filename follows the interface) |
| `NativePolicy` | `JvmPolicy` |
| `NativePolicy.Direct` | `JvmPolicy.Memoryless` |
| `NativePolicy.Search` | `JvmPolicy.SearchSession` |
| Trusted binding `incarnation` | `objectRef` (new object, CR 400.7) |
| `Research.kt` / `ResearchKt` | `ResearchCli.kt` / `ResearchCliKt` (live CLI entrypoint) |
| `PythonResearch` / `PythonResearch.kt` | `GameServer` / `GameServer.kt` (live server entrypoint) |
| `PythonResearchConnection` | `GameServerConnection` |
| Workbench `Player` | `GameAgent` (engine and visible-reference player types unchanged) |
| `productionChoice` | `argentumAiChoice` (policy key `production` unchanged) |
| Evaluation `Probes.kt` | `Checks.kt` |

Retained names (compatibility aliases deliberately omitted):

| Existing name | Proposed spelling omitted | Reason |
| --- | --- | --- |
| `game.ai.search-teacher` | `game.ai.information-set-search` | Keep the deployed Spring key; no dual-key binder. |
| `factual`, `Decision.factual` | `byte_tokens`, `Decision.byte_tokens` | Keep the Python keyword and field; no alias wrapper. |
| `evaluate`, `setups` | `compare_policies`, `pairs` | Keep the Python call signature; no alias wrapper. |
| `FactualEncodingException` | `ByteTokenEncodingException` | The JVM name is the structured error type. |
| `org.mtgallium.agent.monored.ValueEvaluationException`, `ValueEvaluationStop` | `org.mtgallium.agent.value` package | Keep structured error names without a mapper. |
| `SEMANTIC` | `GENERATED` | Preserve enum text and seed inputs without adapters. |
| `PRODUCTION` | `WITH_ARGENTUM_AI_CHOICE` | Preserve enum text and seed inputs without adapters. |
| `RULES_EXACT_V1` | `ALL_LEGAL_ACTIONS` | Preserve enum text and seed inputs without adapters. |
| `MONO_RED_FAST_MANA_PRUNED_V1` | `OMIT_STANDALONE_MANA_ABILITIES` | Preserve enum text and seed inputs without adapters. |
| `SNAPSHOT_A_V1` | `INDEPENDENT_DETERMINIZATIONS` | Preserve enum text and seed inputs without adapters. |
| `SEQUENTIAL_B_V1` | `SEQUENTIAL_PARTICLE_FILTER` | Preserve enum text and seed inputs without adapters. |
| `PRIVILEGED_O_V1` | `CLAIRVOYANT_TRUE_STATE` | Preserve enum text and seed inputs without adapters. |
| `CONSISTENCY_ONLY_V1` | `UNIFORM_CONSISTENT_DEALS` | Preserve enum text and seed inputs without adapters. |
| `POLICY_CONDITIONED_V1` | `OPPONENT_MODEL_POSTERIOR` | Preserve enum text and seed inputs without adapters. |
| `CURRENT_INFORMATION_STATE` | `STATIC` | Preserve enum text and seed inputs without adapters. |
| `BOUNDED_ROLLOUT` | `TRUNCATED_ROLLOUT` | Preserve enum text and seed inputs without adapters. |
| `LEGACY_ENGINE_ORDER_V1` | `ENGINE_EMISSION_ORDER` | Preserve enum text and seed inputs without adapters. |
| `QUALIFIED_TURN_UNTAP_V2` | `UNTAP_STEP_CANONICAL_ORDER` | Preserve enum text and seed inputs without adapters. |
| `LEGACY_SNAPSHOT_V1` | `OBSERVATION_SCOPED_REFERENCES` | Preserve enum text and seed inputs without adapters. |
| `QUALIFIED_OBSERVED_OBJECTS_V2` | `PERSISTENT_OBJECT_REFERENCES` | Preserve enum text and seed inputs without adapters. |
| `PROFILE_FORCED_WHILE_VOLATILE_V1` | `PROFILE_FORCED_WHEN_NOT_QUIET` | Preserve enum text and seed inputs without adapters. |
| `LEARNED_OUTCOME_ESTIMATE` | `MODEL_ESTIMATE` | Preserve enum text and seed inputs without adapters. |
| `CLIP` | `CLIPPED_IDENTITY` | Preserve enum text and seed inputs without adapters. |
