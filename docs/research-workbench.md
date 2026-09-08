# Research from the command line

`tools/mtgallium-research` connects experiment design, typed plans, preflight,
durable execution, retrieval, features and review. Researchers work with editable
JSON and short commands; the existing Kotlin code remains the authority for
scientific settings, admission, representations, populations and evidence.

The [glossary](glossary.md#research-workbench) explains the workbench and related
research terminology.

```text
question → draft → doctor / effective plan → frozen attempt → preflight → launch
              ↑                                                           ↓
           fork / diff ← review packet / extraction ← verify ← status / diagnose
```

The three durable records have different authority: the **draft** expresses
intent, the **attempt** records exactly what was requested, and the **native
manifest** authenticates the producer's retained artifacts.

## Agent use

The workbench is the primary experiment interface for agents as well as human
researchers. Follow the [agent interface policy](../AGENTS.md#primary-experiment-interface):
use supported commands and structured `--json` output, reuse retained work, and
record a concrete capability gap when a documented specialized route is needed.
The command sequences below apply to both users. Source development and tests
keep their normal tools; using the workbench does not authorize new experiments
or turn a read-only task into permission to create records.

## Start here

Use Python 3.11+, JDK 21 and the repository's ordinary build prerequisites.
Durable launch requires a Linux user systemd manager. There is no database,
background workbench service or Python dependency to install.

Run from the intended worktree. This optional shell function shortens the name
while preserving arguments containing spaces:

```bash
research() { python3 tools/mtgallium-research "$@"; }
export MTGALLIUM_PUBLIC_SOURCE=1
export MTGALLIUM_PRIVATE_EVIDENCE_ROOT=/absolute/private/evidence
research catalog
research --help
```

Generated drafts, builds, attempts and exports belong below
`$MTGALLIUM_PRIVATE_EVIDENCE_ROOT/search-teacher/work`. Output guards reject source
checkouts and symlink routes. Canonical evidence and derivatives remain private.
Every public command accepts `--json` after its name for scripting.

Commit the treatment and build it once:

```bash
research build --output /absolute/private/evidence/search-teacher/work/build-001
export MTGALLIUM_RESEARCH_BUILD=/absolute/private/evidence/search-teacher/work/build-001
```

This uses the existing forced-build authority and retains the source/Argentum
revisions, command/log and ordered runtime. Keep the execution worktree unchanged
while its producers run; use another worktree for concurrent source changes. A
new source treatment needs a new committed build. Historical attempts keep their
original identities.

## Design an experiment

Choose a capability with `catalog`, and start from an explicit native plan. The
public [sequential example](../examples/search-teacher-sequential.json) supplies
a starting shape; its scientific settings are not a recommendation for a new
research question:

```bash
research new rollout-comparison --kind sequential \
  --plan examples/search-teacher-sequential.json --deck /absolute/private/deck.json \
  --question 'Does this declared continuation change improve the fixed policy?'
```

The new private directory contains `experiment.json`, `plan.json` and `deck.json`.
Edit these copies. The original inputs are preserved.

| Draft area | Meaning |
| --- | --- |
| `design` | Question, actual learner/target, control, shared components, intervention, population, primary measure, decision rule, allowed claims, limits and data use |
| `inputs` | Typed plan, deck and frozen build paths |
| `execution` | JVM resource arguments, worker setting where applicable, explicit wall-time cap and rehearsal settings |
| `lineage` | Parent reference and reason recorded by `fork`; design ancestry only |

Comparisons require explicit design fields; use a reasoned “not applicable” where
appropriate. Banks, features and campaign snapshots require only the question,
population, allowed claims, limits and data use. No seed, scientific sample cap,
effect margin, gate or objective is invented by the workbench.

```bash
research schema sequential
research schema sequential --type SearchTeacherCalibrationPlan
research plan /absolute/private/evidence/search-teacher/work/rollout-comparison
research doctor /absolute/private/evidence/search-teacher/work/rollout-comparison
```

`schema` shows actual serializer fields, types, enum values, optionality and
nullability, with references for recursive/generic structures. It is a type guide,
not a complete JSON Schema or proof that constructor constraints pass. `plan`
uses the actual Kotlin decoder and displays effective defaults beside the design.
Only that typed plan controls scientific settings. `doctor` lists missing design,
input, source and configuration prerequisites without collecting samples. It
checks deck readability and the actual native build attestation, using a transient
private reference file that is removed after the check.

`execution.timeoutSeconds` is required and bounds the launched workload **and
final verification together**. A separate rehearsal invocation has that same cap;
it is not a combined scientific cost budget. Native scientific caps remain in the
plan. `execution.threads` applies to gameplay and position screen/features.
Composed studies own `workers` in their typed plans; the generic thread setting
does not override them. JVM processor count and heap are separate resources.

## Freeze, preflight and launch once

```bash
research preflight /absolute/private/evidence/search-teacher/work/rollout-comparison
# Use the exact attempt path returned by preflight:
research launch /absolute/private/evidence/search-teacher/work/rollout-comparison/attempts/0001
```

`preflight DRAFT` creates a fresh numbered attempt. `freeze DRAFT` lets you inspect
its request before rehearsal. `preflight ATTEMPT` uses that attempt; a completed
unchanged rehearsal is reverified by the native authority. Failed or partial
rehearsals require inspection and a fresh attempt. Launch never silently runs
missing smoke work.

```text
attempts/0001/
  experiment.json               frozen human design
  plan.json, deck.json           exact copied inputs
  effective-plan.json            native decoded settings
  build-reference.json           build identity and manifest hash
  request.json                   input hashes, source, argv, output and gate
  preflight.json, preflight/     for rehearsal workloads
  submitted.json, started.json
  status.json, run.log, receipt.json
  output/                       native artifacts and research manifest
  verification.json             completed native byte verification
```

The launcher checks frozen input hashes and reconstructs the command before
submission and again in the worker. Native code verifies source/build readiness
and the required gate. Rehearsal verification and primary dispatch run in the
**same JVM**, deriving the primary plan/deck/threads/output solely from the bound
profile; conflicting overrides cannot be supplied.

Default launch returns after starting a uniquely named user systemd service.
`submitted.json` retains its unit and launcher. The wrapper records the process
result and log; an outer service deadline also covers abnormal wrapper failures.
Persistence across logout depends on the host's existing user-manager/linger
configuration; the tool does not change it. `launch --foreground` runs bounded
interactive work with the same gates and deadline.

Submitting an attempt twice refuses. There is no automatic retry, resume,
deadline extension or statistical continuation. A killed process may leave a
stale status file; `status` includes current service observations and does not
infer completion from a stored RUNNING flag.

### Select the appropriate gate

| Kind | Gate | Work performed by existing native authority |
| --- | --- | --- |
| `calibration`, `sequential` | Bound gameplay rehearsal | Fixed/sequential paired games |
| `position-screen` | Bound SEARCH/ACTION_CONDITIONAL rehearsal | Matched saved-position search |
| `position-features` | Authenticated bank/reconstruction | Existing `FEATURES` representation, no new search targets |
| `position-bank` | Authenticated source games | Bank derivation/reconstruction |
| `terminal-kernel-study` | Embedded development pilot | Targets, frozen fit, then validation |
| `terminal-target-sensitivity` | Embedded variant pilots | Changed conditional targets with fixed models |
| `direct-attack-kernel-screen` | Native model/bank/scope/population checks | Declared choices and conditional targets |
| `terminal-prediction-diagnostic` | Authenticated development evidence | Reused-target diagnosis without fitting/collection |
| `research-transfer-audit` | Authenticated model/role/gameplay links | Retained-evidence comparison |
| `campaign-data-snapshot` | Native registry validation | Snapshot of recorded population use |

For non-rehearsal kinds, workbench `preflight` checks the build and structural
plan. Embedded pilots and input admission remain inside launch; the interface
does not present those checks as a gameplay rehearsal pass.

`catalog --all` lists every native suite. Specialized commands retain their own
routes where output or continuation semantics differ. `native -- ...` prints an
argument array/environment and does not execute it. `campaign-data-use` appends
to its explicit registry. Continuation retains its original parent and protocol;
it is not an ordinary fresh output. See [the scientific workflow contract](research-workflow.md).

## Inspect execution and diagnose failures

```bash
research status /absolute/private/evidence/search-teacher/work/rollout-comparison
research logs /absolute/private/evidence/search-teacher/work/rollout-comparison/attempts/0001
research diagnose /absolute/private/evidence/search-teacher/work/rollout-comparison/attempts/0001
research verify /absolute/private/evidence/search-teacher/work/rollout-comparison/attempts/0001
research inspect /absolute/private/evidence/search-teacher/work/rollout-comparison/attempts/0001
research describe /absolute/private/evidence/search-teacher/work/rollout-comparison/attempts/0001 report.json
```

`status DRAFT` lists attempts. Attempt views separate process state, live service
observations, artifact verification and research interpretation. `diagnose`
checks request/source bindings and shows bounded operational failure files and
log tails; it does not infer a scientific cause. `logs --preflight` reads the
rehearsal log. No inspection command reruns scientific work.

Verification/inspection also accept a historical native directory with `--build`.
Use `--identity` to require an original research-run identity. `verify` calls
`ResearchRunArtifacts.loadAndVerify`; `inspect` shows authenticated artifact names,
hashes and sizes; `describe` shows registered JSON keys without their values.
Original manifest fields are preserved, including omitted defaults. Missing
historical source fields remain unknown.

COMPLETE means registered bytes were retained. It may accompany a failed
rehearsal, invalid result or inconclusive trial. Byte verification does not replace
specialized bank/model/checkpoint/population/gameplay admission. Interpret the
appropriate native report and its declared populations before making a claim.

## Retrieve evidence and features

```bash
research find rollout
research find SOURCE_SHA --limit 20 --json
```

Discovery searches experiment/request/manifest metadata, including questions and
frozen source/build declarations. It does not open outcome reports, update an
index or rewrite evidence. Matches remain unverified. Malformed markers are
reported separately; absent declarations are not filled by inference. Traversal
is deterministic, avoids directory symlinks and bounds returned entries/errors.

To generate features, create a `position-features` experiment with the existing
`PositionBankScreenPlan` in `FEATURES` mode, then preflight/launch it. This uses
the source-owned bank, perspective, menu and feature implementation and refuses
search-mode plans. See [position-bank screening](real-game-screening.md).

To retrieve a field that already exists in an artifact:

```bash
research extract /absolute/private/evidence/search-teacher/work/screen/output report.json \
  --pointer /rows --purpose 'Development representation inspection' \
  --output /absolute/private/evidence/search-teacher/work/row-export-001
```

Choose the pointer from the actual report schema; units differ across suites.
Empty pointer selects the entire artifact. Retrieval preserves array order,
nulls, refusals and numeric tokens without filtering rows or inventing outcomes.
`value.json` and `receipt.json` retain the original run identity, manifest/artifact
hashes, pointer, extractor revision/dirty state/script hash, purpose and output
hash. Producer and extractor identity stay separate. JSON retrieval is limited
to 64 MiB; larger reports need their existing specialized streaming reader.
Fresh output directories are required, and symlink/traversal paths refuse.

Before exposing outcomes for a new use, register the appropriate explicit
`campaign-data-use` record through the native authenticated population authority.
The export purpose is a record of intent, not population registration. An empty
registry does not establish fresh validation. See [population usage](research-workflow.md#campaign-population-usage).

## Interpret and design the next experiment

```bash
research packet /absolute/private/evidence/search-teacher/work/rollout-comparison/attempts/0001 \
  --output /absolute/private/evidence/search-teacher/work/review-001
research fork /absolute/private/evidence/search-teacher/work/rollout-comparison/attempts/0001 \
  rollout-comparison-v2 --reason 'Change only the declared continuation setting'
research diff /absolute/private/evidence/search-teacher/work/rollout-comparison/attempts/0001 \
  /absolute/private/evidence/search-teacher/work/rollout-comparison-v2
```

The packet requires a completed receipt bound to the exact request, then compares
current evidence with the identity/hash verified at execution. Substituting a
different valid output refuses. It links design, effective settings and artifact
inventory; `review.json` asks for observations, interpretation, alternatives,
supported claims and the next decision. It does not create an effect estimate or
promote a model. The actual producer and current packet authoring source remain
separate.

Account for planned, executed, inspected, overshoot, refused, stopped and missing
populations through the relevant native report. Distinguish terminal outcomes,
conditional sampled targets and heuristic settlements. Existing gameplay summaries,
prediction diagnostics, sensitivity studies and transfer audits provide the
specialized readouts.

A fork of an attempt copies its frozen inputs; later old-draft edits cannot change
that ancestry. `diff` compares actual plan/design fields and deck contents/hashes,
preserving missing versus null. Differences do not prove causal isolation or semantic equivalence. Reuse a
completed study stage only through explicit native retained references in the new
plan; forking neither continues a statistical test nor resets its boundaries.

## Keep the interface durable

`ResearchWorkbench.kt` owns serializer introspection and routes into scientific
APIs. Python owns drafts, execution records, process supervision, retrieval and
presentation. There is no alternate payoff, feature, gate or population definition.
See [research tooling development](research-tooling-development.md) for module
ownership, native command registration and the shared terminal workflow helpers.

For a recurring workflow, add its actual native serializer and precise gate,
reuse its runner/verifier, and expose matching human purpose/output/worker semantics
in the Python capability table. Leave unsupported operations explicit. Add a
reachable identity/refusal regression and a small successful technical witness;
update this guide and the relevant scientific contract. Historical fixtures stay
private. Avoid adding arbitrary shell launch hooks or result-dependent defaults.

`just research-tools-check` runs synthetic Python pipeline and retrieval tests.
`just check` includes those and the native public-source adapter regressions.
Independent semantic review applies to interpretation-bearing changes. Technical
checks establish implementation behavior, not a scientific result.
