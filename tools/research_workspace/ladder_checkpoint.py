"""Durable comparison checkpoints. A directory belongs to exactly one evaluation."""
from functools import wraps
import fcntl
import hashlib
import json
import os
from pathlib import Path
import secrets
import time


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()


class Checkpoint:
    def __init__(self, path):
        self.path = Path(path)

    def __enter__(self):
        self.path.mkdir(parents=True, exist_ok=True)
        self.lock = (self.path / '.lock').open('a')
        try:
            fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            self.lock.close()
            raise ValueError('comparison checkpoint is already in use') from None
        return self

    def __exit__(self, *_):
        self.lock.close()

    def read(self, name):
        path = self.path / name
        if not path.exists():
            return None
        record = json.loads(path.read_text())
        value = record['value']
        if record['sha256'] != hashlib.sha256(canonical(value)).hexdigest():
            raise ValueError(f'checkpoint integrity failure: {name}')
        return value

    def write(self, name, value):
        record = dict(value=value, sha256=hashlib.sha256(canonical(value)).hexdigest())
        temporary = self.path / (name + '.tmp')
        with temporary.open('wb') as stream:
            stream.write(canonical(record) + b'\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, self.path / name)
        fd = os.open(self.path, os.O_RDONLY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)

    def prepare(self, specification):
        specification = json.loads(canonical(specification))
        header = self.read('identity.json')
        if header is None:
            header = dict(version=1, id=secrets.token_hex(16), specification=specification)
            self.write('identity.json', header)
        if header['version'] != 1 or header['specification'] != specification:
            raise ValueError('checkpoint configuration/source mismatch; use the original invocation')
        self.id = header['id']

    def records(self):
        return [self.read(path.name) for path in sorted(self.path.glob('pair-*.json'))]

    def pair_file(self, name, index):
        return f'pair-{hashlib.sha256(name.encode()).hexdigest()}-{index:08d}.json'

    def issue(self, name, index):
        self.write(self.pair_file(name, index), dict(opponent=name, index=index, pair=None))

    def complete(self, name, index, pair):
        self.write(self.pair_file(name, index), dict(opponent=name, index=index, pair=pair))

    def progress(self, rows, prefix, tests, issued, state='running'):
        opponents = {}
        for name in prefix:
            games = [game for opponent, game in rows if opponent == name]
            valid = [g for g in games if g['status'] != 'ERROR' and 'session_error' not in g]
            opponents[name] = dict(completed_games=len(games), issued_pairs=issued[name],
                ordered_pairs=prefix[name], score=(sum(g['payoff'] or 0 for g in valid) / len(valid)
                                                   if valid else None),
                test=tests[name].result() if tests is not None else None)
        self.write('progress.json', dict(checkpoint_id=self.id, timestamp=time.time(), state=state,
            estimate_label='partial descriptive score; not a final strength claim', opponents=opponents))

    def publish(self, output, row):
        """Persist result first; idempotent publication survives a crash between both writes."""
        row = dict(row, checkpoint_id=self.id)
        retained = self.read('result.json')
        if retained is None:
            self.write('result.json', row)
        elif retained != row:
            raise ValueError('checkpoint result mismatch')
        output = Path(output)
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open('a+') as stream:
            fcntl.flock(stream, fcntl.LOCK_EX)
            stream.seek(0)
            found = False
            for line in stream:
                if not line.endswith('\n'):
                    raise ValueError('incomplete ladder row; refusing to append')
                prior = json.loads(line)
                if prior.get('checkpoint_id') == self.id:
                    if prior != row:
                        raise ValueError('published checkpoint result mismatch')
                    found = True
            if not found:
                stream.write(canonical(row).decode() + '\n')
                stream.flush()
                os.fsync(stream.fileno())
        return row


def checkpointed(function):
    @wraps(function)
    def run(*args, **kwargs):
        path = kwargs.get('checkpoint')
        if path is None:
            return function(*args, **kwargs)
        with Checkpoint(path) as checkpoint:
            return function(*args, **dict(kwargs, checkpoint=checkpoint))
    return run
