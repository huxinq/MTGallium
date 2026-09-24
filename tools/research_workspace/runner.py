"""Fixed game plans, long-lived JVMs, and atomic per-game compressed shards."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import fcntl
import gzip
import hashlib
import json
import os
from pathlib import Path
from queue import Empty, Queue
import threading
import time

from . import Session, runtime
from .ladder import source_provenance
from .resources import JAVA_OPTIONS, default_workers


def _encoded(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False)


def _sync_directory(directory):
    fd = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _atomic_json(path, value):
    temporary = path.with_name(path.name + '.tmp')
    with temporary.open('w') as stream:
        stream.write(_encoded(value) + '\n')
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)
    _sync_directory(path.parent)


def hash_split(key, *, train=0.8, val=0.1, salt=''):
    """Stable train/val/test assignment; use one key for all rows from a game."""
    if not (0 <= train <= 1 and 0 <= val <= 1 and train + val <= 1):
        raise ValueError('train and val must be fractions summing to at most one')
    value = int.from_bytes(hashlib.sha256(_encoded([salt, key]).encode()).digest()[:8], 'big') / 2**64
    return 'train' if value < train else 'val' if value < train + val else 'test'


def run_games(jobs, play, *, output, plan, threads=None, java_options=JAVA_OPTIONS, build=True):
    """play(session, job) returns JSON rows. Jobs are indexed by list position.

    plan must describe callback version, decks, config, and input content hashes.
    Resume requires identical jobs, plan, source provenance and JVM options.
    Committed errors count as completed; use a new plan/output to retry them.
    Callbacks must be deterministic and have no external write side effects:
    an interrupted, uncommitted game is replayed on resume.
    """
    jobs = json.loads(_encoded(list(jobs)))
    threads = default_workers() if threads is None else threads
    if type(threads) is not int or threads <= 0:
        raise ValueError('threads must be positive')
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    with (output / '.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        source = source_provenance()
        manifest = json.loads(_encoded(dict(version=2, jobs=jobs, plan=plan,
                                           source=source, java_options=list(java_options))))
        manifest_path = output / 'plan.json'
        if manifest_path.exists():
            if json.loads(manifest_path.read_text()) != manifest:
                raise ValueError('Cannot resume a different job plan, source, or JVM options')
        else:
            if list(output.glob('game-*.json*.gz')):
                raise ValueError('Shards exist without a job plan')
            _atomic_json(manifest_path, manifest)
        completed = {}
        for path in output.glob('game-*.jsonl.gz'):
            with gzip.open(path, 'rt') as stream:
                record = json.loads(stream.readline())
            index = record['index']
            if (type(index) is not int or not 0 <= index < len(jobs) or index in completed
                    or record['job'] != jobs[index] or path.name != f'game-{index:08d}.jsonl.gz'):
                raise ValueError(f'Invalid committed shard: {path}')
            completed[index] = record
        reused = len(completed)
        started = time.monotonic()
        python_started = time.process_time()
        mutex = threading.Lock()
        stop = threading.Event()
        pending = [i for i in range(len(jobs)) if i not in completed]
        workers = min(threads, len(pending))
        queue = Queue()
        for index in pending:
            queue.put(index)

        def summary(state):
            # A new JVM's first game carries class loading and JIT warm-up; project costs from steady games.
            steady = [r for r in completed.values() if not r.get('warmup')]
            steady_cpu = sum(r['cpu_seconds'] for r in steady)
            steady_rows = sum(r['row_count'] for r in steady)
            result = dict(state=state, total=len(jobs), completed=len(completed),
                          succeeded=sum(r['error'] is None for r in completed.values()),
                          failed=sum(r['error'] is not None for r in completed.values()),
                          rows=sum(r['row_count'] for r in completed.values()), reused=reused,
                          jvm_cpu_seconds=sum(r['cpu_seconds'] for r in completed.values()),
                          warmup_games=len(completed) - len(steady),
                          warmup_jvm_cpu_seconds=sum(r['cpu_seconds'] for r in completed.values()) - steady_cpu,
                          steady_jvm_cpu_per_game=steady_cpu / len(steady) if steady else None,
                          steady_jvm_cpu_per_row=steady_cpu / steady_rows if steady_rows else None,
                          python_cpu_seconds=time.process_time() - python_started,
                          wall_seconds=time.monotonic() - started,
                          timing_scope='JVM: committed games across resumes; Python/wall: this invocation',
                          threads=workers, java_options=list(java_options), source=source)
            _atomic_json(output / 'summary.json', result)
            progress = os.environ.get('MTGALLIUM_PROGRESS_FILE')
            if progress:
                _atomic_json(Path(progress), dict(schemaVersion=1,
                    updatedAt=datetime.now(timezone.utc).isoformat(),
                    completed=len(completed), unit='games', phase=state,
                    **({'total': len(jobs)} if jobs else {})))
            return result

        def worker():
            session = None
            try:
                while not stop.is_set():
                    try:
                        index = queue.get_nowait()
                    except Empty:
                        break
                    before = 0.0
                    cpu = 0.0
                    rows = []
                    error = None
                    warmup = session is None
                    try:
                        if session is None:
                            session = Session(build=False, java_options=java_options)
                        before = session.cpu_seconds()
                        rows = list(play(session, json.loads(_encoded(jobs[index]))))
                        _encoded(rows)  # Serialization failure is a game failure too.
                        cpu = session.cpu_seconds() - before
                    except Exception as exc:
                        error = dict(type=type(exc).__name__, message=str(exc))
                        rows = []
                        if session is not None:
                            try:
                                cpu = session.cpu_seconds() - before
                            except OSError:
                                pass
                            session.close()
                            session = None
                    record = dict(index=index, job=jobs[index], row_count=len(rows),
                                  error=error, cpu_seconds=cpu, warmup=warmup)
                    path = output / f'game-{index:08d}.jsonl.gz'
                    temporary = path.with_name(path.name + '.tmp')
                    with temporary.open('wb') as raw:
                        with gzip.GzipFile(fileobj=raw, mode='wb', mtime=0) as stream:
                            stream.write((_encoded(record) + '\n').encode())
                            for row in rows:
                                stream.write((_encoded(row) + '\n').encode())
                        raw.flush()
                        os.fsync(raw.fileno())
                    with mutex:
                        os.replace(temporary, path)
                        _sync_directory(output)
                        completed[index] = record
                        summary('running')
            except BaseException:
                stop.set()
                raise
            finally:
                if session is not None:
                    session.close()

        state = 'interrupted'
        summary('running')
        try:
            if pending:
                runtime(build=build)
                with ThreadPoolExecutor(max_workers=workers) as pool:
                    futures = [pool.submit(worker) for _ in range(workers)]
                    try:
                        for future in futures:
                            future.result()
                    except BaseException:
                        stop.set()
                        raise
            state = 'complete'
        finally:
            result = summary(state)
        return result
