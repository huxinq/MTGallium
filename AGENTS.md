# MTGallium coding-agent guide

This public repository is the authority for new first-party MTGallium source.
Do not use the pre-publication private repository for new development; it
remains historical provenance. Read [`docs/architecture.md`](docs/architecture.md)
before changing a semantic boundary. Read other source and documentation as
the task requires; reuse relevant context already established in this task.

## Semantic authority

Research meaning outranks generic cleanup. Stop for owner review when an
unresolved choice between reasonable alternatives would materially change an
experiment, representation, result, persistent artifact, compatibility
boundary, or subsystem meaning. Apply established owner decisions without
requesting the same approval again. Routine implementation within the
established objective and research-critical invariants can proceed without
additional approval; touching a semantic boundary alone is not a reason to
stop. If a decision is needed, state the alternatives and their consequences
and continue independent work. Narrow or disposable implementation is
acceptable when its limits are explicit and cannot invalidate the objective.
Do not add generalized lifecycle machinery merely for completeness.

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

## Code Review Rules

Reviewers should flag concrete defects in these interpretation-bearing areas:

1. **Perspective safety:** policy-facing paths that expose referee state,
   sampled hidden truth, raw engine identity, or uncertain inference as
   represented fact.
2. **Action and transition semantics:** changes that merge legal, proposed,
   admitted, or accepted actions; alter semantic identity or rebinding;
   confuse payload, routing, display, or legality; or silently consume a
   genuine player decision without a reachable witness and focused regression.
3. **Evidence and outcomes:** code that counts rejection, timeout, unsupported
   representation, exclusion, stopped execution, or a heuristic settlement as
   a terminal payoff or observed game result, or detaches an artifact from its
   MTGallium revision, Argentum revision, material configuration, or
   research-run identity.

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
technical lane for source changes. For prose and agent configuration changes,
check the affected syntax, links, and instruction consistency; run executable
checks when behavior changes. After the relevant checks pass, repeat or broaden
them only for a new change, failure, or unresolved concern.
Public CI exercises explicitly classified public capabilities;
tests whose meaning intrinsically needs private historical evidence remain
separate. Generic invariants should prefer synthetic or public-safe fixtures.

Do not silently skip a test because a private file is missing, fabricate
historical evidence, or reintroduce private resources into the checkout to make
CI pass. Passing tests are technical evidence, not a research conclusion.

## Working defaults

- Complete the requested work through implementation, relevant verification,
  and repair of failures caused by the change. An initial patch is not completion.
  Use reasonable assumptions for routine reversible choices. Ask only when the
  missing answer changes the outcome materially, and continue independent work.
- Existing owner authorization carries forward. Skills and templates guide the
  work; they do not add approval gates or override explicit task instructions.
  If a local instruction blocks authorized work, identify its file and wording.
- Reuse successful verification when its source, configuration, and inputs still
  apply. Report unrelated failures and continue unaffected work without modifying
  others' changes or claiming that a blocked check passed.

- Commit treatment source before substantial compute so evidence has an
  unambiguous source identity.
- Preserve unrelated changes. Do not casually rewrite history, force-push
  owner work, or change the Argentum pin.
- Use a branch or worktree for actual concurrency or risk, not automatically.
- Generalize only when a capability is recurring and material; prefer
  subtraction when machinery no longer earns its complexity.
- For work spanning tasks, reuse a small private handoff record outside the
  checkout. Refresh it at meaningful handoffs with the objective, established
  decisions, source revision and relevant uncommitted work, last useful
  validation, and next action or unresolved decision. Recheck facts affected
  by intervening changes; a handoff record is context, not evidence authority.

Write commit messages for future readers. The subject should describe the
resulting source state or established result, not implementation activity:
prefer `Retain planner settlement provenance` or `Separate public Search
Teacher test capability`, or `Record ambiguous 8x32 strength result` over
`Implement planner evidence`, `Fix tests`, or `Update issue 0037`. Source and
capability commits describe the source state they create; result-recording
commits describe a stable, decision-relevant result. For non-obvious research,
semantic, evidence, compatibility, or architectural meaning, use a short body
explaining why the change exists, which interpretation-bearing distinction it
preserves, and any important limit or intentionally unchanged behavior. Do not
duplicate detailed experiment reports, measurements, manifests, or diary
interpretation in commit messages, and do not require a body for an obvious
narrow mechanical change.

## Time and compute

Treat elapsed time and agent effort as operational evidence. Distinguish
irreducible compute from repository reacquisition, repeated setup, broad
verification, orchestration, serialization, and avoidable reasoning. Use the
least intensive model or agent effort unlikely to reduce decision-relevant
quality; raise it for semantic ambiguity or genuine difficulty.

Delegate a bounded question when independent work can run alongside useful
local work or independent review materially improves confidence. Give each agent
its objective, relevant paths, ownership, and expected evidence. Reuse an existing
agent for related questions; avoid duplicate exploration and serial handoffs
whose answer is already established. Retain cheaper specialist models for
bounded tracing and review; use Astra for difficult synthesis or ambiguity.

When work encounters friction, briefly investigate its cause in the instructions,
tools, code, or the agent's own approach. Distinguish demonstrated causes from
suspicions. Make small reversible fixes within the objective and verify
proportionately; report larger remedies or unresolved decisions while continuing
independent work. Keep reflection proportionate to the friction.

Lead updates and final reports with the outcome, its evidence, and material limits.
Use concise prose, concrete examples, and lists only when they improve clarity.

Owner research environments may provide durable execution for substantial
compute. When available, use it after cheap preflight and inspect retained
outputs after completion rather than rerunning expensive work. Durable
execution mechanics, notifications, destinations, and credentials are private
execution-layer concerns.
