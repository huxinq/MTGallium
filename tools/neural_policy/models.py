"""Small PyTorch policies over the same factual bytes, with explicit external memory.

No game rules, target construction, episode identifiers or evidence identities enter
these functions. Zero byte tokens are padding; masks have True = included meaning.
"""
from dataclasses import dataclass, asdict
import math
import torch
from torch import nn
from torch.nn import functional as F


@dataclass(frozen=True)
class ModelConfig:
    architecture: str
    hiddenSize: int = 24
    byteWidth: int = 12
    contextEvents: int = 16
    attentionHeads: int = 4
    actionScoring: str = 'additive-tanh-v1'
    bytePooling: str = 'mean-v1'

    def __post_init__(self):
        if self.architecture not in ('CURRENT_VIEW', 'GRU', 'BOUNDED_ATTENTION'):
            raise ValueError('Unknown neural architecture')
        if any(type(value) is not int or value <= 0 for value in (self.hiddenSize, self.byteWidth, self.contextEvents)):
            raise ValueError('Model dimensions must be positive integers')
        if self.attentionHeads < 1 or self.hiddenSize % self.attentionHeads:
            raise ValueError('Attention heads must divide hidden size')
        if self.actionScoring not in ('additive-tanh-v1', 'dot-product-v1'):
            raise ValueError('Unknown state-action scoring contract')
        if self.bytePooling not in ('mean-v1', 'mean-max-v1'):
            raise ValueError('Unknown factual byte pooling contract')

    def wire(self):
        return asdict(self)


class ByteEncoder(nn.Module):
    """Learned local byte features with an explicitly selected, still lossy reduction."""
    def __init__(self, width: int, hidden: int, pooling: str = 'mean-v1'):
        super().__init__()
        if pooling not in ('mean-v1', 'mean-max-v1'):
            raise ValueError('Unknown factual byte pooling contract')
        self.pooling = pooling
        self.embedding = nn.Embedding(257, width, padding_idx=0)
        self.local = nn.Conv1d(width, hidden, kernel_size=3, padding=1)
        if pooling == 'mean-max-v1':
            self.combine = nn.Linear(2 * hidden, hidden)

    def forward(self, tokens, included):
        values = self.embedding(tokens) * included.unsqueeze(-1)
        values = torch.tanh(self.local(values.transpose(1, 2))).transpose(1, 2)
        weights = included.unsqueeze(-1).to(values.dtype)
        mean = (values * weights).sum(1) / weights.sum(1).clamp_min(1)
        if self.pooling == 'mean-v1':
            return mean
        # Maxima preserve sparse local signals without assigning strategic meaning
        # to any field. Current views, events and actions share this learned owner.
        maximum = values.masked_fill(~included.unsqueeze(-1), -torch.inf).amax(1)
        nonempty = included.any(1, keepdim=True)
        maximum = torch.where(nonempty, maximum, torch.zeros_like(maximum))
        combined = self.combine(torch.cat((mean, maximum), dim=-1))
        return torch.where(nonempty, combined, torch.zeros_like(combined))


class SequenceModel(nn.Module):
    def __init__(self, config: ModelConfig):
        super().__init__()
        self.config = config
        h = config.hiddenSize
        self.encoder = ByteEncoder(config.byteWidth, h, config.bytePooling)
        self.current = nn.Linear(h, h)
        self.action = nn.Linear(h, h)
        self.menu = nn.Linear(2, h, bias=False)
        if config.actionScoring == 'additive-tanh-v1':
            self.readout = nn.Linear(h, 1)
        if config.architecture == 'GRU':
            self.cell = nn.GRUCell(h, h)
        if config.architecture == 'BOUNDED_ATTENTION':
            self.query = nn.Linear(h, h)
            self.key = nn.Linear(h, h)
            self.value = nn.Linear(h, h)
            self.attention_output = nn.Linear(h, h)
            positions = torch.arange(config.contextEvents, dtype=torch.float32).unsqueeze(1)
            scales = torch.exp(-math.log(10000.0) * torch.arange(h, dtype=torch.float32).unsqueeze(0) / h)
            self.register_buffer('positions', torch.sin(positions * scales).unsqueeze(0))

    def initial(self, batch: int, *, device=None):
        cfg = self.config
        shape = (batch, cfg.contextEvents, cfg.hiddenSize) if cfg.architecture == 'BOUNDED_ATTENTION' else (
            batch, cfg.hiddenSize if cfg.architecture == 'GRU' else 1)
        return torch.zeros(shape, device=device), torch.zeros((batch, cfg.contextEvents), dtype=torch.bool, device=device)

    def update_encoded(self, event, valid, memory, history_mask):
        if self.config.architecture == 'CURRENT_VIEW':
            return memory, history_mask
        if self.config.architecture == 'GRU':
            return torch.where(valid.unsqueeze(-1), self.cell(event, memory), memory), history_mask
        shifted = torch.cat((memory[:, 1:], event.unsqueeze(1)), dim=1)
        included = torch.cat((history_mask[:, 1:], torch.ones_like(history_mask[:, :1])), dim=1)
        next_mask = (valid[:, None] & included) | (~valid[:, None] & history_mask)
        return torch.where(valid[:, None, None], shifted, memory), next_mask

    def update(self, event_tokens, event_mask, valid, memory, history_mask):
        event = self.encoder(event_tokens, event_mask)
        return self.update_encoded(event, valid, memory, history_mask)

    def score(self, view_tokens, view_mask, action_tokens, action_token_mask, candidate_mask, menu,
              memory=None, history_mask=None):
        batch, candidates, length = action_tokens.shape
        query = self.current(self.encoder(view_tokens, view_mask)) + self.menu(menu)
        action = self.action(self.encoder(action_tokens.reshape(batch * candidates, length),
                                         action_token_mask.reshape(batch * candidates, length))).reshape(batch, candidates, -1)
        if self.config.architecture == 'GRU':
            query = query + memory
        elif self.config.architecture == 'BOUNDED_ATTENTION':
            cfg = self.config
            h, heads = cfg.hiddenSize, cfg.attentionHeads
            keys = memory + self.positions
            # Always-visible sentinel prevents an empty history from producing all-masked attention.
            keys = torch.cat((torch.zeros_like(keys[:, :1]), keys), dim=1)
            included = torch.cat((torch.ones_like(history_mask[:, :1]), history_mask), dim=1)
            q = self.query(query).reshape(batch, 1, heads, h // heads).transpose(1, 2)
            k = self.key(keys).reshape(batch, cfg.contextEvents + 1, heads, h // heads).transpose(1, 2)
            v = self.value(keys).reshape(batch, cfg.contextEvents + 1, heads, h // heads).transpose(1, 2)
            attended = F.scaled_dot_product_attention(q, k, v, attn_mask=included[:, None, None, :], dropout_p=0.0)
            attended = attended.transpose(1, 2).reshape(batch, h)
            query = query + self.attention_output(attended)
        if self.config.actionScoring == 'dot-product-v1':
            scores = (query.unsqueeze(1) * action).sum(-1) / math.sqrt(self.config.hiddenSize)
        else:
            scores = self.readout(torch.tanh(query.unsqueeze(1) + action)).squeeze(-1)
        return scores.masked_fill(~candidate_mask, -1e9)

    def sequence(self, batch):
        """Whole sequences: reconstruct every prefix, then score at declared event positions.

        Future events are encoded independently, with no batch normalization, attention
        across episodes, or sequence-wide statistics. They cannot enter an earlier state.
        """
        event_tokens, event_mask, valid = batch['events'], batch['event_mask'], batch['event_valid']
        b, t, length = event_tokens.shape
        memory, history = self.initial(b, device=event_tokens.device)
        memories, histories = [memory], [history]
        encoded = self.encoder(event_tokens.reshape(b * t, length), event_mask.reshape(b * t, length)).reshape(b, t, -1)
        for position in range(t):
            memory, history = self.update_encoded(encoded[:, position], valid[:, position], memory, history)
            memories.append(memory)
            histories.append(history)
        memory_trace = torch.stack(memories, dim=1)
        history_trace = torch.stack(histories, dim=1)
        positions = batch['positions']
        lanes = torch.arange(b, device=positions.device).unsqueeze(1)
        selected_memory = memory_trace[lanes, positions]
        selected_history = history_trace[lanes, positions]
        _, decisions, candidates, action_length = batch['actions'].shape
        scores = self.score(batch['views'].flatten(0, 1), batch['view_mask'].flatten(0, 1),
                            batch['actions'].reshape(b * decisions, candidates, action_length),
                            batch['action_token_mask'].reshape(b * decisions, candidates, action_length),
                            batch['candidate_mask'].flatten(0, 1), batch['menu'].flatten(0, 1),
                            selected_memory.flatten(0, 1), selected_history.flatten(0, 1))
        return scores.reshape(b, decisions, candidates), memory_trace, history_trace


class CurrentScore(nn.Module):
    def __init__(self, model):
        super().__init__(); self.model = model

    def forward(self, view_tokens, view_mask, action_tokens, action_token_mask, candidate_mask, menu):
        return self.model.score(view_tokens, view_mask, action_tokens, action_token_mask, candidate_mask, menu)


class GruScore(CurrentScore):
    def forward(self, view_tokens, view_mask, action_tokens, action_token_mask, candidate_mask, menu, memory):
        return self.model.score(view_tokens, view_mask, action_tokens, action_token_mask, candidate_mask, menu, memory)


class AttentionScore(CurrentScore):
    def forward(self, view_tokens, view_mask, action_tokens, action_token_mask, candidate_mask, menu, memory, history_mask):
        return self.model.score(view_tokens, view_mask, action_tokens, action_token_mask, candidate_mask, menu, memory, history_mask)


class GruUpdate(CurrentScore):
    def forward(self, event_tokens, event_mask, event_valid, memory):
        event = self.model.encoder(event_tokens, event_mask)
        return torch.where(event_valid[:, None], self.model.cell(event, memory), memory)


class AttentionUpdate(CurrentScore):
    def forward(self, event_tokens, event_mask, event_valid, memory, history_mask):
        return self.model.update(event_tokens, event_mask, event_valid, memory, history_mask)
