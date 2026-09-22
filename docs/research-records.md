# Research records

Use one working home per continuing question. Reuse its existing directory and
note when another agent or session takes over.

## Where things go

Private work lives outside the source checkout, under
`MTGALLIUM_PRIVATE_EVIDENCE_ROOT` or the private root supplied by local context.
Use the task's private project settings for its research index and directory
conventions. The index routes questions to their work and results. Keep an
existing question in its current location; choose a clearly named directory
under the private root for new work.

| Information | Home |
| --- | --- |
| Question, decisions, current next step, interpretation | One work note in the question's directory |
| Inputs, source snapshot or patch, settings, outputs and logs | The run's output directory, or links to their existing locations |
| Process status and recovery | The process runner's own record |
| Which questions are active and where to read their results | The existing research index |

A work note can be an existing `README.md`, result, or handoff. Keep that name.
For a new question, `README.md` is enough. If a result and handoff both exist,
continue the result for findings and retain only unfinished operational details
in the handoff. Do not create another summary for the integrating agent.

## Write once

While work is open, keep the question, consequential decisions, next action or
blocker, source state, and useful links in the work note. At completion, replace
the next action with the conclusion and remaining limits. Explain the comparison,
its population and uncertainty there, with links to the actual outputs. Do not
transcribe execution counts or status that the runner can report directly.

Record source, engine revision, inputs and material settings with results used
for research claims. A source patch can capture uncommitted work. Reuse metadata
emitted by the program rather than maintaining the same inventory in prose.

Update the research index when a question is added, closed, or moves. Keep a
short topic label and link there; conclusions and execution status belong in
the work note. Code changes, status polls, and new sessions need no index update.

Briefs, diaries, director notes and subject syntheses are not a completion
checklist. Keep existing historical entries. Add a retrospective or cross-study
synthesis when that is the requested work or there is a distinct argument to
preserve, not as another account of the latest run. Ordinary read-only questions
are answered in the conversation without creating persistent records.

## Continuation and exceptions

A separate handoff is useful only when someone must resume unfinished work and
the work note lacks the needed operational detail. Put that detail in the work
note first. An existing `engineering-progress.md` can serve this purpose; do not
also add `handoff.md`, an execution ledger and a progress summary.

For a small coding task, the diff, verification output and conversation can be
the complete record. For work spanning sessions, reuse the task's existing note.
Reviewers return findings to the owning task; they do not maintain a second
project history. Explicit requested deliverables still apply.

Keep completed run outputs and historical reports intact. Mark a superseded
interpretation at its reading entry and link the correction. Do not relocate old
artifacts, rewrite their source identity, or make per-update backup copies to
maintain this organization. Version control or a single recoverable edit backup
is sufficient for changing guidance and notes.

For process lifetime and completion, use the [durable runner](workbench/durable-runs.md).
For historical interpretation, use the [evidence skill](../.agents/skills/mtgallium-evidence-interpretation/SKILL.md).
