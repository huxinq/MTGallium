from contextlib import nullcontext
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from research_workspace.ladder import evaluate


class ComparisonTest(unittest.TestCase):
    def test_luck_time_limits_fail_before_setup_or_model_loading(self):
        with patch('research_workspace.ladder.Session') as session, \
                patch('research_workspace.ladder.source_provenance') as provenance:
            for key in ('maximum_seconds', 'maximumSeconds'):
                with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'time limits'):
                    evaluate('random', opponents={'random': 'random'}, incumbent='random',
                             decks=[{'Mountain': 7}] * 2, config={key: 1.0},
                             luck_correction={'models': [{'model': '/missing-model.json'}]})
            session.assert_not_called()
            provenance.assert_not_called()

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.options = dict(candidate='heuristic', incumbent='random', opponents={'one': 'random'},
                            decks=[{'Mountain': 4}]*2, threads=2, build=False,
                            evidence_root=self.root, output=self.root/'rows.jsonl')

    def run_evaluation(self, **options):
        def game(session, candidate, incumbent, opponent, decks, seed, seat, settings):
            return {'seed': seed, 'candidate_seat': f'p{seat}',
                    'status': 'TERMINAL' if seat else 'DECISION_LIMIT',
                    'payoff': 1 if seat else None, 'candidate_decisions': 3, 'changed_decisions': 1}
        with patch('research_workspace.ladder.source_provenance', return_value={'commit':'synthetic'}), \
             patch('research_workspace.ladder.runtime'), \
             patch('research_workspace.ladder.Session', side_effect=lambda **_: nullcontext()), \
             patch('research_workspace.ladder._game', side_effect=game):
            return evaluate(**dict(self.options, **options))

    def test_sequential_prefix_uses_loss_score_and_pool(self):
        row = self.run_evaluation(sequential='improvement', max_pairs=6)
        summary = row['opponents'][0]
        self.assertEqual('development', row['config']['seed_pool']['kind'])
        self.assertEqual(.5, summary['test']['score'])
        self.assertEqual(6, summary['unfinished_candidate_losses'])
        self.assertEqual('INCONCLUSIVE', summary['test']['outcome'])
        self.assertEqual(0, summary['in_flight_games'])
        self.assertTrue(all(game['test_prefix'] for game in summary['raw_games']))
        again = self.run_evaluation(seed_pool='development', setups=6)
        self.assertEqual(row['config']['seeds'], again['config']['seeds'])

    def test_confirmation_reserves_configuration_and_refuses_claim_reuse(self):
        row = self.run_evaluation(phase='confirmation', claim='test claim', setups=6)
        declaration = row['config']['seed_pool']['declaration']
        self.assertEqual(6, declaration['setups'])
        self.assertEqual('synthetic', declaration['source']['commit'])
        self.assertEqual(.5, row['opponents'][0]['test']['score'])
        with self.assertRaisesRegex(ValueError, 'already reserved'):
            self.run_evaluation(phase='confirmation', claim='test claim', setups=6)

    def test_early_stop_descriptive_bounds_use_issued_pairs(self):
        class Stop:
            def __init__(self, **_):
                self.max_pairs, self.n = 10, 0
            @property
            def decided(self):
                return self.n == 3
            def add(self, value):
                self.n += 1
            def result(self):
                return {'pairs': self.n}
        with patch('research_workspace.ladder_statistics.SequentialTest', Stop):
            row = self.run_evaluation(sequential='improvement', max_pairs=10, threads=1)
        summary = row['opponents'][0]
        self.assertEqual(3, summary['independent_setups'])
        self.assertEqual(10, summary['planned_setups'])
        self.assertEqual([.5, 1.], summary['missing_outcome_bounds'])
        self.assertIsNone(summary['fixed_sample_hoeffding_95']['upper'])

    def test_no_sequential_confirmation_or_ambiguous_seed_source(self):
        for options in [dict(phase='confirmation', sequential='improvement'),
                        dict(seed_pool='confirmation'),
                        dict(seed_pool='development', seeds=[1], setups=1)]:
            with self.assertRaises(ValueError):
                self.run_evaluation(**options)
