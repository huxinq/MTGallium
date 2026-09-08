"""Synthetic execution witnesses: no games, private fixtures or real JVM build."""
from contextlib import redirect_stdout
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from research_workspace import capabilities, cli, drafts, execution, inspection, monitoring, native, preparation, requests
from research_workspace import storage


class WorkflowTest(unittest.TestCase):
    def setUp(self):
        # Some developer machines have an actual /tmp Git worktree. The output
        # guard correctly refuses it; allocate public-safe fixtures outside it.
        self.temp = tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR', '/var/tmp'))
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = patch.dict(os.environ, MTGALLIUM_PRIVATE_EVIDENCE_ROOT=str(self.root / 'private'))
        self.env.start()
        self.addCleanup(self.env.stop)
        self.plan = self.root / 'input.json'
        self.plan.write_text('{"baseSeed":7,"pairCount":2}\n')
        self.deck = self.root / 'deck.json'
        self.deck.write_text('{"deck":"synthetic"}\n')
        self.build = self.root / 'build'
        self.build.mkdir()
        (self.build / storage.MANIFEST).write_text('{"researchRunIdentity":"build-id"}')
        self.jar = self.build / 'runtime.jar'
        self.jar.write_bytes(b'synthetic')
        (self.build / 'classpath.txt').write_text(str(self.jar))
        self.source = dict(repository=str(storage.REPO), sourceRevision='source-a', argentumRevision='engine-a',
                           expectedArgentumRevision='engine-a', dirty=False, engineDirty=False)
        for owner in (preparation, requests):
            source_patch = patch.object(owner, 'clean_source', return_value=self.source)
            source_patch.start()
            self.addCleanup(source_patch.stop)
        for owner in (preparation, execution):
            native_patch = patch.object(owner, 'native', side_effect=self.fake_native)
            native_patch.start()
            self.addCleanup(native_patch.stop)
        self.calls = []

    def fake_native(self, command, *args, **kwargs):
        self.calls.append(command)
        if command[0] == 'plan':
            return dict(kind=command[1], validation='structural-only', effectivePlan=storage.read_json(command[2]))
        if command[0] == 'verify':
            return dict(manifest={'researchRunIdentity': 'original-evidence-id'}, manifestSha256='a' * 64)
        return {}

    def draft(self, kind='calibration'):
        result = drafts.new_experiment('study', kind, self.plan, self.deck, self.build, 'Does A change B?')
        path = Path(result['draft'])
        spec = storage.read_json(path)
        spec['design'] = {key: 'Explicit synthetic ' + key for key in capabilities.DESIGN_FIELDS}
        spec['execution'].update(timeoutSeconds=2, smokeBaseSeed=101)
        storage.atomic_json(path, spec)
        return path.parent

    def submitted(self, attempt):
        storage.write_new(attempt / 'submitted.json', {'requestSha256': storage.digest(attempt / 'request.json')})

    def synthetic_command(self, attempt, program):
        # Test process supervision with a real harmless child, while keeping request
        # verification exercised separately by the tampering witnesses below.
        request = storage.read_json(attempt / 'request.json')
        request['command'] = [sys.executable, '-c', program]
        return patch.object(execution, 'verify_request', return_value=request)

    def test_draft_does_not_invent_scientific_design_or_overwrite_inputs(self):
        result = drafts.new_experiment('draft', 'sequential', self.plan, self.deck)
        spec = storage.read_json(result['draft'])
        self.assertIsNone(spec['execution']['timeoutSeconds'])
        self.assertIsNone(spec['execution']['smokeBaseSeed'])
        self.assertTrue(drafts.design_problems(spec))
        self.assertEqual(self.plan.read_bytes(), (Path(result['directory']) / 'plan.json').read_bytes())
        with self.assertRaises(storage.Refusal):
            drafts.new_experiment('draft', 'sequential', self.plan, self.deck)

    def test_freeze_binds_exact_inputs_output_runtime_and_never_collects(self):
        draft = self.draft()
        attempt = preparation.freeze(draft)
        request = requests.verify_request(attempt, current_source=True)
        profile = storage.read_json(attempt / 'preflight.json')
        self.assertEqual(str(attempt / 'output'), profile['targetOutput'])
        self.assertEqual(str(attempt / 'plan.json'), profile['work']['planPath'])
        self.assertEqual(['plan', 'build-verify'], [call[0] for call in self.calls])
        self.assertFalse((attempt / 'output').exists())
        self.assertEqual('source-a', request['source']['sourceRevision'])

    def test_factual_residual_freeze_uses_authenticated_native_route(self):
        attempt = preparation.freeze(self.draft('factual-residual-study'))
        request = requests.verify_request(attempt, current_source=True)
        self.assertEqual('authenticated-inputs', request['gate'])
        self.assertEqual('factual-residual-study', request['nativeArguments'][1])
        self.assertEqual('execute', request['nativeArguments'][0])
        self.assertEqual(str(attempt / 'deck.json'), request['nativeArguments'][4])
        self.assertEqual(['plan', 'build-verify'], [call[0] for call in self.calls])
        self.assertFalse((attempt / 'output').exists())

    def test_changed_frozen_plan_refuses_and_changed_draft_does_not_relabel_attempt(self):
        draft = self.draft()
        attempt = preparation.freeze(draft)
        (draft / 'plan.json').write_text('{"changed":true}')
        self.assertEqual(7, storage.read_json(attempt / 'plan.json')['baseSeed'])
        requests.verify_request(attempt)
        (attempt / 'plan.json').write_text('{"changed":true}')
        with self.assertRaisesRegex(storage.Refusal, 'Frozen input changed'):
            requests.verify_request(attempt)

    def test_command_and_gate_cannot_be_replaced_in_stored_request(self):
        attempt = preparation.freeze(self.draft())
        path = attempt / 'request.json'
        original = storage.read_json(path)
        request = dict(original, nativeArguments=['execute', 'calibration', '/other'])
        storage.atomic_json(path, request)
        with self.assertRaisesRegex(storage.Refusal, 'native arguments'):
            requests.verify_request(attempt)
        storage.atomic_json(path, dict(original, gate='authenticated-inputs'))
        with self.assertRaisesRegex(storage.Refusal, 'launch gate'):
            requests.verify_request(attempt)

    def test_source_movement_and_missing_bound_input_refuse(self):
        attempt = preparation.freeze(self.draft())
        with patch.object(requests, 'clean_source', return_value=dict(self.source, sourceRevision='later')):
            with self.assertRaisesRegex(storage.Refusal, 'Treatment source changed'):
                requests.verify_request(attempt, current_source=True)
        request = storage.read_json(attempt / 'request.json')
        del request['files']['plan.json']
        storage.atomic_json(attempt / 'request.json', request)
        with self.assertRaisesRegex(storage.Refusal, 'input inventory'):
            requests.verify_request(attempt)

    def test_launch_requires_preflight_and_never_implicitly_runs_it(self):
        attempt = preparation.freeze(self.draft())
        with self.assertRaisesRegex(storage.Refusal, 'Run preflight'):
            execution.submit(attempt, foreground=True)
        self.assertFalse((attempt / 'submitted.json').exists())
        self.assertFalse(any(call[0] == 'preflight' for call in self.calls))

    def test_duplicate_submission_refuses_before_execution(self):
        attempt = preparation.freeze(self.draft('position-features'))
        self.submitted(attempt)
        with patch.object(execution.subprocess, 'run') as run:
            with self.assertRaisesRegex(storage.Refusal, 'already submitted'):
                execution.submit(attempt, foreground=True)
            run.assert_not_called()

    def test_successful_process_and_byte_verification_remain_uninterpreted(self):
        attempt = preparation.freeze(self.draft('position-features'))
        self.submitted(attempt)
        with self.synthetic_command(attempt, 'print("synthetic technical work")'):
            self.assertEqual(0, execution.run_attempt(attempt))
        status = storage.read_json(attempt / 'status.json')
        self.assertEqual('EXITED', status['processState'])
        self.assertEqual('BYTES_VERIFIED', status['artifactVerification'])
        self.assertEqual('NOT_INTERPRETED', status['researchDisposition'])
        self.assertEqual('original-evidence-id', status['researchRunIdentity'])
        self.assertTrue((attempt / 'receipt.json').exists())
        with self.synthetic_command(attempt, 'raise SystemExit(0)'):
            with self.assertRaises(FileExistsError):
                execution.run_attempt(attempt)

    def test_exit_zero_with_missing_evidence_is_a_verification_refusal(self):
        attempt = preparation.freeze(self.draft('position-features'))
        self.submitted(attempt)
        with self.synthetic_command(attempt, 'print("no artifact")'), patch.object(execution, 'native', side_effect=storage.Refusal('missing manifest')):
            self.assertEqual(2, execution.run_attempt(attempt))
        status = storage.read_json(attempt / 'status.json')
        self.assertEqual('EXITED', status['processState'])
        self.assertEqual('REFUSED', status['artifactVerification'])
        self.assertEqual('NOT_INTERPRETED', status['researchDisposition'])

    def test_nonzero_and_timeout_never_verify_or_acquire_game_outcome(self):
        draft = self.draft('position-features')
        for program, state, code in [('raise SystemExit(9)', 'FAILED', 9), ('import time; time.sleep(20)', 'TIMED_OUT', 124)]:
            with self.subTest(state=state):
                attempt = preparation.freeze(draft)
                self.submitted(attempt)
                with self.synthetic_command(attempt, program):
                    self.assertEqual(code, execution.run_attempt(attempt))
                status = storage.read_json(attempt / 'status.json')
                self.assertEqual(state, status['processState'])
                self.assertEqual('NOT_RUN', status['artifactVerification'])
                self.assertEqual('NOT_INTERPRETED', status['researchDisposition'])

    def test_final_verification_shares_the_attempt_deadline(self):
        attempt = preparation.freeze(self.draft('position-features'))
        self.submitted(attempt)
        def blocked_verifier(command, build, execution, timeout=None):
            self.assertIsNotNone(timeout)
            self.assertLessEqual(timeout, 2)
            subprocess.run([sys.executable, '-c', 'import time; time.sleep(20)'], timeout=timeout)
        with self.synthetic_command(attempt, 'print("done")'), patch.object(execution, 'native', side_effect=blocked_verifier):
            self.assertEqual(124, execution.run_attempt(attempt))
        receipt = storage.read_json(attempt / 'receipt.json')
        self.assertEqual('TIMED_OUT', receipt['status']['processState'])
        self.assertEqual('NOT_RUN', receipt['status']['artifactVerification'])
        self.assertEqual('NOT_INTERPRETED', receipt['status']['researchDisposition'])
        self.assertIsNone(receipt['verificationSha256'])

    def test_packet_evidence_requires_a_receipt_and_exact_executed_manifest(self):
        attempt = preparation.freeze(self.draft('position-features'))
        original = dict(manifest={'researchRunIdentity': 'original-evidence-id'}, manifestSha256='a' * 64)
        with self.assertRaises(FileNotFoundError):
            inspection.verified_execution(attempt, original)
        self.submitted(attempt)
        with self.synthetic_command(attempt, 'print("done")'):
            self.assertEqual(0, execution.run_attempt(attempt))
        inspection.verified_execution(attempt, original)
        replacement = dict(manifest={'researchRunIdentity': 'separate-valid-run'}, manifestSha256='b' * 64)
        with self.assertRaisesRegex(storage.Refusal, 'not the evidence verified'):
            inspection.verified_execution(attempt, replacement)
        storage.atomic_json(attempt / 'verification.json', replacement)
        with self.assertRaisesRegex(storage.Refusal, 'verification changed'):
            inspection.verified_execution(attempt, replacement)

    def test_fork_of_attempt_uses_frozen_inputs_and_records_parent_hash(self):
        draft = self.draft()
        attempt = preparation.freeze(draft)
        (draft / 'plan.json').write_text('{"new":"later"}')
        result = drafts.fork_experiment(attempt, 'forked', 'Different control')
        fork = Path(result['directory'])
        self.assertEqual(storage.read_json(attempt / 'plan.json'), storage.read_json(fork / 'plan.json'))
        lineage = storage.read_json(fork / 'experiment.json')['lineage']
        self.assertEqual(storage.digest(attempt / 'request.json'), lineage['requestSha256'])
        self.assertTrue(drafts.diff_experiments(draft, fork)['changes'])
        self.assertFalse((fork / 'attempts').exists())

    def test_private_routes_refuse_checkout_and_symlink_before_writes(self):
        fake_checkout = storage.private_work() / 'checkout'
        fake_checkout.mkdir(parents=True)
        (fake_checkout / '.git').write_text('gitdir: somewhere')
        with self.assertRaisesRegex(storage.Refusal, 'source checkout'):
            storage.private_path(fake_checkout / 'output')
        link = storage.private_work() / 'linked'
        link.symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(storage.Refusal, 'Symbolic-link'):
            storage.private_path(link / 'output')

    def test_diff_includes_changed_deck_from_frozen_parent(self):
        draft = self.draft()
        attempt = preparation.freeze(draft)
        fork = Path(drafts.fork_experiment(attempt, 'deck-fork', 'Change declared deck')['directory'])
        (fork / 'deck.json').write_text('{"deck":"changed population"}')
        changes = drafts.diff_experiments(attempt, fork)['changes']
        self.assertTrue(any(row['pointer'] == '/deck/content/deck' and row['after'] == 'changed population' for row in changes))
        self.assertTrue(any(row['pointer'] == '/deck/sha256' for row in changes))
        self.assertEqual('synthetic', storage.read_json(attempt / 'deck.json')['deck'])

    def test_doctor_checks_deck_and_actual_build_authority(self):
        draft = self.draft()
        self.assertTrue(preparation.doctor(draft)['readyToFreeze'])
        self.assertTrue(any(call[0] == 'build-verify' for call in self.calls))
        (draft / 'deck.json').unlink()
        self.assertFalse(preparation.doctor(draft)['readyToFreeze'])
        (draft / 'deck.json').write_text('{}')
        def incompatible(command, *args, **kwargs):
            if command[0] == 'build-verify':
                raise storage.Refusal('Build source differs from the clean execution source')
            return self.fake_native(command, *args, **kwargs)
        with patch.object(preparation, 'native', side_effect=incompatible):
            result = preparation.doctor(draft)
        self.assertFalse(result['readyToFreeze'])
        self.assertTrue(any('Build source differs' in problem for problem in result['problems']))
        self.assertFalse((draft / 'attempts').exists())
        self.assertFalse(list(storage.private_work().glob('.doctor-build-*')))

    def test_json_duplicate_setting_and_runtime_application_override_refuse(self):
        self.plan.write_text('{"threads":2,"threads":8}')
        with self.assertRaisesRegex(storage.Refusal, 'Duplicate'):
            storage.read_json(self.plan)
        with self.assertRaisesRegex(storage.Refusal, 'overrides'):
            native.runtime(self.build, {'jvmArgs': ['-cp', 'other.jar']})

    def test_bounded_logs_preserve_failure_text_without_retry(self):
        attempt = preparation.freeze(self.draft())
        (attempt / 'run.log').write_text('old\nreconstruction refused\nstopped\n')
        self.assertEqual('reconstruction refused\nstopped\n', monitoring.read_log(attempt, lines=2)['lines'])
        self.assertFalse((attempt / 'submitted.json').exists())

    def test_cli_frozen_plan_and_json_refusal_use_frozen_bindings(self):
        draft = self.draft()
        output = io.StringIO()
        with redirect_stdout(output):
            self.assertEqual(0, cli.main(['freeze', str(draft), '--json']))
        attempt = Path(json.loads(output.getvalue())['attempt'])
        (draft / 'plan.json').write_text('{"baseSeed":999}')
        output = io.StringIO()
        with redirect_stdout(output):
            self.assertEqual(0, cli.main(['plan', str(attempt), '--json']))
        result = json.loads(output.getvalue())
        self.assertEqual(7, result['native']['effectivePlan']['baseSeed'])
        self.assertEqual(str(attempt / 'plan.json'), result['planPath'])
        (attempt / 'plan.json').write_text('{"baseSeed":999}')
        output = io.StringIO()
        with redirect_stdout(output):
            self.assertEqual(2, cli.main(['plan', str(attempt), '--json']))
        refusal = json.loads(output.getvalue())
        self.assertEqual('REFUSED', refusal['status'])
        self.assertIn('Frozen input changed', refusal['error'])

    def test_cli_native_preview_preserves_arguments_and_never_launches(self):
        output = io.StringIO()
        arguments = ['--suite', 'campaign-data-use', '--profile', 'path with spaces.json',
                     '--output', 'literal; $(not-a-shell-command)']
        with patch('subprocess.run') as process, redirect_stdout(output):
            self.assertEqual(0, cli.main(['native', '--json', '--build', str(self.build), '--', *arguments]))
        process.assert_not_called()
        preview = json.loads(output.getvalue())
        self.assertFalse(preview['executes'])
        self.assertEqual(arguments, preview['argv'][-len(arguments):])
        self.assertEqual('org.mtgallium.evaluation.searchteacher.SearchTeacherEvaluationKt',
                         preview['argv'][-len(arguments) - 1])


if __name__ == '__main__':
    unittest.main()
