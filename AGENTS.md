# MTGallium

Read `AGENTS.local.md` when present for private project preferences, unless the
same text is already loaded from another instruction file.

Preserve unrelated edits, private research data, and the Argentum pin. Ask the
owner only about unresolved choices that change the research objective, game
model, or interpretation of retained results.

## Read for the task

Before reading a large Kotlin, Python or JavaScript file, run `tools/outline FILE`; `tools/outline --map --name TEXT` finds the file that declares a type.

| Task | Guidance |
| --- | --- |
| Implement or review code | [Contributing](CONTRIBUTING.md); [architecture](docs/architecture.md) for affected boundaries |
| Change information, actions, simulation or value meaning | [Behavior changes](CONTRIBUTING.md#behavior-changes) |
| Play live games, run examples or Kotlin experiments | [Research quick start](docs/research-workbench.md) |
| Configure games or native policies; fit, encode or read files | [Games and CLI reference](docs/research-cli.md) |
| Ladder evaluation, cost measurement, game corpora, remote runs | [Batch research runs](docs/research-runs.md) |
| Train or export neural policies | [Neural policy training](docs/neural-policy.md) |

Results live in run folders and [ladder rows](docs/research-runs.md#policy-ladder).
What they establish lives in `FINDINGS.md` at the private evidence root, which
only reviews change. Do not create other work notes, briefs, or handoffs.
