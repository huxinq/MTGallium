"""Synthetic fixtures for the read-only source outline."""
from pathlib import Path
import runpy
import subprocess
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[1]
OUTLINE = runpy.run_path(str(TOOLS / "outline"))["outline"]


class NavigationTest(unittest.TestCase):
    def source(self, name, text):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def test_python_ast_ranges_and_docstring(self):
        path = self.source("fixture.py", 'class A:\n    """An A."""\n    async def run(self):\n        return "def fake():"\n')
        self.assertEqual(OUTLINE(path), [
            dict(kind="class", name="A", start=1, end=4, doc="An A."),
            dict(kind="fun", name="run", start=3, end=4, doc="")])

    def test_kotlin_masks_strings_and_comments_and_reads_docs(self):
        path = self.source("fixture.kt", '/** Container. */\nclass Box {\n  /** Work. */\n  fun run(x: Int = 1) {\n    val text = "} class Fake {"\n    /* } */\n  }\n}\nfun answer() = 42\nval raw = """\nclass Fake { }\n"""\n')
        rows = OUTLINE(path)
        self.assertEqual([(r["name"], r["start"], r["end"]) for r in rows],
                         [("Box", 2, 8), ("run", 4, 7), ("text", 5, 5), ("answer", 9, 9), ("raw", 10, 10)])
        self.assertEqual([r["doc"] for r in rows[:2]], ["Container.", "Work."])

    def test_javascript_function_and_template_string(self):
        path = self.source("fixture.mjs", 'export function run() {\n  return `}${1}`;\n}\n')
        self.assertEqual([(r["name"], r["start"], r["end"]) for r in OUTLINE(path)], [("run", 1, 3)])

    def test_map_lists_tracked_types(self):
        root = self.source("tools/outline", (TOOLS / "outline").read_text()).parents[1]
        (root / "Box.kt").write_text("class Box\nclass Other\n")
        subprocess.run(["git", "init", "-q", str(root)], check=True)
        subprocess.run(["git", "-C", str(root), "add", "."], check=True)
        output = subprocess.check_output(["python3", str(root / "tools/outline"), "--map", "--name", "Box"], text=True)
        self.assertEqual(output, "Box.kt:1-1 class Box\n")


if __name__ == "__main__":
    unittest.main()
