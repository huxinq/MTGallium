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

A setup is two games with the same seed and swapped policy/deck seats. New seeds
come from the operating system; pass `seeds=[...]` with the matching `setups` to
reproduce a run. Rows keep seeds, config, commit, uncommitted diff, engine pin,
hashes of model files (settings named `*_model` other than `opponent_model`),
individual outcomes, and per-game counts of changed decisions. With
`MTGALLIUM_RESEARCH_BUILD` set, they also keep that build's commit and diff.

At every candidate decision, the incumbent also chooses a move on the same
history without playing it (a *shadow* choice); the row counts where the two
differ. Search-based shadows observe accepted moves from game creation. A game
stopped by a limit has no payoff. On an error the row is still written, marked
failed, before the call raises. Unfinished and unexecuted games are counted
separately from wins, losses and draws.

The score averages complete setup pairs. The conservative 95% interval uses
independent setup counts and allows every unfinished game either outcome; the
additional normal interval is approximate and uses complete pair means. Both are
per-comparison intervals. Fix the sample size before running, and for final
claims use a new `phase="confirmation"` call with fresh seeds after development
comparisons.

Candidates, incumbents and opponents can also be Python `Decision -> Action | int`
callbacks; give candidate and incumbent callbacks explicit `name` and
`incumbent_name`. Callable objects are copied per game, so function closures must
not share mutable policy state. Native games and their shadow comparisons run
inside the JVM.

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
