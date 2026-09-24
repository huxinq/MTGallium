# Neural policy training

`tools/neural_policy` provides PyTorch models over factual byte inputs,
whole-sequence training, checkpoints, and optional ONNX export. The provided
models are current-view, GRU, and bounded-attention policies.

## Run training

Use a Python environment containing PyTorch.
From the repository root:

```bash
python -m tools.neural_policy.worker describe
python -m tools.neural_policy.worker train data.json /absolute/private/new-training \
  --epochs 20 --device cpu --threads 4 --readouts
```

`--model model-config.json` supplies `ModelConfig` fields. For example:

```json
{
  "architecture": "GRU",
  "hiddenSize": 24,
  "byteWidth": 12,
  "contextEvents": 16,
  "attentionHeads": 4,
  "actionScoring": "dot-product-v1",
  "bytePooling": "mean-max-v1"
}
```

`--training training-config.json` supplies the learning rate, group batch size,
seed, gradient clipping norm, and default epoch count. `--epochs` always means
**additional** epochs.

Choose a device such as `--device cuda:0`. An unavailable requested GPU raises
an error; there is no CPU fallback. Threads are user-selected. Deterministic
algorithms are the default; `--nondeterministic` opts out.

## Data and objective

The input is ordinary JSON with `version: 1`, objective
`equal-group-weighted-decision-cross-entropy-v1`, a factual-byte `schema`, and
`episodes`. Each episode supplies its `episodeId`, `groupId`, `playerId`, `split`,
`events`, and `decisions`. `TRAIN` episodes supply optimizer batches; optional
readouts report `TRAIN` and `EVALUATION` separately.

A decision supplies its causal `eventPosition`, an `input` containing `view`,
`actions`, `rulesExhaustive` and `profileExhaustive`, plus `target`, `weight`, and
`lossEligible`. `subset` is optional readout metadata. Tokens are unpadded byte
values plus one; zero is reserved for tensor padding. Schema maxima describe
input encoding, not an approved experiment profile. Targets are candidate-aligned
probability vectors. Context-only decisions have no target and zero loss weight.

`data.pack` creates tensors and masks without passing episode identities, targets,
or group labels to the predictive model. Sequence scores use only the events
delivered before the decision. `reduced_loss` takes a weighted eligible-decision
mean within each source group, then an equal mean over groups. Empty weighted
groups raise an error. Future observations and padding stay unavailable at earlier
decisions.

Train/evaluation group overlap is listed in the result. Correlated frames remain
correlated when they occupy different rows.

## Collect data and run a Python policy

The [live game interface](research-workbench.md#learning-inputs) supplies the
native factual encoding through `game.decision(factual=True)`: current view and
action tensors, the acting player's encoded events so far, and its event
position. The Python experiment assigns target, group, split
and weight; game outcomes are not automatically joined as action labels.

[`examples/python-game-learning.py`](../examples/python-game-learning.py) demonstrates
collection, a factual branch, explicit imitation-label construction, training, and
live action selection by the trained PyTorch model in one Python program. Run it
with `--train --epochs 2` in a PyTorch environment. The live example recomputes
the model from the events delivered to each player; a stateful Python policy manages
and forks its own memory.

## Save, continue, or change treatment

```bash
python -m tools.neural_policy.worker train data.json /absolute/private/more-training \
  --checkpoint /absolute/private/new-training/checkpoint.pt --epochs 10 --threads 4
```

Checkpoints contain weights, Adam state, random-generator state, progress, and
configuration. Matching data and settings support exact continuation on the tested
deterministic path. Changing data, device, settings, or learning rate changes the
treatment. A failed restore is not transactional; use a new learner after a loading
exception.

Each fresh output directory contains `context.json`, `checkpoint.pt`, and
`training.json` after training completes. The final `result.json` records the new
epochs and their losses separately from previous checkpoint metadata. A requested
optional readout or export may fail after useful training has completed; the
checkpoint and `training.json` remain available, but no `result.json` is
written for the failed request. Output directories are not implicitly overwritten.

## Optional production-format export

`--export` creates ONNX score/update graphs and checks their numerical agreement
with the frozen PyTorch model. Install the optional dependencies from
`requirements-export.txt`. Export imports are lazy, so training does not require
ONNX or ONNX Runtime.

`model.json` supplies the runtime schema, tensor dimensions, maximum batch size,
and graph names. `training.json` separately records training information.
`export_models(..., maximum_batch=1)` emits single-lane graphs; larger maxima emit
batch graphs. `maximum_batch` is configurable and defaults to eight.

The JVM loader is `OnnxSequenceModel.load(descriptorBytes, graphBytes)`, where
`graphBytes` resolves a graph name to bytes. Names are caller-defined, and one
graph can serve several roles. The loader checks tensor names, types and ranks;
ONNX Runtime checks concrete shapes when executing. Close the model to release
its sessions. Player memory belongs to the caller.

## Verification

Ordinary CPU training and model tests require PyTorch, not ONNX:

```bash
python -m unittest discover -s tools/neural_policy/tests -p 'test_*.py'
```

Export and CUDA integration checks are separate:

```bash
python -m unittest discover -s tools/neural_policy/export_tests -p 'test_*.py'
python -m unittest discover -s tools/neural_policy/cuda_tests -p 'test_*.py'
```
