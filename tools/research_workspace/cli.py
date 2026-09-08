"""CLI entry point and uniform refusal reporting."""
import subprocess
import sys
from .arguments import parser
from .commands import execute
from .presentation import render
from .storage import json_bytes


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        result = execute(args)
        if args.command == '_run':
            return result
        if args.json:
            print(json_bytes(result).decode(), end='')
        else:
            render(args.command, result)
        return 2 if ((args.command == 'doctor' and not result['readyToFreeze']) or
                     (args.command == 'audit' and result.get('status') == 'UNSUPPORTED')) else 0
    except (ValueError, OSError, KeyError, TypeError, subprocess.SubprocessError) as exc:
        if getattr(args, 'json', False):
            print(json_bytes(dict(status='REFUSED', error=str(exc))).decode(), end='')
        else:
            print(f'Research command refused: {exc}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
