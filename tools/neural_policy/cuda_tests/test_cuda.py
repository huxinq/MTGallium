"""Optional real-CUDA witnesses. This lane requires an executable CUDA device."""
import copy
import unittest
import torch
from tools.neural_policy.data import pack
from tools.neural_policy.models import ModelConfig
from tools.neural_policy.learning import SequenceLearner, TrainingConfig
from tools.neural_policy.tests.test_sequences import dataset


class CudaTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not torch.cuda.is_available():
            raise RuntimeError('This optional integration lane requires CUDA; no CPU substitute')
        torch.set_num_threads(1)

    def test_current_gpu_weights_agree_with_their_cpu_reference(self):
        for architecture in ('CURRENT_VIEW', 'GRU', 'BOUNDED_ATTENTION'):
            with self.subTest(architecture=architecture):
                learner = SequenceLearner(dataset(), ModelConfig(architecture),
                                          TrainingConfig(epochs=1), device='cuda:0')
                learner.run(1)
                batch = pack(dataset()['episodes'][:2])
                with torch.inference_mode():
                    actual, _, _ = learner.model.sequence(batch.to('cuda:0').features)
                    reference, _, _ = learner.cpu_reference().sequence(batch.features)
                torch.testing.assert_close(actual.cpu(), reference, atol=2e-5, rtol=2e-5)

    def test_same_gpu_checkpoint_continuation_reproduces_uninterrupted_work(self):
        config = ModelConfig('GRU')
        whole = SequenceLearner(dataset(), config, TrainingConfig(epochs=2), device='cuda:0')
        whole.run(2)
        partial = SequenceLearner(dataset(), config, TrainingConfig(epochs=1), device='cuda:0')
        partial.run(1)
        checkpoint = partial.checkpoint()
        self.assertTrue(all(value.device.type == 'cpu' for value in checkpoint['model'].values()))
        resumed = SequenceLearner(dataset(), config, TrainingConfig(epochs=1), device='cuda:0')
        resumed.restore(copy.deepcopy(checkpoint))
        resumed.run(1)
        self.assertEqual(whole.losses, resumed.losses)
        for key, value in whole.model.state_dict().items():
            self.assertTrue(torch.equal(value, resumed.model.state_dict()[key]))
