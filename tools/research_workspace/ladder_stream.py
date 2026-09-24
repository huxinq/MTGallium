"""Bounded pair scheduling; only the ordered prefix can update a test."""
from concurrent.futures import ThreadPoolExecutor
import threading


def stream_pairs(opponents, count, workers, session_factory, play_pair, tests):
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

    def worker():
        nonlocal failed
        try:
            with session_factory() as session:
                while True:
                    with condition:
                        while True:
                            if failed:
                                return
                            active = [name for name in opponents
                                      if not tests[name].decided and issued[name] < count]
                            if not active:
                                return
                            eligible = [name for name in active
                                        if issued[name] < prefix[name] + workers]
                            if eligible:
                                name = min(eligible, key=lambda name: issued[name])
                                index = issued[name]
                                issued[name] += 1
                                break
                            condition.wait()
                    pair = play_pair(session, name, index)
                    with condition:
                        rows.extend((name, dict(game, pool_position=index)) for game in pair)
                        if any(game['status'] == 'ERROR' or 'session_error' in game for game in pair):
                            failed = True
                        else:
                            pending[name][index] = pair
                            while prefix[name] in pending[name] and not tests[name].decided:
                                ordered = pending[name].pop(prefix[name])
                                # Limits are candidate losses, never omitted observations.
                                value = sum(game['payoff'] if game['payoff'] is not None else 0
                                            for game in ordered) / 2
                                tests[name].add(value)
                                prefix[name] += 1
                        condition.notify_all()
        except Exception as error:
            with condition:
                failed = True
                errors.append({'type': type(error).__name__, 'message': str(error)})
                condition.notify_all()

    with ThreadPoolExecutor(max_workers=workers) as executor:
        list(executor.map(lambda _: worker(), range(workers)))
    return rows, errors
