"""Private operational records and guarded filesystem paths."""
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import tempfile

REPO = Path(__file__).resolve().parents[2]
MANIFEST = 'research-run-manifest.json'


class Refusal(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise Refusal(message)


def now():
    return datetime.now(timezone.utc).isoformat()


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def read_json(path):
    def reject(value):
        raise Refusal(f'Non-finite JSON number: {value}')
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, f'Duplicate JSON field: {key}')
            result[key] = value
        return result
    with Path(path).open() as stream:
        return json.load(stream, parse_constant=reject, object_pairs_hook=pairs)


def json_bytes(value):
    return (json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + '\n').encode()


def write_new(path, value):
    with Path(path).open('xb') as stream:
        stream.write(json_bytes(value))


def atomic_json(path, value):
    path = Path(path)
    fd, temporary = tempfile.mkstemp(prefix='.' + path.name, dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(json_bytes(value))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def no_links(path):
    path = Path(os.path.abspath(path))
    for parent in (path, *path.parents):
        require(not parent.is_symlink(), f'Symbolic-link route is not supported: {parent}')
    return path


def private_work():
    value = os.environ.get('MTGALLIUM_PRIVATE_EVIDENCE_ROOT')
    require(value, 'Set MTGALLIUM_PRIVATE_EVIDENCE_ROOT to an absolute directory outside all source checkouts.')
    require(Path(value).is_absolute(), 'MTGALLIUM_PRIVATE_EVIDENCE_ROOT must be absolute.')
    root = no_links(value)
    require(root != REPO and REPO not in root.parents, 'Private evidence must be outside the checkout.')
    return root / 'search-teacher' / 'work'


def private_path(path):
    path = no_links(path)
    work = private_work()
    require(work in path.parents, f'Choose a child directory below {work}')
    # Worktrees can share a private root configuration. Refuse any Git checkout,
    # including another worktree, rather than checking only this tool's checkout.
    for parent in (path, *path.parents):
        require(not (parent / '.git').exists(), f'Private output lies in a source checkout: {parent}')
    return path
