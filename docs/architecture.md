# MTGallium architecture

MTGallium gives each policy only the information its player could legitimately
know, plans over the possible hidden states (information-set search) on a pinned
Argentum engine, and provides Mono-Red value models and research tools.

## Primary dataflow

```text
Argentum state → trusted adapter → captured player information + admitted menu
                                      ↓
                           direct selection or search
                                      ↓
                     semantic choice → live rebinding → engine acceptance
                                      ↓
                  represented history / belief update → next decision

private runs → decision and replay records → kernel fitting / neural training → study results
```

The adapter projects engine state into player information and captures it with
the available menu. It rebinds the chosen semantic action to the current engine
objects before asking the engine to apply it. Simulation advances to the next
player decision; see [simulation](architecture/information-and-decisions.md#live-selection).

## Module responsibilities

| Location | Responsibility |
| --- | --- |
| `agent/infoset-semantics` | Player information, history, semantic actions, and the contracts policies see. |
| `agent/infoset-planning` | Planning algorithms and root selection. |
| `agent/infoset-argentum` | Trusted Argentum projections, engine-backed worlds, and transitions. |
| `agent/mono-red-models` | Mono-Red features and value evaluators. |
| `agent/neural-policy` | Factual tensor encoding and ONNX runtime loading. |
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
- [Neural policy training](neural-policy.md): tensors, player memory, training
  and ONNX sessions.
