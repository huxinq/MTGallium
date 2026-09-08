"""Small filesystem and process primitives; no experimental interpretation."""
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

REPO = Path(__file__).resolve().parents[2]
MAIN = 'org.mtgallium.evaluation.searchteacher.ResearchWorkbenchKt'
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


def git(*args, root=REPO):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()


def source_state():
    engine = REPO / 'third_party' / 'argentum-engine'
    state = dict(repository=str(REPO), sourceRevision=git('rev-parse', 'HEAD'),
                 argentumRevision=git('rev-parse', 'HEAD', root=engine),
                 expectedArgentumRevision=git('rev-parse', 'HEAD:third_party/argentum-engine'),
                 dirty=bool(git('status', '--porcelain', '--untracked-files=normal', '--ignore-submodules=none')),
                 engineDirty=bool(git('status', '--porcelain', root=engine)))
    return state


def clean_source():
    state = source_state()
    require(not state['dirty'] and not state['engineDirty'], 'Commit the clean treatment before freezing an attempt.')
    require(state['argentumRevision'] == state['expectedArgentumRevision'], 'Argentum checkout differs from the source pin.')
    return state


def build_reference(directory):
    directory = no_links(directory)
    require(directory.is_absolute(), 'Build directory must be absolute.')
    manifest = read_json(directory / MANIFEST)
    return dict(directory=str(directory), identity=manifest['researchRunIdentity'],
                manifestSha256=digest(directory / MANIFEST))


def runtime(build, execution):
    directory = no_links(build)
    classpath = (directory / 'classpath.txt').read_text().strip()
    require(classpath and all(Path(p).is_absolute() and Path(p).is_file() for p in classpath.split(os.pathsep)),
            'Frozen runtime has a missing or nonabsolute classpath entry. Build with tools/mtgallium-research-build.')
    java = execution.get('java', 'java')
    args = execution.get('jvmArgs', [])
    require(isinstance(java, str) and java and isinstance(args, list) and all(isinstance(a, str) for a in args),
            'execution.java and execution.jvmArgs must be an executable and an argument array.')
    # Do not permit JVM arguments to replace the application or its frozen classpath.
    require(all(a.startswith(('-X', '-D', '-ea', '-da')) for a in args),
            'jvmArgs accepts -X/-XX, -D and assertion options; classpath/application overrides are forbidden.')
    return [java, *args, '-cp', classpath, MAIN]


def native(command, build=None, execution=None, capture=True, timeout=None):
    require(build, 'A frozen build is required. Run `research build --output PATH`, then set inputs.build in experiment.json or pass --build.')
    args = runtime(build, execution or {}) + [str(x) for x in command]
    env = dict(os.environ, MTGALLIUM_PUBLIC_SOURCE='1')
    result = subprocess.run(args, cwd=REPO, env=env, text=True,
                            stdout=subprocess.PIPE if capture else None,
                            stderr=subprocess.PIPE if capture else None, timeout=timeout)
    if result.returncode:
        detail = (result.stderr or result.stdout or '').strip()[-8000:]
        raise Refusal(f'Native {command[0]} refused (exit {result.returncode}).\n{detail}')
    if capture:
        try:
            return json.loads(result.stdout)
        except json.JSONDecodeError as exc:
            raise Refusal(f'Native {command[0]} did not return the expected JSON: {result.stdout[-1000:]}') from exc
    return result.returncode
