"""Padding is data, while loss eligibility is a separate semantic distinction."""
import copy
import unittest
import torch
from tools.neural_policy.data import admit, pack
from tools.neural_policy.models import ModelConfig, SequenceModel
from tools.neural_policy.tests.test_sequences import dataset


class PackingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        torch.set_num_threads(1)

    def test_ragged_padding_and_context_only_actions_are_not_confused(self):
        data = dataset()
        first, second = data['episodes'][:2]
        first['events'] = []
        first['decisions'][0]['eventPosition'] = 0
        first['decisions'][0]['input']['view'] = [256, 1]
        first['decisions'][0]['input']['actions'] = [[256]]
        first['decisions'][0]['target'] = [1.0]
        context = copy.deepcopy(second['decisions'][0])
        context.update(eventPosition=2, target=None, weight=0.0, lossEligible=False)
        context['input']['actions'].append([256, 3])
        second['decisions'].append(context)
        admit(data)
        batch = pack([first, second]).to('cpu')
        features = batch.features
        self.assertEqual([[False, False], [True, True]], features['event_valid'].tolist())
        self.assertEqual([[[True, False, False], [False, False, False]],
                          [[True, True, False], [True, True, True]]], features['candidate_mask'].tolist())
        self.assertEqual([[True, False], [True, False]], batch.eligible.tolist())
        self.assertEqual([True, True, False, False], features['view_mask'][0, 0].tolist())
        self.assertEqual([True, False, False, False], features['action_token_mask'][0, 0, 0].tolist())
        self.assertEqual(0.0, batch.weights[1, 1].item())
        self.assertTrue(features['candidate_mask'][1, 1].all())  # Context has actual choices, not padding.
        for key in ('weights', 'targets', 'eligible', 'groups', 'coordinates'):
            self.assertNotIn(key, features)

    def test_an_empty_delivered_history_is_valid_input_for_each_model(self):
        data = dataset()
        for episode in data['episodes']:
            episode['events'] = []
            episode['decisions'][0]['eventPosition'] = 0
        admit(data)
        batch = pack(data['episodes'][:2])
        self.assertFalse(batch.features['event_valid'].any())
        for architecture in ('CURRENT_VIEW', 'GRU', 'BOUNDED_ATTENTION'):
            with self.subTest(architecture=architecture), torch.inference_mode():
                model = SequenceModel(ModelConfig(architecture, hiddenSize=12, attentionHeads=3)).eval()
                scores, _, _ = model.sequence(batch.features)
                self.assertTrue(torch.isfinite(scores).all())
                self.assertEqual(batch.targets.shape, scores.shape)


if __name__ == '__main__':
    unittest.main()
