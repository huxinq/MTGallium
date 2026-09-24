"""Synthetic seed-ledger and reference-math tests; no games, builds or evidence."""
from concurrent.futures import ThreadPoolExecutor
import json
import math
from pathlib import Path
from statistics import NormalDist
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from research_workspace import ladder_statistics as stats


class SeedPoolsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_frozen_sizes_disjoint_identities_and_positions(self):
        development = stats.allocate(self.root, 'development', 8192)
        confirmation = stats.allocate(self.root, 'confirmation', 32768, claim='first')
        self.assertEqual(8192, len(set(development['seeds'])))
        self.assertEqual(32768, len(set(confirmation['seeds'])))
        self.assertFalse(set(development['seeds']) & set(confirmation['seeds']))
        self.assertEqual(development['pool_sha256'], confirmation['pool_sha256'])
        self.assertNotEqual(development['seed_pool_sha256'], confirmation['seed_pool_sha256'])
        again = stats.allocate(self.root, 'development', 5, start=7)
        self.assertEqual(development['seeds'][7:12], again['seeds'])
        self.assertEqual(list(range(7,12)), again['positions'])
        self.assertEqual(development['pool_sha256'], again['pool_sha256'])
        with self.assertRaisesRegex(ValueError, 'exhausted'):
            stats.allocate(self.root, 'confirmation', 1, claim='next')

    def test_claim_reuse_overlap_and_append_only_declaration(self):
        declaration = {'candidate': 'hash', 'decks': [{'Mountain': 7}], 'max_pairs': 10}
        metadata = stats.allocate(self.root, 'confirmation', 10, claim='claim',
                                  start=5, declaration=declaration)
        seeds = metadata['seeds']
        path = self.root / 'ladder/confirmation-reservations.jsonl'
        before = path.read_bytes()
        row = json.loads(before)
        self.assertEqual(declaration, row['declaration'])
        self.assertEqual(declaration, metadata['declaration'])
        self.assertEqual(metadata['reservation_sha256'], row['sha256'])
        for claim, start, count in [('claim', 100, 1), ('different', 5, 1),
                                    ('different', 0, 6), ('different', 14, 2),
                                    ('different', 0, 20)]:
            with self.assertRaises(ValueError):
                stats.allocate(self.root, 'confirmation', count, claim=claim, start=start)
            self.assertEqual(before, path.read_bytes())
        next_row = stats.allocate(self.root, 'confirmation', 3, claim='next')
        self.assertEqual(15, next_row['start'])
        self.assertTrue(path.read_bytes().startswith(before))
        self.assertEqual(row['sha256'], json.loads(path.read_text().splitlines()[1])['previous'])
        replay = stats.reproduction_seeds(seeds)
        self.assertFalse(replay['fresh_confirmation'])
        self.assertEqual(metadata['sha256'], replay['sha256'])

    def test_concurrent_claims_get_nonoverlapping_reservations(self):
        def reserve(i):
            return stats.allocate(self.root, 'confirmation', 5, claim=f'claim-{i}')
        with ThreadPoolExecutor(max_workers=8) as executor:
            results = list(executor.map(reserve, range(12)))
        self.assertEqual(list(range(0,60,5)), sorted(row['start'] for row in results))
        self.assertEqual(60, len({s for row in results for s in row['seeds']}))
        def duplicate(_):
            try:
                reserve('same')
                return True
            except ValueError:
                return False
        with ThreadPoolExecutor(max_workers=4) as executor:
            self.assertEqual(1, sum(executor.map(duplicate, range(4))))

    def test_torn_or_missing_ledger_fails_closed(self):
        stats.allocate(self.root, 'confirmation', 1, claim='a')
        path = self.root / 'ladder/confirmation-reservations.jsonl'
        with path.open('ab') as stream:
            stream.write(b'{"claim":')
        with self.assertRaises(ValueError):
            stats.allocate(self.root, 'confirmation', 1, claim='b')
        path.unlink()
        with self.assertRaisesRegex(ValueError, 'missing reservation ledger'):
            stats.allocate(self.root, 'confirmation', 1, claim='b')

    def test_identity_tampering_fails_closed(self):
        stats.allocate(self.root, 'development', 1)
        path = self.root / 'ladder/seed-pools.json'
        document = json.loads(path.read_text())
        document['pools']['development'][0] ^= 1
        path.chmod(0o644)
        path.write_text(json.dumps(document))
        with self.assertRaisesRegex(ValueError, 'identity mismatch'):
            stats.allocate(self.root, 'development', 1)

    def test_invalid_requests(self):
        for kwargs in [dict(kind='bad', count=1), dict(kind='development', count=0),
                       dict(kind='development', count=True), dict(kind='development', count=1, start=-1),
                       dict(kind='confirmation', count=1), dict(kind='confirmation', count=1, claim='  ')]:
            with self.assertRaises(ValueError):
                stats.allocate(self.root, **kwargs)
        with self.assertRaises(ValueError):
            stats.allocate(self.root/'missing', 'development', 1)
        for values in [[], [1,1], [True], [-1], [2**63]]:
            with self.assertRaises(ValueError):
                stats.reproduction_seeds(values)

    def test_os_collisions_are_retried_across_both_pools(self):
        draws = iter([17,17,19,19,23])
        with patch.dict(stats.POOL_SIZES, {'development': 2, 'confirmation': 1}, clear=True), \
                patch.object(stats.secrets, 'randbits', side_effect=lambda _: next(draws)) as entropy:
            development = stats.allocate(self.root, 'development', 2)
            confirmation = stats.allocate(self.root, 'confirmation', 1, claim='claim')
        self.assertEqual([17,19], development['seeds'])
        self.assertEqual([23], confirmation['seeds'])
        self.assertEqual(5, entropy.call_count)

    def test_ledger_edit_is_rejected(self):
        stats.allocate(self.root, 'confirmation', 10, claim='claim')
        path = self.root / 'ladder/confirmation-reservations.jsonl'
        row = json.loads(path.read_text())
        row['stop'] = 1
        path.write_text(json.dumps(row)+'\n')
        with self.assertRaisesRegex(ValueError, 'identity mismatch'):
            stats.allocate(self.root, 'confirmation', 2, claim='second')


class BrownianTest(unittest.TestCase):
    def test_centered_reference_reflection_series_not_double_count(self):
        for t in [.001, .01, .09999, .1, .10001, .5, 1., 5.]:
            # Exact alternating reflection formula for exit from (-.5,.5).
            expected = 2*math.fsum((-1)**k * math.erfc((2*k+1)*.5/math.sqrt(2*t))
                                  for k in range(100))
            got = stats.brownian_either_bound(.5, 0, 1, t, 0, 1)
            self.assertAlmostEqual(expected, got, places=12)
        naive = 2*math.erfc(.5/math.sqrt(2*.5))
        exact = stats.brownian_either_bound(.5,0,1,.5,0,1)
        self.assertGreater(naive-exact, .04)

    def test_drift_against_independent_kernel_quadrature(self):
        # Numerically integrate tilted killed density directly, independent of
        # production's integrated CDF/image and integrated sine formulas.
        for x, t, drift in [(.3,.03,.7), (.2,.1,-1.3), (.7,.4,2.), (.6,1.,-.5)]:
            def density(y):
                kernel = math.fsum(math.exp(-(y-x+2*k)**2/(2*t)) -
                                   math.exp(-(y+x+2*k)**2/(2*t)) for k in range(-12,13))
                return math.exp(drift*(y-x)-drift*drift*t/2)*kernel/math.sqrt(2*math.pi*t)
            steps = 2000
            survival = (density(0)+density(1)+math.fsum(
                (4 if i % 2 else 2)*density(i/steps) for i in range(1,steps)))/(3*steps)
            self.assertAlmostEqual(1-survival, stats.brownian_either_bound(x,0,1,t,drift,1), places=10)

    def test_one_bound_limit_symmetry_scaling_and_monotonicity(self):
        # Other boundary is effectively unreachable: reflection formula with drift.
        mu, t, distance = .4, .7, 1.
        normal = NormalDist()
        expected = normal.cdf((mu*t-distance)/math.sqrt(t)) + math.exp(2*mu*distance)*normal.cdf((-mu*t-distance)/math.sqrt(t))
        self.assertAlmostEqual(expected, stats.brownian_either_bound(0,-100,1,t,mu,1), places=12)
        for t in [.00001,.01,.1,1,100]:
            p = stats.brownian_either_bound(.3,0,1,t,.2,.7)
            self.assertAlmostEqual(p, stats.brownian_either_bound(.7,0,1,t,-.2,.7), places=12)
            self.assertAlmostEqual(p, stats.brownian_either_bound(13,10,20,t,2,70), places=12)
        ps = [stats.brownian_either_bound(.3,0,1,t,.2,.7) for t in [.001,.01,.1,1,10]]
        self.assertEqual(ps, sorted(ps))

    def test_extreme_and_deterministic_limits(self):
        self.assertEqual(1, stats.brownian_either_bound(0,0,1,0,0,0))
        self.assertEqual(0, stats.brownian_either_bound(.5,0,1,0,1,1))
        self.assertEqual(1, stats.brownian_either_bound(.5,0,1,1,.5,0))
        self.assertEqual(0, stats.brownian_either_bound(.5,0,1,1,.4,0))
        self.assertEqual(1, stats.brownian_either_bound(.5,0,1,1,1000,.001))
        self.assertEqual(0, stats.brownian_either_bound(.5,0,1,1e-12,0,1))
        for args in [(0,1,0,1,0,1), (.5,0,1,-1,0,1), (.5,0,1,1,0,-1), (.5,0,1,1,float('nan'),1)]:
            with self.assertRaises(ValueError):
                stats.brownian_either_bound(*args)


class SequentialMathTest(unittest.TestCase):
    def test_planning_derivation(self):
        z = NormalDist()
        delta = 1/(1+10**(-20/400))-.5
        expected = math.ceil(.0875*(z.inv_cdf(.975)+z.inv_cdf(.95))**2/delta**2)
        self.assertEqual(1376, expected)
        self.assertEqual(expected, stats.fixed_confirmation_size())
        self.assertEqual(655, stats.fixed_confirmation_size(power=.8, two_sided=False))
        self.assertGreater(stats.fixed_confirmation_size('non_regression'), 4*1376-20)
        self.assertEqual(expected, stats.SequentialTest('improvement').max_pairs)

    def test_profile_normal_likelihood_reference(self):
        values = [.25,.5,.75,1,.25,.5,.5,.75]
        test = stats.SequentialTest('improvement', max_pairs=100)
        for value in values:
            test.add(value)
        mean = sum(values)/len(values)
        v0 = sum((v-.5)**2 for v in values)/len(values)
        v1 = sum((v-stats.elo_score(20))**2 for v in values)/len(values)
        result = test.result()
        self.assertAlmostEqual(len(values)/2*math.log(v0/v1), result['llr'])
        self.assertAlmostEqual(mean, result['score'])
        self.assertAlmostEqual(math.log(19), result['upper_bound'])
        self.assertEqual(len(values), result['n'])
        self.assertFalse(test.decided)

    def test_boundary_outcomes_and_stopping(self):
        for mode, values, expected in [('improvement',[.75,1],'BETTER'),
                                       ('improvement',[0,.25],'NOT_BETTER'),
                                       ('non_regression',[.75,1],'NON_INFERIOR'),
                                       ('non_regression',[0,.25],'INFERIOR')]:
            test = stats.SequentialTest(mode, max_pairs=200)
            for i in range(200):
                result = test.add(values[i%2])
                if test.decided:
                    break
            self.assertEqual(expected, result['outcome'])
            self.assertIn('biased upward', result['estimate_label'])
            self.assertIn('not valid under optional stopping', result['interval_label'])
            with self.assertRaises(ValueError):
                test.add(.5)

    def test_futility_and_cap_are_inconclusive(self):
        test = stats.SequentialTest('improvement', max_pairs=31)
        midpoint = (stats.elo_score(0)+stats.elo_score(20))/2
        for i in range(30):
            test.add(midpoint + (.2 if i%2 else -.2))
        self.assertEqual('futility', test.result()['decision'])
        self.assertEqual('INCONCLUSIVE', test.result()['outcome'])
        self.assertLess(test.result()['either_bound_probability'], .1)
        test = stats.SequentialTest('improvement', max_pairs=3)
        for _ in range(3):
            test.add(.5)
        self.assertEqual('max_pairs', test.result()['decision'])
        self.assertEqual('INCONCLUSIVE', test.result()['outcome'])
        self.assertEqual([0.,1.], test.result()['normal_interval_95'])
        self.assertIn('uninformative', test.result()['interval_label'])

    def test_fixed_results_and_incomplete_samples(self):
        values = [.5,.75]*100
        result = stats.fixed_result(values, expected_pairs=200)
        self.assertEqual(.625, result['score'])
        self.assertEqual('BETTER', result['outcome'])
        self.assertIn('unbiased', result['estimate_label'])
        self.assertAlmostEqual(.125**2*200/199, result['pair_variance'])
        incomplete = stats.fixed_result(values, expected_pairs=201)
        self.assertEqual('INCONCLUSIVE', incomplete['outcome'])
        self.assertIsNone(incomplete['p_value'])
        for values in [[], [.5], [.5]*5]:
            result = stats.fixed_result(values, expected_pairs=5)
            self.assertEqual('INCONCLUSIVE', result['outcome'])
            self.assertEqual([0.,1.], result['normal_interval_95'])
            self.assertIn('uninformative', result['interval_label'])

    def test_input_validation_and_initial_result(self):
        test = stats.SequentialTest('improvement', max_pairs=5)
        self.assertEqual(0, test.result()['n'])
        self.assertEqual([0.,1.], test.result()['normal_interval_95'])
        for value in [None, True, -.1, 1.1, float('nan'), float('inf')]:
            with self.assertRaises(ValueError):
                test.add(value)
        self.assertEqual(0, test.result()['n'])
        for cap in [0, -1, True, 1.5]:
            with self.assertRaises(ValueError):
                stats.SequentialTest('improvement', max_pairs=cap)
        with self.assertRaises(ValueError):
            stats.SequentialTest('bad')

    def test_nearly_degenerate_likelihood_remains_finite(self):
        test = stats.SequentialTest('improvement')
        for value in [.5, .5+1e-12]:
            result = test.add(value)
        self.assertTrue(math.isfinite(result['llr']))
        self.assertLess(result['llr'], 0)

    def test_benchmark_1400_additions(self):
        test = stats.SequentialTest('improvement', max_pairs=10000)
        midpoint = (stats.elo_score(0)+stats.elo_score(20))/2
        start = time.perf_counter()
        for i in range(1400):
            test.add(midpoint + (1 if i%2 else -1)*math.sqrt(.0875))
        elapsed = time.perf_counter()-start
        self.assertEqual(1400, test.result()['n'])
        self.assertFalse(test.decided)
        print(f'\nSequentialTest 1400 additions (including Brownian): {elapsed:.6f} seconds', flush=True)


if __name__ == '__main__':
    unittest.main()
