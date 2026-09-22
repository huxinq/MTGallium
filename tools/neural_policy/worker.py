"""Train directly from JSON data. No native parent, frozen bundle, or environment approval."""
import argparse
import json
from pathlib import Path
import platform
import sys
import time

import torch
from .models import ModelConfig
from .learning import TrainingConfig, SequenceLearner
from .data import readouts


def describe_environment():
    return dict(python=sys.version, platform=platform.platform(), torch=str(torch.__version__),
                cudaBuild=torch.version.cuda)


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2, allow_nan=False) + '\n')


def train(data_path, output, model, training, *, device='cpu', checkpoint=None,
          deterministic=True, export=False, evaluate=False):
    """Additional epochs are explicit. Loading a checkpoint does not forbid a new treatment."""
    data_path, output = Path(data_path), Path(output)
    data = json.loads(data_path.read_text())
    output.mkdir(parents=True, exist_ok=False)
    context = dict(data=str(data_path.resolve()), model=model.wire(), training=training.wire(),
                   environment=describe_environment(), device=device, deterministic=deterministic,
                   checkpoint=str(Path(checkpoint).resolve()) if checkpoint else None)
    write_json(output / 'context.json', context)
    started = time.monotonic()
    learner = SequenceLearner(data, model, training, device=device, deterministic=deterministic)
    prior = None
    if checkpoint is not None:
        state = torch.load(checkpoint, map_location='cpu', weights_only=True)
        prior = {key: state.get(key) for key in ('inputHash', 'modelConfig', 'trainingConfig', 'completedEpochs')}
        learner.restore(state)
    before = learner.completed
    losses = learner.run(training.epochs)
    torch.save(learner.checkpoint(), output / 'checkpoint.pt')
    training_groups = {e['groupId'] for e in data['episodes'] if e['split'] == 'TRAIN'}
    evaluation_groups = {e['groupId'] for e in data['episodes'] if e['split'] == 'EVALUATION'}
    result = dict(completedEpochs=learner.completed, newEpochs=learner.completed - before,
                  newEpochLosses=losses, inputDataSha256=learner.input_hash,
                  previousCheckpoint=prior, optimizerSteps=learner.optimizer_steps,
                  overlappingTrainEvaluationGroups=sorted(training_groups & evaluation_groups),
                  trainingSeconds=time.monotonic() - started, checkpoint='checkpoint.pt',
                  note='Changed inputs/settings are a new treatment. Overlapping groups are not independent evaluation.')
    # Training remains usable if a separately requested export or readout fails.
    write_json(output / 'training.json', result)
    if evaluate:
        result['readouts'] = {split: readouts(learner.model,
            [e for e in data['episodes'] if e['split'] == split], device=learner.execution.device)
            for split in ('TRAIN', 'EVALUATION') if any(e['split'] == split for e in data['episodes'])}
    if export:
        from .exporting import export_models, parity_cases
        export_models(learner, output / 'inference')
        result['exportParity'] = parity_cases(learner, output / 'inference')
    result['elapsedSeconds'] = time.monotonic() - started
    result['cudaPeakMemory'] = learner.execution.peaks()
    write_json(output / 'result.json', result)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='action', required=True)
    commands.add_parser('describe')
    command = commands.add_parser('train')
    command.add_argument('data', type=Path)
    command.add_argument('output', type=Path)
    command.add_argument('--model', type=Path, help='ModelConfig JSON; default is a small GRU')
    command.add_argument('--training', type=Path, help='TrainingConfig JSON')
    command.add_argument('--epochs', type=int, help='Additional epochs, including after loading a checkpoint')
    command.add_argument('--device', default='cpu', help='Any PyTorch device, e.g. cpu or cuda:0')
    command.add_argument('--threads', type=int, help='Optional PyTorch CPU thread count')
    command.add_argument('--checkpoint', type=Path)
    command.add_argument('--nondeterministic', action='store_true')
    command.add_argument('--export', action='store_true', help='Also export and test production-ABI ONNX graphs')
    command.add_argument('--readouts', action='store_true', help='Compute TRAIN/EVALUATION loss and target matching')
    args = parser.parse_args(argv)
    if args.action == 'describe':
        print(json.dumps(describe_environment(), indent=2))
        return
    model = ModelConfig(**json.loads(args.model.read_text())) if args.model else ModelConfig('GRU')
    config = json.loads(args.training.read_text()) if args.training else {}
    if args.epochs is not None:
        config['epochs'] = args.epochs
    if args.threads is not None:
        torch.set_num_threads(args.threads)
    result = train(args.data, args.output, model, TrainingConfig(**config), device=args.device,
                   checkpoint=args.checkpoint, deterministic=not args.nondeterministic,
                   export=args.export, evaluate=args.readouts)
    print(json.dumps(result, indent=2, allow_nan=False))


if __name__ == '__main__':
    main()
