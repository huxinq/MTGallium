"""Optional ONNX integration tests. Run explicitly with the export dependencies installed."""
from pathlib import Path
import json
import tempfile
import unittest
import torch
from tools.neural_policy.models import ModelConfig
from tools.neural_policy.learning import SequenceLearner, TrainingConfig
from tools.neural_policy.exporting import export_models, parity_cases
from tools.neural_policy.tests.test_sequences import dataset


class ExportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        torch.set_num_threads(1)

    def test_export_honors_one_and_two_candidate_schema_bounds(self):
        for maximum in (1, 2):
            data = dataset()
            data['schema']['maximumCandidates'] = maximum
            if maximum == 1:
                for episode in data['episodes']:
                    episode['decisions'][0]['input']['actions'] = [[2, 4, 6, 8]]
                    episode['decisions'][0]['target'] = [1.]
            with tempfile.TemporaryDirectory() as directory:
                learner = SequenceLearner(data, ModelConfig('CURRENT_VIEW'), TrainingConfig(epochs=1), 'small-menu')
                learner.run(1)
                output = Path(directory) / 'export'
                export_models(learner, output, maximum_batch=maximum)
                self.assertEqual(0, parity_cases(learner, output)['choiceMismatches'])


    def test_actual_byte_models_export_and_qualify_propagated_state(self):
        for architecture in ('CURRENT_VIEW', 'GRU', 'BOUNDED_ATTENTION'):
            with self.subTest(architecture=architecture), tempfile.TemporaryDirectory() as directory:
                learner = SequenceLearner(dataset(), ModelConfig(architecture, hiddenSize=12, attentionHeads=3),
                                          TrainingConfig(epochs=2), 'numerical-export-fixture')
                learner.run(2)
                output = Path(directory) / 'export'
                descriptor = export_models(learner, output)
                self.assertEqual(architecture, descriptor['config']['architecture'])
                result = parity_cases(learner, output)
                self.assertEqual(0, result['choiceMismatches'])
                self.assertLess(result['maximumScoreError'], 2e-5)


    def test_new_pooling_exports_through_the_same_onnx_memory_and_score_abi(self):
        for architecture in ('CURRENT_VIEW', 'GRU', 'BOUNDED_ATTENTION'):
            with self.subTest(architecture=architecture), tempfile.TemporaryDirectory() as temporary:
                cfg = ModelConfig(architecture, hiddenSize=12, attentionHeads=3,
                                  actionScoring='dot-product-v1', bytePooling='mean-max-v1')
                learner = SequenceLearner(dataset(), cfg, TrainingConfig(epochs=2), 'pooled-export')
                learner.run(2)
                output = Path(temporary) / 'inference'
                descriptor = export_models(learner, output)
                training = json.loads((output / 'training.json').read_text())
                self.assertEqual('mean-max-v1', training['model']['bytePooling'])
                self.assertEqual(0, parity_cases(learner, output)['choiceMismatches'])
