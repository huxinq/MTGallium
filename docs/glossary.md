# MTGallium glossary

This is the shared reference for terminology in MTGallium documentation and
discussions. It explains established meanings in plain language and links to the
public documents or source that define the details. It does not change those
contracts or establish a research result. Historical artifacts keep the meanings
and versions under which they were produced.

Search this page for a term, alias, or code name, or start with a topic:

- [Tools and subsystems](#tools-and-subsystems): component map, research workbench,
  research commands, artifact system, builds, preflight, diagnostics, local workers.
- [Information and knowledge](#information-and-knowledge): perspective safety,
  observation, information state, represented knowledge, belief, determinization.
- [Actions and decisions](#actions-and-decisions): semantic choice, legal/proposed/
  admitted/accepted, rebinding, action profile, simulation, non-game failure.
- [Search and learning](#search-and-learning): Search Teacher, root/leaf, rollout,
  visits/backups, value, terminal target, features, learned model.
- [Experiments and evidence](#experiments-and-evidence): treatment/control,
  provenance, replay, position bank, seed group, paired gameplay, validation,
  confidence sequence, stopping populations, runtime.
- [Maintaining the glossary](#maintaining-the-glossary): when and how to add entries.

## Tools and subsystems

### Subsystem map

These are the main parts of MTGallium and where their responsibilities live.
Follow the linked definitions for the concepts each part uses.

| Subsystem | What it does | Source or guide |
| --- | --- | --- |
| Argentum engine | Applies the game rules and maintains the full game state. MTGallium uses a specific reviewed revision. | [Pinned engine](../third_party/argentum-engine/README.md) |
| Information-set core | Defines player information, remembered knowledge, semantic choices, and search contracts and algorithms. | [`agent/infoset-core`](../agent/infoset-core), [information state](#information-state) |
| Argentum adapter | Connects those contracts to the engine while enforcing the player's information boundary. | [`agent/infoset-argentum`](../agent/infoset-argentum), [adapter definition](#argentum-adapter) |
| Search Teacher | Chooses actions by planning over possible play. | [`agent/search-teacher`](../agent/search-teacher), [policy definition](#policy-and-search-teacher) |
| Argentum Search Teacher integration | Connects Search Teacher to Argentum's application and player interfaces. | [`integration/argentum-search-teacher`](../integration/argentum-search-teacher) |
| Research evaluation | Runs gameplay comparisons, saved-position studies, fitting, and diagnostics under explicit plans. | [`evaluation/search-teacher`](../evaluation/search-teacher), [research workflow](research-workflow.md) |
| Learned outcome value | Supplies experimental model inference, training, and admission for predicting outcomes at search leaves. Inference lives in the policy module; training and evidence work live in evaluation. | [Subsystem guide](learned-outcome-value.md) |
| Research-run support | Records and verifies the source, configuration, and files associated with research work. | [`agent/research-run`](../agent/research-run), [artifact system](#research-run-artifact-system) |

### Research workbench

A command-line interface (CLI), used as the primary experiment interface by both
human researchers and agents, for preparing experiment drafts, checking plans,
launching bounded runs, and inspecting and reviewing retained outputs. It connects
the existing research commands; those commands still define the experiment and
its evidence checks. A draft describes the intended work, while a frozen attempt
records the exact request. The workbench tracks execution and assembles review
packets without choosing scientific settings or automatically retrying experiments.

Start with `python3 tools/mtgallium-research --help` or
`python3 tools/mtgallium-research catalog` in a checkout containing the workbench.
Durable launches use a Linux user systemd manager to keep bounded work running
outside the interactive session. Generated work stays outside the source checkout.

See [the workbench guide](research-workbench.md),
[the agent interface policy](../AGENTS.md#primary-experiment-interface), and
[the CLI entry point](../tools/mtgallium-research), all included in this checkout.

### Native research commands

The Kotlin command implementations that perform the actual research work.
“Native” here identifies the underlying commands used by the workbench, not
machine-code compilation. They include gameplay comparisons, position banks,
terminal-target studies, campaign data-use records, and transfer audits. Select
a command through `--suite` and supply its declared plan and inputs; commands
differ in whether they run new work or inspect existing evidence.
See [the command parser][research-cli], [gameplay guide][screening-sequential], and
[research workflow](research-workflow.md).

### Research-run artifact system

The shared code that gives research outputs an identity, registers their files,
and checks retained contents against their manifest (`ResearchRunArtifacts` in
`agent/research-run`). It also supplies source, build, preflight, cost, and private
output-path support. The workbench's operational records and review packets do
not replace the original producer's manifest. File verification establishes what
was retained; the scientific interpretation still needs the experiment's rules.
See [the artifact implementation][research-run-source] and
[provenance](#provenance-manifest-and-frozen-source).

### Research build and preflight tools

`tools/mtgallium-research-build` builds and retains a runtime from clean committed
source so a study can use a fixed set of binaries. The `research-preflight` suite
runs a small technical rehearsal for a specified workload;
`research-preflight-verify` checks that its retained pass still matches the
planned launch. Building and rehearsing answer different readiness questions.
A rehearsal may execute small games or fits and produce private artifacts; its
pass does not establish scientific validity or guarantee full-run resource use.
See [retained builds][workflow-build] and [the preflight guide](research-preflight.md).

### Inspection and profiling tools

Inspection reads retained results; profiling measures where execution spends
time or other resources. `just gameplay-summary` verifies and summarizes finalized
gameplay, while `just search-profile-summary` verifies and summarizes a retained
Java Flight Recorder (JFR) recording. These two commands do not launch games.
Other diagnostics, such as `SearchAdapterProfile`, run their own declared bounded
workload. Name the tool and measured scope: a search cost share is not a whole-game
speedup or a playing-strength result.
See [diagnostic tools](../tools/diagnostics/README.md) and
[gameplay inspection][workflow-runtime].

### Local workers

Locally hosted language models used by a coordinating agent for bounded source
investigation, log analysis, preliminary review, and drafts. The `local-worker`
command accepts a project directory and a task, then returns a compact summary
and a retained report. Its worker reads and suggests; the coordinator verifies
the result, edits source, and makes consequential decisions. This is development
and research assistance, separate from gameplay policies.

**Availability:** installed separately from the repository. The installed
`local-workers` skill and `local-worker --help` describe its interface;
`local-worker doctor` checks the local endpoint without performing inference.

## Information and knowledge

### Perspective safety

A policy receives only information its player is entitled to use. This includes
that player's own private information and legitimately remembered facts, as well
as public information. It excludes the opponent's unknown cards and the engine's
private bookkeeping. “Information-safe” and “policy-safe” describe this boundary;
they do not by themselves authorize publishing an artifact.
See [information boundaries][architecture-information] and [evidence boundaries][architecture-evidence].

### Argentum adapter

The trusted code that translates between Argentum's full game state and
MTGallium's player-facing information and actions. Argentum is the underlying
rules engine; `agent/infoset-argentum` owns this translation boundary. Full engine
state, also called referee state, stays inside the trusted boundary.
See [information boundaries][architecture-information].

### Observation

A snapshot of the game as one player can see it now (`PolicyObservation`). It
does not contain the entire remembered history. Two equal-looking snapshots can
therefore leave players with different knowledge.
See [the policy information contract][policy-contract].

### Information state

The represented context a player can legitimately use to choose: the observation,
safe history, exact knowledge, and represented candidate choices
(`PolicyInformationState`). “Infoset” is common project shorthand for the
information-set area. An observation alone is not a complete information state.
For example, remembering a previously revealed card can distinguish two otherwise
identical current observations.
See [the policy information contract][policy-contract].

### Represented knowledge

Exact facts the system can reconstruct from the known decklists and that player's
safe history (`PolicyKnowledgeState`). Remembered card identities and known
library positions belong here. A probability or plausible guess does not become
a fact. The representation can be explicitly incomplete when a visible transition
cannot be tracked safely and exactly.
See [the knowledge contract][knowledge-contract].

### Belief and particles

A belief represents uncertainty about what the player cannot see. A particle is
one possible complete world; its weight describes its share of the represented
belief. “Posterior” refers to the belief after accounting for available evidence
under the configured model. More particles can improve an approximation without
proving that the model or its assumptions are correct.
See [belief interfaces and modes][search-boundary].

### Determinization and support

A determinization fills in hidden information to construct one possible world
consistent with represented knowledge. It is a hypothesis, never the actual
hidden truth supplied to the policy. A support check asks whether a proposed
world satisfies the required represented constraints; it does not certify that
the world is the real one.
See [the research invariants][agent-invariants] and [terminal sampling][architecture].

## Actions and decisions

### Semantic choice and signature

A semantic choice describes the player's intended action (`SemanticChoice`). Its
signature identifies that intent for comparison. Display text describes it to a
reader; payload carries action details; routing identifies the current objects
needed to execute it; legality says whether it is allowed now. These concerns
must stay distinct. Similar strategic effects alone do not make two actions
semantically identical.
See [information and action boundaries][architecture-information].

### Legal, proposed, admitted, and accepted actions

These words describe different stages, not interchangeable labels for a move:

| Term | Plain-language meaning |
| --- | --- |
| Legal | The engine's rules allow the action in the relevant state. |
| Proposed | Action generation has represented it as a candidate. |
| Admitted | The configured search or selection path has included it in its menu. |
| Accepted | The engine has successfully applied the rebound choice. |

A proposal is not an exhaustive list of everything the rules allow. Selection
from an admitted menu does not guarantee acceptance in the live state. Elsewhere,
“admission” can mean validating data or a checkpoint; name what is being admitted.
See [action boundaries][architecture-information] and [checkpoint admission][learned-value].

### Menu, action profile, and completeness

A menu is the supplied collection of candidate choices. An action profile states
which action families and representation limits a search covers. A complete menu
covers that declared profile; it does not automatically cover every rules-legal
Magic action. Menu size alone cannot prove completeness.
See [action-space profiles][action-profile] and [menu completeness][architecture].

### Rebinding

Resolving a selected semantic choice against the objects and legal actions in
the state where it will actually be applied. A saved or sampled action's routing
references are not a guarantee that it can execute now. Acceptance follows a
successful application, not merely a matching label.
See [action boundaries][architecture-information].

### Genuine player decision and simulation

A genuine decision requires a player's choice, including a response, target,
ordering, or mulligan. A simulation tries hypothetical play. Each advance must
stop at the next genuine decision, a terminal state, or an explicit non-game
failure; search can then choose an action and continue. It must not silently
choose responses just to reach a convenient later state.
See [simulation boundaries][architecture-information].

### Non-game failure

Execution could not supply a valid game transition or result: for example,
rejection, timeout, unsupported representation, or stopped execution. Report the
failure with its reason. It supplies no win, loss, draw, or strategic value.
For example, a timed-out game is not a draw.
See [simulation boundaries][architecture-information].

## Search and learning

### Policy and Search Teacher

A policy is the procedure that chooses a player's action. Search Teacher is
MTGallium's hand-authored information-set planning policy: it searches possible
play over hidden-world hypotheses using the pinned engine. “Teacher” names its
role; its choice is not automatically a correct-move label. Learned components
and diagnostic models have separate configurations and evidence requirements.
See [the project overview][overview] and [screening limits][screening-bank].

### Root and leaf

The root is the starting decision for a search. The root player is the player
whose decision and payoff perspective that search concerns. A leaf is a point
where tree exploration stops and needs a result or evaluation; it need not be a
finished game. A saved “root” in a position bank is a recorded decision context.
See [search value interfaces][search-boundary] and [position banks][screening-bank].

### Rollout and continuation policy

A rollout simulates further play using configured action-selection policies.
The root player's rollout policy and the opponent's rollout policy control
different players and are separately configured. The opponent model used to
update belief has another role. Name the role when discussing a policy change:
changing a simulated continuation can differ from changing the actual player's
direct action selection. In experiment operations, “continuation” can also mean
extending a stopped gameplay trial; qualify that use as **gameplay continuation**.
See [policy roles][architecture] and [gameplay continuation][workflow-continuation].

### Visits, backups, and tree reuse

A visit counts search work on a tree node or action. A backup carries a
simulation's settled value back into tree statistics. These adaptive statistics
are not independent completed games. Tree reuse retains search work across
decisions; production reuse remains disabled until retained visits are justified
under the current information-state distribution.
See [search estimate limits][screening-bank] and [reuse boundaries][architecture].

### Value, payoff, and settlement

“Value” needs a qualifier identifying where the number comes from and whose
perspective it uses:

| Term | What the number means |
| --- | --- |
| Terminal payoff | The result for the specified player after the game in that world has ended. A sampled world's terminal payoff is still hypothetical. |
| Information-state evaluation | An estimate computed from legitimately available player information. |
| Sampled-world evaluation | An estimate using a complete hypothetical world through the trusted evaluation route. |
| Bounded-rollout settlement | The value assigned under the configured settlement rule after limited simulated continuation; reaching the limit does not establish a game result. |

Settlement provenance records which origin supplied a backed value. A learned
prediction or neutral nonterminal settlement is not an observed draw.
See [value routing][search-boundary], [settlement origins][search-contract], and
[architectural distinctions][architecture].

### Terminal target

A training or evaluation target obtained by forcing an admitted root action in
supported hypothetical worlds and following declared continuation policies to
terminal payoff. It estimates an outcome conditional on that belief and those
policies, rather than optimal play. Incomplete actions have no value target;
these samples do not create adaptive search visits or backups.
See [terminal root-continuation evidence][architecture].

### Features, model, fit, and checkpoint

Features are the numerical inputs supplied to an evaluator or learned model.
A model maps its inputs to scores or predictions; a fit is the learned parameter
result. A checkpoint is a saved model artifact used for inference or admission.
In this project, visible features come from player-safe information. A frozen
fit is fixed before the specified validation outcomes are accessed. Neither
loading a checkpoint nor passing a diagnostic promotes it to production.
See [learned outcome value][learned-value] and [fit ordering][workflow-iteration].

## Experiments and evidence

### Treatment, control, and intervention

The treatment is the candidate configuration being evaluated; the control is
the declared comparison configuration. The intervention is what changes between
them. An incumbent is the currently designated reference policy, not a timeless
synonym for a particular budget or model. Identify the exact learner, target,
shared components, and changed role when explaining a learning comparison.
See [explicit comparison plans][screening-sequential] and [deployment links][workflow-transfer].

### Provenance, manifest, and frozen source

Provenance records where evidence came from: its exact MTGallium commit, Argentum
revision, material configuration, and research-run identity. A manifest records
an artifact's identity and registered contents for verification. A frozen source
or build fixes what ran; a later commit does not become the producer of older
evidence. Verification establishes the recorded identity and integrity, not the
scientific conclusion by itself.
See [source and evidence authority][architecture-authority] and [retained builds][workflow-build].

### Canonical replay and safe trajectory

A canonical replay is the private reconstruction record containing referee
state. A safe trajectory or inspection is a derived player-facing record with
narrower authority. It cannot replace the canonical record or reveal sampled
hidden truth as player knowledge. Player-safe content still requires separate
public-artifact review before publication.
See [evidence boundaries][architecture-evidence] and [publication limits][overview].

### Position bank

A collection of selected decision contexts reconstructed from authenticated
games for later diagnostics. It keeps source choices, candidate coverage,
selection, exclusions, and reconstruction refusals explicit. A bank allows
repeated comparisons at saved positions; agreement with its recorded moves does
not establish correct play or deployed strength.
See [bank and screening][screening-bank].

### Population and seed group

A population is the exact collection a measurement describes: for example,
selected roots, executed games, or inspected pairs. Name its unit and inclusion
rules. In the campaign workflow, a seed group ties together data sharing a
library seed, deck, and card pool across seats and source runs. More positions
from one group do not create more independent groups.
See [campaign population usage][workflow-population] and [coverage limits][architecture].

### Paired gameplay

A comparison runs two games with the candidate and reference swapping seats
under the declared seed schedule. In the sequential protocol, one observation
is a complete valid independent-seed pair's mean game score; a draw scores half
a point. Two games in a pair are not two independent pair observations. Invalid
pairs do not become scores.
See [sequential gameplay][screening-sequential] and [practical acceptance][architecture-acceptance].

### Development, validation, and held-out data

Development data guide fitting and methodological choices. Validation data
evaluate a specified frozen choice. “Held-out” must say what it was held out
from: this fit, model selection, or the wider campaign. Reusing or inspecting
validation affects what later claims it can support. An empty overlap record
does not prove the data were untouched.
See [fit ordering][workflow-iteration] and [campaign population usage][workflow-population].

### Confidence sequence

An uncertainty interval designed to remain valid across repeated progress
checks under a declared sequential model and error allocation. MTGallium's
protocol uses complete pair observations and frozen betting mixtures. This
guarantee is conditional on its assumptions; it does not automatically cover
choosing among many candidate treatments or inspecting selected subsets.
See [practical acceptance][architecture-acceptance] and [continuation limits][workflow-continuation].

### Superiority, non-inferiority, and equivalence

These are different declared comparison objectives. Superiority seeks evidence
that the candidate's mean score exceeds parity. Non-inferiority (NI) allows a
predeclared shortfall; equivalence requires both sides of a predeclared tolerance
around parity. For example, the documented two-percentage-point tolerance uses
0.48 and 0.52. Accepting non-inferiority does not establish superiority, and an
inconclusive result establishes none of these objectives.
See [practical acceptance][architecture-acceptance] and [gameplay plans][screening-sequential].

### Stopping prefix, overshoot, and futility

The stopping prefix is the ordered observations through the first stop selected
by the declared rule. Overshoot is already executed work beyond that prefix,
such as games finished by concurrent workers. Planned, executed, inspected,
overshoot, and unexecuted populations stay separate. Futility means the declared
boundary cannot be reached within the remaining cap; it is an inconclusive stop,
not evidence of parity. A continuation retains its parent's original result.
See [sequential stopping][screening-sequential] and [gameplay continuation][workflow-continuation].

### Wall time, CPU time, and decision cost

Wall time is elapsed clock time. CPU time counts processor time used by the
measured process. Decision cost measures the declared action-selection work;
state which preparation and selection stages it includes. Worker component
times can overlap and cannot simply be added to wall time. Both players share
a game's duration, so game time alone cannot attribute a speedup to one policy.
See [cost interpretation][workflow-cost] and [game length and runtime][workflow-runtime].

## Maintaining the glossary

Agents consult and maintain this file under [the shared terminology instructions](../AGENTS.md#shared-terminology).
Add an entry when a term is important to following the work, is likely to recur,
or has a misleading everyday meaning. Ordinary words and one-off implementation
names usually need only an explanation where they appear.

For tools and subsystems, include their purpose, practical entrypoint or owning
module, and the limits needed to use them correctly. State availability when a
tool is separately installed or exists only on a development branch. For a source
outside this checkout, name its exact revision and document path, or its installed
command/skill; do not create a broken link or publish a private machine path.
Replace branch-only references with relative links when the source is integrated.

Before adding an entry, search for an existing concept and its aliases. Extend
that entry when the meaning is the same; cross-link related concepts when it is
different. Expand acronyms and qualify overloaded terms such as “value,”
“admission,” and “continuation.” Keep headings and links stable; if a rename is
necessary, preserve the old anchor or update its referring links.

Use this small format; the alias and example lines are optional:

```markdown
### Term

Also called: established alias or expanded acronym.

Explain the meaning in one or two plain-language sentences. State the distinction
or limit a reader needs to avoid misunderstanding it. Add a short example if useful.
See [the owning public document or source](relative/path).
```

Put the entry in the closest topic and update the topic index if needed. Verify
its source and links. Preserve existing semantic distinctions and historical
versions; a glossary edit is not a way to resolve an unsettled research choice.
Follow the existing owner-review rule for consequential ambiguity. Routine
source-backed additions and clarifications can proceed autonomously during
authorized editing work. For read-only tasks, report the proposed entry without
changing files. Keep private paths, evidence, and current campaign results out
of this public reference.

[overview]: ../README.md
[agent-invariants]: ../AGENTS.md#research-critical-invariants
[architecture]: architecture.md
[architecture-information]: architecture.md#information-and-action-boundaries
[architecture-evidence]: architecture.md#evidence-and-dependencies
[architecture-authority]: architecture.md#source-and-evidence-authority
[architecture-acceptance]: architecture.md#practical-strength-acceptance
[policy-contract]: ../agent/infoset-core/src/main/kotlin/org/mtgallium/agent/infoset/core/PolicyContract.kt
[knowledge-contract]: ../agent/infoset-core/src/main/kotlin/org/mtgallium/agent/infoset/core/KnowledgeState.kt
[search-boundary]: ../agent/infoset-core/src/main/kotlin/org/mtgallium/agent/infoset/core/SearchBoundary.kt
[search-contract]: ../agent/infoset-core/src/main/kotlin/org/mtgallium/agent/infoset/core/InformationSetSearchContract.kt
[action-profile]: ../agent/infoset-core/src/main/kotlin/org/mtgallium/agent/infoset/core/SearchActionSpaceProfile.kt
[learned-value]: learned-outcome-value.md
[screening-bank]: real-game-screening.md#bank-and-screening
[screening-sequential]: real-game-screening.md#sequential-gameplay
[workflow-build]: research-workflow.md#build-once-and-execute-the-retained-runtime
[workflow-iteration]: research-workflow.md#one-bounded-learning-iteration
[workflow-population]: research-workflow.md#campaign-population-usage
[workflow-transfer]: research-workflow.md#link-saved-position-results-to-deployment-evidence
[workflow-continuation]: research-workflow.md#optional-continuation-of-stopped-gameplay
[workflow-cost]: research-workflow.md#cost-interpretation
[workflow-runtime]: research-workflow.md#game-length-and-runtime-inspection
[research-cli]: ../evaluation/search-teacher/src/main/kotlin/org/mtgallium/evaluation/searchteacher/cli/SearchTeacherCli.kt
[research-run-source]: ../agent/research-run/src/main/kotlin/org/mtgallium/research/run/ResearchRun.kt
