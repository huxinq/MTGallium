"""Frozen seeds and approximate normal sequential inference, without dependencies.

Integration: allocate(evidence_root, 'development', count) for opt-in development;
allocate(root, 'confirmation', count, claim=predeclared_claim) BEFORE any play.
Persist the entire returned dict in the run row. Positions are zero-based and
stop is exclusive. A confirmation reservation is consumed even if play fails.
Pass explicit seeds through reproduction_seeds(), never through allocation.

Feed ONE complete, seat-swapped pair mean in [0, 1] to SequentialTest.add().
Stop when decision != 'continue'. Its cap is independent of legacy setups.
Normal GSPRT profiles the unknown variance separately under each point mean.
Wald error targets and Brownian futility are approximations for bounded pair
scores, not exact error guarantees. Futility/cap are inconclusive, not H0 wins.
Futility is judged under the design hypotheses, not the running estimate: stop
only when neither a true H1 would reach the upper bound nor a true H0 the lower
bound within the remaining budget with probability >= FUTILITY.
The reported normal interval is descriptive, NOT a confidence sequence.

Brownian survival integrates the killed interval heat kernel (image expansion
at short times, sine expansion otherwise), with the exponential drift tilt.
See https://www.stat.uchicago.edu/~lalley/Courses/312/BrownianMotion312.pdf,
the absorbing interval transition-density exercise. This computes the union
of boundary hits, rather than adding two overlapping one-bound probabilities.
"""

from contextlib import contextmanager
import fcntl
import hashlib
import json
import math
import os
from pathlib import Path
import secrets
from statistics import NormalDist
import tempfile


POOL_SIZES = {'development': 8192, 'confirmation': 32768}
PLANNING_VARIANCE = 0.0875
ALPHA = BETA = 0.05
POWER = 0.95
FUTILITY = 0.1
HYPOTHESES = {'improvement': (0.0, 20.0), 'non_regression': (-10.0, 0.0)}
_NORMAL = NormalDist()


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()


def _sha(value):
    return hashlib.sha256(_canonical(value)).hexdigest()


def _integer(value, name, minimum=0):
    if type(value) is not int or value < minimum:
        raise ValueError(f'{name} must be an integer >= {minimum}')


def reproduction_seeds(seeds):
    """Validate explicit seeds; this grants no fresh-confirmation eligibility."""
    values = list(seeds)
    if not values or any(type(s) is not int or not 0 <= s < 2**63 for s in values):
        raise ValueError('seeds must be nonempty 63-bit nonnegative integers')
    if len(set(values)) != len(values):
        raise ValueError('duplicate seeds')
    return {'kind': 'reproduction', 'seeds': values, 'sha256': _sha(values),
            'fresh_confirmation': False}


@contextmanager
def _locked(directory):
    with (directory / '.seed-pools.lock').open('a+b') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(lock, fcntl.LOCK_UN)


def _sync_directory(directory):
    descriptor = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _load_pools(directory):
    path = directory / 'seed-pools.json'
    ledger = directory / 'confirmation-reservations.jsonl'
    if not path.exists():
        if ledger.exists():
            raise ValueError('missing frozen pools; refusing to regenerate')
        seen, pools = set(), {}
        for kind, size in POOL_SIZES.items():
            values = []
            while len(values) < size:
                seed = secrets.randbits(63)
                if seed not in seen:
                    seen.add(seed)
                    values.append(seed)
            pools[kind] = values
        payload = {'version': 1, 'pools': pools}
        document = {**payload, 'sha256': _sha(payload)}
        fd, temporary = tempfile.mkstemp(dir=directory, prefix='.seed-pools-')
        try:
            with os.fdopen(fd, 'wb') as stream:
                stream.write(_canonical(document) + b'\n')
                stream.flush()
                os.fsync(stream.fileno())
                os.fchmod(stream.fileno(), 0o444)
            os.replace(temporary, path)
            with ledger.open('xb') as stream:
                stream.flush()
                os.fsync(stream.fileno())
            _sync_directory(directory)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
    if not ledger.exists():
        raise ValueError('missing reservation ledger; refusing unsafe reuse')
    document = json.loads(path.read_text())
    payload = {'version': document['version'], 'pools': document['pools']}
    if document['version'] != 1 or document['sha256'] != _sha(payload):
        raise ValueError('frozen pool identity mismatch')
    pools = document['pools']
    if set(pools) != set(POOL_SIZES):
        raise ValueError('invalid pool kinds')
    all_seeds = []
    for kind, size in POOL_SIZES.items():
        if len(pools[kind]) != size:
            raise ValueError('invalid frozen pool size')
        all_seeds.extend(reproduction_seeds(pools[kind])['seeds'])
    if len(set(all_seeds)) != len(all_seeds):
        raise ValueError('overlapping frozen seed pools')
    return document, ledger


def allocate(root, kind, count, claim=None, start=None, declaration=None, resume=False):
    """Allocate a frozen contiguous slice; confirmation is locked and append-only.

    root must be an existing evidence directory. Development defaults to offset
    zero and is reusable. Confirmation defaults to the next unused tail; every
    claim (nonempty string or JSON object describing the predeclared comparison)
    may be reserved only once, globally in this evidence root. Explicit offsets
    cannot overlap ANY prior reservation, even under another claim. Store model,
    opponent, hypothesis and sample-size commitments in declaration before play.
    declaration is an optional JSON object, durably stored beside the claim.
    resume=True can recover the same reservation only when the declaration
    contains the original checkpoint_id and all committed fields match.
    POSIX advisory locks require a filesystem providing coherent flock semantics.
    """
    if kind not in POOL_SIZES:
        raise ValueError('kind must be development or confirmation')
    _integer(count, 'count', 1)
    if start is not None:
        _integer(start, 'start')
    if kind == 'confirmation':
        if not isinstance(claim, (str, dict)) or not claim or (isinstance(claim, str) and not claim.strip()):
            raise ValueError('confirmation requires a predeclared claim')
        claim = json.loads(_canonical(claim))
    elif claim is not None:
        raise ValueError('claims belong only to confirmation')
    if declaration is not None:
        if kind != 'confirmation' or not isinstance(declaration, dict):
            raise ValueError('declaration must be a confirmation JSON object')
        declaration = json.loads(_canonical(declaration))
    root = Path(root)
    if not root.is_dir():
        raise ValueError('evidence root must already exist')
    directory = root / 'ladder'
    directory.mkdir(exist_ok=True)
    with _locked(directory):
        document, ledger = _load_pools(directory)
        reservations, previous = [], None
        with ledger.open() as stream:
            for line in stream:
                if not line.endswith('\n'):
                    raise ValueError('incomplete reservation ledger; refusing reuse')
                row = json.loads(line)
                digest = row.pop('sha256')
                if digest != _sha(row) or row['previous'] != previous or row['pool_sha256'] != document['sha256']:
                    raise ValueError('reservation ledger identity mismatch')
                _integer(row['start'], 'ledger start')
                _integer(row['stop'], 'ledger stop', row['start'] + 1)
                if row['stop'] > POOL_SIZES['confirmation']:
                    raise ValueError('reservation outside pool')
                if any(row['claim'] == old['claim'] or
                       max(row['start'], old['start']) < min(row['stop'], old['stop'])
                       for old in reservations):
                    raise ValueError('reused confirmation in ledger')
                reservations.append(row)
                previous = digest
        recovered = None
        if kind == 'confirmation' and resume:
            for row in reservations:
                if row['claim'] == claim:
                    if (not declaration or not declaration.get('checkpoint_id') or
                            row['declaration'] != declaration or row['stop'] - row['start'] != count or
                            (start is not None and start != row['start'])):
                        raise ValueError('confirmation checkpoint reservation mismatch')
                    recovered = dict(row, sha256=_sha(row))
                    start = row['start']
        if start is None:
            start = max((r['stop'] for r in reservations), default=0) if kind == 'confirmation' else 0
        stop = start + count
        if stop > POOL_SIZES[kind]:
            raise ValueError('seed pool exhausted')
        if recovered is not None:
            record = recovered
        elif kind == 'confirmation':
            for row in reservations:
                if row['claim'] == claim:
                    raise ValueError('claim already reserved; use explicit seeds for reproduction')
                if max(start, row['start']) < min(stop, row['stop']):
                    raise ValueError('confirmation reservation overlaps prior use')
            record = {'claim': claim, 'declaration': declaration,
                      'pool_sha256': document['sha256'],
                      'start': start, 'stop': stop, 'previous': previous}
            record['sha256'] = _sha(record)
            with ledger.open('ab') as stream:
                stream.write(_canonical(record) + b'\n')
                stream.flush()
                os.fsync(stream.fileno())
        seeds = document['pools'][kind][start:stop]
        return {'kind': kind, 'seeds': seeds, 'positions': list(range(start, stop)),
                'start': start, 'stop': stop, 'pool_sha256': document['sha256'],
                'sha256': _sha(seeds), 'claim': claim,
                'seed_pool_sha256': _sha(document['pools'][kind]),
                'declaration': declaration,
                'reservation_sha256': record['sha256'] if kind == 'confirmation' else None,
                'fresh_confirmation': kind == 'confirmation'}


def elo_score(elo):
    if not math.isfinite(elo):
        raise ValueError('Elo must be finite')
    x = elo * math.log(10) / 400
    return 1 / (1 + math.exp(-x)) if x >= 0 else math.exp(x) / (1 + math.exp(x))


def _hypotheses(mode):
    if mode not in HYPOTHESES:
        raise ValueError('mode must be improvement or non_regression')
    return tuple(elo_score(e) for e in HYPOTHESES[mode])


def fixed_confirmation_size(mode='improvement', *, variance=PLANNING_VARIANCE,
                            alpha=ALPHA, power=POWER, two_sided=True):
    """Fixed normal size: ceil(v*(z(1-alpha/2)+z(power))²/delta²).

    The default sequential budget is sequential_cap(), not this size.
    Fix this size before confirmation; no sequential peeking in fixed analysis.
    Non-regression is powered at 0 Elo against the -10 Elo null boundary.
    """
    if not math.isfinite(variance) or variance <= 0 or not 0 < alpha < .5 or not .5 < power < 1:
        raise ValueError('invalid planning variance, alpha or power')
    lo, hi = _hypotheses(mode)
    return math.ceil(variance * (_NORMAL.inv_cdf(1-alpha/(2 if two_sided else 1)) +
                                _NORMAL.inv_cdf(power))**2 / (hi-lo)**2)


def sequential_cap(mode='improvement'):
    """Default sequential budget: twice the fixed size, within the development pool.

    The SPRT's stopping time has a long right tail. At the fixed size about 5% of
    runs at either design point reach the cap, cutting power from .95 to .90; at
    twice the fixed size 0.2% do, for about 4% more expected pairs there (exact
    lattice calculation, pair variance .0875). Non-regression's doubled size
    exceeds the development pool, so it is capped at the pool (1.49x, ~.945 power).
    """
    return min(2*fixed_confirmation_size(mode), POOL_SIZES['development'])


def planning_design(mode='improvement'):
    """Persist these assumptions with the selected cap or confirmation size."""
    return {'variance': PLANNING_VARIANCE, 'power': POWER, 'alpha': ALPHA,
            'two_sided': True, 'hypotheses_elo': list(HYPOTHESES[mode]),
            'formula': 'ceil(variance * (z(1-alpha/2) + z(power))^2 / score_delta^2)',
            'pairs': fixed_confirmation_size(mode)}


def fixed_result(values, mode='improvement', *, expected_pairs):
    """Descriptive fixed analysis and two-sided normal H0 test at alpha=.05.

    expected_pairs must be declared before sampling. The helper cannot verify
    that commitment or independence: persist the confirmation claim/reservation.
    A missing pair is not a draw; incomplete data have no test decision. A
    zero empirical variance has no calibrated normal test; its interval is [0,1].
    Non-rejection is not acceptance of H0. No claim of sequential validity.
    """
    mu0, _ = _hypotheses(mode)
    _integer(expected_pairs, 'expected_pairs', 1)
    values = list(values)
    if len(values) > expected_pairs:
        raise ValueError('more pairs than the predeclared sample size')
    if any(isinstance(v, bool) or not isinstance(v, (int, float)) or
           not math.isfinite(v) or not 0 <= v <= 1 for v in values):
        raise ValueError('values must be complete pair means in [0, 1]')
    n = len(values)
    mean = math.fsum(values)/n if n else None
    variance = math.fsum((v-mean)**2 for v in values)/(n-1) if n > 1 else None
    interval, z, p = [0.0, 1.0], None, None
    informative = variance is not None and variance > 0
    if variance is not None and variance > 0:
        se = math.sqrt(variance/n)
        radius = _NORMAL.inv_cdf(.975)*se
        interval = [max(0., mean-radius), min(1., mean+radius)]
        z = (mean-mu0)/se
        p = math.erfc(abs(z)/math.sqrt(2))
    complete = n == expected_pairs
    decision = ('reject_h0' if p is not None and p <= ALPHA else
                'do_not_reject_h0' if p is not None else 'insufficient_variance') if complete else 'incomplete'
    outcome = 'INCONCLUSIVE'
    if complete and informative:
        if interval[0] > mu0:
            outcome = 'BETTER' if mode == 'improvement' else 'NON_INFERIOR'
        elif interval[1] < mu0:
            outcome = 'NOT_BETTER' if mode == 'improvement' else 'INFERIOR'
    return {'n': n, 'pairs': n, 'expected_pairs': expected_pairs, 'mode': mode,
            'score': mean, 'pair_variance': variance, 'normal_interval_95': interval,
            'z': z if complete else None, 'p_value': p if complete else None,
            'decision': decision, 'outcome': outcome, 'alpha': ALPHA,
            'planning_design': planning_design(mode),
            'interval_label': ('fixed-sample normal approximation; not valid under optional stopping' if informative else
                               'uninformative [0,1]; insufficient data or zero variance; no calibrated normal interval'),
            'estimate_label': 'unbiased mean under prespecified independent sampling' if complete else
                              'incomplete sample; descriptive mean only'}


def _log_cdf(x):
    p = .5 * math.erfc(-x / math.sqrt(2))
    if p:
        return math.log(p)
    # Mills expansion only in the far negative tail where erfc underflows.
    u = 1 / (x*x)
    return -.5*x*x - math.log(-x) - .5*math.log(2*math.pi) + math.log(1-u+3*u*u-15*u**3+105*u**4)


def _log_normal_interval(lo, hi):
    if lo >= 0:
        lo, hi = -hi, -lo
    a, b = _log_cdf(lo), _log_cdf(hi)
    return b + math.log(-math.expm1(a-b)) if a < b else -math.inf


def brownian_either_bound(current, lower, upper, remaining, drift, variance):
    """P(exit [lower,upper] by remaining) for X=current+drift*t+sqrt(v)*W.

    Integrates the absorbing heat kernel; continuous monitoring and constant
    supplied drift/variance are assumptions. Series truncation targets ~1e-14
    absolute error, not a guarantee for arbitrary floating-point extremes.
    variance is per pair, NOT a standard deviation or standard error.
    """
    if not all(math.isfinite(v) for v in (current, lower, upper, remaining, drift, variance)):
        raise ValueError('Brownian inputs must be finite')
    if lower >= upper or remaining < 0 or variance < 0:
        raise ValueError('invalid Brownian bounds, time or variance')
    if current <= lower or current >= upper:
        return 1.0
    if remaining == 0:
        return 0.0
    if variance == 0:
        return float(current + drift*remaining <= lower or current + drift*remaining >= upper)
    width = upper-lower
    x, tau, a = (current-lower)/width, variance*remaining/width**2, drift*width/variance
    sd = math.sqrt(tau)
    center = x+a*tau
    # An endpoint outside implies an earlier exit; omit <1e-14 survival.
    if .5*math.erfc(center/sd/math.sqrt(2)) + .5*math.erfc((1-center)/sd/math.sqrt(2)) > 1-1e-14:
        return 1.0
    terms = []
    if tau < .1:
        limit = math.ceil(abs(a*tau)/2 + 4)
        for k in range(-limit, limit+1):
            for sign, base, weight in ((1, x-2*k, -2*a*k), (-1, -x-2*k, -2*a*(x+k))):
                mean = base+a*tau
                log_mass = _log_normal_interval(-mean/sd, (1-mean)/sd)
                terms.append(sign*math.exp(weight+log_mass))
    else:
        # Integral exp(a*y)*sin(n*pi*y)dy =
        # n*pi*(1-(-1)^n*exp(a))/(a²+(n*pi)²).
        left = math.exp(-a*x-.5*a*a*tau)
        right = math.exp(a*(1-x)-.5*a*a*tau)
        for n in range(1, math.ceil(math.sqrt(80/(math.pi**2*tau)))+2):
            b = n*math.pi
            terms.append(2*math.sin(b*x)*b/(a*a+b*b) *
                         (left-(-1)**n*right)*math.exp(-.5*b*b*tau))
    return max(0.0, min(1.0, 1-math.fsum(terms)))


def brownian_upper_bound(current, lower, upper, remaining, drift, variance):
    """P(exit [lower,upper] through upper by remaining) for X=current+drift*t+sqrt(v)*W.

    Unbounded-time exit probability minus the integrated upper-boundary flux after
    remaining: in unit coordinates y=(X-lower)/width, a=drift*width/v and tau=v*t/width²,
    the flux is exp(a(1-x)-a²tau/2)*pi*sum n(-1)^(n+1) sin(n pi x) exp(-n²pi²tau/2).
    The lower-bound probability follows by reflection. Same assumptions as
    brownian_either_bound, which bounds the result.
    """
    either = brownian_either_bound(current, lower, upper, remaining, drift, variance)
    if current >= upper:
        return 1.0
    if current <= lower or either == 0 or remaining == 0:
        return 0.0
    if variance == 0:
        return float(current + drift*remaining >= upper)
    width = upper-lower
    x, tau, a = (current-lower)/width, variance*remaining/width**2, drift*width/variance
    if a*(1-x) > 5:
        # A large tilt multiplies nearly cancelling terms; the opposite bound's
        # series has tilt -a*x <= 0 here, so take the complement of the union.
        return max(0.0, either-brownian_lower_bound(current, lower, upper, remaining, drift, variance))
    if a == 0:
        eventual = x
    elif abs(a) > 350:
        eventual = 1.0 if a > 0 else math.exp(2*abs(a)*(x-1))
    else:
        eventual = math.expm1(-2*a*x)/math.expm1(-2*a)
    tail = math.fsum(2*math.pi*n*(-1)**(n+1)*math.sin(n*math.pi*x)/(n*n*math.pi**2+a*a) *
                     math.exp(a*(1-x)-.5*(n*n*math.pi**2+a*a)*tau)
                     for n in range(1, math.ceil(math.sqrt(80/(math.pi**2*tau)))+2))
    return max(0.0, min(either, eventual-tail))


def brownian_lower_bound(current, lower, upper, remaining, drift, variance):
    """P(exit through lower by remaining); the reflection of brownian_upper_bound."""
    return brownian_upper_bound(-current, -upper, -lower, remaining, -drift, variance)


def _design_reach(lower, upper, current, remaining, mu0, mu1, pair_variance):
    """P(H1 reaches upper), P(H0 reaches lower) within remaining pairs.

    Under the linearized LLR n*delta*(mean-midpoint)/v each pair adds mean
    ±delta²/(2v) under H1/H0 and variance delta²/v, with v the pair variance.
    """
    step = (mu1-mu0)**2/pair_variance
    return (brownian_upper_bound(current, lower, upper, remaining, step/2, step),
            brownian_lower_bound(current, lower, upper, remaining, -step/2, step))


class SequentialTest:
    """Normal profile GSPRT with Wald bounds, a fixed cap and Brownian futility.

    add(value) returns the same JSON-ready shape as result(); terminal decisions
    are 'accept_h1', 'accept_h0', 'futility', 'max_pairs'. Terminal tests reject
    further observations. A minimum of 30 pairs and positive empirical variance
    are required for inferential stopping; degenerate data continue to the cap.
    Futility stops only when both design forecasts (H1 reaching the upper bound,
    H0 reaching the lower bound within the remaining pairs) fall below FUTILITY,
    so a slow start cannot end a comparison that the budget could still decide.
    either_bound_probability remains a descriptive forecast at the current drift
    (LLR/n, delta-method variance); it is not a stopping input.
    """

    def __init__(self, mode, max_pairs=None):
        self.mu0, self.mu1 = _hypotheses(mode)
        self.mode = mode
        self.max_pairs = sequential_cap(mode) if max_pairs is None else max_pairs
        _integer(self.max_pairs, 'max_pairs', 1)
        self.n, self.mean, self.m2 = 0, 0.0, 0.0
        self.decision = 'continue'
        self.lower, self.upper = math.log(BETA/(1-ALPHA)), math.log((1-BETA)/ALPHA)

    @property
    def decided(self):
        """True for every terminal state, including inconclusive stops."""
        return self.decision != 'continue'

    def add(self, value):
        if self.decision != 'continue':
            raise ValueError('sequential test already stopped')
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not 0 <= value <= 1:
            raise ValueError('value must be a complete pair mean in [0, 1]')
        self.n += 1
        delta = value-self.mean
        self.mean += delta/self.n
        self.m2 += delta*(value-self.mean)
        result = self.result()
        if self.n >= 30 and self.m2 > 0:
            if result['llr'] >= self.upper:
                self.decision = 'accept_h1'
            elif result['llr'] <= self.lower:
                self.decision = 'accept_h0'
        if self.decision == 'continue' and self.n >= self.max_pairs:
            self.decision = 'max_pairs'
        elif (self.decision == 'continue' and self.n >= 30 and self.m2 > 0 and
              max(result['design_reach_probabilities'].values()) < FUTILITY):
            self.decision = 'futility'
        return self.result()

    def result(self):
        variance = self.m2/(self.n-1) if self.n > 1 else None
        llr, probability, reach = 0.0, None, None
        if self.n and self.m2 > 0:
            v = self.m2/self.n
            v0, v1 = v+(self.mean-self.mu0)**2, v+(self.mean-self.mu1)**2
            relative = (v0-v1)/v1
            # log1p preserves precision near equal fits; separate logs avoid
            # rounding the ratio to -1 for nearly deterministic null data.
            log_ratio = math.log1p(relative) if abs(relative) < .5 else math.log(v0)-math.log(v1)
            llr = self.n/2 * log_ratio
            slope = (self.mean-self.mu0)/v0 - (self.mean-self.mu1)/v1
            probability = brownian_either_bound(llr, self.lower, self.upper,
                                               max(0, self.max_pairs-self.n),
                                               llr/self.n, slope*slope*variance)
            if variance:
                h1, h0 = _design_reach(self.lower, self.upper, llr, max(0, self.max_pairs-self.n),
                                       self.mu0, self.mu1, variance)
                reach = {'h1_reaches_upper': h1, 'h0_reaches_lower': h0}
        interval = [0.0, 1.0]
        if variance is not None and variance > 0:
            radius = _NORMAL.inv_cdf(.975)*math.sqrt(variance/self.n)
            interval = [max(0.0, self.mean-radius), min(1.0, self.mean+radius)]
        stopped = self.decision != 'continue'
        outcome = 'INCONCLUSIVE'
        if self.decision == 'accept_h1':
            outcome = 'BETTER' if self.mode == 'improvement' else 'NON_INFERIOR'
        elif self.decision == 'accept_h0':
            outcome = 'NOT_BETTER' if self.mode == 'improvement' else 'INFERIOR'
        return {'mode': self.mode, 'n': self.n, 'pairs': self.n, 'max_pairs': self.max_pairs,
                'decision': self.decision, 'outcome': outcome, 'stopped': stopped,
                'score': self.mean if self.n else None, 'pair_variance': variance,
                'llr': llr, 'lower_bound': self.lower, 'upper_bound': self.upper,
                'either_bound_probability': probability, 'design_reach_probabilities': reach,
                'futility_rule': 'design-hypotheses-v1', 'futility_threshold': FUTILITY,
                'alpha': ALPHA, 'beta': BETA, 'hypotheses_elo': list(HYPOTHESES[self.mode]),
                'planning_design': planning_design(self.mode),
                'normal_interval_95': interval,
                'interval_label': ('descriptive normal approximation; not valid under optional stopping'
                                   if variance is not None and variance > 0 else
                                   'uninformative [0,1]; insufficient data or zero variance; no calibrated normal interval'),
                'estimate_label': ('stopped estimate; promoted estimates are biased upward; '
                                   'lower-bound stops may be biased downward') if stopped else 'running estimate'}
