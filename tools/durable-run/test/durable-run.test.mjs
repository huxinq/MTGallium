import test from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { fakeCodex, readCodexCalls } from './fake-codex.mjs'
import {
  WAKE_MECHANISM,
  atomicWriteJson,
  parseEnvironmentFile,
  parseLaunchArgs,
  systemdRunArguments,
} from '../durable-run.mjs'

const SCRIPT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'durable-run.mjs')
const THREAD = '12345678-1234-4234-9234-123456789abc'

function fixture(command, options = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mtgallium-durable-run-test-'))
  const runDirectory = path.join(root, 'run')
  fs.mkdirSync(runDirectory, { mode: 0o700 })
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
    outputPaths: [path.join(root, 'result.txt')],
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
      terminal: {
        state: completionConfig.ntfy.url ? 'pending' : 'unavailable',
        reason: completionConfig.ntfy.url ? null : 'unconfigured for test',
        attemptedAt: null,
        exitCode: null,
      },
    },
    wake: {
      configured: Boolean(options.thread && completionConfig.wake.supported),
      mechanism: completionConfig.wake.supported ? WAKE_MECHANISM : null,
      targetThreadId: options.thread || null,
      state: options.thread && completionConfig.wake.supported ? 'pending' : 'unavailable',
      reason: options.thread && completionConfig.wake.supported ? null : 'unconfigured for test',
      attemptedAt: null,
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
    '--env', 'CUDA_VISIBLE_DEVICES',
    '--', 'just', 'neural-run', 'ARGS=a b;$(not-a-shell)',
  ])
  assert.deepEqual(parsed.command, ['just', 'neural-run', 'ARGS=a b;$(not-a-shell)'])
  assert.deepEqual(parsed.outputs, ['result with spaces.json'])
  assert.deepEqual(parsed.envNames, ['CUDA_VISIBLE_DEVICES'])
  assert.throws(() => parseLaunchArgs(['--name', 'run', '--thread', 'not-a-uuid', '--', 'true']), /UUID/)
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
  const codexPath = path.join(root, 'codex')
  fakeCodex(codexPath, recordedArgs)
  const { runDirectory } = fixture(['/bin/sh', '-c', 'exit 23'], {
    thread: THREAD,
    ntfy: { url: 'https://example.invalid/private', token: 'private-token', curlPath: '/usr/bin/false' },
    wake: { requested: true, supported: true, reason: null, codexPath },
  })
  const executed = spawnSync(process.execPath, [SCRIPT, '_execute', '--run-dir', runDirectory], { encoding: 'utf8' })
  assert.equal(executed.status, 23, executed.stderr)
  const status = JSON.parse(fs.readFileSync(path.join(runDirectory, 'status.json'), 'utf8'))
  assert.equal(status.experiment.exitCode, 23)
  assert.equal(status.notification.state, 'failed')
  assert.equal(status.wake.state, 'succeeded')
  const calls = readCodexCalls(recordedArgs)
  assert.deepEqual(calls[0], ['app-server', 'proxy'])
  const start = calls.find(call => call.method === 'turn/start')
  assert.equal(start.params.threadId, THREAD)
  assert.match(start.params.input[0].text, /finished with exit 23/)
  assert.equal(calls.filter(call => call.method === 'turn/start').length, 1)
})

test('an unsteerable active turn falls back to queueing the completion for the exact thread', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mtgallium-durable-run-queue-'))
  const record = path.join(root, 'codex-calls.txt')
  const codexPath = path.join(root, 'codex')
  fakeCodex(codexPath, record, 'unsteerable')
  const { runDirectory } = fixture(['/bin/true'], {
    thread: THREAD,
    wake: { requested: true, supported: true, reason: null, codexPath },
  })
  const executed = spawnSync(process.execPath, [SCRIPT, '_execute', '--run-dir', runDirectory], { encoding: 'utf8' })
  assert.equal(executed.status, 0, executed.stderr)
  const status = JSON.parse(fs.readFileSync(path.join(runDirectory, 'status.json'), 'utf8'))
  assert.equal(status.wake.state, 'succeeded')
  assert.equal(status.wake.fallback, 'codex queue --thread')
  const calls = readCodexCalls(record)
  assert.equal(calls.filter(call => call.method === 'turn/start').length, 1)
  const queued = calls.filter(call => call[0] === 'queue')
  assert.equal(queued.length, 1)
  assert.deepEqual(queued[0].slice(0, 4), ['queue', '--thread', THREAD, '--message'])
  assert.match(queued[0][4], /finished with exit 0/)
})

test('configured ntfy can succeed while a wake failure remains independent of successful compute', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mtgallium-durable-run-ntfy-'))
  const recordedConfig = path.join(root, 'curl-config.txt')
  const recordedCalls = path.join(root, 'curl-calls.txt')
  const recordedPhases = path.join(root, 'curl-phases.txt')
  const fakeCurl = path.join(root, 'curl')
  const { runDirectory } = fixture(['/bin/true'], {
    thread: THREAD,
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
  assert.doesNotMatch(statusText, /private-token|example\.invalid/)
  assert.match(fs.readFileSync(recordedConfig, 'utf8'), /Authorization: Bearer private-token/)
  const calls = fs.readFileSync(recordedCalls, 'utf8')
  assert.match(calls, /Started\./)
  assert.match(calls, /Completed successfully/)
  assert.deepEqual(fs.readFileSync(recordedPhases, 'utf8').trim().split('\n'), ['running', 'completing'])
})
