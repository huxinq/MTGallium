# Reusable terminal-kernel research workflow

These public commands compose retained position banks, conditional terminal
samples, one frozen fit and saved-position validation. They do not declare
playing strength or select a winning model automatically. Generated data, plans,
build bundles and campaign records belong under the private evidence root.

## Build once and execute the retained runtime

Commit a clean treatment with the intended Argentum pin, then run:

```bash
export MTGALLIUM_PUBLIC_SOURCE=1
export MTGALLIUM_PRIVATE_EVIDENCE_ROOT=/absolute/private/evidence
python3 tools/mtgallium-research-build \
  --output /absolute/private/evidence/search-teacher/work/build-001
```

The wrapper refuses a dirty source, pin mismatch, existing destination or
symlink output route. It forces a clean rebuild of the first-party and included
engine projects, disables build-cache reuse and Kotlin incremental compilation,
and retains the command, log, successful exit, elapsed time, source revisions
before/after and a copied ordered runtime. Ordinal jar names preserve distinct artifacts even
when different modules share a basename. A source-owned capture command
registers every bundle artifact and prints its `ResearchBuildReference`:
`directory`, `identity` and `manifestSha256`.

Put that reference in the study's `build` field and execute the frozen jars:

```bash
java -XX:ActiveProcessorCount=4 -Xmx8g \
  -cp "$(cat /absolute/private/evidence/search-teacher/work/build-001/classpath.txt)" \
  org.mtgallium.evaluation.searchteacher.SearchTeacherEvaluationKt \
  --suite terminal-kernel-study \
  --profile /absolute/private/study.json \
  --deck-manifest /absolute/private/deck.json \
  --output /absolute/private/evidence/search-teacher/work/study-001
```

Run from a clean checkout of that exact source and engine. Study launch verifies
the bundle against the actual JVM and ordered classpath. VM workload arguments
may differ from the build-capture invocation; the study binds their actual values.
A local build attestation supplies a causal build record and rejects accidental
stale binaries. It is not a cryptographic proof against fabricated local records,
nor an independently reproduced build.

## One bounded learning iteration

`TerminalKernelStudyPlan` is the JSON contract for `terminal-kernel-study`:

| Field | Meaning |
| --- | --- |
| `campaignId`, `campaignDirectory` | Explicit campaign and its append-only private data-use registry |
| `build` | Verified clean-build reference |
| `baseline` | Frozen terminal-kernel fit reference, including manifest hash |
| `development`, `validation` | Each has an exact `PositionBankScreenPlan` in `plan`, and optional `retained` target reference |
| `control` | Exact `ROOT_ROLLOUT_SELECTION` plan for the validation roots, one production choice per root |
| `gate.minimumPositiveGroups` | Prospectively declared breadth requirement; no result-dependent default |
| `workers` | Collection worker count, default 2 |
| `maximumNewContinuations` | Total newly requested terminal samples, including the pilot; default 25,000 |
| `maximumProjectedCollectionSeconds` | Pilot projection cap for primary collection, default 3,600 seconds |
| `requireCastingContext` | Require a spell-containing menu at every selected root; default true |
| `retainedFit`, `retainedControl` | Optional exact completed-stage references; absent means produce a new stage |

Construct a bank with the existing `real-game-position-bank` command. Supply
explicit sorted root IDs for both partitions. The study authenticates bank
metadata and the baseline model, requires complete declared-profile menus and
keeps every training seed group out of validation. The historical baseline may
already contain multiple data additions. The new fit appends development roots;
it preserves the baseline's features, kernel, ridge, weighting and target.

Both target plans must retain the same production continuation policies,
repetitions, belief configuration, seed domain and terminal sampling settings as
the baseline. They may differ in population and output references. Alternative
continuation policies belong in the sensitivity command below, not in this fit.

The sequence is fixed:

1. Authenticate population metadata and record training and validation use
   before decoding target values or fitted models. Retain the campaign's prior
   validation accesses in the study; a later refusal does not erase its reservation.
2. Run a mandatory two-root **development-only** terminal pilot: the largest
   selected menu and one root from another seed group. Refuse incomplete
   samples or an excessive primary-work projection. Pilot coordinates overlap
   primary samples and are never extra independent evidence.
3. Collect or authenticate development targets, then fit once or authenticate
   an exactly matching retained fit.
4. Save and verify `frozen-fit.json` before opening validation outcomes,
   including when validation targets are reused.
5. Collect or authenticate validation targets and actual annotated production
   rollout choices. Reject invalidating replacements and unexpected search
   diagnostics.
6. Compare raw first-menu model argmax choices, retain every root/group and
   target repetition, evaluate the fixed gate and finalize the study artifacts.

The exploratory gate requires positive equal-group new-minus-old payoff in the
overall mean and every target repetition, the declared number of positive groups,
and nonnegative new-minus-production means overall and in every repetition.
Group weighting averages roots within each group before averaging groups.
Passing is a reason to design a separate deployment test, not statistical
significance, optimal value or gameplay strength.

A failure retains its diagnostic, measured stage costs and any completed child
manifests. The failed parent does not acquire a successful final manifest. Use a
fresh parent output and explicit `retained` references to reuse successful stages;
do not rerun successful collection implicitly. Reused targets must match the
entire requested plan, and a reused fit must match the exact appended training
plan. Reuse preserves original producer identities and does not count historical
samples or fitting as new work.

## Campaign population usage

`campaign-data-use --profile use.json --output REGISTRY` appends one immutable,
source-bound use record. Its plan contains:

- `campaignId`, `studyIdentity`, `purpose`;
- `role`: `TRAINING`, `MODEL_SELECTION`, `METHOD_SELECTION`, `INSPECTION` or
  `FINAL_EVALUATION`;
- `timing`: `PROSPECTIVE_RESERVATION` or `RETROSPECTIVE_RECORD`;
- `populations`: each has a verified `bank` reference, exact `rootIds`, and
  optionally a `screen` reference. A screen-backed record must include every
  selected root, including refused rows.

Groups are derived from authenticated bank metadata, never supplied by callers.
Library seed, deck and card pool determine a group across seats, policy seeds,
source runs and bank versions. Existing events are never overwritten. An
incomplete or corrupt event refuses further admission instead of disappearing.

`campaign-data-snapshot --profile snapshot.json --output SNAPSHOT` retains an
immutable snapshot. The profile contains `directory` and `campaignId`. The
snapshot lists all known uses of each group. A retrospective record is an honest
backfill; its recording time is not historical access time. A reservation records
intended exposure, including when a later stage fails. Neither an empty registry
nor absence of a recorded overlap proves pristine campaign confirmation.
Unregistered human or agent inspection remains unknown.

## Target sensitivity without fitting to the answers

`terminal-target-sensitivity` uses `TerminalTargetSensitivityPlan`, the same
`--profile`, `--deck-manifest` and `--output` arguments, and a verified build.
Supply a bank, retained production `baselineTargets`, retained
`productionControl`, two frozen model references, explicit validation root IDs,
ascending `samplePrefixes`, and named `variants` with complete terminal-screen
plans. Declare the worker count, total new-sample cap and projection cap.

All variants use the same roots, target repetitions and seed domain. Each gets a
small technical pilot before its main collection. The complete grid is retained.
It reports fixed-model payoff differences, best-action sets, strict pairwise
ranking reversals and ties, compared with the full retained baseline target.
Nested sample prefixes reuse actual saved samples. They are dependent subsets,
not additional replications. Changed continuation or belief-particle settings
remain distinct target interventions. There is no best-variant selection,
refitting or replacement of the original training target.

## Link saved-position results to deployment evidence

`research-transfer-audit --profile links.json --output AUDIT` performs no game
or target collection. `ResearchTransferAuditPlan.links` supplies named links with
the exact model, bank, terminal targets, annotated rollout control, completed
sequential gameplay run and candidate policy ID.

The current adapter covers the CAST-context kernel root-rollout intervention.
It authenticates manifests and checkpoint populations, verifies the actual
model/continuation identity, and permits only the declared simulation-budget
difference alongside that rollout change. The sequential comparison must be
around parity. It reports saved-position gain, the original sequential decision,
planned/executed/inspected/overshoot populations, measured decision and per-game
search costs, and training/screen/gameplay group overlap.

Inconclusive gameplay is neither a successful nor a failed transfer measurement.
Selected adaptive links do not estimate a benchmark's predictive accuracy.
Establishing that requires prospective links across independent interventions
and complete-game evidence; the audit does not manufacture that evidence or
pool incompatible treatments.

## Cost interpretation

`costs.json` records invocation elapsed wall time and, when available, JVM process
CPU time. Each completed, reused or failed stage has its own timing, result
identity when available, new-work counts and accumulated component times.
Reconstruction and selection times can overlap across workers; they must not be
added to wall time as if sequential. Reused stages report current verification
cost and zero new scientific work, not zero historical cost. JVM CPU excludes
child processes. Build cost is retained in the separate build bundle. Agent
allowance, machine energy and unobserved historical costs are not inferred.

## Development prediction diagnosis

`terminal-prediction-diagnostic` accepts a `TerminalPredictionDiagnosticPlan`
with verified `bank`, `targets` and frozen `model` references. It requires two
complete production-target repetitions on DEVELOPMENT groups excluded from
that model's recorded training, with the same terminal target configuration and
engine. It reuses all samples and performs no fit or continuation collection.
Register the intended METHOD_SELECTION population in the campaign before use.

The report retains scores, action means, sample accounting and action-centered
moments per root, per group and with equal group weighting. Pooled squared
residual equals cross-repetition residual product plus one quarter of squared
repetition difference. These are descriptive algebraic quantities; negative
cross-products remain negative. Repetitions share the sampled belief population,
so persistent residuals cannot distinguish model error from posterior error,
nor identify a preferred representation or learning intervention. Source revisions
remain separately recorded. Sample-max regrets are optimistic and inspected
development data do not constitute fresh confirmation.

## Large gameplay reports

Calibration and sequential-gameplay reports retain their existing JSON schema
and bytes but stream JSON to an atomic temporary file. Completed-run resume,
sequential transfer auditing and position-bank import stream the report parser
after their existing manifest checks. This avoids whole-report String and UTF-8
byte-array allocation, including the single-String size limit. The full decoded
report object still resides in memory; streaming JSON does not imply bounded
heap use independent of the number of games. Size the heap and private storage
for the declared cap, and preserve checkpoint recovery and all population checks.

## Optional continuation of stopped gameplay

`search-teacher-continuation-preflight` authenticates a completed parent and the
current frozen treatment without playing games. `search-teacher-continuation`
uses the same explicit JSON plan and output directory to extend it. Both require
`--profile`, `--output`, and `--deck-manifest`; worker count belongs to the plan.

The plan records `parentDirectory`, `parentIdentity`, `parentManifestSha256`,
`parentPrefixSha256`, `expectedSourceCommit`, `build`, `sourceCompatibilityStatement`,
`totalPairCap`, `workerThreads`, and optional `seekSuperiority`. Omitted or false
`seekSuperiority` preserves the original plan encoding and continuation protocol.
The `build` reference pins the verified clean-source frozen runtime. The cap
counts parent plus new pairs and must exceed the parent's cap.

The default mode requires an operationally valid parent stopped for futility or
exhausted budget, with no executed overshoot. Only the cap increases: original
bets and boundaries remain fixed.

With `seekSuperiority: true`, the parent must be a completed, valid
`NON_INFERIOR` trial with its authenticated confidence sequence. The distinct
`search-teacher-confidence-sequence-superiority-v1` protocol inspects parity in
that already-declared time-uniform confidence sequence. Its betting fractions
and directional error allocations remain fixed; the same mixtures are evaluated
at 0.5 with no capital reset. Every executed parent pair, including original NI
overshoot, enters exactly once in order. The first parity crossing determines
this readout's own inspected prefix and overshoot. `ABOVE_NULL` rejects mean
at most 0.5; `BELOW_TARGET` rejects mean at least 0.5. Invalid execution stops
without a score, and an exhausted cap is inconclusive. Superiority is not
promised. If inherited scores already cross parity, the report finalizes with
zero new pairs and no child dispatch.

Both modes keep seed base, ordered population, model references, policy
descriptors and deck fixed. Current policy specifications and Argentum must match;
implementation equivalence across different source revisions still requires a
reviewed compatibility statement, retained alongside both source identities.

A new parent-linked report governs fixed calibration batches at the next unseen
pair index. Each batch retains its own source, manifest and checkpoints; it is
an execution unit, not an independently restarted statistical test. The
cumulative test inspects every prefix and dispatches no later batch after its
first stop. Already executed work beyond that prefix is retained as overshoot.
Resume verifies and reuses existing batches and checkpoints; the original
parent result and cap are never rewritten. A finalized continuation can be
reverified by the same command without playing missing games. Nested batch
manifests are registered in the final continuation manifest and their complete
artifacts are checked during reconstruction.

Costs report parent, new and combined execution epochs, including overshoot.
The strength-and-cost indicator requires the upper-only boundary, valid
execution, no treatment diagnostic issues, and both new and combined mean
searched-decision cost ratios at most one. Per-game search time is reported
separately. This indicator does not automatically promote a policy or establish
the subsequent learning iteration.

Optional continuation retains the original process's time-uniform guarantee
under its original conditional-mean null relative to all available information.
It does not yield fresh confirmation, conditional-on-this-prefix error control
or campaign-wide multiplicity control. Other treatments and selected subsets
cannot be appended. The parent keeps its original disposition, inspected prefix and overshoot even
if the continuation later crosses a different boundary. An NI result therefore
remains an NI result in its original artifact.

### Game length and runtime inspection

`just gameplay-summary /private/run` authenticates an already finalized calibration,
sequential calibration, optional/superiority continuation, or attack-gameplay run.
Pass a second directory to compare two runs:

```sh
just gameplay-summary /private/earlier/gameplay /private/later/gameplay
```

The command prints to stdout, verifies source/configuration identities and registered
manifests/checkpoints through the existing loaders, and never invokes a gameplay or
replay runner. Each parent/child retains its actual execution identity. A running
composed root is refused because it has no authenticated final chunk index; a
finalized child can be inspected explicitly. Invalid operational sequential sources
remain subject to the existing sequential loader's refusal contract.

Reports show accepted decisions (including passes, responses and singletons), searched
decisions across both seats, player-turn number at terminal, and concurrent game time.
Means, medians and nearest-rank p90 decision lengths are descriptive. Counts identify
missing historical fields; absent turns are not zero. The optional terminal-turn field
is omitted from historical GameRunResult serialization when absent. New arena games
record the engine's terminal player-turn number (not rounds); no replay is needed to
recover it in future reports.

New calibration, attack gameplay and continuation Markdown reports include these
summaries automatically. Terminal games enter length summaries only through valid
complete pairs; stopped/invalid pair attempts, inspected games and operational
overshoot remain distinct. Outcome-conditioned lengths use the inspected population
and cannot establish that stronger policies cause shorter games. Both policies share
one game length and game time. Cross-run ratios keep populations separate and expose
configuration, source, deck and worker differences; they are not isolated speedups or
tournament throughput. Existing retained reports are not modified.
