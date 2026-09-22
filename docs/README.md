# Technical documentation

## Current research

Start with the [direct research library and CLI](research-workbench.md). The
[architecture](architecture.md#module-responsibilities) maps source ownership and
extension points. The [PyTorch guide](neural-policy.md) covers
direct training, numerical checkpoints and optional export. The independent
[durable runner](workbench/durable-runs.md) manages a foreground command's lifetime
across sessions.

## Understand the system

The [architecture](architecture.md), [shared glossary](glossary.md), and
[terminology map](terminology.md) describe modules, definitions, and APIs.
The [white paper](whitepapers/README.md) supplies the mathematical model. The
[evidence and research boundary guide](architecture/evidence-and-research.md)
describes record contents, output on interruption, and source metadata.

[Record organization](research-records.md) covers working notes, run outputs and
research navigation. [Historical source](history.md) locates retired protocols.

## Implement and verify

Follow the [coding-agent guide](../AGENTS.md) and use focused tests before the
public `just check` command.
The [architecture](architecture.md#module-responsibilities) and [neural
verification commands](neural-policy.md#verification) identify the current checks.
