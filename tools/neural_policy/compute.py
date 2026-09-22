"""A normal PyTorch device, not hardware admission or a resource scheduler."""
import os
import torch


class LearnerDevice:
    def __init__(self, device='cpu', *, deterministic=True):
        self.device = torch.device(device)
        if self.device.type == 'cuda' and not torch.cuda.is_available():
            raise RuntimeError('CUDA was requested but is unavailable; no CPU fallback')
        if deterministic:
            os.environ.setdefault('CUBLAS_WORKSPACE_CONFIG', ':4096:8')
        torch.use_deterministic_algorithms(deterministic)
        self.binding = {'device': str(self.device), 'deterministic': deterministic,
                        'torch': str(torch.__version__)}
        if self.cuda:
            torch.cuda.init()
            torch.cuda.reset_peak_memory_stats(self.device)

    @property
    def cuda(self):
        return self.device.type == 'cuda'

    def seed(self, seed):
        torch.random.default_generator.manual_seed(seed)
        if self.cuda:
            with torch.cuda.device(self.device):
                torch.cuda.manual_seed(seed)

    def synchronize(self):
        if self.cuda:
            torch.cuda.synchronize(self.device)

    def optimizer(self, model, learning_rate):
        return torch.optim.Adam(model.parameters(), lr=learning_rate)

    def random_state(self):
        return torch.cuda.get_rng_state(self.device).clone() if self.cuda else None

    def install_random_state(self, value):
        if self.cuda and value is not None:
            torch.cuda.set_rng_state(value, self.device)

    def peaks(self):
        if not self.cuda:
            return None
        self.synchronize()
        return dict(allocatedBytes=torch.cuda.max_memory_allocated(self.device),
                    reservedBytes=torch.cuda.max_memory_reserved(self.device))


def cpu_checkpoint_value(value):
    """Store tensors on CPU so a saved model does not prescribe its next device."""
    if isinstance(value, torch.Tensor):
        return value.detach().cpu().clone()
    if isinstance(value, dict):
        return {key: cpu_checkpoint_value(item) for key, item in value.items()}
    if isinstance(value, list):
        return [cpu_checkpoint_value(item) for item in value]
    if isinstance(value, tuple):
        return tuple(cpu_checkpoint_value(item) for item in value)
    return value
