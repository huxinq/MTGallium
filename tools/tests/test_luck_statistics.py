from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from research_workspace.luck_statistics import summarize_luck


class LuckStatisticsTest(unittest.TestCase):
    def test_pair_crossfit_and_event_interval(self):
        games = []
        for seed, payoff in enumerate([0., .5, 1., 0., .5, 1., 0., .5, 1., 0., .5, 1.]):
            luck = payoff-.5
            for seat in (0, 1):
                games.append({'seed': seed, 'candidate_seat': f'p{seat}', 'status': 'TERMINAL',
                              'payoff': payoff, 'luck': {'models': {'v': {'sum': luck}},
                              'events': [{'kind': 'draw', 'status': 'retained', 'weightedLuck': {'v': luck}}]}})
        result = summarize_luck(games, bootstrap=40)
        self.assertEqual(12, result['pairs'])
        model = result['models']['v']
        self.assertEqual({0: 1., 1: 1.}, model['cross_fit_betas'])
        for key in ('beta_one', 'cross_fitted'):
            self.assertEqual(.5, model[key]['mean'])
            self.assertEqual(0., model[key]['variance_ratio'])
            for bound in model[key]['variance_ratio_bootstrap_95']:
                self.assertAlmostEqual(0., bound, places=14)
        self.assertEqual(24, model['events']['draw']['count'])
        self.assertEqual(0., model['events']['draw']['mean_luck'])

    def test_missing_pairs_are_not_fabricated(self):
        self.assertEqual('insufficient_pairs', summarize_luck([])['status'])
