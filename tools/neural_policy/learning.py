"""Whole-sequence optimization with ordinary save/load and caller-chosen work."""
from dataclasses import dataclass, asdict
import copy
import hashlib
import json
import math
import torch
from .models import ModelConfig, SequenceModel
from .data import OBJECTIVE, admit, pack, reduced_loss, require
from .compute import LearnerDevice, cpu_checkpoint_value


@dataclass(frozen=True)
class TrainingConfig:
    epochs: int = 200
    seed: int = 713
    learningRate: float = 0.01
    groupsPerBatch: int = 4
    gradientNorm: float = 5.0
    objective: str = OBJECTIVE

    def __post_init__(self):
        require(type(self.epochs) is int and self.epochs >= 0, 'Epoch count')
        require(type(self.seed) is int and 0 <= self.seed < 2**63, 'Seed limit')
        require(math.isfinite(self.learningRate) and self.learningRate > 0, 'Learning rate')
        require(type(self.groupsPerBatch) is int and self.groupsPerBatch > 0, 'Group batch size')
        require(math.isfinite(self.gradientNorm) and self.gradientNorm > 0, 'Gradient limit')
        require(self.objective == OBJECTIVE, 'Unsupported objective')

    def wire(self):
        return asdict(self)


def content_hash(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()).hexdigest()


class SequenceLearner:
    """An epoch is the resumable unit. No hidden state from old weights is cached between epochs."""
    def __init__(self, data, model_config: ModelConfig, training: TrainingConfig, binding: str = 'research', *, device='cpu', deterministic=True):
        self.data = copy.deepcopy(admit(data))
        self.config = training
        self.model_config = model_config
        self.binding = binding
        self.input_hash = content_hash(self.data)
        self.training = [e for e in self.data['episodes'] if e['split'] == 'TRAIN']
        require(bool(self.training), 'No training sequences')
        self.group_names = sorted({e['groupId'] for e in self.training})
        self.execution = LearnerDevice(device, deterministic=deterministic)
        self.execution.seed(training.seed)
        # Initialize from the CPU generator, then transfer. Backend choice is not an
        # accidental choice of a different initialization distribution.
        self.model = SequenceModel(model_config).to(self.execution.device)
        self.optimizer = self.execution.optimizer(self.model, training.learningRate)
        self.generator = torch.Generator(device='cpu').manual_seed(training.seed)
        self.completed = 0
        self.optimizer_steps = 0
        self.losses = []

    def run(self, epochs: int):
        require(type(epochs) is int and epochs >= 0, 'Epoch count must be nonnegative')
        self.model.train()
        for _ in range(epochs):
            order = torch.randperm(len(self.group_names), generator=self.generator).tolist()
            epoch_loss = 0.0
            groups_seen = 0
            for offset in range(0, len(order), self.config.groupsPerBatch):
                names = [self.group_names[i] for i in order[offset:offset + self.config.groupsPerBatch]]
                episodes = [e for name in names for e in self.training if e['groupId'] == name]
                batch = pack(episodes).to(self.execution.device)
                self.optimizer.zero_grad(set_to_none=True)
                scores, _, _ = self.model.sequence(batch.features)
                loss = reduced_loss(scores, batch)
                require(bool(torch.isfinite(loss)), 'Non-finite training loss')
                loss.backward()
                norm = torch.nn.utils.clip_grad_norm_(self.model.parameters(), self.config.gradientNorm, error_if_nonfinite=True)
                require(bool(torch.isfinite(norm)), 'Non-finite gradient norm')
                self.optimizer.step()
                self.optimizer_steps += 1
                epoch_loss += loss.item() * len(names)
                groups_seen += len(names)
            self.completed += 1
            self.losses.append(epoch_loss / groups_seen)
        self.execution.synchronize()
        self.model.eval()
        return self.losses[-epochs:] if epochs else []

    def checkpoint(self):
        self.execution.synchronize()
        state = dict(version=2 if self.execution.cuda else 1, binding=self.binding, inputHash=self.input_hash,
            schema=self.data['schema'], modelConfig=self.model_config.wire(), trainingConfig=self.config.wire(),
            completedEpochs=self.completed, optimizerSteps=self.optimizer_steps, losses=list(self.losses),
            model=self.model.state_dict(), optimizer=self.optimizer.state_dict(),
            torchRandomState=torch.get_rng_state(), groupOrderState=self.generator.get_state(),
            memoryRule='whole-sequence-zero-initialization-under-current-weights-v1')
        if self.execution.cuda:
            state.update(compute=copy.deepcopy(self.execution.binding), cudaRandomState=self.execution.random_state())
        return cpu_checkpoint_value(state)

    def restore(self, state):
        """Load ordinary numerical state. New data, device and learning rate are allowed.

        Matching inputs/settings reproduce continuation; changed inputs/settings are a
        new treatment. Metadata is available to the caller, not a permission system.
        Shape/optimizer incompatibilities raise their normal PyTorch errors.
        """
        self.model.load_state_dict(state['model'])
        self.optimizer.load_state_dict(copy.deepcopy(state['optimizer']))
        for group in self.optimizer.param_groups:
            group['lr'] = self.config.learningRate
        self.generator.set_state(state['groupOrderState'])
        torch.set_rng_state(state['torchRandomState'])
        self.execution.install_random_state(state.get('cudaRandomState'))
        self.completed = state['completedEpochs']
        self.optimizer_steps = state['optimizerSteps']
        self.losses = list(state['losses'])
        self.model.eval()

    def cpu_reference(self):
        """Copy frozen weights for export without moving or reseeding the live GPU learner."""
        with torch.random.fork_rng(devices=[]):
            model = SequenceModel(self.model_config)
            model.load_state_dict(cpu_checkpoint_value(self.model.state_dict()), strict=True)
        return model.eval()
