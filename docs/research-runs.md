# Batch research runs

Multi-game runs from Python: ladder evaluation, cost measurement, and resumable
game corpora, plus where results and their source context live. Start with the
[research quick start](research-workbench.md).

## Source context

Keep the source revision and any uncommitted diff, Argentum revision, settings
and inputs with every result. `--no-build` and `build=False` use the last
compiled classes; record their source when it differs from the current checkout.
Read historical records with their [producing source](history.md).

## Workers and memory

`evaluate()`, `measure()` and `run_games()` start one JVM per worker with the
shared `JAVA_OPTIONS`: `-Xms64m -Xmx768m -XX:+UseParallelGC
-XX:ActiveProcessorCount=2`. The default worker count is the smallest of 12,
CPU count minus two (at least one), and available RAM at 1.5 GiB per worker,
including Linux cgroup v2 memory limits. If not even one worker fits, the call
fails before starting JVMs; unknown platforms use one worker. Explicit `threads`
and `java_options` override these defaults; lower them when other jobs share the
machine. `Session()` and the CLI keep their own defaults.

Builds happen once before workers start. `build=False` reuses compiled classes,
so close sessions before rebuilding. On Linux with `flock`,
`tools/mtgallium-gradle` holds `/tmp/mtgallium-science-gradle.lock` during every
build, including builds started by sessions and public checks. An ancestor
process that already holds the lock is recognized, so outer `flock` commands
still work, and the Gradle daemon does not inherit the lock.

`Session.cpu_seconds()` reads user plus system CPU for all JVM threads from Linux
`/proc/<pid>/stat`. Read it before closing the session; a missing process or an
unsupported platform raises an OS error.

## Policy ladder

`research_workspace.evaluate` plays one candidate against a fixed set of named
opponents and appends one row to `ladder/ladder.jsonl` under the private evidence
root (`~/Documents/MTGallium-private-evidence` unless
`MTGALLIUM_PRIVATE_EVIDENCE_ROOT` is set):

```python
from research_workspace import evaluate

row = evaluate("heuristic", opponents={"random": "random"}, incumbent="random",
               decks=[deck, deck], setups=100)
```

A setup is two games with the same seed and swapped policy/deck seats. Existing
calls retain their legacy fixed-size behavior and OS-generated seeds. Old rows
are never rewritten. Opt into shared deals with `seed_pool="development"`;
sequential calls select this pool automatically unless explicit seeds are given:

```python
row = evaluate("heuristic", opponents={"random": "random"}, incumbent="random",
               decks=[deck, deck], sequential="improvement")
```

The first pool allocation freezes 8,192 development seeds and 32,768 disjoint
confirmation seeds from OS randomness in `ladder/seed-pools.json` under the
evidence root. Development runs start at position zero, so candidates share
deals. Rows record the pool hash and zero-based positions. `pool_start` selects
an explicit offset. `seeds=[...]` remains available for reproduction; reproducing
a confirmation is not a fresh claim. Keep the pools and their ledger together;
missing or corrupt state fails closed. Allocate from a filesystem with coherent
POSIX file locking (use the evidence host for shared storage).

The sequential unit is a complete seat-swapped pair. `sequential="improvement"`
tests 0 versus +20 Elo; `sequential="non_regression"` tests -10 versus 0 Elo,
using `1 / (1 + 10**(-elo/400))`. A normal profile GSPRT uses alpha=beta=0.05.
`max_pairs` caps the comparison. The improvement default is 1,376 pairs (2,752
games), derived as `ceil(v * (z(.975) + z(.95))**2 / delta**2)` with pair variance
`v=0.0875`, 95% planning power, and the 20-Elo score difference. Non-regression
uses the same calculation for its smaller 10-Elo difference. These assumptions
and any explicit cap are recorded. Each look forecasts the
probability of crossing either bound within the remaining budget using a
Brownian approximation with the current drift and variance. Below 10%, it stops
for futility. The normal model and error targets are approximate.

Improvement outcomes are **BETTER**, **NOT_BETTER**, or **INCONCLUSIVE**;
non-regression outcomes are **NON_INFERIOR**, **INFERIOR**, or **INCONCLUSIVE**.
Futility and budget exhaustion are inconclusive. The row retains an interval,
thresholds, sample count and stopping reason. Effect sizes selected from stopped
runs are labeled biased upward; their normal intervals are descriptive, not
confidence sequences. Each opponent has its own test, and the row finishes when
all tests stop. Workers issue seeds in pool order, and only the contiguous
completed prefix changes a decision. In-flight games are recorded separately
and cannot change a stopped result. Unfinished games count as candidate losses
in the new score and are also counted separately. Errors still fail the row.

For a final claim, use a predeclared **fixed-size** confirmation:

```python
row = evaluate("heuristic", opponents={"random": "random"}, incumbent="random",
               decks=[deck, deck], phase="confirmation",
               seed_pool="confirmation", claim="predeclared comparison identifier")
```

Without `setups`, confirmation uses the recorded power calculation for 20 Elo.
An append-only, locked `ladder/confirmation-reservations.jsonl` ledger consumes a
never-used block before play. Repeated claims and overlapping blocks are refused,
even after a failed run. Fix the claim, policies, models and sample size before
running. Confirmation has no sequential stopping and supplies the unbiased raw
estimate and ordinary fixed-sample interval.

`luck_correction` enables an **experimental secondary statistic**. It is off by
default and never feeds a stopping decision. The host JVM combines evaluators on
each player's own information into a candidate win probability; library order
is excluded. The pilot compares the public V2 evaluator and optional supplied
linear weights, retaining model hashes. For eligible pure single-card draws it
replays each distinct remaining name, weighted by copies. Opening hands and
mulligan redraws use independent uniform re-permutations. Other randomness and
failed counterfactuals are skipped and counted. Event sampling is chosen before
the outcome. Rows report payoff minus summed luck (beta=1), a beta fitted on the
opposite setup fold, paired variance ratios with bootstrap intervals, event
counts and timing. Corrected values are not clipped. Adoption as the headline
score requires an explicit research decision after the pilot.

For example, `luck_correction={"rate": 0.01, "samples": 8, "models":
[{"name": "v2"}, {"name": "linear", "model": "model.json", "link": "tanh"}]}`
samples steps and openings independently at 1% before observing outcomes.
Retained terms are unweighted: skipped events contribute zero. The default
enabled rate is 1; measure overhead before choosing a rate for compute runs.
The pilot V uses current own-perspective snapshots without history features,
and maps values as `0.5 + (candidate_value - opponent_value) / 4`. Known library
order and transitions that also mill, shuffle, search or reorder a library are
skipped. The pure-draw guard applies to the actual step and every counterfactual.
Hand smoothing is rejected because its opening
distribution is not uniform. A deterministic mirror may have zero raw pair
variance; its paired ratio is then undefined, and the additional game-level
ratio uses setup-pair cluster resampling.
Wall-clock game limits are rejected with correction enabled, so instrumentation
cannot turn an otherwise completed game into a timeout loss; use decision limits.

Rows retain seeds, config, source provenance, model hashes, individual outcomes,
and changed-decision counts. At every candidate decision the incumbent chooses
on the same history without playing it (a *shadow* choice); native comparisons
run inside the JVM. Legacy complete-pair and conservative missing-outcome fields
remain unchanged. New loss-scored and test fields are additive. Callable
policies remain supported for ordinary comparisons; the correction pilot uses
native policies. Give callbacks explicit `name` and `incumbent_name`. Callable
objects are copied per game, so closures must not share mutable policy state.

## Cost measurement

```python
from research_workspace import measure

cost = measure('search', opponent='heuristic', decks=[deck, deck], config=config,
               seeds=[101, 102, 103, 104], warmup_seed=100,
               output=run_dir / 'cost', build=False, jfr=False)
```

`measure` plays in one JVM without an incumbent shadow. It excludes the warm-up
game from CPU per game, searched decisions per game, and CPU per searched
decision. CPU includes both players and game stepping, matching the profiling
harness. Candidate seats alternate over measured games. `complete=False` flags
decision limits, and `jfr=True` records each measured game with the runtime JDK's
`jcmd`.

`measure.json` sums the candidate's `leaf_evaluations` and
`unsettled_leaf_evaluations` (evaluations in [volatile positions](value-models.md#search-use))
and reports their ratio as `unsettled_leaf_fraction`, undefined when there were
no evaluator calls. It also records a SHA-256 fingerprint of each game's full
sequence: every actor and action signature in order, then the outcome, where a
signature binds operation family, intent and canonical payload. Compare
fingerprints, not timings, to check that two runs played identically.

## Resumable game corpora

```python
from research_workspace import run_games, hash_split

def record_game(session, job):
    # Play one game and return an iterable of JSON rows.
    return play_and_record(session, job, split=hash_split(job['seed']))

summary = run_games(jobs, record_game, output=run_dir / 'corpus',
                    plan={'recorder_version': 1, 'config': config,
                          'decks': [deck, deck], 'input_sha256': model_hashes},
                    build=False)
```

`run_games` feeds the fixed job list through a shared queue to long-lived worker
sessions, so faster workers take more games.

**Output.** Each game becomes one compressed `game-NNNNNNNN.jsonl.gz` shard. Its
first line holds the job index, job, error, CPU seconds and row count; each later
line is one data row. Rows are held one game at a time. A shard is fsynced and
atomically renamed only after its game succeeds or fails.

**Resume.** Call the function again with the same output. Finished jobs,
including recorded errors, are skipped by reading only each shard's first line;
unfinished games are replayed. Plan, job order, source provenance and JVM options
must match; the worker count may change. A directory lock prevents concurrent
writers. Format version 2 refuses a version 1 job plan, so leave old runs in
their original directories.

**Callbacks.** Put the callback version and content hashes of external inputs in
`plan`. Callbacks must be thread-safe, deterministic, and free of external write
side effects. A failed game records its error and restarts its session before
the worker continues.

**Summary.** `summary.json` is written before building, after each committed
game, and on normal exit or a caught interruption; after an uncatchable kill it
may lag one shard, and resume rebuilds counts from the shards. It records
counts, CPU, wall time, JVM options and `ladder.source_provenance()`. JVM CPU
covers committed games across resumes; Python CPU and wall time cover the
current invocation, so work lost to a kill is not included.

The first game in a new JVM (each worker's first, or the first after a failure)
is marked `warmup`: class loading and compilation make it cost several times a
later game. The summary reports `warmup_games` and `warmup_jvm_cpu_seconds`
separately from `steady_jvm_cpu_per_game` and `steady_jvm_cpu_per_row`. Project
large runs from the steady figures, not from a short run's totals.

Progress also goes to `MTGALLIUM_PROGRESS_FILE` when set.
`hash_split(key, train=.8, val=.1, salt='')` deterministically assigns train,
validation or test; use the same game key for all of a game's rows to avoid
leakage.

For coarse-grained process and file work, `research_workspace.run` and
`read_data` are also importable with `tools` on `PYTHONPATH`.

## Remote and long-running runs

`tools/remote <name> -- <command…>` copies this working tree, including
uncommitted edits, to `~/mtg-runs/<id>/src` on a remote Linux host (`linuxbox`
unless `MTG_REMOTE_HOST` is set), saves the commit and diff beside it, and starts
the command there under the [durable runner](workbench/durable-runs.md). Check it
with `tools/remote status <id>` or `tools/remote logs <id>`. Each run keeps its
own copy, so later edits never change a running job.

## Hidden-information conformance

`hidden-information` compares fresh native policies on factual positions and
same-information hidden-truth permutations. The default policy list includes
all built-ins and ServiceLoader providers. It does not change their interface.
The public example uses the Mono-Red fixture, heuristic/production play over four
seeds, and a deterministic reservoir of two positions per seat and category
(main phases, attacks, blocks, stack responses, mulligans and other steps).
Coverage counts show which categories actually occurred; missing categories are
not evidence of invariance. The corpus stops at 512 decisions per game.

Run the realistic check separately from `just check`, on a Linux research host:

```bash
tools/remote hidden-information -- python3 tools/mtgallium-research hidden-information \
  examples/research-hidden-information.json ../hidden-information
```

The Kotlin API is `hiddenInformationCorpus`, `checkHiddenInformation`, and
`runHiddenInformationCheck` in `research/workbench`. An explicit provider list
supports test controls. Each permutation assigns card identities among unknown
slots within the same owner's hand/library, including the viewer's unknown
library. The engine rebuilds printed card components coherently and shuffles unknown library slots.
Visible and remembered objects stay fixed, as do engine RNG and history.
Information and knowledge digests, knowledge consistency, and the candidate and
admitted menus must agree. No-op and incompatible proposals are counted as
rejections. The menu is part of each policy's input, so `MENU_DIFFERS` or
`POLICY_MENU_DIFFERS` rejections mean the adapter's menu depends on hidden truth:
investigate them rather than reading them as reduced coverage.

The output includes the plan, corpus coordinates, execution context, one
`policy-N.json` per policy, and a summary. Each finding retains game seed, game
ID, decision index, actor, permutation seed, original/permuted signatures and
candidate visits, means and settlement counts. Reproduce with the saved plan
and source, or select that coordinate from `hiddenInformationCorpus` and pass
it alone to `checkHiddenInformation`. Remote runs retain the source revision
and diff. The example requests 64 simulations, eight particles and depth 32;
reports record providers' actual configured budgets and completed simulations
in findings. Providers with a wall-clock search budget are refused. An unchanged
world is evaluated twice first; disagreement is `NONDETERMINISTIC`, not a leak.

`DECISION_DIFFERS` identifies changed selections; `STATISTICS_DIFFER` identifies
weaker dependence even when the selected action agrees. Errors and zero checked
positions cannot pass. The CLI writes reports before returning failure. A low
acceptance rate means weak coverage, even if accepted comparisons agree.
Do not silently repair a flagged sampler or policy: preserve a same-seed
reproduction and assess the dependency before changing policy identity.

This is evidence, not proof. It tests only sampled positions, permutations and
seeds, with fresh sessions at each position. It does not test accumulated policy
memory, every hidden zone, native-ID renaming, arbitrary malicious providers,
or dependencies that leave these selections and statistics unchanged. Tiny
public tests include a true-hand leaking provider and privileged-world search
as positive controls; neither is installed in production.
