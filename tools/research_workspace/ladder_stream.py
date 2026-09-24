"""Bounded pair scheduling; only the ordered prefix can update a test."""
from concurrent.futures import ThreadPoolExecutor
import threading


def stream_pairs(opponents, count, workers, session_factory, play_pair, tests, checkpoint=None):
    """Return all issued pairs, including observations beyond a stopped prefix.

    Each worker owns one session. At most ``workers`` pairs per opponent can
    precede the next unresolved pair, bounding both speculative work and memory.
    ``play_pair`` returns both seats, including explicit error records.
    """
    condition = threading.Condition()
    issued = dict.fromkeys(opponents, 0)
    prefix = dict.fromkeys(opponents, 0)
    pending = {name: {} for name in opponents}
    rows = []
    errors = []
    failed = False
    recovery = []

    def decided(name):
        return tests is not None and tests[name].decided

    def consume(name, index, pair):
        nonlocal failed
        rows.extend((name, dict(game, pool_position=index)) for game in pair)
        if any(game['status'] == 'ERROR' or 'session_error' in game for game in pair):
            failed = True
        else:
            pending[name][index] = pair
            while prefix[name] in pending[name] and not decided(name):
                ordered = pending[name].pop(prefix[name])
                # Limits are candidate losses, never omitted observations.
                value = sum(game['payoff'] if game['payoff'] is not None else 0
                            for game in ordered) / 2
                if tests is not None:
                    tests[name].add(value)
                prefix[name] += 1

    def progress(state='running'):
        if checkpoint is not None:
            checkpoint.progress(rows, prefix, tests, issued, state)

    if checkpoint is not None:
        records = sorted(checkpoint.records(), key=lambda r: (r['opponent'], r['index']))
        for record in records:
            name, index, pair = record['opponent'], record['index'], record['pair']
            if name not in issued or type(index) is not int or index != issued[name] or index >= count:
                raise ValueError('invalid checkpoint pair assignment')
            issued[name] += 1
            if pair is None:
                recovery.append((name, index))
            else:
                if len(pair) != 2:
                    raise ValueError('checkpoint must contain both seats')
                consume(name, index, pair)
        prior_errors = checkpoint.read('errors.json')
        if prior_errors:
            errors.extend(prior_errors)
            failed = True
        progress('failed' if failed else 'running')

    def worker():
        nonlocal failed
        try:
            with session_factory() as session:
                while True:
                    with condition:
                        while True:
                            if failed:
                                return
                            if recovery:
                                # Finish previously issued work even when replay reaches a stop.
                                name, index = recovery.pop(0)
                                break
                            active = [name for name in opponents
                                      if not decided(name) and issued[name] < count]
                            if not active:
                                return
                            eligible = [name for name in active
                                        if issued[name] < prefix[name] + workers]
                            if eligible:
                                name = min(eligible, key=lambda name: issued[name])
                                index = issued[name]
                                if checkpoint is not None:
                                    checkpoint.issue(name, index)
                                issued[name] += 1
                                break
                            condition.wait()
                    pair = play_pair(session, name, index)
                    with condition:
                        if checkpoint is not None:
                            checkpoint.complete(name, index, pair)
                        consume(name, index, pair)
                        progress('failed' if failed else 'running')
                        condition.notify_all()
        except Exception as error:
            with condition:
                failed = True
                errors.append({'type': type(error).__name__, 'message': str(error)})
                condition.notify_all()
                if checkpoint is not None:
                    checkpoint.write('errors.json', errors)

    if not failed and (recovery or any(not decided(name) and issued[name] < count for name in opponents)):
        with ThreadPoolExecutor(max_workers=workers) as executor:
            list(executor.map(lambda _: worker(), range(workers)))
    progress('failed' if failed else 'completed')
    return rows, errors
