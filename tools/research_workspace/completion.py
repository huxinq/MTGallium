"""Bounded completion observations and audits using retained native authorities.

Status observations are deliberately cheap and unverified. An explicit audit
authenticates artifacts and uses a versioned adapter against a selected frozen
runtime; it does not launch or resume the producer.
"""
from collections import Counter
import json
from pathlib import Path
import subprocess
import time

from . import evidence
from .native import build_reference, native, runtime
from .source import source_state
from .storage import MANIFEST, REPO, Refusal, digest, no_links, require

METADATA_BYTES = 2 * 1024 * 1024
FACTUAL_PROTOCOL = 'factual-residual-study-v1'
ADAPTERS = Path(__file__).parent / 'audits'


def metadata(path):
    path = no_links(path)
    value = evidence._load_json(evidence._read_bounded(path.parent, path.name, METADATA_BYTES))
    require(isinstance(value, dict), f'Expected an object in {path}')
    return value


def recorded_research(output):
    """Observe sealed-reference presence; never certify populations or infer fit execution."""
    output = no_links(output)
    if not (output / 'report.json').is_file():
        return dict(verification='NOT_RECHECKED', disposition='NOT_RECORDED',
                    next='Wait for a retained report or inspect a finalized child explicitly.')
    try:
        report = metadata(output / 'report.json')
        bindings = report.get('bindings') or {}
        require(isinstance(bindings, dict), 'Unsupported report bindings')
        protocol = bindings.get('protocol')
        result = dict(verification='RECORDED_UNVERIFIED', protocol=protocol,
                      disposition=report.get('disposition', 'NOT_RECORDED'),
                      next='Run audit for native population and binding checks; verify checks bytes only.')
        if protocol != FACTUAL_PROTOCOL:
            result['disposition'] = report.get('sequentialResult', {}).get('disposition', result['disposition']) \
                if isinstance(report.get('sequentialResult'), dict) else result['disposition']
            return result
        result['stages'] = {name: 'REFERENCE_RECORDED' if report.get(name) is not None else 'NO_REFERENCE_RECORDED'
                            for name in ('allocation', 'corpus', 'training')}
        result['stages']['predictions'] = 'RECORDED' if report.get('predictions') is not None else 'NOT_RECORDED'
        result['stages']['gate'] = 'RECORDED' if report.get('gate') is not None else 'NOT_RECORDED'
        require(isinstance(report.get('searches', []), list), 'Unsupported recorded searches')
        result['searchRowsRecorded'] = len(report.get('searches', []))
        result['fitExecution'] = 'CHECKPOINT_REFERENCE_RECORDED' if report.get('training') else 'NOT_ESTABLISHED'
        result['failure'] = report.get('failure')
        if report.get('allocation') is not None:
            # The producer owns these fixed in-run locations. Status never follows
            # an unverified directory field into another evidence tree.
            games = metadata(output / 'allocation' / 'report.json')['games']
            require(isinstance(games, list) and len(games) <= 4096, 'Unsupported allocation metadata')
            result['plannedGames'] = len(games)
            if report.get('corpus') is None:
                candidates = [i for i in range(len(games)) if all(
                    no_links(output / 'corpus' / 'trajectories' / f'game-{i}' / name).is_file()
                    for name in ('report.json', MANIFEST))]
                result['recovery'] = dict(kind='factual-admission', candidateCoordinates=candidates,
                    missingCoordinates=[i for i in range(len(games)) if i not in candidates],
                    eligibility='NOT_VERIFIED', automaticResume=False,
                    next='Authenticate retained admissions with audit, then use an explicit admissionParent plan and its continuation preflight.')
            else:
                corpus = metadata(output / 'corpus' / 'report.json')
                entries = corpus['entries']
                require(isinstance(entries, list) and len(entries) <= 4096 and
                        all(isinstance(entry, dict) for entry in entries), 'Unsupported corpus metadata')
                result['recordedCorpus'] = dict(entries=len(entries),
                    dispositions=dict(Counter(entry.get('disposition', 'NOT_RECORDED') for entry in entries)))
                result['recovery'] = dict(kind='factual-admission', eligibility='PARENT_HAS_CORPUS',
                    automaticResume=False,
                    next='A sealed corpus is retained; admission continuation does not accept post-corpus parents. Inspect its references before proposing further work.')
        return result
    except (ValueError, OSError, KeyError, TypeError) as exc:
        return dict(verification='NOT_RECHECKED', disposition='NOT_ESTABLISHED', error=str(exc),
                    next='Inspect the retained metadata error; do not infer a scientific outcome.')


def audit(run, build, *, expected=None, deck=None, timeout=180, heap_mib=4096):
    require(isinstance(timeout, int) and 1 <= timeout <= 1800, '--timeout must be between 1 and 1800 seconds')
    require(isinstance(heap_mib, int) and 256 <= heap_mib <= 8192, '--heap-mib must be between 256 and 8192')
    require(build, 'A frozen verification build is required; pass --build or an attempt with a retained build.')
    run, build = no_links(run), no_links(build)
    started = time.monotonic()

    def remaining():
        seconds = timeout - (time.monotonic() - started)
        require(seconds > 0, 'Completion audit exceeded its time budget; no scientific outcome inferred.')
        return seconds

    checked_build = native(['verify', build], build, timeout=remaining())
    verified = native(['verify', run] + ([expected] if expected else []), build, timeout=remaining())
    # Reuse bounded registered-artifact selection, including its post-verification
    # size/hash check. These reads select an adapter, not a scientific conclusion.
    _, _, _, _, plan = evidence._selected(run, 'plan.json', lambda _: verified)
    require(isinstance(plan, dict), 'Unsupported retained plan: expected an object')
    gameplay = any(key in plan for key in ('phase', 'parentDirectory', 'studyManifestSha256'))
    if gameplay:
        protocol = 'retained-gameplay'
    else:
        _, _, _, _, report = evidence._selected(run, 'report.json', lambda _: verified)
        require(isinstance(report, dict), 'Unsupported retained report: expected an object')
        bindings = report.get('bindings') or {}
        require(isinstance(bindings, dict), 'Unsupported report bindings')
        protocol = bindings.get('protocol')
    inspector = dict(source=source_state(), verificationBuild=build_reference(build),
                     buildManifestSha256=checked_build['manifestSha256'])
    base = dict(directory=str(run), researchRunIdentity=verified['manifest']['researchRunIdentity'],
                manifestSha256=verified['manifestSha256'], inspector=inspector,
                byteVerification='VERIFIED', launchesResearch=False)
    if protocol == FACTUAL_PROTOCOL:
        require(deck, 'Factual completion audit requires the retained deck; pass an attempt or --deck.')
        adapter = ADAPTERS / 'FactualResidualCompletionAudit.java'
        inputs = [run, no_links(deck), base['researchRunIdentity']]
    elif gameplay:
        adapter = ADAPTERS / 'GameplayCompletionAudit.java'
        inputs = [run, base['researchRunIdentity']]
    else:
        return dict(base, status='UNSUPPORTED', protocol=protocol,
                    reason='No completion authority is registered for this protocol; byte verification remains separate.')
    inspector['adapter'] = adapter.name
    inspector['adapterSha256'] = digest(adapter)
    # Java source-file mode compiles in memory. Only the selected frozen
    # classpath supplies scientific APIs; main's newer jars are never mixed in.
    argv = runtime(build, {'jvmArgs': [f'-Xmx{heap_mib}m', '-XX:ActiveProcessorCount=2']})[:-1]
    try:
        process = subprocess.run(argv + [str(adapter), *map(str, inputs)], cwd=REPO,
            text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=remaining())
    except subprocess.TimeoutExpired as exc:
        raise Refusal('Completion audit timed out; verification is incomplete, not a game or fit result.') from exc
    require(process.returncode == 0,
            'Completion authority refused or the selected build lacks its API. '
            'Use a compatible frozen --build; no fallback or producer execution occurred.\n' + process.stderr[-6000:])
    try:
        result = json.loads(process.stdout)
    except json.JSONDecodeError as exc:
        raise Refusal('Completion authority did not return structured JSON') from exc
    require(isinstance(result, dict), 'Completion authority did not return an object')
    require(digest(build / MANIFEST) == checked_build['manifestSha256'], 'Verification build changed during audit')
    require(digest(adapter) == inspector['adapterSha256'], 'Completion adapter changed during audit')
    require(result.get('researchRunIdentity') == base['researchRunIdentity'], 'Completion audit identity differs from verified root')
    require(digest(run / MANIFEST) == base['manifestSha256'], 'Root manifest changed during completion audit')
    return dict(base, status='AUDITED', protocol=protocol, completion=result,
                elapsedSeconds=time.monotonic() - started,
                limits=['Binding and population checks do not establish learned quality or authorize another execution.',
                        'Recorded references, attempted work and successful scientific stages remain distinct.'])
