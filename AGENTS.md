# MTGallium coding-agent guide

Read [`docs/architecture.md`](docs/architecture.md) before changing a semantic
boundary. The current source, focused tests, and this guidance are the active
technical authority; historical research evidence is held separately.

## Semantic authority

Research meaning outranks generic cleanup. Stop for explicit owner review when
two reasonable choices would materially change an experiment, representation,
result, persistent artifact, or subsystem meaning. Narrow implementation is
acceptable when its limits are explicit and cannot invalidate the objective.

## Invariants

- Policy-facing code receives only information legitimately available to the
  acting player; full engine state stays inside the Argentum adapter.
- Observations do not replace represented history and exact remembered facts.
- Determinizations satisfy player knowledge and are hypothetical worlds, not
  authoritative hidden truth.
- A transition stops at the next genuine player decision.
- Legal, proposed, admitted, and accepted actions are distinct; semantic
  identity, display text, payload, routing, and legality are distinct concerns.
- Software failures are not game outcomes or strategic values.
- Canonical replay is private; safe inspection is a derived interpretation.

## Working and verification

Preserve source provenance for an experimental population. Use focused tests
first; `just check` is the normal technical lane. Passing tests do not alone
establish a research conclusion.

Treat elapsed time and agent effort as operational evidence. Distinguish
necessary computation from repeated setup, broad verification, and avoidable
reconstruction. Raise effort for semantic ambiguity or real difficulty, not
only task size.

Private evidence belongs outside the source checkout. When
`MTGALLIUM_PUBLIC_SOURCE=1`, producers require
`MTGALLIUM_PRIVATE_EVIDENCE_ROOT` outside the checkout and refuse an in-tree
destination.
