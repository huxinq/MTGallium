# MTGallium architecture

MTGallium gives each policy only the information its player could legitimately
know, plans over the possible hidden states (information-set search) on a pinned
Argentum engine, and provides Mono-Red value models and research tools.

The seat agent is the development line. The old view, casting kernel, belief
samplers and IS-MCTS are frozen for `horizon16`.

## Primary dataflow

```text
Argentum state → trusted adapter → captured player information + admitted menu
                                      ↓
                           direct selection or search
                                      ↓
                     semantic choice → live rebinding → engine acceptance
                                      ↓
                  represented history / belief update → next decision

Argentum browser messages → SeatHost → SeatAgent → browser actions
```

The adapter projects engine state into player information and captures it with
the available menu. It rebinds the chosen semantic action to the current engine
objects before asking the engine to apply it. Simulation advances to the next
player decision; see [simulation](architecture/information-and-decisions.md#live-selection).

## Module responsibilities

The planning module uses `org.mtgallium.agent.infoset.planning`. Semantics uses
`org.mtgallium.agent.infoset.core`, so a type’s package identifies its layer.

| Location | Responsibility |
| --- | --- |
| `agent/infoset-semantics` | Player information, history, semantic actions, and the contracts policies see. |
| `agent/infoset-planning` | Planning algorithms and root selection. |
| `agent/infoset-argentum` | Trusted Argentum projections, engine-backed worlds, and transitions. |
| `agent/value-models` | Frozen V2 material evaluator. |
| `agent/argentum-policy` | Argentum policy lifecycle, defaults, and search/leaf composition. |
| `research/workbench` | Direct games, Python bridge, native policies and the provider hook for other builds, feature/kernel routines, and CLI entry points. |
| `integration/argentum-policy` | Argentum application integration. |
| `evaluation/argentum` | Engine-policy evaluation and probes. |
| `tools/research_workspace` | Python session and game handles. |

`checkArchitecture` resolves production compile and runtime classpaths
transitively. Semantics, planning and models stay isolated from the engine and
application layers; agent and integration modules cannot depend on evaluation.

## Detailed contracts

- [Information and decisions](architecture/information-and-decisions.md): player
  views, live selection, action identity across worlds, and history event order.
- [Policies and beliefs](architecture/policies-and-beliefs.md): search
  composition, belief maintenance and snapshots, and continuations.
- [Value models](value-models.md): value features, linear evaluators and search
  leaves.
