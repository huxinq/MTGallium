"""Drafts, frozen execution requests and bounded durable attempts.

The request is an operational record, not a second research-run manifest.
All plan semantics, input admission and evidence verification stay in Kotlin.
"""
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time

from .common import (REPO, Refusal, atomic_json, build_reference, clean_source, digest,
                     native, no_links, now, private_path, private_work, read_json,
                     require, runtime, source_state, write_new)

CAPABILITIES = {
    'calibration': ('rehearsal', 'Fixed paired gameplay', 'search-teacher-calibration'),
    'sequential': ('rehearsal', 'Sequential paired gameplay', 'search-teacher-sequential'),
    'position-screen': ('rehearsal', 'Matched saved-position search', 'position-bank-screen'),
    'position-features': ('authenticated-inputs', 'Reconstruct acting-player features from a position bank', 'position-bank-screen'),
    'position-bank': ('authenticated-inputs', 'Derive a bank from retained games', 'real-game-position-bank'),
    'terminal-kernel-study': ('embedded-pilot', 'Development targets, frozen fit and held-out comparison', 'terminal-kernel-study'),
    'terminal-target-sensitivity': ('embedded-pilot', 'Measure changed conditional targets with frozen models', 'terminal-target-sensitivity'),
    'terminal-prediction-diagnostic': ('authenticated-inputs', 'Diagnose frozen development predictions', 'terminal-prediction-diagnostic'),
    'direct-attack-kernel-screen': ('authenticated-inputs', 'Compare direct decisions on declared development roots', 'direct-attack-kernel-screen'),
    'research-transfer-audit': ('authenticated-inputs', 'Join saved-position and deployment evidence', 'research-transfer-audit'),
    'campaign-data-snapshot': ('authenticated-inputs', 'Inspect recorded campaign population use', 'campaign-data-snapshot'),
}
DESIGN_FIELDS = ('question', 'learner', 'target', 'control', 'sharedComponents', 'intervention',
                 'population', 'primaryMeasure', 'decisionRule', 'allowedClaims', 'limitations', 'dataUse')


def catalog():
    return {'capabilities': [dict(kind=k, gate=v[0], purpose=v[1], suite=v[2],
                                  plan=True, launch=True, verify='artifact-bytes',
                                  inspection='registered-artifact-inventory')
                             for k, v in CAPABILITIES.items()],
            'nativeRoutes': [
                dict(suite='campaign-data-use', purpose='Append explicit prospective or retrospective population use',
                     route='--suite campaign-data-use --profile USE.json --output REGISTRY',
                     reason='An append-only campaign registry has different output semantics from a fresh attempt.'),
                dict(suite='gameplay-summary', purpose='Authenticate complete-game populations and report lengths',
                     route='--suite gameplay-summary --run-directory RUN'),
                dict(suite='search-profile-summary', purpose='Read retained registered JFR cost evidence',
                     route='--suite search-profile-summary --profile REGISTERED.jfr'),
                dict(suite='search-teacher-continuation', purpose='Explicit parent-linked continuation protocol',
                     route='See docs/research-workflow.md#optional-continuation-of-stopped-gameplay',
                     reason='Forking a draft does not authorize or implement statistical continuation.')],
            'limits': ['A gate checks the capability named in this catalog, not research validity.',
                       'Field export preserves recorded JSON; position-features generates the existing policy representation.']}


def draft_path(path):
    path = no_links(path)
    return path / 'experiment.json' if path.is_dir() else path


def load_draft(path):
    path = draft_path(path)
    spec = read_json(path)
    require(spec.get('schemaVersion') == 1 and spec.get('kind') in CAPABILITIES,
            'Unsupported experiment schema or kind. Use `research catalog`.')
    require(set(spec) <= {'schemaVersion', 'name', 'kind', 'design', 'inputs', 'execution', 'lineage'},
            'Unknown experiment field; refusing a possibly misspelled setting.')
    require(set(spec['inputs']) == {'plan', 'deck', 'build'}, 'inputs requires exactly plan, deck and build.')
    require(set(spec['execution']) <= {'java', 'jvmArgs', 'threads', 'timeoutSeconds', 'smokeBaseSeed',
                                     'smokeSimulations', 'smokeRootLimit', 'smokeRepetitions'},
            'Unknown execution field; refusing a possibly misspelled setting.')
    return path, spec


def input_path(path, value):
    require(isinstance(value, str) and value, 'Required input path is missing.')
    target = Path(value)
    target = target if target.is_absolute() else path.parent / target
    target = no_links(target)
    require(target.is_file(), f'Missing input: {target}')
    return target


def plan_input(path, spec):
    if (path.parent / 'request.json').is_file():
        verify_request(path.parent)
        return path.parent / 'plan.json'
    return input_path(path, spec['inputs']['plan'])


def new_experiment(name, kind, plan, deck, build=None, question='', destination=None, lineage=None):
    require(re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_.-]{0,79}', name), 'Use a short experiment name containing letters, digits, dot, dash or underscore.')
    require(kind in CAPABILITIES, 'Unknown experiment kind.')
    destination = private_path(destination or private_work() / name)
    require(not destination.exists(), f'Experiment already exists: {destination}; use fork to change a frozen experiment.')
    plan = no_links(plan)
    read_json(plan)
    deck = no_links(deck) if deck else None
    if deck:
        read_json(deck)
    destination.mkdir(parents=True)
    shutil.copyfile(plan, destination / 'plan.json')
    if deck:
        shutil.copyfile(deck, destination / 'deck.json')
    spec = dict(schemaVersion=1, name=name, kind=kind,
                design={key: question if key == 'question' else '' for key in DESIGN_FIELDS},
                inputs=dict(plan='plan.json', deck='deck.json' if deck else None,
                            build=str(no_links(build)) if build else None),
                execution=dict(java='java', jvmArgs=['-XX:ActiveProcessorCount=2', '-Xmx4g'], threads=2,
                               timeoutSeconds=None, smokeBaseSeed=None, smokeSimulations=4,
                               smokeRootLimit=1, smokeRepetitions=1), lineage=lineage)
    write_new(destination / 'experiment.json', spec)
    return {'directory': str(destination), 'draft': str(destination / 'experiment.json'),
            'next': f'Edit experiment.json and plan.json, then run: research doctor {destination}'}


def fork_experiment(original, name, reason, destination=None):
    require(reason.strip(), 'Record why this fork changes the experiment.')
    original = no_links(original)
    if (original / 'request.json').exists():
        request = verify_request(original)
        path, spec = original / 'experiment.json', read_json(original / 'experiment.json')
        plan, deck = original / 'plan.json', original / 'deck.json'
        deck = deck if deck.exists() else None
        parent = dict(attempt=str(original), requestSha256=digest(original / 'request.json'), reason=reason)
    else:
        path, spec = load_draft(original)
        plan = input_path(path, spec['inputs']['plan'])
        deck = input_path(path, spec['inputs']['deck']) if spec['inputs']['deck'] else None
        parent = dict(draft=str(path), draftSha256=digest(path), planSha256=digest(plan), reason=reason)
    result = new_experiment(name, spec['kind'], plan, deck, spec['inputs']['build'],
                            destination=destination, lineage=parent)
    target = Path(result['draft'])
    fork = read_json(target)
    fork['design'], fork['execution'] = spec['design'], spec['execution']
    atomic_json(target, fork)
    result['next'] = 'Edit the new draft/plan, use diff to review the changed treatment, then doctor/preflight.'
    return result


def design_problems(spec):
    required_design = DESIGN_FIELDS if spec['kind'] not in ('position-bank', 'position-features', 'campaign-data-snapshot') else (
        'question', 'population', 'allowedClaims', 'limitations', 'dataUse')
    problems = [f'design.{key}: state the intended meaning (use "not applicable" with a reason when appropriate)'
                for key in required_design if not isinstance(spec.get('design', {}).get(key), str)
                or not spec['design'][key].strip()]
    execution = spec['execution']
    for key in ('threads', 'timeoutSeconds', 'smokeSimulations', 'smokeRootLimit', 'smokeRepetitions'):
        if type(execution.get(key)) is not int or execution[key] <= 0:
            problems.append(f'execution.{key}: must be an explicit positive integer')
    if spec['kind'] in ('calibration', 'sequential') and type(execution.get('smokeBaseSeed')) is not int:
        problems.append('execution.smokeBaseSeed: declare a separate technical smoke seed')
    if not spec['inputs']['build']:
        problems.append('inputs.build: point to a verified frozen build directory')
    if spec['kind'] not in ('terminal-prediction-diagnostic', 'research-transfer-audit', 'campaign-data-snapshot') and not spec['inputs']['deck']:
        problems.append('inputs.deck: this workload requires an explicit deck manifest')
    return problems


def bound_arguments(attempt, spec):
    """Reconstruct the allowed command from frozen inputs, never trust stored argv."""
    if CAPABILITIES[spec['kind']][0] == 'rehearsal':
        return ['launch', str(attempt / 'preflight.json'), str(attempt / 'preflight'), str(attempt / 'build-reference.json')]
    return ['execute', spec['kind'], str(attempt / 'plan.json'), str(attempt / 'output'),
            str(attempt / 'deck.json') if spec['inputs']['deck'] else '-',
            str(spec['execution']['threads']), str(attempt / 'build-reference.json')]


def plan_draft(path, build=None):
    path, spec = load_draft(path)
    plan = plan_input(path, spec)
    result = native(['plan', spec['kind'], plan], build or spec['inputs']['build'], spec['execution'])
    return dict(design=spec['design'], execution=spec['execution'], lineage=spec.get('lineage'),
                planPath=str(plan), planSha256=digest(plan), native=result,
                threadControl='execution.threads' if spec['kind'] in ('calibration', 'sequential', 'position-screen', 'position-features')
                else 'The typed plan owns workers when present; execution.threads is not a scientific worker override for this kind.',
                note='Design text explains intent. Only the typed effective plan controls scientific settings.')


def doctor(path):
    path, spec = load_draft(path)
    problems = design_problems(spec)
    checks = []
    for label, check in (
        ('private destination', lambda: str(private_path(path.parent))),
        ('committed treatment', clean_source),
        ('plan input', lambda: str(input_path(path, spec['inputs']['plan']))),
        ('effective typed plan', lambda: plan_draft(path)['native']),
    ):
        try:
            checks.append(dict(check=label, passed=True, detail=check()))
        except (ValueError, OSError, subprocess.SubprocessError) as exc:
            checks.append(dict(check=label, passed=False, detail=str(exc)))
            problems.append(f'{label}: {exc}')
    return dict(readyToFreeze=not problems, gate=CAPABILITIES[spec['kind']][0], problems=problems, checks=checks,
                next='preflight creates a frozen attempt; launch rechecks its build and required gate.',
                limitation='Readiness is technical and structural. The researcher owns population use, design and claims.')


def freeze(path):
    path, spec = load_draft(path)
    require(not (path.parent / 'request.json').exists(), 'This is an immutable attempt. Fork it or freeze the original editable draft.')
    private_path(path.parent)
    problems = design_problems(spec)
    require(not problems, 'Complete the experiment design:\n' + '\n'.join(problems))
    source = clean_source()
    plan = input_path(path, spec['inputs']['plan'])
    deck = input_path(path, spec['inputs']['deck']) if spec['inputs']['deck'] else None
    plan_hash = digest(plan)
    effective = native(['plan', spec['kind'], plan], spec['inputs']['build'], spec['execution'])
    reference = build_reference(spec['inputs']['build'])
    attempts = path.parent / 'attempts'
    private_path(attempts)
    attempts.mkdir(exist_ok=True)
    # mkdir is the allocation authority even when two researchers freeze together.
    number = 1
    while True:
        attempt = attempts / f'{number:04d}'
        try:
            attempt.mkdir()
            break
        except FileExistsError:
            number += 1
    try:
        shutil.copyfile(plan, attempt / 'plan.json')
        if deck:
            shutil.copyfile(deck, attempt / 'deck.json')
        write_new(attempt / 'experiment.json', spec)
        write_new(attempt / 'effective-plan.json', effective)
        write_new(attempt / 'build-reference.json', reference)
        native(['build-verify', attempt / 'build-reference.json'], reference['directory'], spec['execution'])
        gate = CAPABILITIES[spec['kind']][0]
        if gate == 'rehearsal':
            work = dict(type='position-screen' if spec['kind'] == 'position-screen' else 'gameplay',
                        planPath=str(attempt / 'plan.json'), deckManifest=str(attempt / 'deck.json'),
                        threads=spec['execution']['threads'], smokeSimulations=spec['execution']['smokeSimulations'])
            if work['type'] == 'gameplay':
                work.update(sequential=spec['kind'] == 'sequential', smokeBaseSeed=spec['execution']['smokeBaseSeed'])
            else:
                work.update(smokeRootLimit=spec['execution']['smokeRootLimit'], smokeRepetitions=spec['execution']['smokeRepetitions'])
            write_new(attempt / 'preflight.json', dict(schemaVersion=1, targetOutput=str(attempt / 'output'), work=work))
        command = bound_arguments(attempt, spec)
        # An input modified while being frozen must not disagree with its typed description.
        require(plan_hash == digest(plan) == digest(attempt / 'plan.json') and (not deck or digest(deck) == digest(attempt / 'deck.json')),
                'An input changed while freezing. Inspect this incomplete attempt and freeze a new one.')
        require(clean_source() == source, 'Source changed while freezing the attempt.')
        files = {item.name: digest(item) for item in attempt.iterdir() if item.is_file()}
        request = dict(schemaVersion=1, createdAtUtc=now(), experiment=str(path), name=spec['name'], kind=spec['kind'],
                       gate=gate, source=source, build=reference, execution=spec['execution'],
                       files=files, command=runtime(reference['directory'], spec['execution']) + command,
                       nativeArguments=command, targetOutput=str(attempt / 'output'),
                       privateEvidenceRoot=str(private_work().parent.parent),
                       interpretation='Frozen execution request; native child manifests retain their own research identities.')
        write_new(attempt / 'request.json', request)
        atomic_json(attempt / 'status.json', dict(processState='PREPARED', updatedAtUtc=now(), gate=gate))
        return attempt
    except Exception as exc:
        atomic_json(attempt / 'status.json', dict(processState='PREPARATION_FAILED', updatedAtUtc=now(), error=str(exc)))
        raise


def verify_request(attempt, current_source=False):
    attempt = private_path(attempt)
    request = read_json(attempt / 'request.json')
    require(request['schemaVersion'] == 1 and request['kind'] in CAPABILITIES, 'Unsupported frozen request.')
    require(request['source']['repository'] == str(REPO), 'Run this attempt with the original source worktree.')
    require(request['privateEvidenceRoot'] == str(private_work().parent.parent), 'Private evidence root differs from the frozen request.')
    for name, expected in request['files'].items():
        require(Path(name).name == name, 'Invalid frozen input name.')
        require(digest(no_links(attempt / name)) == expected, f'Frozen input changed: {name}; fork the experiment instead.')
    spec = read_json(attempt / 'experiment.json')
    require(request['execution'] == spec['execution'] and request['kind'] == spec['kind'], 'Request and frozen experiment disagree.')
    require(request['build'] == read_json(attempt / 'build-reference.json'), 'Frozen build reference disagrees.')
    require(request['gate'] == CAPABILITIES[spec['kind']][0], 'Frozen launch gate disagrees with workload kind.')
    require(request['targetOutput'] == str(attempt / 'output'), 'Attempt moved: target output binding differs.')
    require(request['nativeArguments'] == bound_arguments(attempt, spec), 'Frozen native arguments differ from their bound inputs.')
    expected_files = {'experiment.json', 'effective-plan.json', 'build-reference.json', 'plan.json'}
    if spec['inputs']['deck']:
        expected_files.add('deck.json')
    if request['gate'] == 'rehearsal':
        expected_files.add('preflight.json')
        preflight = read_json(attempt / 'preflight.json')
        require(preflight['targetOutput'] == request['targetOutput'] and
                preflight['work']['planPath'] == str(attempt / 'plan.json') and
                preflight['work']['deckManifest'] == str(attempt / 'deck.json') and
                preflight['work']['threads'] == request['execution']['threads'], 'Preflight points outside its frozen attempt.')
        expected_type = 'position-screen' if spec['kind'] == 'position-screen' else 'gameplay'
        require(preflight['work']['type'] == expected_type and
                (expected_type != 'gameplay' or preflight['work']['sequential'] == (spec['kind'] == 'sequential')),
                'Preflight workload differs from its declared kind.')
    require(set(request['files']) == expected_files, 'Frozen input inventory differs from the required inputs.')
    require(request['command'] == runtime(request['build']['directory'], request['execution']) + request['nativeArguments'],
            'Frozen runtime command differs from the request.')
    require(request['nativeArguments'][0] in ('launch', 'execute'), 'Unsupported native launch command.')
    if current_source:
        require(clean_source() == request['source'], 'Treatment source changed; use its original clean worktree or freeze a new treatment.')
    return request


def preflight(path):
    path = no_links(path)
    attempt = path if (path / 'request.json').exists() else freeze(path)
    request = verify_request(attempt, current_source=True)
    require(not (attempt / 'submitted.json').exists(), 'Attempt already submitted; inspect it instead of rerunning preflight.')
    if request['gate'] == 'rehearsal':
        with (attempt / 'preflight.log').open('a') as log:
            args = runtime(request['build']['directory'], request['execution']) + [
                'preflight', str(attempt / 'preflight.json'), str(attempt / 'preflight'), str(attempt / 'build-reference.json')]
            result = subprocess.run(args, cwd=REPO, env=dict(os.environ, MTGALLIUM_PUBLIC_SOURCE='1'),
                                    stdout=log, stderr=subprocess.STDOUT, timeout=request['execution']['timeoutSeconds'])
        require(result.returncode == 0, f'Preflight refused. Inspect {attempt / "preflight.log"}; choose a fresh attempt after repair.')
        gate = 'REHEARSAL_PASSED'
    else:
        native(['build-verify', attempt / 'build-reference.json'], request['build']['directory'], request['execution'])
        gate = 'BUILD_AND_PLAN_CHECKED; ' + request['gate'] + ' remains inside launch'
    atomic_json(attempt / 'status.json', dict(processState='PREPARED', updatedAtUtc=now(), preflight=gate))
    return dict(attempt=str(attempt), requestSha256=digest(attempt / 'request.json'), preflight=gate,
                next=f'research launch {attempt}')


def submit(attempt, foreground=False):
    attempt = private_path(attempt)
    request = verify_request(attempt, current_source=True)
    if request['gate'] == 'rehearsal':
        require((attempt / 'preflight' / 'research-run-manifest.json').is_file(), 'Run preflight on this attempt first. Launch never runs smoke work implicitly.')
    require(not (attempt / 'submitted.json').exists(), 'Attempt already submitted. Inspect its status/logs; no implicit retry or resume.')
    unit = 'mtgallium-research-' + digest(attempt / 'request.json')[:20]
    command = [sys.executable, str(REPO / 'tools' / 'mtgallium-research'), '_run', str(attempt)]
    launcher = command if foreground else [
        'systemd-run', '--user', '--collect', '--unit', unit,
        '--property', f'RuntimeMaxSec={request["execution"]["timeoutSeconds"] + 30}',
        '--property', 'TimeoutStopSec=10', '--property', 'KillMode=control-group',
        '--working-directory', str(REPO), '--setenv', 'MTGALLIUM_PUBLIC_SOURCE=1',
        '--setenv', 'MTGALLIUM_PRIVATE_EVIDENCE_ROOT=' + request['privateEvidenceRoot'], *command]
    if not foreground:
        require(shutil.which('systemd-run'), 'Durable launch requires a user systemd manager. Use --foreground only for bounded interactive work.')
        probe = subprocess.run(['systemctl', '--user', 'show-environment'], capture_output=True)
        require(probe.returncode == 0, 'No user systemd manager is available. Use --foreground for bounded interactive work.')
    write_new(attempt / 'submitted.json', dict(atUtc=now(), requestSha256=digest(attempt / 'request.json'),
                                              mode='foreground' if foreground else 'systemd', unit=None if foreground else unit,
                                              launcher=launcher))
    result = subprocess.run(launcher, cwd=REPO, env=dict(os.environ, MTGALLIUM_PUBLIC_SOURCE='1'),
                            capture_output=not foreground, text=True)
    if result.returncode:
        # A foreground runner already records its actual failure. Keep that detail.
        if not foreground:
            atomic_json(attempt / 'status.json', dict(processState='LAUNCH_FAILED', updatedAtUtc=now(), error=result.stderr))
        raise Refusal(f'Launch exited {result.returncode}; inspect {attempt}. No automatic retry was scheduled.')
    return dict(attempt=str(attempt), submitted=True, unit=None if foreground else unit,
                next=f'research status {attempt}; research logs {attempt}')


def run_attempt(attempt):
    attempt = private_path(attempt)
    request = verify_request(attempt, current_source=True)
    submitted = read_json(attempt / 'submitted.json')
    require(submitted['requestSha256'] == digest(attempt / 'request.json'), 'Request changed after submission.')
    write_new(attempt / 'started.json', dict(atUtc=now(), pid=os.getpid(), requestSha256=digest(attempt / 'request.json')))
    require(not Path(request['targetOutput']).exists(), 'Primary output already exists; refuse implicit resume.')
    status = dict(processState='RUNNING', startedAtUtc=now(), updatedAtUtc=now(), pid=os.getpid(),
                  artifactVerification='NOT_RUN', researchDisposition='NOT_INTERPRETED')
    atomic_json(attempt / 'status.json', status)
    started = time.monotonic()
    deadline = started + request['execution']['timeoutSeconds']
    child = None
    def stopped(signum, _frame):
        raise InterruptedError(f'Execution stopped by signal {signum}')
    old_handlers = {sig: signal.signal(sig, stopped) for sig in (signal.SIGTERM, signal.SIGINT)}
    try:
        with (attempt / 'run.log').open('a') as log:
            child = subprocess.Popen(request['command'], cwd=REPO,
                                     env=dict(os.environ, MTGALLIUM_PUBLIC_SOURCE='1'),
                                     stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            code = child.wait(timeout=request['execution']['timeoutSeconds'])
        status['exitCode'] = code
        status['processState'] = 'EXITED' if code == 0 else 'FAILED'
        if code == 0:
            try:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise subprocess.TimeoutExpired('final artifact verification', request['execution']['timeoutSeconds'])
                verification = native(['verify', request['targetOutput']], request['build']['directory'], request['execution'], timeout=remaining)
                write_new(attempt / 'verification.json', verification)
                status['artifactVerification'] = 'BYTES_VERIFIED'
                status['researchRunIdentity'] = verification['manifest']['researchRunIdentity']
            except (ValueError, OSError) as exc:
                status['artifactVerification'] = 'REFUSED'
                status['verificationError'] = str(exc)
                code = 2
        return code
    except (subprocess.TimeoutExpired, InterruptedError) as exc:
        status['processState'] = 'TIMED_OUT' if isinstance(exc, subprocess.TimeoutExpired) else 'STOPPED'
        status['error'] = str(exc)
        return 124 if isinstance(exc, subprocess.TimeoutExpired) else 130
    except Exception as exc:
        status.update(processState='FAILED', error=str(exc))
        return 2
    finally:
        if child and child.poll() is None:
            os.killpg(child.pid, signal.SIGTERM)
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait()
        for sig, handler in old_handlers.items():
            signal.signal(sig, handler)
        status.update(updatedAtUtc=now(), elapsedSeconds=time.monotonic() - started)
        atomic_json(attempt / 'status.json', status)
        write_new(attempt / 'receipt.json', dict(requestSha256=digest(attempt / 'request.json'), status=status,
                                                verificationSha256=digest(attempt / 'verification.json') if (attempt / 'verification.json').exists() else None,
                                                logSha256=digest(attempt / 'run.log') if (attempt / 'run.log').exists() else None,
                                                interpretation='Operational execution receipt. Exit state, byte verification and research disposition are separate.'))


def verified_execution(attempt, verification):
    """Tie current native evidence to the completed attempt, not just its path."""
    request = verify_request(attempt)
    receipt = read_json(attempt / 'receipt.json')
    submitted = read_json(attempt / 'submitted.json')
    request_hash = digest(attempt / 'request.json')
    require(receipt['requestSha256'] == submitted['requestSha256'] == request_hash,
            'Execution receipt does not belong to this frozen request.')
    status = receipt['status']
    require(status.get('processState') == 'EXITED' and status.get('exitCode') == 0 and
            status.get('artifactVerification') == 'BYTES_VERIFIED', 'Attempt has no completed, byte-verified execution receipt.')
    require(receipt.get('verificationSha256') == digest(attempt / 'verification.json'), 'Recorded execution verification changed.')
    recorded = read_json(attempt / 'verification.json')
    require(verification['manifestSha256'] == recorded['manifestSha256'] and
            verification['manifest']['researchRunIdentity'] == recorded['manifest']['researchRunIdentity'] == status['researchRunIdentity'],
            'Current output is not the evidence verified at execution. Inspect the independent native run by its own directory.')
    return request


def status(path):
    path = no_links(path)
    if (path / 'experiment.json').exists() and not (path / 'request.json').exists():
        attempts = sorted((path / 'attempts').glob('[0-9][0-9][0-9][0-9]'))
        return dict(experiment=str(path), attempts=[status(attempt) for attempt in attempts],
                    next='preflight the draft to create a new attempt' if not attempts else 'Inspect the named attempt; new attempts never resume prior work implicitly.')
    result = dict(attempt=str(path), operational=read_json(path / 'status.json') if (path / 'status.json').exists() else {'processState': 'UNKNOWN'},
                  artifactVerification='NOT_RECHECKED', researchDisposition='NOT_INTERPRETED')
    if (path / 'submitted.json').exists():
        submission = read_json(path / 'submitted.json')
        result['submission'] = submission
        if submission.get('unit') and shutil.which('systemctl'):
            live = subprocess.run(['systemctl', '--user', 'show', submission['unit'], '-p', 'ActiveState', '-p', 'SubState', '-p', 'MainPID', '-p', 'Result'], capture_output=True, text=True)
            result['serviceObservation'] = dict(line.split('=', 1) for line in live.stdout.splitlines() if '=' in line)
            result['serviceObservedAtUtc'] = now()
            if result['operational'].get('processState') == 'RUNNING' and result['serviceObservation'].get('ActiveState') not in ('active', 'activating'):
                result['warning'] = 'Stored RUNNING status is stale; the service is not active. Inspect logs and retained output; completion is unknown.'
    output = path / 'output'
    result['outputDirectory'] = str(output)
    result['manifestPresent'] = (output / 'research-run-manifest.json').is_file()
    result['next'] = 'verify/inspect the output; a finalized manifest does not establish a scientific pass'
    return result


def diff_experiments(left, right):
    def content(path):
        path, spec = load_draft(path)
        return dict(experiment=spec, plan=read_json(plan_input(path, spec)))
    changes = []
    def walk(a, b, pointer=''):
        if type(a) is dict and type(b) is dict:
            for key in sorted(a.keys() | b.keys()):
                route = pointer + '/' + key.replace('~', '~0').replace('/', '~1')
                if key not in a or key not in b:
                    changes.append(dict(pointer=route, beforePresent=key in a, afterPresent=key in b,
                                        before=a.get(key), after=b.get(key)))
                else:
                    walk(a[key], b[key], route)
        elif type(a) != type(b) or a != b:
            changes.append(dict(pointer=pointer, beforePresent=True, afterPresent=True, before=a, after=b))
    walk(content(left), content(right))
    return dict(changes=changes, interpretation='Configuration and design differences; no claim of semantic equivalence or causal isolation.')
