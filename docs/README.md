# Documentation

## Run research

- [Research quick start](research-workbench.md): build, public examples, live
  games from Python, and Kotlin experiments.
- [Games and CLI reference](research-cli.md): `GamesPlan` settings, native
  policies, output files and numerical commands.
- [Batch research runs](research-runs.md): ladder evaluation, cost measurement,
  resumable game corpora, remote runs and source context.
- [Neural policy training](neural-policy.md): PyTorch training, checkpoints and
  ONNX export.
- [Durable runner](workbench/durable-runs.md): keep a long command and its logs
  running across sessions.

## Understand the system

- [Architecture](architecture.md): dataflow, modules and dependency rules, with
  links to the detailed contracts.
- [Glossary](glossary.md) defines terms; the [API map](terminology.md) links
  concepts to classes.
- [White paper](whitepapers/README.md): the mathematical model.
- [History](history.md): retired protocols and the Argentum pin record.

## Change the code

Follow [CONTRIBUTING](../CONTRIBUTING.md). Run focused tests while developing,
then `just check`; neural checks have [separate commands](neural-policy.md#verification).
