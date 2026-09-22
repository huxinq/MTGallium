"""Real CPU training through the ordinary command line, without a native parent."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import torch
from tools.neural_policy.worker import train
from tools.neural_policy.learning import TrainingConfig
from tools.neural_policy.models import ModelConfig
from tools.neural_policy.tests.test_sequences import dataset


class DirectLearnerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        torch.set_num_threads(1)

    def test_direct_module_train_and_additional_checkpoint_work(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data = root / 'data with spaces.json'
            data.write_text(json.dumps(dataset()))
            base = [sys.executable, '-m', 'tools.neural_policy.worker', 'train', str(data)]
            first = root / 'first'
            subprocess.run(base + [str(first), '--epochs', '1', '--threads', '1'],
                           capture_output=True, text=True, check=True, timeout=45)
            initial = json.loads((first / 'result.json').read_text())
            self.assertEqual(1, initial['newEpochs'])
            self.assertEqual(1, initial['completedEpochs'])
            self.assertFalse((first / 'inference').exists())
            second = root / 'second'
            subprocess.run(base + [str(second), '--epochs', '2', '--threads', '1',
                           '--checkpoint', str(first / 'checkpoint.pt')],
                           capture_output=True, text=True, check=True, timeout=45)
            continued = json.loads((second / 'result.json').read_text())
            self.assertEqual(2, continued['newEpochs'])
            self.assertEqual(3, continued['completedEpochs'])
            self.assertEqual(2, len(continued['newEpochLosses']))
            self.assertEqual(1, continued['previousCheckpoint']['completedEpochs'])

    def test_overlap_is_reported_instead_of_forbidding_the_experiment(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data = dataset()
            data['episodes'][-1]['groupId'] = 'g0'
            source = root / 'data.json'
            source.write_text(json.dumps(data))
            result = train(source, root / 'output', ModelConfig('GRU'), TrainingConfig(epochs=1), evaluate=True)
            self.assertEqual(['g0'], result['overlappingTrainEvaluationGroups'])
            self.assertIn('EVALUATION', result['readouts'])
            self.assertIn('not independent evaluation', result['note'])

    def test_optional_export_failure_does_not_erase_completed_training(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'data.json'
            source.write_text(json.dumps(dataset()))
            # The optional export dependency is unavailable, independently of training.
            with patch.dict(sys.modules, {'tools.neural_policy.exporting': None}):
                with self.assertRaises(ModuleNotFoundError):
                    train(source, root / 'output', ModelConfig('GRU'), TrainingConfig(epochs=1), export=True)
            self.assertTrue((root / 'output/checkpoint.pt').exists())
            self.assertEqual(1, json.loads((root / 'output/training.json').read_text())['newEpochs'])
            self.assertFalse((root / 'output/result.json').exists())


if __name__ == '__main__':
    unittest.main()
