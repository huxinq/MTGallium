"""Dispatch parsed commands to their operational owners."""
import os
from pathlib import Path
import subprocess
import sys
from . import capabilities, drafts, evidence, execution, inspection, monitoring, preparation
from .native import build_reference, native, runtime
from .source import source_state
from .storage import REPO, Refusal, digest, private_path, private_work, require


def execute(args):
    command = args.command
    if command == 'catalog':
        result = capabilities.catalog()
        if args.all:
            result['native'] = native(['catalog'], args.build)
        return result
    if command == 'new':
        return drafts.new_experiment(args.name, args.kind, args.plan, args.deck, args.build, args.question, args.output)
    if command == 'schema':
        result = native(['schema', args.kind], args.build)
        if args.type:
            result['selectedType'] = args.type
        return result
    if command == 'fork':
        return drafts.fork_experiment(args.source, args.name, args.reason, args.output)
    if command == 'diff':
        return drafts.diff_experiments(args.left, args.right)
    if command == 'plan':
        return preparation.plan_draft(args.path, args.build)
    if command == 'doctor':
        return preparation.doctor(args.path)
    if command == 'freeze':
        return dict(attempt=str(preparation.freeze(args.path)), next='preflight the named attempt, then launch it')
    if command == 'preflight':
        return preparation.preflight(args.path)
    if command == 'launch':
        return execution.submit(args.attempt, args.foreground)
    if command == '_run':
        return execution.run_attempt(args.attempt)
    if command == 'status':
        return monitoring.status(args.path)
    if command == 'diagnose':
        return monitoring.diagnose(args.path)
    if command == 'logs':
        return monitoring.read_log(args.attempt, args.preflight, args.lines)
    if command == 'find':
        return evidence.find_runs(args.root or private_work(), args.query, args.limit)
    if command in ('verify', 'inspect', 'describe', 'extract'):
        run, build = inspection.run_and_build(args.run, args.build)
        check = inspection.verifier(build, args.identity)
        if command == 'verify':
            return check(run)
        if command == 'inspect':
            return evidence.inspect_run(run, check)
        if command == 'describe':
            return evidence.describe_artifact(run, args.artifact, check)
        state = source_state()
        extractor = dict(sourceRevision=state['sourceRevision'], dirty=state['dirty'],
                         scriptSha256=digest(Path(evidence.__file__)), purpose=args.purpose,
                         verificationRuntime=build_reference(build))
        return evidence.extract_json(run, args.artifact, args.pointer, private_path(args.output), check, extractor)
    if command == 'packet':
        return inspection.packet(args.attempt, args.output)
    if command == 'build':
        private_path(args.output)
        subprocess.run([sys.executable, str(REPO / 'tools' / 'mtgallium-research-build'), '--output', str(args.output)], cwd=REPO,
                       env=dict(os.environ, MTGALLIUM_PUBLIC_SOURCE='1'), check=True,
                       stdout=sys.stderr if args.json else None)
        return dict(directory=str(args.output), next='Set inputs.build in your experiment draft to this directory.')
    if command == 'native':
        require(args.build, 'Pass --build or set MTGALLIUM_RESEARCH_BUILD.')
        arguments = args.arguments[1:] if args.arguments[:1] == ['--'] else args.arguments
        require(arguments, 'Pass the specialized native CLI flags after --.')
        command = runtime(args.build, {})
        command[-1] = 'org.mtgallium.evaluation.searchteacher.SearchTeacherEvaluationKt'
        return dict(argv=command + arguments, cwd=str(REPO), environment={'MTGALLIUM_PUBLIC_SOURCE': '1',
                    'MTGALLIUM_PRIVATE_EVIDENCE_ROOT': str(private_work().parent.parent)},
                    executes=False, note='This prints a command for an explicitly documented native route. Apply that route\'s gates and output contract.')
    raise Refusal('Unknown command')
