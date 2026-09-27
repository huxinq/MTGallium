#!/usr/bin/env python3
"""Verify recorded actions on the public engine and export Argentum replay frames.

Run on Linux with the research JVM already built. Inputs and outputs are private data.
"""
import argparse
import copy
import gzip
import json
import re
from pathlib import Path

from research_workspace.game import Session


def read_lines(path):
    with gzip.open(path, 'rt') as stream:
        for line in stream:
            yield json.loads(line)


def hand_owner(zone):
    zone_id = zone.get('zoneId', {})
    if isinstance(zone_id, dict) and str(zone_id.get('zoneType', '')).upper() == 'HAND':
        return zone_id.get('ownerId')
    return None


def hindsight(views):
    """Use each owner's own hand projection; all other fields use p0's view."""
    merged = copy.deepcopy(views['p0'])
    own = views['p1']
    merged['cards'].update(own['cards'])
    zones = {json.dumps(z['zoneId'], sort_keys=True): z for z in merged['zones']}
    p1_id = own['players'][1]['playerId']
    for zone in own['zones']:
        if hand_owner(zone) == p1_id:
            zones[json.dumps(zone['zoneId'], sort_keys=True)] = zone
    merged['zones'] = list(zones.values())
    return merged


def snapshot(game_id, view, state, logs):
    state = copy.deepcopy(state)
    state['gameLog'] = logs.copy()
    players = state['players']
    def seat(player):
        return {
            'playerId': player['playerId'], 'playerName': player.get('name', ''),
            'life': player.get('life', 20), 'handSize': player.get('handSize', 0),
            'librarySize': player.get('librarySize', 0), 'battlefield': [],
            'graveyard': [], 'stack': [],
        }
    return {
        'gameSessionId': f'{game_id}-{view}', 'gameState': state,
        'players': [{'playerId': p['playerId']} for p in players],
        'player1Id': players[0]['playerId'], 'player2Id': players[1]['playerId'],
        'player1Name': players[0].get('name', 'Player 0'),
        'player2Name': players[1].get('name', 'Player 1'),
        'player1': seat(players[0]), 'player2': seat(players[1]),
        'currentPhase': state['currentPhase'],
        'activePlayerId': state['activePlayerId'],
        'priorityPlayerId': state['priorityPlayerId'],
        'combat': state.get('combat'), 'decisionStatus': None,
    }


def delta(before, after):
    old, new = before['gameState'], after['gameState']
    assert new['gameLog'][:len(old['gameLog'])] == old['gameLog']
    old_cards, cards = old['cards'], new['cards']
    changes = {key: value for key, value in cards.items() if old_cards.get(key) != value}
    return {
        'gameStateDelta': {
            'addedCards': {key: value for key, value in changes.items() if key not in old_cards},
            'updatedCards': {key: value for key, value in changes.items() if key in old_cards},
            'removedCardIds': [key for key in old_cards if key not in cards],
            'updatedZones': new['zones'], 'players': new['players'],
            'currentPhase': new['currentPhase'], 'currentStep': new['currentStep'],
            'activePlayerId': new['activePlayerId'], 'priorityPlayerId': new['priorityPlayerId'],
            'turnNumber': new['turnNumber'], 'isGameOver': new['isGameOver'],
            'winnerId': new.get('winnerId'), 'combat': new.get('combat'),
            'combatCleared': new.get('combat') is None,
            'newLogEntries': new['gameLog'][len(old['gameLog']):],
        },
        'player1': after['player1'], 'player2': after['player2'],
        'currentPhase': after['currentPhase'],
        'activePlayerId': after['activePlayerId'],
        'priorityPlayerId': after['priorityPlayerId'],
        'combat': after['combat'], 'combatCleared': after['combat'] is None,
    }


def choice_text(choice):
    return choice.get('display', {}).get('label') or choice.get('operationFamily', 'Action')


def decision_logs(row, flags):
    if len(row['menu']) <= 1:
        return []
    actor = row['actor']
    lines = []
    if row.get('search'):
        candidates = sorted(row['search']['candidates'], key=lambda x: x['visits'], reverse=True)[:3]
        ranking = '; '.join(f"{choice_text(c['choice'])} ({c['visits']} visits, mean {c['meanValue']:.3f})"
                            for c in candidates)
        lines.append(f"{actor} chose {choice_text(row['selectedAction']['choice'])}. Top: {ranking}")
    for flag in flags:
        lines.append(f"Flag {flag['category']} [{flag['severity']}]: {flag['reason']}" +
                     (f" Verified cost: {flag['verified_cost']}" if 'verified_cost' in flag else ''))
    return [{'type': 'system', 'playerId': actor, 'description': line} for line in lines]


def checked(actual, expected, game_id, position):
    return check_state(actual, expected, game_id, position, {})


GENERATED_IDS = {
    'uuid': re.compile(r'[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}'),
    'ability': re.compile(r'ability_\d+'),
}


def check_state(actual, expected, game_id, position, identities):
    """Compare state with a persistent bijection for engine-generated IDs."""
    def mismatch(left, right, path):
        if isinstance(left, str) and isinstance(right, str):
            for kind, pattern in GENERATED_IDS.items():
                if pattern.fullmatch(left) and pattern.fullmatch(right):
                    forward, reverse = identities.setdefault(kind, ({}, {}))
                    if (left in forward and forward[left] != right or
                            right in reverse and reverse[right] != left):
                        return path
                    forward[left] = right
                    reverse[right] = left
                    return None
        if type(left) is not type(right):
            return path
        if isinstance(left, dict):
            if left.keys() != right.keys():
                return path + '.keys'
            for key in left:
                found = mismatch(left[key], right[key], f'{path}.{key}')
                if found:
                    return found
        elif isinstance(left, list):
            if len(left) != len(right):
                return path + '.length'
            for index, (item, other) in enumerate(zip(left, right)):
                found = mismatch(item, other, f'{path}[{index}]')
                if found:
                    return found
        elif left != right:
            return path
        return None
    path = mismatch(actual, expected, 'state')
    if path:
        raise ValueError(f'{game_id} diverged at decision {position}: {path}')


def write_gzip(path, data):
    with gzip.open(path, 'wt', compresslevel=1) as stream:
        json.dump(data, stream, separators=(',', ':'), ensure_ascii=False)


def verify_game(session, path, deck, header):
    game_id = header['gameId']
    identities = {}
    game = session.game([deck, deck], seed=header['seed'], policies=['random', 'random'])
    try:
        try:
            check_state(game.state(), header['startingState'], game_id, 'start', identities)
            lines = read_lines(path)
            next(lines)
            next(lines)
            for row in lines:
                if row['type'] == 'end':
                    check_state(game.state(), row['privilegedFinalState'], game_id, 'final', identities)
                    status = game.status()
                    if not status['terminal'] or status['payoffs'] != header['result']['payoffs']:
                        raise ValueError(f'{game_id} diverged at terminal result: {status}')
                    return None
                index = row['decisionIndex']
                if game.status()['index'] != index:
                    raise ValueError(f'{game_id} diverged at decision {index}: index')
                check_state(game.state(), row['privilegedPreState'], game_id, index, identities)
                action = row['selectedAction']
                game._call('step', index=action['index'], view=action['view'],
                           choice=action['choice'], record=False)
            raise ValueError(f'{game_id} missing terminal record')
        except Exception as error:
            return str(error)
    finally:
        game.close()


def export_game(session, path, deck, flags, output):
    lines = read_lines(path)
    execution = next(lines)
    if execution.get('error'):
        raise ValueError(f'{path.name}: recorded game failed: {execution["error"]}')
    header = next(lines)
    game_id = header['gameId']
    divergence = verify_game(session, path, deck, header)
    streams = {name: [] for name in ('hindsight', 'p0', 'p1')}
    states = []
    logs = []
    for row in lines:
        if row['type'] == 'end':
            break
        index = row['decisionIndex']
        state = row['privilegedPreState']
        if len(row['menu']) > 1:
            logs.extend(decision_logs(row, flags.get((game_id, index), [])))
            views = session._call('render-replay-state', state=state)
            views['hindsight'] = hindsight(views)
            for name in streams:
                streams[name].append(snapshot(game_id, name, views[name], logs))
            states.append(state)
    final_state = row['privilegedFinalState']
    views = session._call('render-replay-state', state=final_state)
    views['hindsight'] = hindsight(views)
    for name in streams:
        streams[name].append(snapshot(game_id, name, views[name], logs))
    states.append(final_state)
    payoffs = header['result']['payoffs']
    winner = 'p0' if payoffs['p0'] > 0 else 'p1' if payoffs['p1'] > 0 else None
    for name, frames in streams.items():
        metadata = {'gameId': f'{game_id}-{name}', 'player1Name': 'Player 0',
                    'player2Name': 'Player 1', 'winnerName': winner,
                    'startedAt': '2026-09-26T00:00:00Z', 'endedAt': '2026-09-26T00:00:00Z',
                    'snapshotCount': len(frames),
                    'fidelity': 'DIVERGED' if divergence else 'EXACT',
                    'degradedReason': divergence,
                    'stateReproducible': divergence is None}
        write_gzip(output / f'{game_id}-{name}.json.gz', {
            'metadata': metadata, 'initialSnapshot': frames[0],
            'deltas': [delta(a, b) for a, b in zip(frames, frames[1:])],
        })
    write_gzip(output / f'{game_id}-states.json.gz', states)
    return {'gameId': game_id, 'seed': header['seed'], 'policies': header['policies'],
            'winner': winner, 'frames': len(states), 'divergence': divergence}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('records', type=Path, help='Task 14 run folder')
    parser.add_argument('deck', type=Path, help='private deck JSON with mainDeck')
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    deck = json.loads(args.deck.read_text())['mainDeck']
    all_flags = json.loads((args.records / 'branch-flags.json').read_text())
    flags = {}
    for flag in all_flags:
        flags.setdefault((flag['gameId'], flag['decision_index']), []).append(flag)
    paths = sorted((args.records / 'record-pilot').glob('game-*.jsonl.gz')) + \
            sorted((args.records / 'record-main').glob('game-*.jsonl.gz'))
    results = []
    with Session(build=False, java_options=['-Xmx1g', '-XX:ActiveProcessorCount=2']) as session:
        for path in paths:
            try:
                result = export_game(session, path, deck, flags, args.output)
                result['flags'] = sum(f['gameId'] == result['gameId'] for f in all_flags)
                results.append(result)
                print(result['gameId'], result['frames'], flush=True)
            except Exception as error:
                results.append({'source': path.name, 'error': str(error)})
                print(path.name, error, flush=True)
    (args.output / 'index.json').write_text(json.dumps(results, indent=2))
    if any('error' in result for result in results):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
