"""The workbench delegates process transport without reconstructing shell text."""
import contextlib
import io
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from research_workspace import cli


class DurableCliTests(unittest.TestCase):
    def test_exact_passthrough_and_exit(self):
        argv = ['launch', '--name', 'test', '--json', '--', '/bin/echo', 'a b;$(literal)']
        with patch.object(cli.shutil, 'which', return_value='/node'), \
                patch.object(cli.subprocess, 'call', return_value=23) as run:
            self.assertEqual(cli.main(['durable', *argv]), 23)
        self.assertEqual(run.call_args.args[0], [
            '/node', str(cli.REPO / 'tools/durable-run/durable-run.mjs'), *argv])

    def test_help_reaches_runner_and_missing_node_is_actionable(self):
        with patch.object(cli.shutil, 'which', return_value='/node'), \
                patch.object(cli.subprocess, 'call', return_value=0) as run:
            self.assertEqual(cli.main(['durable', '--help']), 0)
            self.assertEqual(run.call_args.args[0][-1], '--help')
        error = io.StringIO()
        with patch.object(cli.shutil, 'which', return_value=None), contextlib.redirect_stderr(error):
            self.assertEqual(cli.main(['durable', 'list']), 2)
        self.assertIn('Node.js 18+', error.getvalue())
