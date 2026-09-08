"""Exact checkout and pinned engine identity for execution bindings."""
import subprocess
from .storage import REPO, require


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
