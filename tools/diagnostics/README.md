# Search adapter cost profile

`SearchAdapterProfile.java` runs four existing tactical roots with a synthetic
60-card deck and the default production Search Teacher configuration (8
particles, 64 simulations, 32-decision horizon). It measures initial policy
and belief construction separately from selection. Each repetition starts from
a fresh root and session; one repetition per root warms up before JFR starts.

This is a cost-location diagnostic, not a strength test, a full game, or an
isolated throughput comparison. It excludes fixture preparation from stage
timings and does not measure sequential belief updates between real decisions.
The JFR also contains fixture preparation and report writing between stages;
filter samples to the `mtgallium.AdapterProfileStage` intervals on their event
thread. Allocation sample weights estimate attribution, while reported stage
byte counts use the current-thread allocation counter.

Commit source first and use a fresh external evidence destination. Build the
current runtime classpath with:

```sh
MTGALLIUM_PROFILE_CLASSPATH=/private/setup/classpath.txt \
  bash tools/mtgallium-gradle \
  :evaluation:search-teacher:searchAdapterProfileClasspath \
  -I tools/diagnostics/search-adapter-profile.init.gradle
```

Compile the Java source with JDK 21 against that classpath, then run its main
class with the compiled diagnostic directory prepended to the same classpath:

```text
SearchAdapterProfile <repository-root> <relative-evidence-directory> <repetitions>
```

Set `MTGALLIUM_PUBLIC_SOURCE=1` and an external
`MTGALLIUM_PRIVATE_EVIDENCE_ROOT`. Use owner-provided durable execution for a
substantial run. The diagnostic refuses dirty source, an engine/gitlink
mismatch, source changes during execution, and Java instrumentation agents.
It retains source provenance, full policy identities, deck, search results,
belief diagnostics, CPU/allocation/timing measurements, JFR, and harness source
through the existing research-run artifact authority. All repeated outcomes
remain diagnostic search results; no terminal-game population is implied.

## Historical AI setup prototype comparison

The adapter now uses `AIPlayer.Factory` from the integrated engine pin: a lazy
factory per heuristic annotator creates a fresh player for each selection.
Ordinary profiling of that implementation uses the three-argument invocation
above, with no runtime overlays.

The earlier isolated comparison was generated from MTGallium `ce376f8ef6c6`.
Its `ai-player-factory-adapter.patch` remains available at that historical
revision. The following instructions describe that experiment, where the
production adapter and pin had not yet adopted the factory. Check out its
recorded source to reproduce it; do not apply the old patch to today's adapter.

For this experiment, compile the original and patched `ArgentumSearchWorld.kt`
with identical Kotlin flags and serialization plugin into separate adapter
jars. Run separate JVMs with each adapter jar and its matching engine AI jar
before the ordinary runtime classpath. Use `FactoryComparisonBundle.java` to
seal each prepared external input directory with the existing research-run
artifact authority. Its inputs include exact source revisions, effective
adapter source, engine/adapter patches, build command, both overlay jars,
diagnostic jar, and runtime classpath. It also records runtime file hashes.
Commit the diagnostic sources before sealing or running the experiment.

Pass the bundle directory and identity as the fourth and fifth arguments to
`SearchAdapterProfile`. It verifies the bundle before and after execution and
checks that the overlaid classes and harness load from its jars. The comparison
uses two warmup repetitions per root. The resulting binding
names the bundle identity. **Checkout provenance describes the unchanged pin;
the bundle describes the runtime treatment.** Interpret both together. This
is an explicitly overlaid diagnostic, not verification of a pinned production
build. Runtime file inventories bind the remaining classpath inputs; preserve
those inputs during the comparison. The binary/source association also retains
the build command; binary hashes alone do not prove compilation provenance.

## Search count shadow diagnostic

The ordinary evaluation runtime includes
`org.mtgallium.evaluation.searchteacher.SearchCountDiagnostic`. Build its
classpath with the command above, then launch that main class with:

```text
<repository-root> <relative-evidence-directory> <base-seed> <maximum-search-decisions>
```

It records two synthetic mono-red traces using the normal arena lifecycle:
Search Teacher controls p0 at the base seed and p1 at the next seed, with the
determinized Argentum heuristic controlling the other seat. The current runtime
uses 8 particles, 64 simulations and a 32-decision horizon. Each trace stops at
the requested search count (1–64), a game end, or an existing typed failure.
There is no warmup game. All outputs remain private, and the existing
`ResearchRunArtifacts` manifest binds source, behavior, protocol, runtime-file
hashes, complete game records and available trajectories.

The arena already records its shadow heuristic beside each searched choice.
Join trajectory decisions to `seatDiagnostics.searchDecisionsDetail` by
decision index for action agreement and search latency; retain comparator
fallback counts from the game record. Count existing automatic forced passes
separately from searched singletons, and use the expansion's exhaustiveness
and omissions when describing a singleton.

Search latency covers policy selection after root expansion, including belief
synchronization. Game elapsed time also includes setup, the opponent,
the shadow comparator, evidence writes, transitions and belief updates.
The comparator's own cost is not isolated. Agreement is a retrospective
opportunity to investigate, not a prospective gate or a strength result;
subtracting matching search time describes an optimistic counterfactual,
not a measured speedup. The two bounded traces do not represent full-game or
typical-workload frequencies.

For a paired singleton-selection comparison, append `search` or `singleton`:

```text
<repository-root> <relative-evidence-directory> <base-seed> <maximum-accepted-decisions> <search|singleton>
```

These modes stop both traces after the same accepted-decision limit (1–256),
including explicit passes and both seats' actions. They do not impose a search
count limit. `search` retains the default policy; `singleton` enables
`PolicySingletonSelectionConfig(enabled = true)` in the responsible policy.
All in-tree decisions remain explicit and policy compression stays disabled.
Pending responses and pregame choices remain searched. Singleton selections
have no search values or visits and therefore produce no searched trajectory
record; the accepted transition ledger and selection-kind counts retain them.

The comparison also retains private before/after authoritative fingerprints,
both players' information-state digests, accepted semantic choices, and forced
transition events. Compare these traces before interpreting elapsed time, then
compare retained multi-candidate search results and belief diagnostics. Use
separate JVMs and alternate mode order for a timing comparison. The timings
include diagnostic observation/evidence overhead and the arena's shadow
comparator, which is omitted at unsearched roots. They are not a production
full-game or strength result. The ordinary default remains disabled, preserving
existing callers' search-label contract and historical behavior identities.

## Automated continuation progress

`tools/mtgallium-continuation-monitor` installs a passive monitor beside an
existing parent-linked continuation. Develop and commit it in a separate
worktree while gameplay is running. It compiles the diagnostic consumer against
the treatment's retained frozen runtime; its own source and compiled classes
are sealed separately. It never invokes gameplay, changes the stopping rule,
or edits treatment artifacts.

```sh
MTGALLIUM_PUBLIC_SOURCE=1 MTGALLIUM_PRIVATE_EVIDENCE_ROOT=/private/evidence \
  tools/mtgallium-continuation-monitor \
  --game-output /private/evidence/search-teacher/work/continuation \
  --durable-state /private/operational-state/the-existing-run \
  --output /private/evidence/search-teacher/work/continuation-monitor \
  --trials 2000 --poll-seconds 60 --start
```

The low-priority systemd user service refreshes `latest.md` and `latest.json`
every minute and seals a new statistical snapshot only when the coordinator
has announced a new completed batch. It verifies source-owned parent and batch
manifests/checkpoints, the contiguous population and the cumulative stopping
result before admitting scores. Within the process, verified immutable inputs
are reused; changed manifests and verification failures are reported as errors,
with the last verified snapshot clearly identified. A restart reauthenticates
inputs. An incomplete batch and post-stop overshoot are excluded from WR and CI.
The service stops after the workload reports terminal execution, and sends no
external messages. Synthetic tests cover cap exhaustion, source-rule stopping,
worker-batch overshoot, invalid observations and deterministic snapshots.

WR counts wins, with draws reported separately. Its descriptive 95% interval
uses the existing 10,000-resample paired bootstrap, preserving the two legs of
each seed as a block. The betting test continues to use pair point scores,
including half-credit for a draw. The descriptive CI is not an anytime
confidence sequence and does not replace the stopping rule.

The stopping forecast uses 2,000 deterministic Monte Carlo paths conditional
on the current complete prefix. Future paired point scores are resampled from
the observed pair-score distribution; every path runs the treatment's exact
`pairedSequentialTest`. The sealed setup binds the complete continuation plan. The report gives expected additional **executed** games
including final-batch overshoot and any in-flight batch, a modelled 5th–95th percentile range, Monte
Carlo SE, and upper-only stopping probability. Any stop includes futility
and exhausted budget. This is a plug-in forecast, not games guaranteed to show
superiority, and its range omits uncertainty in the estimated future score
distribution. Cost-gate success and non-game interruptions are not forecast. It does not select a treatment,
change budgets, or promote a policy.
