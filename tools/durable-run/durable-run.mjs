#!/usr/bin/env node

import { spawn, spawnSync } from 'node:child_process'
import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const SCRIPT_PATH = fileURLToPath(import.meta.url)
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const RUN_ID = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,159}$/
const ENV_NAME = /^[A-Za-z_][A-Za-z0-9_]*$/
const PROGRESS_FIELDS = new Set([
  'schemaVersion', 'updatedAt', 'completed', 'total', 'unit', 'phase', 'detail', 'etaAt', 'remainingSeconds',
])
export const DEFAULT_PROGRESS_POLICY = Object.freeze({
  pollIntervalMs: 2_000,
  milestonePercent: 20,
  minimumNotificationIntervalMs: 30_000,
  substantialEtaChangeMs: 15 * 60_000,
  etaMinimumObservations: 3,
  etaMinimumElapsedMs: 15_000,
  etaMinimumFraction: 0.05,
  etaMaximumObservations: 12,
})

function usage() {
  return `Usage:
  durable-run.mjs launch --name <name> [options] -- <command> [arguments...]
  durable-run.mjs status [--json] [--state-root <path>] <run-id>
  durable-run.mjs logs [--json] [--lines N] [--state-root <path>] <run-id>
  durable-run.mjs notify [--json] [--state-root <path>] [--ntfy-config <path>] <run-id>
  durable-run.mjs list [--json] [--state-root <path>]
  durable-run.mjs progress --file <path> [progress fields]

Launch options:
  --workdir <path>       Command working directory (default: current directory)
  --output <path>        Expected result/artifact location (repeatable)
  --log <path>           Combined command stdout/stderr (default: run state directory)
  --thread <uuid>        Exact Codex thread (default: CODEX_THREAD_ID)
  --no-wake              Do not queue a completion message to Codex
  --require-wake         Refuse before launch unless the exact-task wake is available
  --no-notify            Disable external notifications, including saved configuration
  --json                 Print a structured launch receipt
  --env <name>           Capture one environment variable for the command (repeatable)
  --estimated-seconds N  Optional workload-supplied launch estimate
  --ntfy-config <path>   Local ntfy environment file
  --state-root <path>    Operational state root

Progress fields:
  --completed <number>   Completed work
  --total <number>       Total work
  --unit <label>         Unit label, such as seeds or decisions
  --phase <label>        Current phase
  --detail <text>        Concise human-readable operational detail
  --eta-at <ISO time>    Workload-supplied estimated completion time
  --remaining-seconds N  Workload-supplied remaining duration
`
}

export function defaultStateRoot(environment = process.env) {
  if (environment.XDG_STATE_HOME) return path.join(environment.XDG_STATE_HOME, 'mtgallium', 'durable-runs')
  const home = environment.HOME || os.homedir()
  return path.join(home, '.local', 'state', 'mtgallium', 'durable-runs')
}

function optionValue(args, index, option) {
  if (index + 1 >= args.length || args[index + 1] === '--') throw new Error(`${option} requires a value`)
  return args[index + 1]
}

export function parseLaunchArgs(args) {
  const separator = args.indexOf('--')
  if (separator < 0 || separator === args.length - 1) {
    throw new Error('launch requires -- followed by a command and its arguments')
  }
  const options = args.slice(0, separator)
  const command = args.slice(separator + 1)
  const parsed = { outputs: [], envNames: [], command, noWake: false }
  for (let index = 0; index < options.length; index += 1) {
    const option = options[index]
    if (['--no-wake', '--require-wake', '--no-notify', '--json'].includes(option)) {
      parsed[option.slice(2).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase())] = true
      continue
    }
    if (!['--name', '--workdir', '--output', '--log', '--thread', '--env', '--estimated-seconds', '--ntfy-config', '--state-root'].includes(option)) {
      throw new Error(`unknown launch option: ${option}`)
    }
    const value = optionValue(options, index, option)
    index += 1
    if (option === '--output') parsed.outputs.push(value)
    else if (option === '--env') parsed.envNames.push(value)
    else if (option === '--estimated-seconds') parsed.estimatedSeconds = finiteNumber(value, option)
    else parsed[option.slice(2).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase())] = value
  }
  if (!parsed.name) throw new Error('--name is required')
  if (parsed.name.length > 100 || /[\x00-\x1f\x7f]/.test(parsed.name)) throw new Error('--name must be 1-100 printable characters')
  for (const name of parsed.envNames) {
    if (!ENV_NAME.test(name)) throw new Error(`invalid environment variable name: ${name}`)
  }
  if (parsed.thread && !UUID.test(parsed.thread)) throw new Error('--thread must be an exact Codex thread UUID')
  if (parsed.thread && parsed.noWake) throw new Error('--thread and --no-wake cannot be used together')
  if (parsed.requireWake && parsed.noWake) throw new Error('--require-wake and --no-wake cannot be used together')
  if (parsed.estimatedSeconds !== undefined && parsed.estimatedSeconds <= 0) throw new Error('--estimated-seconds must be greater than zero')
  return parsed
}

function parseCommonArgs(args, needsRunId, logs = false) {
  const parsed = { json: false, lines: 60 }
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index]
    if (argument === '--json') parsed.json = true
    else if (argument === '--state-root') {
      parsed.stateRoot = optionValue(args, index, argument)
      index += 1
    } else if (argument === '--lines' && logs) {
      parsed.lines = Number(optionValue(args, index, argument))
      index += 1
      if (!Number.isInteger(parsed.lines) || parsed.lines < 1 || parsed.lines > 10000) throw new Error('--lines must be 1-10000')
    } else if (!argument.startsWith('-') && !parsed.runId && needsRunId) parsed.runId = argument
    else throw new Error(`unknown argument: ${argument}`)
  }
  if (needsRunId && !parsed.runId) throw new Error('a run id is required')
  if (parsed.runId && !RUN_ID.test(parsed.runId)) throw new Error('invalid run id')
  return parsed
}

function finiteNumber(value, option) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) throw new Error(`${option} must be a finite number`)
  return parsed
}

export function parseProgressArgs(args) {
  const parsed = { schemaVersion: 1, updatedAt: now() }
  for (let index = 0; index < args.length; index += 1) {
    const option = args[index]
    if (!['--file', '--completed', '--total', '--unit', '--phase', '--detail', '--eta-at', '--remaining-seconds'].includes(option)) {
      throw new Error(`unknown progress option: ${option}`)
    }
    const value = optionValue(args, index, option)
    index += 1
    if (option === '--file') parsed.file = value
    else if (option === '--completed') parsed.completed = finiteNumber(value, option)
    else if (option === '--total') parsed.total = finiteNumber(value, option)
    else if (option === '--eta-at') parsed.etaAt = value
    else if (option === '--remaining-seconds') parsed.remainingSeconds = finiteNumber(value, option)
    else parsed[option.slice(2)] = value
  }
  if (!parsed.file) throw new Error('--file is required')
  const { file, ...progress } = parsed
  return { file: path.resolve(file), progress: validateProgressUpdate(progress) }
}

function slugify(name) {
  const slug = name.normalize('NFKD').replace(/[^A-Za-z0-9]+/g, '-').replace(/^-|-$/g, '').toLowerCase()
  return (slug || 'run').slice(0, 48).replace(/-$/, '')
}

function now() {
  return new Date().toISOString()
}

function newRunIdentity(name) {
  const timestamp = now().replace(/[-:]/g, '').replace(/\.\d{3}/, '')
  const suffix = crypto.randomBytes(4).toString('hex')
  const slug = slugify(name)
  return {
    runId: `${timestamp}-${slug}-${suffix}`,
    unit: `mtgallium-run-${slug}-${suffix}.service`,
  }
}

function ensurePrivateDirectory(directory) {
  fs.mkdirSync(directory, { recursive: true, mode: 0o700 })
  // Existing parents may be shared or owned by the host. New runner directories
  // and files get private modes; never change an existing parent's permissions.
}

// Operational state may live outside the evidence root, but never in source
// or through a symlink. Scientific producers retain their stricter output guards.
export function privateOperationalPath(filename) {
  const resolved = path.resolve(filename)
  for (let current = resolved; ; current = path.dirname(current)) {
    try {
      if (fs.lstatSync(current).isSymbolicLink()) throw new Error(`Symbolic-link route is not supported: ${current}`)
    } catch (error) { if (error.code !== 'ENOENT') throw error }
    if (fs.existsSync(path.join(current, '.git'))) throw new Error(`Private output lies in a source checkout: ${current}`)
    if (current === path.dirname(current)) break
  }
  return resolved
}

export function atomicWriteJson(filename, value) {
  ensurePrivateDirectory(path.dirname(filename))
  const temporary = `${filename}.tmp-${process.pid}-${crypto.randomBytes(3).toString('hex')}`
  const descriptor = fs.openSync(temporary, 'wx', 0o600)
  try {
    fs.writeFileSync(descriptor, `${JSON.stringify(value, null, 2)}\n`)
    fs.fsyncSync(descriptor)
  } finally {
    fs.closeSync(descriptor)
  }
  fs.renameSync(temporary, filename)
  const directoryDescriptor = fs.openSync(path.dirname(filename), 'r')
  try { fs.fsyncSync(directoryDescriptor) } finally { fs.closeSync(directoryDescriptor) }
}

function readJson(filename) {
  return JSON.parse(fs.readFileSync(filename, 'utf8'))
}

function progressText(value, field, limit) {
  if (value === undefined) return undefined
  if (typeof value !== 'string' || !value.trim() || value.length > limit || /[\x00-\x1f\x7f]/.test(value)) {
    throw new Error(`${field} must be 1-${limit} readable characters`)
  }
  return value.trim()
}

export function validateProgressUpdate(value, options = {}) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('progress must be a JSON object')
  for (const field of Object.keys(value)) {
    if (!PROGRESS_FIELDS.has(field)) throw new Error(`unknown progress field: ${field}`)
  }
  if (value.schemaVersion !== 1) throw new Error('progress schemaVersion must be 1')
  if (typeof value.updatedAt !== 'string') throw new Error('progress updatedAt must be an ISO timestamp')
  const updatedAtMs = Date.parse(value.updatedAt)
  if (!Number.isFinite(updatedAtMs)) throw new Error('progress updatedAt must be an ISO timestamp')
  const observedAtMs = options.observedAtMs ?? Date.now()
  if (updatedAtMs > observedAtMs + 5 * 60_000) throw new Error('progress updatedAt is implausibly far in the future')

  const progress = { schemaVersion: 1, updatedAt: new Date(updatedAtMs).toISOString() }
  for (const field of ['completed', 'total', 'remainingSeconds']) {
    if (value[field] !== undefined) {
      if (typeof value[field] !== 'number' || !Number.isFinite(value[field]) || value[field] < 0) {
        throw new Error(`progress ${field} must be a non-negative finite number`)
      }
      progress[field] = value[field]
    }
  }
  if (progress.total !== undefined && progress.total <= 0) throw new Error('progress total must be greater than zero')
  if (progress.completed !== undefined && progress.total !== undefined && progress.completed > progress.total) {
    throw new Error('progress completed cannot exceed total')
  }
  progress.unit = progressText(value.unit, 'unit', 40)
  progress.phase = progressText(value.phase, 'phase', 80)
  progress.detail = progressText(value.detail, 'detail', 240)
  if (value.etaAt !== undefined) {
    if (typeof value.etaAt !== 'string') throw new Error('progress etaAt must be an ISO timestamp')
    const etaAtMs = Date.parse(value.etaAt)
    if (!Number.isFinite(etaAtMs) || etaAtMs < updatedAtMs) throw new Error('progress etaAt must not precede updatedAt')
    progress.etaAt = new Date(etaAtMs).toISOString()
  }
  if (progress.etaAt !== undefined && progress.remainingSeconds !== undefined) {
    throw new Error('progress may supply etaAt or remainingSeconds, not both')
  }
  if (!['completed', 'total', 'unit', 'phase', 'detail', 'etaAt', 'remainingSeconds'].some((field) => progress[field] !== undefined)) {
    throw new Error('progress must contain at least one progress field')
  }
  return Object.fromEntries(Object.entries(progress).filter(([, fieldValue]) => fieldValue !== undefined))
}

function unavailableEta(reason = 'insufficient-progress') {
  return { state: 'unavailable', source: null, reason }
}

export function workloadEta(progress) {
  if (progress.etaAt) {
    return {
      state: 'available', source: 'workload', kind: 'estimated-completion',
      estimatedCompletionAt: progress.etaAt,
      remainingSeconds: Math.max(0, Math.round((Date.parse(progress.etaAt) - Date.parse(progress.updatedAt)) / 1000)),
    }
  }
  if (progress.remainingSeconds !== undefined) {
    return {
      state: 'available', source: 'workload', kind: 'remaining-duration',
      estimatedCompletionAt: new Date(Date.parse(progress.updatedAt) + progress.remainingSeconds * 1000).toISOString(),
      remainingSeconds: Math.round(progress.remainingSeconds),
    }
  }
  return null
}

export function deriveRunnerEta(samples, progress, policy = DEFAULT_PROGRESS_POLICY) {
  if (progress.completed === undefined || progress.total === undefined) return unavailableEta()
  if (samples.length < policy.etaMinimumObservations) return unavailableEta('too-few-observations')
  const first = samples[0]
  const last = samples.at(-1)
  const elapsedMs = last.observedAtMs - first.observedAtMs
  const completedDelta = last.completed - first.completed
  if (elapsedMs < policy.etaMinimumElapsedMs) return unavailableEta('observation-window-too-short')
  if (completedDelta < Math.max(1, progress.total * policy.etaMinimumFraction)) {
    return unavailableEta('too-little-observed-progress')
  }
  const ratePerMs = completedDelta / elapsedMs
  if (!(ratePerMs > 0)) return unavailableEta('no-positive-observed-rate')
  const remainingMs = Math.max(0, progress.total - progress.completed) / ratePerMs
  return {
    state: 'available', source: 'runner', kind: 'observed-rate',
    estimatedCompletionAt: new Date(last.observedAtMs + remainingMs).toISOString(),
    remainingSeconds: Math.round(remainingMs / 1000),
    basis: {
      observationCount: samples.length,
      observedSeconds: Math.round(elapsedMs / 1000),
      completedDelta,
    },
  }
}

function percentage(progress) {
  if (progress.completed === undefined || progress.total === undefined) return null
  return Math.max(0, Math.min(100, progress.completed / progress.total * 100))
}

export function progressNotificationDecision(progress, atMs, policy = DEFAULT_PROGRESS_POLICY) {
  if (!progress.current) return null
  const notificationPolicy = progress.notificationPolicy
  const lastAtMs = notificationPolicy.lastNotifiedAt ? Date.parse(notificationPolicy.lastNotifiedAt) : null
  if (lastAtMs !== null && atMs - lastAtMs < policy.minimumNotificationIntervalMs) return null

  const triggers = []
  const currentPercent = progress.percent
  const milestone = currentPercent === null ? 0 : Math.floor(currentPercent / policy.milestonePercent) * policy.milestonePercent
  if (milestone >= policy.milestonePercent && milestone > notificationPolicy.highestNotifiedMilestone) {
    triggers.push('milestone')
  }
  if (progress.current.phase && progress.current.phase !== notificationPolicy.lastNotifiedPhase) {
    triggers.push('phase')
  }
  if (progress.eta.state === 'available') {
    if (!notificationPolicy.lastNotifiedEtaAt) triggers.push('eta-available')
    else if (Math.abs(Date.parse(progress.eta.estimatedCompletionAt) - Date.parse(notificationPolicy.lastNotifiedEtaAt)) >= policy.substantialEtaChangeMs) {
      triggers.push('eta-changed')
    }
  }
  if (!triggers.length) return null
  return { triggers, milestone }
}

export function findExecutable(name, environment = process.env) {
  if (name.includes(path.sep)) {
    try { fs.accessSync(name, fs.constants.X_OK); return path.resolve(name) } catch { return undefined }
  }
  for (const directory of (environment.PATH || '').split(path.delimiter)) {
    if (!directory) continue
    const candidate = path.join(directory, name)
    try { fs.accessSync(candidate, fs.constants.X_OK); return candidate } catch { /* keep looking */ }
  }
  return undefined
}

function unquoteConfigValue(value, filename, lineNumber) {
  const trimmed = value.trim()
  if (!trimmed) return ''
  if (trimmed.startsWith("'") && trimmed.endsWith("'")) return trimmed.slice(1, -1)
  if (trimmed.startsWith('"') && trimmed.endsWith('"')) {
    try { return JSON.parse(trimmed) } catch { throw new Error(`${filename}:${lineNumber}: invalid quoted value`) }
  }
  if (/\s#/.test(trimmed)) return trimmed.replace(/\s+#.*$/, '').trim()
  return trimmed
}

export function parseEnvironmentFile(contents, filename = '<config>') {
  const result = {}
  for (const [offset, original] of contents.split(/\r?\n/).entries()) {
    const line = original.trim()
    if (!line || line.startsWith('#')) continue
    const match = /^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/.exec(line)
    if (!match) throw new Error(`${filename}:${offset + 1}: expected NAME=value`)
    result[match[1]] = unquoteConfigValue(match[2], filename, offset + 1)
  }
  return result
}

export function loadNtfyConfiguration(explicitPath, environment = process.env) {
  const defaultPath = path.join(environment.XDG_CONFIG_HOME || path.join(environment.HOME || os.homedir(), '.config'), 'mtgallium', 'durable-run.env')
  const configPath = path.resolve(explicitPath || environment.MTGALLIUM_DURABLE_RUN_CONFIG || defaultPath)
  let fileValues = {}
  if (fs.existsSync(configPath)) fileValues = parseEnvironmentFile(fs.readFileSync(configPath, 'utf8'), configPath)
  else if (explicitPath || environment.MTGALLIUM_DURABLE_RUN_CONFIG) throw new Error(`ntfy config does not exist: ${configPath}`)

  const value = (key) => environment[key] ?? fileValues[key]
  let url = value('MTGALLIUM_NTFY_URL')
  const server = value('MTGALLIUM_NTFY_SERVER')
  const topic = value('MTGALLIUM_NTFY_TOPIC')
  if (!url && server && topic) url = `${server.replace(/\/$/, '')}/${encodeURIComponent(topic)}`
  if (url) {
    let parsed
    try { parsed = new URL(url) } catch { throw new Error('MTGALLIUM_NTFY_URL is not a valid URL') }
    if (!['http:', 'https:'].includes(parsed.protocol)) throw new Error('MTGALLIUM_NTFY_URL must use http or https')
  }
  const fromEnvironment = ['MTGALLIUM_NTFY_URL', 'MTGALLIUM_NTFY_SERVER', 'MTGALLIUM_NTFY_TOPIC', 'MTGALLIUM_NTFY_TOKEN']
    .some((key) => Object.hasOwn(environment, key))
  return {
    url,
    token: value('MTGALLIUM_NTFY_TOKEN'),
    source: fromEnvironment ? 'launch environment' : (fs.existsSync(configPath) ? configPath : 'unconfigured'),
  }
}

function codexQueueSupport(codexPath) {
  if (!codexPath) return { supported: false, reason: 'codex executable was not found at launch' }
  const checked = spawnSync(codexPath, ['queue', '--help'], { encoding: 'utf8', timeout: 10_000 })
  const output = `${checked.stdout || ''}\n${checked.stderr || ''}`
  if (checked.status === 0 && output.includes('Queue a message for an existing session') && output.includes('--thread')) {
    return { supported: true }
  }
  return { supported: false, reason: 'installed codex does not expose the fail-closed queue --thread interface' }
}

function capturedEnvironment(names, environment = process.env) {
  const selected = {}
  for (const name of new Set(['PATH', 'JAVA_HOME', 'MTGALLIUM_PUBLIC_SOURCE', 'MTGALLIUM_PRIVATE_EVIDENCE_ROOT', ...names])) {
    if (environment[name] !== undefined) selected[name] = environment[name]
  }
  return selected
}

export function systemdRunArguments(request, options = {}) {
  const node = options.node || process.execPath
  const script = options.script || SCRIPT_PATH
  return [
    '--user', `--unit=${request.unit}`, '--service-type=exec', '--collect', '--quiet',
    `--description=MTGallium durable run ${request.name} (${request.runId})`,
    `--working-directory=${request.workingDirectory}`,
    '--property=Restart=no', '--property=KillMode=control-group', '--property=TimeoutStopSec=90s',
    node, script, '_execute', '--run-dir', request.runDirectory,
  ]
}

function initialStatus(request, completionConfig) {
  const notificationReason = !completionConfig.ntfy.url
    ? 'MTGALLIUM_NTFY_URL (or server + topic) is not configured'
    : (!completionConfig.ntfy.curlPath ? 'curl was not found at launch' : undefined)
  const wakeReason = !completionConfig.wake.requested
    ? 'disabled by --no-wake'
    : (!request.codexThreadId ? 'no originating Codex thread was supplied or found in CODEX_THREAD_ID' : completionConfig.wake.reason)
  const notificationConfigured = Boolean(completionConfig.ntfy.url && completionConfig.ntfy.curlPath)
  const notificationState = notificationReason ? 'unavailable' : 'pending'
  const notificationEvent = () => ({
    state: notificationState,
    reason: notificationReason || null,
    attemptedAt: null,
    exitCode: null,
  })
  return {
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
      eta: unavailableEta('no-progress-reported'),
      observedAt: null,
      acceptedUpdates: 0,
      ignoredUpdates: 0,
      lastIgnored: null,
      notificationPolicy: {
        milestonePercent: request.progressPolicy.milestonePercent,
        minimumIntervalSeconds: request.progressPolicy.minimumNotificationIntervalMs / 1000,
        highestNotifiedMilestone: 0,
        lastNotifiedPhase: null,
        lastNotifiedEtaAt: null,
        lastNotifiedAt: null,
      },
    },
    notification: {
      configured: notificationConfigured,
      state: notificationState,
      reason: notificationReason || null,
      attemptedAt: null,
      exitCode: null,
      start: notificationEvent(),
      progress: {
        state: notificationReason ? 'unavailable' : 'idle',
        reason: notificationReason || null,
        attemptedCount: 0,
        succeededCount: 0,
        failedCount: 0,
        last: null,
      },
      terminal: notificationEvent(),
    },
    wake: {
      configured: Boolean(completionConfig.wake.supported && request.codexThreadId),
      mechanism: completionConfig.wake.supported ? 'codex queue --thread' : null,
      targetThreadId: request.codexThreadId,
      state: wakeReason ? 'unavailable' : 'pending',
      reason: wakeReason || null,
      attemptedAt: null,
      exitCode: null,
    },
  }
}

export function prepareRun(parsed, environment = process.env) {
  const workingDirectory = fs.realpathSync(path.resolve(parsed.workdir || process.cwd()))
  if (!fs.statSync(workingDirectory).isDirectory()) throw new Error(`working directory is not a directory: ${workingDirectory}`)
  const stateRoot = privateOperationalPath(parsed.stateRoot || defaultStateRoot(environment))
  const { runId, unit } = newRunIdentity(parsed.name)
  const runDirectory = path.join(stateRoot, runId)
  const logPath = privateOperationalPath(path.resolve(workingDirectory, parsed.log || path.join(runDirectory, 'command.log')))
  const progressFile = path.join(runDirectory, 'progress.json')
  const progressPolicy = { ...DEFAULT_PROGRESS_POLICY }
  const outputPaths = parsed.outputs.map((output) => privateOperationalPath(path.resolve(workingDirectory, output)))
  const environmentValues = capturedEnvironment(parsed.envNames, environment)
  const codexThreadId = parsed.noWake ? null : (parsed.thread || environment.CODEX_THREAD_ID || null)
  if (codexThreadId && !UUID.test(codexThreadId)) throw new Error('CODEX_THREAD_ID is not an exact Codex thread UUID')
  const ntfy = parsed.noNotify ? { source: 'disabled by --no-notify' } : loadNtfyConfiguration(parsed.ntfyConfig, environment)
  const curlPath = findExecutable('curl', environment)
  const codexPath = findExecutable('codex', environment)
  const queueSupport = parsed.noWake ? { supported: false, reason: 'disabled by --no-wake' } : codexQueueSupport(codexPath)
  if (parsed.requireWake && (!codexThreadId || !queueSupport.supported)) {
    throw new Error(`Required completion wake unavailable: ${!codexThreadId ? 'supply --thread or CODEX_THREAD_ID' : queueSupport.reason}`)
  }
  const createdAt = now()
  const request = {
    schemaVersion: 2,
    runId,
    name: parsed.name,
    unit,
    createdAt,
    runDirectory,
    stateRoot,
    workingDirectory,
    command: { argv: parsed.command },
    environment: environmentValues,
    logPath,
    progressFile,
    progressPolicy,
    outputPaths,
    initialEstimatedSeconds: parsed.estimatedSeconds ?? null,
    codexThreadId,
    notificationConfigSource: ntfy.source,
  }
  const completionConfig = {
    schemaVersion: 2,
    ntfy: { url: ntfy.url || null, token: ntfy.token || null, curlPath: curlPath || null },
    wake: {
      requested: !parsed.noWake,
      supported: queueSupport.supported,
      reason: queueSupport.reason || null,
      codexPath: codexPath || null,
    },
  }
  ensurePrivateDirectory(runDirectory)
  ensurePrivateDirectory(path.dirname(logPath))
  // Reserve and validate the destination before submission. Never append a new
  // invocation to a prior log or expose output through an existing public file.
  fs.closeSync(fs.openSync(logPath, 'wx', 0o600))
  atomicWriteJson(path.join(runDirectory, 'request.json'), request)
  atomicWriteJson(path.join(runDirectory, 'completion-config.json'), completionConfig)
  atomicWriteJson(path.join(runDirectory, 'status.json'), initialStatus(request, completionConfig))
  return { request, completionConfig }
}

function updateStatus(runDirectory, transform) {
  const filename = path.join(runDirectory, 'status.json')
  const status = readJson(filename)
  const updated = transform(status) || status
  atomicWriteJson(filename, updated)
  return updated
}

function appendLog(filename, line) {
  ensurePrivateDirectory(path.dirname(filename))
  fs.appendFileSync(filename, `${line}\n`, { mode: 0o600 })
}

function exitCodeFor(code, signal) {
  if (Number.isInteger(code)) return code
  const signalNumber = signal ? os.constants.signals[signal] : undefined
  return signalNumber ? 128 + signalNumber : 125
}

function recordIgnoredProgress(request, reason) {
  updateStatus(request.runDirectory, (status) => ({
    ...status,
    progress: {
      ...status.progress,
      ignoredUpdates: status.progress.ignoredUpdates + 1,
      lastIgnored: { at: now(), reason: redact(reason, []) },
    },
  }))
}

function updateProgressNotification(request, decision, outcome, attemptedAt) {
  updateStatus(request.runDirectory, (status) => {
    const succeeded = outcome.state === 'succeeded'
    const progressNotification = status.notification.progress
    return {
      ...status,
      notification: {
        ...status.notification,
        progress: {
          ...progressNotification,
          state: outcome.state,
          reason: outcome.error || null,
          attemptedCount: progressNotification.attemptedCount + 1,
          succeededCount: progressNotification.succeededCount + (succeeded ? 1 : 0),
          failedCount: progressNotification.failedCount + (succeeded ? 0 : 1),
          last: { ...outcome, attemptedAt, triggers: decision.triggers },
        },
      },
      progress: {
        ...status.progress,
        notificationPolicy: {
          ...status.progress.notificationPolicy,
          highestNotifiedMilestone: Math.max(
            status.progress.notificationPolicy.highestNotifiedMilestone,
            decision.milestone,
          ),
          lastNotifiedPhase: status.progress.current?.phase || status.progress.notificationPolicy.lastNotifiedPhase,
          lastNotifiedEtaAt: status.progress.eta.state === 'available'
            ? status.progress.eta.estimatedCompletionAt
            : status.progress.notificationPolicy.lastNotifiedEtaAt,
          lastNotifiedAt: attemptedAt,
        },
      },
    }
  })
}

export function startProgressMonitor(request, completionConfig) {
  const policy = { ...DEFAULT_PROGRESS_POLICY, ...request.progressPolicy }
  let lastFileVersion = null
  let lastAcceptedUpdatedAtMs = null
  let samples = []

  const poll = (allowNotification = true) => {
    try {
      let changed = false
      try {
        const stat = fs.statSync(request.progressFile, { bigint: true })
        const fileVersion = `${stat.ino}:${stat.size}:${stat.mtimeNs}`
        if (fileVersion !== lastFileVersion) {
          lastFileVersion = fileVersion
          changed = true
        }
      } catch (error) {
        if (error?.code !== 'ENOENT') recordIgnoredProgress(request, error)
      }

      if (changed) {
        const observedAtMs = Date.now()
        let progress
        try {
          progress = validateProgressUpdate(readJson(request.progressFile), { observedAtMs })
        } catch (error) {
          recordIgnoredProgress(request, error)
          progress = null
        }
        if (progress) {
          const updatedAtMs = Date.parse(progress.updatedAt)
          if (lastAcceptedUpdatedAtMs !== null && updatedAtMs <= lastAcceptedUpdatedAtMs) {
            recordIgnoredProgress(request, 'stale progress updatedAt did not advance')
          } else {
            lastAcceptedUpdatedAtMs = updatedAtMs
            if (progress.completed !== undefined && progress.total !== undefined) {
              const previous = samples.at(-1)
              if (previous && (previous.total !== progress.total || progress.completed < previous.completed)) samples = []
              samples.push({ completed: progress.completed, total: progress.total, observedAtMs })
              samples = samples.slice(-policy.etaMaximumObservations)
            } else {
              samples = []
            }
            const observedEta = workloadEta(progress) || deriveRunnerEta(samples, progress, policy)
            updateStatus(request.runDirectory, (status) => ({
              ...status,
              progress: {
                ...status.progress,
                state: 'reported',
                current: progress,
                percent: percentage(progress),
                eta: observedEta.state === 'available'
                  ? observedEta
                  : (status.progress.eta.source === 'workload' ? status.progress.eta : observedEta),
                observedAt: new Date(observedAtMs).toISOString(),
                acceptedUpdates: status.progress.acceptedUpdates + 1,
              },
            }))
          }
        }
      }

      const status = readJson(path.join(request.runDirectory, 'status.json'))
      if (!allowNotification || !status.notification.configured || status.phase !== 'running') return
      const decision = progressNotificationDecision(status.progress, Date.now(), policy)
      if (!decision) return
      const attemptedAt = now()
      const outcome = attemptHumanNotification(request, status, completionConfig.ntfy, 'progress')
      updateProgressNotification(request, decision, outcome, attemptedAt)
    } catch (error) {
      appendLog(request.logPath, `[${now()}] ignored progress monitor error: ${redact(error, [])}`)
    }
  }

  poll()
  const interval = setInterval(poll, policy.pollIntervalMs)
  interval.unref()
  return () => {
    clearInterval(interval)
    // The final write can race the last timer tick. Retain it before recording
    // completion, without sending a redundant progress notification.
    poll(false)
  }
}

async function runCommand(request, completionConfig) {
  ensurePrivateDirectory(path.dirname(request.logPath))
  const descriptor = fs.openSync(request.logPath, 'a', 0o600)
  fs.writeSync(descriptor, `[${now()}] MTGallium durable run ${request.runId} started\n`)
  fs.writeSync(descriptor, `[${now()}] argv=${JSON.stringify(request.command.argv)}\n`)
  let child
  try {
    child = spawn(request.command.argv[0], request.command.argv.slice(1), {
      cwd: request.workingDirectory,
      env: { ...process.env, ...request.environment, MTGALLIUM_PROGRESS_FILE: request.progressFile },
      stdio: ['ignore', descriptor, descriptor],
    })
  } catch (error) {
    fs.closeSync(descriptor)
    return { code: 127, signal: null, spawnError: String(error) }
  }
  const forward = (signal) => { if (child.exitCode === null && child.signalCode === null) child.kill(signal) }
  const forwardTermination = () => forward('SIGTERM')
  const forwardInterrupt = () => forward('SIGINT')
  process.on('SIGTERM', forwardTermination)
  process.on('SIGINT', forwardInterrupt)
  let spawnError
  let settleSpawn
  const spawned = new Promise((resolve) => { settleSpawn = resolve })
  const closed = new Promise((resolve) => {
    child.once('error', (error) => {
      spawnError = error instanceof Error ? error.message : String(error)
      settleSpawn(false)
    })
    child.once('spawn', () => settleSpawn(true))
    child.once('close', (code, signal) => resolve({
      code: spawnError ? 127 : exitCodeFor(code, signal),
      signal: signal || null,
      spawnError: spawnError || null,
    }))
  })
  const didSpawn = await spawned
  let stopProgress = () => {}
  if (didSpawn) {
    const startedAt = now()
    let status = updateStatus(request.runDirectory, (current) => ({
      ...current,
      phase: 'running',
      startedAt,
      experiment: { ...current.experiment, state: 'running' },
      progress: request.initialEstimatedSeconds === null || request.initialEstimatedSeconds === undefined
        ? current.progress
        : {
            ...current.progress,
            eta: {
              state: 'available', source: 'workload', kind: 'launch-estimate',
              estimatedCompletionAt: new Date(Date.parse(startedAt) + request.initialEstimatedSeconds * 1000).toISOString(),
              remainingSeconds: Math.round(request.initialEstimatedSeconds),
            },
          },
    }))
    if (status.notification.configured) {
      const attemptedAt = now()
      const outcome = attemptHumanNotification(request, status, completionConfig.ntfy, 'start')
      status = updateStatus(request.runDirectory, (current) => ({
        ...current,
        notification: {
          ...current.notification,
          start: { ...current.notification.start, ...outcome, attemptedAt },
        },
        progress: {
          ...current.progress,
          notificationPolicy: { ...current.progress.notificationPolicy, lastNotifiedAt: attemptedAt },
        },
      }))
    }
    stopProgress = startProgressMonitor(request, completionConfig)
  }
  const result = await closed
  stopProgress()
  process.off('SIGTERM', forwardTermination)
  process.off('SIGINT', forwardInterrupt)
  fs.writeSync(descriptor, `[${now()}] command exited ${result.code}${result.signal ? ` (${result.signal})` : ''}\n`)
  fs.fsyncSync(descriptor)
  fs.closeSync(descriptor)
  return result
}

function redact(text, secrets) {
  let result = String(text || '')
  for (const secret of secrets.filter(Boolean)) result = result.split(secret).join('[redacted]')
  return result.slice(0, 4000)
}

function curlConfigLine(name, value) {
  const escaped = String(value).replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/[\r\n]/g, '')
  return `${name} = "${escaped}"`
}

function formatClock(isoTime) {
  return new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit' }).format(new Date(isoTime))
}

function formatDuration(milliseconds) {
  const seconds = Math.max(0, Math.round(milliseconds / 1000))
  if (seconds < 90) return `${seconds} sec`
  const minutes = Math.round(seconds / 60)
  if (minutes < 90) return `${minutes} min`
  const hours = Math.floor(minutes / 60)
  const remainder = minutes % 60
  return remainder ? `${hours} hr ${remainder} min` : `${hours} hr`
}

function etaText(eta, unavailableText = null) {
  if (eta?.state !== 'available') return unavailableText
  const source = eta.source === 'workload' ? 'workload estimate' : 'observed rate'
  return `ETA ~${formatClock(eta.estimatedCompletionAt)} (${source}).`
}

function notificationContent(request, status, kind) {
  const title = `MTGallium · ${request.name}`
  if (kind === 'start') {
    return {
      title,
      tags: 'rocket',
      message: ['Started.', etaText(status.progress.eta, 'ETA not available yet.'), `Run ${request.runId}`].filter(Boolean).join('\n'),
    }
  }
  if (kind === 'progress') {
    const current = status.progress.current
    const lines = []
    if (status.progress.percent !== null) {
      let summary = `${Math.round(status.progress.percent)}% complete`
      if (current.completed !== undefined && current.total !== undefined) {
        summary += ` · ${current.completed}/${current.total}${current.unit ? ` ${current.unit}` : ''}`
      }
      lines.push(summary)
    } else if (current.phase) lines.push(current.phase)
    if (current.phase && lines[0] !== current.phase) lines.push(`Phase: ${current.phase}`)
    if (current.detail) lines.push(current.detail)
    const eta = etaText(status.progress.eta)
    if (eta) lines.push(eta)
    lines.push(`Run ${request.runId}`)
    return { title, tags: 'chart_with_upwards_trend', message: lines.join('\n') }
  }

  const successful = status.experiment.exitCode === 0
  const startMs = Date.parse(status.startedAt || status.createdAt)
  const finishMs = Date.parse(status.commandFinishedAt || now())
  const result = successful
    ? `Completed successfully in ${formatDuration(finishMs - startMs)}.`
    : `Failed after ${formatDuration(finishMs - startMs)} · exit ${status.experiment.exitCode}.`
  const codex = status.wake.configured
    ? `Codex will be asked to ${successful ? 'analyze the results' : 'investigate'}.`
    : 'Codex resume is not configured.'
  return {
    title,
    tags: successful ? 'white_check_mark' : 'x',
    priority: successful ? null : 'high',
    message: [result, codex, `Run ${request.runId}`].join('\n'),
  }
}

export function publishNtfy(request, ntfy, content) {
  const configLines = [curlConfigLine('url', ntfy.url)]
  if (ntfy.token) configLines.push(curlConfigLine('header', `Authorization: Bearer ${ntfy.token}`))
  configLines.push(curlConfigLine('header', `Title: ${content.title}`))
  configLines.push(curlConfigLine('header', `Tags: ${content.tags}`))
  if (content.priority) configLines.push(curlConfigLine('header', `Priority: ${content.priority}`))
  // Node 26 can leave stdin open indefinitely when spawnSync receives `input`,
  // which makes curl --config - wait until our outer timeout. Keep the same
  // argv-only execution and private-at-rest semantics while avoiding stdin.
  const configPath = path.join(
    request.runDirectory,
    `.ntfy-curl-config-${process.pid}-${crypto.randomBytes(3).toString('hex')}`,
  )
  fs.writeFileSync(configPath, `${configLines.join('\n')}\n`, { flag: 'wx', mode: 0o600 })
  let published
  try {
    published = spawnSync(ntfy.curlPath, [
      '--fail-with-body', '--silent', '--show-error', '--max-time', '15', '--retry', String(ntfy.retries ?? 2), '--retry-delay', '1',
      '--config', configPath, '--data-binary', content.message,
    ], { encoding: 'utf8', timeout: 50_000, maxBuffer: 1024 * 1024 })
  } finally {
    try { fs.unlinkSync(configPath) } catch { /* completion-config.json remains the authoritative private secret store */ }
  }
  return {
    state: published.status === 0 ? 'succeeded' : 'failed',
    exitCode: published.status,
    error: published.status === 0 ? null : redact(published.stderr || published.error?.message || 'curl failed', [ntfy.url, ntfy.token]),
  }
}

function attemptHumanNotification(request, status, ntfy, kind) {
  try {
    return publishNtfy(request, ntfy, notificationContent(request, status, kind))
  } catch (error) {
    return { state: 'failed', exitCode: null, error: redact(error, [ntfy.url, ntfy.token]) }
  }
}

function wakeCodex(request, status, wake) {
  const message = `Detached MTGallium run "${request.name}" (${request.runId}) finished with exit ${status.experiment.exitCode}. Continue the original task. Inspect ${path.join(request.runDirectory, 'status.json')}, ${request.logPath}, and the recorded output locations; do not rerun the expensive command unless those outputs are invalid.`
  const queued = spawnSync(wake.codexPath, [
    'queue', '--thread', request.codexThreadId, '--message', message,
  ], { encoding: 'utf8', timeout: 30_000, maxBuffer: 1024 * 1024 })
  return {
    state: queued.status === 0 ? 'succeeded' : 'failed',
    exitCode: queued.status,
    error: queued.status === 0 ? null : redact(queued.stderr || queued.error?.message || 'codex queue failed', []),
  }
}

export async function executeRun(runDirectory) {
  const request = readJson(path.join(runDirectory, 'request.json'))
  const completionConfig = readJson(path.join(runDirectory, 'completion-config.json'))
  const result = await runCommand(request, completionConfig)
  const commandFinishedAt = now()
  let status = updateStatus(runDirectory, (current) => ({
    ...current,
    phase: 'completing',
    commandFinishedAt,
    experiment: {
      state: result.code === 0 ? 'succeeded' : 'failed',
      exitCode: result.code,
      signal: result.signal,
      spawnError: result.spawnError,
    },
  }))

  if (status.notification.configured) {
    const attemptedAt = now()
    const outcome = attemptHumanNotification(request, status, completionConfig.ntfy, 'terminal')
    status = updateStatus(runDirectory, (current) => ({
      ...current,
      notification: {
        ...current.notification,
        ...outcome,
        attemptedAt,
        terminal: { ...current.notification.terminal, ...outcome, attemptedAt },
      },
    }))
  }

  if (status.wake.configured) {
    const attemptedAt = now()
    status = updateStatus(runDirectory, (current) => ({
      ...current,
      wake: { ...current.wake, state: 'attempting', attemptedAt },
    }))
    let outcome
    try {
      outcome = wakeCodex(request, status, completionConfig.wake)
    } catch (error) {
      outcome = { state: 'failed', exitCode: null, error: redact(error, []) }
    }
    status = updateStatus(runDirectory, (current) => ({
      ...current,
      wake: { ...current.wake, ...outcome, attemptedAt },
    }))
  }

  updateStatus(runDirectory, (current) => ({ ...current, phase: 'completed', completedAt: now() }))
  try { fs.unlinkSync(path.join(runDirectory, 'completion-config.json')) } catch (error) {
    appendLog(request.logPath, `[${now()}] could not remove private completion config: ${error}`)
  }
  return result.code
}

export function resolveRunDirectory(stateRoot, runId) {
  if (!RUN_ID.test(runId)) throw new Error('invalid run id')
  const root = path.resolve(stateRoot)
  const directory = path.join(root, runId)
  if (path.dirname(directory) !== root || !fs.statSync(directory).isDirectory()) throw new Error(`run not found: ${runId}`)
  return directory
}

export function systemdSnapshot(unit) {
  const shown = spawnSync('systemctl', [
    '--user', 'show', unit, '--no-pager',
    '--property=LoadState,ActiveState,SubState,Result,ExecMainCode,ExecMainStatus',
  ], { encoding: 'utf8', timeout: 10_000 })
  if (shown.status !== 0) return { querySucceeded: false, available: null, error: redact(shown.stderr || 'systemctl failed', []) }
  const values = Object.fromEntries((shown.stdout || '').trim().split('\n').filter(Boolean).map((line) => {
    const separator = line.indexOf('=')
    return [line.slice(0, separator), line.slice(separator + 1)]
  }))
  if (values.LoadState === 'not-found') return { querySucceeded: true, available: false }
  return { querySucceeded: true, available: true, ...values }
}

function inspection(stateRoot, runId) {
  const runDirectory = resolveRunDirectory(stateRoot, runId)
  const request = readJson(path.join(runDirectory, 'request.json'))
  const storedStatus = readJson(path.join(runDirectory, 'status.json'))
  const progressFile = request.progressFile || path.join(runDirectory, 'progress.json')
  const status = storedStatus.progress ? storedStatus : {
    ...storedStatus,
    progress: {
      state: 'unreported', file: progressFile, current: null, percent: null,
      eta: unavailableEta('not-recorded-by-status-schema'), observedAt: null,
      acceptedUpdates: 0, ignoredUpdates: 0, lastIgnored: null,
    },
    notification: {
      ...storedStatus.notification,
      start: { state: 'not-recorded' },
      progress: { state: 'not-recorded' },
      terminal: { state: storedStatus.notification.state },
    },
  }
  return {
    ...status,
    runDirectory,
    workingDirectory: request.workingDirectory,
    command: request.command,
    capturedEnvironmentNames: Object.keys(request.environment),
    logPath: request.logPath,
    progressFile,
    outputPaths: request.outputPaths,
    notificationConfigSource: request.notificationConfigSource,
    codexThreadId: request.codexThreadId,
    systemd: systemdSnapshot(request.unit),
    submission: fs.existsSync(path.join(runDirectory, 'submission.json'))
      ? readJson(path.join(runDirectory, 'submission.json')) : null,
    attachedNotifications: fs.existsSync(path.join(runDirectory, 'notification-observer/status.json'))
      ? readJson(path.join(runDirectory, 'notification-observer/status.json')) : null,
  }
}

function printInspection(value) {
  let progress = 'not reported'
  if (value.progress.current) {
    const current = value.progress.current
    progress = value.progress.percent === null
      ? (current.phase || current.detail || 'reported')
      : `${Math.round(value.progress.percent)}% (${current.completed}/${current.total}${current.unit ? ` ${current.unit}` : ''})`
  }
  const eta = value.progress.eta.state === 'available'
    ? `${value.progress.eta.source} ~${formatClock(value.progress.eta.estimatedCompletionAt)}`
    : `unavailable (${value.progress.eta.reason})`
  process.stdout.write([
    `Run: ${value.runId} (${value.name})`,
    `State: ${value.phase}; experiment=${value.experiment.state}; exit=${value.experiment.exitCode ?? 'not available'}`,
    ...(value.submission ? [`Submission: ${value.submission.state}; inspect the recorded unit before recovery`] : []),
    `Unit: ${value.unit}; systemd=${value.systemd.querySucceeded ? (value.systemd.available ? `${value.systemd.ActiveState}/${value.systemd.SubState}` : 'not loaded') : 'query unavailable'}`,
    `Working directory: ${value.workingDirectory}`,
    `Command argv: ${JSON.stringify(value.command.argv)}`,
    `Status: ${path.join(value.runDirectory, 'status.json')}`,
    `Log: ${value.logPath}`,
    `Progress: ${progress}`,
    `ETA: ${eta}`,
    `Progress file: ${value.progressFile}`,
    `Outputs: ${value.outputPaths.length ? value.outputPaths.join(', ') : '(none recorded)'}`,
    `Codex thread: ${value.codexThreadId || '(none)'}`,
    `ntfy: start=${value.notification.start.state}; progress=${value.notification.progress.state}; terminal=${value.notification.terminal.state}`,
    `Codex wake: ${value.wake.state}${value.wake.reason ? ` (${value.wake.reason})` : ''}`,
    ...(value.attachedNotifications ? [`Attached ntfy: ${value.attachedNotifications.phase}; last delivery=${value.attachedNotifications.lastDelivery?.state || 'pending'}`] : []),
    '',
  ].join('\n'))
}

function listRuns(stateRoot) {
  if (!fs.existsSync(stateRoot)) return []
  return fs.readdirSync(stateRoot, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && RUN_ID.test(entry.name))
    .flatMap((entry) => {
      try { return [readJson(path.join(stateRoot, entry.name, 'status.json'))] } catch { return [] }
    })
    .sort((left, right) => right.createdAt.localeCompare(left.createdAt))
}

export function submitRun(request, options = {}) {
  const systemdArguments = systemdRunArguments(request)
  const started = spawnSync(options.systemdRun || 'systemd-run', systemdArguments, {
    encoding: 'utf8', timeout: options.timeoutMs || 30_000,
  })
  if (started.status !== 0) {
    // A client timeout/error can follow accepted submission. Only the executor
    // owns process status; keep its status and completion config untouched.
    atomicWriteJson(path.join(request.runDirectory, 'submission.json'), {
      state: 'unknown', observedAt: now(), clientExitCode: started.status,
      error: redact(started.stderr || started.error?.message || 'systemd-run failed', []),
      systemd: systemdSnapshot(request.unit),
    })
    throw Object.assign(new Error(`Submission outcome unknown for ${request.runId} (${request.unit}). Inspect retained status, logs and the service before recovery; no automatic retry was scheduled.`), {
      receipt: { status: 'SUBMISSION_UNKNOWN', runId: request.runId, unit: request.unit,
        stateRoot: request.stateRoot, runDirectory: request.runDirectory, logPath: request.logPath },
    })
  }
}

async function launch(args) {
  const parsed = parseLaunchArgs(args)
  const { request } = prepareRun(parsed)
  submitRun(request)
  const receipt = {
    runId: request.runId, unit: request.unit, runDirectory: request.runDirectory,
    stateRoot: request.stateRoot,
    logPath: request.logPath, outputPaths: request.outputPaths,
    wake: readJson(path.join(request.runDirectory, 'status.json')).wake,
    inspectionArgv: Object.fromEntries(['status', 'logs'].map(command => [command,
      [process.execPath, SCRIPT_PATH, command, '--state-root', request.stateRoot, request.runId]])),
  }
  if (parsed.json) {
    process.stdout.write(`${JSON.stringify(receipt, null, 2)}\n`)
    return
  }
  process.stdout.write([
    `Started ${request.runId}.`,
    `Unit: ${request.unit}`,
    `State: ${request.runDirectory}`,
    `Status: node ${SCRIPT_PATH} status --state-root ${request.stateRoot} ${request.runId}`,
    `Log: ${request.logPath}`,
    `Completion wake: ${receipt.wake.state}; ${receipt.wake.reason || request.codexThreadId}`,
    '',
  ].join('\n'))
}

async function main(argv) {
  const [command, ...args] = argv
  if (!command || command === '--help' || command === '-h' || (args.length === 1 && args[0] === '--help')) {
    process.stdout.write(usage())
    return
  }
  if (command === 'launch') return launch(args)
  if (command === 'notify' || command === '_observe-notifications') {
    const observer = await import('./notifications.mjs')
    if (command === 'notify') return observer.attachNotifications(args)
    if (args.length !== 1) throw new Error('_observe-notifications requires a run directory')
    return observer.observeNotifications(path.resolve(args[0]))
  }
  if (command === 'logs') {
    const parsed = parseCommonArgs(args, true, true)
    const directory = resolveRunDirectory(parsed.stateRoot || defaultStateRoot(), parsed.runId)
    const request = readJson(path.join(directory, 'request.json'))
    const result = spawnSync('tail', ['-n', String(parsed.lines), '--', request.logPath], {
      encoding: 'utf8', timeout: 10_000, maxBuffer: 8 * 1024 * 1024,
    })
    if (result.status !== 0) throw new Error(result.error?.message || result.stderr || 'Could not read log')
    if (parsed.json) process.stdout.write(`${JSON.stringify({ runId: parsed.runId, logPath: request.logPath, text: result.stdout }, null, 2)}\n`)
    else process.stdout.write(result.stdout)
    return
  }
  if (command === 'status') {
    const parsed = parseCommonArgs(args, true)
    const value = inspection(path.resolve(parsed.stateRoot || defaultStateRoot()), parsed.runId)
    if (parsed.json) process.stdout.write(`${JSON.stringify(value, null, 2)}\n`)
    else printInspection(value)
    return
  }
  if (command === 'list') {
    const parsed = parseCommonArgs(args, false)
    const values = listRuns(path.resolve(parsed.stateRoot || defaultStateRoot()))
    if (parsed.json) process.stdout.write(`${JSON.stringify(values, null, 2)}\n`)
    else for (const value of values) process.stdout.write(`${value.runId}\t${value.phase}\texit=${value.experiment.exitCode ?? '-'}\t${value.name}\n`)
    return
  }
  if (command === 'progress') {
    const { file, progress } = parseProgressArgs(args)
    atomicWriteJson(privateOperationalPath(file), progress)
    process.stdout.write(`${file}\n`)
    return
  }
  if (command === '_execute') {
    if (args.length !== 2 || args[0] !== '--run-dir') throw new Error('_execute requires --run-dir <path>')
    process.exitCode = await executeRun(path.resolve(args[1]))
    return
  }
  throw new Error(usage())
}

if (process.argv[1] && path.resolve(process.argv[1]) === SCRIPT_PATH) {
  main(process.argv.slice(2)).catch((error) => {
    const argv = process.argv.slice(2)
    const separator = argv.indexOf('--')
    const message = error instanceof Error ? error.message : String(error)
    if ((separator < 0 ? argv : argv.slice(0, separator)).includes('--json')) {
      process.stdout.write(`${JSON.stringify({ ...(error.receipt || { status: 'REFUSED' }), error: message })}\n`)
    } else process.stderr.write(`${message}\n`)
    process.exitCode = 1
  })
}
