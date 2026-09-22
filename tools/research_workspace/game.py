"""Live games from Python through a shared JVM.

Use a Session for several games, or a standalone Game as a context manager.
Python policy objects belong to the experiment; a factual world fork does not
implicitly clone their state. Native search sessions are forked with the world.
"""
from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, field
import json
import subprocess
import threading
import time
from typing import Any

from . import jvm_command


class ResearchError(RuntimeError):
    """The JVM reported an error. An action is never automatically retried."""


@dataclass(frozen=True)
class Action:
    index: int
    view: dict
    choice: dict
    search: dict | None = field(default=None, compare=False)

    @property
    def family(self) -> str:
        return self.choice['operationFamily']

    @property
    def label(self) -> str:
        return self.choice['display']['label']

    @property
    def payload(self) -> dict:
        return self.choice['canonicalPayload']


@dataclass(frozen=True)
class Decision:
    index: int
    actor: str
    information: dict
    actions: tuple[Action, ...]
    rules_exhaustive: bool
    profile_exhaustive: bool
    features: list | None = None
    factual: dict | None = None


class Session:
    """A synchronous child JVM shared by this context's games and numerical calls."""
    def __init__(self, *, build: bool = True, java_options: Sequence[str] = ()):
        command = jvm_command([], main_class='org.mtgallium.research.workbench.PythonResearch',
                              build=build, java_options=java_options)
        self._process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                         text=True, encoding='utf-8', bufsize=1)
        self._lock = threading.RLock()
        self._closed = False

    @property
    def pid(self) -> int:
        return self._process.pid

    def _call(self, command: str, **arguments: Any) -> Any:
        message = json.dumps(dict(command=command, **arguments), allow_nan=False, separators=(',', ':'))
        with self._lock:
            if self._closed:
                raise RuntimeError('Research session is closed')
            try:
                self._process.stdin.write(message + '\n')
                self._process.stdin.flush()
                line = self._process.stdout.readline()
                if not line:
                    raise ConnectionError('JVM connection ended; the last operation may have executed. It was not retried.')
                response = json.loads(line)
                if not isinstance(response, dict) or not ('value' in response or 'error' in response):
                    raise ConnectionError('Unexpected JVM response; the operation was not retried')
            except BaseException:
                self.close()
                raise
            if 'error' in response:
                raise ResearchError(f"{response['error']}: {response['message']}")
            return response['value']

    def game(self, decks: Sequence[Mapping[str, int]], *, seed: int = 1,
             policies: Sequence[str] = ('random', 'random'), **settings: Any) -> Game:
        plan = {''.join(word if i == 0 else word[:1].upper() + word[1:]
                        for i, word in enumerate(key.split('_'))): value
                for key, value in settings.items()}
        plan.update(decks=list(decks), seed=seed, policies=list(policies))
        result = self._call('create', plan=plan)
        return Game._from(self, result['game'], tuple(policies))

    def fit(self, roots: Sequence[Mapping], ridge: float = .001, *, weights: Mapping | None = None) -> dict:
        """The existing root-centered kernel objective; caller-supplied targets and groups."""
        return self._call('fit', roots=list(roots), ridge=ridge, weights=weights)

    def predict(self, model: Mapping, menus: Sequence[Sequence[Mapping]]) -> list[list[float]]:
        return self._call('predict', model=model, menus=list(menus))

    def close(self) -> None:
        with self._lock:
            if self._closed:
                return
            self._closed = True
            try:
                self._process.stdin.close()
            except (BrokenPipeError, OSError):
                pass
            try:
                self._process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self._process.terminate()
                try:
                    self._process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    self._process.kill()
                    self._process.wait()
            finally:
                self._process.stdout.close()

    def __enter__(self) -> Session:
        return self

    def __exit__(self, *_: Any) -> None:
        self.close()


class Game:
    """A live game. Use Session.game to share a JVM across independent games."""
    def __init__(self, decks: Sequence[Mapping[str, int]], *, seed: int = 1,
                 policies: Sequence[str] = ('random', 'random'), build: bool = True,
                 java_options: Sequence[str] = (), **settings: Any):
        session = Session(build=build, java_options=java_options)
        try:
            game = session.game(decks, seed=seed, policies=policies, **settings)
        except BaseException:
            session.close()
            raise
        self.__dict__.update(game.__dict__)
        self._owns_session = True

    @classmethod
    def _from(cls, session: Session, handle: int, policies: tuple[str, ...]) -> Game:
        game = object.__new__(cls)
        game.session, game._handle, game._policies = session, handle, policies
        game._owns_session = False
        game._closed = False
        return game

    def _call(self, command: str, **arguments: Any) -> Any:
        if self._closed:
            raise RuntimeError('Game is closed')
        return self.session._call(command, game=self._handle, **arguments)

    def status(self) -> dict:
        return self._call('status')

    def information(self, player: str) -> dict:
        """Only the named player's represented information, including remembered history."""
        return self._call('information', player=player)

    def value_features(self, player: str | None = None) -> dict[str, float]:
        """Sparse linear-value features, from the named player's or acting player's view."""
        return self._call('value-features', **({} if player is None else {'player': player}))

    def state(self) -> dict:
        """Privileged referee snapshot for research inspection, not a policy input."""
        return self._call('state')

    def decision(self, *, kernel: bool = False, factual: bool = False,
                 from_event: int = 0, schema: Mapping | None = None,
                 view: Mapping | None = None) -> Decision | None:
        arguments = dict(kernel=kernel, factual=factual, fromEvent=from_event)
        if schema is not None:
            arguments['schema'] = dict(schema)
        if view is not None:
            arguments['view'] = dict(view)
        value = self._call('decision', **arguments)
        if value['terminal']:
            return None
        actions = tuple(Action(value['index'], value['view'], choice)
                        for choice in value['information']['candidates'])
        return Decision(value['index'], value['actor'], value['information'], actions,
                        value['rulesExhaustive'], value['profileExhaustive'],
                        value.get('features'), value.get('factual'))

    def select(self, policy: str = 'heuristic', *, seed: int | None = None) -> Action:
        """Choose without advancing. Action.search contains estimates when search ran."""
        value = self._call('select', policy=policy, seed=seed)
        return Action(value['index'], value['view'], value['choice'], value.get('search'))

    def step(self, action: Action | int) -> dict:
        if type(action) is int:
            decision = self.decision()
            if decision is None:
                raise ValueError('Cannot step a terminal game')
            if action < 0 or action >= len(decision.actions):
                raise IndexError('Action index is outside the current menu')
            action = decision.actions[action]
        if not isinstance(action, Action):
            raise TypeError('step takes an Action or an index in the current menu')
        return self._call('step', index=action.index, view=action.view, choice=action.choice)

    def fork(self) -> Game:
        """Copy the factual world and native search memory, not external Python policy state."""
        value = self._call('fork')
        return Game._from(self.session, value['game'], self._policies)

    def play(self, policies: Sequence[str | Callable[[Decision], Action | int]] | Mapping | None = None,
             *, decision_limit: int | None = 2048, seconds: float | None = None,
             record: Callable[[dict], None] | None = None, kernel: bool = False,
             factual: bool = False) -> dict:
        """Python callbacks receive a decision, never the game or referee state.

        All-native play without recording stays inside the JVM. Supplying Python
        callbacks or a record sink uses explicit decision/step calls. Exceptions
        propagate and accepted transitions are not rolled back or retried.
        """
        policies = self._policies if policies is None else policies
        if isinstance(policies, Mapping):
            seats = {f'p{i}' for i in range(len(self._policies))}
            if set(policies) - seats:
                raise ValueError('Unknown policy seat')
            policies = tuple(policies.get(f'p{i}', fallback) for i, fallback in enumerate(self._policies))
        else:
            policies = tuple(policies)
        if len(policies) != len(self._policies):
            raise ValueError('Supply one policy per player')
        if decision_limit is not None and (type(decision_limit) is not int or decision_limit < 0):
            raise ValueError('decision_limit must be nonnegative or None')
        if seconds is not None and not (0 < seconds < float('inf')):
            raise ValueError('seconds must be positive and finite or None')
        if record is None and all(isinstance(policy, str) for policy in policies):
            return self._call('play', policies=list(policies), maximumDecisions=decision_limit, maximumSeconds=seconds)
        start = time.monotonic()
        status = self.status()
        first = status['index']
        while True:
            count = status['index'] - first
            if status['terminal']:
                return dict(status='TERMINAL', decisions=count, payoffs=status['payoffs'])
            if decision_limit is not None and count >= decision_limit:
                return dict(status='DECISION_LIMIT', decisions=count, payoffs=None)
            if seconds is not None and time.monotonic() - start >= seconds:
                return dict(status='TIME_LIMIT', decisions=count, payoffs=None)
            policy = policies[int(status['actor'][1:])]
            if isinstance(policy, str):
                action = self.select(policy)
                decision = self.decision(view=action.view, kernel=kernel, factual=factual)
            else:
                decision = self.decision(kernel=kernel, factual=factual)
                selected = policy(decision)
                if type(selected) is int:
                    if selected < 0 or selected >= len(decision.actions):
                        raise IndexError('Policy selected an index outside its menu')
                    action = decision.actions[selected]
                else:
                    action = selected
            if not isinstance(action, Action) or action.index != decision.index:
                raise ValueError('Policy action must belong to the current decision')
            # Rebind an equivalent choice to the supplied menu, so recording and encodings
            # keep that menu's view even when a policy consulted another native view.
            action = next((candidate for candidate in decision.actions if candidate.choice == action.choice), None)
            if action is None:
                raise ValueError('Policy action is absent from the supplied decision menu')
            result = self.step(action)
            status = result['status']
            if record is not None:
                row = result['decision']
                if kernel:
                    row['features'] = decision.features
                if factual:
                    row['factual'] = decision.factual
                record(row)

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        try:
            if not self.session._closed:
                self.session._call('close', game=self._handle)
        finally:
            if self._owns_session:
                self.session.close()

    def __enter__(self) -> Game:
        return self

    def __exit__(self, *_: Any) -> None:
        self.close()
