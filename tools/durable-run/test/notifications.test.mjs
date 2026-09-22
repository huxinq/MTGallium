import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { atomicWriteJson } from '../durable-run.mjs'
import { progressSnapshot, notificationDue, observeNotifications } from '../notifications.mjs'

const SCRIPT = fileURLToPath(new URL('../durable-run.mjs', import.meta.url))
function temporary(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'durable-notify-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  return root
}

function fixture(t, completed = false, curlExit = 0) {
  const root = temporary(t)
  const run = path.join(root, 'run')
  const directory = path.join(run, 'notification-observer')
  fs.mkdirSync(directory, { recursive: true })
  const calls = path.join(root, 'messages.jsonl')
  const curl = path.join(root, 'curl')
  fs.writeFileSync(curl, `#!${process.execPath}\nconst fs=require('node:fs');const a=process.argv.slice(2);fs.appendFileSync(${JSON.stringify(calls)},JSON.stringify(a)+'\\n');process.exit(${curlExit})\n`, { mode: 0o700 })
  const request = { runId: 'run', name: 'fixture', unit: 'original.service', outputPaths: [],
    command: { argv: ['/definitely/not/an/executable'] }, codexThreadId: 'must-not-wake' }
  const status = { phase: completed ? 'completed' : 'running', commandFinishedAt: completed ? new Date().toISOString() : null,
    experiment: { state: completed ? 'succeeded' : 'running', exitCode: completed ? 0 : null },
    progress: { current: { phase: 'TEST', completed: 10, total: 100, unit: 'checkpoints' } },
    notification: { configured: false }, wake: { state: 'pending' } }
  atomicWriteJson(path.join(run, 'request.json'), request)
  atomicWriteJson(path.join(run, 'status.json'), status)
  atomicWriteJson(path.join(directory, 'status.json'), { phase: 'starting', unit: 'observer.service' })
  atomicWriteJson(path.join(directory, 'config.json'), { ntfy: {
    url: 'https://example.invalid/topic', token: 'test-token', curlPath: curl, retries: 0 }, pollIntervalMs: 10 })
  return { root, run, directory, calls, request, status }
}

test('unreported progress uses the run phase without reading output files', t => {
  const root = temporary(t)
  fs.writeFileSync(path.join(root, 'pipeline-request.json'), '{ malformed')
  const snapshot = progressSnapshot({ phase: 'running', progress: { current: null } })
  assert.deepEqual(snapshot, { phase: 'running', detail: 'No workload progress is available.' })
})

test('unchanged progress stays quiet; phase changes and 20% milestones notify', () => {
  const previous = { atMs: 0, snapshot: { phase: 'test', completed: 10, total: 100 } }
  assert.equal(notificationDue(null, previous.snapshot, 0), true)
  assert.equal(notificationDue(previous, previous.snapshot, 120000), false)
  assert.equal(notificationDue(previous, { phase: 'test', completed: 20, total: 100 }, 10000), false)
  assert.equal(notificationDue(previous, { phase: 'test', completed: 20, total: 100 }, 60000), true)
  assert.equal(notificationDue(previous, { phase: 'summary' }, 1000), true)
  assert.equal(notificationDue({ atMs: 0, snapshot: { phase: 'running' } },
    { phase: 'running', completed: 40, total: 100 }, 60000), true)
})

test('completed runs send one terminal post, never execute research or change its wake', async t => {
  const f = fixture(t, true)
  const original = fs.readFileSync(path.join(f.run, 'status.json'), 'utf8')
  await observeNotifications(f.run, { querySystemd: () => { throw new Error('terminal needs no live query') } })
  assert.equal(fs.readFileSync(path.join(f.run, 'status.json'), 'utf8'), original)
  const calls = fs.readFileSync(f.calls, 'utf8').trim().split('\n').map(JSON.parse)
  assert.equal(calls.length, 1)
  assert.match(calls[0].at(-1), /Command finished with exit 0/)
  assert.equal(calls[0][calls[0].indexOf('--retry') + 1], '0')
  assert.equal(fs.existsSync(path.join(f.directory, 'config.json')), false)
  const stored = fs.readFileSync(path.join(f.directory, 'status.json'), 'utf8')
  assert.doesNotMatch(stored, /test-token|example.invalid/)
  assert.equal(JSON.parse(stored).lastDelivery.state, 'succeeded')
})

test('observer sends current progress then completion, with no repeat while unchanged', async t => {
  const f = fixture(t)
  const watching = observeNotifications(f.run, { querySystemd: () => ({ querySucceeded: true, available: true, ActiveState: 'active' }) })
  await new Promise(resolve => setTimeout(resolve, 70))
  atomicWriteJson(path.join(f.run, 'status.json'), { ...f.status, phase: 'completed', commandFinishedAt: new Date().toISOString(),
    experiment: { state: 'failed', exitCode: 2 } })
  await watching
  const calls = fs.readFileSync(f.calls, 'utf8').trim().split('\n').map(JSON.parse)
  assert.equal(calls.length, 2)
  assert.match(calls[0].at(-1), /Notifications enabled: TEST. 10\/100 checkpoints/)
  assert.match(calls[1].at(-1), /Command finished with exit 2/)
  assert.equal(JSON.parse(fs.readFileSync(path.join(f.run, 'status.json'), 'utf8')).wake.state, 'pending')
})

test('delivery failure stays separate from successful command completion', async t => {
  const f = fixture(t, true, 7)
  await observeNotifications(f.run)
  const own = JSON.parse(fs.readFileSync(path.join(f.directory, 'status.json'), 'utf8'))
  assert.equal(own.lastDelivery.state, 'failed')
  assert.equal(own.lastDelivery.exitCode, 7)
  assert.equal(JSON.parse(fs.readFileSync(path.join(f.run, 'status.json'), 'utf8')).experiment.exitCode, 0)
})

test('completion written during the service query wins over a stale running snapshot', async t => {
  const f = fixture(t)
  await observeNotifications(f.run, { querySystemd: () => {
    atomicWriteJson(path.join(f.run, 'status.json'), { ...f.status, phase: 'completed',
      commandFinishedAt: new Date().toISOString(), experiment: { state: 'succeeded', exitCode: 0 } })
    return { querySucceeded: true, available: true, ActiveState: 'inactive' }
  } })
  const calls = fs.readFileSync(f.calls, 'utf8').trim().split('\n').map(JSON.parse)
  assert.equal(calls.length, 1)
  assert.match(calls[0].at(-1), /Command finished with exit 0/)
  assert.doesNotMatch(calls[0].at(-1), /not retained/)
})

test('ambiguous attach retains configuration and refuses a duplicate observer', t => {
  const root = temporary(t)
  const run = path.join(root, 'run')
  atomicWriteJson(path.join(run, 'request.json'), { runId: 'run' })
  atomicWriteJson(path.join(run, 'status.json'), { notification: { configured: false } })
  const config = path.join(root, 'notify.env')
  fs.writeFileSync(config, 'MTGALLIUM_NTFY_URL=https://example.invalid/topic\nMTGALLIUM_NTFY_TOKEN=secret-token\n')
  for (const name of ['systemd-run', 'curl']) fs.writeFileSync(path.join(root, name), '#!/bin/sh\nexit 1\n', { mode: 0o700 })
  const args = [SCRIPT, 'notify', 'run', '--state-root', root, '--ntfy-config', config, '--json']
  const first = spawnSync(process.execPath, args, { encoding: 'utf8', env: { PATH: root } })
  assert.equal(first.status, 1)
  assert.equal(JSON.parse(first.stdout).status, 'SUBMISSION_UNKNOWN')
  assert.doesNotMatch(first.stdout, /secret-token|example.invalid/)
  assert.equal(fs.existsSync(path.join(run, 'notification-observer/config.json')), true)
  const second = spawnSync(process.execPath, args, { encoding: 'utf8', env: { PATH: root } })
  assert.equal(second.status, 1)
  assert.match(JSON.parse(second.stdout).error, /EEXIST/)
})
