# Retained completion adapters

These read-only Java source-file adapters are part of `research audit`. Python
owns bounded dispatch and inspection provenance. Java uses only the selected
frozen build's classpath, serializers, manifest verification and scientific
readers; Java source-file mode compiles in memory. No producer, replay, fit or
search is invoked. Java 17 or newer is required (the project runtime uses 26).

`GameplayCompletionAudit` uses `loadRetainedGameplayLengths`, including its
origin, stopping-prefix and continuation checks. It counts outcomes only within
the eligible inspected population. It does not add a strength inference.

`FactualResidualCompletionAudit` targets `factual-residual-study-v1`, including
`FactualResidualContinuation`, as retained at source revision
`064f7b866704e9936a7f470fbc77ed9afe99365c` (use a verified compatible build).
The original producer may be older; an inspection build does not relabel it.
Unknown serializer schemas or incompatible Java/Kotlin APIs refuse explicitly.

The frozen producer does not export its corpus, environment or training binding
constructors or its single-child source join. Their v1 compatibility checks live
here, using the frozen serializers, hashes and types. They correspond to
`FactualResidualStudy.kt` (corpus/environment/training bindings), and
`FactualResidualContinuation.kt` (source replay/checkpoint join). Revalidate those
checks against owning source before extending support to another protocol.
Training uses the owning checkpoint loader. Prediction values are retained
observations with population/finite-value checks; the gate is recomputed with the
owning function. These checks do not rerun training or prediction evaluation.

The public lane tests orchestration, bounded metadata, refusal, immutable identity,
and status/recovery semantics using synthetic fixtures. Native compatibility is a
separate, explicit lane against a retained build and private evidence: run the
workbench command shown in `docs/research-workbench.md`, retain its output outside
the checkout, and report which stages the fixture actually exercises. No private
fixture is copied into public source, and missing fixtures must not silently skip
a claimed compatibility test. Compilation alone is not scientific validation.

`tests/FactualReadoutCompatibilityTest.java` is an explicit compatibility
regression. Compile it together with the adapters against a compatible retained
classpath into a disposable external directory. Run
`FactualReadoutCompatibilityTest ALLOCATION_REPORT` with that directory plus the
same classpath. It reads the allocation and constructs synthetic unexecuted rows
in memory: a complete population passes, duplicate/missing coordinates refuse
even without a retained gate, and an inconsistent retained gate refuses. It
neither produces evidence nor exercises fitting or prediction correctness.
