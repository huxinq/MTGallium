"""Public CLI argument shapes; parsing does not perform research work."""
import argparse
import os
from pathlib import Path
from .capabilities import CAPABILITIES


def parser():
    root = argparse.ArgumentParser(
        prog='research', description='Design, freeze, run and inspect MTGallium experiments.',
        epilog='Start: catalog → new → edit → doctor → preflight → launch → status → inspect. '
               'Use --json after any command for scripts. All generated records stay private.')
    commands = root.add_subparsers(dest='command', required=True, metavar='COMMAND')
    def command(name, help):
        item = commands.add_parser(name, help=help, description=help)
        item.add_argument('--json', action='store_true', help='Print structured JSON')
        return item
    def build_option(item):
        item.add_argument('--build', type=Path, default=os.environ.get('MTGALLIUM_RESEARCH_BUILD'),
                          help='Frozen build directory (or MTGALLIUM_RESEARCH_BUILD)')
    p = command('catalog', 'Choose a workflow and see exactly which gate it supplies')
    p.add_argument('--all', action='store_true', help='Also list every native suite from the frozen runtime')
    build_option(p)
    p = command('schema', 'Show the actual native plan fields, types and optionality for experiment design')
    p.add_argument('kind', choices=[*CAPABILITIES, 'campaign-data-use'])
    p.add_argument('--type', help='Show one named nested type (full name or suffix) in human output')
    build_option(p)
    p = command('new', 'Create an editable experiment draft from an existing typed plan')
    p.add_argument('name')
    p.add_argument('--kind', choices=CAPABILITIES, required=True)
    p.add_argument('--plan', type=Path, required=True)
    p.add_argument('--deck', type=Path)
    p.add_argument('--question', default='')
    p.add_argument('--output', type=Path)
    build_option(p)
    p = command('fork', 'Copy a draft or frozen attempt with explicit ancestry; retain the original')
    p.add_argument('source', type=Path)
    p.add_argument('name')
    p.add_argument('--reason', required=True)
    p.add_argument('--output', type=Path)
    p = command('diff', 'Show exact design and plan differences between two experiments')
    p.add_argument('left', type=Path)
    p.add_argument('right', type=Path)
    for name, help in [('plan', 'Decode the typed plan and show effective defaults alongside the research design'),
                       ('doctor', 'List missing design, input, source and configuration prerequisites without running research'),
                       ('freeze', 'Capture immutable attempt inputs and verify the build without collecting samples'),
                       ('preflight', 'Freeze a draft and rehearse, or verify/rehearse an existing unlaunched attempt'),
                       ('status', 'Read operational status and current durable service state'),
                       ('diagnose', 'Inspect failure records and log messages without rerunning work')]:
        p = command(name, help)
        p.add_argument('path', type=Path)
        if name == 'plan':
            build_option(p)
    p = command('launch', 'Launch a frozen attempt exactly once; durable systemd execution is the default')
    p.add_argument('attempt', type=Path)
    p.add_argument('--foreground', action='store_true', help='Bounded interactive execution instead of a durable user service')
    p = command('logs', 'Read the last lines of an attempt log')
    p.add_argument('attempt', type=Path)
    p.add_argument('--preflight', action='store_true')
    p.add_argument('--lines', type=int, default=60)
    p = command('find', 'Retrieve experiments and native runs by metadata without reading outcomes')
    p.add_argument('query', nargs='?', default='')
    p.add_argument('--root', type=Path)
    p.add_argument('--limit', type=int, default=50)
    for name, help in [('verify', 'Verify every registered artifact through the native manifest authority'),
                       ('inspect', 'Authenticate and list artifacts without opening outcomes or canonical replay'),
                       ('describe', 'Authenticate a JSON artifact and show its top-level keys without values')]:
        p = command(name, help)
        p.add_argument('run', type=Path)
        p.add_argument('--identity', help='Require an exact retained research-run identity')
        build_option(p)
        if name == 'describe':
            p.add_argument('artifact')
    p = command('extract', 'Export an exact registered JSON field and its provenance; no filtering or new feature definition')
    p.add_argument('run', type=Path)
    p.add_argument('artifact')
    p.add_argument('--pointer', default='', help='RFC 6901 JSON pointer; empty selects the entire artifact')
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--purpose', required=True, help='Record the intended use of this derivative; this is not a campaign population registration')
    p.add_argument('--identity')
    build_option(p)
    p = command('packet', 'Create a private review packet with verified evidence links and explicit interpretation questions')
    p.add_argument('attempt', type=Path)
    p.add_argument('--output', type=Path, required=True)
    p = command('build', 'Force and retain a clean-source frozen runtime with the existing build authority')
    p.add_argument('--output', type=Path, required=True)
    p = command('native', 'Print an argument-safe native CLI invocation for a documented specialized command')
    build_option(p)
    p.add_argument('arguments', nargs=argparse.REMAINDER,
                   help='After --, pass native CLI flags. This command prints argv and does not launch.')
    p = commands.add_parser('_run')
    p.add_argument('attempt', type=Path)
    return root
