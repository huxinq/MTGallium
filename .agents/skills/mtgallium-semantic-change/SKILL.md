---
name: mtgallium-semantic-change
description: Use for MTGallium changes that may affect policy-visible information, represented knowledge or history, semantic choice identity or rebinding, action generation/proposal/admission/acceptance/legality, simulation stopping, settlement or value meaning, replay or evidence admission, the action profile, the Argentum pin, or another boundary where plausible implementations could pass tests while meaning different things.
---

# MTGallium semantic change

Keep the owner and current public source authoritative for research meaning.
Use this procedure before implementation and again when reporting the result:

1. Read the current root `AGENTS.md` and `docs/architecture.md`.
2. State the semantic claim, defect, or capability being addressed.
3. Have Codex use the repository `source-tracer` agent to find the narrowest
   current-source type and call path that owns that meaning. Ask, for example,
   `Have source-tracer trace <boundary> before implementation.`
4. Separate established source facts, reasonable inference, discussion
   assumptions, and unknown implementation facts.
5. Identify a reachable witness that distinguishes the intended behavior from
   a nearby plausible-but-wrong implementation.
6. Surface an owner decision only when two reasonable choices would materially
   change an experiment, representation, result, persistent artifact,
   compatibility boundary, or subsystem meaning.
7. Make the smallest semantically adequate change. Run the focused regression
   first, then broader public verification only when proportionate.
8. State whether the change affects evidence identity, historical
   compatibility, or interpretation.
9. State material behavior deliberately left unchanged.

After focused tests, ask Codex to have `semantic-reviewer` independently review
the stated objective and actual diff, or run a separate local `/review` using
the repository Code Review Rules. Repair material findings before committing
the treatment source.

The intended composition is:

```text
semantic objective -> source-tracer -> implementation -> focused tests
-> semantic-reviewer or local /review -> repair -> treatment commit
-> existing durable/private evidence -> evidence-auditor when installed
-> Advisor/owner interpretation
```

The agents and skills support this flow; they do not decide research meaning,
create a new experiment lifecycle, or make generated evidence authoritative.
