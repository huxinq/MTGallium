# Developing the research tools

The human workbench, native command adapters and scientific runners have separate
owners. Editable design text explains intent; typed native plans control execution.
The research-run APIs remain the authority for retained identity and artifact bytes.

```text
tools/mtgallium-research
    → Python command / preparation / execution modules
    → ResearchWorkbenchKt (typed plans, build and input verification)
    → SearchTeacherEvaluationKt (stable CLI entry point)
    → registered command handler
    → scientific runner and research-run artifact APIs
```

## Python workbench

All modules below live in `tools/research_workspace/`.

| Owner | Responsibility |
| --- | --- |
| `cli.py`, `arguments.py`, `commands.py`, `presentation.py` | Entry point, argument parsing, dispatch and human output |
| `capabilities.py` | Immutable capability records: purpose, native route and preparation requirements |
| `drafts.py` | Editable experiment inputs, forks and exact design/plan/deck differences |
| `preparation.py` | Readiness checks, frozen input capture and technical preflight |
| `requests.py` | Frozen request verification and reconstruction of allowed native arguments |
| `execution.py` | Bounded foreground or durable execution and execution receipts |
| `monitoring.py` | Operational status, logs and failure records |
| `inspection.py`, `evidence.py` | Verified inventory, exact field retrieval and review packets |
| `storage.py`, `source.py`, `native.py` | File/path primitives, source capture and frozen JVM invocation |

Import a function from its owner. Preparation may assemble a request, but launch
must verify it through `requests.py`; stored argv is never an independent source
of execution authority. Operational status cannot substitute for a native result.
The Python catalog works without a build. Its preparation guidance must agree with
the native workbench's independent launch checks.

## Kotlin commands

The Kotlin sources live in
`evaluation/search-teacher/src/main/kotlin/org/mtgallium/evaluation/searchteacher/`.
`cli/SearchTeacherCommands.kt` collects the registrations in `cli/commands/`.
The same registrations supply dispatch and the alphabetically ordered suite
catalog. Each command has a named handler in its domain group; adding a suite
does not require editing the JVM entry point or a second list of suite names.

`SearchTeacherCommandContext` holds invocation-scoped, lazy dependencies and the
shared typed-plan reader and diagnostic output checks. A registration declares
its existing startup contract explicitly:

- `HANDLER`: the handler or runner owns the relevant checks. Historical inspection
  can authenticate retained inputs without treating current source as their producer.
- `CURRENT_SOURCE`: capture current provenance and verify the engine gitlink before
  entering the handler. Deck and registry construction remain lazy.
- `LEGACY_ARENA`: perform current-source checks, then the established profile/arena
  initialization and run header used by the older arena commands.

Keep native plan serialization and launch verification in `ResearchWorkbench.kt`.
Command handlers adapt arguments and report results; scientific admission, gameplay,
fitting and statistical decisions belong to their runners.

## Shared terminal workflow code

| Owner | Responsibility |
| --- | --- |
| `TerminalResearchInputs.kt` | Authenticate retained screens and production controls |
| `TerminalResearchStages.kt` | Terminal collection/reuse, technical pilot selection and measured work |
| `TerminalTargetComparison.kt` | First-menu model argmax, regret rows and equal-group comparisons |
| `ResearchWorkflowArtifacts.kt` | Finalize registered workflow artifacts |
| `TerminalKernelStudy.kt` | Explicit study stages, prospective gate and study contracts |
| `TerminalTargetSensitivity.kt` | Declared target variants and ordered prefix comparison grid |
| `ResearchTransferAudit.kt` | Exact saved-position/gameplay treatment links and their limits |

Shared helpers must not eagerly open values that a runner has not yet authorized.
The study reserves campaign use before decoding model or target values and writes
and verifies the frozen fit before validation access. Reused stages retain their
producer identities and zero *new* scientific work. Sensitivity computes fixed
model choices once per root; every declared variant and nested sample prefix still
uses its own target values and the original equal-group arithmetic.

Preserve serialized names, field order, defaults, protocol/material keys, artifact
paths and stage names when moving implementation. Keep first-menu tie selection and
duplicate-row refusal explicit. The public regressions use synthetic populations;
historical compatibility checks must not import private evidence into source.

Run `just research-tools-check` for Python changes and the affected
`:evaluation:search-teacher:publicSourceTest --tests '*ClassName'` tests for Kotlin
changes, followed by `just check`. The [workflow contract](research-workflow.md)
defines the scientific meanings, and the [workbench guide](research-workbench.md)
describes the user interface.
