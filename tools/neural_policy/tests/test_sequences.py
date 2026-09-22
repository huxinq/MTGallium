import copy
import math
from pathlib import Path
import tempfile
import unittest
import torch
from tools.neural_policy.models import ModelConfig, SequenceModel
from tools.neural_policy.data import admit, pack, reduced_loss, OBJECTIVE
from tools.neural_policy.learning import SequenceLearner, TrainingConfig


def dataset():
    schema = dict(version='factual-policy-json-bytes-v1', maximumViewBytes=16384,
                  maximumEventBytes=4096, maximumActionBytes=4096, maximumCandidates=64)
    episodes = []
    for group in range(4):
        for answer in (0, 1):
            events = [[9, 12 + answer, 23, 17], [40 + group, 41, 42, 43]]
            value = dict(view=[8, 7, 6, 5], actions=[[2, 4, 6, 8], [3, 5, 7, 9]], rulesExhaustive=True, profileExhaustive=True)
            episodes.append(dict(episodeId=f'{group}-{answer}', groupId=f'g{group}', playerId='p0',
                split='TRAIN' if group < 3 else 'EVALUATION', events=events,
                decisions=[dict(eventPosition=1, input=value, target=[1.0 - answer, float(answer)],
                                weight=1.0, lossEligible=True, subset='delayed-cue')]))
    return dict(version=1, objective=OBJECTIVE, schema=schema, episodes=episodes)


class SequenceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        torch.set_num_threads(1)
        torch.use_deterministic_algorithms(True)

    def test_loss_uses_valid_decisions_then_equal_groups_not_padding_or_episode_count(self):
        data = dataset()['episodes'][:3]
        data[0]['decisions'][0]['target'] = [1., 0.]
        data[1]['decisions'][0]['target'] = [1., 0.]
        data[2]['decisions'][0]['target'] = [1., 0.]
        data[0]['decisions'][0]['weight'] = 3.0
        context = copy.deepcopy(data[0]['decisions'][0])
        context.update(target=None, weight=0., lossEligible=False)
        data[0]['decisions'].append(context)
        batch = pack(data)
        scores = torch.tensor([[[0., 0.], [1e5, -1e5]], [[math.log(3), 0.], [0., 0.]],
                               [[0., math.log(3)], [0., 0.]]], dtype=torch.float64, requires_grad=True)
        expected = ((3 * math.log(2) + math.log(4 / 3)) / 4 + math.log(4)) / 2
        loss = reduced_loss(scores, batch)
        self.assertAlmostEqual(expected, loss.item(), places=12)
        loss.backward()
        self.assertTrue(torch.equal(scores.grad[:, 1], torch.zeros_like(scores.grad[:, 1])))
        # Duplicating identical decisions within one group does not increase that group's weight.
        repeated = data + [copy.deepcopy(data[2])]
        self.assertAlmostEqual(loss.item(), reduced_loss(torch.cat((scores.detach(), scores.detach()[2:3])), pack(repeated)).item(), places=12)

    def test_prefix_is_causal_and_incremental_memory_matches_sequence_for_all_models(self):
        episodes = dataset()['episodes'][:2]
        episodes[0]['events'].extend([[77, 88, 99, 11]] * 20)
        for architecture in ('CURRENT_VIEW', 'GRU', 'BOUNDED_ATTENTION'):
            with self.subTest(architecture=architecture):
                torch.manual_seed(54)
                model = SequenceModel(ModelConfig(architecture, hiddenSize=12, attentionHeads=3)).eval()
                batch = pack(episodes)
                with torch.inference_mode():
                    scores, trace, masks = model.sequence(batch.features)
                    memory, history = model.initial(2)
                    for position in range(batch.features['events'].shape[1]):
                        memory, history = model.update(batch.features['events'][:, position], batch.features['event_mask'][:, position],
                                                       batch.features['event_valid'][:, position], memory, history)
                        torch.testing.assert_close(memory, trace[:, position + 1], atol=2e-6, rtol=2e-6)
                        self.assertTrue(torch.equal(history, masks[:, position + 1]))
                    changed = copy.deepcopy(batch.features)
                    changed['events'][:, 1:] = 201
                    future, _, _ = model.sequence(changed)
                    torch.testing.assert_close(scores, future, atol=0, rtol=0)
                    repeated, _, _ = model.sequence(batch.features)
                    self.assertTrue(torch.equal(scores, repeated))
                    reverse = {k: v.flip(0) for k, v in batch.features.items()}
                    permuted, _, _ = model.sequence(reverse)
                    torch.testing.assert_close(scores.flip(0), permuted, atol=2e-6, rtol=2e-6)
                    # The short episode's padded events never change its memory after its last event.
                    self.assertTrue(torch.equal(trace[1, 2], trace[1, -1]))

    def test_optimizer_and_data_order_resume_match_uninterrupted_training(self):
        data = dataset()
        config = TrainingConfig(epochs=6, groupsPerBatch=2)
        for architecture in ('CURRENT_VIEW', 'GRU', 'BOUNDED_ATTENTION'):
            with self.subTest(architecture=architecture):
                model = ModelConfig(architecture, hiddenSize=12, attentionHeads=3)
                complete = SequenceLearner(data, model, config, 'same-binding')
                complete.run(6)
                partial = SequenceLearner(data, model, config, 'same-binding')
                partial.run(2)
                state = partial.checkpoint()
                restored = SequenceLearner(data, model, config, 'same-binding')
                restored.restore(state)
                restored.run(4)
                self.assertEqual(complete.losses, restored.losses)
                self.assertEqual(complete.optimizer_steps, restored.optimizer_steps)
                for name, value in complete.model.state_dict().items():
                    self.assertTrue(torch.equal(value, restored.model.state_dict()[name]), name)
                # The persisted form is loadable without arbitrary-object pickle execution.
                with tempfile.TemporaryDirectory() as directory:
                    path = Path(directory) / 'checkpoint.pt'
                    torch.save(state, path)
                    safe = torch.load(path, map_location='cpu', weights_only=True)
                    self.assertEqual(state['completedEpochs'], safe['completedEpochs'])

    def test_dot_product_head_can_reverse_preference_and_learns_the_delayed_control(self):
        from tools.neural_policy.data import readouts
        for architecture in ('CURRENT_VIEW', 'GRU', 'BOUNDED_ATTENTION'):
            with self.subTest(architecture=architecture):
                model = ModelConfig(architecture, hiddenSize=12, attentionHeads=3, actionScoring='dot-product-v1')
                learner = SequenceLearner(dataset(), model, TrainingConfig(epochs=120), 'dot-product-control')
                learner.run(120)
                evaluation = [e for e in learner.data['episodes'] if e['split'] == 'EVALUATION']
                result = readouts(learner.model, evaluation)['subsets']['delayed-cue']
                self.assertEqual(2, result['decisions'])
                self.assertEqual(1 if architecture == 'CURRENT_VIEW' else 2, result['matchingTarget'])
        with self.assertRaises(ValueError):
            ModelConfig('GRU', actionScoring='unbound-head')

    def test_overlap_is_allowed_but_malformed_targets_are_errors(self):
        data = dataset()
        admit(data)
        data['episodes'][-1]['groupId'] = 'g0'
        admit(data)
        for mutate in (lambda d: d.update(eventPosition=9), lambda d: d.update(target=[.2, .2]),
                       lambda d: d.update(lossEligible=False), lambda d: d['input'].update(actions=[])):
            data = dataset()
            mutate(data['episodes'][0]['decisions'][0])
            with self.assertRaises(ValueError): admit(data)

    def test_large_batches_are_allowed_and_readouts_still_stream_groups(self):
        from tools.neural_policy.data import readouts
        episodes = []
        for index in range(128):
            episode = copy.deepcopy(dataset()['episodes'][0])
            episode.update(episodeId=str(index), groupId=str(index))
            episode['decisions'][0]['input']['view'] = [1] * 16384
            episodes.append(episode)
        self.assertEqual(128, pack(episodes).features['views'].shape[0])
        class ZeroModel:
            def sequence(self, features):
                return torch.zeros_like(features['candidate_mask'], dtype=torch.float), None, None
        result = readouts(ZeroModel(), episodes)
        self.assertEqual(128, result['groups'])
        self.assertEqual(128, result['eligibleDecisions'])
        self.assertAlmostEqual(math.log(2), result['loss'], places=6)

    def test_padded_action_scores_do_not_enter_the_learning_objective(self):
        episode = dataset()['episodes'][0]
        context = copy.deepcopy(episode['decisions'][0])
        context.update(target=None, weight=0., lossEligible=False)
        context['input']['actions'].append([9, 9, 9, 9])
        episode['decisions'].append(context)
        batch = pack([episode])
        scores = torch.tensor([[[0., 0., 1e8], [9., 8., 7.]]], requires_grad=True)
        loss = reduced_loss(scores, batch)
        self.assertAlmostEqual(math.log(2), loss.item(), places=6)
        loss.backward()
        self.assertEqual(0., scores.grad[0, 0, 2])
        self.assertTrue(torch.equal(scores.grad[0, 1], torch.zeros(3)))




if __name__ == '__main__':
    unittest.main()
