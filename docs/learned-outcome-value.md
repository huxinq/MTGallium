# Experimental learned outcome value

The learned-value subsystem supplies a checkpoint-backed, root-relative leaf
evaluator, a replay-derived outcome corpus, staged training and admission,
paired arena pilots, and fixed-root, global-signal, decision-local, and retained
inference-parity diagnostics. It is experimental source, not the default
production policy or a claim of improved playing strength.

`agent/search-teacher` owns feature compilation and checkpoint inference.
`evaluation/search-teacher` owns private corpus loading, training, checkpoint
admission, experiments, and diagnostics. The arena binds an injected evaluator
to policy identity and records successful-backup settlement origins; missing
historical origin counts remain explicitly unavailable.

## Source and historical compatibility

This source integrates the learned subsystem and later decision-local diagnostics
from `f788a31d` and the retained parity audit from `25ce6892`. The public Argentum
pin is unchanged. Replay consumers use the current MTGallium replay types and
explicit adapter transitions, and policy sessions obtain registry authority
from their search world.

Historical outcome-corpus production remains bound to its declared old source,
adapter trees, and Argentum `3eda577fdd10d08e0e62d66b4727ab53f1b41ff5`.
Its producer checks intentionally reject a current-engine checkout. Publishing
and compiling the reader does not establish that current engine execution can
reconstruct that historical population. Use the matching historical source for
that reconstruction; admitting another engine requires separate compatibility
evidence and an explicit research decision.

Retained checkpoint diagnostics authenticate recorded source and material
identities. A diagnostic checkpoint is not a production promotion capability.
Existing artifacts keep their original identities after this source advances.

## Inputs and verification

Canonical replays, corpus bundles, gate records, checkpoints, frozen private
deck/profile resources, and generated reports are not included. Commands expose
their required inputs through `SearchTeacherCli`; they fail when required
material or admission is absent. In public-source mode, set
`MTGALLIUM_PRIVATE_EVIDENCE_ROOT` to an external directory for generated evidence.

Self-contained tests cover perspective-safe features, terminal bypass, typed
evaluation failure, checkpoint admission, train/test separation, replay
authentication, settlement provenance, and diagnostic calculations. `just check`
runs the public technical lane. These tests do not reproduce historical research
results or launch a new training or tournament population.
