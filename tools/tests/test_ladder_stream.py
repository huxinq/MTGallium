"""Scheduling tests use synthetic outcomes and no JVM."""
from contextlib import nullcontext
from pathlib import Path
import sys
import threading
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from research_workspace.ladder_stream import stream_pairs


class PrefixTest:
    def __init__(self, stop_at):
        self.values = []
        self.stop_at = stop_at

    @property
    def decided(self):
        return len(self.values) == self.stop_at

    def add(self, value):
        self.values.append(value)


class StreamTest(unittest.TestCase):
    def test_only_contiguous_prefix_decides_with_different_interleavings(self):
        for delayed in (0, 1, 3):
            tests = {'first': PrefixTest(4), 'second': PrefixTest(7)}
            def play(_, name, index):
                if index == delayed:
                    time.sleep(.02)
                return [{'seed': index, 'candidate_seat': f'p{seat}',
                         'status': 'TERMINAL', 'payoff': (index % 3) / 2}
                        for seat in (0, 1)]
            rows, errors = stream_pairs(tests, 20, 3, nullcontext, play, tests)
            self.assertFalse(errors)
            for name, test in tests.items():
                self.assertEqual([(i % 3) / 2 for i in range(test.stop_at)], test.values)
                positions = sorted({row['pool_position'] for opponent, row in rows if opponent == name})
                self.assertEqual(list(range(len(positions))), positions)
                self.assertLessEqual(len(positions), test.stop_at + 2)

    def test_unfinished_game_is_a_loss_and_error_is_not_evidence(self):
        tests = {'one': PrefixTest(9)}
        def play(_, name, index):
            return [{'status': 'TERMINAL', 'payoff': 1},
                    {'status': 'DECISION_LIMIT' if index == 0 else 'ERROR', 'payoff': None}]
        rows, errors = stream_pairs(tests, 9, 1, nullcontext, play, tests)
        self.assertEqual([.5], tests['one'].values)
        self.assertEqual(4, len(rows))

    def test_session_failure_wakes_blocked_workers(self):
        tests = {'one': PrefixTest(9)}
        class Broken:
            def __enter__(self):
                raise RuntimeError('session unavailable')
            def __exit__(self, *_):
                pass
        rows, errors = stream_pairs(tests, 9, 2, Broken, None, tests)
        self.assertFalse(rows)
        self.assertEqual(2, len(errors))
        self.assertEqual([], tests['one'].values)
