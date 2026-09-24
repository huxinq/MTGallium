"""Native-policy cost and identical-play measurements without shadow searches."""
import hashlib
from pathlib import Path
import subprocess
import time

from . import Session
from .ladder import source_provenance
from .resources import JAVA_OPTIONS
from .runner import _atomic_json, _encoded


def _jfr(session, command, *arguments):
    java = Path(f'/proc/{session.pid}/exe').resolve()
    subprocess.run([str(java.parent / 'jcmd'), str(session.pid), command, *arguments],
                   check=True, stdout=subprocess.DEVNULL)


def measure(policy, *, opponent, decks, config, seeds, warmup_seed, output,
            java_options=JAVA_OPTIONS, build=True, jfr=False):
    """One warm-up followed by fixed seeds in one JVM, alternating candidate seats.

    CPU covers both policies and game stepping, divided by candidate searches.
    Fingerprints include every actor/semantic action signature, order, and terminal outcome.
    """
    seeds = list(seeds)
    if not seeds or len(set(seeds)) != len(seeds) or warmup_seed in seeds:
        raise ValueError('Use distinct measurement seeds and a separate warm-up seed')
    if {'seed', 'policies', 'decks'} & config.keys():
        raise ValueError('Pass seeds, policies and decks separately from config')
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    result = dict(policy=policy, opponent=opponent, config=config, decks=decks,
                  seeds=seeds, warmup_seed=warmup_seed, java_options=list(java_options),
                  source=source_provenance(), games=[])
    with Session(build=build, java_options=java_options) as session:
        for i, seed in enumerate([warmup_seed, *seeds]):
            seat = max(0, i - 1) % 2
            policies = [opponent, opponent]
            policies[seat] = policy
            recording = output.resolve() / f'game-{i}.jfr' if jfr and i else None
            if recording:
                _jfr(session, 'JFR.start', 'name=measure', 'settings=profile', 'disk=true')
            cpu0, wall0 = session.cpu_seconds(), time.monotonic()
            searched = 0
            leaf_evaluations = 0
            unsettled_leaf_evaluations = 0
            moves = hashlib.sha256()
            count = 0
            try:
                with session.game(decks, seed=seed, policies=policies, **config) as game:
                    status = game.status()
                    while not status['terminal'] and count < config.get('maximum_decisions', 4096):
                        actor = int(status['actor'][1:])
                        action = game.select(policies[actor])
                        if actor == seat and action.search is not None:
                            searched += 1
                            diagnostics = action.search['diagnostics']
                            leaf_evaluations += diagnostics['evaluatorCalls']
                            unsettled_leaf_evaluations += diagnostics['unsettledLeafEvaluations']
                        moves.update((_encoded([status['actor'], action.choice['signature']]) + '\n').encode())
                        status = game.step(action, record=False)['status']
                        count += 1
                    moves.update(_encoded([status['terminal'], status.get('payoffs')]).encode())
                cpu = session.cpu_seconds() - cpu0
                wall = time.monotonic() - wall0
            finally:
                if recording:
                    _jfr(session, 'JFR.stop', 'name=measure', f'filename={recording}')
            result['games'].append(dict(seed=seed, warmup=i == 0, seat=seat,
                terminal=status['terminal'], payoffs=status.get('payoffs'), decisions=count,
                searched_decisions=searched, cpu_seconds=cpu, wall_seconds=wall,
                leaf_evaluations=leaf_evaluations,
                unsettled_leaf_evaluations=unsettled_leaf_evaluations,
                fingerprint=moves.hexdigest(), jfr=str(recording) if recording else None))
            _atomic_json(output / 'measure.json', result)
    measured = result['games'][1:]
    cpu = sum(row['cpu_seconds'] for row in measured)
    searched = sum(row['searched_decisions'] for row in measured)
    leaf_evaluations = sum(row['leaf_evaluations'] for row in measured)
    unsettled_leaf_evaluations = sum(row['unsettled_leaf_evaluations'] for row in measured)
    result.update(cpu_per_game=cpu / len(measured), searched_decisions_per_game=searched / len(measured),
                  cpu_per_searched_decision=cpu / searched if searched else None,
                  leaf_evaluations=leaf_evaluations,
                  unsettled_leaf_evaluations=unsettled_leaf_evaluations,
                  unsettled_leaf_fraction=(unsettled_leaf_evaluations / leaf_evaluations
                                           if leaf_evaluations else None),
                  complete=all(row['terminal'] for row in measured))
    _atomic_json(output / 'measure.json', result)
    return result
