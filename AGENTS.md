# MTGallium coding-agent guide

This public repository is the authority for new first-party MTGallium source.
Do not use the pre-publication private repository for new development; it
remains historical provenance. Read [`docs/architecture.md`](docs/architecture.md)
before changing a semantic boundary.

## Semantic authority

Research meaning outranks generic cleanup. Stop for owner review when two
reasonable choices would materially change an experiment, representation,
result, persistent artifact, or subsystem meaning. Narrow or disposable
implementation is acceptable when its limits are explicit and cannot
invalidate the objective. Do not add generalized lifecycle machinery merely
for completeness.

## Research-critical invariants

- Policy-facing code receives only information legitimately available to the
  acting player; full engine state stays inside the Argentum adapter.
- Observations do not replace represented history, exact remembered knowledge,
  or the distinction between fact and uncertain inference.
- Determinizations satisfy represented knowledge and are hypotheses, never
  authoritative hidden truth.
- A simulation stops at a terminal state, the next genuine player decision, or
  a typed non-game failure. Responses, targets, ordering, and mulligans are
  never silently consumed.
- Legal, proposed, admitted, and accepted actions are distinct. Semantic
  identity, display text, payload, routing, and current legality are distinct
  concerns.
- Rejection, timeout, unsupported representation, and stopped execution are
  not strategic outcomes or values.
- Terminal payoff, information-state evaluation, sampled-world evaluation, and
  bounded-rollout settlement have different meanings.
- Canonical replay is private; safe trajectories and inspections have narrower
  derived authority.
- Production tree reuse remains disabled until current source justifies
  reweighting retained visits to the current information-state distribution.

Prefer types, APIs, focused tests, and local comments over process ceremony.

## Source and evidence workflow

Treat substantial work as:

```text
public source change → committed treatment SHA → private durable evidence
```

An experiment retains the exact MTGallium source SHA, Argentum revision,
configuration, and evidence identity from which it ran. Source may advance
while durable compute executes; completed evidence must not be relabeled as
originating from a later commit.

Code that generates private evidence may be public. Generated evidence is not
thereby public and belongs outside the checkout. When
`MTGALLIUM_PUBLIC_SOURCE=1`, producers require
`MTGALLIUM_PRIVATE_EVIDENCE_ROOT` outside the checkout and refuse in-tree
destinations. Do not copy historical private evidence into public source to
satisfy a test or tool.

## Public and private verification

Use focused tests first; `just check` is the normal self-contained public
technical lane. Public CI exercises explicitly classified public capabilities;
tests whose meaning intrinsically needs private historical evidence remain
separate. Generic invariants should prefer synthetic or public-safe fixtures.

Do not silently skip a test because a private file is missing, fabricate
historical evidence, or reintroduce private resources into the checkout to make
CI pass. Passing tests are technical evidence, not a research conclusion.

## Working defaults

- Commit treatment source before substantial compute so evidence has an
  unambiguous source identity.
- Preserve unrelated changes. Do not casually rewrite history, force-push
  owner work, or change the Argentum pin.
- Use a branch or worktree for actual concurrency or risk, not automatically.
- Generalize only when a capability is recurring and material; prefer
  subtraction when machinery no longer earns its complexity.

## Time and compute

Treat elapsed time and agent effort as operational evidence. Distinguish
irreducible compute from repository reacquisition, repeated setup, broad
verification, orchestration, serialization, and avoidable reasoning. Use the
least intensive model or agent effort unlikely to reduce decision-relevant
quality; raise it for semantic ambiguity or genuine difficulty.

For substantial compute, use the repository-local durable-run facility after
cheap preflight, then inspect retained outputs after completion rather than
rerunning. Durable execution is an execution-layer concern; keep its private
destinations, notifications, and credentials out of public source doctrine.
