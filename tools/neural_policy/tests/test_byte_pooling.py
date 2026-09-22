"""Numerical controls for the optional factual encoder, not new game targets."""
import copy
from pathlib import Path
import tempfile
import unittest
import torch

from tools.neural_policy.models import ByteEncoder, ModelConfig, SequenceModel
from tools.neural_policy.data import pack
from tools.neural_policy.learning import SequenceLearner, TrainingConfig
from tools.neural_policy.tests.test_sequences import dataset


class BytePoolingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        torch.set_num_threads(1)
        torch.use_deterministic_algorithms(True)

    def test_mean_pooling_records_its_choice_and_preserves_the_arithmetic(self):
        config = ModelConfig('GRU')
        self.assertEqual('mean-v1', config.wire()['bytePooling'])
        self.assertEqual(config, ModelConfig(**config.wire()))
        with self.assertRaises(ValueError):
            ModelConfig('GRU', bytePooling='guess-fields')
        encoder = ByteEncoder(4, 8)
        self.assertEqual({'embedding.weight', 'local.weight', 'local.bias'}, set(encoder.state_dict()))
        tokens = torch.tensor([[2, 3, 4, 0], [6, 0, 0, 0]])
        included = tokens != 0
        values = encoder.embedding(tokens) * included.unsqueeze(-1)
        values = torch.tanh(encoder.local(values.transpose(1, 2))).transpose(1, 2)
        weights = included.unsqueeze(-1).to(values.dtype)
        original = (values * weights).sum(1) / weights.sum(1).clamp_min(1)
        self.assertTrue(torch.equal(original, encoder(tokens, included)))
        # Ordinary numerical checkpoint restore preserves progress.
        learner = SequenceLearner(dataset(), config, TrainingConfig(epochs=2), 'old-mean')
        learner.run(1)
        saved = learner.checkpoint()
        learner.restore(saved)
        self.assertEqual(saved['completedEpochs'], learner.completed)

    def test_sparse_feature_maximum_is_not_diluted_and_padding_has_no_content(self):
        encoder = ByteEncoder(4, 8, 'mean-max-v1')
        with torch.no_grad():
            for parameter in encoder.parameters():
                parameter.zero_()
            encoder.embedding.weight[2, 0] = 1
            encoder.local.weight[0, 0, 1] = 1
            encoder.combine.weight[0, 8] = 1  # Read just the controlled maximum.
        short = torch.ones((1, 4), dtype=torch.long)
        long = torch.ones((1, 1024), dtype=torch.long)
        short[0, 1] = long[0, 500] = 2
        self.assertTrue(torch.equal(encoder(short, short != 0), encoder(long, long != 0)))
        padded = torch.cat((short, torch.full((1, 100), 200)), dim=1)
        mask = torch.zeros_like(padded, dtype=torch.bool)
        mask[:, :4] = True
        torch.testing.assert_close(encoder(short, short != 0), encoder(padded, mask), atol=0, rtol=0)
        empty = encoder(padded, torch.zeros_like(mask))
        self.assertTrue(torch.equal(empty, torch.zeros_like(empty)))
        empty.sum().backward()
        self.assertTrue(all(p.grad is not None and torch.isfinite(p.grad).all() for p in encoder.parameters()))

    def test_all_architectures_keep_causal_prefixes_and_exact_resume(self):
        episodes = dataset()['episodes'][:2]
        episodes[0]['events'].extend([[20, 21, 22, 23]] * 20)
        batch = pack(episodes)
        for architecture in ('CURRENT_VIEW', 'GRU', 'BOUNDED_ATTENTION'):
            with self.subTest(architecture=architecture):
                cfg = ModelConfig(architecture, hiddenSize=12, attentionHeads=3,
                                  actionScoring='dot-product-v1', bytePooling='mean-max-v1')
                model = SequenceModel(cfg).eval()
                scores, memories, masks = model.sequence(batch.features)
                memory, history = model.initial(2)
                for position in range(batch.features['events'].shape[1]):
                    memory, history = model.update(batch.features['events'][:, position], batch.features['event_mask'][:, position],
                                                    batch.features['event_valid'][:, position], memory, history)
                    torch.testing.assert_close(memory, memories[:, position + 1], atol=2e-6, rtol=2e-6)
                    self.assertTrue(torch.equal(history, masks[:, position + 1]))
                future = copy.deepcopy(batch.features)
                future['events'][:, 1:] = 201
                changed, _, _ = model.sequence(future)
                torch.testing.assert_close(scores, changed, atol=0, rtol=0)
                config = TrainingConfig(epochs=4, groupsPerBatch=2)
                whole = SequenceLearner(dataset(), cfg, config, 'pooled-resume')
                whole.run(4)
                partial = SequenceLearner(dataset(), cfg, config, 'pooled-resume')
                partial.run(1)
                restored = SequenceLearner(dataset(), cfg, config, 'pooled-resume')
                restored.restore(partial.checkpoint())
                restored.run(3)
                self.assertEqual(whole.losses, restored.losses)
                for key, value in whole.model.state_dict().items():
                    self.assertTrue(torch.equal(value, restored.model.state_dict()[key]), key)




if __name__ == '__main__':
    unittest.main()
