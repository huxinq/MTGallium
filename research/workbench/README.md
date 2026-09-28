# Research workbench

Kotlin side of the research tools: game setup and play, the `games`/
`predict`/`encode`/`replay-state` CLI, the JVM half of the live Python
connection, and root-action kernel features. It depends on the agent modules
and Argentum; nothing in this build depends on it.

- `Games.kt`: `createWorld`, `playGame`, `GameAgent`, `selectorPlayer` and
  `searchPlayer` for Kotlin experiments.
- `ResearchCli.kt`: `ResearchGameConfig`, `runGames` and the CLI entry point (`ResearchCliKt`).
- `GameServer.kt`: the synchronous connection behind `research_workspace`;
  standard output carries only responses, diagnostics go to standard error.
- `SeatHost.kt`: the browser-seat host and `SeatAgent` development interface.
- `NativePolicies.kt`: the built-in native policies and the `JvmPolicyProvider`
  extension point through which [another build](../../docs/research-cli.md#added-policies)
  adds policies.
- `Kernel.kt` and `HashedKernelFeatures.kt`: hashed decision features and the
  frozen casting-kernel scores for `horizon16`.
- `ResearchFiles.kt`: JSON Lines reading and writing, and execution context.

`tools/mtgallium-research` builds this module through the `researchClasspath`
task, which records the Java executable and runtime classpath in
`build/research/runtime.json`. Put new experiment `main` functions in this
package. See the [research quick start](../../docs/research-workbench.md) and the
[games and CLI reference](../../docs/research-cli.md).
