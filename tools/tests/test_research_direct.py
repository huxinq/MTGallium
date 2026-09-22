"""Behavior of the direct research launcher, not a frozen-workflow emulation."""
import contextlib
import gzip
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
import research_workspace as research
from research_workspace import cli


class DirectResearchTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.runtime_file = self.root / 'runtime.json'
        self.paths = {'java': '/jdk with spaces/bin/java', 'classpath': '/classes here:/dependency.jar'}
        self.runtime_file.write_text(json.dumps(self.paths))

    def test_build_uses_gradle_invalidation_not_a_source_cleanliness_protocol(self):
        with patch.object(research, 'RUNTIME', self.runtime_file), patch.object(research.subprocess, 'run') as execute:
            self.assertEqual(research.runtime(), self.paths)
        self.assertEqual(execute.call_count, 1)
        self.assertIn(':research:workbench:researchClasspath', execute.call_args.args[0])
        self.assertTrue(execute.call_args.kwargs['check'])

    def test_no_build_is_an_explicit_reuse_of_compiled_paths(self):
        with patch.object(research, 'RUNTIME', self.runtime_file), patch.object(research.subprocess, 'run') as execute:
            self.assertEqual(research.runtime(build=False), self.paths)
        execute.assert_not_called()

    def test_missing_runtime_explains_how_to_build(self):
        with patch.object(research, 'RUNTIME', self.root / 'missing.json'):
            with self.assertRaisesRegex(FileNotFoundError, 'mtgallium-research build'):
                research.runtime(build=False)

    def test_arbitrary_main_and_arguments_are_not_reconstructed_as_shell_text(self):
        values = ['relative input.json', 'a b;$(literal)', '--flag']
        with patch.object(research, 'runtime', return_value=self.paths), patch.dict(os.environ, {'JAVA_OPTS': ''}):
            command = research.jvm_command(values, main_class='my.experiment.Main', build=False)
        self.assertEqual(command, [self.paths['java'], '-cp', self.paths['classpath'], 'my.experiment.Main', *values])

    def test_resources_are_user_choices_not_a_four_processor_ceiling(self):
        with patch.object(research, 'runtime', return_value=self.paths), \
                patch.dict(os.environ, {'JAVA_OPTS': '-Xmx12g -XX:ActiveProcessorCount=16'}):
            command = research.jvm_command(['fit'], java_options=['-Xss8m'])
        self.assertEqual(command[1:4], ['-Xmx12g', '-XX:ActiveProcessorCount=16', '-Xss8m'])

    def test_native_run_preserves_the_callers_directory_and_reports_dependency_source(self):
        with patch.object(research, 'jvm_command', return_value=['java', 'Main', 'relative.json']), \
                patch.object(research.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)) as execute:
            result = research.run(['relative.json'])
        self.assertEqual(result.returncode, 0)
        self.assertNotIn('cwd', execute.call_args.kwargs)
        self.assertEqual(execute.call_args.kwargs['env']['MTGALLIUM_SOURCE_ROOT'], str(research.REPO))

    def test_plain_and_compressed_data_need_no_producer_or_manifest(self):
        value = {'producer': 'someone else', 'a_new_quantity': [1, 2, 3]}
        path = self.root / 'data.json'
        path.write_text(json.dumps(value))
        link = self.root / 'linked.json'
        link.symlink_to(path)
        self.assertEqual(research.read_data(link), value)
        path = self.root / 'rows.jsonl.gz'
        with gzip.open(path, 'wt') as stream:
            stream.write(json.dumps(value) + '\n\n' + json.dumps({'other': True}) + '\n')
        self.assertEqual(research.read_data(path), [value, {'other': True}])

    def test_cli_propagates_native_failure(self):
        with patch.object(cli, 'run', return_value=subprocess.CompletedProcess([], 17)) as execute:
            self.assertEqual(cli.main(['--no-build', 'predict', 'model', 'data', 'output']), 17)
        self.assertEqual(execute.call_args.args[0], ['predict', 'model', 'data', 'output'])
        self.assertEqual(execute.call_args.kwargs, {'build': False, 'check': False})

    def test_native_commands_have_one_owner_not_a_second_python_registry(self):
        with patch.object(cli, 'run', return_value=subprocess.CompletedProcess([], 23)) as execute:
            self.assertEqual(cli.main(['--no-build', 'new-native-question', 'input with spaces', '--option']), 23)
        self.assertEqual(execute.call_args.args[0], ['new-native-question', 'input with spaces', '--option'])
        self.assertEqual(execute.call_args.kwargs, {'build': False, 'check': False})

    def test_cli_can_launch_a_main_not_in_a_capability_catalog(self):
        with patch.object(cli, 'run', return_value=subprocess.CompletedProcess([], 0)) as execute:
            self.assertEqual(cli.main(['jvm', 'local.NewQuestionKt', 'data with spaces']), 0)
        self.assertEqual(execute.call_args.args[0], ['data with spaces'])
        self.assertEqual(execute.call_args.kwargs['main_class'], 'local.NewQuestionKt')

    def test_show_does_not_reinterpret_an_artifact_as_an_authorization_request(self):
        path = self.root / 'anything.json'
        value = {'status': 'unfinished', 'unknown-schema': 'fine', 'values': [0, 1]}
        path.write_text(json.dumps(value))
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(cli.main(['show', str(path)]), 0)
        self.assertEqual(json.loads(output.getvalue()), value)


if __name__ == '__main__':
    unittest.main()
