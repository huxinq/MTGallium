#!/usr/bin/env python3
"""Collect short-deck games, fork a decision, train an imitation policy, and play it.

    python examples/python-game-learning.py /tmp/my-fixture
    python examples/python-game-learning.py /tmp/my-learning-fixture --train --epochs 2

Targets are observed heuristic actions. Eight-card decks keep the example short.
"""
import argparse
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from research_workspace import Session


OBJECTIVE = 'equal-group-weighted-decision-cross-entropy-v1'
DECKS = [{'Mountain': 5, 'Shock': 3}] * 2


def write(path, value):
    path.write_text(json.dumps(value, allow_nan=False, indent=2) + '\n')


def collect(runtime, seed, split):
    episodes = {}
    schema = None

    def record(row):
        nonlocal schema
        if not row['accepted']:
            raise RuntimeError('A rejected action cannot become an imitation label')
        actor = row['information']['actingPlayerId']
        factual = row['factual']
        schema = factual['schema']
        episode = episodes.setdefault(actor, dict(episodeId=f'{seed}/{actor}', groupId=f'setup:{seed}',
            playerId=actor, split=split, events=[], decisions=[]))
        if factual['events'][:len(episode['events'])] != episode['events']:
            raise RuntimeError('The delivered event prefix changed')
        episode['events'] = factual['events']
        count = len(factual['input']['actions'])
        episode['decisions'].append(dict(eventPosition=factual['eventPosition'], input=factual['input'],
            target=[float(i == row['selectedIndex']) for i in range(count)], weight=1., lossEligible=True,
            subset='observed-heuristic-action'))

    with runtime.game(DECKS, seed=seed, policies=('heuristic', 'heuristic'), starting_life=6,
                      starting_hand_size=7, skip_mulligans=True) as game:
        before = game.status()
        decision = game.decision()
        with game.fork() as branch:
            branch.step(decision.actions[0])
            assert game.status() == before
            assert branch.status()['index'] == before['index'] + 1
        result = game.play(decision_limit=96, factual=True, record=record)
    return list(episodes.values()), schema, result


def run(output, *, train=False, epochs=2, build=True):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    episodes, outcomes = [], []
    with Session(build=build, java_options=['-Xmx1g']) as runtime:
        pid = runtime.pid
        schema = None
        for seed, split in ((17, 'TRAIN'), (18, 'TRAIN'), (19, 'EVALUATION')):
            rows, current_schema, result = collect(runtime, seed, split)
            if schema is not None:
                assert current_schema == schema
            schema = current_schema
            episodes.extend(rows)
            outcomes.append(dict(seed=seed, split=split, result=result))
        data = dict(version=1, objective=OBJECTIVE, schema=schema, episodes=episodes)
        write(output / 'episodes.json', data)
        report = dict(gameResults=outcomes, episodes=len(episodes),
            decisions=sum(len(e['decisions']) for e in episodes),
            multiActionDecisions=sum(len(d['input']['actions']) > 1 for e in episodes for d in e['decisions']),
            runtimePid=pid, scope='Public short-deck technical fixture. Imitation targets are observed actions, not strategic values.')
        write(output / 'collection.json', report)
        if train:
            import torch
            from neural_policy.models import ModelConfig
            from neural_policy.learning import SequenceLearner, TrainingConfig
            from neural_policy.data import pack, readouts

            torch.set_num_threads(1)
            learner = SequenceLearner(data, ModelConfig('GRU', hiddenSize=8, byteWidth=4, attentionHeads=2),
                TrainingConfig(epochs=epochs, groupsPerBatch=1, learningRate=.005))
            learner.run(epochs)
            torch.save(learner.checkpoint(), output / 'checkpoint.pt')
            report['trainingLosses'] = learner.losses
            report['evaluation'] = readouts(learner.model, [e for e in episodes if e['split'] == 'EVALUATION'])

            def learned_policy(decision):
                factual = decision.factual
                # No target or terminal result is supplied to the live model.
                episode = dict(episodeId='live', groupId='live', playerId=decision.actor,
                    events=factual['events'], decisions=[dict(eventPosition=factual['eventPosition'],
                        input=factual['input'], target=None, weight=0., lossEligible=False, subset='inference')])
                batch = pack([episode])
                with torch.inference_mode():
                    scores, _, _ = learner.model.sequence(batch.features)
                return decision.actions[int(scores[0, 0].argmax())]

            with runtime.game(DECKS, seed=27, policies=('heuristic', 'heuristic'), starting_life=6,
                              starting_hand_size=7, skip_mulligans=True) as reference, reference.fork() as learned:
                report['referenceGame'] = reference.play(decision_limit=96)
                report['learnedGame'] = learned.play({'p0': learned_policy}, decision_limit=96, factual=True)
            report['comparisonScope'] = 'One shared-setup technical comparison, not a playing-strength estimate.'
        assert runtime.pid == pid
        write(output / 'result.json', report)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--train', action='store_true', help='Also train PyTorch and run it as a live Python policy')
    parser.add_argument('--epochs', type=int, default=2)
    parser.add_argument('--no-build', action='store_true', help='Use the current compiled classes explicitly')
    args = parser.parse_args()
    print(json.dumps(run(args.output, train=args.train, epochs=args.epochs, build=not args.no_build), indent=2))
