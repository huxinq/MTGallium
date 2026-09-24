"""Secondary pilot statistics. The resampling unit is a complete setup pair."""
from collections import Counter
import math
import random
import statistics


def _variance(values):
    return statistics.variance(values) if len(values) > 1 else 0.0


def _beta(raw, luck):
    variance = _variance(luck)
    # An evaluator insensitive to card identity can produce only summation noise.
    # Do not turn roundoff into enormous fitted control-variate coefficients.
    if variance <= 1e-24:
        return 0.0
    a, b = statistics.mean(raw), statistics.mean(luck)
    return sum((x-a)*(y-b) for x, y in zip(raw, luck)) / ((len(raw)-1)*variance)


def _crossfit(raw, luck, folds):
    betas = {fold: _beta([x for x, f in zip(raw, folds) if f != fold],
                          [x for x, f in zip(luck, folds) if f != fold])
             for fold in (0, 1)}
    return [x-betas[fold]*y for x, y, fold in zip(raw, luck, folds)], betas


def _interval(values):
    if len(values) < 2:
        return None
    mean = statistics.mean(values)
    radius = 1.959963984540054 * math.sqrt(_variance(values)/len(values))
    # Control variates can leave [0,1]; clipping would bias their mean.
    return [mean-radius, mean+radius]


def summarize_luck(games, *, bootstrap=1000, seed=1701):
    """Cross-fit on opposite even/odd setup folds, never on an individual game.

    Bootstrap resamples pairs within folds and refits beta each time. Intervals
    are exploratory percentile intervals, not sequential decision inputs.
    """
    by_seed = {}
    for game in games:
        by_seed.setdefault(game['seed'], []).append(game)
    pairs = [pair for _, pair in sorted(by_seed.items()) if len(pair) == 2
             and all('luck' in game and game['status'] not in ('ERROR', 'UNEXECUTED') for game in pair)]
    if len(pairs) < 4:
        return {'status': 'insufficient_pairs', 'pairs': len(pairs)}
    raw = [sum(game['payoff'] if game['payoff'] is not None else 0 for game in pair)/2 for pair in pairs]
    folds = [i % 2 for i in range(len(pairs))]
    names = sorted(set.intersection(*(set(game['luck']['models']) for pair in pairs for game in pair)))
    result = {'status': 'pilot_only', 'decision_input': False, 'pairs': len(pairs),
              'raw_mean': statistics.mean(raw), 'raw_normal_95': _interval(raw),
              'raw_pair_variance': _variance(raw), 'models': {},
              'beta_zero_variance_threshold': 1e-24,
              'bootstrap': {'unit': 'setup pair, stratified by cross-fit fold', 'replicates': bootstrap, 'seed': seed}}
    for name in names:
        luck = [sum(game['luck']['models'][name]['sum'] for game in pair)/2 for pair in pairs]
        game_raw = [[game['payoff'] if game['payoff'] is not None else 0 for game in pair] for pair in pairs]
        game_luck = [[game['luck']['models'][name]['sum'] for game in pair] for pair in pairs]
        corrected = [x-y for x, y in zip(raw, luck)]
        crossfit, betas = _crossfit(raw, luck, folds)
        ratios = {'beta_one': [], 'cross_fitted': []}
        game_ratios = []
        generator = random.Random(seed)
        indices = [[i for i, f in enumerate(folds) if f == fold] for fold in (0, 1)]
        for _ in range(bootstrap):
            sample = [generator.choice(group) for group in indices for _ in group]
            x, y, f = ([values[i] for i in sample] for values in (raw, luck, folds))
            variance = _variance(x)
            if variance:
                ratios['beta_one'].append(_variance([a-b for a, b in zip(x, y)])/variance)
                ratios['cross_fitted'].append(_variance(_crossfit(x, y, f)[0])/variance)
            gx = [v for i in sample for v in game_raw[i]]
            gy = [v for i in sample for v in game_luck[i]]
            if _variance(gx):
                game_ratios.append(_variance([a-b for a, b in zip(gx, gy)])/_variance(gx))
        model = {'luck_pair_mean': statistics.mean(luck), 'luck_pair_normal_95': _interval(luck),
                 'cross_fit_betas': betas, 'events': {}}
        gx = [v for pair in game_raw for v in pair]
        gy = [v for pair in game_luck for v in pair]
        samples = sorted(game_ratios)
        model['beta_one_game_variance_ratio'] = {
            'ratio': _variance([a-b for a, b in zip(gx, gy)])/_variance(gx) if _variance(gx) else None,
            'pair_cluster_bootstrap_95': [samples[int(.025*(len(samples)-1))],
                                          samples[int(.975*(len(samples)-1))]] if samples else None}
        for key, values in (('beta_one', corrected), ('cross_fitted', crossfit)):
            samples = sorted(ratios[key])
            model[key] = {'mean': statistics.mean(values), 'normal_95': _interval(values),
                          'pair_variance': _variance(values),
                          'variance_ratio': _variance(values)/_variance(raw) if _variance(raw) else None,
                          'variance_ratio_bootstrap_95': [samples[int(.025*(len(samples)-1))],
                                                          samples[int(.975*(len(samples)-1))]] if samples else None}
        kinds = sorted({event['kind'] for pair in pairs for game in pair for event in game['luck']['events']})
        for kind in kinds:
            pair_sums, counts, statuses = [], [], Counter()
            for pair in pairs:
                events = [event for game in pair for event in game['luck']['events'] if event['kind'] == kind]
                statuses.update(event['status'] for event in events)
                retained = [event for event in events if event['status'] == 'retained']
                pair_sums.append(sum(event['weightedLuck'][name] for event in retained))
                counts.append(len(retained))
            total = sum(counts)
            mean = sum(pair_sums)/total if total else None
            # Cluster-robust delta-method interval for sum(luck)/count(events).
            radius = (1.959963984540054 * math.sqrt(len(pairs) * _variance(
                [s-mean*c for s, c in zip(pair_sums, counts)]))/total) if total else None
            model['events'][kind] = {'statuses': dict(statuses), 'count': total, 'mean_luck': mean,
                                      'cluster_normal_95': [mean-radius, mean+radius] if total else None}
        result['models'][name] = model
    return result
