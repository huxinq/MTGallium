"""Editable experiment design, input resolution, ancestry and exact differences."""
from pathlib import Path
import re
import shutil
from .capabilities import CAPABILITIES, DESIGN_FIELDS
from .requests import verify_request
from .storage import (atomic_json, digest, no_links, private_path, private_work,
                      read_json, require, write_new)


def draft_path(path):
    path = no_links(path)
    return path / 'experiment.json' if path.is_dir() else path


def load_draft(path):
    path = draft_path(path)
    spec = read_json(path)
    require(spec.get('schemaVersion') == 1 and spec.get('kind') in CAPABILITIES,
            'Unsupported experiment schema or kind. Use `research catalog`.')
    require(set(spec) <= {'schemaVersion', 'name', 'kind', 'design', 'inputs', 'execution', 'lineage'},
            'Unknown experiment field; refusing a possibly misspelled setting.')
    require(set(spec['inputs']) == {'plan', 'deck', 'build'}, 'inputs requires exactly plan, deck and build.')
    require(set(spec['execution']) <= {'java', 'jvmArgs', 'threads', 'timeoutSeconds', 'smokeBaseSeed',
                                     'smokeSimulations', 'smokeRootLimit', 'smokeRepetitions'},
            'Unknown execution field; refusing a possibly misspelled setting.')
    return path, spec


def input_path(path, value):
    require(isinstance(value, str) and value, 'Required input path is missing.')
    target = Path(value)
    target = target if target.is_absolute() else path.parent / target
    target = no_links(target)
    require(target.is_file(), f'Missing input: {target}')
    return target


def plan_input(path, spec):
    if (path.parent / 'request.json').is_file():
        verify_request(path.parent)
        return path.parent / 'plan.json'
    return input_path(path, spec['inputs']['plan'])


def deck_input(path, spec):
    if not spec['inputs']['deck']:
        return None
    if (path.parent / 'request.json').is_file():
        verify_request(path.parent)
        return path.parent / 'deck.json'
    return input_path(path, spec['inputs']['deck'])


def new_experiment(name, kind, plan, deck, build=None, question='', destination=None, lineage=None):
    require(re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_.-]{0,79}', name), 'Use a short experiment name containing letters, digits, dot, dash or underscore.')
    require(kind in CAPABILITIES, 'Unknown experiment kind.')
    destination = private_path(destination or private_work() / name)
    require(not destination.exists(), f'Experiment already exists: {destination}; use fork to change a frozen experiment.')
    plan = no_links(plan)
    read_json(plan)
    deck = no_links(deck) if deck else None
    if deck:
        read_json(deck)
    destination.mkdir(parents=True)
    shutil.copyfile(plan, destination / 'plan.json')
    if deck:
        shutil.copyfile(deck, destination / 'deck.json')
    spec = dict(schemaVersion=1, name=name, kind=kind,
                design={key: question if key == 'question' else '' for key in DESIGN_FIELDS},
                inputs=dict(plan='plan.json', deck='deck.json' if deck else None,
                            build=str(no_links(build)) if build else None),
                execution=dict(java='java', jvmArgs=['-XX:ActiveProcessorCount=2', '-Xmx4g'], threads=2,
                               timeoutSeconds=None, smokeBaseSeed=None, smokeSimulations=4,
                               smokeRootLimit=1, smokeRepetitions=1), lineage=lineage)
    write_new(destination / 'experiment.json', spec)
    return {'directory': str(destination), 'draft': str(destination / 'experiment.json'),
            'next': f'Edit experiment.json and plan.json, then run: research doctor {destination}'}


def fork_experiment(original, name, reason, destination=None):
    require(reason.strip(), 'Record why this fork changes the experiment.')
    original = no_links(original)
    if (original / 'request.json').exists():
        verify_request(original)
        path, spec = original / 'experiment.json', read_json(original / 'experiment.json')
        plan, deck = original / 'plan.json', original / 'deck.json'
        deck = deck if deck.exists() else None
        parent = dict(attempt=str(original), requestSha256=digest(original / 'request.json'), reason=reason)
    else:
        path, spec = load_draft(original)
        plan = input_path(path, spec['inputs']['plan'])
        deck = input_path(path, spec['inputs']['deck']) if spec['inputs']['deck'] else None
        parent = dict(draft=str(path), draftSha256=digest(path), planSha256=digest(plan), reason=reason)
    result = new_experiment(name, spec['kind'], plan, deck, spec['inputs']['build'],
                            destination=destination, lineage=parent)
    target = Path(result['draft'])
    fork = read_json(target)
    fork['design'], fork['execution'] = spec['design'], spec['execution']
    atomic_json(target, fork)
    result['next'] = 'Edit the new draft/plan, use diff to review the changed treatment, then doctor/preflight.'
    return result


def design_problems(spec):
    capability = CAPABILITIES[spec['kind']]
    required_design = capability.design_fields
    problems = [f'design.{key}: state the intended meaning (use "not applicable" with a reason when appropriate)'
                for key in required_design if not isinstance(spec.get('design', {}).get(key), str)
                or not spec['design'][key].strip()]
    execution = spec['execution']
    for key in ('threads', 'timeoutSeconds', 'smokeSimulations', 'smokeRootLimit', 'smokeRepetitions'):
        if type(execution.get(key)) is not int or execution[key] <= 0:
            problems.append(f'execution.{key}: must be an explicit positive integer')
    if capability.requires_smoke_seed and type(execution.get('smokeBaseSeed')) is not int:
        problems.append('execution.smokeBaseSeed: declare a separate technical smoke seed')
    if not spec['inputs']['build']:
        problems.append('inputs.build: point to a verified frozen build directory')
    if capability.requires_deck and not spec['inputs']['deck']:
        problems.append('inputs.deck: this workload requires an explicit deck manifest')
    return problems


def diff_experiments(left, right):
    def content(path):
        path, spec = load_draft(path)
        plan = plan_input(path, spec)
        deck = deck_input(path, spec)
        return dict(experiment=spec, plan=read_json(plan), planSha256=digest(plan),
                    deck=None if deck is None else dict(content=read_json(deck), sha256=digest(deck)))
    changes = []
    def walk(a, b, pointer=''):
        if type(a) is dict and type(b) is dict:
            for key in sorted(a.keys() | b.keys()):
                route = pointer + '/' + key.replace('~', '~0').replace('/', '~1')
                if key not in a or key not in b:
                    changes.append(dict(pointer=route, beforePresent=key in a, afterPresent=key in b,
                                        before=a.get(key), after=b.get(key)))
                else:
                    walk(a[key], b[key], route)
        elif type(a) != type(b) or a != b:
            changes.append(dict(pointer=pointer, beforePresent=True, afterPresent=True, before=a, after=b))
    walk(content(left), content(right))
    return dict(changes=changes, interpretation='Configuration and design differences; no claim of semantic equivalence or causal isolation.')
