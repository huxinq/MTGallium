"""Structural readiness, frozen attempt allocation and explicit preflight."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from .capabilities import CAPABILITIES
from .drafts import load_draft, input_path, plan_input, deck_input, design_problems
from .native import build_reference, native, runtime
from .requests import bound_arguments, verify_request
from .source import clean_source
from .storage import (REPO, atomic_json, digest, no_links, now, private_path,
                      private_work, read_json, require, write_new)


def inspect_build(spec):
    require(spec['inputs']['build'], 'inputs.build must name a frozen build directory.')
    reference = build_reference(spec['inputs']['build'])
    # The native API consumes an exact reference file. Keep this transient check
    # private and remove it on return; doctor creates no retained attempt.
    with tempfile.TemporaryDirectory(prefix='.doctor-build-', dir=private_work()) as temporary:
        path = Path(temporary) / 'build-reference.json'
        write_new(path, reference)
        return native(['build-verify', path], reference['directory'], spec['execution'])


def plan_draft(path, build=None):
    path, spec = load_draft(path)
    plan = plan_input(path, spec)
    result = native(['plan', spec['kind'], plan], build or spec['inputs']['build'], spec['execution'])
    return dict(design=spec['design'], execution=spec['execution'], lineage=spec.get('lineage'),
                planPath=str(plan), planSha256=digest(plan), native=result,
                threadControl='execution.threads' if CAPABILITIES[spec['kind']].uses_execution_threads
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
        ('deck input', lambda: read_json(deck_input(path, spec)) if spec['inputs']['deck'] else 'No deck required by this kind'),
        ('effective typed plan', lambda: plan_draft(path)['native']),
        ('frozen build attestation', lambda: inspect_build(spec)),
    ):
        try:
            checks.append(dict(check=label, passed=True, detail=check()))
        except (ValueError, OSError, subprocess.SubprocessError) as exc:
            checks.append(dict(check=label, passed=False, detail=str(exc)))
            problems.append(f'{label}: {exc}')
    return dict(readyToFreeze=not problems, gate=CAPABILITIES[spec['kind']].gate, problems=problems, checks=checks,
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
        gate = CAPABILITIES[spec['kind']].gate
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
        if request['kind'] == 'factual-residual-study' and read_json(attempt / 'plan.json').get('admissionParent'):
            checked = native(['factual-residual-continuation-check', attempt / 'plan.json', attempt / 'deck.json'],
                             request['build']['directory'], request['execution'])
            atomic_json(attempt / 'continuation-check.json', checked)
            gate += '; ADMISSION_PARENT_CHECKED'
    atomic_json(attempt / 'status.json', dict(processState='PREPARED', updatedAtUtc=now(), preflight=gate))
    return dict(attempt=str(attempt), requestSha256=digest(attempt / 'request.json'), preflight=gate,
                next=f'research launch {attempt}')
