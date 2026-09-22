"""Plain device and checkpoint behavior; no hardware identity registry."""
import copy
import unittest
from unittest.mock import patch
import torch
from tools.neural_policy.compute import LearnerDevice
from tools.neural_policy.learning import SequenceLearner, TrainingConfig
from tools.neural_policy.models import ModelConfig
from tools.neural_policy.tests.test_sequences import dataset


class ComputeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        torch.set_num_threads(1)

    def test_unavailable_cuda_fails_without_becoming_cpu(self):
        with patch.object(torch.cuda, 'is_available', return_value=False):
            with self.assertRaisesRegex(RuntimeError, 'no CPU fallback'):
                LearnerDevice('cuda:0')
        with patch.object(torch.cuda, 'is_available', side_effect=AssertionError('CPU need not inspect CUDA')):
            self.assertEqual('cpu', LearnerDevice('cpu').device.type)

    def test_cpu_reference_copy_preserves_weights_and_random_state(self):
        learner = SequenceLearner(dataset(), ModelConfig('GRU'), TrainingConfig(epochs=2))
        learner.run(1)
        before = torch.get_rng_state().clone()
        reference = learner.cpu_reference()
        self.assertTrue(torch.equal(before, torch.get_rng_state()))
        self.assertIsNot(reference, learner.model)
        for key, value in reference.state_dict().items():
            self.assertTrue(torch.equal(value, learner.model.state_dict()[key]))

    def test_checkpoint_can_seed_a_new_treatment_with_a_different_learning_rate(self):
        original = SequenceLearner(dataset(), ModelConfig('GRU'), TrainingConfig(epochs=1))
        original.run(1)
        changed = copy.deepcopy(dataset())
        changed['episodes'][0]['decisions'][0]['target'] = [.25, .75]
        new = SequenceLearner(changed, original.model_config,
                              TrainingConfig(epochs=1, learningRate=.02), 'different-task')
        new.restore(original.checkpoint())
        self.assertNotEqual(original.input_hash, new.input_hash)
        self.assertEqual(.02, new.optimizer.param_groups[0]['lr'])
        new.run(2)  # Additional work is not confined to the old epoch declaration.
        self.assertEqual(3, new.completed)
        self.assertTrue(all(torch.isfinite(value).all() for value in new.model.state_dict().values()))

    def test_incompatible_model_shapes_raise_an_actual_torch_error(self):
        small = SequenceLearner(dataset(), ModelConfig('GRU', hiddenSize=12), TrainingConfig(epochs=1))
        large = SequenceLearner(dataset(), ModelConfig('GRU', hiddenSize=24), TrainingConfig(epochs=1))
        with self.assertRaises(RuntimeError):
            large.restore(small.checkpoint())

    def test_dimensions_and_schedule_are_not_fixed_research_profiles(self):
        ModelConfig('CURRENT_VIEW', hiddenSize=256, byteWidth=80, contextEvents=256)
        TrainingConfig(epochs=3000, groupsPerBatch=64, learningRate=.2, gradientNorm=200.)
        data = dataset()
        for episode in data['episodes']:
            episode['events'].extend([[1, 2, 3, 4]] * 140)
            episode['decisions'] *= 40
        from tools.neural_policy.data import admit
        admit(data)


if __name__ == '__main__':
    unittest.main()
