"""Research plumbing tests; fake sessions do not start JVMs."""
from contextlib import ExitStack
import gzip
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from research_workspace import runner, resources, Session
import importlib
measurement = importlib.import_module('research_workspace.measure')


class FakeSession:
    created = 0

    def __init__(self, **kwargs):
        FakeSession.created += 1
        self.cpu = 0

    def cpu_seconds(self):
        self.cpu += 1
        return self.cpu

    def close(self):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


class ResourcesTest(unittest.TestCase):
    def test_worker_memory_cap(self):
        with patch.object(resources.os, 'cpu_count', return_value=32):
            for memory, expected in [(3 * resources.WORKER_BYTES, 3),
                                     (100 * resources.WORKER_BYTES, 12)]:
                with patch.object(resources, 'available_memory', return_value=memory):
                    self.assertEqual(resources.default_workers(), expected)
            with patch.object(resources, 'available_memory', return_value=resources.WORKER_BYTES - 1):
                with self.assertRaises(RuntimeError):
                    resources.default_workers()

    def test_proc_stat_uses_process_cpu_not_children_or_one_thread(self):
        session = object.__new__(Session)
        session._process = type('Process', (), {'pid': 123})()
        fields = ['0'] * 30
        fields[11:15] = ['100', '200', '9999', '9999']
        with patch('pathlib.Path.read_text', return_value='123 (java worker ) name) ' + ' '.join(fields)), \
             patch('os.sysconf', return_value=100):
            self.assertEqual(session.cpu_seconds(), 3)

    def test_cgroup_limits_available_ram(self):
        values = {'/proc/meminfo': 'MemAvailable: 99999999 kB',
                  '/proc/self/cgroup': '0::/test',
                  '/sys/fs/cgroup/test/memory.max': '4000000000',
                  '/sys/fs/cgroup/test/memory.current': '1000000000',
                  '/sys/fs/cgroup/memory.max': 'max'}
        with patch.object(Path, 'read_text', lambda path: values[str(path)]):
            self.assertEqual(resources.available_memory(), 3000000000)


class RunnerTest(unittest.TestCase):
    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.root = Path(self.stack.enter_context(tempfile.TemporaryDirectory()))
        self.stack.enter_context(patch.object(runner, 'Session', FakeSession))
        self.stack.enter_context(patch.object(runner, 'runtime'))
        self.stack.enter_context(patch.object(runner, 'source_provenance', return_value={'commit': 'test'}))
        self.stack.enter_context(patch.dict(os.environ, {'MTGALLIUM_PROGRESS_FILE': str(self.root / 'progress.json')}))
        self.jobs = [{'seed': i} for i in range(7)]
        FakeSession.created = 0

    def run_plan(self, output, play, **kwargs):
        return runner.run_games(self.jobs, play, output=self.root / output,
                                plan={'version': 1}, threads=kwargs.pop('threads', 1), **kwargs)

    @staticmethod
    def play(session, job):
        return [dict(seed=job['seed'], split=runner.hash_split(job['seed']))]

    def rows(self, directory):
        result = []
        for path in sorted((self.root / directory).glob('game-*.jsonl.gz')):
            with gzip.open(path, 'rt') as stream:
                metadata = json.loads(stream.readline())
                rows = [json.loads(line) for line in stream]
                self.assertEqual(len(rows), metadata['row_count'])
                result.extend(rows)
        return result

    def test_resume_reads_only_metadata_line(self):
        self.run_plan('headers', self.play)
        paths = sorted((self.root / 'headers').glob('game-*.jsonl.gz'))
        for path in paths:
            with gzip.open(path, 'rt') as stream:
                metadata = json.loads(stream.readline())
                self.assertEqual(set(metadata),
                                 {'index', 'job', 'error', 'cpu_seconds', 'row_count', 'warmup'})
                self.assertEqual(json.loads(stream.readline()),
                                 self.play(None, metadata['job'])[0])
        original_open = gzip.open
        reads = []
        class HeaderOnly:
            def __init__(self, path, *args, **kwargs):
                self.path = path
                self.stream = original_open(path, *args, **kwargs)
            def __enter__(self):
                return self
            def __exit__(self, *_):
                self.stream.close()
            def readline(self):
                if self.path in reads:
                    raise AssertionError('Resume read beyond metadata')
                reads.append(self.path)
                return self.stream.readline()
            def read(self, *args):
                raise AssertionError('Resume read the whole shard')
        with patch.object(runner.gzip, 'open', HeaderOnly):
            summary = self.run_plan('headers', lambda *_: self.fail('finished job rerun'))
        self.assertEqual(len(reads), len(self.jobs))
        self.assertEqual(summary['rows'], len(self.jobs))
        self.assertEqual(summary['reused'], len(self.jobs))

    def test_fast_worker_takes_jobs_while_slow_worker_is_busy(self):
        last_job = threading.Event()
        sessions = {}
        def play(session, job):
            seed = job['seed']
            sessions[seed] = session
            if seed == 0:
                self.assertTrue(last_job.wait(5), 'Remaining jobs were stranded behind slow worker')
            if seed == 6:
                last_job.set()
            return self.play(session, job)
        summary = self.run_plan('queue', play, threads=2)
        self.assertEqual(summary['failed'], 0)
        self.assertEqual(summary['completed'], len(self.jobs))
        self.assertEqual(len(self.rows('queue')), len(self.jobs))
        self.assertIsNot(sessions[0], sessions[1])
        self.assertTrue(all(sessions[i] is sessions[1] for i in range(1, 7)))
        self.assertEqual(FakeSession.created, 2)

    def test_interrupt_resume_and_plan_mismatch(self):
        self.run_plan('full', self.play)
        seen = []

        def interrupted(session, job):
            if job['seed'] == 3:
                raise KeyboardInterrupt()
            return self.play(session, job)

        with self.assertRaises(KeyboardInterrupt):
            self.run_plan('resume', interrupted)
        summary = json.loads((self.root / 'resume/summary.json').read_text())
        self.assertEqual((summary['state'], summary['completed']), ('interrupted', 3))
        # An incomplete gzip must never be mistaken for a finished game.
        (self.root / 'resume/game-00000003.jsonl.gz.tmp').write_bytes(b'partial')

        def resumed(session, job):
            seen.append(job['seed'])
            return self.play(session, job)

        result = self.run_plan('resume', resumed)
        self.assertEqual(seen, [3, 4, 5, 6])
        self.assertEqual(result['completed'], 7)
        self.assertEqual(self.rows('full'), self.rows('resume'))
        self.run_plan('resume', lambda *_: self.fail('finished job rerun'))
        self.jobs[0]['seed'] = 999
        with self.assertRaisesRegex(ValueError, 'different job plan'):
            self.run_plan('resume', self.play)

    def test_failure_continues_and_summary_written(self):
        def play(session, job):
            if job['seed'] == 2:
                raise ValueError('bad game')
            return self.play(session, job)
        summary = self.run_plan('errors', play)
        self.assertEqual((summary['completed'], summary['failed'], summary['rows']), (7, 1, 6))
        self.assertEqual(FakeSession.created, 2)
        self.assertEqual(json.loads((self.root / 'progress.json').read_text())['completed'], 7)

    def test_warmup_games_are_separated_from_steady_cost(self):
        def play(session, job):
            if not getattr(session, 'warm', False):
                session.cpu += 100  # the first game in each JVM pays warm-up
                session.warm = True
            if job['seed'] == 2:
                raise ValueError('bad game')  # closes the JVM; the next game warms a new one
            return self.play(session, job)
        summary = self.run_plan('warmup', play)
        flags = {}
        for path in (self.root / 'warmup').glob('game-*.jsonl.gz'):
            with gzip.open(path, 'rt') as stream:
                metadata = json.loads(stream.readline())
                flags[metadata['index']] = metadata['warmup']
        self.assertEqual(sorted(i for i, warm in flags.items() if warm), [0, 3])
        self.assertEqual((summary['warmup_games'], summary['warmup_jvm_cpu_seconds']), (2, 202))
        self.assertEqual(summary['jvm_cpu_seconds'], 207)
        # Steady games: 1, 2 (failed, no rows), 4, 5 and 6 cost one second each and give four rows.
        self.assertEqual(summary['steady_jvm_cpu_per_game'], 1)
        self.assertEqual(summary['steady_jvm_cpu_per_row'], 1.25)

    def test_build_failure_has_summary(self):
        with patch.object(runner, 'runtime', side_effect=RuntimeError('build')):
            with self.assertRaises(RuntimeError):
                self.run_plan('build', self.play)
        self.assertEqual(json.loads((self.root / 'build/summary.json').read_text())['completed'], 0)

    def test_commit_before_summary_is_recovered(self):
        original = runner._atomic_json
        def write(path, value):
            if path.name == 'summary.json' and value['completed'] == 1:
                raise RuntimeError('power loss after shard commit')
            original(path, value)
        with patch.object(runner, '_atomic_json', side_effect=write):
            with self.assertRaises(RuntimeError):
                self.run_plan('crash', self.play)
        summary = self.run_plan('crash', self.play)
        self.assertEqual(summary['reused'], 1)
        self.assertEqual(len(self.rows('crash')), 7)


class MeasureTest(unittest.TestCase):
    def test_warmup_excluded_and_all_moves_fingerprinted(self):
        from research_workspace import Action
        class Game:
            def __init__(self):
                self.index = 0
            def __enter__(self):
                return self
            def __exit__(self, *_):
                pass
            def status(self):
                return {'terminal': self.index == 2, 'actor': f'p{self.index % 2}',
                        'payoffs': {'p0': 1, 'p1': -1} if self.index == 2 else None}
            def select(self, policy):
                return Action(self.index, {}, {'signature': policy, 'canonicalPayload': {}},
                              {'diagnostics': {'evaluatorCalls': 4,
                                               'unsettledLeafEvaluations': 3}}
                              if policy == 'search' else None)
            def step(self, action, **kwargs):
                self.index += 1
                return {'status': self.status()}
        class Session(FakeSession):
            def game(self, *args, **kwargs):
                return Game()
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(measurement, 'Session', Session), \
             patch.object(measurement, 'source_provenance', return_value={}):
            def measure(opponent):
                return measurement.measure('search', opponent=opponent, decks=[{}, {}],
                    config={}, seeds=[2, 3], warmup_seed=1, output=directory)
            first = measure('heuristic')
            second = measure('random')
        self.assertEqual(first['cpu_per_game'], 1)
        self.assertEqual(first['searched_decisions_per_game'], 1)
        self.assertEqual(first['cpu_per_searched_decision'], 1)
        self.assertEqual(first['leaf_evaluations'], 8)
        self.assertEqual(first['unsettled_leaf_evaluations'], 6)
        self.assertEqual(first['unsettled_leaf_fraction'], .75)
        self.assertEqual(len(first['games']), 3)
        self.assertNotEqual(first['games'][1]['fingerprint'], second['games'][1]['fingerprint'])


@unittest.skipUnless(sys.platform == 'linux' and shutil.which('flock'), 'Linux flock required')
class GradleLockTest(unittest.TestCase):
    def test_outer_flock_no_deadlock_or_daemon_inheritance(self):
        helper = Path(__file__).resolve().parents[1] / 'gradle_lock.py'
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            lock = directory / 'build.lock'
            script = directory / 'gradle_lock.py'
            script.write_text(helper.read_text().replace("Path('/tmp/mtgallium-science-gradle.lock')", repr(lock).replace('PosixPath', 'Path')))
            daemon = directory / 'daemon.py'
            daemon.write_text('import subprocess\nsubprocess.Popen(["sleep", "2"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)\n')
            for outer in ([], ['flock', str(lock)], ['flock', '--close', str(lock)]):
                subprocess.run([*outer, sys.executable, str(script), sys.executable, str(daemon)],
                               check=True, timeout=5)
                subprocess.run(['flock', '-n', str(lock), 'true'], check=True, timeout=1)

    def test_independent_builds_serialize(self):
        helper = Path(__file__).resolve().parents[1] / 'gradle_lock.py'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            script = root / 'lock.py'
            script.write_text(helper.read_text().replace("Path('/tmp/mtgallium-science-gradle.lock')", f'Path({str(root / "lock")!r})'))
            child = root / 'child.py'
            child.write_text('import time\nfrom pathlib import Path\np=Path(__file__).with_name("busy")\np.mkdir()\ntime.sleep(.1)\np.rmdir()\n')
            processes = [subprocess.Popen([sys.executable, str(script), sys.executable, str(child)]) for _ in range(3)]
            for process in processes:
                self.assertEqual(process.wait(timeout=5), 0)


if __name__ == '__main__':
    unittest.main()
