# MTGallium glossary

Implementation and experiment terms. Information state, belief, world, value,
settlement and the other terms of the model are defined in
[terminology](terminology.md) and the [white paper](whitepapers/README.md).
Usage and commands belong in the [research guides](README.md#run-research).

## Information and knowledge

### Player observation snapshot

`PlayerObservationSnapshot` records the current player-facing view, including
visible zones and pending decision data. `observationDigest` identifies this
snapshot. See [fields][policy-contract].

### Information-state representation

`InformationStateRepresentation` combines a snapshot, player-visible event history,
represented exact knowledge, and candidate choices. Its digest includes the
candidate expansion. `BoundedPolicyInput` further limits the representation for
neural policies. See [fields][policy-contract].

### Epistemic state and decision site

`EpistemicState` captures one player's represented observation, history, and
knowledge independently of action enumeration. Despite its name, it describes
one player, not a [world](terminology.md#game-and-information). `DecisionSite`
adds the actor and an ordered menu with completeness and proposal metadata. Its
`information()` record and digest use that same menu.

### Captured decision view

`DecisionView` requests menu expansion, optional policy annotations, and an
expansion limit. `DecisionSiteRequest` captures that demand at one world revision;
expensive information projection can remain lazy.

### Represented knowledge

Exact facts recoverable from declared known decks and the player's visible history
(`PolicyKnowledgeState`), including remembered card identities and library
positions. Tracking can be explicitly incomplete after a visible transition the
adapter cannot represent exactly. See [the knowledge contract][knowledge-contract].

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

### Knowledge consistency

Agreement with the snapshot, knowledge projection, visible history, known
objects and zones, and remembered library prefix checked by
`knowledgeConsistencyFailure`. See [adapter checks][search-world].

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

### Live policy selection

`RootActionSelection` records the chosen semantic action. Searched selections
also carry the search result. `ResolvedPolicyDecision` adds the rebound engine
payload; the caller then submits it for acceptance.

### Menu, action profile, and completeness

An action profile declares the covered families and representation limits.
Menu-profile completeness means the supplied candidates cover that profile;
rules completeness means they cover every rules-legal action in that state.

### Combat edge reference

A local name for a damage edge, built from its direction, trample-drain role,
and viewer-safe endpoint references. The adapter maps it to the native wire ID.
Indistinguishable edges produce an ambiguity failure.

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

### Policy role contracts

`ActionSelector` selects from a supplied menu. `ActionDistributionModel`
assigns probabilities to that menu. `OpponentPolicy` supplies both. Live action
selection, root-player continuation, opponent continuation, and belief updating
configure their policies separately.

### Factual-root branch

A continuation from a captured actual game position. Its return is conditional
on that hidden world and the continuation policies. Forking copies game and
native search state; copy Python policy state separately. "Factual" here means
the actual game; the [factual policy tensors](#factual-policy-tensors) use the
word differently.

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
