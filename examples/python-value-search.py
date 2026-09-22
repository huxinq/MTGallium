"""Compare native search with hand-authored value weights at two decision horizons.

Run from the checkout: python3 examples/python-value-search.py
"""
import json
import argparse
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from research_workspace import Session


# Feature-key components are unpadded URL-safe Base64: life/root and life/opponent.
weights = {'bias': 0., 'weights': {
    'player/bGlmZQ/cm9vdA': .2,
    'player/bGlmZQ/b3Bwb25lbnQ': -.2,
}}
deck = {'Mountain': 8, 'Lightning Bolt': 4}

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--no-build', action='store_true', help='Reuse the last compiled JVM output')
args = parser.parse_args()

with Session(build=not args.no_build, java_options=['-Xmx1g']) as research:
    for horizon in (2, 8):
        with research.game([deck, deck], seed=17, policies=('search', 'heuristic'),
                           skip_mulligans=True, starting_hand_size=3,
                           particles=1, simulations=4, search_depth=horizon,
                           value_weights=weights, exploration_constant=1.4,
                           leaf={'stateSource': 'BOUNDED_ROLLOUT', 'cutoff': 'EVALUATE'}) as game:
            estimates = []
            def choose(decision):
                action = game.select('search')
                if action.search is not None:
                    diagnostics = action.search['diagnostics']
                    estimates.append({'decision': decision.index, 'choice': action.label,
                                      'value': action.search['rootValue'],
                                      'rollout_decisions': diagnostics['rootRolloutDecisions'] +
                                                           diagnostics['opponentRolloutDecisions']})
                return action
            result = game.play({'p0': choose}, decision_limit=32)
            print(json.dumps({'decision_horizon': horizon, 'estimates': estimates, 'game': result}))
