"""Human command surface. JSON output is available without parsing presentation."""
import argparse
from collections import deque
import json
import os
from pathlib import Path
import subprocess
import sys

from . import evidence, workflow
from .common import (REPO, Refusal, digest, git, json_bytes, native, no_links, now,
                     private_path, private_work, read_json, require, source_state, write_new)


def parser():
    root = argparse.ArgumentParser(
        prog='research', description='Design, freeze, run and inspect MTGallium experiments.',
        epilog='Start: catalog → new → edit → doctor → preflight → launch → status → inspect. '
               'Use --json after any command for scripts. All generated records stay private.')
    commands = root.add_subparsers(dest='command', required=True, metavar='COMMAND')
    def command(name, help):
        item = commands.add_parser(name, help=help, description=help)
        item.add_argument('--json', action='store_true', help='Print structured JSON')
        return item
    def build_option(item):
        item.add_argument('--build', type=Path, default=os.environ.get('MTGALLIUM_RESEARCH_BUILD'),
                          help='Frozen build directory (or MTGALLIUM_RESEARCH_BUILD)')
    p = command('catalog', 'Choose a workflow and see exactly which gate it supplies')
    p.add_argument('--all', action='store_true', help='Also list every native suite from the frozen runtime')
    build_option(p)
    p = command('schema', 'Show the actual native plan fields, types and optionality for experiment design')
    p.add_argument('kind', choices=[*workflow.CAPABILITIES, 'campaign-data-use'])
    p.add_argument('--type', help='Show one named nested type (full name or suffix) in human output')
    build_option(p)
    p = command('new', 'Create an editable experiment draft from an existing typed plan')
    p.add_argument('name')
    p.add_argument('--kind', choices=workflow.CAPABILITIES, required=True)
    p.add_argument('--plan', type=Path, required=True)
    p.add_argument('--deck', type=Path)
    p.add_argument('--question', default='')
    p.add_argument('--output', type=Path)
    build_option(p)
    p = command('fork', 'Copy a draft or frozen attempt with explicit ancestry; retain the original')
    p.add_argument('source', type=Path)
    p.add_argument('name')
    p.add_argument('--reason', required=True)
    p.add_argument('--output', type=Path)
    p = command('diff', 'Show exact design and plan differences between two experiments')
    p.add_argument('left', type=Path)
    p.add_argument('right', type=Path)
    for name, help in [('plan', 'Decode the typed plan and show effective defaults alongside the research design'),
                       ('doctor', 'List missing design, input, source and configuration prerequisites without running research'),
                       ('freeze', 'Capture immutable attempt inputs and verify the build without collecting samples'),
                       ('preflight', 'Freeze a draft and rehearse, or verify/rehearse an existing unlaunched attempt'),
                       ('status', 'Read operational status and current durable service state'),
                       ('diagnose', 'Inspect failure records and log messages without rerunning work')]:
        p = command(name, help)
        p.add_argument('path', type=Path)
        if name == 'plan':
            build_option(p)
    p = command('launch', 'Launch a frozen attempt exactly once; durable systemd execution is the default')
    p.add_argument('attempt', type=Path)
    p.add_argument('--foreground', action='store_true', help='Bounded interactive execution instead of a durable user service')
    p = command('logs', 'Read the last lines of an attempt log')
    p.add_argument('attempt', type=Path)
    p.add_argument('--preflight', action='store_true')
    p.add_argument('--lines', type=int, default=60)
    p = command('find', 'Retrieve experiments and native runs by metadata without reading outcomes')
    p.add_argument('query', nargs='?', default='')
    p.add_argument('--root', type=Path)
    p.add_argument('--limit', type=int, default=50)
    for name, help in [('verify', 'Verify every registered artifact through the native manifest authority'),
                       ('inspect', 'Authenticate and list artifacts without opening outcomes or canonical replay'),
                       ('describe', 'Authenticate a JSON artifact and show its top-level keys without values')]:
        p = command(name, help)
        p.add_argument('run', type=Path)
        p.add_argument('--identity', help='Require an exact retained research-run identity')
        build_option(p)
        if name == 'describe':
            p.add_argument('artifact')
    p = command('extract', 'Export an exact registered JSON field and its provenance; no filtering or new feature definition')
    p.add_argument('run', type=Path)
    p.add_argument('artifact')
    p.add_argument('--pointer', default='', help='RFC 6901 JSON pointer; empty selects the entire artifact')
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--purpose', required=True, help='Record the intended use of this derivative; this is not a campaign population registration')
    p.add_argument('--identity')
    build_option(p)
    p = command('packet', 'Create a private review packet with verified evidence links and explicit interpretation questions')
    p.add_argument('attempt', type=Path)
    p.add_argument('--output', type=Path, required=True)
    p = command('build', 'Force and retain a clean-source frozen runtime with the existing build authority')
    p.add_argument('--output', type=Path, required=True)
    p = command('native', 'Print an argument-safe native CLI invocation for a documented specialized command')
    build_option(p)
    p.add_argument('arguments', nargs=argparse.REMAINDER,
                   help='After --, pass native CLI flags. This command prints argv and does not launch.')
    p = commands.add_parser('_run')
    p.add_argument('attempt', type=Path)
    return root


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
    result = workflow.status(path)
    findings = []
    try:
        workflow.verify_request(path, current_source=True)
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
    request = workflow.verify_request(attempt)
    run, build = run_and_build(attempt)
    verified = native(['verify', run], build)
    workflow.verified_execution(attempt, verified)
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


def execute(args):
    c = args.command
    if c == 'catalog':
        result = workflow.catalog()
        if args.all:
            result['native'] = native(['catalog'], args.build)
        return result
    if c == 'new':
        return workflow.new_experiment(args.name, args.kind, args.plan, args.deck, args.build, args.question, args.output)
    if c == 'schema':
        result = native(['schema', args.kind], args.build)
        if args.type:
            result['selectedType'] = args.type
        return result
    if c == 'fork':
        return workflow.fork_experiment(args.source, args.name, args.reason, args.output)
    if c == 'diff':
        return workflow.diff_experiments(args.left, args.right)
    if c == 'plan':
        return workflow.plan_draft(args.path, args.build)
    if c == 'doctor':
        return workflow.doctor(args.path)
    if c == 'freeze':
        return dict(attempt=str(workflow.freeze(args.path)), next='preflight the named attempt, then launch it')
    if c == 'preflight':
        return workflow.preflight(args.path)
    if c == 'launch':
        return workflow.submit(args.attempt, args.foreground)
    if c == '_run':
        return workflow.run_attempt(args.attempt)
    if c == 'status':
        return workflow.status(args.path)
    if c == 'diagnose':
        return diagnose(args.path)
    if c == 'logs':
        return read_log(args.attempt, args.preflight, args.lines)
    if c == 'find':
        return evidence.find_runs(args.root or private_work(), args.query, args.limit)
    if c in ('verify', 'inspect', 'describe', 'extract'):
        run, build = run_and_build(args.run, args.build)
        check = verifier(build, args.identity)
        if c == 'verify':
            return check(run)
        if c == 'inspect':
            return evidence.inspect_run(run, check)
        if c == 'describe':
            return evidence.describe_artifact(run, args.artifact, check)
        state = source_state()
        extractor = dict(sourceRevision=state['sourceRevision'], dirty=state['dirty'],
                         scriptSha256=digest(Path(evidence.__file__)), purpose=args.purpose,
                         verificationRuntime=build_reference_for_export(build))
        return evidence.extract_json(run, args.artifact, args.pointer, private_path(args.output), check, extractor)
    if c == 'packet':
        return packet(args.attempt, args.output)
    if c == 'build':
        private_path(args.output)
        subprocess.run([sys.executable, str(REPO / 'tools' / 'mtgallium-research-build'), '--output', str(args.output)], cwd=REPO,
                       env=dict(os.environ, MTGALLIUM_PUBLIC_SOURCE='1'), check=True,
                       stdout=sys.stderr if args.json else None)
        return dict(directory=str(args.output), next='Set inputs.build in your experiment draft to this directory.')
    if c == 'native':
        from .common import runtime
        require(args.build, 'Pass --build or set MTGALLIUM_RESEARCH_BUILD.')
        arguments = args.arguments[1:] if args.arguments[:1] == ['--'] else args.arguments
        require(arguments, 'Pass the specialized native CLI flags after --.')
        command = runtime(args.build, {})
        command[-1] = 'org.mtgallium.evaluation.searchteacher.SearchTeacherEvaluationKt'
        return dict(argv=command + arguments, cwd=str(REPO), environment={'MTGALLIUM_PUBLIC_SOURCE': '1',
                    'MTGALLIUM_PRIVATE_EVIDENCE_ROOT': str(private_work().parent.parent)},
                    executes=False, note='This prints a command for an explicitly documented native route. Apply that route\'s gates and output contract.')
    raise Refusal('Unknown command')


def build_reference_for_export(build):
    from .common import build_reference
    return build_reference(build)


def render(command, value):
    if command == 'catalog':
        print('Workflow                          Gate                 Purpose')
        for row in value['capabilities']:
            print(f'{row["kind"]:33} {row["gate"]:20} {row["purpose"]}')
        print('\nSpecialized native routes: ' + ', '.join(row['suite'] for row in value['nativeRoutes']))
        print('See docs/research-workbench.md. Use --json for capability details; --all --build PATH for every suite.')
        if 'native' in value:
            print('\nAll native suites:\n' + '\n'.join(row['id'] for row in value['native']['suites']))
    elif command == 'doctor':
        print('Ready to freeze.' if value['readyToFreeze'] else 'Preparation needed:')
        for check in value['checks']:
            print(f'  {"OK" if check["passed"] else "REFUSED"}: {check["check"]}')
        for problem in value['problems']:
            print('  - ' + problem)
        print('Gate: ' + value['gate'] + '\n' + value['next'])
    elif command == 'logs':
        print(value['path'] + '\n' + value['lines'], end='')
    elif command == 'find':
        for row in value['entries']:
            print(row['directory'])
            for document in row.get('documents', []):
                declared = document.get('declared', {})
                description = declared.get('researchRunIdentity') or declared.get('name') or document['name']
                question = declared.get('design', {}).get('question', '')
                print('  unverified: ' + str(description) + (' — ' + question[:180] if question else ''))
        for error in value['errors']:
            print('ERROR: ' + json.dumps(error, ensure_ascii=False))
        if value.get('truncated'):
            print('More entries exist; narrow the query or increase --limit.')
    elif command == 'status':
        entries = value.get('attempts', [value])
        if not entries:
            print('No frozen attempts. ' + value['next'])
        for entry in entries:
            recorded = entry['operational']
            print(entry['attempt'])
            print('  Process: ' + recorded.get('processState', 'UNKNOWN'))
            print('  Artifact verification: ' + recorded.get('artifactVerification', 'NOT_RUN') + ' (recorded; use verify to recheck)')
            print('  Research disposition: ' + recorded.get('researchDisposition', 'NOT_INTERPRETED'))
            if 'serviceObservation' in entry:
                print('  Current service: ' + json.dumps(entry['serviceObservation']))
            if 'elapsedSeconds' in recorded:
                print(f'  Elapsed: {recorded["elapsedSeconds"]:.2f} seconds')
            if 'error' in recorded:
                print('  Error: ' + recorded['error'])
            if 'warning' in entry:
                print('  ' + entry['warning'])
            print('  Output: ' + entry['outputDirectory'])
    elif command in ('verify', 'inspect'):
        manifest = value.get('manifest', value.get('declared', {}))
        artifacts = value.get('artifacts', manifest.get('artifacts', []))
        print('Verified artifact bytes: ' + manifest['researchRunIdentity'])
        print('Manifest SHA-256: ' + value['manifestSha256'])
        print(f'{len(artifacts)} registered artifacts; {sum(row["bytes"] for row in artifacts):,} bytes.')
        if command == 'inspect':
            for row in artifacts[:50]:
                print(f'  {row["bytes"]:>12,}  {row["relativePath"]}')
            if len(artifacts) > 50:
                print('  More artifacts omitted from this display; --json retains the complete inventory.')
        print('Scientific validity and interpretation require the appropriate native report/population checks.')
    elif command == 'schema':
        definitions = value['definitions']
        requested = value.get('selectedType')
        if requested:
            selected = [row for row in definitions.values() if row['serialName'] == requested or row['serialName'].endswith('.' + requested)]
            require(len(selected) == 1, 'Type is missing or ambiguous. Use --json to inspect exact serial names.')
            definition = selected[0]
        else:
            definition = definitions[value['root']['ref']]
        print(definition['serialName'] + ' (native type shape; constructor constraints still apply)')
        for field in definition.get('fields', []):
            flags = ('optional' if field['optional'] else 'required') + (', nullable' if field['type']['nullable'] else '')
            print(f'  {field["name"]}: {field["type"]["serialName"]} [{flags}]')
        if 'enumValues' in definition:
            print('  Values: ' + ', '.join(definition['enumValues']))
        print('Use --type SERIAL_NAME for a nested type; --json returns all type definitions.')
    elif command == 'plan':
        print('Research design:')
        for key, text in value['design'].items():
            if text:
                print(f'  {key}: {text}')
        print('\nEffective native plan (structural validation):')
        print(json_bytes(value['native']['effectivePlan']).decode(), end='')
        print('Worker authority: ' + value['threadControl'])
        print('Plan SHA-256: ' + value['planSha256'])
    elif command == 'diff':
        if not value['changes']:
            print('No design or plan differences.')
        for change in value['changes']:
            print(change['pointer'])
            print('  before: ' + (json.dumps(change['before'], ensure_ascii=False) if change['beforePresent'] else '<absent>'))
            print('  after:  ' + (json.dumps(change['after'], ensure_ascii=False) if change['afterPresent'] else '<absent>'))
        print(value['interpretation'])
    else:
        print(json_bytes(value).decode(), end='')


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        result = execute(args)
        if args.command == '_run':
            return result
        if args.json:
            print(json_bytes(result).decode(), end='')
        else:
            render(args.command, result)
        return 2 if args.command == 'doctor' and not result['readyToFreeze'] else 0
    except (ValueError, OSError, KeyError, TypeError, subprocess.SubprocessError) as exc:
        if getattr(args, 'json', False):
            print(json_bytes(dict(status='REFUSED', error=str(exc))).decode(), end='')
        else:
            print(f'Research command refused: {exc}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
