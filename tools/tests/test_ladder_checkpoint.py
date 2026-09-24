"""Recovery checks use synthetic native-game responses; no JVM is needed."""
from contextlib import nullcontext
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from research_workspace.ladder import evaluate
from research_workspace.ladder_checkpoint import Checkpoint
from research_workspace.ladder_statistics import allocate, validate_allocation
from research_workspace.game import ResearchError
from research_workspace.persistence import durable_directory
from research_workspace.ladder_stream import stream_pairs


CHILD = r'''
from contextlib import nullcontext
import json, os, pathlib, sys, time
from unittest.mock import patch
from research_workspace.ladder import evaluate
root = pathlib.Path(sys.argv[1])
def game(session, candidate, incumbent, opponent, decks, seed, seat, settings):
    concurrent = sys.argv[3] == 'concurrent'
    if concurrent and seed == 10 and seat == 0 and sys.argv[2] == 'interrupt':
        while True:
            p = root/'checkpoint/progress.json'
            if p.exists() and json.loads(p.read_text())['value']['opponents']['one']['completed_games'] >= 4:
                break
            time.sleep(.01)
        (root/'ready').write_text('ready')
        time.sleep(60)
    if not concurrent and seed == 12 and seat == 0 and sys.argv[2] == 'interrupt':
        (root/'ready').write_text('ready')
        time.sleep(60)
    with (root/'played').open('a') as f:
        f.write(f'{seed}:{seat}\n')
    return dict(seed=seed, candidate_seat=f'p{seat}', status='TERMINAL',
                payoff=(seed+seat)%2, candidate_decisions=3, changed_decisions=1)
with patch('research_workspace.ladder.source_provenance', return_value={'commit':'synthetic'}), \
     patch('research_workspace.ladder.runtime'), \
     patch('research_workspace.ladder.Session', side_effect=lambda **_: nullcontext()), \
     patch('research_workspace.ladder._game', side_effect=game):
    options = dict(sequential='improvement', max_pairs=6) if sys.argv[3]!='fixed' else dict(setups=6)
    row = evaluate('heuristic', opponents={'one':'random'}, incumbent='random', decks=[{'Mountain':4}]*2,
        seeds=list(range(10,16)), threads=3 if sys.argv[3]=='concurrent' else 1, build=False, evidence_root=root,
        output=root/'ladder.jsonl', checkpoint=root/'checkpoint', **options)
    (root/'returned.json').write_text(json.dumps(row))
'''


class CheckpointTest(unittest.TestCase):
    def test_kill_and_resume_fixed_and_sequential_without_replaying_completed_pairs(self):
        for mode in ('fixed', 'sequential', 'concurrent'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                env = dict(os.environ, PYTHONPATH=str(Path(__file__).resolve().parents[1]))
                child = subprocess.Popen([sys.executable, '-c', CHILD, directory, 'interrupt', mode], env=env)
                try:
                    deadline = time.monotonic() + 15
                    while not (root/'ready').exists():
                        if child.poll() is not None or time.monotonic() > deadline:
                            self.fail('child did not reach interruption point')
                        time.sleep(.01)
                    child.kill()
                    child.wait(timeout=5)
                finally:
                    if child.poll() is None:
                        child.kill()
                        child.wait()
                progress = json.loads((root/'checkpoint/progress.json').read_text())['value']
                self.assertEqual(4, progress['opponents']['one']['completed_games'])
                self.assertEqual(.5, progress['opponents']['one']['score'])
                for _ in range(2):  # completed invocation is also idempotent
                    subprocess.run([sys.executable, '-c', CHILD, directory, 'resume', mode],
                                   env=env, check=True, timeout=20)
                played = (root/'played').read_text().splitlines()
                self.assertEqual(12, len(played))
                self.assertEqual(12, len(set(played)))
                self.assertEqual(1, len((root/'ladder.jsonl').read_text().splitlines()))
                row = json.loads((root/'returned.json').read_text())
                self.assertEqual(.5, row['opponents'][0]['complete_pair_score'])
                if mode != 'fixed':
                    self.assertEqual(6, row['opponents'][0]['test']['pairs'])

    def test_replay_order_and_in_flight_pair_survive_an_early_stop(self):
        class Test:
            def __init__(self): self.values = []
            @property
            def decided(self): return len(self.values) == 2
            def add(self, value): self.values.append(value)
            def result(self): return dict(values=self.values.copy())
        def pair(index):
            return [dict(seed=index, candidate_seat=f'p{s}', status='TERMINAL', payoff=index/4)
                    for s in (0, 1)]
        with tempfile.TemporaryDirectory() as directory, Checkpoint(directory) as store:
            store.prepare({'synthetic': True})
            for i in range(4): store.issue('one', i)
            store.complete('one', 1, pair(1))
            store.complete('one', 2, pair(2))
            test = Test()
            played = []
            def play(_, name, index):
                played.append(index)
                return pair(index)
            rows, errors = stream_pairs(['one'], 8, 1, nullcontext, play, {'one':test}, store)
            self.assertEqual([0, 3], played)
            self.assertEqual([0, .25], test.values)
            self.assertEqual(8, len(rows))
            self.assertEqual([], errors)

    def test_identity_integrity_and_exclusive_lock(self):
        with tempfile.TemporaryDirectory() as directory:
            with Checkpoint(directory) as store:
                store.prepare({'source':'one'})
                with self.assertRaisesRegex(ValueError, 'already in use'):
                    with Checkpoint(directory): pass
                with self.assertRaisesRegex(ValueError, 'mismatch'):
                    store.prepare({'source':'two'})
                store.write('test.json', {'value':1})
                path = Path(directory)/'test.json'
                record = json.loads(path.read_text()); record['value']['value'] = 2
                path.write_text(json.dumps(record))
                with self.assertRaisesRegex(ValueError, 'integrity'):
                    store.read('test.json')

    def test_confirmation_reservation_recovery_requires_original_checkpoint(self):
        with tempfile.TemporaryDirectory() as root:
            declaration = dict(checkpoint_id='original', config='unchanged')
            first = allocate(root, 'confirmation', 4, claim='claim', declaration=declaration)
            again = allocate(root, 'confirmation', 4, claim='claim', declaration=declaration, resume=True)
            self.assertEqual(first, again)
            with self.assertRaisesRegex(ValueError, 'mismatch'):
                allocate(root, 'confirmation', 4, claim='claim',
                         declaration=dict(checkpoint_id='different'), resume=True)
            with self.assertRaisesRegex(ValueError, 'already reserved'):
                allocate(root, 'confirmation', 4, claim='claim', declaration=declaration)

    def test_publication_recovery_does_not_duplicate_row(self):
        with tempfile.TemporaryDirectory() as directory, Checkpoint(directory) as store:
            store.prepare({'synthetic':True})
            row = dict(state='completed', checkpoint_id=store.id)
            store.write('result.json', row)  # crash before publication
            for _ in range(2): store.publish(Path(directory)/'rows.jsonl', row)
            self.assertEqual(1, len((Path(directory)/'rows.jsonl').read_text().splitlines()))

    def test_confirmation_evaluate_is_idempotent_and_binds_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            options = dict(candidate='heuristic', opponents={'one':'random'}, incumbent='random',
                decks=[{'Mountain':4}]*2, setups=3, threads=1, phase='confirmation', claim='claim',
                evidence_root=root, output=root/'rows.jsonl', checkpoint=root/'checkpoint', build=False)
            def game(*args):
                return dict(seed=args[5], candidate_seat=f'p{args[6]}', status='TERMINAL', payoff=1,
                            candidate_decisions=1, changed_decisions=0)
            with patch('research_workspace.ladder.source_provenance', return_value={'commit':'synthetic'}), \
                 patch('research_workspace.ladder.runtime'), \
                 patch('research_workspace.ladder.Session', side_effect=lambda **_: nullcontext()), \
                 patch('research_workspace.ladder._game', side_effect=game) as play:
                first = evaluate(**options)
                self.assertEqual(first, evaluate(**options))
                self.assertEqual(6, play.call_count)
                with self.assertRaisesRegex(ValueError, 'mismatch'):
                    evaluate(**dict(options, config={'starting_life':10}))
                with self.assertRaisesRegex(ValueError, 'mismatch'):
                    evaluate(**dict(options, checkpoint=root/'different-checkpoint'))
                plan = root/'checkpoint/plan.json'
                plan_bytes = plan.read_bytes()
                plan.unlink()
                output_before = (root/'rows.jsonl').read_bytes()
                with self.assertRaisesRegex(ValueError, 'missing checkpoint plan'):
                    evaluate(**options)
                self.assertEqual(output_before, (root/'rows.jsonl').read_bytes())
                plan.write_bytes(plan_bytes)
                # Both cached-result publication and unfinished recovery validate the ledger.
                ledger = root/'ladder/confirmation-reservations.jsonl'
                ledger.unlink()
                with self.assertRaisesRegex(ValueError, 'authoritative'):
                    evaluate(**options)
                (root/'checkpoint/result.json').unlink()
                with self.assertRaisesRegex(ValueError, 'authoritative'):
                    evaluate(**options)
                self.assertEqual(6, play.call_count)

    def test_incomplete_checkpoint_restore_cannot_create_new_identity_or_plan(self):
        with tempfile.TemporaryDirectory() as directory, Checkpoint(directory) as store:
            store.write('plan.json', {'seeds':[1]})
            with self.assertRaisesRegex(ValueError, 'missing checkpoint identity'):
                store.prepare({'synthetic':True})
            (Path(directory)/'plan.json').unlink()
            store.prepare({'synthetic':True})
            store.issue('one', 0)
            with self.assertRaisesRegex(ValueError, 'missing checkpoint plan'):
                store.load_plan()

    def test_retained_error_is_not_retried(self):
        with tempfile.TemporaryDirectory() as directory, Checkpoint(directory) as store:
            store.prepare({'synthetic':True})
            store.issue('one', 0)
            store.complete('one', 0, [dict(status='ERROR', payoff=None)]*2)
            def play(*_): self.fail('retained errors must not be replayed')
            rows, errors = stream_pairs(['one'], 3, 1, nullcontext, play, None, store)
            self.assertEqual(2, len(rows))
            self.assertEqual('failed', store.read('progress.json')['state'])

    def test_missing_corrupt_or_rolled_back_reservations_refuse_recovery(self):
        for damage in ('missing', 'corrupt', 'rollback', 'missing-pools', 'different-metadata'):
            with self.subTest(damage=damage), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                metadata = allocate(root, 'confirmation', 4, claim='claim',
                                    declaration={'checkpoint_id':'original'})
                ledger = root/'ladder/confirmation-reservations.jsonl'
                if damage == 'missing': ledger.unlink()
                elif damage == 'corrupt': ledger.write_text('broken\n')
                elif damage == 'rollback': ledger.write_text('')
                elif damage == 'missing-pools': (root/'ladder/seed-pools.json').unlink()
                else: metadata['seeds'][0] += 1
                before = ledger.read_bytes() if ledger.exists() else None
                with self.assertRaises(ValueError): validate_allocation(root, metadata)
                self.assertEqual(before, ledger.read_bytes() if ledger.exists() else None)

    def test_fixed_worker_start_failure_retains_missing_slots_and_errors(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch('research_workspace.ladder.source_provenance', return_value={}), \
                 patch('research_workspace.ladder.runtime'), \
                 patch('research_workspace.ladder.Session', side_effect=RuntimeError('startup failed')):
                with self.assertRaises(ResearchError):
                    evaluate('heuristic', opponents={'one':'random'}, incumbent='random',
                        decks=[{'Mountain':4}]*2, setups=3, threads=1, build=False,
                        evidence_root=root, checkpoint=root/'checkpoint', output=root/'rows.jsonl')
            row = json.loads((root/'rows.jsonl').read_text())
            summary = row['opponents'][0]
            self.assertEqual(6, summary['missing_outcomes'])
            self.assertEqual([0, 1], summary['missing_outcome_bounds'])
            self.assertEqual('startup failed', row['stream_errors'][0]['message'])

    def test_new_directory_syncs_all_parent_entries_even_on_retry(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory).resolve()/'nested'/'checkpoint'
            for _ in range(2):
                with patch('research_workspace.persistence.sync_directory') as sync:
                    durable_directory(path)
                self.assertEqual([*reversed(path.parents), path], [c.args[0] for c in sync.call_args_list])
