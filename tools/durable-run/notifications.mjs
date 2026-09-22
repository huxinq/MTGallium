// Attach human notifications to retained run state. This service never launches
// research, changes its status/configuration, or sends a Codex completion wake.
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { atomicWriteJson, defaultStateRoot, findExecutable, loadNtfyConfiguration,
  privateOperationalPath, publishNtfy, resolveRunDirectory, systemdSnapshot } from './durable-run.mjs'

const SCRIPT = fileURLToPath(new URL('./durable-run.mjs', import.meta.url))
const read = file => JSON.parse(fs.readFileSync(file, 'utf8'))
const now = () => new Date().toISOString()
const directoryOf = run => path.join(run, 'notification-observer')

export function progressSnapshot(status) {
  const progress = status.progress?.current
  if (progress) return { phase: progress.phase || 'running', completed: progress.completed,
    total: progress.total, unit: progress.unit, detail: progress.detail || progress.unit || 'work units' }
  return { phase: status.phase, detail: 'No workload progress is available.' }
}

export function notificationDue(previous, current, atMs, minimumIntervalMs = 60000) {
  if (!previous) return true
  if (previous.snapshot.phase !== current.phase) return true
  if (atMs - previous.atMs < minimumIntervalMs) return false
  if (!(current.total > 0 && current.completed >= 0)) return false
  if (!(previous.snapshot.total > 0 && previous.snapshot.completed >= 0) || previous.snapshot.total !== current.total) return true
  const milestone = value => Math.floor(5 * value.completed / value.total)
  return milestone(current) > milestone(previous.snapshot)
}

export function attachNotifications(args) {
  let stateRoot = defaultStateRoot(), config, runId, json = false
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--json') json = true
    else if (['--state-root', '--ntfy-config'].includes(args[i])) {
      if (!args[i + 1] || args[i + 1].startsWith('--')) throw new Error(`${args[i]} requires a value`)
      if (args[i] === '--state-root') stateRoot = args[++i]
      else config = args[++i]
    } else if (!runId && !args[i].startsWith('-')) runId = args[i]
    else throw new Error(`Unknown notification option: ${args[i]}`)
  }
  const run = resolveRunDirectory(stateRoot, runId || '')
  const request = read(path.join(run, 'request.json'))
  const original = read(path.join(run, 'status.json'))
  if (original.notification.configured) throw new Error('This run already has native ntfy notifications configured.')
  const ntfy = loadNtfyConfiguration(config)
  ntfy.retries = 0
  ntfy.curlPath = findExecutable('curl')
  if (!ntfy.url || !ntfy.curlPath) throw new Error('Configure an ntfy destination and install curl before attaching notifications.')
  const directory = privateOperationalPath(directoryOf(run))
  fs.mkdirSync(directory, { mode: 0o700 }) // exclusive; ambiguous submissions must be inspected, never repeated
  const unit = `mtgallium-notify-${crypto.createHash('sha256').update(run).digest('hex').slice(0, 20)}.service`
  atomicWriteJson(path.join(directory, 'config.json'), { ntfy, pollIntervalMs: 10000 })
  atomicWriteJson(path.join(directory, 'status.json'), { phase: 'starting', runId: request.runId, unit,
    attachedAt: now(), lastDelivery: null, source: 'retained operational status; no research authority' })
  const launched = spawnSync('systemd-run', ['--user', `--unit=${unit}`, '--collect', '--quiet',
    '--service-type=exec', '--property=Restart=no', '--property=KillMode=control-group',
    process.execPath, SCRIPT, '_observe-notifications', run], { encoding: 'utf8', timeout: 30000 })
  if (launched.status !== 0) {
    atomicWriteJson(path.join(directory, 'submission.json'), { state: 'unknown', atUtc: now(),
      clientExitCode: launched.status, systemd: systemdSnapshot(unit) })
    throw Object.assign(new Error('Notification service submission is uncertain; inspect its unit before retrying.'), {
      receipt: { status: 'SUBMISSION_UNKNOWN', runId: request.runId, unit, directory } })
  }
  const result = { runId: request.runId, attached: true, unit, directory,
    notification: 'Future progress milestones and completion; no replay of past milestones.',
    researchRestarted: false, additionalCodexWake: false }
  process.stdout.write(json ? `${JSON.stringify(result, null, 2)}\n` : `Attached ntfy notifications to ${request.runId}.\n`)
}

export async function observeNotifications(run, { querySystemd = systemdSnapshot } = {}) {
  const directory = directoryOf(run)
  const statusFile = path.join(directory, 'status.json')
  const configFile = path.join(directory, 'config.json')
  const { ntfy, pollIntervalMs } = read(configFile)
  const request = read(path.join(run, 'request.json'))
  fs.writeFileSync(path.join(directory, 'started.json'), JSON.stringify({ atUtc: now(), pid: process.pid }), { flag: 'wx', mode: 0o600 })
  let previous = null
  const record = fields => atomicWriteJson(statusFile, { ...read(statusFile), ...fields, updatedAt: now() })
  const deliver = (kind, message, snapshot) => {
    // Persist the attempt first. An ambiguous delivery is never automatically resent.
    record({ phase: 'observing', lastKind: kind, lastDelivery: { state: 'attempting' }, snapshot })
    const result = publishNtfy({ ...request, runDirectory: directory }, ntfy, {
      title: `MTGallium · ${request.name}`, tags: kind === 'complete' ? 'checkered_flag' : 'chart_with_upwards_trend',
      message: `${message}\nRun ${request.runId}`,
    })
    record({ lastDelivery: result })
  }
  try {
    while (true) {
      const status = read(path.join(run, 'status.json'))
      let snapshot
      try { snapshot = progressSnapshot(status) }
      catch { snapshot = { phase: status.phase, detail: 'Progress temporarily unavailable; execution status is still monitored.' } }
      if (status.commandFinishedAt) {
        deliver('complete', `Command finished with exit ${status.experiment.exitCode}. The original Codex completion wake remains managed by the run.`, snapshot)
        record({ phase: 'completed', completedAt: now() })
        return
      }
      const live = querySystemd(request.unit)
      if (live.querySucceeded && (live.available === false || ['inactive', 'failed'].includes(live.ActiveState))) {
        // Completion is persisted before the run service exits. It may have
        // happened between our first status read and the service observation.
        const latest = read(path.join(run, 'status.json'))
        if (latest.commandFinishedAt) {
          deliver('complete', `Command finished with exit ${latest.experiment.exitCode}. The original Codex completion wake remains managed by the run.`, snapshot)
          record({ phase: 'completed', completedAt: now() })
          return
        }
        deliver('stopped', 'Run service is no longer active; its terminal outcome was not retained. Inspect the run before recovery.', snapshot)
        record({ phase: 'completed', completedAt: now() })
        return
      }
      const atMs = Date.now()
      if (notificationDue(previous, snapshot, atMs)) {
        const count = snapshot.total > 0 ? ` ${snapshot.completed}/${snapshot.total} ${snapshot.unit || 'work units'}.` : ''
        deliver('progress', `${previous ? 'Progress' : 'Notifications enabled'}: ${snapshot.phase}.${count}\n${snapshot.detail}`, snapshot)
        previous = { snapshot, atMs }
      }
      await new Promise(resolve => setTimeout(resolve, pollIntervalMs))
    }
  } catch (error) {
    record({ phase: 'failed', error: 'Notification observer failed; inspect its service journal and retained state.' })
    throw error
  } finally {
    fs.unlinkSync(configFile)
  }
}
