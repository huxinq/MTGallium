# Contributing to MTGallium

Use the current public checkout for source development. Preserve unrelated work,
private research data and the Argentum pin. Trace a change through its callers;
update the affected tests, examples and documentation. Prefer names from MTG and
ordinary research; the [glossary](docs/glossary.md) and [API map](docs/terminology.md)
record existing meanings.

## Behavior changes

For changes to player information, actions, simulation, value interpretation or
the Argentum revision, read the affected architecture and trace the owning code.
Add a small test that distinguishes the intended behavior from a plausible error.
Where it applies, add a same-seed test showing unrelated decisions are unchanged.
The owner or a reviewing agent reviews the diff and relevant checks. Routine
renames and prose edits use ordinary checks.

Feature retirement can remove obsolete tests and interfaces. Resolve only
unsettled changes to the research objective, game model or interpretation with
the task owner.

## Verification

Run focused tests while developing, then `just check` for source changes. For
prose or agent configuration, check syntax, links and instruction consistency;
exercise changed scripts. Reuse verification whose source and inputs still
apply, and report checks that could not run. Public tests use synthetic or
public fixtures; keep tests requiring actual private evidence separate.

For memory or concurrency defects, trace creation, copying, retention and
release across the actual consumers. Establish a representative failing case
before adding variants. A scripted edit should check its expected matches.

Describe the resulting behavior and relevant checks in the pull request. Write
commit subjects for the resulting state; explain non-obvious mathematical or
compatibility changes in the body.

## Agent guidance

Public guidance describes project behavior, development and review. Keep personal
model/provider/effort choices and installed machine paths in private project
settings. Cross-project personal preferences belong in the user's general agent
instructions. `.codex/config.toml` is local and ignored; do not commit it or copy
its preferences into public instructions.

## Public submissions

Keep private replays, referee state, research inputs and credentials out of
public issues and pull requests. Use independently reviewable public fixtures.

Contributions are submitted under the project MIT License (inbound=outbound).
Use a Developer Certificate of Origin sign-off on every commit, for example
`git commit -s`. By contributing, you certify that you have the right to submit
the change under those terms. No CLA is required. Do not submit
material whose redistribution terms are unclear.
