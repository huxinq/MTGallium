"""Build research code, run experiments, and inspect JSON results."""
import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path
from . import REPO, read_data, run, runtime


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--no-build', action='store_true',
                        help='Use the last compiled runtime without checking for source changes')
    parser.add_argument('command', help='build, show, durable, jvm, or a native command; use help for native commands')
    parser.add_argument('arguments', nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    try:
        if args.command == 'durable':
            node = shutil.which('node')
            if node is None:
                print('Node.js 18+ is required for the durable process runner.', file=sys.stderr)
                return 2
            return subprocess.call([node, str(REPO / 'tools/durable-run/durable-run.mjs'), *args.arguments])
        if args.command == 'build':
            if args.arguments:
                parser.error('build takes no arguments; use Gradle directly for additional build tasks')
            print(json.dumps(runtime(), indent=2))
            return 0
        if args.command == 'show':
            if len(args.arguments) != 1:
                parser.error('show requires one JSON/JSONL file or directory')
            path = Path(args.arguments[0])
            if path.is_dir():
                print('\n'.join(str(item) for item in sorted(path.iterdir())))
            else:
                print(json.dumps(read_data(path), indent=2, ensure_ascii=False, allow_nan=False))
            return 0
        if args.command == 'jvm':
            if not args.arguments:
                parser.error('jvm requires a main class followed by its arguments')
            result = run(args.arguments[1:], main_class=args.arguments[0], build=not args.no_build, check=False)
        else:
            result = run([args.command, *args.arguments], build=not args.no_build, check=False)
        return result.returncode
    except subprocess.CalledProcessError as error:
        return error.returncode
    except (OSError, ValueError) as error:
        print(str(error), file=sys.stderr)
        return 2
