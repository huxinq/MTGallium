"""Synthetic controls for the narrow frozen identity source-digest adapter."""

from contextlib import redirect_stdout, redirect_stderr
import hashlib
from io import StringIO
from pathlib import Path
import sys
import unittest
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import FrozenSourceDigests as digests


class FrozenSourceDigestsTest(unittest.TestCase):
    def invoke(self, parent, *options, mutate=None):
        output = StringIO()
        errors = StringIO()
        calls = []

        def fake_git(*args):
            calls.append(args)
            if args[:2] == ("cat-file", "-t"):
                return b"commit\n"
            if args[:2] == ("merge-base", "--is-ancestor"):
                return b""
            if args[0] == "show":
                relative = args[1].split(":", 1)[1]
                if parent == digests.POST_IDENTITY_PARENT and relative == digests.RETIRED_BELIEF_PREPARATION:
                    raise AssertionError("retired source was read from POST")
                value = ("synthetic:" + relative).encode()
                return mutate(value) if mutate else value
            raise AssertionError(args)

        with patch.object(sys, "argv", ["FrozenSourceDigests.py", parent, *options]), \
                patch.object(digests, "git", side_effect=fake_git), \
                redirect_stdout(output), redirect_stderr(errors):
            try:
                digests.main()
            except SystemExit as error:
                return error.code, output.getvalue(), errors.getvalue(), calls
        return 0, output.getvalue(), errors.getvalue(), calls

    def test_pre_default_still_emits_both_groups_and_retired_source(self):
        code, output, _, calls = self.invoke(digests.ROOT_MAIN)
        self.assertEqual(0, code)
        self.assertIn("MTG_GOLDEN_BEHAVIOR_SOURCE_DIGEST=", output)
        self.assertIn("MTG_GOLDEN_IDENTITY_SOURCE_DIGEST=", output)
        self.assertIn(("show", f"{digests.ROOT_MAIN}:{digests.RETIRED_BELIEF_PREPARATION}"), calls)

    def test_post_identity_digest_uses_exact_selected_committed_bytes(self):
        parent = digests.POST_IDENTITY_PARENT
        code, output, _, calls = self.invoke(parent, "--group", "identity")
        self.assertEqual(0, code)
        selected = [path for path in digests.SOURCES["identity"] if path != digests.RETIRED_BELIEF_PREPARATION]
        expected = hashlib.sha256()
        for path in sorted(selected):
            expected.update(path.encode() + b"\0" + ("synthetic:" + path).encode())
        self.assertIn(f"MTG_GOLDEN_IDENTITY_SOURCE_DIGEST={expected.hexdigest()}\n", output)
        self.assertNotIn("MTG_GOLDEN_BEHAVIOR_SOURCE_DIGEST", output)
        self.assertEqual(len(selected), sum(call[0] == "show" for call in calls))
        self.assertIn("BeliefTracker.kt", " ".join(path for path in selected))

    def test_changed_source_bytes_change_digest(self):
        parent = digests.POST_IDENTITY_PARENT
        _, original, _, _ = self.invoke(parent, "--group", "identity")
        _, changed, _, _ = self.invoke(parent, "--group", "identity", mutate=lambda value: value + b"x")
        self.assertNotEqual(original, changed)

    def test_post_behavior_and_implicit_all_are_rejected_before_git(self):
        for options in (("--group", "behavior"), ()):
            with self.subTest(options=options):
                code, output, error, calls = self.invoke(digests.POST_IDENTITY_PARENT, *options)
                self.assertNotEqual(0, code)
                self.assertEqual("", output)
                self.assertIn("POST requires explicit --group identity", error)
                self.assertEqual([], calls)

    def test_unknown_parent_and_group_are_rejected_before_git(self):
        for parent, options in (("b" * 40, ("--group", "identity")),
                                (digests.ROOT_MAIN, ("--group", "unknown")),
                                ("short", ("--group", "identity"))):
            with self.subTest(parent=parent, options=options):
                code, output, _, calls = self.invoke(parent, *options)
                self.assertNotEqual(0, code)
                self.assertEqual("", output)
                self.assertEqual([], calls)


if __name__ == "__main__":
    unittest.main()
