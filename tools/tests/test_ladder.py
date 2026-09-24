"""Checks for paired ladder arithmetic, scheduling, and one public live journey."""
from pathlib import Path
import json
import os
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from research_workspace import ladder
from research_workspace import evaluate


def game(seed, seat, payoff, status='TERMINAL'):
    return {'seed': seed, 'candidate_seat': f'p{seat}', 'status': status, 'payoff': payoff,
            'candidate_decisions': 3, 'changed_decisions': 1}


class LadderArithmeticTest(unittest.TestCase):
    def test_outcomes_and_partial_pair_have_fixed_sample_bounds(self):
        # 120 wins + 60 draws = 150 known points; twenty assigned games are missing.
        games = ([game(i, 0, 1) for i in range(60)] +
                 [game(i, 1, 1) for i in range(60)] +
                 [game(i, 0, .5) for i in range(60, 100)] +
                 [game(i, 1, None, 'DECISION_LIMIT') for i in range(60, 80)] +
                 [game(i, 1, .5) for i in range(80, 100)])
        result = ladder._summarize('fixed', games, 100)
        self.assertEqual({'wins': 120, 'draws': 60, 'losses': 0}, result['terminal_games'])
        self.assertEqual({'DECISION_LIMIT': 20}, result['incomplete_games'])
        self.assertEqual(80, result['complete_setups'])
        self.assertAlmostEqual(0.6141898484, result['fixed_sample_hoeffding_95']['lower'], places=9)
        self.assertAlmostEqual(0.9858101516, result['fixed_sample_hoeffding_95']['upper'], places=9)

    def test_complete_pair_score_and_normal_interval_use_setup_means(self):
        games = [game(1, 0, 1), game(1, 1, .5), game(2, 0, .5), game(2, 1, 0)]
        result = ladder._summarize('paired', games, 2)
        self.assertEqual(.5, result['complete_pair_score'])
        # Pair means are .75 and .25: sample SD=sqrt(.125), SE=.25, 1.96*SE=.49.
        lower, upper = result['complete_pair_normal_approx_95']['interval']
        self.assertAlmostEqual(.01, lower)
        self.assertAlmostEqual(.99, upper)


class LadderSchedulingTest(unittest.TestCase):
    def evaluate(self, **options):
        defaults = dict(candidate='heuristic', opponents={'random': 'random'},
                        incumbent='random', decks=[{'Mountain': 7}] * 2, setups=2,
                        output=Path(self.temporary.name) / 'ladder.jsonl', build=False)
        defaults.update(options)
        return evaluate(**defaults)

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.temporary.cleanup()

    @patch('research_workspace.ladder._git', side_effect=lambda *args:
           '160000 commit deadbeef\tthird_party/argentum-engine'
           if args[0] == 'ls-tree' else 'deadbeef')
    @patch('research_workspace.ladder.subprocess.run')
    @patch('research_workspace.ladder.runtime')
    @patch('research_workspace.ladder._game')
    @patch('research_workspace.ladder.Session')
    def test_workers_own_and_reuse_distinct_sessions(self, session_type, play, runtime, process, git):
        made = []
        class FakeSession:
            def __init__(self, **_):
                self.threads = set()
                made.append(self)
            def __enter__(self): return self
            def __exit__(self, *_): pass
        session_type.side_effect = FakeSession
        def fake_game(session, candidate, incumbent, opponent, decks, seed, seat, settings):
            session.threads.add(threading.get_ident())
            return game(seed, seat, .5)
        play.side_effect = fake_game
        process.return_value.stdout = 'engine-head\n'
        self.evaluate(threads=2, seeds=[11, 12])
        self.assertEqual(2, len(made))
        self.assertTrue(all(len(session.threads) == 1 for session in made))
        self.assertEqual(4, play.call_count)
        runtime.assert_called_once_with(build=False)

    @patch('research_workspace.ladder._git', side_effect=lambda *args:
           '160000 commit deadbeef\tthird_party/argentum-engine'
           if args[0] == 'ls-tree' else 'deadbeef')
    @patch('research_workspace.ladder.subprocess.run')
    @patch('research_workspace.ladder.runtime')
    @patch('research_workspace.ladder._game', side_effect=lambda s, c, i, o, d, seed, seat, settings:
           game(seed, seat, .5))
    @patch('research_workspace.ladder.Session')
    @patch('research_workspace.ladder.secrets.randbits', side_effect=[7, 7, 9])
    def test_default_seeds_are_unique_despite_collision(self, random_bits, session_type, play,
                                                        runtime, process, git):
        session_type.return_value.__enter__.return_value = object()
        process.return_value.stdout = 'engine-head\n'
        row = self.evaluate(threads=1)
        self.assertEqual([7, 9], row['config']['seeds'])

    @patch('research_workspace.ladder._git', side_effect=lambda *args:
           '160000 commit deadbeef\tthird_party/argentum-engine'
           if args[0] == 'ls-tree' else 'deadbeef')
    @patch('research_workspace.ladder.subprocess.run')
    @patch('research_workspace.ladder.runtime')
    @patch('research_workspace.ladder._game', side_effect=lambda s, c, i, o, d, seed, seat, settings:
           game(seed, seat, .5))
    @patch('research_workspace.ladder.Session')
    def test_default_output_uses_live_ladder_directory(self, session_type, play, runtime,
                                                       process, git):
        session_type.return_value.__enter__.return_value = object()
        process.return_value.stdout = 'engine-head\n'
        with patch.dict(os.environ, {'MTGALLIUM_PRIVATE_EVIDENCE_ROOT': self.temporary.name}):
            self.evaluate(output=None, setups=1, seeds=[7], threads=1)
        self.assertTrue((Path(self.temporary.name) / 'ladder/ladder.jsonl').is_file())
        self.assertFalse((Path(self.temporary.name) / 'search-teacher/ladder/ladder.jsonl').exists())

    @patch('research_workspace.ladder._git', side_effect=lambda *args:
           '160000 commit deadbeef\tthird_party/argentum-engine'
           if args[0] == 'ls-tree' else 'deadbeef')
    @patch('research_workspace.ladder.subprocess.run')
    @patch('research_workspace.ladder.runtime')
    @patch('research_workspace.ladder.Session')
    def test_failure_row_retains_error_and_all_assigned_games(self, session_type, runtime,
                                                              process, git):
        session_type.return_value.__enter__.return_value = object()
        process.return_value.stdout = 'engine-head\n'
        calls = 0
        def fail_second(session, candidate, incumbent, opponent, decks, seed, seat, settings):
            nonlocal calls
            calls += 1
            if calls == 2:
                raise RuntimeError('deliberate engine failure')
            return game(seed, seat, .5)
        output = Path(self.temporary.name) / 'failed.jsonl'
        with patch('research_workspace.ladder._game', side_effect=fail_second):
            with self.assertRaisesRegex(ladder.ResearchError, 'failure row retained'):
                self.evaluate(threads=1, seeds=[11, 12], output=output)
        row = json.loads(output.read_text())
        self.assertEqual('failed', row['state'])
        summary = row['opponents'][0]
        self.assertEqual(4, summary['games'])
        self.assertEqual({'ERROR': 1, 'UNEXECUTED': 2}, summary['incomplete_games'])
        self.assertEqual(3, summary['decision_counts_missing_games'])
        self.assertEqual({'wins': 0, 'draws': 1, 'losses': 0}, summary['terminal_games'])
        failure = next(game for game in summary['raw_games'] if game['status'] == 'ERROR')
        self.assertEqual(11, failure['seed'])
        self.assertEqual('p1', failure['candidate_seat'])
        self.assertEqual('RuntimeError', failure['error']['type'])


class LadderLiveTest(unittest.TestCase):
    def test_two_workers_python_candidate_against_native_policies(self):
        with tempfile.TemporaryDirectory() as directory:
            row = evaluate(lambda decision: 0, name='first-action',
                           opponents={'random': 'random'}, incumbent='heuristic',
                           decks=[{'Mountain': 7}, {'Mountain': 7}], setups=2,
                           seeds=[1701, 1702], threads=2,
                           config={'starting_hand_size': 0, 'skip_mulligans': True,
                                   'maximum_decisions': 4},
                           output=Path(directory) / 'ladder.jsonl',
                           java_options=['-Xmx512m'])
        self.assertEqual(4, row['opponents'][0]['games'])
        self.assertGreater(row['opponents'][0]['candidate_decisions'], 0)


if __name__ == '__main__':
    unittest.main()


class SourceProvenanceTest(unittest.TestCase):
    def test_remote_snapshot_uses_recorded_provenance_without_git(self):
        from research_workspace.ladder import source_provenance
        recorded = {'commit': 'abc', 'diff': 'd', 'status': '', 'engine_head': 'e', 'engine_pin': 'e'}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'source.json'
            path.write_text(json.dumps(recorded))
            with patch.dict(os.environ, {'MTG_SOURCE_JSON': str(path)}), \
                 patch('research_workspace.ladder._git', side_effect=AssertionError('git called')):
                self.assertEqual(source_provenance(), recorded)
