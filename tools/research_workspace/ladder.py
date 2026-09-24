"""Paired, reproducible policy comparisons for exploratory research.

Python policy objects are deep-copied for every game.  Their closures must therefore
be stateless or independently copyable; mutable closure state is otherwise shared.
"""
from __future__ import annotations

from .resources import JAVA_OPTIONS, default_workers

from collections import Counter
from collections.abc import Callable, Mapping, Sequence
import copy
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import fcntl
import hashlib
import inspect
import json
import math
import os
from pathlib import Path
import secrets
import statistics
import subprocess
import threading
import time
from typing import Any

from . import REPO, research_build, runtime
from .game import Action, Decision, ResearchError, Session
from .ladder_stream import stream_pairs
from .ladder_checkpoint import checkpointed

Policy = str | Callable[[Decision], Action | int]


def _name(policy: Policy, supplied: str | None, role: str) -> str:
    if isinstance(policy, str):
        return supplied or policy
    if not callable(policy):
        raise TypeError(f'{role} must be a native policy name or callable')
    if not supplied:
        raise ValueError(f'{role} name is required for a Python callback')
    return supplied


def _descriptor(policy: Policy) -> dict[str, Any]:
    if isinstance(policy, str):
        return {'kind': 'native', 'native': policy}
    descriptor = {'kind': 'python', 'module': getattr(policy, '__module__', None),
                  'qualname': getattr(policy, '__qualname__', type(policy).__qualname__)}
    try:
        descriptor['source'] = inspect.getsource(policy)
    except (OSError, TypeError):
        descriptor['source'] = None
    provenance = getattr(policy, 'provenance', None)
    if provenance is not None:
        descriptor['provenance'] = json.loads(json.dumps(provenance, allow_nan=False))
    return descriptor


def _rebind(decision: Decision, selected: Action | int, role: str) -> Action:
    if type(selected) is int:
        if selected < 0 or selected >= len(decision.actions):
            raise IndexError(f'{role} selected an index outside its menu')
        return decision.actions[selected]
    if not isinstance(selected, Action) or selected.index != decision.index:
        raise ValueError(f'{role} action must belong to the current decision')
    rebound = next((action for action in decision.actions
                    if _choice_key(action.choice) == _choice_key(selected.choice)), None)
    if rebound is None:
        raise ValueError(f'{role} action is absent from the supplied decision menu')
    return rebound


def _choice_key(choice: Mapping[str, Any]) -> tuple[Any, Any, str]:
    """Semantic identity without optional policy display annotations."""
    return (choice.get('operationFamily'), choice.get('signature'),
            json.dumps(choice.get('canonicalPayload'), sort_keys=True, separators=(',', ':')))


def _select(game: Any, policy: Policy, decision: Decision, role: str) -> Action:
    selected = game.select(policy) if isinstance(policy, str) else policy(
        copy.deepcopy(decision) if role == 'incumbent' else decision)
    return _rebind(decision, selected, role)


def _game(session: Session, candidate: Policy, incumbent: Policy, opponent: Policy,
          decks: tuple[dict, dict], seed: int, candidate_seat: int, settings: dict) -> dict:
    # Each game owns fresh callback objects. A named incumbent is shadowed at creation so
    # search memory sees actions selected by Python; memoryless shadows cost nothing.
    candidate, incumbent, opponent = map(copy.deepcopy, (candidate, incumbent, opponent))
    policies: list[Policy] = [opponent, opponent]
    policies[candidate_seat] = candidate
    initial = tuple(policy if isinstance(policy, str) else 'random' for policy in policies)
    game_settings = copy.deepcopy(settings)
    delivery = game_settings.pop('_factual_delivery', {})
    luck_config = game_settings.pop('_ladder_luck_correction', None)
    if luck_config is not None:
        if not all(isinstance(policy, str) for policy in (candidate, incumbent, opponent)):
            raise ValueError('Host luck correction requires native policies')
        game_settings['luck_correction'] = luck_config
    luck = None
    if isinstance(incumbent, str):
        shadows = list(game_settings.get('shadow_policies', ()))
        if incumbent not in shadows:
            shadows.append(incumbent)
        game_settings['shadow_policies'] = shadows
    ordered_decks = decks if candidate_seat == 0 else (decks[1], decks[0])
    counts = {'candidate': 0, 'changed': 0}

    with session.game(ordered_decks, seed=seed, policies=initial, **game_settings) as game:
        limit = settings.get('maximum_decisions', settings.get('maximumDecisions', 2048))
        if all(isinstance(policy, str) for policy in (candidate, incumbent, opponent)):
            comparison = game._call('compare', candidateSeat=f'p{candidate_seat}',
                                    incumbent=incumbent, maximumDecisions=limit,
                                    maximumSeconds=settings.get('maximum_seconds', settings.get('maximumSeconds')),
                                    **({} if luck_config is None else {'luckCorrection': luck_config}))
            result = comparison['result']
            luck = comparison.get('luck')
            counts = {'candidate': comparison['candidateDecisions'],
                      'changed': comparison['changedDecisions']}
        else:
            def compared(decision: Decision) -> Action:
                baseline = _select(game, incumbent, decision, 'incumbent')
                chosen = _select(game, candidate, decision, 'candidate')
                counts['candidate'] += 1
                counts['changed'] += _choice_key(chosen.choice) != _choice_key(baseline.choice)
                return chosen

            played: list[Policy] = [opponent, opponent]
            played[candidate_seat] = compared
            result = game.play(played, decision_limit=limit,
                               seconds=settings.get('maximum_seconds', settings.get('maximumSeconds')),
                               **delivery)
    payoff = None if result['payoffs'] is None else (result['payoffs'][f'p{candidate_seat}'] + 1) / 2
    return {'seed': seed, 'candidate_seat': f'p{candidate_seat}', 'status': result['status'],
            'payoff': payoff, 'candidate_decisions': counts['candidate'],
            'changed_decisions': counts['changed'], **({} if luck is None else {'luck': luck})}


def _summarize(name: str, games: list[dict], setup_count: int) -> dict:
    terminal = [game for game in games if game['status'] == 'TERMINAL']
    wins = sum(game['payoff'] == 1 for game in terminal)
    draws = sum(game['payoff'] == .5 for game in terminal)
    losses = sum(game['payoff'] == 0 for game in terminal)
    by_seed: dict[int, list[dict]] = {}
    for game in games:
        by_seed.setdefault(game['seed'], []).append(game)
    complete = [sum(g['payoff'] for g in pair) / 2 for pair in by_seed.values()
                if len(pair) == 2 and all(g['payoff'] is not None for g in pair)]
    known = sum(g['payoff'] for g in games if g['payoff'] is not None)
    missing = sum(g['payoff'] is None for g in games)
    epsilon = math.sqrt(math.log(40) / (2 * setup_count))
    normal = [0, 1]
    if len(complete) >= 2:
        half = 1.96 * statistics.stdev(complete) / math.sqrt(len(complete))
        normal = [max(0, statistics.mean(complete) - half),
                  min(1, statistics.mean(complete) + half)]
    measured = [g for g in games if g['candidate_decisions'] is not None
                and g['changed_decisions'] is not None]
    decisions = sum(g['candidate_decisions'] for g in measured)
    changes = sum(g['changed_decisions'] for g in measured)
    return {'name': name, 'games': len(games),
            'executed_games': sum(g['status'] != 'UNEXECUTED' for g in games),
            'independent_setups': setup_count,
            'complete_setups': len(complete), 'terminal_games': {'wins': wins, 'draws': draws, 'losses': losses},
            'incomplete_games': dict(sorted(Counter(g['status'] for g in games
                                                   if g['status'] != 'TERMINAL').items())),
            'candidate_decisions': decisions, 'changed_decisions': changes,
            'changed_decisions_per_game': changes / len(measured) if measured else None,
            'decision_counts_missing_games': len(games) - len(measured),
            'complete_pair_score': None if not complete else statistics.mean(complete),
            'known_outcomes': len(games) - missing, 'missing_outcomes': missing,
            'missing_outcome_bounds': [known / (2 * setup_count), (known + missing) / (2 * setup_count)],
            'fixed_sample_hoeffding_95': {'method': 'two-sided Hoeffding, alpha=0.05, missing outcomes bounded in [0,1]; fixed sample required',
                                       'lower': max(0, known / (2 * setup_count) - epsilon),
                                       'upper': min(1, (known + missing) / (2 * setup_count) + epsilon)},
            'complete_pair_normal_approx_95': {'method': 'normal approximation from complete pair means',
                                           'interval': normal, 'n': len(complete)},
            'raw_games': sorted(games, key=lambda game: (game['seed'], game['candidate_seat']))}


def _pair_values(games: list[dict]) -> list[float]:
    pairs: dict[int, list[dict]] = {}
    for game in games:
        pairs.setdefault(game['seed'], []).append(game)
    return [sum(game['payoff'] if game['payoff'] is not None else 0 for game in pair) / 2
            for pair in pairs.values() if len(pair) == 2
            and all(game['status'] not in ('ERROR', 'UNEXECUTED') for game in pair)]


def _loss_scored_pairs(games: list[dict]) -> dict:
    values = _pair_values(games)
    mean = statistics.mean(values) if values else None
    half = 1.959963984540054 * statistics.stdev(values) / math.sqrt(len(values)) if len(values) > 1 else None
    return {'n': len(values), 'mean': mean,
            'normal_approx_95': [max(0, mean - half), min(1, mean + half)] if half is not None else [0, 1],
            'interval_method': 'fixed sample normal approximation; unfinished games are candidate losses'}


def _git(*arguments: str, cwd: Path = REPO) -> str:
    return subprocess.run(['git', '-C', str(cwd), *arguments], check=True,
                          text=True, stdout=subprocess.PIPE).stdout.rstrip('\n')


def source_provenance(engine: Path | None = None) -> dict:
    """Commit, diff and engine identity of this checkout, or of the snapshot named by
    MTG_SOURCE_JSON (written by tools/remote, whose copies carry no Git metadata)."""
    recorded = os.environ.get('MTG_SOURCE_JSON')
    if recorded:
        return json.loads(Path(recorded).read_text())
    engine = engine or REPO / 'third_party/argentum-engine'
    result = {'commit': _git('rev-parse', 'HEAD'), 'diff': _git('diff', 'HEAD', '--binary'),
              'status': _git('status', '--porcelain=v1'),
              'engine_head': subprocess.run(['git', '-C', str(engine), 'rev-parse', 'HEAD'], check=True,
                                            text=True, stdout=subprocess.PIPE).stdout.strip(),
              'engine_pin': _git('ls-tree', 'HEAD', 'third_party/argentum-engine').split()[2]}
    build = research_build()
    if build is not None:
        result['research_build'] = {'path': str(build), 'commit': _git('rev-parse', 'HEAD', cwd=build),
                                    'diff': _git('diff', 'HEAD', '--binary', cwd=build),
                                    'status': _git('status', '--porcelain=v1', cwd=build)}
    return result


def _append(path: Path, row: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    encoded = json.dumps(row, allow_nan=False, separators=(',', ':')) + '\n'
    with path.open('a', encoding='utf-8') as stream:
        fcntl.flock(stream, fcntl.LOCK_EX)
        stream.write(encoded)
        stream.flush()
        os.fsync(stream.fileno())
        fcntl.flock(stream, fcntl.LOCK_UN)


@checkpointed
def evaluate(candidate: Policy, *, name: str | None = None, opponents: Mapping[str, Policy],
             incumbent: Policy, incumbent_name: str | None = None,
             decks: Sequence[Mapping[str, int]], setups: int | None = None,
             seeds: Sequence[int] | None = None, threads: int | None = None,
             config: Mapping[str, Any] | None = None, output: str | Path | None = None,
             build: bool = True, java_options: Sequence[str] = JAVA_OPTIONS,
             phase: str = 'exploration', seed_pool: str | None = None,
             sequential: str | None = None, max_pairs: int | None = None,
             claim: str | None = None, pool_start: int | None = None,
             evidence_root: str | Path | None = None,
             luck_correction: Mapping[str, Any] | None = None, factual: bool = False,
             schema: Mapping | None = None, include_events: bool = True,
             checkpoint: str | Path | None = None) -> dict:
    """Evaluate paired seat swaps; checkpoint enables durable native-policy recovery."""
    if luck_correction is not None and any(
            (config or {}).get(key) is not None for key in ('maximum_seconds', 'maximumSeconds')):
        raise ValueError('Luck correction does not support time limits; use maximum_decisions')
    candidate_name = _name(candidate, name, 'candidate')
    incumbent_name = _name(incumbent, incumbent_name, 'incumbent')
    if not isinstance(opponents, Mapping) or not opponents:
        raise ValueError('opponents must be a nonempty mapping')
    if len(decks) != 2:
        raise ValueError('decks must contain exactly two decks')
    if setups is None:
        if phase == 'confirmation' and (claim is not None or seed_pool is not None):
            from .ladder_statistics import fixed_confirmation_size
            setups = fixed_confirmation_size()
        else:
            setups = 64
    if type(setups) is not int or setups <= 0:
        raise ValueError('setups must be positive')
    threads = default_workers() if threads is None else threads
    if type(threads) is not int or threads <= 0:
        raise ValueError('threads must be positive')
    root = Path(evidence_root or os.environ.get('MTGALLIUM_PRIVATE_EVIDENCE_ROOT',
                                              str(Path.home() / 'Documents/MTGallium-private-evidence')))
    modern = sequential is not None or seed_pool is not None or claim is not None
    if sequential is not None and phase == 'confirmation':
        raise ValueError('Confirmation must use a predeclared fixed sample')
    if seed_pool == 'confirmation' and (phase != 'confirmation' or not claim):
        raise ValueError('Confirmation pools require phase="confirmation" and a predeclared claim')
    if phase == 'confirmation' and seed_pool == 'development':
        raise ValueError('Confirmation cannot use development seeds')
    if max_pairs is not None and sequential is None:
        raise ValueError('max_pairs requires sequential mode; use setups for fixed samples')
    pool_metadata = None
    tests = None
    if sequential is not None:
        from .ladder_statistics import SequentialTest
        tests = {key: SequentialTest(mode=sequential, max_pairs=max_pairs) for key in opponents}
        setups = next(iter(tests.values())).max_pairs
        if seeds is None:
            seed_pool = seed_pool or 'development'
    if claim is not None:
        if phase != 'confirmation':
            raise ValueError('A confirmation claim requires phase="confirmation"')
        if seeds is not None:
            raise ValueError('Explicit seeds reproduce runs; they cannot reserve a new confirmation claim')
        seed_pool = seed_pool or 'confirmation'
    for opponent_name, opponent in opponents.items():
        _name(opponent, opponent_name, 'opponent')

    settings = copy.deepcopy(dict(config or {}))
    if '_factual_delivery' in settings:
        raise ValueError('Pass factual delivery options through evaluate, not config')
    if schema is not None and not factual:
        raise ValueError('A factual schema requires factual=True')
    if factual:
        settings['_factual_delivery'] = dict(factual=True, schema=copy.deepcopy(schema),
                                             include_events=include_events)
    if luck_correction is not None:
        if not all(isinstance(policy, str) for policy in (candidate, incumbent, *opponents.values())):
            raise ValueError('The luck pilot currently requires native policies')
        settings['_ladder_luck_correction'] = copy.deepcopy(dict(luck_correction))
    if {'seed', 'policies', 'decks', 'games', 'threads'} & settings.keys():
        raise ValueError('Pass seeds, policies, decks and threads through evaluate, not config')
    model_files = {}
    if luck_correction is not None:
        for index, model in enumerate(settings['_ladder_luck_correction'].get('models', [])):
            if 'model' in model:
                if 'weights' in model:
                    raise ValueError('Supply a luck model file or inline weights, not both')
                path = Path(model.pop('model')).resolve()
                content = path.read_bytes()
                model['weights'] = json.loads(content)
                model_files[f'luck_model_{index}'] = {
                    'path': str(path), 'sha256': hashlib.sha256(content).hexdigest()}
    for key in sorted(settings):
        # Settings named *_model are model files, except the opponent model's name.
        if key.endswith('_model') and key != 'opponent_model' and settings[key] is not None:
            path = Path(settings[key]).resolve()
            settings[key] = str(path)
            with path.open('rb') as stream:
                model_files[key] = {'path': str(path), 'sha256': hashlib.file_digest(stream, 'sha256').hexdigest()}
    deck_snapshot = tuple(copy.deepcopy(dict(deck)) for deck in decks)
    total_started = time.monotonic()
    started_at = datetime.now(timezone.utc).isoformat()
    source = source_provenance()
    candidate_descriptor = {'name': candidate_name, **_descriptor(candidate)}
    incumbent_descriptor = {'name': incumbent_name, **_descriptor(incumbent)}
    opponent_descriptors = {key: _descriptor(value) for key, value in opponents.items()}
    if output is None:
        output = root / 'ladder/ladder.jsonl'
    saved_plan = None
    if checkpoint is not None:
        if not all(isinstance(policy, str) for policy in (candidate, incumbent, *opponents.values())):
            raise ValueError('Resumable comparisons currently require native policies')
        checkpoint.prepare(dict(candidate=candidate_descriptor, incumbent=incumbent_descriptor,
            opponents=opponent_descriptors, opponent_order=list(opponents), decks=deck_snapshot,
            settings=settings, models=model_files, source=source, setups=setups, threads=threads,
            seeds=list(seeds) if seeds is not None else None, seed_pool=seed_pool,
            sequential=sequential, max_pairs=max_pairs, claim=claim, pool_start=pool_start,
            phase=phase, java_options=list(java_options), java_opts=os.environ.get('JAVA_OPTS', ''),
            evidence_root=str(root.resolve()), output=str(Path(output).resolve())))
        retained = checkpoint.read('result.json')
        if retained is not None:
            retained = checkpoint.publish(output, retained)
            if retained['state'] == 'failed':
                raise ResearchError('Checkpoint retains a failed comparison; refusing to replay outcomes')
            return retained
        saved_plan = checkpoint.read('plan.json')
    if saved_plan is not None:
        pool_metadata = saved_plan['pool_metadata']
        seeds = saved_plan['seeds']
        started_at = saved_plan['started_at']
    elif seed_pool is not None:
        if seeds is not None:
            raise ValueError('Use either explicit seeds or a seed pool')
        from .ladder_statistics import allocate
        declaration = None
        if seed_pool == 'confirmation':
            from .ladder_statistics import planning_design
            declaration = {'candidate': candidate_descriptor, 'incumbent': incumbent_descriptor,
                           'opponents': opponent_descriptors, 'decks': list(deck_snapshot),
                           'settings': settings, 'model_files': model_files, 'source': source,
                           'setups': setups, 'planning_design': planning_design()}
            if checkpoint is not None:
                declaration['checkpoint_id'] = checkpoint.id
        pool_metadata = allocate(root, seed_pool, setups, claim=claim, start=pool_start,
                                 declaration=declaration, resume=checkpoint is not None)
        seeds = pool_metadata['seeds']
    elif pool_start is not None:
        raise ValueError('pool_start requires a seed pool')
    if seeds is None:
        seed_values: list[int] = []
        while len(seed_values) < setups:
            value = secrets.randbits(63)
            if value not in seed_values:
                seed_values.append(value)
    else:
        seed_values = list(seeds)
        if len(seed_values) != setups or len(set(seed_values)) != setups:
            raise ValueError('seeds must contain one unique seed per setup')
        if any(type(seed) is not int or not 0 <= seed < 2**63 for seed in seed_values):
            raise ValueError('seeds must be 63-bit nonnegative integers')
    if checkpoint is not None and saved_plan is None:
        checkpoint.write('plan.json', dict(seeds=seed_values, pool_metadata=pool_metadata,
                                          started_at=started_at))
    build_started = total_started
    runtime(build=build)  # Resolve/build once, before any worker starts a JVM.
    build_elapsed = time.monotonic() - build_started
    jobs = [(opponent_name, opponent, seed) for opponent_name, opponent in opponents.items()
            for seed in seed_values]
    worker_count = min(threads, len(jobs))
    batches = [jobs[index::worker_count] for index in range(worker_count)]
    started = time.monotonic()
    stop = threading.Event()

    def unfinished(seed: int, seat: int, status: str, error: Exception | None = None) -> dict:
        row = {'seed': seed, 'candidate_seat': f'p{seat}', 'status': status, 'payoff': None,
               'candidate_decisions': None, 'changed_decisions': None}
        if error is not None:
            row['error'] = {'type': type(error).__name__, 'message': str(error)}
        return row

    def run_batch(batch: list[tuple[str, Policy, int]]) -> list[tuple[str, dict]]:
        rows = []
        slots = [(opponent_name, opponent, seed, seat) for opponent_name, opponent, seed in batch
                 for seat in (0, 1)]
        try:
            with Session(build=False, java_options=java_options) as session:
                for opponent_name, opponent, seed, seat in slots:
                    if stop.is_set():
                        rows.append((opponent_name, unfinished(seed, seat, 'UNEXECUTED')))
                        continue
                    try:
                        result = _game(session, candidate, incumbent, opponent,
                                       deck_snapshot, seed, seat, settings)
                    except Exception as error:
                        stop.set()
                        result = unfinished(seed, seat, 'ERROR', error)
                    rows.append((opponent_name, result))
        except Exception as error:
            stop.set()
            completed_slots = len(rows)
            if completed_slots == len(slots) and rows:
                # Cleanup failure does not erase an already observed terminal outcome.
                rows[-1][1]['session_error'] = {'type': type(error).__name__, 'message': str(error)}
            for index, (opponent_name, _opponent, seed, seat) in enumerate(slots[completed_slots:]):
                status = 'ERROR' if index == 0 else 'UNEXECUTED'
                rows.append((opponent_name, unfinished(seed, seat, status,
                                                       error if status == 'ERROR' else None)))
        return rows

    stream_errors = []
    if tests is None and checkpoint is None:
        with ThreadPoolExecutor(max_workers=worker_count) as executor:
            completed = [item for batch in executor.map(run_batch, batches) for item in batch]
    else:
        def play_pair(session, opponent_name, index):
            pair = []
            for seat in (0, 1):
                try:
                    pair.append(_game(session, candidate, incumbent, opponents[opponent_name],
                                      deck_snapshot, seed_values[index], seat, settings))
                except Exception as error:
                    pair.append(unfinished(seed_values[index], seat, 'ERROR', error))
            return pair
        completed, stream_errors = stream_pairs(
            opponents, setups, worker_count,
            lambda: Session(build=False, java_options=java_options), play_pair, tests, checkpoint)
    elapsed = time.monotonic() - started
    grouped = {opponent_name: [] for opponent_name in opponents}
    for opponent_name, game in completed:
        grouped[opponent_name].append(game)
    failed = bool(stream_errors) or any(game['status'] == 'ERROR' or 'session_error' in game
                                       for _, game in completed)
    row = {'timestamp': started_at, 'phase': phase, 'state': 'failed' if failed else 'completed',
           'candidate': candidate_descriptor,
           'incumbent': incumbent_descriptor,
           'config': {'decks': list(deck_snapshot), 'settings': settings, 'threads': threads,
                      'seeds': seed_values, 'build': build, 'java_options': list(java_options),
                      'model_files': model_files, 'java_opts_environment': os.environ.get('JAVA_OPTS', '')},
           'source': source,
           'timings': {'build_seconds': build_elapsed, 'evaluation_seconds': elapsed,
                       'total_seconds': time.monotonic() - total_started},
           'opponents': [dict(_summarize(opponent_name, grouped[opponent_name],
                                        setups if tests is None else max(1, len({game['seed'] for game in grouped[opponent_name]}))),
                              policy=opponent_descriptors[opponent_name])
                         for opponent_name in opponents]}
    if modern:
        row['config']['seed_pool'] = pool_metadata
        row['config']['comparison_method'] = 'sequential' if tests is not None else 'fixed'
        row['stream_errors'] = stream_errors
        for summary in row['opponents']:
            games = grouped[summary['name']]
            summary['unfinished_candidate_losses'] = sum(
                game['payoff'] is None and game['status'] not in ('UNEXECUTED', 'ERROR') for game in games)
            summary['loss_scored_pairs'] = _loss_scored_pairs(games)
            if tests is not None:
                summary['planned_setups'] = setups
                summary['independent_setups'] = len({game['seed'] for game in games})
                summary['fixed_sample_hoeffding_95'] = {
                    'method': 'not applicable after sequential stopping', 'lower': None, 'upper': None}
                summary['complete_pair_normal_approx_95']['method'] = 'descriptive only after sequential stopping'
                summary['loss_scored_pairs']['interval_method'] = 'descriptive only after sequential stopping; includes in-flight pairs'
                summary['test'] = tests[summary['name']].result()
                summary['in_flight_games'] = sum(game['pool_position'] >= summary['test']['pairs'] for game in games)
                for game in summary['raw_games']:
                    game['test_prefix'] = game['pool_position'] < summary['test']['pairs']
                    if pool_metadata is not None:
                        game['pool_position'] += pool_metadata['start']
            else:
                from .ladder_statistics import fixed_result
                summary['test'] = fixed_result(_pair_values(games), expected_pairs=setups)
    if luck_correction is not None:
        from .luck_statistics import summarize_luck
        for summary in row['opponents']:
            summary['luck_pilot'] = summarize_luck(summary['raw_games'])
    if checkpoint is None:
        _append(Path(output), row)
    else:
        row['timings']['scope'] = 'current invocation only; outcomes include checkpoint recovery'
        row = checkpoint.publish(output, row)
    if failed:
        raise ResearchError(f'Ladder evaluation failed; reproducible failure row retained at {output}')
    return row
