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
shared `JAVA_OPTIONS`: `-Xms64m -Xmx384m -XX:+UseSerialGC
-XX:+ExitOnOutOfMemoryError -XX:ActiveProcessorCount=2`. The default worker count is the smallest of 12,
CPU count minus two (at least one), and available RAM at 1 GiB per worker,
including Linux cgroup v2 memory limits. If not even one worker fits, the call
fails before starting JVMs; unknown platforms use one worker. Explicit `threads`
and `java_options` override these defaults; lower them when other jobs share the
machine. `Session()` and the CLI keep their own defaults.

The worker allowance covers the heap, class metadata, compiled code and native
policies. The heap cap also leaves room for transient search allocations;
post-collection heap samples alone can miss these peaks. A worker that still runs
out of heap exits at once instead of stalling in collection.

Game registries load the decklists' cards, basic lands and tokens from card data
generated during the build. The generator preserves Argentum's catalog order
and checks serialization against each original definition. Game JVMs do not
initialize the full catalog of card-definition classes. Missing deck cards fail
with their names; there is no full-catalog fallback.

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

An `output` row file inside the evidence root's `runs/` is published to the ladder
too, once per row. `tools/mtgallium-research publish <row.json>...` publishes rows
written elsewhere, and skips rows already there. Settings named `*_model` may name
a file or a model directory; a directory is recorded by the hashes of its files.

A setup is two games with the same seed and swapped policy/deck seats. Existing
calls retain their legacy fixed-size behavior and OS-generated seeds. Old rows
are never rewritten. Opt into shared deals with `seed_pool="development"`;
sequential calls select this pool automatically unless explicit seeds are given:

```python
row = evaluate("heuristic", opponents={"random": "random"}, incumbent="random",
               decks=[deck, deck], sequential="improvement")
```

For long comparisons, supply a checkpoint directory. For example, save this
invocation with your run configuration and repeat it unchanged after interruption:

```python
import json
from pathlib import Path
from research_workspace import evaluate

evidence = Path.home() / "Documents/MTGallium-private-evidence"
deck = json.loads((evidence / "decks/my-deck.json").read_text())["mainDeck"]
checkpoint = evidence / "runs/my-comparison/checkpoint"
row = evaluate("heuristic", opponents={"random": "random"}, incumbent="random",
               decks=[deck, deck], sequential="improvement", max_pairs=128,
               evidence_root=evidence, checkpoint=checkpoint)
```

Replace the example deck path with your retained deck file. To monitor from a
second process, define the same `checkpoint` path and read:

```python
progress = json.loads((checkpoint / "progress.json").read_text())["value"]
print(progress["timestamp"], progress["state"], progress["opponents"])
```

This supports native policies with either fixed or sequential comparisons.
Re-run the same invocation and checkpoint directory after interruption: completed
pairs are reused, unfinished assigned pairs are replayed with their original
seeds, and the sequential test is reconstructed in original pair order. A crash
can lose the current pair, but not an already committed pair. Assignments already
in flight when the test stops remain part of the retained result.

The directory is exclusively locked while running. Configuration, model hashes,
source provenance, worker count and output path must match on resume; retained
game errors are not silently retried. Keep the original source snapshot for
recovery. A confirmation resumes its original reservation and claim, never a
fresh seed slice. Repeating a completed invocation returns the saved result
without appending a duplicate ladder row.

The progress document contains each opponent's completed-game count, partial score, ordered-pair count and sequential
test state. It updates after each pair; its timestamp can be stale after a crash.
Partial scores are descriptive and include completed in-flight pairs; they are
not final strength claims. Checkpoint files use atomic replacement, checksums,
and file/directory fsync; use storage with coherent POSIX locking and durability.
Timing in a resumed result covers only the latest invocation, not total run cost.
If a crash tears the final shared ladder append, publication refuses that damaged
ledger; the complete result remains in `result.json` for repair without replay.
Existing calls without `checkpoint` retain their in-memory behavior.

For recovery problems:

- **Checkpoint in use:** verify the original process has stopped; don't remove
  `.lock` or start another writer against a copied checkpoint.
- **Configuration/source mismatch:** use the original checkout or remote snapshot,
  model files, worker count and invocation. Do not edit the checkpoint identity.
- **Missing/corrupt reservation ledger:** restore the authoritative pools and
  ledger together from a consistent backup. Recovery validates the original
  reservation before any games or result publication; a checkpoint alone cannot
  authorize reuse of confirmation seeds.
- **Retained game/session error:** inspect `result.json`, `errors.json`, and pair
  records (each document's payload is under `value`). Errors are retained evidence,
  not an interruption to retry silently. Preserve the failed run and diagnose the
  cause before declaring another comparison; don't delete errors or reuse its
  confirmation claim with a different checkpoint.
- **Torn final ladder append:** stop all writers and back up the ledger and
  checkpoint. Under the same exclusive file lock used by the writers, inspect the
  bytes after the final newline. Only remove that unterminated suffix if it is
  verified as a prefix of the saved result's canonical JSON and identifies this
  checkpoint; preserve every complete row. If ownership is ambiguous, restore a
  consistent ledger backup instead. Then rerun the unchanged invocation: it
  republishes `result.json` without replaying games and suppresses duplicates.
  Never remove a complete row or edit retained game outcomes to repair publication.

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
`max_pairs` caps the comparison. The fixed size for 20 Elo is 1,376 pairs,
derived as `ceil(v * (z(.975) + z(.95))**2 / delta**2)` with pair variance
`v=0.0875` and 95% planning power; non-regression uses its 10-Elo difference.
The default cap is twice that, 2,752 pairs (5,504 games), because the test's
stopping time has a long tail: at the fixed size about 5% of runs at either
design point reach the cap, cutting power from 0.95 to 0.90, while at twice it
0.2% do, for about 4% more expected pairs. Non-regression's cap is limited to
the 8,192-seed development pool (about 1.5 times its fixed size, power about
0.945). These figures come from an exact lattice calculation of the test at
`v=0.0875`. The assumptions and any explicit cap are recorded.

Futility is judged under the design hypotheses, not the running estimate. Each
look computes the Brownian probability that a true H1 would reach the upper
bound, and that a true H0 would reach the lower bound, within the remaining
budget. It stops for futility only when both are below 10%, so a slow start
cannot end a comparison that the budget could still decide. The forecast at the
current drift is still reported but does not stop the test. The normal model
and error targets are approximate.

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

Rows retain seeds, config, source provenance, model hashes, individual outcomes,
and changed-decision counts. At every candidate decision the incumbent chooses
on the same history without playing it (a *shadow* choice); native comparisons
run inside the JVM. Legacy complete-pair and conservative missing-outcome fields
remain unchanged. New loss-scored and test fields are additive. Callable
policies remain supported for ordinary comparisons. Give callbacks explicit `name` and `incumbent_name`. Callable
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

For client-seat stream diagnostics, `SeatStreamAuditTest` compares snapshot and
delta sensitivity to rule-state perturbations. An unchanged snapshot does not
prove that prior events omit the fact. `SeatStreamPropertiesTest` checks private
choices, face-down identities, draw names and resolution context through the
server envelope. Set `SEAT_STREAM_REQUIRE_PRIVATE=1` to enable the desired
hidden-library and hidden-draw-ID invariance contracts, and to require that the
allocation-order recovery witness finds no hidden library card named in the
envelope. They fail at Argentum `3757f6bd` and upstream `3139bebc`, and pass once
the library-order fix (Task 20) is in the engine, including the current
`8037aa92` pin. The completeness audit reports 21 of 51 perturbations visible
in both snapshot and delta; unchanged pairs remain open.

`SeatStreamRecordsTest` is opt-in: pass `SEAT_STREAM_RECORDS` (a directory of
recorded game JSONL gzip files), `SEAT_STREAM_GAME` (one complete tape) and
`SEAT_STREAM_OUTPUT` (a private output directory) through `tools/remote`.
It measures server updates without serialization, then JSON and independent
gzip message sizes. The snapshot pass supplies no events; the transition pass
reapplies accepted choices from each recorded pre-state and includes the emitted
events. This is distinct from seed-and-action replay verification. Hidden swaps
rebuild printed components and report unsupported materializations separately;
the first changed identity swap is written to `identity-swap-counterexample.json`.
Keep these private corpus runs separate from `just check`.

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
