"""Current source and docs use the vocabulary list; persisted strings keep their bytes."""
from pathlib import Path
import os
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
LITERAL = r'''"(?:[^"\\\n$]|\\.)*"|'(?:[^'\\\n]|\\.)*' '''.strip()
SERIAL_NAME = re.compile(r'@(?:kotlinx\.serialization\.)?SerialName\(\s*(' + LITERAL + r')\s*\)')
PERSISTED = re.compile(r'(' + LITERAL + r')(?=[ \t)\],]*[ \t]*(?://|#) persisted:)')


def current_text(source):
    source = SERIAL_NAME.sub('@SerialName()', source)
    return PERSISTED.sub('""', source)


class RetiredNamesTest(unittest.TestCase):
    def test_current_names(self):
        names = (Path(__file__).with_name('retired-names.txt')).read_text().splitlines()
        retired = re.compile(r'\b(?:' + '|'.join(map(re.escape, names)) + r')\b')
        self.assertEqual('Old "" // persisted: wire', current_text('Old "Old" // persisted: wire'))
        self.assertEqual('@SerialName() Old', current_text('@SerialName("Old") Old'))
        failures = []
        paths = []
        for base, directories, files in os.walk(ROOT):
            directories[:] = [d for d in directories if not d.startswith('.') and
                              d not in {'build', 'node_modules', 'third_party', '__pycache__'}]
            paths.extend(str((Path(base) / f).relative_to(ROOT)) for f in files)
        for relative in paths:
            path = ROOT / relative
            if path.suffix not in {'.kt', '.py', '.md', '.tex'} or not path.is_file():
                continue
            source = path.read_text()
            if relative == 'docs/terminology.md':
                source = source.split('## Former names', 1)[0]
            for line, text in enumerate(current_text(source).splitlines(), 1):
                if match := retired.search(text):
                    failures.append(f'{relative}:{line}: {match.group()}')
        self.assertEqual([], failures, '\n'.join(failures))
