"""Read stored and live operational status, failure records and bounded log tails."""
from collections import deque
import shutil
import subprocess
from .requests import verify_request
from .storage import no_links, now, read_json, require


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


def read_log(attempt, preflight=False, lines=60):
    require(0 < lines <= 10000, '--lines must be between 1 and 10000')
    path = no_links(attempt) / ('preflight.log' if preflight else 'run.log')
    no_links(path)
    require(path.is_file(), f'Log is not present: {path}')
    with path.open(errors='replace') as stream:
        tail = ''.join(deque(stream, maxlen=lines))
    return dict(path=str(path), lines=tail)


def diagnose(path):
    path = no_links(path)
    result = status(path)
    findings = []
    try:
        verify_request(path, current_source=True)
        findings.append(dict(check='frozen request and current source', state='PASS'))
    except (ValueError, OSError, KeyError, subprocess.SubprocessError) as exc:
        findings.append(dict(check='frozen request and current source', state='REFUSED', detail=str(exc)))
    for name in ('status.json', 'preflight-failure.json', 'output/failure.json', 'output/preflight-failure.json'):
        target = no_links(path / name)
        if target.is_file() and target.stat().st_size <= 1024 * 1024:
            findings.append(dict(observedFile=str(target), content=read_json(target), verification='operational-unverified'))
    tails = []
    for preflight in (True, False):
        if (path / ('preflight.log' if preflight else 'run.log')).is_file():
            tails.append(read_log(path, preflight))
    result.update(findings=findings, logs=tails,
                  next='Repair the named prerequisite and create a fresh attempt. Reuse completed scientific stages only through the native plan\'s explicit retained references.',
                  limitation='Logs and wrapper states are operational observations, not a scientific failure diagnosis or outcome.')
    return result
