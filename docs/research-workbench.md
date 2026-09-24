# Research quick start

Build the research tools, run the public examples, and play live games from
Python or Kotlin. The [games and CLI reference](research-cli.md) covers
`GamesPlan` settings, native policies, output files and the numerical commands.
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

## Runnable public examples

The checked-in examples are small technical fixtures:

```bash
work=$(mktemp -d)
python3 tools/mtgallium-research fit examples/research-kernel-rows.json "$work/model.json" 0.001
python3 tools/mtgallium-research predict "$work/model.json" examples/research-kernel-rows.json "$work/scores.json"
python3 tools/mtgallium-research games examples/research-games.json "$work/game"
python3 tools/mtgallium-research show "$work/game/results.json"
```

The fit uses artificial targets; the game uses an intentionally tiny deck.

[`examples/python-value-search.py`](../examples/python-value-search.py) compares
hand-written value weights at search horizons of 2 and 8 decisions through the
live interface.

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
`research.game` accepts the [`GamesPlan`](research-cli.md#games-plan) settings as
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

`value_features(player=None)` returns the sparse value-feature map for the named
player, or for the acting player when omitted. `value_score(player, weights,
link='clip')` scores that player's current information with the JVM linear
evaluator and returns `rawScore` and `deployedValue`; use `link='tanh'` for
weights fitted with a logistic link. See [value models](value-models.md).

### Learning inputs

`decision(kernel=True)` adds sparse kernel features. `decision(factual=True)` adds
the byte tensors used by [neural policies](neural-policy.md): the current view
and actions, the acting player's encoded event history so far, its
`eventPosition`, and the encoding schema. Pass `from_event` to receive only later
events; keep the earlier ones yourself. The optional `schema` takes
`FactualTensorSchema` fields. `play(..., kernel=True, factual=True,
record=callback)` collects the same encodings before each accepted action.
`research.fit(roots, ridge=...)` and `research.predict(model, menus)` run the
[kernel routines](research-cli.md#fit-score-encode-and-read) in the same JVM on
Python dictionaries and lists.

A complete Python example collects decisions, branches a game, trains a model
and plays with it:

```bash
python3 examples/python-game-learning.py /absolute/private/new-collection
# With PyTorch in the chosen Python environment:
python examples/python-game-learning.py /absolute/private/new-learning --train --epochs 2
```

It records the heuristic's choices on short-deck fixtures as imitation targets,
fits the PyTorch model, and uses its scores to choose live actions.

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

Call `createWorld`, `playGame`, `Player`, `selectorPlayer`, `searchPlayer`,
`rootActionKernelFeatures`, or `fitRootActionKernel` directly. `playGame` accepts
caller-owned policy callbacks, decision recording, and a privileged research hook
that runs before selection. A `world.fork()` of the actual game is independent
of its parent and keeps the world's accepted-decision numbering. A native search
policy session needs its own fork as well: copying only the world does not copy
the policy's belief.

## Verification

`just check` runs the public tests. Native tests use public fixtures for real
transitions, branches, recording and numerical checks. Python tests cover
builds, arbitrary JVM entry points, file reading, and live games through one JVM,
including Python policies, native search-policy forks, menu binding, factual
encodings, limits and transport cleanup. Neural training, ONNX export and CUDA
checks have [separate commands](neural-policy.md#verification).
