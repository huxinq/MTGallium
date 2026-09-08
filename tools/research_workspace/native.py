"""Frozen runtime construction and calls to the native scientific authority."""
import json
import os
from pathlib import Path
import subprocess
from .storage import REPO, MANIFEST, Refusal, digest, no_links, read_json, require

MAIN = 'org.mtgallium.evaluation.searchteacher.ResearchWorkbenchKt'


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
