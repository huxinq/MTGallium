# MTGallium glossary

Definitions used by the [API map](terminology.md), [architecture](architecture.md),
and [white paper](whitepapers/README.md). Usage and commands belong in the
[research guides](README.md#run-research).

## Information and knowledge

### Game state and game history

A game state is the current configuration at a player choice or termination.
A game history is an initial state followed by legal choices and their resulting
states. The formal model excludes engine randomness from the transition after
each choice.

### Observation

Information disclosed to a player by the game. One transition can produce an
ordered sequence of observations, or none.

### Information state

The player's full ordered observation history, including their own observed
choices. Also called player history in the theoretical model.

### Compatibility and information set

The legal game histories that produce a player's stated observation history form
that player's information set. Mapping them to current states can merge histories
with different pasts. See [the formal model, §4](whitepapers/README.md).

### Player observation snapshot

`PlayerObservationSnapshot` records the current player-facing view, including
visible zones and pending decision data. `observationDigest` identifies this
snapshot. See [fields][policy-contract].

### Information-state representation

`InformationStateRepresentation` combines a snapshot, player-visible event history,
represented exact knowledge, and candidate choices. Its digest includes the
candidate expansion. `BoundedPolicyInput` further limits the representation for
neural policies. See [fields][policy-contract].

### Full epistemic state

The game state together with both players' information states. “Epistemic”
means concerning information or knowledge.

### Epistemic state and decision site

`EpistemicState` captures the represented observation, history, and knowledge
independently of action enumeration. `DecisionSite` adds the actor and an ordered
menu with completeness and proposal metadata. Its `information()` record and
digest use that same menu.

### Captured decision view

`DecisionView` requests menu expansion, optional policy annotations, and an
expansion limit. `DecisionSiteRequest` captures that demand at one world revision;
expensive information projection can remain lazy.

### Perspective safety

A policy receives public information, its player's own private information, and
legitimately remembered facts. The trusted Argentum adapter projects these from
full engine state. See [information boundaries](architecture/information-and-decisions.md).

### Represented knowledge

Exact facts recoverable from declared known decks and the player's visible history
(`PolicyKnowledgeState`), including remembered card identities and library
positions. Tracking can be explicitly incomplete after a visible transition the
adapter cannot represent exactly. See [the knowledge contract][knowledge-contract].

### Belief and particles

A belief assigns probabilities to compatible histories. A particle represents a
hypothetical world; its weight supplies relative mass in the approximation.
A posterior incorporates observed evidence under the configured inference model.

### Belief backend and snapshot

A backend maintains the belief and publishes a snapshot with aggregate queries
and independent weighted worlds for continuation. Both capabilities refer to the
same perspective, epistemic state, inference model, and snapshot token (an
opaque identity for one snapshot).

### Belief queries

`OpponentHandBeliefQueries` estimates card presence, copy thresholds, joint
requirements, unions, and expected counts in other players' pooled hands. The
Argentum implementation captures particle weights and counts; a subsequent belief
update needs a fresh snapshot. Joint queries preserve particle correlations.

### Probabilistic support

The histories or hypotheses assigned positive mass by a belief.

### Determinization

A hypothetical world constructed by filling in hidden information consistently
with represented knowledge.

### Knowledge consistency

Agreement with the snapshot, knowledge projection, visible history, known
objects and zones, and remembered library prefix checked by
`knowledgeConsistencyFailure`. See [adapter checks][search-world].

### Observation conditioning

Discard hypothetical descendants that cannot explain newly observed information
and renormalize the remainder. The conditioned tracker combines this test with
action likelihood before resampling. See
[belief maintenance](architecture/policies-and-beliefs.md#belief-maintenance).

## Actions and decisions

### Semantic choice and signature

`SemanticChoice` describes an intended action. Its signature identifies the
represented intent or search group. Display text describes it; payload carries
details; routing names the current objects needed for execution.

### Exact declaration and search group

An exact declaration retains the native action and occurrence-specific routing.
A search group collects declarations under a planning abstraction. See
[exact actions](architecture/information-and-decisions.md#exact-actions-and-search-groups).

### Qualified observed-object correspondence

A cross-world join using the observing player's acquired object handles and
checked incarnation continuity. This permits matching an actor's choice to
objects the observer remembers. See
[cross-world correspondence](architecture/information-and-decisions.md#cross-world-action-correspondence).

### Conditional member probability

The probability of a concrete action given its selected search group. Exact
action likelihood is the group probability times this conditional probability.

### Legal, proposed, admitted, and accepted actions

| Stage | Meaning |
| --- | --- |
| Legal | Allowed by the engine's rules in the state. |
| Proposed | Represented by action generation as a candidate. |
| Admitted | Included in the configured selection menu. |
| Accepted | Applied successfully by the engine after rebinding. |

### Live policy selection

`RootActionSelection` records the chosen semantic action. Searched selections
also carry the search result. `ResolvedPolicyDecision` adds the rebound engine
payload; the caller then submits it for acceptance.

### Menu, action profile, and completeness

An action profile declares the covered families and representation limits.
Menu-profile completeness means the supplied candidates cover that profile;
rules completeness means they cover every rules-legal action in that state.

### Rebinding

Resolve a semantic choice to the current native objects and legal action before
submitting it to the engine.

### Combat edge reference

A local name for a damage edge, built from its direction, trample-drain role,
and viewer-safe endpoint references. The adapter maps it to the native wire ID.
Indistinguishable edges produce an ambiguity failure.

### Genuine player decision and simulation

A player choice includes responses, targets, ordering, and mulligans. Simulation
advances to the next such choice, termination, or an execution failure.

### Non-game failure

Execution failed to supply a transition or result, for example because of rejected
input or unsupported representation. At a play limit the game remains unfinished
and `payoffs=None`.

### Snapshot locator

A reference to one entry in a current observation. Remembered object references
instead link occurrences across observations. See
[history references](architecture/information-and-decisions.md#remembered-battlefield-references).

### Incarnation

One engine object's identity from one zone entry to the next; under the MTG
rules, an object that changes zones becomes a new object. Incarnations stay
inside the trusted adapter, which checks remembered handles against them so a
handle never follows a card into a later incarnation.

### Qualified resolution source

A reference to an observed stack spell, matched to a native stack-to-battlefield
incarnation transition. It names the spell before resolution. See
[history references](architecture/information-and-decisions.md#remembered-battlefield-references).

## Search and learning

### Policy

A behavioral policy assigns an action distribution from player information.
A deterministic policy assigns all mass to one action.

### Policy role contracts

`ActionSelector` selects from a supplied menu. `ActionDistributionModel`
assigns probabilities to that menu. `OpponentPolicy` supplies both. Live action
selection, root-player continuation, opponent continuation, and belief updating
configure their policies separately.

### Search node and action branch

Nodes group visits by actor and represented information; branches record semantic
choices. See [search](../agent/infoset-planning/src/main/kotlin/org/mtgallium/agent/infoset/core/InformationSetSearch.kt).

### Root and leaf

The root starts a search and determines its player payoff perspective. A leaf is
where tree exploration stops and obtains a settlement.

### Rollout and continuation policy

A rollout simulates play with separately configured root-player and opponent
policies. The policy used to infer observed opponent choices has its own role.

### Search-conditioned response model

A simulated policy that also depends on the search record. Equal opponent
information across different root searches can then receive different responses.
See the counterexample in [the formal model](whitepapers/README.md).

### Factual-root branch

A continuation from a captured actual game position. Its return is conditional
on that hidden world and the continuation policies. Forking copies game and
native search state; copy Python policy state separately. "Factual" here means
the actual game; the [factual policy tensors](#factual-policy-tensors) use the
word differently.

### Visits and backups

A backup adds a simulation's settlement to tree statistics. Node and edge visits
count successful backups. Each search creates a fresh tree; prefix caching
reuses computation within that search.

### Information-state evaluator

`InformationStateEvaluator` scores the perspective player's represented
information. See [value models](value-models.md).

### Target value and search mean

A target value specifies expected payoff under a belief and continuation
policies; an action value also fixes the current action. A search mean averages
the settlements backed to a node or branch under its actual adaptive sampling.

### Value, payoff, and settlement

| Quantity | Meaning |
| --- | --- |
| Terminal payoff | The specified player's result after the game in that world ends. |
| Information-state evaluation | A score computed from that player's represented information. |
| Sampled-world evaluation | A score computed using a complete hypothetical world. |
| Bounded-rollout settlement | A value supplied by the configured rule at a continuation limit. |

A settlement is the value one simulation backs up into the tree, supplied by one
of these routes. `SearchSettlementOrigin` records which route supplied it.

### Features, model, fit, and checkpoint

Features are a model's numerical inputs. A fit produces parameters; a checkpoint
stores parameters and any state needed to resume training. `LinearWeights`
contains a bias and sparse coefficients; `ValueFeatures` defines the inputs used
by the [linear and residual evaluators](value-models.md).

### Learned policy memory

Numeric model state computed from the events delivered to a player so far.
Each game, player, and branch owns separate memory. Observation updates advance
its history cursor; repeated scoring does not. See [neural policies](neural-policy.md).

### Factual policy tensors

Numeric inputs encoding player information, delivered events, and candidate
actions as normalized UTF-8 bytes. The schema fixes bounds, padding, and masks.
See [the tensor contract](neural-policy.md).

### Metamorphic test

A test that changes a declared irrelevant input and checks that the relevant
output remains unchanged. Pair it with a case that must produce a different
output. See [history event order](architecture/information-and-decisions.md#history-event-order).

## Experiments and evidence

### Treatment, control, and intervention

The treatment is the candidate configuration; the control is its comparison.
The intervention is the changed component or behavior.

### Statistical grouping

The relationships among observations used to define joint measurements and
independent comparison units. Sibling branches and positions from one game
retain their shared game group.

### Replay and decision records

A referee replay contains full engine state and stays private. Decision records
contain the acting player's information and accepted choice. The current replay
reader supplies state playback. See [output files](research-cli.md#output-and-limits).

### Population and seed group

A population is the collection a measurement describes, with stated inclusion
rules and unit. A seed group associates games or branches sharing a random setup.

### Paired gameplay

Compare policies by swapping seats under a declared shared setup schedule.
Average the two game scores to obtain one observation per setup group.

### Development, validation, and held-out data

Development data guide fitting and methodological choices. State whether
evaluation data were held out from the fit, model selection, or all adaptive
inspection.

### Wall time, CPU time, and decision cost

Wall time is elapsed clock time. CPU time counts processor time used.
Decision cost covers the stated preparation and selection stages. Concurrent
component times can overlap.

## Maintaining the glossary

Add an entry for a recurring concept when its meaning is not clear from ordinary
MTG or mathematical usage. Verify it against the owning source; link to the
guide that explains its use.

[policy-contract]: ../agent/infoset-semantics/src/main/kotlin/org/mtgallium/agent/infoset/core/PolicyContract.kt
[knowledge-contract]: ../agent/infoset-semantics/src/main/kotlin/org/mtgallium/agent/infoset/core/KnowledgeState.kt
[search-world]: ../agent/infoset-argentum/src/main/kotlin/org/mtgallium/agent/infoset/argentum/ArgentumSearchWorld.kt
