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
from research_workspace.ladder_statistics import allocate
from research_workspace.ladder_stream import stream_pairs


CHILD = r'''
from contextlib import nullcontext
import json, os, pathlib, sys, time
from unittest.mock import patch
from research_workspace.ladder import evaluate
root = pathlib.Path(sys.argv[1])
def game(session, candidate, incumbent, opponent, decks, seed, seat, settings):
    if seed == 12 and seat == 0 and sys.argv[2] == 'interrupt':
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
    options = dict(sequential='improvement', max_pairs=6) if sys.argv[3]=='sequential' else dict(setups=6)
    row = evaluate('heuristic', opponents={'one':'random'}, incumbent='random', decks=[{'Mountain':4}]*2,
        seeds=list(range(10,16)), threads=1, build=False, evidence_root=root,
        output=root/'ladder.jsonl', checkpoint=root/'checkpoint', **options)
    (root/'returned.json').write_text(json.dumps(row))
'''


class CheckpointTest(unittest.TestCase):
    def test_kill_and_resume_fixed_and_sequential_without_replaying_completed_pairs(self):
        for mode in ('fixed', 'sequential'):
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
                if mode == 'sequential':
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

    def test_retained_error_is_not_retried(self):
        with tempfile.TemporaryDirectory() as directory, Checkpoint(directory) as store:
            store.prepare({'synthetic':True})
            store.issue('one', 0)
            store.complete('one', 0, [dict(status='ERROR', payoff=None)]*2)
            def play(*_): self.fail('retained errors must not be replayed')
            rows, errors = stream_pairs(['one'], 3, 1, nullcontext, play, None, store)
            self.assertEqual(2, len(rows))
            self.assertEqual('failed', store.read('progress.json')['state'])
