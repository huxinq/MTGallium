from pathlib import Path
import math
import random
import statistics
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

    @staticmethod
    def games(rows):
        games = []
        for seed, pair in enumerate(rows):
            for seat, (payoff, luck) in enumerate(pair):
                games.append({'seed': seed, 'candidate_seat': f'p{seat}',
                              'status': 'TERMINAL', 'payoff': payoff,
                              'luck': {'models': {'v': {'sum': luck}},
                                       'events': [{'kind': 'draw', 'status': 'retained',
                                                   'weightedLuck': {'v': luck}}]}})
        return games

    def test_game_fit_survives_pair_degeneracy_without_seat_leakage(self):
        # Opposite folds have slopes 1 and 2; pair means are constant.
        games = self.games([[(.5-a*s, -a), (.5+a*s, a)]
                            for a, s in [(.1, 1), (.2, 2), (.3, 1), (.1, 2)]])
        result = summarize_luck(games, bootstrap=30)
        model = result['models']['v']
        fit = model['cross_fitted_game']
        self.assertFalse(result['decision_input'])
        self.assertEqual({0: 0., 1: 0.}, model['cross_fit_betas'])
        self.assertAlmostEqual(2., fit['cross_fit_betas'][0])
        self.assertAlmostEqual(1., fit['cross_fit_betas'][1])
        self.assertIsNone(fit['pair_variance_ratio'])
        self.assertIsNone(fit['pair_variance_ratio_bootstrap_95'])
        self.assertGreater(fit['game_variance_ratio'], 0.)
        self.assertIsNotNone(fit['game_variance_ratio_bootstrap_95'])
        self.assertAlmostEqual(.5, fit['mean'])
        self.assertEqual([.5, .5], fit['mean_bootstrap_95'])
        self.assertEqual(result, summarize_luck(list(reversed(games)), bootstrap=30))

    def test_game_bootstrap_refits_with_original_pair_folds(self):
        rows = [[(.05, -.3), (.8, .4)], [(.4, .1), (.9, .2)],
                [(.2, -.1), (.6, .5)], [(.1, -.4), (.7, .1)],
                [(.3, .3), (1., .6)], [(.2, -.2), (.5, .4)]]
        fit = summarize_luck(self.games(rows), bootstrap=40, seed=52)['models']['v']['cross_fitted_game']
        rng = random.Random(52)
        means, ratios = [], []
        for _ in range(40):
            sampled = [[rows[rng.choice(group)] for _ in group]
                       for group in ([0, 2, 4], [1, 3, 5])]
            corrected, raw = [], []
            for fold in (0, 1):
                training = [game for pair in sampled[1-fold] for game in pair]
                x, y = zip(*training)
                beta = statistics.covariance(x, y)/statistics.variance(y)
                for pair in sampled[fold]:
                    for payoff, luck in pair:
                        corrected.append(payoff-beta*luck)
                        raw.append(payoff)
            means.append(statistics.mean(corrected))
            ratios.append(statistics.variance(corrected)/statistics.variance(raw))
        for key, samples in [('mean_bootstrap_95', means),
                             ('game_variance_ratio_bootstrap_95', ratios)]:
            samples.sort()
            expected = [samples[int(.025*39)], samples[int(.975*39)]]
            for actual, bound in zip(fit[key], expected):
                self.assertAlmostEqual(bound, actual)
        self.assertGreater(fit['mean_bootstrap_95'][1]-fit['mean_bootstrap_95'][0], 0)

    def test_zero_luck_and_degenerate_outcomes(self):
        for luck in (0., 1e-14):
            games = self.games([[(0., luck), (1., -luck)]]*6)
            fit = summarize_luck(games, bootstrap=20)['models']['v']['cross_fitted_game']
            self.assertEqual({0: 0., 1: 0.}, fit['cross_fit_betas'])
            self.assertEqual(1., fit['game_variance_ratio'])
            self.assertEqual([1., 1.], fit['game_variance_ratio_bootstrap_95'])
        fit = summarize_luck(self.games([[(.5, 0.), (.5, 0.)]]*4),
                             bootstrap=0)['models']['v']['cross_fitted_game']
        self.assertIsNone(fit['game_variance_ratio'])
        self.assertIsNone(fit['mean_bootstrap_95'])
        self.assertEqual(.5, fit['mean'])

    def test_event_variances_and_zero_mean_cluster_uncertainty(self):
        games = self.games([[(.5, value), (.5, value)] for value in (-1., 1., -1., 1.)])
        for game in games:
            game['luck']['events'].append({'kind': 'draw', 'status': 'skipped'})
            game['luck']['events'].append({'kind': 'shuffle', 'status': 'skipped'})
        model = summarize_luck(games, bootstrap=10)['models']['v']
        event = model['events']['draw']
        self.assertEqual(8, event['count'])
        self.assertEqual({'retained': 8, 'skipped': 8}, event['statuses'])
        self.assertEqual(0., event['mean_luck'])
        self.assertAlmostEqual(8/7, event['individual_luck_variance'])
        self.assertAlmostEqual(8/7, event['game_sum_luck_variance'])
        self.assertAlmostEqual(16/3, event['pair_sum_luck_variance'])
        radius = 1.959963984540054/math.sqrt(3)
        for actual, expected in zip(event['cluster_normal_95'], [-radius, radius]):
            self.assertAlmostEqual(expected, actual)
        self.assertEqual(event['cluster_normal_95'], event['game_sum_cluster_normal_95'])
        self.assertIsNone(model['events']['shuffle']['cluster_normal_95'])
        self.assertIsNone(model['events']['shuffle']['individual_luck_variance'])
        self.assertEqual(0., model['events']['shuffle']['game_sum_luck_variance'])

    def test_event_sum_variances_include_absent_games_and_within_game_covariance(self):
        games = self.games([[(.5, 0.), (.5, 0.)]]*4)
        for game in games:
            game['luck']['events'] = []
        games[0]['luck']['events'] = [
            {'kind': 'draw', 'status': 'retained', 'weightedLuck': {'v': x}}
            for x in (-1., 1.)]
        event = summarize_luck(games, bootstrap=0)['models']['v']['events']['draw']
        self.assertEqual(2., event['individual_luck_variance'])
        self.assertEqual(0., event['game_sum_luck_variance'])
        self.assertEqual(0., event['pair_sum_luck_variance'])
        self.assertEqual([0., 0.], event['cluster_normal_95'])
