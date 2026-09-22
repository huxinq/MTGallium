# Direct research tools

## Build and run

From the checkout, with Python 3 and the repository's Gradle/JDK setup:

```bash
python3 tools/mtgallium-research --help
python3 tools/mtgallium-research build
python3 tools/mtgallium-research games /absolute/private/plan.json /absolute/private/new-run
python3 tools/mtgallium-research show /absolute/private/new-run/results.json
```

Normal execution asks Gradle to update the compiled classes and classpath.
`--no-build` reuses the last compiled output without checking for source changes.
Close live sessions before rebuilding their classes. JVM arguments belong in
`JAVA_OPTS`, for example `JAVA_OPTS='-Xmx4g'`. Relative input and output paths refer
to the caller's working directory.

Keep actual private research inputs, replays, and results outside the checkout.
For result metadata and retained records, see [source context](architecture/evidence-and-research.md#source-context).

## Runnable public examples

The checked-in examples are small technical fixtures:

```bash
work=$(mktemp -d)
python3 tools/mtgallium-research fit examples/research-kernel-rows.json "$work/model.json" 0.001
python3 tools/mtgallium-research predict "$work/model.json" examples/research-kernel-rows.json "$work/scores.json"
python3 tools/mtgallium-research games examples/research-games.json "$work/game"
python3 tools/mtgallium-research show "$work/game/results.json"
```

The fit uses artificial targets; the small game uses an intentionally tiny deck.

[`examples/python-value-search.py`](../examples/python-value-search.py) compares
hand-authored value weights at decision horizons of 2 and 8 through the live interface.

## Live games from Python

Use `research_workspace` with `tools` on `PYTHONPATH`. A session builds once and
keeps one JVM alive for its games, branches, and numerical calls. From the repository root:

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

`Game(decks, ...)` is also a context manager that owns its own session. Prefer an
explicit `Session` for several games. Closing one game releases its world without
closing sibling games; closing the session ends its JVM. A standalone game's
branches share its session and must finish before the owning game closes.

`decision()` returns the acting player's represented information, exact ordered
`actions`, decision index, and both rules/menu-profile completeness flags. It
returns `None` at a terminal state. An action's `family`, `label`, and `payload`
are conveniences over its existing semantic record. `step(action)` applies one
choice; `step(index)` uses an index in the current menu. A stale action raises
`ResearchError`. The result contains the accepted-decision record and new
game status. `information('p0')` reads that player's information; `state()` is a
**privileged referee inspection**.

`play` accepts native policy names or Python callbacks. A mapping such as
`{'p0': policy}` replaces only that seat; other seats use their configured native
policies. A Python callback receives a `Decision` and returns an `Action` or an
integer in its supplied menu. An equivalent choice obtained from another view
is rebound to the supplied menu before recording, so its index and optional
encodings describe that same menu. `select('heuristic')` or `select('search')`
queries a native policy without applying its choice. Native search state still
observes accepted actions when Python overrides selection.

`select('search')` returns the usual `Action`; `action.search` is the complete
search result when search ran and `None` for a forced or singleton selection.
`value_features(player=None)` returns the sparse value-feature map for the named
player, or for the acting player when omitted.

`fork()` copies game and native search state; copy Python policy state separately.
Initialize a native search policy at game creation with
`policies=('search', 'random')` when its belief history must run from the start.
Later requesting search initializes its belief from the current world.

`decision(kernel=True)` adds the existing sparse kernel features.
`decision(factual=True)` adds the existing factual byte tensors, the acting
player's encoded event prefix, its `eventPosition`, and encoding schema. A
`from_event` cursor can request a suffix; retain the preceding prefix yourself.
The optional `schema` uses `FactualTensorSchema` fields. Use `record=callback` with
`play(..., kernel=True, factual=True)` to collect the corresponding pre-action
encodings beside the actual accepted action. `research.fit(roots, ridge=...)`
and `research.predict(model, menus)` call the existing kernel routines in that
same JVM using Python dictionaries and lists.

A complete Python-authored collection, branch, training, and live-policy example is:

```bash
python3 examples/python-game-learning.py /absolute/private/new-collection
# With PyTorch in the chosen Python environment:
python examples/python-game-learning.py /absolute/private/new-learning --train --epochs 2
```

It collects heuristic actions on short-deck fixtures as imitation targets, fits
the PyTorch model, and uses its scores to select live game actions.

The connection is synchronous and sequential. Fully native `play` without a
Python recorder stays inside the JVM; Python callbacks cross the pipe at each
decision.
`decision_limit` and `seconds` bound a `play` call; time is checked between
policy calls. A lost connection closes the session; accepted steps remain applied.
After changing Kotlin source, create
a new session to compile and load it. `build=False` explicitly uses compiled output
without checking source changes.

## Games

The convenience `games` command reads `GamesPlan` JSON:

```json
{
  "decks": [{"Mountain": 8}, {"Mountain": 8}],
  "policies": ["search", "random"],
  "seed": 11,
  "games": 1,
  "threads": 1,
  "startingHandSize": 2,
  "skipMulligans": true,
  "particles": 1,
  "simulations": 2,
  "searchDepth": 2,
  "explorationConstant": 1.4,
  "leaf": {"stateSource": "BOUNDED_ROLLOUT"},
  "opponentModel": "mixture",
  "maximumDecisions": 8,
  "recordDecisions": true,
  "recordReplay": true
}
```

Built-in policies are `heuristic`, `random`, and `search`. The plan runs two players;
each game gets `seed + gameIndex` with the configured seat assignments.

`GamesPlan` is shared by the CLI and `Session.game`. Python converts top-level
snake_case keyword names to the plan's camelCase JSON names; nested `leaf` keys
remain `stateSource`, `cutoff`, and `unresolved`. Search settings are
`valueWeights` (or `value_weights`), `explorationConstant`, `leaf`, optional
`rolloutTurnHorizon`, and `opponentModel`. Omit `valueWeights` to use the visible
heuristic, or supply a `LinearWeights` JSON object with required `weights` and
optional `bias`. `opponentModel` is `mixture` by default, with `heuristic` and
`random` also available.

The default leaf is `LeafEvaluationConfig(BOUNDED_ROLLOUT)`, whose cutoff and
unresolved handling both default to `EVALUATE`. Cutoffs are `EVALUATE`,
`QUIESCENCE`, and `POLICY_QUIESCENCE`; the latter two require a bounded rollout.
Set `unresolved` to `BACK_UP_NEUTRAL` to record a neutral unresolved settlement.
`searchDepth` bounds player decisions across the simulated tree and rollout.
`rolloutTurnHorizon` is optional and takes `completedTurns` plus optional
`maxPolicyDecisions` (512 by default): it stops at the first player decision with
`turnNumber >= rootTurnNumber + completedTurns`. The decision count is a safety limit.
It requires a bounded rollout with `EVALUATE` cutoff.

A new output directory contains the plan and execution context, per-game results,
and a final `results.json` after all games succeed. Optional decision logs contain
the acting player's information, observed `selectedIndex`, menu completeness, and
`accepted`. Optional replay logs contain privileged full engine-state snapshots,
including intermediate engine transitions.

At a decision limit, `play` returns `payoffs=None`. `TERMINAL` carries actual
game payoffs; `TIME_LIMIT` also returns `payoffs=None`. `maximumDecisions` and
`maximumSeconds` may be null. The time limit is checked between decisions and does
not interrupt an expensive policy call. A policy exception, rejected transition,
or recording failure propagates and leaves completed files available.

## Fit, score, encode, and read

```bash
python3 tools/mtgallium-research fit roots.json model.json 0.001
python3 tools/mtgallium-research predict model.json menus.json predictions.json
python3 tools/mtgallium-research encode decisions.jsonl.gz features.json
python3 tools/mtgallium-research replay-state replay.jsonl.gz 3 state.json
python3 tools/mtgallium-research show any-producers-data.jsonl.gz
```

`fit` takes an array of `RootActionKernelTrainingRoot` values: `rootId`,
`seedGroupId`, `features`, and `actionMeans`. Each feature has a sparse `state` and
`centeredCandidate`, each represented by aligned `indices` and `values` arrays.
The kernel is `(1 + state·state′) (candidate·candidate′)`. Targets are centered
within each root. Default loss mass is equal per group, then per root, then per
action. The callable fitter also accepts explicit positive action weights; they
are used as supplied, not silently normalized. Scores are not clipped.

`predict` takes rows containing `features` and adds `scores` and `predictedIndex`.
It retains other fields, including `selectedIndex`. `encode` normalizes state and
candidate vectors, centers candidates within the menu, and retains
rejection/completeness information.

`rootActionKernelFeatures` consumes the current player schema 6 and candidate
schema 4 contract with a live decision site or recorded information and an
optional explicit menu.

`replay-state` reads only through its requested frame. A later unfinished JSON
record does not invalidate an already-complete earlier frame in a readable plain
or gzip stream. A missing or malformed requested frame still fails. The callable
`useJsonLines(path) { records -> ... }` similarly consumes records inside a scoped
stream; materialize a list explicitly only when the computation needs it.

## Kotlin experiments

Kotlin entry points live in
`research/workbench/src/main/kotlin/org/mtgallium/research/workbench`.
Add an ordinary `main` there and run its fully qualified class:

```bash
python3 tools/mtgallium-research jvm org.mtgallium.research.workbench.MyExperimentKt input.json
```

Call `createWorld`, `playGame`, `Player`, `selectorPlayer`, `searchPlayer`,
`rootActionKernelFeatures`, or `fitRootActionKernel` directly. `playGame` accepts
caller-owned policy callbacks, decision recording, and a privileged research hook
before selection. A factual `world.fork()` is independent of its parent; continuing
or branching preserves the world's existing accepted-decision coordinates. A
stateful native search-policy session also needs its explicit factual-continuation
fork; copying only the world does not clone a policy's belief state.

Python experiments can use the [live interface](#live-games-from-python), or import
`research_workspace.run` and `read_data` with `tools` on `PYTHONPATH` for coarse-grained
process/file work. The [durable runner](workbench/durable-runs.md) can retain a long-running
foreground command and its logs.

## Verification

`just check` runs the public tests. Native tests use public
fixtures for real transitions, factual branches, recording, and numerical checks;
Python tests exercise normal builds, arbitrary JVM entry points and arguments,
file reading, and real live-game journeys through one JVM, including Python policies,
native search-policy forks, menu binding, factual encodings, limits, and transport cleanup.
Neural training, optional ONNX export, and CUDA checks have separate commands in
the neural guide.
