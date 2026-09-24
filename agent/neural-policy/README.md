# Stateful neural policies

This module scores captured player decisions with shared model weights and
player-owned memory. Inputs are `DecisionSite` and `EpistemicState` values.

Observation consumption advances memory through delivered events. Repeated
scoring leaves memory unchanged. Restoration checks player ownership, model,
schema, and history commitment; forking copies memory at the player's prefix.

The runtime supports current-view, GRU, and bounded-attention ONNX graphs on CPU.
Python training and inference use the same tensor schema. See
[neural policy training](../../docs/neural-policy.md) for tensor limits, graph roles,
session ownership, training, and export.
