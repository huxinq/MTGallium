"""Native artifact verification and review packets bound to completed executions."""
from pathlib import Path
from . import evidence
from .native import native
from .requests import verify_request
from .source import source_state
from .storage import digest, no_links, now, private_path, read_json, require, write_new


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


def run_and_build(path, override=None):
    path = no_links(path)
    if (path / 'request.json').is_file():
        request = read_json(path / 'request.json')
        return Path(request['targetOutput']), override or request['build']['directory']
    return path, override


def verifier(build, expected=None):
    return lambda directory: native(['verify', directory] + ([expected] if expected else []), build)


def packet(attempt, output):
    attempt = no_links(attempt)
    request = verify_request(attempt)
    run, build = run_and_build(attempt)
    verified = native(['verify', run], build)
    verified_execution(attempt, verified)
    inspection = evidence.inspect_run(run, lambda _: verified)
    output = private_path(output)
    require(not output.exists(), 'Choose a fresh review packet directory.')
    output.mkdir(parents=True)
    design = read_json(attempt / 'experiment.json')['design']
    review = dict(schemaVersion=1, createdAtUtc=now(), requestSha256=digest(attempt / 'request.json'),
                  executionSource=request['source'], packetSource=source_state(), inspection=inspection,
                  observation='', interpretation='', alternativeExplanations='', supportedClaim='',
                  excludedClaims=design['limitations'], nextDecision='',
                  instruction='Complete observations from source-native verified populations. Separate terminal results, conditional targets, heuristics and non-game failures.')
    write_new(output / 'review.json', review)
    write_new(output / 'evidence.json', inspection)
    write_new(output / 'effective-plan.json', read_json(attempt / 'effective-plan.json'))
    lines = [f'# {request["name"]}: research review', '',
             'This packet binds an execution request and verified artifact bytes. The scientific interpretation remains explicit in `review.json`.', '',
             '## Question and design', '']
    lines += [f'- **{key}**: {value}' for key, value in design.items()]
    lines += ['', '## Exact execution', '', f'- Attempt: `{attempt}`',
              f'- Source: `{request["source"]["sourceRevision"]}`',
              f'- Argentum: `{request["source"]["argentumRevision"]}`',
              f'- Request SHA-256: `{digest(attempt / "request.json")}`',
              f'- Build: `{request["build"]["identity"]}`',
              f'- Native output: `{run}`',
              '- The effective scientific configuration is in `effective-plan.json`; prose design does not override it.',
              '', '## Evidence and interpretation', '',
              '- `evidence.json` contains the original identity and authenticated artifact inventory.',
              '- Manifest completion records retained bytes. It does not establish a successful gate, valid population or strength conclusion.',
              '- Account separately for planned, attempted, completed, refused, stopped, excluded and overshoot work using the appropriate native report.',
              '- State the actual learner, target, control, shared components and changed intervention before interpreting differences.',
              '- Record inspected population use through `campaign-data-use`; absence of a registry entry does not prove fresh confirmation.',
              '- Fill `review.json` with observed quantities, their meaning and limits, alternative explanations and the next decision.',
              '', '## Useful commands', '',
              f'`research inspect {attempt}`', f'`research describe {attempt} report.json`',
              f'`research native --build {build} -- --suite gameplay-summary --run-directory {run}`',
              '', 'Use the gameplay summary only for its supported gameplay populations. Conditional-target diagnosis and transfer audits are separate catalog workflows.', '']
    (output / 'README.md').write_text('\n'.join(lines))
    return dict(directory=str(output), review=str(output / 'review.json'),
                evidenceIdentity=inspection.get('declared', {}).get('researchRunIdentity'),
                next='Read README.md and complete review.json; retain a new packet for a revised interpretation.')
