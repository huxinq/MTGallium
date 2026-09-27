"""Synthetic fixtures for read-only source navigation commands."""
import json
from pathlib import Path
import runpy
import subprocess
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[1]
OUTLINE = runpy.run_path(str(TOOLS / "outline"))["outline"]


class NavigationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def source(self, name, text):
        path = self.root / name
        path.write_text(text)
        return path

    def test_python_ast_ranges_and_docstring(self):
        path = self.source("fixture.py", 'class A:\n    """An A."""\n    async def run(self):\n        return "def fake():"\n')
        self.assertEqual(OUTLINE(path), [
            dict(kind="class", name="A", start=1, end=4, doc="An A."),
            dict(kind="fun", name="run", start=3, end=4, doc="")])

    def test_kotlin_nested_braces_comments_and_default_argument(self):
        path = self.source("fixture.kt", '/** Container. */\nclass Box {\n  /** Work. */\n  fun run(x: Int = 1) {\n    val text = "} class Fake {"\n    /* nested /* } */ comment */\n  }\n}\n')
        rows = OUTLINE(path)
        self.assertEqual([(r["name"], r["start"], r["end"]) for r in rows],
                         [("Box", 2, 8), ("run", 4, 7), ("text", 5, 5)])
        self.assertEqual([r["doc"] for r in rows], ["Container.", "Work.", ""])

    def test_kotlin_triple_string_and_expression_body(self):
        path = self.source("fixture.kt", 'fun answer() = 42\nval raw = """\nclass Fake { }\n"""\nfun last() { }\n')
        self.assertEqual([r["name"] for r in OUTLINE(path)], ["answer", "raw", "last"])
        self.assertEqual(OUTLINE(path)[0]["end"], 1)

    def test_javascript_function_and_template_string(self):
        path = self.source("fixture.mjs", '/** Run it. */\nexport async function run() {\n  const text = `} function fake() {`;\n  return text;\n}\n')
        rows = OUTLINE(path)
        self.assertEqual((rows[0]["name"], rows[0]["end"], rows[0]["doc"]), ("run", 5, "Run it."))
        self.assertEqual([r["name"] for r in rows], ["run", "text"])

    def test_nested_kotlin_template_quotes_do_not_expose_braces(self):
        path = self.source("fixture.kt", 'fun run() {\n val s = "${call("}")}"\n val nested = "${call("${other("}")}")}"\n}\n')
        self.assertEqual(OUTLINE(path)[0]["end"], 4)

    def test_kotlin_constructor_properties_do_not_bound_class(self):
        path = self.source("fixture.kt", "data class Box(\n val value:Int,\n) {\n fun get()=value\n}\n")
        self.assertEqual((OUTLINE(path)[0]["start"], OUTLINE(path)[0]["end"]), (1, 5))

    def test_bodyless_constructor_does_not_capture_quoted_test_body(self):
        path = self.source("fixture.kt", "data class Scenario(\n val id: String,\n)\n@Test\nfun `does a thing`() {\n println(1)\n}\n")
        rows = OUTLINE(path)
        self.assertEqual((rows[0]["start"], rows[0]["end"]), (1, 3))
        self.assertEqual((rows[-1]["name"], rows[-1]["start"], rows[-1]["end"]), ("`does a thing`", 4, 7))
        path.write_text("class Box {\n fun `brace } test`() {\n println(1)\n }\n}\n")
        rows = OUTLINE(path)
        self.assertEqual((rows[0]["end"], rows[1]["name"], rows[1]["end"]), (5, "`brace } test`", 4))
        path.write_text("data class Scenario(\n val id: String,\n)\n@Test fun `does a thing`() {\n println(1)\n}\n")
        rows = OUTLINE(path)
        self.assertEqual((rows[0]["end"], rows[-1]["name"], rows[-1]["start"], rows[-1]["end"]),
                         (3, "`does a thing`", 4, 6))

    def test_javascript_regex_braces_are_not_body_delimiters(self):
        path = self.source("fixture.js", "function run(){\n const pattern=/}/;\n return pattern;\n}\n")
        self.assertEqual(OUTLINE(path)[0]["end"], 4)
        path.write_text("function run(){\n const pattern=/[}\\/]/g;\n return pattern;\n}\n")
        self.assertEqual(OUTLINE(path)[0]["end"], 4)

    def test_ambiguous_javascript_slash_fails_explicitly(self):
        path = self.source("fixture.js", "function run() { return x / y; }\n")
        with self.assertRaisesRegex(ValueError, "ambiguous JavaScript"):
            OUTLINE(path)

    def test_doc_does_not_cross_intervening_declaration_or_comment(self):
        path = self.source("fixture.kt", "/** First doc. */\nclass First {}\n/* Ordinary comment. */\nclass Second {}\n")
        self.assertEqual([r["doc"] for r in OUTLINE(path)], ["First doc.", ""])

    def test_cli_json_and_invalid_python(self):
        path = self.source("fixture.py", "def go():\n    pass\n")
        run = subprocess.run([str(TOOLS / "outline"), "--json", str(path)], capture_output=True, text=True)
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(json.loads(run.stdout)[0]["name"], "go")
        path.write_text("def (bad")
        run = subprocess.run([str(TOOLS / "outline"), str(path)], capture_output=True, text=True)
        self.assertEqual(run.returncode, 2)
        self.assertEqual(run.stdout, "")

    def test_code_map_rejects_symlinked_parent_outside_root(self):
        (self.root / "package").mkdir()
        self.source("package/a.py", "def tracked():\n    pass\n")
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "add", "package/a.py"], check=True)
        with tempfile.TemporaryDirectory() as outside:
            moved = Path(outside) / "package"
            (self.root / "package").rename(moved)
            (self.root / "package").symlink_to(moved, target_is_directory=True)
            result = subprocess.run([str(TOOLS / "code-map"), "--root", str(self.root)],
                                    capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, "")
        self.assertIn("unsafe symlink/path: package/a.py", result.stderr)

    def test_code_map_language_filter_excludes_unsupported_syntax(self):
        self.source("a.py", "def a():\n    return 1\n")
        self.source("b.js", "function b() { return x / y; }\n")
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "add", "a.py", "b.js"], check=True)
        cmd = [str(TOOLS / "code-map"), "--root", str(self.root), "--json"]
        failed = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(failed.returncode, 2)
        self.assertEqual(failed.stdout, "")
        self.assertIn("b.js:", failed.stderr)
        rows = json.loads(subprocess.check_output(cmd + ["--language", "python"]))
        self.assertEqual([r["name"] for r in rows], ["a"])

    def test_code_map_only_tracked_files_deterministic_and_read_only(self):
        self.source("a.py", "def a():\n    return 1\n")
        self.source("untracked.py", "def hidden():\n    pass\n")
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "add", "a.py"], check=True)
        before = subprocess.check_output(["git", "-C", str(self.root), "status", "--porcelain"])
        cmd = [str(TOOLS / "code-map"), "--root", str(self.root), "--json"]
        first = subprocess.check_output(cmd)
        self.assertEqual(first, subprocess.check_output(cmd))
        self.assertEqual([r["name"] for r in json.loads(first)], ["a"])
        self.assertEqual(before, subprocess.check_output(["git", "-C", str(self.root), "status", "--porcelain"]))


if __name__ == "__main__":
    unittest.main()
