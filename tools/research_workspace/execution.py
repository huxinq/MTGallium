"""Single submission and bounded process supervision; never research interpretation."""
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time
from .native import native
from .requests import verify_request
from .storage import (REPO, Refusal, atomic_json, digest, now, private_path,
                      read_json, require, write_new)


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
