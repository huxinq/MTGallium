# MTGallium architecture

MTGallium supplies perspective-safe policy information over a pinned Argentum
engine, information-set planning, a narrow Mono-Red model runtime, and direct
research tools.

The [terminology guide](terminology.md) maps concepts to APIs; the
[glossary](glossary.md) defines them and links to the [white paper](whitepapers/README.md).

## Primary dataflow

```text
Argentum state → trusted adapter → captured player information + admitted menu
                                      ↓
                           direct selection or search
                                      ↓
                     semantic choice → live rebinding → engine acceptance
                                      ↓
                  represented history / belief update → next decision

private execution → ordinary decision and replay records
                                         ↓
                            numerical core → study readouts
```

The adapter projects engine state into player information and captures it with
the available menu. It rebinds the chosen semantic action before asking the engine
to apply it. Simulation stops at termination, the next player decision, or a typed
execution failure. Responses, targets, ordering and mulligans require decisions.

## Module responsibilities

| Location | Responsibility |
| --- | --- |
| `agent/infoset-semantics` | Player information, history, semantic actions, and safe contracts. |
| `agent/infoset-planning` | Planning algorithms and root selection. |
| `agent/infoset-argentum` | Trusted Argentum projections, engine-backed worlds, and transitions. |
| `agent/mono-red-models` | Mono-Red features and value evaluators. |
| `agent/neural-policy` | Factual tensor encoding and ONNX runtime loading. |
| `agent/argentum-policy` | Argentum policy lifecycle, defaults, and search/leaf composition. |
| `research/workbench` | Direct games, Python bridge, feature/kernel routines, and CLI entry points. |
| `integration/argentum-policy` | Argentum application integration. |
| `evaluation/argentum` | Engine-policy evaluation and probes. |
| `tools/research_workspace` | Python session and game handles. |

`checkArchitecture` resolves production compile and runtime classpaths
transitively. Semantics, planning and models stay isolated from the engine and
application layers; agent and integration modules cannot depend on evaluation.

## Information and action boundaries

The [information and decision reference](architecture/information-and-decisions.md)
describes captured views, action identity, and deferred information projection.
The [history-equivalence guide](history-event-equivalence.md) covers opt-in history
normalization.

Policy role composition, belief snapshots and terminal continuation contracts
live in the [policy and belief reference](architecture/policies-and-beliefs.md).
The [belief-maintenance guide](belief-maintenance.md) owns conditioned particle
maintenance details. [Value models](value-models.md) documents scalar features and
search cutoffs; [neural policy](neural-policy.md) covers tensors, player memory,
training and ONNX sessions.

## Evidence and dependencies

The [research guide](research-workbench.md) covers live Python sessions, Kotlin
entry points, numerical routines and output files. The
[evidence reference](architecture/evidence-and-research.md) describes record
contents, partial output and source metadata.
