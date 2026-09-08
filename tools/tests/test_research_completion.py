"""Public synthetic witnesses; retained scientific adapters have a separate compatibility lane."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from research_workspace import completion as c, storage


class CompletionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR', '/var/tmp'))
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.run = self.root / 'run'
        self.build = self.root / 'build'
        self.write(self.run / storage.MANIFEST, {'researchRunIdentity': 'original'})
        self.write(self.build / storage.MANIFEST, {'researchRunIdentity': 'verifier'})

    def write(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value))

    def report(self, **extra):
        value = dict(bindings={'protocol': c.FACTUAL_PROTOCOL}, disposition='REFUSED',
                     allocation={'directory': '/must-not-be-read'}, searches=[])
        self.write(self.run / 'report.json', dict(value, **extra))
        self.write(self.run / 'allocation/report.json', {'games': [{}, {}, {}]})

    def test_recovery_counts_pairs_of_files_without_certifying_or_inferring_fit(self):
        self.report()
        for name in ('report.json', storage.MANIFEST):
            self.write(self.run / 'corpus/trajectories/game-0' / name, {})
        self.write(self.run / 'corpus/trajectories/game-1/report.json', {})
        result = c.recorded_research(self.run)
        self.assertEqual('REFUSED', result['disposition'])
        self.assertEqual('NOT_ESTABLISHED', result['fitExecution'])
        self.assertEqual('RECORDED_UNVERIFIED', result['verification'])
        self.assertEqual([0], result['recovery']['candidateCoordinates'])
        self.assertEqual([1, 2], result['recovery']['missingCoordinates'])
        self.assertEqual('NOT_VERIFIED', result['recovery']['eligibility'])
        self.assertFalse(result['recovery']['automaticResume'])

    def test_sealed_corpus_does_not_suggest_admission_continuation(self):
        self.report(corpus={'directory': '/untrusted'})
        self.write(self.run / 'corpus/report.json', {'entries': [{'disposition': 'ADMITTED'}, {'disposition': 'REFUSED'}]})
        result = c.recorded_research(self.run)
        self.assertEqual({'ADMITTED': 1, 'REFUSED': 1}, result['recordedCorpus']['dispositions'])
        self.assertEqual('PARENT_HAS_CORPUS', result['recovery']['eligibility'])

    def test_malformed_oversized_and_symlink_metadata_never_becomes_success(self):
        for extra in ({'bindings': []}, {'searches': None}, {'corpus': {}}):
            with self.subTest(extra=extra):
                self.report(**extra)
                self.write(self.run / 'corpus/report.json', {'entries': [None]})
                # Empty bindings means an unknown protocol; a malformed nonempty binding refuses.
                if extra == {'bindings': []}:
                    self.report(bindings=['bad'])
                self.assertEqual('NOT_RECHECKED', c.recorded_research(self.run)['verification'])
        (self.run / 'report.json').write_bytes(b' ' * (c.METADATA_BYTES + 1))
        self.assertEqual('NOT_RECHECKED', c.recorded_research(self.run)['verification'])
        (self.run / 'report.json').unlink()
        (self.run / 'report.json').symlink_to(self.build / storage.MANIFEST)
        self.assertEqual('NOT_RECHECKED', c.recorded_research(self.run)['verification'])

    def audit_patches(self, protocol=c.FACTUAL_PROTOCOL, gameplay=False):
        def native(command, *args, **kwargs):
            path = Path(command[1])
            return dict(manifest=json.loads((path / storage.MANIFEST).read_text()),
                        manifestSha256=storage.digest(path / storage.MANIFEST))
        def selected(run, name, verifier):
            value = ({'phase': 'SCREEN'} if gameplay else {}) if name == 'plan.json' else {'bindings': {'protocol': protocol}}
            return None, None, None, None, value
        for target, kwargs in (('native', {'side_effect': native}),
                               ('source_state', {'return_value': {'sourceRevision': 'inspector'}}),
                               ('runtime', {'return_value': ['java', '-cp', '/frozen/only.jar', 'Main']})):
            context = patch.object(c, target, **kwargs)
            context.start()
            self.addCleanup(context.stop)
        context = patch.object(c.evidence, '_selected', side_effect=selected)
        context.start()
        self.addCleanup(context.stop)

    def test_unknown_protocol_refuses_without_running_adapter(self):
        self.audit_patches(protocol='future-schema')
        with patch.object(c.subprocess, 'run') as run:
            result = c.audit(self.run, self.build)
        self.assertEqual('UNSUPPORTED', result['status'])
        run.assert_not_called()

    def test_audit_preserves_original_identity_and_selected_classpath_without_writes(self):
        self.audit_patches()
        before = sorted(str(p) for p in self.root.rglob('*'))
        with patch.object(c.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0,
                          '{"researchRunIdentity":"original","fitAttempted":null}', '')) as run:
            result = c.audit(self.run, self.build, deck=self.root / 'deck.json')
        self.assertEqual('original', result['researchRunIdentity'])
        self.assertEqual('inspector', result['inspector']['source']['sourceRevision'])
        self.assertEqual('verifier', result['inspector']['verificationBuild']['identity'])
        self.assertEqual(['java', '-cp', '/frozen/only.jar'], run.call_args.args[0][:3])
        self.assertNotIn('Main', run.call_args.args[0])
        self.assertFalse(result['launchesResearch'])
        self.assertEqual(before, sorted(str(p) for p in self.root.rglob('*')))

    def test_gameplay_selection_does_not_load_large_report(self):
        self.audit_patches(gameplay=True)
        with patch.object(c.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0,
                          '{"researchRunIdentity":"original"}', '')):
            result = c.audit(self.run, self.build)
        self.assertEqual('retained-gameplay', result['protocol'])
        self.assertEqual(1, c.evidence._selected.call_count)

    def test_timeout_incompatible_api_bad_json_and_identity_cannot_pass(self):
        self.audit_patches()
        outcomes = [subprocess.TimeoutExpired('java', 1), subprocess.CompletedProcess([], 1, '', 'missing API'),
                    subprocess.CompletedProcess([], 0, '[]', ''), subprocess.CompletedProcess([], 0, 'not json', ''),
                    subprocess.CompletedProcess([], 0, '{"researchRunIdentity":"later"}', '')]
        for outcome in outcomes:
            with self.subTest(outcome=outcome):
                kwargs = {'side_effect': outcome} if isinstance(outcome, Exception) else {'return_value': outcome}
                with patch.object(c.subprocess, 'run', **kwargs), self.assertRaises(storage.Refusal):
                    c.audit(self.run, self.build, deck=self.root / 'deck.json')

    def test_changed_manifest_and_invalid_resource_bounds_refuse(self):
        self.audit_patches()
        def changed(*args, **kwargs):
            self.write(self.run / storage.MANIFEST, {'researchRunIdentity': 'replacement'})
            return subprocess.CompletedProcess([], 0, '{"researchRunIdentity":"original"}', '')
        with patch.object(c.subprocess, 'run', side_effect=changed), self.assertRaisesRegex(storage.Refusal, 'manifest changed'):
            c.audit(self.run, self.build, deck=self.root / 'deck.json')
        for kwargs in ({'timeout': 0}, {'heap_mib': 9000}):
            with self.assertRaises(storage.Refusal):
                c.audit(self.run, self.build, **kwargs)


if __name__ == '__main__':
    unittest.main()
