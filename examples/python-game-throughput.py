"""Measure native, mixed Python/native, and recorded play on matched short games.

The Python seat asks the native heuristic for its move, so every path plays the
same policy. Timings cover play calls after warm-up, including both processes and
transport, but exclude game setup, building, and the final equivalence checks.
Use --traffic to count actual response bytes; this adds measurement overhead.
"""
import argparse
import json
import os
from pathlib import Path
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from research_workspace import Session


def jvm_cpu(pid):
    """User plus system CPU seconds for the live child, on Linux."""
    try:
        fields = Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()
        return (int(fields[11]) + int(fields[12])) / os.sysconf('SC_CLK_TCK')
    except (OSError, ValueError, IndexError):
        return None


class ResponseCounter:
    def __init__(self, stream):
        self.stream, self.calls, self.bytes = stream, 0, 0

    def readline(self, *args):
        line = self.stream.readline(*args)
        self.calls += 1
        self.bytes += len(line.encode('utf-8'))
        return line

    def __getattr__(self, name):
        return getattr(self.stream, name)


def measure(game, mode, traffic, decision_limit):
    def choose(decision):
        selected = game.select('heuristic')
        # The heuristic's display tags differ; return the matching supplied action.
        return next(action for action in decision.actions if action.choice['signature'] == selected.choice['signature'])
    policies = None if mode == 'native' else {'p0': choose}
    recorder = (lambda row: None) if mode == 'recorded' else None
    stream = game.session._process.stdout
    counter = ResponseCounter(stream)
    if traffic:
        game.session._process.stdout = counter
    jvm_started, python_started = jvm_cpu(game.session.pid), time.process_time()
    started = time.perf_counter()
    try:
        result = game.play(policies, decision_limit=decision_limit, record=recorder)
    finally:
        elapsed = time.perf_counter() - started
        python_elapsed = time.process_time() - python_started
        game.session._process.stdout = stream
    jvm_finished = jvm_cpu(game.session.pid)
    sample = dict(mode=mode, wall_seconds=elapsed, decisions=result['decisions'],
                  status=result['status'], python_cpu_seconds=python_elapsed,
                  jvm_cpu_seconds=None if jvm_started is None or jvm_finished is None else jvm_finished - jvm_started)
    if traffic:
        sample.update(response_calls=counter.calls, response_bytes=counter.bytes)
    # Full inspection stays outside both the timer and the response counter.
    ending = (result, game.state(), [game.information(p)['historyCommitment'] for p in ('p0', 'p1')])
    return sample, ending


def run(games, build, traffic, decision_limit):
    samples = []
    modes = ('native', 'mixed', 'recorded')
    with Session(build=build, java_options=['-Xmx1g', '-XX:ActiveProcessorCount=4']) as research:
        for family, spell in (('burn', 'Shock'), ('creatures', 'Raging Goblin')):
            deck = {'Mountain': 8, spell: 4}
            # Each mode gets a complete warm-up game. Rotate subsequent order.
            for number in range(-1, games):
                with research.game([deck, deck], seed=17 + number, policies=('heuristic', 'heuristic'),
                                   starting_life=6, starting_hand_size=3, skip_mulligans=True) as source:
                    expected = None
                    for index in range(len(modes)):
                        mode = modes[(index + number) % len(modes)]
                        with source.fork() as game:
                            sample, ending = measure(game, mode, traffic, decision_limit)
                        if expected is not None and ending != expected:
                            raise RuntimeError(f'{family} seed {17 + number}: {mode} changed the game or player history')
                        expected = ending
                        if number >= 0:
                            samples.append(dict(family=family, seed=17 + number, **sample))
    summaries = []
    for family in ('burn', 'creatures'):
        for mode in modes:
            rows = [row for row in samples if row['family'] == family and row['mode'] == mode]
            elapsed = sum(row['wall_seconds'] for row in rows)
            decisions = sum(row['decisions'] for row in rows)
            summary = dict(family=family, mode=mode, games=len(rows),
                           terminal_games=sum(row['status'] == 'TERMINAL' for row in rows),
                           wall_seconds=elapsed, decisions=decisions,
                           milliseconds_per_decision=1000 * elapsed / decisions if decisions else None)
            for key in ('python_cpu_seconds', 'jvm_cpu_seconds', 'response_calls', 'response_bytes'):
                if all(row.get(key) is not None for row in rows):
                    summary[key] = sum(row[key] for row in rows)
            summaries.append(summary)
    return dict(scope=__doc__.strip(), summaries=summaries, samples=samples)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--games', type=int, default=4, help='Measured games per family and path')
    parser.add_argument('--decision-limit', type=int, default=512)
    parser.add_argument('--no-build', action='store_true')
    parser.add_argument('--traffic', action='store_true', help='Count response bytes and messages as well as time')
    args = parser.parse_args()
    if args.games < 1 or args.decision_limit < 1:
        parser.error('games and decision-limit must be positive')
    print(json.dumps(run(args.games, not args.no_build, args.traffic, args.decision_limit), indent=2))
