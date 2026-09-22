import test from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  DEFAULT_PROGRESS_POLICY,
  atomicWriteJson,
  deriveRunnerEta,
  parseEnvironmentFile,
  parseLaunchArgs,
  parseProgressArgs,
  progressNotificationDecision,
  startProgressMonitor,
  systemdRunArguments,
  validateProgressUpdate,
  workloadEta,
} from '../durable-run.mjs'

const SCRIPT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'durable-run.mjs')
const PROGRESS_CANARY = path.resolve(path.dirname(fileURLToPath(import.meta.url)), 'progress-canary.mjs')
const THREAD = '12345678-1234-4234-9234-123456789abc'

function fixture(command, options = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mtgallium-durable-run-test-'))
  const runDirectory = path.join(root, 'run')
  fs.mkdirSync(runDirectory, { mode: 0o700 })
  const progressPolicy = { ...DEFAULT_PROGRESS_POLICY, ...(options.progressPolicy || {}) }
  const request = {
    schemaVersion: 2,
    runId: 'test-run',
    name: 'test run',
    unit: 'mtgallium-run-test.service',
    createdAt: new Date().toISOString(),
    runDirectory,
    stateRoot: root,
    workingDirectory: root,
    command: { argv: command },
    environment: { PATH: process.env.PATH },
    logPath: path.join(runDirectory, 'command.log'),
    progressFile: path.join(runDirectory, 'progress.json'),
    progressPolicy,
    outputPaths: [path.join(root, 'result.txt')],
    initialEstimatedSeconds: options.initialEstimatedSeconds ?? null,
    codexThreadId: options.thread || null,
    notificationConfigSource: options.ntfy ? 'test fixture' : 'unconfigured',
  }
  const completionConfig = {
    schemaVersion: 2,
    ntfy: options.ntfy || { url: null, token: null, curlPath: '/usr/bin/false' },
    wake: options.wake || { requested: false, supported: false, reason: 'test disabled', codexPath: null },
  }
  const status = {
    schemaVersion: 2,
    runId: request.runId,
    name: request.name,
    unit: request.unit,
    phase: 'starting',
    createdAt: request.createdAt,
    startedAt: null,
    commandFinishedAt: null,
    completedAt: null,
    experiment: { state: 'pending', exitCode: null, signal: null, spawnError: null },
    progress: {
      state: 'unreported',
      file: request.progressFile,
      current: null,
      percent: null,
      eta: { state: 'unavailable', source: null, reason: 'no-progress-reported' },
      observedAt: null,
      acceptedUpdates: 0,
      ignoredUpdates: 0,
      lastIgnored: null,
      notificationPolicy: {
        milestonePercent: progressPolicy.milestonePercent,
        minimumIntervalSeconds: progressPolicy.minimumNotificationIntervalMs / 1000,
        highestNotifiedMilestone: 0,
        lastNotifiedPhase: null,
        lastNotifiedEtaAt: null,
        lastNotifiedAt: null,
      },
    },
    notification: {
      configured: Boolean(completionConfig.ntfy.url),
      state: completionConfig.ntfy.url ? 'pending' : 'unavailable',
      reason: completionConfig.ntfy.url ? null : 'unconfigured for test',
      attemptedAt: null,
      exitCode: null,
      start: {
        state: completionConfig.ntfy.url ? 'pending' : 'unavailable',
        reason: completionConfig.ntfy.url ? null : 'unconfigured for test',
        attemptedAt: null,
        exitCode: null,
      },
      progress: {
        state: completionConfig.ntfy.url ? 'idle' : 'unavailable',
        reason: completionConfig.ntfy.url ? null : 'unconfigured for test',
        attemptedCount: 0,
        succeededCount: 0,
        failedCount: 0,
        last: null,
      },
      terminal: {
        state: completionConfig.ntfy.url ? 'pending' : 'unavailable',
        reason: completionConfig.ntfy.url ? null : 'unconfigured for test',
        attemptedAt: null,
        exitCode: null,
      },
    },
    wake: {
      configured: Boolean(options.thread && completionConfig.wake.supported),
      mechanism: completionConfig.wake.supported ? 'codex queue --thread' : null,
      targetThreadId: options.thread || null,
      state: options.thread && completionConfig.wake.supported ? 'pending' : 'unavailable',
      reason: options.thread && completionConfig.wake.supported ? null : 'unconfigured for test',
      attemptedAt: null,
      exitCode: null,
    },
  }
  atomicWriteJson(path.join(runDirectory, 'request.json'), request)
  atomicWriteJson(path.join(runDirectory, 'completion-config.json'), completionConfig)
  atomicWriteJson(path.join(runDirectory, 'status.json'), status)
  return { root, runDirectory }
}

test('launch parsing preserves command arguments without shell reconstruction', () => {
  const parsed = parseLaunchArgs([
    '--name', 'long neural run', '--workdir', '/repo', '--output', 'result with spaces.json',
    '--env', 'CUDA_VISIBLE_DEVICES', '--estimated-seconds', '120',
    '--', 'just', 'neural-run', 'ARGS=a b;$(not-a-shell)',
  ])
  assert.deepEqual(parsed.command, ['just', 'neural-run', 'ARGS=a b;$(not-a-shell)'])
  assert.deepEqual(parsed.outputs, ['result with spaces.json'])
  assert.deepEqual(parsed.envNames, ['CUDA_VISIBLE_DEVICES'])
  assert.equal(parsed.estimatedSeconds, 120)
  assert.throws(() => parseLaunchArgs(['--name', 'run', '--thread', 'not-a-uuid', '--', 'true']), /UUID/)
  assert.throws(() => parseLaunchArgs(['--name', 'run', '--estimated-seconds', '0', '--', 'true']), /greater than zero/)
})

test('systemd launch is a transient exec service carrying only the run directory', () => {
  const request = {
    unit: 'mtgallium-run-canary.service',
    name: 'canary',
    runId: 'canary-id',
    workingDirectory: '/repo',
    runDirectory: '/state/canary-id',
  }
  const args = systemdRunArguments(request, { node: '/node', script: '/repo/durable-run.mjs' })
  assert.ok(args.includes('--user'))
  assert.ok(args.includes('--service-type=exec'))
  assert.ok(args.includes('--property=KillMode=control-group'))
  assert.deepEqual(args.slice(-4), ['/repo/durable-run.mjs', '_execute', '--run-dir', '/state/canary-id'])
})

test('environment files support local ntfy configuration without shell evaluation', () => {
  assert.deepEqual(parseEnvironmentFile([
    '# local only',
    'MTGALLIUM_NTFY_URL="https://example.invalid/private-topic"',
    "MTGALLIUM_NTFY_TOKEN='tk_example'",
  ].join('\n')), {
    MTGALLIUM_NTFY_URL: 'https://example.invalid/private-topic',
    MTGALLIUM_NTFY_TOKEN: 'tk_example',
  })
  assert.throws(() => parseEnvironmentFile('curl dangerous'), /NAME=value/)
})

test('progress helper publishes one validated atomic state', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mtgallium-progress-helper-'))
  const progressFile = path.join(root, 'progress.json')
  const written = spawnSync(process.execPath, [
    SCRIPT, 'progress', '--file', progressFile,
    '--completed', '3', '--total', '10', '--unit', 'seeds', '--phase', 'training', '--detail', 'seed 3253',
  ], { encoding: 'utf8' })
  assert.equal(written.status, 0, written.stderr)
  const progress = validateProgressUpdate(JSON.parse(fs.readFileSync(progressFile, 'utf8')))
  assert.equal(progress.completed, 3)
  assert.equal(progress.total, 10)
  assert.equal(progress.phase, 'training')
  assert.deepEqual(fs.readdirSync(root), ['progress.json'])
  assert.equal(parseProgressArgs(['--file', progressFile, '--phase', 'done']).progress.phase, 'done')
})

test('ETA stays unavailable until enough observed progress and labels its source when derived', () => {
  const progress = validateProgressUpdate({
    schemaVersion: 1,
    updatedAt: '2026-09-01T10:00:06.000Z',
    completed: 30,
    total: 100,
    unit: 'decisions',
  }, { observedAtMs: Date.parse('2026-09-01T10:00:06.000Z') })
  const samples = [
    { completed: 10, total: 100, observedAtMs: 0 },
    { completed: 20, total: 100, observedAtMs: 3_000 },
    { completed: 30, total: 100, observedAtMs: 6_000 },
  ]
  const etaPolicy = { ...DEFAULT_PROGRESS_POLICY, etaMinimumElapsedMs: 4_000 }
  assert.equal(deriveRunnerEta(samples.slice(0, 2), progress, etaPolicy).state, 'unavailable')
  const eta = deriveRunnerEta(samples, progress, etaPolicy)
  assert.equal(eta.state, 'available')
  assert.equal(eta.source, 'runner')
  assert.equal(eta.basis.observationCount, 3)
  assert.equal(eta.estimatedCompletionAt, '1970-01-01T00:00:27.000Z')
  const supplied = workloadEta(validateProgressUpdate({
    schemaVersion: 1,
    updatedAt: '2026-09-01T10:00:00.000Z',
    remainingSeconds: 120,
    phase: 'training',
  }, { observedAtMs: Date.parse('2026-09-01T10:00:00.000Z') }))
  assert.equal(supplied.source, 'workload')
  assert.equal(supplied.kind, 'remaining-duration')
  assert.equal(supplied.estimatedCompletionAt, '2026-09-01T10:02:00.000Z')
})

test('milestones, phase, ETA threshold, and minimum interval prevent notification spam', () => {
  const progress = {
    current: { phase: 'training' },
    percent: 21,
    eta: { state: 'available', source: 'runner', estimatedCompletionAt: '2026-09-01T11:00:00.000Z' },
    notificationPolicy: {
      highestNotifiedMilestone: 0,
      lastNotifiedPhase: null,
      lastNotifiedEtaAt: null,
      lastNotifiedAt: '2026-09-01T10:00:00.000Z',
    },
  }
  assert.equal(progressNotificationDecision(progress, Date.parse('2026-09-01T10:00:29.000Z')), null)
  const first = progressNotificationDecision(progress, Date.parse('2026-09-01T10:00:30.000Z'))
  assert.deepEqual(first.triggers.sort(), ['eta-available', 'milestone', 'phase'])
  progress.notificationPolicy = {
    highestNotifiedMilestone: 20,
    lastNotifiedPhase: 'training',
    lastNotifiedEtaAt: '2026-09-01T11:00:00.000Z',
    lastNotifiedAt: '2026-09-01T10:00:30.000Z',
  }
  progress.percent = 19
  assert.equal(progressNotificationDecision(progress, Date.parse('2026-09-01T10:02:00.000Z')), null)
  progress.percent = 21
  assert.equal(progressNotificationDecision(progress, Date.parse('2026-09-01T10:02:00.000Z')), null)
  progress.percent = 40
  assert.deepEqual(progressNotificationDecision(progress, Date.parse('2026-09-01T10:02:00.000Z')).triggers, ['milestone'])
})

test('successful compute records completion while unconfigured notification and wake stay distinct', () => {
  const { runDirectory } = fixture(['/bin/sh', '-c', 'printf result > result.txt; exit 0'])
  const executed = spawnSync(process.execPath, [SCRIPT, '_execute', '--run-dir', runDirectory], { encoding: 'utf8' })
  assert.equal(executed.status, 0, executed.stderr)
  const status = JSON.parse(fs.readFileSync(path.join(runDirectory, 'status.json'), 'utf8'))
  assert.equal(status.phase, 'completed')
  assert.deepEqual(status.experiment, { state: 'succeeded', exitCode: 0, signal: null, spawnError: null })
  assert.equal(status.notification.state, 'unavailable')
  assert.equal(status.wake.state, 'unavailable')
  assert.equal(fs.existsSync(path.join(runDirectory, 'completion-config.json')), false)
})

test('an unspawnable command is recorded as exit 127', () => {
  const { runDirectory } = fixture(['/definitely/not/a/command'])
  const executed = spawnSync(process.execPath, [SCRIPT, '_execute', '--run-dir', runDirectory], { encoding: 'utf8' })
  assert.equal(executed.status, 127, executed.stderr)
  const status = JSON.parse(fs.readFileSync(path.join(runDirectory, 'status.json'), 'utf8'))
  assert.equal(status.experiment.exitCode, 127)
  assert.match(status.experiment.spawnError, /ENOENT/)
})

test('compute failure remains the service exit when notification fails and exact-thread wake succeeds', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mtgallium-durable-run-routing-'))
  const recordedArgs = path.join(root, 'codex-args.txt')
  const fakeCodex = path.join(root, 'codex')
  fs.writeFileSync(fakeCodex, `#!/bin/sh\nprintf '%s\\n' "$@" > "${recordedArgs}"\n`, { mode: 0o700 })
  const { runDirectory } = fixture(['/bin/sh', '-c', 'exit 23'], {
    thread: THREAD,
    ntfy: { url: 'https://example.invalid/private', token: 'private-token', curlPath: '/usr/bin/false' },
    wake: { requested: true, supported: true, reason: null, codexPath: fakeCodex },
  })
  const executed = spawnSync(process.execPath, [SCRIPT, '_execute', '--run-dir', runDirectory], { encoding: 'utf8' })
  assert.equal(executed.status, 23, executed.stderr)
  const status = JSON.parse(fs.readFileSync(path.join(runDirectory, 'status.json'), 'utf8'))
  assert.equal(status.experiment.exitCode, 23)
  assert.equal(status.notification.state, 'failed')
  assert.equal(status.wake.state, 'succeeded')
  assert.deepEqual(fs.readFileSync(recordedArgs, 'utf8').trim().split('\n').slice(0, 3), ['queue', '--thread', THREAD])
})

test('configured ntfy can succeed while a wake failure remains independent of successful compute', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mtgallium-durable-run-ntfy-'))
  const recordedConfig = path.join(root, 'curl-config.txt')
  const recordedCalls = path.join(root, 'curl-calls.txt')
  const recordedPhases = path.join(root, 'curl-phases.txt')
  const fakeCurl = path.join(root, 'curl')
  const { runDirectory } = fixture(['/bin/true'], {
    thread: THREAD,
    initialEstimatedSeconds: 60,
    ntfy: { url: 'https://example.invalid/private', token: 'private-token', curlPath: fakeCurl },
    wake: { requested: true, supported: true, reason: null, codexPath: '/usr/bin/false' },
  })
  fs.writeFileSync(fakeCurl, `#!/usr/bin/env node\nconst fs = require('node:fs')\nconst args = process.argv.slice(2)\nfs.appendFileSync(${JSON.stringify(recordedCalls)}, JSON.stringify(args) + '\\n')\nconst status = JSON.parse(fs.readFileSync(${JSON.stringify(path.join(runDirectory, 'status.json'))}, 'utf8'))\nfs.appendFileSync(${JSON.stringify(recordedPhases)}, status.phase + '\\n')\nconst config = args.find((argument) => { try { return fs.statSync(argument).isFile() } catch { return false } })\nif (!config) process.exit(1)\nfs.copyFileSync(config, ${JSON.stringify(recordedConfig)})\n`, { mode: 0o700 })
  const executed = spawnSync(process.execPath, [SCRIPT, '_execute', '--run-dir', runDirectory], { encoding: 'utf8' })
  assert.equal(executed.status, 0, executed.stderr)
  const statusText = fs.readFileSync(path.join(runDirectory, 'status.json'), 'utf8')
  const status = JSON.parse(statusText)
  assert.equal(status.experiment.exitCode, 0)
  assert.equal(status.notification.state, 'succeeded')
  assert.equal(status.notification.start.state, 'succeeded')
  assert.equal(status.notification.terminal.state, 'succeeded')
  assert.equal(status.wake.state, 'failed')
  assert.equal(status.progress.acceptedUpdates, 0)
  assert.equal(status.progress.eta.source, 'workload')
  assert.equal(status.progress.eta.kind, 'launch-estimate')
  assert.doesNotMatch(statusText, /private-token|example\.invalid/)
  assert.match(fs.readFileSync(recordedConfig, 'utf8'), /Authorization: Bearer private-token/)
  const calls = fs.readFileSync(recordedCalls, 'utf8')
  assert.match(calls, /Started\./)
  assert.match(calls, /workload estimate/)
  assert.match(calls, /Completed successfully/)
  assert.deepEqual(fs.readFileSync(recordedPhases, 'utf8').trim().split('\n'), ['running', 'completing'])
})

test('structured progress is observed, malformed and stale updates are isolated, and only terminal wakes Codex', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mtgallium-durable-run-progress-'))
  const codexCalls = path.join(root, 'codex-calls.txt')
  const fakeCodex = path.join(root, 'codex')
  fs.writeFileSync(fakeCodex, `#!/bin/sh\nprintf 'call\\n' >> "${codexCalls}"\nprintf '%s\\n' "$@" >> "${codexCalls}"\n`, { mode: 0o700 })
  const { runDirectory } = fixture([process.execPath, PROGRESS_CANARY, '40', '--await-observation'], {
    thread: THREAD,
    ntfy: { url: 'https://example.invalid/private', token: null, curlPath: '/usr/bin/false' },
    wake: { requested: true, supported: true, reason: null, codexPath: fakeCodex },
    progressPolicy: {
      pollIntervalMs: 10,
      minimumNotificationIntervalMs: 0,
      etaMinimumElapsedMs: 50,
    },
  })
  const executed = spawnSync(process.execPath, [SCRIPT, '_execute', '--run-dir', runDirectory], { encoding: 'utf8' })
  assert.equal(executed.status, 0, executed.stderr)
  const status = JSON.parse(fs.readFileSync(path.join(runDirectory, 'status.json'), 'utf8'))
  assert.equal(status.experiment.exitCode, 0)
  assert.ok(status.progress.acceptedUpdates >= 5)
  assert.equal(status.progress.current.completed, 100)
  assert.equal(status.progress.eta.source, 'runner')
  assert.ok(status.notification.progress.failedCount >= 1)
  assert.equal(status.wake.state, 'succeeded')
  const wakeCalls = fs.readFileSync(codexCalls, 'utf8')
  assert.equal(wakeCalls.match(/^call$/gm).length, 1)
  assert.deepEqual(wakeCalls.trim().split('\n').slice(1, 4), ['queue', '--thread', THREAD])
})

test('malformed and stale progress never change compute success', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mtgallium-durable-run-bad-progress-'))
  const workload = path.join(root, 'bad-progress.mjs')
  fs.writeFileSync(workload, `import fs from 'node:fs'\nconst file = process.env.MTGALLIUM_PROGRESS_FILE\nconst sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))\nfs.writeFileSync(file, '{bad json')\nawait sleep(40)\nconst fresh = {schemaVersion:1, updatedAt:new Date().toISOString(), completed:1, total:10}\nfs.writeFileSync(file + '.tmp', JSON.stringify(fresh)); fs.renameSync(file + '.tmp', file)\nawait sleep(40)\nconst stale = {...fresh, updatedAt:'2020-01-01T00:00:00.000Z', completed:2}\nfs.writeFileSync(file + '.tmp', JSON.stringify(stale)); fs.renameSync(file + '.tmp', file)\nawait sleep(40)\n`, { mode: 0o600 })
  const { runDirectory } = fixture([process.execPath, workload], {
    progressPolicy: { pollIntervalMs: 10, minimumNotificationIntervalMs: 0 },
  })
  const executed = spawnSync(process.execPath, [SCRIPT, '_execute', '--run-dir', runDirectory], { encoding: 'utf8' })
  assert.equal(executed.status, 0, executed.stderr)
  const status = JSON.parse(fs.readFileSync(path.join(runDirectory, 'status.json'), 'utf8'))
  assert.equal(status.experiment.state, 'succeeded')
  assert.equal(status.progress.acceptedUpdates, 1)
  assert.ok(status.progress.ignoredUpdates >= 2)
  assert.match(status.progress.lastIgnored.reason, /stale/)
})

test('stopping progress monitoring retains a final write between timer ticks', () => {
  const { runDirectory } = fixture(['/bin/true'], {
    progressPolicy: { pollIntervalMs: 60000 },
    ntfy: { url: 'https://example.invalid/private', token: null, curlPath: '/usr/bin/false' },
  })
  const request = JSON.parse(fs.readFileSync(path.join(runDirectory, 'request.json'), 'utf8'))
  const completion = JSON.parse(fs.readFileSync(path.join(runDirectory, 'completion-config.json'), 'utf8'))
  const running = JSON.parse(fs.readFileSync(path.join(runDirectory, 'status.json'), 'utf8'))
  running.phase = 'running'
  running.experiment.state = 'running'
  atomicWriteJson(path.join(runDirectory, 'status.json'), running)
  const stop = startProgressMonitor(request, completion)
  atomicWriteJson(request.progressFile, { schemaVersion: 1, updatedAt: new Date().toISOString(), completed: 100, total: 100 })
  stop()
  const status = JSON.parse(fs.readFileSync(path.join(runDirectory, 'status.json'), 'utf8'))
  assert.equal(status.progress.current.completed, 100)
  assert.equal(status.progress.acceptedUpdates, 1)
  assert.equal(status.notification.progress.attemptedCount, 0)
  assert.equal(status.experiment.state, 'running')
})
