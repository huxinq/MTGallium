# Games and CLI reference

Settings, native policies and output files for `games` runs and live Python
games, plus the numerical file commands. Start with the
[research quick start](research-workbench.md).

## Games plan

The `games` command reads `GamesPlan` JSON:

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

The plan runs two players; game `i` uses seed `seed + i` with the configured seat
assignments. `threads` defaults to CPU count minus two; set fewer when sharing
the machine. `Session.game` takes the same plan: Python converts top-level
snake_case keywords such as `value_weights` to these camelCase names, while
nested `leaf` keys keep their JSON spelling.

Search settings:

- `particles`, `simulations` and `explorationConstant` configure the search.
- `searchDepth` bounds player decisions across the simulated tree and rollout.
- `leaf` is a `LeafEvaluationConfig` (`stateSource`, `cutoff`, `unresolved`,
  `quiescencePasses`), and
  `rolloutTurnHorizon` optionally stops rollouts after a number of turns. The
  default leaf is a bounded rollout that evaluates at its cutoff. See
  [search use](value-models.md#search-use) for all options.
- `valueWeights` is a `LinearWeights` JSON object for the leaf value; omit it to
  use the Mono-Red heuristic. `valueLink` is `clip` (default) or `tanh`, for
  coefficients fitted with a logistic link.
- `opponentModel` is the opponent policy assumed inside search: `mixture`
  (default), `heuristic` or `random`.

## Native policies

`heuristic` and `random` choose directly; `production` plays the action the
Argentum heuristic marks in the menu and stops if no action is marked. `search`
runs information-set search with the plan's search settings.

### Added policies

A separate Gradle build can add native policies without editing this checkout.
It includes this build with `includeBuild`, depends on `:research:workbench`, and
names its `NativePolicyProvider` implementation in
`META-INF/services/org.mtgallium.research.workbench.NativePolicyProvider`. A
provider lists its policy names and the plan settings it reads. Those settings
sit beside the plan fields (`myModel` in JSON, `my_model` from Python);
`GamesPlan` keeps them in `extensions`, and the provider decodes them with
`NativePolicyContext.settings`. Game creation rejects an unknown policy name,
including a shadow, and any setting no provider claims.

A provider returns `NativePolicy.Direct` for a player without memory, or
`NativePolicy.Search` for a `SearchPolicySession`. The game creates a search
session once per player, feeds it accepted moves, forks it with the game and
reports its search as it does for `search`.

Set `MTGALLIUM_RESEARCH_BUILD` to that build's root to use it from Python. Its root
project provides a `researchClasspath` task that writes
`build/research/runtime.json` as this module's task does.

## Output and limits

A new output directory contains the plan and execution context, per-game results,
and a final `results.json` after all games succeed. Optional decision logs contain
the acting player's information, the chosen `selectedIndex`, menu completeness,
and `accepted`. Optional replay logs contain privileged full engine-state
snapshots, including intermediate engine transitions; keep them private.

`maximumDecisions` and `maximumSeconds` may be null. A game stopped by either
limit ends as `DECISION_LIMIT` or `TIME_LIMIT` with `payoffs` null (`None` in
Python); only `TERMINAL` carries game payoffs. The time limit is checked between
decisions and does not interrupt a slow policy call. A policy exception, rejected
transition or recording failure propagates to the caller and leaves completed
files in place.

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
`centeredCandidate`, each stored as aligned `indices` and `values` arrays.
The kernel is `(1 + state·state′) (candidate·candidate′)`. Targets are centered
within each root. By default the loss gives equal mass to each seed group, then
each root, then each action. `fitRootActionKernel` in Kotlin also accepts
explicit positive `actionWeights` and uses them as supplied. Scores are not
clipped.

`predict` takes rows containing `features` and adds `scores` and `predictedIndex`,
keeping other fields such as `selectedIndex`. `encode` normalizes state and
candidate vectors, centers candidates within the menu, and keeps rejection and
completeness information. `rootActionKernelFeatures` reads player schema 6 and
candidate schema 4, from a live decision site or recorded information, with an
optional explicit menu.

`replay-state` reads only up to its requested frame, so a truncated final record
does not affect earlier frames in a plain or gzip stream. A missing or malformed
requested frame fails. In Kotlin, `useJsonLines(path) { records -> ... }` streams
records the same way; build a list only when the computation needs one.
