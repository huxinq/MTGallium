# Research quick start

Build the research tools, run the public examples, and play live games from
Python or Kotlin. The [games and CLI reference](research-cli.md) covers
`ResearchGameConfig` settings, native policies, output files and the numerical commands.
[Batch research runs](research-runs.md) covers ladder evaluation, cost
measurement, resumable game corpora and remote execution.

## Build and run

From the checkout, with Python 3 and JDK 21:

```bash
python3 tools/mtgallium-research --help
python3 tools/mtgallium-research build
python3 tools/mtgallium-research games /absolute/private/plan.json /absolute/private/new-run
python3 tools/mtgallium-research show /absolute/private/new-run/results.json
```

Each command first asks Gradle to update the compiled classes. `--no-build`
reuses the last compiled output without checking for source changes. Close live
sessions before rebuilding their classes. JVM arguments belong in `JAVA_OPTS`,
for example `JAVA_OPTS='-Xmx4g'`. Relative paths refer to the caller's working
directory.

Keep private research inputs, replays and results outside the checkout, and keep
each result's [source context](research-runs.md#source-context) with it.

### Watch recorded games

After exporting a recorded run on Linux with `tools/replay_export.py`, copy its
export folder to the private evidence share. On the Mac, start the local viewer
with one command:

```bash
tools/replay-viewer /absolute/private/replay-export
```

Open `http://127.0.0.1:5173/`. The index links to hindsight (both hands shown)
and each player's own view. The decision log beside the board follows the
scrubber. The command installs the pinned web client's npm dependencies when
needed. The exporter verifies each recorded pre-state against a seed-and-action
rebuild. If verification diverges, the index reports the first mismatch and the
viewer continues from the captured states. Exports contain privileged states
and belong only in private evidence.

## Runnable public examples

`examples/research-games.json` is a tiny game fixture. Run it with
`python3 tools/mtgallium-research games examples/research-games.json /absolute/output`.

## Live games from Python

Put `tools` on `PYTHONPATH` and import `research_workspace`. A `Session` builds
once and keeps one JVM alive for its games, branches and numerical calls. From
the repository root:

```bash
PYTHONPATH=tools python3 - <<'PY'
from research_workspace import Session

with Session(java_options=['-Xmx1g']) as research:
    with research.game([{'Mountain': 7}, {'Mountain': 7}], seed=17,
                       starting_hand_size=7, skip_mulligans=True) as game:
        decision = game.decision()
        with game.fork() as branch:
            branch.step(decision.actions[0])
            print(branch.play(decision_limit=40))
        assert game.status()['index'] == decision.index
        rows = []
        result = game.play({'p0': lambda d: d.actions[0]},
                           decision_limit=40, record=rows.append)
        print(result, len(rows))
PY
```

`Game(decks, ...)` is also a context manager that owns its own session; prefer an
explicit `Session` for several games. Closing a game releases its world without
closing sibling games; closing the session ends its JVM. A standalone game's
branches share its session and must finish before the owning game closes.
`research.game` accepts the [`ResearchGameConfig`](research-cli.md#games-plan) settings as
snake_case keyword arguments.

### Decisions and actions

`decision()` returns the acting player's information, the ordered `actions`
menu, the decision index, and two completeness flags: whether the menu covers
every rules-legal action, and whether it covers the configured action profile.
It returns `None` at a terminal state. An action's `family`, `label` and
`payload` are shortcuts into its semantic record.

`step(action)` applies one choice; `step(index)` uses an index into the current
menu. A stale action raises `ResearchError`. The result contains the
accepted-decision record and the new game status; `step(action, record=False)`
skips the record and returns only `status`. `information('p0')` reads that
player's information. `state()` is a **privileged referee inspection** that
includes hidden information.

### Policies

`play` accepts native policy names or Python callbacks. A mapping such as
`{'p0': policy}` replaces only that seat; other seats keep their configured
native policies. A callback receives a `Decision` and returns an `Action` or an
integer index into its menu. An equivalent action taken from another view is
rebound to the supplied menu before recording, so the recorded index and
encodings describe that menu.

`select('heuristic')` or `select('search')` asks a native policy for its choice
without applying it. For search, `action.search` holds the complete search
result, or `None` when the action was rules-forced or the menu's only option.
Native search state keeps observing accepted actions when Python chooses the
move. Pass `policies=('search', 'random')` at game creation when a search
policy's belief must cover the game from the start; requesting search later
initializes its belief from the current world.

`fork()` copies game and native search state; copy Python policy state yourself.

### Frozen kernel inputs

`decision(kernel=True)` adds the historical casting-kernel features retained for
`horizon16`. No new models are trained on this view.

### Connection and limits

The connection is synchronous. Without a recorder, native moves, including
those of a Python policy's opponents, are chosen and applied inside the JVM, and
Python receives full decisions only for its own callbacks; a fully native
`play` without recording is a single JVM call. With `record=callback`, every
move returns its full record.

`decision_limit` and `seconds` bound a `play` call; time is checked between
policy calls. A lost connection closes the session; accepted steps remain
applied. After changing Kotlin source, open a new session to compile and load
it; `build=False` uses compiled output without checking for source changes.

### Measure the game loop

```bash
python3 examples/python-game-throughput.py
python3 examples/python-game-throughput.py --no-build --traffic
```

This compares native play, a Python-controlled seat, and the same mixed game with
recording, on short burn and creature decks. The Python callback asks the native
heuristic for its move, so all paths play the same policy. Each path starts from
the same fork; results, final states and player-history commitments must agree.
Timings cover `play` after warm-up, excluding building and game setup. Python and
JVM CPU time are reported separately; `--traffic` also counts response bytes and
messages, at some measurement cost.

## Kotlin experiments

Kotlin entry points live in
`research/workbench/src/main/kotlin/org/mtgallium/research/workbench`.
Add a `main` there and run its fully qualified class:

```bash
python3 tools/mtgallium-research jvm org.mtgallium.research.workbench.MyExperimentKt input.json
```

Call `createWorld`, `playGame`, `GameAgent`, `selectorPlayer`, `searchPlayer`,
or the frozen `kernelActionFeatures` directly. `playGame` accepts
caller-owned policy callbacks, decision recording, and a privileged research hook
that runs before selection. A `world.fork()` of the actual game is independent
of its parent and keeps the world's accepted-decision numbering. A native search
policy session needs its own fork as well: copying only the world does not copy
the policy's belief.

## Verification

`just check` runs the public tests. Native tests use public fixtures for real
transitions, branches, recording and numerical checks. Python tests cover
builds, arbitrary JVM entry points, file reading, and live games through one JVM,
including Python policies, native search-policy forks, menu binding, limits
and transport cleanup.
