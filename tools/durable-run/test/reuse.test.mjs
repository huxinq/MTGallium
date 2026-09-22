import test from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseLaunchArgs, prepareRun, privateOperationalPath, submitRun } from '../durable-run.mjs'

const SCRIPT = fileURLToPath(new URL('../durable-run.mjs', import.meta.url))
const THREAD = '12345678-1234-4234-9234-123456789abc'

function temporary(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'durable-reuse-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  return root
}

test('private destinations reject worktrees and symlinks before any launch records', t => {
  const root = temporary(t)
  const repo = path.join(root, 'repo')
  fs.mkdirSync(repo)
  fs.writeFileSync(path.join(repo, '.git'), 'gitdir: elsewhere')
  fs.symlinkSync(repo, path.join(root, 'link'))
  assert.throws(() => privateOperationalPath(path.join(repo, 'new', 'log')), /source checkout/)
  assert.throws(() => privateOperationalPath(path.join(root, 'link', 'log')), /Symbolic-link/)
  const state = path.join(root, 'state')
  const parsed = parseLaunchArgs(['--name', 'guard', '--workdir', root, '--state-root', state,
    '--no-wake', '--no-notify', '--log', path.join(repo, 'log'), '--', '/bin/true'])
  assert.throws(() => prepareRun(parsed, { PATH: '' }), /source checkout/)
  assert.equal(fs.existsSync(state), false)
})

test('required wake fails closed without a task or compatible CLI', t => {
  const root = temporary(t)
  const state = path.join(root, 'state')
  const options = ['--name', 'wake', '--workdir', root, '--state-root', state,
    '--require-wake', '--no-notify', '--', '/bin/true']
  assert.throws(() => prepareRun(parseLaunchArgs(options), { PATH: '' }), /supply --thread/)
  assert.throws(() => prepareRun(parseLaunchArgs(options), { PATH: '', CODEX_THREAD_ID: THREAD }), /codex executable/)
  assert.equal(fs.existsSync(state), false)
  assert.throws(() => parseLaunchArgs(['--name', 'x', '--require-wake', '--no-wake', '--', '/bin/true']), /cannot be used together/)
})

test('task-only launch ignores ntfy config and captures public evidence environment', t => {
  const root = temporary(t)
  const parsed = parseLaunchArgs(['--name', 'quiet', '--workdir', root,
    '--state-root', path.join(root, 'state'), '--no-notify', '--no-wake', '--', '/bin/true'])
  const { request, completionConfig } = prepareRun(parsed, {
    PATH: '', CODEX_THREAD_ID: 'not-used', MTGALLIUM_NTFY_URL: 'bad URL',
    MTGALLIUM_DURABLE_RUN_CONFIG: '/missing/config', MTGALLIUM_PUBLIC_SOURCE: '1',
    MTGALLIUM_PRIVATE_EVIDENCE_ROOT: path.join(root, 'evidence'),
  })
  assert.equal(completionConfig.ntfy.url, null)
  assert.equal(request.codexThreadId, null)
  assert.equal(request.environment.MTGALLIUM_PUBLIC_SOURCE, '1')
  assert.equal(request.environment.MTGALLIUM_PRIVATE_EVIDENCE_ROOT, path.join(root, 'evidence'))
  assert.equal(fs.statSync(path.join(request.runDirectory, 'request.json')).mode & 0o777, 0o600)
})

test('JSON launch preserves argv and configures the checked exact-task wake', t => {
  const root = temporary(t)
  const bin = path.join(root, 'bin')
  fs.mkdirSync(bin)
  fs.writeFileSync(path.join(bin, 'codex'), '#!/bin/sh\nprintf "Queue a message for an existing session --thread\\n"\n', { mode: 0o700 })
  fs.writeFileSync(path.join(bin, 'systemd-run'), '#!/bin/sh\nexit 0\n', { mode: 0o700 })
  const command = ['/bin/echo', 'spaces ; $(literal)']
  const result = spawnSync(process.execPath, [SCRIPT, 'launch', '--name', 'receipt',
    '--workdir', root, '--state-root', path.join(root, 'state'), '--thread', THREAD,
    '--require-wake', '--no-notify', '--json', '--', ...command], {
    encoding: 'utf8', env: { PATH: bin },
  })
  assert.equal(result.status, 0, result.stderr)
  const receipt = JSON.parse(result.stdout)
  assert.equal(receipt.wake.configured, true)
  assert.equal(receipt.wake.targetThreadId, THREAD)
  assert.equal(receipt.wake.state, 'pending')
  assert.deepEqual(receipt.inspectionArgv.status.slice(-3), ['--state-root', path.join(root, 'state'), receipt.runId])
  const request = JSON.parse(fs.readFileSync(path.join(receipt.runDirectory, 'request.json'), 'utf8'))
  assert.deepEqual(request.command.argv, command)
})

test('help and JSON logs work on retained records without launching a process', t => {
  const root = temporary(t)
  const directory = path.join(root, 'old-run')
  fs.mkdirSync(directory)
  const log = path.join(root, 'old command.log')
  fs.writeFileSync(log, 'first\nsecond\nthird\n')
  fs.writeFileSync(path.join(directory, 'request.json'), JSON.stringify({ logPath: log }))
  const help = spawnSync(process.execPath, [SCRIPT, '--help'], { encoding: 'utf8' })
  assert.equal(help.status, 0)
  assert.match(help.stdout, /--require-wake/)
  const result = spawnSync(process.execPath, [SCRIPT, 'logs', 'old-run', '--state-root', root, '--lines', '2', '--json'], { encoding: 'utf8' })
  assert.equal(result.status, 0, result.stderr)
  assert.equal(JSON.parse(result.stdout).text, 'second\nthird\n')
  const invalid = spawnSync(process.execPath, [SCRIPT, 'logs', 'old-run', '--lines', '0', '--json'], { encoding: 'utf8' })
  assert.equal(invalid.status, 1)
  assert.equal(JSON.parse(invalid.stdout).status, 'REFUSED')
})

test('accepted submission followed by client timeout preserves executor state and wake config', t => {
  const root = temporary(t)
  const { request } = prepareRun(parseLaunchArgs(['--name', 'ambiguous', '--workdir', root,
    '--state-root', path.join(root, 'state'), '--no-notify', '--no-wake', '--', '/bin/true']), { PATH: '' })
  const statusPath = path.join(request.runDirectory, 'status.json')
  const configPath = path.join(request.runDirectory, 'completion-config.json')
  const config = fs.readFileSync(configPath, 'utf8')
  const fake = path.join(root, 'systemd-run')
  fs.writeFileSync(fake, `#!${process.execPath}\nconst fs = require('node:fs')\nconst file = ${JSON.stringify(statusPath)}\nconst state = JSON.parse(fs.readFileSync(file, 'utf8'))\nstate.phase = 'running'\nstate.experiment.state = 'running'\nfs.writeFileSync(file, JSON.stringify(state))\nsetTimeout(() => {}, 3000)\n`, { mode: 0o700 })
  assert.throws(() => submitRun(request, { systemdRun: fake, timeoutMs: 500 }), error => {
    assert.equal(error.receipt.status, 'SUBMISSION_UNKNOWN')
    assert.equal(error.receipt.runId, request.runId)
    return /Submission outcome unknown/.test(error.message)
  })
  const status = JSON.parse(fs.readFileSync(statusPath, 'utf8'))
  assert.equal(status.phase, 'running')
  assert.equal(status.experiment.state, 'running')
  assert.equal(fs.readFileSync(configPath, 'utf8'), config)
  const submission = JSON.parse(fs.readFileSync(path.join(request.runDirectory, 'submission.json'), 'utf8'))
  assert.equal(submission.state, 'unknown')
  assert.equal(submission.clientExitCode, null)
  assert.match(submission.error, /ETIMEDOUT/)
  assert.equal(typeof submission.systemd.querySucceeded, 'boolean')
})

test('custom logging preserves existing parent modes and refuses an existing destination before submission', t => {
  const root = temporary(t)
  const shared = path.join(root, 'shared')
  fs.mkdirSync(shared)
  fs.chmodSync(shared, 0o775)
  const log = path.join(shared, 'new.log')
  const parsed = parseLaunchArgs(['--name', 'custom-log', '--workdir', root,
    '--state-root', path.join(root, 'state'), '--log', log,
    '--no-notify', '--no-wake', '--', '/bin/true'])
  const { request } = prepareRun(parsed, { PATH: '' })
  assert.equal(fs.statSync(shared).mode & 0o777, 0o775)
  assert.equal(fs.statSync(log).mode & 0o777, 0o600)
  const executed = spawnSync(process.execPath, [SCRIPT, '_execute', '--run-dir', request.runDirectory], { encoding: 'utf8' })
  assert.equal(executed.status, 0, executed.stderr)
  assert.equal(fs.statSync(shared).mode & 0o777, 0o775)
  const bytes = fs.readFileSync(log, 'utf8')
  assert.throws(() => prepareRun(parsed, { PATH: '' }), /EEXIST/)
  assert.equal(fs.readFileSync(log, 'utf8'), bytes)
  assert.equal(fs.statSync(shared).mode & 0o777, 0o775)
})

test('launch JSON distinguishes ambiguous client failure from refusal and retains identity', t => {
  const root = temporary(t)
  const fake = path.join(root, 'systemd-run')
  fs.writeFileSync(fake, '#!/bin/sh\nexit 1\n', { mode: 0o700 })
  const result = spawnSync(process.execPath, [SCRIPT, 'launch', '--name', 'unknown-json',
    '--workdir', root, '--state-root', path.join(root, 'state'), '--no-wake', '--no-notify',
    '--json', '--', '/bin/true'], { encoding: 'utf8', env: { PATH: root } })
  assert.equal(result.status, 1)
  const receipt = JSON.parse(result.stdout)
  assert.equal(receipt.status, 'SUBMISSION_UNKNOWN')
  assert.ok(receipt.runId)
  assert.ok(receipt.unit)
  assert.equal(path.basename(receipt.runDirectory), receipt.runId)
  assert.ok(fs.existsSync(path.join(receipt.runDirectory, 'completion-config.json')))
})
