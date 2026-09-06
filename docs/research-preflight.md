# Research-run preflight

`research-preflight` runs a small technical rehearsal before a substantial research
run. `research-preflight-verify` rechecks a retained pass immediately before launch.
Both require an explicit JSON profile and a separate private output directory:

```bash
bash tools/mtgallium-gradle :evaluation:search-teacher:run \
  --args='--suite research-preflight --profile /absolute/private/preflight.json --output /absolute/private/search-teacher/work/preflight-001'

bash tools/mtgallium-gradle :evaluation:search-teacher:run \
  --args='--suite research-preflight-verify --profile /absolute/private/preflight.json --output /absolute/private/search-teacher/work/preflight-001'
```

Set `MTGALLIUM_PUBLIC_SOURCE=1` and `MTGALLIUM_PRIVATE_EVIDENCE_ROOT` to an
external evidence root, as for other research producers. Paths in the profile
are absolute. Both `targetOutput` and `--output` must be separate child directories
under the configured private `search-teacher/work` root. Inputs cannot overlap
outputs. The primary output must be absent or empty; preflight probes write access
and places smoke artifacts only in its own directory.

Commit the treatment first. A pass binds the source SHA and source fingerprints,
Argentum gitlink and checkout, full profile bytes, input hashes, target output,
actual Java executable/modules, JVM arguments, and ordered classpath contents
(including absent entries permitted by the JVM).
The binary fingerprint identifies the executed runtime; it does not prove that
those binaries were built from the declared source. Build from the committed
checkout and keep the execution classpath fixed.

The launcher must invoke verification with the same profile, runtime, and source
immediately before starting the declared primary workload, and honor its nonzero
exit on refusal. The existing full-run commands do not automatically enforce
this gate. This capability does not run arbitrary shell commands or own durable
execution. A tiny rehearsal cannot certify full-run memory, duration, every action
path, or research validity.

## Gameplay profile

```json
{
  "schemaVersion": 1,
  "targetOutput": "/absolute/private/search-teacher/work/gameplay-001",
  "work": {
    "type": "gameplay",
    "planPath": "/absolute/private/gameplay-plan.json",
    "sequential": true,
    "deckManifest": "/absolute/private/deck.json",
    "threads": 2,
    "smokeBaseSeed": 2026090602,
    "smokeSimulations": 4
  }
}
```

`planPath` contains the complete intended `SearchTeacherSequentialPlan` when
`sequential` is true, or `SearchTeacherCalibrationPlan` otherwise. The same
calibration runner exercises one seat-swapped pair for every candidate, preserving
particles, policies, evaluators, rollout horizons, and worker count. It reduces
simulations to `min(full simulations, smokeSimulations)`, sets phase `PREFLIGHT`,
pair offset zero, and uses the explicit smoke base seed, which must differ from
the primary base seed. The sequential runner retains the intended rule with a
one-pair cap. Neither the primary plan nor its schedule is modified.

The report must contain two valid completed games per candidate. Existing runner
checks verify private replay, safe trajectory, planner artifacts, and checkpoints.
A valid pair of losses passes; rejection, stopped execution, or missing legs
fails. Smoke game outcomes are excluded from primary strength evidence. Their
sequential disposition supplies no strength conclusion for the primary run.

## Position-screen profile

Use `work.type = "position-screen"` with absolute `planPath`, `deckManifest`,
and positive `threads`. The plan is a complete `PositionBankScreenPlan` in
`SEARCH` or `ACTION_CONDITIONAL` mode. Optional `smokeSimulations`,
`smokeRootLimit` and `smokeRepetitions` default to 4, 1 and 1. Each caps its
corresponding full setting; policies, model pins, particle counts, evaluator,
bank identity, partition and worker count remain fixed.

The gate authenticates the bank and binds its exact manifest alongside the
full plan and deck. Every smoke row must complete the requested search; an
automatic selection is insufficient. Action-conditional rows must retain
multiple distinct actions with all requested visits. The child screen's
finalized artifacts are verified. The smoke accesses a subset of the intended
roots with the same repetition seeds; it is a technical rehearsal, not an
independent quality sample or an additional replicate. A new full plan or bank
manifest cannot reuse the old pass.

## Decision-local learning profile

```json
{
  "schemaVersion": 1,
  "targetOutput": "/absolute/private/search-teacher/work/learning-001",
  "work": {
    "type": "decision-local-learning",
    "parentDirectory": "/absolute/private/original-retained-run",
    "precisionDirectory": "/absolute/private/precision-retained-run",
    "learner": "NONLINEAR",
    "nonlinear": {
      "hiddenUnits": 16,
      "epochs": 400,
      "learningRate": 0.01,
      "regularization": 0.001,
      "seed": 2026090601
    },
    "smokeTrainingRoots": 2,
    "smokeValidationRoots": 1,
    "smokeEpochs": 2
  }
}
```

The initial learning adapter supports the existing decision-local retained-32
population and its exact historical identities. It reuses the learnability
pilot's verified manifest, assignment, checkpoint-lineage, feature, and terminal
label loader. This is a bounded adapter, not a generic dataset loader. It does not
load TEST roots, generate new labels, or change the historical pilot protocol.

`learner` selects `LINEAR`, `PHASE_LINEAR`, or `NONLINEAR`. Linear variants use
the existing fixed ridge configuration; nonlinear uses the complete declared
configuration with epochs capped at `smokeEpochs`. The adapter deterministically
selects up to the declared root counts by root ID, fits TRAIN only, writes and
reloads the model, then requires finite and exactly matching predictions on
VALIDATION. The report retains selected IDs, full population counts, model ID,
and epoch reductions. Whole-game groups must not overlap. There is no loss,
accuracy, regret, or improvement threshold. These smoke models are technical
artifacts, not fitted primary-run models.

## Retention and reuse

A successful directory contains `preflight-report.json`, source/runtime/profile
snapshots, the workload's model/scores or child gameplay run, and a standard
`research-run-manifest.json`. Reuse verifies registered bytes and every child
run's artifacts, revalidates input manifests, and recaptures current bindings.
Any changed source, configuration, runtime, input, or destination refuses reuse.
Invoking `research-preflight` again on the same complete directory performs this
verification instead of rerunning smoke compute.

Failures after binding preparation retain a failed structured report and manifest;
failures during preparation retain `preflight-failure.json` without a reusable
pass. A failed or partial directory must be inspected and a fresh destination
chosen for a new attempt. Unsafe output destinations are refused before writing.
A completed preflight manifest means the technical report was retained; inspect
its checks or use the verifier to establish a pass.
