# Research examples

The current direct research CLI has three public examples:

- `research-games.json` and `research-kernel-rows.json` exercise its JSON
  commands with synthetic fixtures. They are not private research evidence or a
  playing-strength benchmark. See the [runnable commands](../docs/research-workbench.md#runnable-public-examples).
- `python-game-learning.py` creates and branches live games through one JVM,
  collects native factual tensors, and can train the ordinary PyTorch learner for
  use as a live Python policy. See [the live-interface guide](../docs/research-workbench.md#live-games-from-python).

## Python-authored research

`python-game-learning.py` creates and branches live games, collects factual
tensors, and optionally trains a PyTorch model and runs it as a Python policy.
Run it with a fresh output directory and `--train --epochs 2` for the complete
technical journey. Its short-deck imitation task does not measure playing strength.
