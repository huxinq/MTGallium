"""Human rendering of command results; JSON retains the complete structured result."""
import json
from .storage import json_bytes, require


def render(command, value):
    if command == 'catalog':
        print('Workflow                          Gate                 Purpose')
        for row in value['capabilities']:
            print(f'{row["kind"]:33} {row["gate"]:20} {row["purpose"]}')
        print('\nSpecialized native routes: ' + ', '.join(row['suite'] for row in value['nativeRoutes']))
        print('See docs/research-workbench.md. Use --json for capability details; --all --build PATH for every suite.')
        if 'native' in value:
            print('\nAll native suites:\n' + '\n'.join(row['id'] for row in value['native']['suites']))
    elif command == 'doctor':
        print('Ready to freeze.' if value['readyToFreeze'] else 'Preparation needed:')
        for check in value['checks']:
            print(f'  {"OK" if check["passed"] else "REFUSED"}: {check["check"]}')
        for problem in value['problems']:
            print('  - ' + problem)
        print('Gate: ' + value['gate'] + '\n' + value['next'])
    elif command == 'logs':
        print(value['path'] + '\n' + value['lines'], end='')
    elif command == 'find':
        for row in value['entries']:
            print(row['directory'])
            for document in row.get('documents', []):
                declared = document.get('declared', {})
                description = declared.get('researchRunIdentity') or declared.get('name') or document['name']
                question = declared.get('design', {}).get('question', '')
                print('  unverified: ' + str(description) + (' — ' + question[:180] if question else ''))
        for error in value['errors']:
            print('ERROR: ' + json.dumps(error, ensure_ascii=False))
        if value.get('truncated'):
            print('More entries exist; narrow the query or increase --limit.')
    elif command == 'status':
        entries = value.get('attempts', [value])
        if not entries:
            print('No frozen attempts. ' + value['next'])
        for entry in entries:
            recorded = entry['operational']
            print(entry['attempt'])
            print('  Process: ' + recorded.get('processState', 'UNKNOWN'))
            print('  Artifact verification: ' + recorded.get('artifactVerification', 'NOT_RUN') + ' (recorded; use verify to recheck)')
            print('  Research disposition: ' + recorded.get('researchDisposition', 'NOT_INTERPRETED'))
            if 'serviceObservation' in entry:
                print('  Current service: ' + json.dumps(entry['serviceObservation']))
            if 'elapsedSeconds' in recorded:
                print(f'  Elapsed: {recorded["elapsedSeconds"]:.2f} seconds')
            if 'error' in recorded:
                print('  Error: ' + recorded['error'])
            if 'warning' in entry:
                print('  ' + entry['warning'])
            print('  Output: ' + entry['outputDirectory'])
    elif command in ('verify', 'inspect'):
        manifest = value.get('manifest', value.get('declared', {}))
        artifacts = value.get('artifacts', manifest.get('artifacts', []))
        print('Verified artifact bytes: ' + manifest['researchRunIdentity'])
        print('Manifest SHA-256: ' + value['manifestSha256'])
        print(f'{len(artifacts)} registered artifacts; {sum(row["bytes"] for row in artifacts):,} bytes.')
        if command == 'inspect':
            for row in artifacts[:50]:
                print(f'  {row["bytes"]:>12,}  {row["relativePath"]}')
            if len(artifacts) > 50:
                print('  More artifacts omitted from this display; --json retains the complete inventory.')
        print('Scientific validity and interpretation require the appropriate native report/population checks.')
    elif command == 'schema':
        definitions = value['definitions']
        requested = value.get('selectedType')
        if requested:
            selected = [row for row in definitions.values() if row['serialName'] == requested or row['serialName'].endswith('.' + requested)]
            require(len(selected) == 1, 'Type is missing or ambiguous. Use --json to inspect exact serial names.')
            definition = selected[0]
        else:
            definition = definitions[value['root']['ref']]
        print(definition['serialName'] + ' (native type shape; constructor constraints still apply)')
        for field in definition.get('fields', []):
            flags = ('optional' if field['optional'] else 'required') + (', nullable' if field['type']['nullable'] else '')
            print(f'  {field["name"]}: {field["type"]["serialName"]} [{flags}]')
        if 'enumValues' in definition:
            print('  Values: ' + ', '.join(definition['enumValues']))
        print('Use --type SERIAL_NAME for a nested type; --json returns all type definitions.')
    elif command == 'plan':
        print('Research design:')
        for key, text in value['design'].items():
            if text:
                print(f'  {key}: {text}')
        print('\nEffective native plan (structural validation):')
        print(json_bytes(value['native']['effectivePlan']).decode(), end='')
        print('Worker authority: ' + value['threadControl'])
        print('Plan SHA-256: ' + value['planSha256'])
    elif command == 'diff':
        if not value['changes']:
            print('No design or plan differences.')
        for change in value['changes']:
            print(change['pointer'])
            print('  before: ' + (json.dumps(change['before'], ensure_ascii=False) if change['beforePresent'] else '<absent>'))
            print('  after:  ' + (json.dumps(change['after'], ensure_ascii=False) if change['afterPresent'] else '<absent>'))
        print(value['interpretation'])
    else:
        print(json_bytes(value).decode(), end='')
