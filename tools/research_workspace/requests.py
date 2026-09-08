"""Verify frozen operational bindings and reconstruct allowed native arguments."""
from pathlib import Path
from .capabilities import CAPABILITIES
from .native import runtime
from .source import clean_source
from .storage import REPO, digest, no_links, private_path, private_work, read_json, require


def bound_arguments(attempt, spec):
    """Reconstruct the allowed command from frozen inputs, never trust stored argv."""
    if CAPABILITIES[spec['kind']].gate == 'rehearsal':
        return ['launch', str(attempt / 'preflight.json'), str(attempt / 'preflight'), str(attempt / 'build-reference.json')]
    return ['execute', spec['kind'], str(attempt / 'plan.json'), str(attempt / 'output'),
            str(attempt / 'deck.json') if spec['inputs']['deck'] else '-',
            str(spec['execution']['threads']), str(attempt / 'build-reference.json')]


def verify_request(attempt, current_source=False):
    attempt = private_path(attempt)
    request = read_json(attempt / 'request.json')
    require(request['schemaVersion'] == 1 and request['kind'] in CAPABILITIES, 'Unsupported frozen request.')
    require(request['source']['repository'] == str(REPO), 'Run this attempt with the original source worktree.')
    require(request['privateEvidenceRoot'] == str(private_work().parent.parent), 'Private evidence root differs from the frozen request.')
    for name, expected in request['files'].items():
        require(Path(name).name == name, 'Invalid frozen input name.')
        require(digest(no_links(attempt / name)) == expected, f'Frozen input changed: {name}; fork the experiment instead.')
    spec = read_json(attempt / 'experiment.json')
    require(request['execution'] == spec['execution'] and request['kind'] == spec['kind'], 'Request and frozen experiment disagree.')
    require(request['build'] == read_json(attempt / 'build-reference.json'), 'Frozen build reference disagrees.')
    require(request['gate'] == CAPABILITIES[spec['kind']].gate, 'Frozen launch gate disagrees with workload kind.')
    require(request['targetOutput'] == str(attempt / 'output'), 'Attempt moved: target output binding differs.')
    require(request['nativeArguments'] == bound_arguments(attempt, spec), 'Frozen native arguments differ from their bound inputs.')
    expected_files = {'experiment.json', 'effective-plan.json', 'build-reference.json', 'plan.json'}
    if spec['inputs']['deck']:
        expected_files.add('deck.json')
    if request['gate'] == 'rehearsal':
        expected_files.add('preflight.json')
        preflight = read_json(attempt / 'preflight.json')
        require(preflight['targetOutput'] == request['targetOutput'] and
                preflight['work']['planPath'] == str(attempt / 'plan.json') and
                preflight['work']['deckManifest'] == str(attempt / 'deck.json') and
                preflight['work']['threads'] == request['execution']['threads'], 'Preflight points outside its frozen attempt.')
        expected_type = 'position-screen' if spec['kind'] == 'position-screen' else 'gameplay'
        require(preflight['work']['type'] == expected_type and
                (expected_type != 'gameplay' or preflight['work']['sequential'] == (spec['kind'] == 'sequential')),
                'Preflight workload differs from its declared kind.')
    require(set(request['files']) == expected_files, 'Frozen input inventory differs from the required inputs.')
    require(request['command'] == runtime(request['build']['directory'], request['execution']) + request['nativeArguments'],
            'Frozen runtime command differs from the request.')
    require(request['nativeArguments'][0] in ('launch', 'execute'), 'Unsupported native launch command.')
    if current_source:
        require(clean_source() == request['source'], 'Treatment source changed; use its original clean worktree or freeze a new treatment.')
    return request
