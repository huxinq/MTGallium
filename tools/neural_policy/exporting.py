"""Bounded ONNX export and numerical qualification of frozen PyTorch weights."""
import importlib.metadata
import json
import sys
from pathlib import Path
import numpy as np
import onnx
import onnxruntime as ort
import torch
from .models import CurrentScore, GruScore, AttentionScore, GruUpdate, AttentionUpdate
from .data import pack, require

ATOL = 2e-5
RTOL = 2e-5
SCORE_INPUTS = ['view_tokens', 'view_mask', 'action_tokens', 'action_token_mask', 'candidate_mask', 'menu']


def environment():
    """Record installed versions. Numerical parity, not a version whitelist, tests the export."""
    return {name: importlib.metadata.version(name)
            for name in ('torch', 'numpy', 'onnx', 'onnxruntime', 'onnxscript')}


def session(path):
    options = ort.SessionOptions()
    options.intra_op_num_threads = options.inter_op_num_threads = 1
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    options.add_session_config_entry('session.intra_op.allow_spinning', '0')
    options.add_session_config_entry('session.inter_op.allow_spinning', '0')
    return ort.InferenceSession(str(path), sess_options=options, providers=['CPUExecutionProvider'])


def numpy(value):
    return value.detach().cpu().numpy()


def assert_close(expected, actual):
    expected = numpy(expected) if isinstance(expected, torch.Tensor) else expected
    np.testing.assert_allclose(expected, actual, atol=ATOL, rtol=RTOL)
    return float(np.max(np.abs(expected - actual), initial=0))


@torch.random.fork_rng(devices=[])
def export_models(learner, directory: Path, *, maximum_batch=8):
    """Export current production-ABI graphs and test their numerical agreement."""
    require(maximum_batch > 0, 'maximum_batch must be positive')
    model, cfg, schema = learner.cpu_reference(), learner.model_config, learner.data['schema']
    directory.mkdir(parents=True, exist_ok=False)
    torch.random.default_generator.manual_seed(991)
    graphs = {}
    for single in ((True, False) if maximum_batch > 1 else (True,)):
        b = 1 if single else 2
        batch = None if single else torch.export.Dim('batch', min=2, max=maximum_batch) if maximum_batch > 2 else None
        def dims(**extra):
            return ({0: batch} if batch is not None else {}) | {int(key): value for key, value in extra.items()}
        # Tensor padding to at least four tokens/two candidate slots avoids singleton
        # export specialization. Masks preserve one-byte and single-action requests.
        vl = torch.export.Dim('view_bytes', min=4, max=schema['maximumViewBytes'])
        al = torch.export.Dim('action_bytes', min=4, max=schema['maximumActionBytes'])
        el = torch.export.Dim('event_bytes', min=4, max=schema['maximumEventBytes'])
        ac = torch.export.Dim('candidates', min=2, max=schema['maximumCandidates']) if schema['maximumCandidates'] > 2 else None
        candidate_axes = {'1': ac} if ac is not None else {}
        examples = 3 if ac is not None else 2
        vt = torch.randint(1, 257, (b, 80))
        at = torch.randint(1, 257, (b, examples, 40))
        args = [vt, torch.ones_like(vt, dtype=torch.bool), at, torch.ones_like(at, dtype=torch.bool),
                torch.ones((b, examples), dtype=torch.bool), torch.ones((b, 2))]
        dynamic = [dims(**{'1': vl}), dims(**{'1': vl}), dims(**(candidate_axes | {'2': al})),
                   dims(**(candidate_axes | {'2': al})), dims(**candidate_axes), dims()]
        memory, history = model.initial(b)
        names = list(SCORE_INPUTS)
        if cfg.architecture != 'CURRENT_VIEW':
            args.append(memory); dynamic.append(dims()); names.append('memory')
        if cfg.architecture == 'BOUNDED_ATTENTION':
            args.append(history); dynamic.append(dims()); names.append('history_mask')
        wrapper = {'CURRENT_VIEW': CurrentScore, 'GRU': GruScore, 'BOUNDED_ATTENTION': AttentionScore}[cfg.architecture](model).eval()
        suffix = 'single' if single else 'batch'
        def export(role, module, arguments, input_names, output_names, shapes):
            filename = role + '-' + suffix + '.onnx'
            target = directory / filename
            torch.onnx.export(module, tuple(arguments), target, input_names=input_names, output_names=output_names,
                dynamic_shapes=tuple(shapes), dynamo=True, opset_version=18, external_data=False,
                verify=True, report=True, artifacts_dir=str(directory))
            graph = onnx.load(target, load_external_data=False)
            onnx.checker.check_model(graph)
            require(all(t.data_location != onnx.TensorProto.EXTERNAL for t in graph.graph.initializer), 'External graph data is not admitted')
            loaded = session(target)
            require([x.name for x in loaded.get_inputs()] == input_names, 'Export changed the declared input ABI')
            actual = loaded.run(None, dict(zip(input_names, map(numpy, arguments))))
            with torch.inference_mode():
                expected = module(*arguments)
                expected = expected if isinstance(expected, tuple) else (expected,)
                for x, y in zip(expected, actual):
                    if x.dtype == torch.bool:
                        np.testing.assert_array_equal(numpy(x), y)
                    else:
                        assert_close(x, y)
            graphs[role + ('Single' if single else 'Batch')] = filename
        export('score', wrapper, args, names, ['scores'], dynamic)
        if cfg.architecture != 'CURRENT_VIEW':
            et = torch.randint(1, 257, (b, 64))
            args = [et, torch.ones_like(et, dtype=torch.bool), torch.ones(b, dtype=torch.bool), memory]
            names = ['event_tokens', 'event_mask', 'event_valid', 'memory']
            dynamic = [dims(**{'1': el}), dims(**{'1': el}), dims(), dims()]
            outputs = ['next_memory']
            if cfg.architecture == 'BOUNDED_ATTENTION':
                args.append(history); names.append('history_mask'); dynamic.append(dims()); outputs.append('next_history_mask')
            wrapper = (GruUpdate if cfg.architecture == 'GRU' else AttentionUpdate)(model).eval()
            export('update', wrapper, args, names, outputs, dynamic)
    descriptor = dict(schema=schema, config=dict(architecture=cfg.architecture,
        hiddenSize=cfg.hiddenSize, contextEvents=cfg.contextEvents), maximumBatch=maximum_batch, graphs=graphs)
    (directory / 'model.json').write_text(json.dumps(descriptor, sort_keys=True, separators=(',', ':'), allow_nan=False))
    (directory / 'training.json').write_text(json.dumps(dict(model=cfg.wire(), training=learner.config.wire(),
        inputDataSha256=learner.input_hash, environment=environment(), compute=learner.execution.binding),
        sort_keys=True, indent=2, allow_nan=False))
    return descriptor


def parity_cases(learner, directory: Path):
    """Carry each runtime's own state over native tensors; expected memory is never an input to ORT."""
    model, cfg = learner.cpu_reference(), learner.model_config
    score = session(directory / 'score-single.onnx')
    update = None if cfg.architecture == 'CURRENT_VIEW' else session(directory / 'update-single.onnx')
    cases = []
    maximum_score_error = maximum_memory_error = 0.0
    mismatches = 0
    with torch.inference_mode():
        for episode in learner.data['episodes']:
            memory, history = model.initial(1)
            actual_memory, actual_history = numpy(memory).copy(), numpy(history).copy()
            decisions = []
            for position in range(len(episode['events']) + 1):
                if position:
                    tokens = episode['events'][position - 1]
                    event = torch.tensor([tokens + [0] * max(0, 4 - len(tokens))])
                    mask = event != 0
                    valid = torch.ones(1, dtype=torch.bool)
                    memory, history = model.update(event, mask, valid, memory, history)
                    if update is not None:
                        inputs = dict(event_tokens=numpy(event), event_mask=numpy(mask), event_valid=numpy(valid), memory=actual_memory)
                        if cfg.architecture == 'BOUNDED_ATTENTION': inputs['history_mask'] = actual_history
                        output = update.run(None, inputs)
                        actual_memory = output[0]
                        if cfg.architecture == 'BOUNDED_ATTENTION': actual_history = output[1]
                        maximum_memory_error = max(maximum_memory_error, assert_close(memory, actual_memory))
                        np.testing.assert_array_equal(numpy(history), actual_history)
                for decision in (d for d in episode['decisions'] if d['eventPosition'] == position):
                    # Pack one frame, adding only transport padding. It does not add candidates.
                    value = decision['input']
                    view = torch.tensor([value['view'] + [0] * max(0, 4 - len(value['view']))])
                    width = max(4, max(map(len, value['actions'])))
                    actions = torch.tensor([a + [0] * (width - len(a)) for a in value['actions']] +
                                           [[0] * width] * max(0, 2 - len(value['actions'])))
                    candidate = torch.arange(actions.shape[0]).unsqueeze(0) < len(value['actions'])
                    actions = actions.unsqueeze(0)
                    menu = torch.tensor([[value['rulesExhaustive'], value['profileExhaustive']]], dtype=torch.float32)
                    args = [view, view != 0, actions, actions != 0, candidate, menu]
                    inputs = dict(zip(SCORE_INPUTS, map(numpy, args)))
                    if update is not None: inputs['memory'] = actual_memory
                    if cfg.architecture == 'BOUNDED_ATTENTION': inputs['history_mask'] = actual_history
                    expected = model.score(*args, memory, history)
                    actual = score.run(None, inputs)[0]
                    maximum_score_error = max(maximum_score_error, assert_close(expected, actual))
                    np.testing.assert_array_equal(actual, score.run(None, inputs)[0])
                    n = len(value['actions'])
                    mismatches += int(expected[0, :n].argmax() != actual[0, :n].argmax())
                    decisions.append(dict(eventPosition=position, input=value, scores=numpy(expected)[0, :n].tolist(),
                        memory=numpy(memory)[0].reshape(-1).tolist(), historyMask=numpy(history)[0].tolist()
                        if cfg.architecture == 'BOUNDED_ATTENTION' else []))
            cases.append(dict(episodeId=episode['episodeId'], events=episode['events'], decisions=decisions))
    require(mismatches == 0, 'ONNX chosen-action parity failed')
    result = dict(atol=ATOL, rtol=RTOL, maximumScoreError=maximum_score_error, maximumMemoryError=maximum_memory_error,
                  choiceMismatches=mismatches, episodes=cases)
    if learner.execution.cuda:
        result['trainingDeviceParity'] = training_device_parity(learner, cases)
    (directory / 'parity.json').write_text(json.dumps(result, separators=(',', ':'), allow_nan=False))
    return {k: v for k, v in result.items() if k != 'episodes'}


def training_device_parity(learner, cases):
    """Compare independently propagated CUDA state with the frozen CPU reference."""
    model, device = learner.model.eval(), learner.execution.device
    score_error = memory_error = 0.0
    mismatches = decisions = 0
    with torch.inference_mode():
        for case in cases:
            memory, history = model.initial(1, device=device)
            for position in range(len(case['events']) + 1):
                if position:
                    tokens = case['events'][position - 1]
                    event = torch.tensor([tokens + [0] * max(0, 4 - len(tokens))], device=device)
                    valid = torch.ones(1, dtype=torch.bool, device=device)
                    memory, history = model.update(event, event != 0, valid, memory, history)
                for row in (r for r in case['decisions'] if r['eventPosition'] == position):
                    value = row['input']
                    view = torch.tensor([value['view'] + [0] * max(0, 4 - len(value['view']))], device=device)
                    width = max(4, max(map(len, value['actions'])))
                    action_rows = [a + [0] * (width - len(a)) for a in value['actions']]
                    action_rows += [[0] * width] * max(0, 2 - len(action_rows))
                    actions = torch.tensor([action_rows], device=device)
                    count = len(value['actions'])
                    candidates = torch.arange(actions.shape[1], device=device).unsqueeze(0) < count
                    menu = torch.tensor([[value['rulesExhaustive'], value['profileExhaustive']]], dtype=torch.float32, device=device)
                    args = (view, view != 0, actions, actions != 0, candidates, menu, memory, history)
                    scores = model.score(*args)
                    require(torch.equal(scores, model.score(*args)), 'Repeated CUDA scoring changed output')
                    expected = np.asarray(row['scores'], dtype=np.float32)
                    actual = numpy(scores)[0, :count]
                    score_error = max(score_error, assert_close(expected, actual))
                    memory_error = max(memory_error, assert_close(np.asarray(row['memory'], dtype=np.float32), numpy(memory)[0].reshape(-1)))
                    if learner.model_config.architecture == 'BOUNDED_ATTENTION':
                        np.testing.assert_array_equal(np.asarray(row['historyMask']), numpy(history)[0])
                    mismatches += int(expected.argmax() != actual.argmax())
                    decisions += 1
    learner.execution.synchronize()
    require(mismatches == 0, 'CUDA-to-CPU chosen-action parity failed')
    return dict(device='CUDA', decisions=decisions, choiceMismatches=mismatches,
                maximumScoreError=score_error, maximumMemoryError=memory_error, atol=ATOL, rtol=RTOL,
                scope='Frozen CUDA model versus CPU reference, each propagating its own memory; not cross-device training identity.')
