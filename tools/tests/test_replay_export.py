import gzip
import json
import tempfile
import unittest
from pathlib import Path

from tools.replay_export import check_state, checked, delta, export_game, hindsight, snapshot


def view(owner):
    return {
        'cards': {'public': {'name': 'Mountain'}, owner: {'name': owner}},
        'zones': [
            {'zoneId': {'ownerId': 'e0', 'zoneType': 'Hand'},
             'cardIds': ['p0'] if owner == 'p0' else [], 'size': 1, 'isVisible': owner == 'p0'},
            {'zoneId': {'ownerId': 'e1', 'zoneType': 'Hand'},
             'cardIds': ['p1'] if owner == 'p1' else [], 'size': 1, 'isVisible': owner == 'p1'},
        ],
        'players': [{'playerId': 'e0', 'name': 'Player 0'},
                    {'playerId': 'e1', 'name': 'Player 1'}],
        'currentPhase': 'MAIN', 'currentStep': 'MAIN1',
        'activePlayerId': 'p0', 'priorityPlayerId': 'p0',
        'turnNumber': 1, 'isGameOver': False, 'winnerId': None,
        'combat': None, 'gameLog': [],
    }


class ReplayExportTest(unittest.TestCase):
    def test_hand_views_and_hindsight(self):
        views = {'p0': view('p0'), 'p1': view('p1')}
        both = hindsight(views)
        self.assertNotIn('p1', views['p0']['cards'])
        self.assertNotIn('p0', views['p1']['cards'])
        self.assertEqual({'public', 'p0', 'p1'}, set(both['cards']))
        hands = {z['zoneId']['ownerId']: z['cardIds'] for z in both['zones']}
        self.assertEqual({'e0': ['p0'], 'e1': ['p1']}, hands)

    def test_wire_delta_appends_log_and_changes_board(self):
        first = snapshot('sample', 'p0', view('p0'), [])
        next_view = view('p0')
        next_view['cards']['new'] = {'name': 'Goblin'}
        second = snapshot('sample', 'p0', next_view,
                          [{'type': 'system', 'playerId': 'p0', 'description': 'Choice'}])
        wire = delta(first, second)
        state = wire['gameStateDelta']
        self.assertEqual({'new': {'name': 'Goblin'}}, state['addedCards'])
        self.assertEqual(second['gameState']['gameLog'], state['newLogEntries'])
        self.assertEqual(second['gameState']['zones'], state['updatedZones'])

    def test_divergence_stops_before_action(self):
        class FakeGame:
            def state(self): return {'turn': 2}
            def close(self): pass
            def _call(self, *_args, **_kwargs):
                raise AssertionError('Action applied after divergence')

        class FakeSession:
            def game(self, *_args, **_kwargs): return FakeGame()
            def _call(self, *_args, **_kwargs):
                return {'p0': view('p0'), 'p1': view('p1')}

        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / 'game.jsonl.gz'
            with gzip.open(source, 'wt') as stream:
                stream.write(json.dumps({'error': None}) + '\n')
                stream.write(json.dumps({'gameId': 'sample', 'seed': 1,
                                         'policies': ['a', 'b'],
                                         'result': {'payoffs': {'p0': 1, 'p1': -1}},
                                         'startingState': {'turn': 1}}) + '\n')
                stream.write(json.dumps({'type': 'end', 'privilegedFinalState': {'turn': 2}}) + '\n')
            result = export_game(FakeSession(), source, {}, {}, Path(folder))
            self.assertIn('diverged at decision start', result['divergence'])
            with gzip.open(Path(folder) / 'sample-p0.json.gz', 'rt') as stream:
                self.assertEqual('DIVERGED', json.load(stream)['metadata']['fidelity'])

    def test_random_resolution_keys_keep_reference_identity(self):
        first = 'e1020c41-eba8-4807-a820-c507e354a31b'
        second = 'c3afb571-17c4-4bf5-8c16-873eaa1c22ee'
        other = 'b3afb571-17c4-4bf5-8c16-873eaa1c22ee'
        checked({'keys': [first, first]}, {'keys': [second, second]}, 'sample', 1)
        with self.assertRaisesRegex(ValueError, 'diverged'):
            checked({'keys': [first, first]}, {'keys': [second, other]}, 'sample', 1)

    def test_generated_ability_ids_keep_identity_across_decisions(self):
        identities = {}
        check_state({'id': 'ability_100'}, {'id': 'ability_200'}, 'sample', 1, identities)
        check_state({'id': 'ability_100'}, {'id': 'ability_200'}, 'sample', 2, identities)
        with self.assertRaisesRegex(ValueError, 'diverged at decision 3'):
            check_state({'id': 'ability_100'}, {'id': 'ability_201'}, 'sample', 3, identities)


if __name__ == '__main__':
    unittest.main()
