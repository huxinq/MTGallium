"""Validate numerical structure and pack tensors without inventing game targets."""
from dataclasses import dataclass
import math
import torch
from torch.nn import functional as F

OBJECTIVE = 'equal-group-weighted-decision-cross-entropy-v1'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def admit(data):
    require(data.get('version') == 1 and data.get('objective') == OBJECTIVE, 'Unsupported sequence/objective contract')
    schema = data['schema']
    require(schema['version'] == 'factual-policy-json-bytes-v1', 'Unknown factual input schema')
    episodes = data['episodes']
    require(isinstance(episodes, list) and bool(episodes), 'Nonempty sequence population required')
    require(len({e['episodeId'] for e in episodes}) == len(episodes), 'Duplicate episode')
    def tokens(values, maximum):
        require(isinstance(values, list) and 1 <= len(values) <= maximum and
                all(type(x) is int and 1 <= x <= 256 for x in values), 'Invalid unpadded factual byte tokens')
    for episode in episodes:
        require(episode['playerId'] and episode['groupId'] and episode['split'] in ('TRAIN', 'EVALUATION'), 'Episode ownership/split')
        events = episode['events']
        for event in events:
            tokens(event, schema['maximumEventBytes'])
        decisions = episode['decisions']
        require(bool(decisions), 'An episode needs a decision')
        previous = -1
        for decision in decisions:
            position = decision['eventPosition']
            require(type(position) is int and previous <= position <= len(events) and position >= 0, 'Noncausal event position')
            previous = position
            value = decision['input']
            tokens(value['view'], schema['maximumViewBytes'])
            actions = value['actions']
            require(1 <= len(actions) <= schema['maximumCandidates'], 'Candidate count bound')
            for action in actions:
                tokens(action, schema['maximumActionBytes'])
            require(type(value['rulesExhaustive']) is bool and type(value['profileExhaustive']) is bool and
                    (not value['rulesExhaustive'] or value['profileExhaustive']), 'Menu completeness contract')
            weight = decision['weight']
            require(type(weight) in (int, float) and math.isfinite(weight) and weight >= 0, 'Invalid decision weight')
            require(type(decision['lossEligible']) is bool, 'Loss eligibility must be explicit')
            target = decision['target']
            if decision['lossEligible']:
                require(isinstance(target, list) and len(target) == len(actions) and weight > 0 and
                        all(type(x) in (int, float) and math.isfinite(x) and 0 <= x <= 1 for x in target) and
                        abs(sum(target) - 1) < 1e-9, 'Invalid native target distribution')
            else:
                require(target is None and weight == 0, 'Context-only decisions have no target or loss weight')
    return data


@dataclass
class TrainingBatch:
    features: dict
    targets: torch.Tensor
    weights: torch.Tensor
    eligible: torch.Tensor
    groups: torch.Tensor
    coordinates: list

    def to(self, device):
        return TrainingBatch({key: value.to(device) for key, value in self.features.items()},
                             self.targets.to(device), self.weights.to(device), self.eligible.to(device),
                             self.groups.to(device), self.coordinates)


def pack(episodes):
    require(bool(episodes), 'Cannot pack an empty batch')
    b = len(episodes)
    t = max(1, max(len(e['events']) for e in episodes))
    d = max(len(e['decisions']) for e in episodes)
    a = max(len(x['input']['actions']) for e in episodes for x in e['decisions'])
    el = max(1, max((len(x) for e in episodes for x in e['events']), default=1))
    vl = max(len(x['input']['view']) for e in episodes for x in e['decisions'])
    al = max(len(y) for e in episodes for x in e['decisions'] for y in x['input']['actions'])
    events = torch.zeros((b, t, el), dtype=torch.long)
    views = torch.zeros((b, d, vl), dtype=torch.long)
    actions = torch.zeros((b, d, a, al), dtype=torch.long)
    menu = torch.zeros((b, d, 2))
    positions = torch.zeros((b, d), dtype=torch.long)
    targets = torch.zeros((b, d, a))
    weights = torch.zeros((b, d))
    eligible = torch.zeros((b, d), dtype=torch.bool)
    group_ids = {name: i for i, name in enumerate(sorted({e['groupId'] for e in episodes}))}
    groups = torch.tensor([group_ids[e['groupId']] for e in episodes], dtype=torch.long)
    coordinates = []
    for i, episode in enumerate(episodes):
        for j, event in enumerate(episode['events']):
            events[i, j, :len(event)] = torch.tensor(event)
        for j, decision in enumerate(episode['decisions']):
            value = decision['input']
            positions[i, j] = decision['eventPosition']
            views[i, j, :len(value['view'])] = torch.tensor(value['view'])
            for k, action in enumerate(value['actions']):
                actions[i, j, k, :len(action)] = torch.tensor(action)
            menu[i, j] = torch.tensor([value['rulesExhaustive'], value['profileExhaustive']])
            eligible[i, j] = decision['lossEligible']
            weights[i, j] = decision['weight']
            if decision['lossEligible']:
                targets[i, j, :len(decision['target'])] = torch.tensor(decision['target'])
            coordinates.append((i, j, episode['episodeId'], decision.get('subset', '')))
    # Episode/group/source/target metadata cannot be supplied to model.sequence by this API.
    event_mask = events != 0
    action_token_mask = actions != 0
    return TrainingBatch(dict(events=events, event_mask=event_mask, event_valid=event_mask.any(-1), views=views,
        view_mask=views != 0, actions=actions, action_token_mask=action_token_mask, candidate_mask=action_token_mask.any(-1),
        menu=menu, positions=positions), targets, weights, eligible, groups, coordinates)


def reduced_loss(scores, batch):
    """Weighted mean over eligible decisions WITHIN each source group, then equal mean over groups."""
    if not torch.isfinite(scores).all():
        raise ValueError('Non-finite policy scores')
    require(scores.shape == batch.targets.shape, 'Score/target shapes differ')
    logp = F.log_softmax(scores.masked_fill(~batch.features['candidate_mask'], -1e9), dim=-1)
    per_decision = -(batch.targets * logp).sum(-1)
    valid_weight = batch.weights * batch.eligible
    means = []
    for group in torch.unique(batch.groups, sorted=True):
        members = batch.groups == group
        denominator = valid_weight[members].sum()
        if denominator <= 0:
            raise ValueError('Empty weighted source group')
        means.append((per_decision[members] * valid_weight[members]).sum() / denominator)
    return torch.stack(means).mean()


def readouts(model, episodes, *, device='cpu'):
    require(episodes, 'Readouts require a nonempty population')
    groups = {}
    for episode in episodes:
        groups.setdefault(episode['groupId'], []).append(episode)
    losses, subsets, eligible = [], {}, 0
    with torch.inference_mode():
        for members in groups.values():
            batch = pack(members).to(device)
            scores, _, _ = model.sequence(batch.features)
            losses.append(reduced_loss(scores, batch).item())
            chosen = scores.argmax(-1)
            target = batch.targets.argmax(-1)
            eligible += int(batch.eligible.sum())
            for i, j, episode, subset in batch.coordinates:
                if not batch.eligible[i, j]:
                    continue
                entry = subsets.setdefault(subset, dict(decisions=0, matchingTarget=0))
                entry['decisions'] += 1
                entry['matchingTarget'] += int(chosen[i, j] == target[i, j])
    return dict(objective=OBJECTIVE, loss=sum(losses) / len(losses), groups=len(groups),
                eligibleDecisions=eligible, subsets=subsets, terminalQuality=None, referenceActionRegret=None,
                scope='Equal source-group mean of weighted eligible-decision losses; streamed one group at a time. Action matching is not terminal quality or independent games.')
