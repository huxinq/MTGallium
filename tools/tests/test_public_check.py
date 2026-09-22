"""Keep the public verification recipes aligned with first-party modules."""

from pathlib import Path
import re
import shlex
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[2]


def recipe_tasks(recipe):
    result = subprocess.run(
        ['just', '--justfile', str(ROOT / 'justfile'), '--dry-run', recipe],
        cwd=ROOT, capture_output=True, text=True, check=True, timeout=10,
    )
    return {
        token for token in shlex.split(result.stdout + result.stderr)
        if token.startswith(':')
    }


class PublicCheckTest(unittest.TestCase):
    def test_check_covers_each_first_party_module(self):
        modules = re.findall(
            r'^include\("([^"]+)"\)',
            (ROOT / 'settings.gradle.kts').read_text(), re.MULTILINE,
        )
        self.assertTrue(modules)
        expected = {
            module + ':test'
            for module in modules
        }
        self.assertEqual(expected, recipe_tasks('check'))

    def test_policy_check_omits_argentum_evaluation(self):
        self.assertEqual(
            recipe_tasks('check') - {':evaluation:argentum:test'},
            recipe_tasks('policy-check'),
        )


if __name__ == '__main__':
    unittest.main()
