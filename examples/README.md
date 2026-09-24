# Research examples

Small public fixtures and scripts for the research tools. They are not private
research evidence or a playing-strength benchmark.

- `research-games.json` and `research-kernel-rows.json` exercise the `games`,
  `fit` and `predict` commands with synthetic data. See the
  [runnable commands](../docs/research-workbench.md#runnable-public-examples).
- `python-game-learning.py` creates and branches live games through one JVM,
  collects factual tensors, and with `--train --epochs 2` trains a PyTorch model
  and plays with it as a Python policy. Give it a fresh output directory. See
  [learning inputs](../docs/research-workbench.md#learning-inputs).
- `python-value-search.py` compares hand-written value weights at search
  horizons of 2 and 8 decisions. See [value models](../docs/value-models.md).
- `python-game-throughput.py` compares native, mixed Python/native, and recorded
  play on matched burn and creature games; `--traffic` adds response bytes and
  counts. See [game-loop measurement](../docs/research-workbench.md#measure-the-game-loop).
