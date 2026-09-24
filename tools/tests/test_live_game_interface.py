"""Real public-fixture journeys through Python, its pipe, and the current game engine."""
import copy
from itertools import product
import math
from dataclasses import replace
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from research_workspace import Action, Game, ResearchError, Session


class LiveGameInterfaceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.session = Session(java_options=['-Xmx1g'])

    @classmethod
    def tearDownClass(cls):
        cls.session.close()

    def game(self, **options):
        settings = dict(seed=17, starting_hand_size=0, skip_mulligans=True)
        settings.update(options)
        return self.session.game([{'Mountain': 7}, {'Mountain': 7}], **settings)

    def test_independent_games_and_forks_share_one_jvm(self):
        pid = self.session.pid
        with self.game() as first, self.game(seed=18) as second:
            with first.fork() as branch:
                self.assertEqual(pid, branch.session.pid)
                branch.step(0)
                self.assertEqual(0, first.status()['index'])
                self.assertEqual(0, second.status()['index'])
                self.assertEqual(1, branch.status()['index'])
        self.assertEqual(pid, self.session.pid)

    def test_factual_branch_preserves_parent_and_continuing_coordinates(self):
        with self.game() as game:
            game.step(0)
            before = game.state()
            decision = game.decision()
            with game.fork() as branch:
                result = branch.step(decision.actions[0])
                self.assertEqual(1, result['decision']['index'])
                self.assertEqual(2, branch.status()['index'])
                self.assertEqual(before, game.state())
                game.step(decision.actions[0])
                self.assertEqual(game.state(), branch.state())

    def test_stale_or_altered_choices_do_not_advance_the_game(self):
        with self.game() as game:
            action = game.decision().actions[0]
            game.step(action)
            status = game.status()
            with self.assertRaises(ResearchError):
                game.step(action)
            self.assertEqual(status, game.status())
            current = game.decision().actions[0]
            broken = copy.deepcopy(current.choice)
            broken['signature'] = 'not-the-signature-of-this-action'
            with self.assertRaises(ResearchError):
                game.step(replace(current, choice=broken))
            self.assertEqual(status, game.status())

    def test_invalid_integer_choices_are_not_python_negative_indexing(self):
        with self.game() as game:
            for value in (-1, 100000):
                with self.subTest(value=value), self.assertRaises(IndexError):
                    game.step(value)
            with self.assertRaises(TypeError):
                game.step(True)
            self.assertEqual(0, game.status()['index'])

    def test_python_policy_receives_detached_player_information(self):
        observed = []
        def choose(decision):
            self.assertEqual(decision.actor, decision.information['actingPlayerId'])
            self.assertEqual(decision.actor, decision.information['observation']['perspectivePlayerId'])
            self.assertNotIn('components', decision.information)
            for zone in decision.information['observation']['zones']:
                if zone['ownerId'] != decision.actor and zone['zone'] in ('HAND', 'LIBRARY'):
                    self.assertFalse(zone['cards'])
            observed.append(decision.index)
            return decision.actions[0]
        with self.game(starting_hand_size=7) as game:
            original = game.information('p0')
            detached = game.decision().information
            detached['observation']['players'][0]['life'] = 123456
            self.assertEqual(original, game.information('p0'))
            rows = []
            result = game.play({'p0': choose}, decision_limit=40, record=rows.append)
            self.assertTrue(observed)
            self.assertEqual('TERMINAL', result['status'])
            self.assertIsNotNone(result['payoffs'])
            self.assertEqual(result['decisions'], len(rows))
            self.assertTrue(all(row['accepted'] for row in rows))
            self.assertEqual(list(range(len(rows))), [row['index'] for row in rows])

    def test_python_callback_exception_is_not_an_outcome_or_a_step(self):
        class PolicyFailure(Exception):
            pass
        def broken(_):
            raise PolicyFailure('deliberate test failure')
        with self.game() as game:
            before = game.state()
            with self.assertRaises(PolicyFailure):
                game.play({'p0': broken}, decision_limit=2)
            self.assertEqual(before, game.state())
            self.assertFalse(game.status()['terminal'])
            self.assertIsNone(game.status()['payoffs'])

    def test_limits_produce_no_payoff_in_native_and_python_paths(self):
        with self.game() as game:
            native = game.play(decision_limit=0)
            python = game.play({'p0': lambda decision: 0}, decision_limit=0)
            self.assertEqual(native, python)
            self.assertEqual(dict(status='DECISION_LIMIT', decisions=0, payoffs=None), native)
            with self.assertRaises(ValueError):
                game.play({'p2': lambda decision: 0})

    def test_native_play_matches_python_driven_recording_of_native_choices(self):
        with self.game(starting_hand_size=7) as game, game.fork() as branch:
            native = game.play(decision_limit=40)
            rows = []
            interactive = branch.play(decision_limit=40, record=rows.append)
            self.assertEqual(native, interactive)
            self.assertEqual('TERMINAL', native['status'])
            self.assertEqual(game.state(), branch.state())
            self.assertEqual(interactive['decisions'], len(rows))

    def test_unrecorded_mixed_play_exports_only_python_seat_decisions(self):
        for seat in ('p0', 'p1'):
            with self.subTest(seat=seat), self.game(starting_hand_size=2) as game:
                exported, chosen = [], []
                call = game._call
                def inspect(command, **arguments):
                    result = call(command, **arguments)
                    if command == 'decision':
                        exported.append(result['actor'])
                    return result
                def choose(decision):
                    chosen.append(decision.index)
                    return 0
                with patch.object(game, '_call', side_effect=inspect):
                    result = game.play({seat: choose}, decision_limit=16)
                self.assertEqual(16, result['decisions'])
                self.assertTrue(chosen)
                self.assertLess(len(chosen), result['decisions'])
                self.assertEqual([seat] * len(chosen), exported)

    def test_unrecorded_python_play_does_not_return_duplicate_decision_rows(self):
        with self.game() as game:
            replies = []
            call = game._call
            def inspect(command, **arguments):
                result = call(command, **arguments)
                if command == 'step':
                    replies.append(result)
                return result
            with patch.object(game, '_call', side_effect=inspect):
                game.play([lambda decision: 0] * 2, decision_limit=4)
            self.assertTrue(replies)
            self.assertTrue(all('decision' not in reply for reply in replies))

    def test_step_can_omit_its_record_without_changing_the_game(self):
        with self.game() as game, game.fork() as branch:
            action = game.decision().actions[0]
            recorded = game.step(action)
            lean = branch.step(action, record=False)
            self.assertTrue(recorded['decision']['accepted'])
            self.assertEqual({'status': recorded['status']}, lean)
            self.assertEqual(game.state(), branch.state())
            for player in ('p0', 'p1'):
                self.assertEqual(game.information(player), branch.information(player))
            with self.assertRaises(ResearchError):
                branch.step(action, record=False)
            self.assertEqual(game.state(), branch.state())

    def test_mixed_play_matches_recorded_actions_and_native_search_memory(self):
        # A limit of None plays the seven-card decks to completion.
        for native, seat, limit in product(('random', 'heuristic', 'search'), ('p0', 'p1'), (16, None)):
            with self.subTest(native=native, seat=seat, limit=limit), self.game(
                    starting_hand_size=2, policies=(native, native),
                    particles=1, simulations=2, search_depth=2) as game, game.fork() as branch:
                seen, rows = [], []
                def choose(decision):
                    seen.append((decision.index, decision.information, decision.actions[0].choice))
                    return 0
                result = game.play({seat: choose}, decision_limit=limit)
                expected_seen = list(seen)
                seen.clear()
                recorded = branch.play({seat: choose}, decision_limit=limit, record=rows.append)
                self.assertEqual(result, recorded)
                self.assertEqual('DECISION_LIMIT' if limit else 'TERMINAL', result['status'])
                self.assertEqual(expected_seen, seen)
                self.assertTrue(all(row['accepted'] for row in rows))
                self.assertEqual(list(range(result['decisions'])), [row['index'] for row in rows])
                self.assertEqual(game.state(), branch.state())
                for player in ('p0', 'p1'):
                    self.assertEqual(game.information(player), branch.information(player))
                if native == 'search' and limit:
                    for _ in range(2):
                        left, right = game.select('search'), branch.select('search')
                        self.assertEqual(left.choice, right.choice)
                        if left.search is not None:
                            self.assertEqual(left.search['candidates'], right.search['candidates'])
                        game.step(left)
                        branch.step(right)
                    self.assertEqual(game.state(), branch.state())

    def test_mixed_play_checks_its_deadline_after_one_native_move(self):
        with self.game() as game:
            chosen = []
            with patch('research_workspace.game.time.monotonic', side_effect=[0., .25, 2.]):
                result = game.play({'p1': lambda decision: chosen.append(decision.index) or 0},
                                   decision_limit=None, seconds=1.)
            self.assertEqual(dict(status='TIME_LIMIT', decisions=1, payoffs=None), result)
            self.assertEqual([], chosen)
            self.assertEqual(1, game.status()['index'])

    def test_native_search_state_forks_after_accepted_history(self):
        with self.game(policies=('search', 'random'), particles=1, simulations=1, search_depth=1) as game:
            game.play(decision_limit=2)
            with game.fork() as branch:
                before = game.state()
                selected = game.select('search')
                alternative = branch.select('search')
                self.assertEqual(selected.choice, alternative.choice)
                branch.step(alternative)
                self.assertEqual(before, game.state())
                game.step(selected)
                self.assertEqual(game.state(), branch.state())

    def test_hand_authored_value_weights_inside_search_at_two_horizons(self):
        # Base64 components encode player/life/root. All players stay at 20 life here.
        root_life = 'player/bGlmZQ/cm9vdA'
        expected = -.2 + .1 * math.log1p(20)
        rollout_decisions = []
        for horizon in (1, 4):
            with self.subTest(horizon=horizon), self.game(
                    starting_hand_size=2, policies=('search', 'random'), particles=1,
                    simulations=2, search_depth=horizon, exploration_constant=.7,
                    opponent_model='random', value_weights={'bias': -.2, 'weights': {root_life: .1}},
                    leaf={'stateSource': 'BOUNDED_ROLLOUT', 'cutoff': 'EVALUATE'}) as game:
                for _ in range(24):
                    decision = game.decision()
                    if decision.actor == 'p0' and len(decision.actions) > 1:
                        break
                    game.step(next(a for a in decision.actions if a.family == 'PASS_PRIORITY'))
                else:
                    self.fail('Fixture did not reach a branching player decision')
                self.assertAlmostEqual(math.log1p(20), game.value_features()[root_life])
                before = game.state()
                action = game.select('search')
                search = action.search
                self.assertIsNotNone(search)
                self.assertAlmostEqual(expected, search['rootValue'], places=12)
                self.assertEqual(action.choice, search['chosen'])
                diagnostics = search['diagnostics']
                self.assertGreater(diagnostics['evaluatorCalls'], 0)
                self.assertEqual('uniform-v1', diagnostics['opponentModelId'])
                self.assertEqual(diagnostics['simulations'], sum(
                    c['learnedOutcomeEstimateBackups'] for c in search['candidateSettlementCounts'].values()))
                rollout_decisions.append(diagnostics['rootRolloutDecisions'] + diagnostics['opponentRolloutDecisions'])
                self.assertEqual(before, game.state())
                game.step(action)
                self.assertEqual(decision.index + 1, game.status()['index'])
        self.assertEqual(0, rollout_decisions[0])
        self.assertGreater(rollout_decisions[1], rollout_decisions[0])

    def test_misspelled_or_missing_model_weights_fail_at_game_creation(self):
        for model in ({'bias': .3}, {'bias': .3, 'wieghts': {}}):
            with self.subTest(model=model), self.assertRaises(ResearchError):
                self.game(policies=('search', 'random'), value_weights=model)

    def test_python_policy_memory_is_explicitly_copied_by_the_experiment(self):
        class CountingPolicy:
            def __init__(self):
                self.calls = 0
            def __call__(self, decision):
                self.calls += 1
                return 0
        policy = CountingPolicy()
        with self.game() as game, game.fork() as branch:
            branch_policy = copy.deepcopy(policy)
            branch.play({'p0': branch_policy}, decision_limit=1)
            self.assertEqual(1, branch_policy.calls)
            self.assertEqual(0, policy.calls)
            game.play({'p0': policy}, decision_limit=1)
            self.assertEqual(1, policy.calls)

    def test_kernel_and_factual_encodings_keep_menu_order_and_have_no_targets(self):
        with self.game(starting_hand_size=3) as game:
            decision = game.decision(kernel=True, factual=True)
            self.assertEqual(len(decision.actions), len(decision.features))
            value = decision.factual
            self.assertEqual(len(decision.actions), len(value['input']['actions']))
            self.assertEqual(value['eventPosition'], len(value['events']))
            self.assertTrue(all(1 <= token <= 256 for token in value['input']['view']))
            self.assertFalse({'target', 'split', 'groupId', 'actionMeans'} & value.keys())
            self.assertEqual(decision.rules_exhaustive, value['input']['rulesExhaustive'])
            self.assertEqual(decision.profile_exhaustive, value['input']['profileExhaustive'])

    def test_callback_choice_uses_the_same_menu_as_its_recorded_encodings(self):
        with self.game() as game:
            offered = game.decision(kernel=True)
            other_view = game.decision(view={'admission': 'SEMANTIC', 'annotations': False})
            self.assertEqual(offered.actions[0].choice, other_view.actions[0].choice)
            rows = []
            with patch.object(game, 'step', wraps=game.step) as applied:
                game.play({'p0': lambda decision: other_view.actions[0]}, decision_limit=1,
                          kernel=True, record=rows.append)
            self.assertEqual(offered.actions[0].view, applied.call_args.args[0].view)
            self.assertEqual(offered.information['candidates'], rows[0]['information']['candidates'])
            self.assertEqual(offered.features, rows[0]['features'])

    def test_fitter_and_prediction_use_ordinary_python_numeric_values(self):
        vector = lambda value: dict(indices=[0], values=[value])
        menu = [dict(state=vector(1.), centeredCandidate=vector(a)) for a in (-1., 1.)]
        roots = [dict(rootId='authored', seedGroupId='synthetic', features=menu, actionMeans=[-.4, .4])]
        model = self.session.fit(roots, ridge=.001)
        scores = self.session.predict(model, [menu])[0]
        self.assertGreater(scores[1], scores[0])
        self.assertAlmostEqual(0., sum(scores), places=12)
        self.assertNotIn('researchRunIdentity', model)

    def test_closing_one_game_does_not_close_siblings(self):
        with self.game() as game:
            branch = game.fork()
            branch.close()
            branch.close()
            with self.assertRaises(RuntimeError):
                branch.status()
            self.assertEqual(0, game.status()['index'])

    def test_standalone_game_owns_and_closes_its_runtime(self):
        with Game([{'Mountain': 7}] * 2, seed=17, starting_hand_size=7,
                  skip_mulligans=True, build=False, java_options=['-Xmx512m']) as game:
            session = game.session
            self.assertIsNotNone(game.decision())
        self.assertIsNotNone(session._process.poll())
        with self.assertRaises(RuntimeError):
            game.status()

    def test_off_menu_python_choice_cannot_be_recorded_with_another_menus_features(self):
        with self.game() as game:
            before = game.state()
            rows = []
            def off_menu(decision):
                choice = dict(decision.actions[0].choice, signature='not-an-admitted-choice')
                return replace(decision.actions[0], choice=choice)
            with self.assertRaisesRegex(ValueError, 'absent.*menu'):
                game.play([off_menu, off_menu], decision_limit=1,
                          record=rows.append, kernel=True)
            self.assertEqual(before, game.state())
            self.assertEqual([], rows)

    def test_both_native_search_memories_follow_python_steps_before_forking(self):
        with self.game(policies=('search', 'search'), particles=1, simulations=1, search_depth=1) as game:
            for _ in range(2):
                game.step(game.decision().actions[0])
            before = game.state()
            with game.fork() as first, game.fork() as second:
                for _ in range(4):
                    left, right = first.select('search'), second.select('search')
                    self.assertEqual(left, right)
                    first.step(left)
                    second.step(right)
                self.assertEqual(first.state(), second.state())
            self.assertEqual(before, game.state())

    def test_dead_transport_closes_without_restarting_or_replaying(self):
        session = Session(build=False, java_options=['-Xmx512m'])
        pid = session.pid
        session._process.kill()
        session._process.wait(timeout=5)
        with self.assertRaises((OSError, ConnectionError)):
            session._call('status', game=0)
        self.assertEqual(pid, session.pid)
        self.assertTrue(session._closed)
        self.assertTrue(session._process.stdin.closed)
        self.assertTrue(session._process.stdout.closed)


if __name__ == '__main__':
    unittest.main()
