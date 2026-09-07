"""Public-safe refusal checks: no Gradle invocation or source mutation is needed."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

class ResearchBuildPathTest(unittest.TestCase):
    def test_parent_traversal_is_refused_before_any_output(self):
        with tempfile.TemporaryDirectory() as temp:
            private = Path(temp)/'private'
            work = private/'search-teacher'/'work'
            work.mkdir(parents=True)
            escaped = private/'escape'
            requested = work/'..'/'..'/'escape'
            result = subprocess.run([sys.executable, str(Path(__file__).with_name('mtgallium-research-build')), '--output', str(requested)],
                env=dict(os.environ, MTGALLIUM_PRIVATE_EVIDENCE_ROOT=str(private)), capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('Choose a fresh child', result.stderr)
            self.assertFalse(escaped.exists())
            self.assertEqual([], list(work.iterdir()))

if __name__ == '__main__':
    unittest.main()
