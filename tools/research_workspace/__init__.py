"""Build current source, run a JVM main, and read research data."""
from collections.abc import Sequence
import gzip
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
from typing import Any

REPO = Path(__file__).resolve().parents[2]
MAIN = 'org.mtgallium.research.workbench.ResearchKt'
RUNTIME = REPO / 'research/workbench/build/research/runtime.json'


def runtime(*, build: bool = True) -> dict[str, str]:
    """Gradle owns dependency invalidation, including ordinary uncommitted edits."""
    if build:
        subprocess.run(
            ['bash', str(REPO / 'tools/mtgallium-gradle'),
             ':research:workbench:researchClasspath', '--console=plain', '-q'],
            cwd=REPO, stdout=sys.stderr, check=True,
        )
    try:
        return json.loads(RUNTIME.read_text())
    except FileNotFoundError as error:
        raise FileNotFoundError('No compiled research runtime; run tools/mtgallium-research build') from error


def jvm_command(arguments: Sequence[str], *, main_class: str = MAIN,
                build: bool = True, java_options: Sequence[str] = ()) -> list[str]:
    """No imposed processor, heap, or elapsed-time ceiling. JAVA_OPTS is honored."""
    paths = runtime(build=build)
    return [paths['java'], *shlex.split(os.environ.get('JAVA_OPTS', '')), *java_options,
            '-cp', paths['classpath'], main_class, *map(str, arguments)]


def run(arguments: Sequence[str], *, main_class: str = MAIN, build: bool = True,
        java_options: Sequence[str] = (), **kwargs: Any) -> subprocess.CompletedProcess:
    """Run in the caller's directory; relative input/output paths keep their normal meaning."""
    command = jvm_command(arguments, main_class=main_class, build=build, java_options=java_options)
    environment = dict(os.environ)
    environment['MTGALLIUM_SOURCE_ROOT'] = str(REPO)
    kwargs.setdefault('env', environment)
    kwargs.setdefault('check', True)
    return subprocess.run(command, **kwargs)


def read_data(path: str | Path) -> Any:
    """Read JSON or JSON lines from any producer, with optional gzip compression."""
    path = Path(path)
    opener = gzip.open if path.suffix == '.gz' else open
    with opener(path, 'rt', encoding='utf-8') as stream:
        if '.jsonl' in path.suffixes:
            return [json.loads(line) for line in stream if line.strip()]
        return json.load(stream)


from .game import Action, Decision, Game, ResearchError, Session
