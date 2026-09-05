---
name: mtgallium-semantic-change
description: Implement or review MTGallium changes to information, action, simulation, value, evidence contracts, or the Argentum pin. Use when behavior or research meaning can change; not for mechanical edits preserving those contracts.
---

# MTGallium semantic change

Preserve the research-critical invariants and owner-decision boundary in the
current root `AGENTS.md`. Use `docs/architecture.md` for the affected boundary;
reuse those instructions when already available and current in this task.

Establish the intended behavior and the current source type and call path that
own it. Distinguish source facts, inference, assumptions, and unknowns. Identify
a reachable witness that separates the intended behavior from a plausible wrong
implementation. These are correctness requirements, not a required report outline.

Use `source-tracer` for an unresolved, bounded ownership or call-path question
when delegation adds value. Trace locally when the path is short or already
established. Give the tracer only the question and relevant context; continue
independent work while it runs. Its report is source context, not owner approval.

Make the smallest semantically adequate change and run the focused regression.
Broaden public verification as required by the change. For changes to these
contracts, obtain an independent `semantic-reviewer` review of the objective,
actual diff, and relevant verification, or a separate local `/review` using the
repository Code Review Rules. Repair material findings before committing the
treatment. If independent review is unavailable, report that specific limitation;
continue implementation and verification without claiming the review happened.

Ask the owner only about an unresolved consequential choice under `AGENTS.md`.
Apply established decisions without asking again; continue unaffected work while
awaiting an answer. Report changes to evidence identity, historical compatibility,
or interpretation, and limits that affect the result.

For substantial compute, commit treatment source first and retain private
evidence under its actual execution identity. Use the evidence-interpretation
skill when interpreting retained results. Review agents do not choose research
meaning or authorize experiments.
