import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { Duplex } from 'node:stream'
import WebSocket from 'ws'

export const NOT_STEERABLE = 'ACTIVE_TURN_NOT_STEERABLE'

// turn/start steers an active turn, or starts a turn when idle. Let the server
// make that decision atomically instead of reading status and racing completion.
export async function sendCompletion(codexPath, threadId, message, { timeoutMs = 30_000 } = {}) {
  const child = spawn(codexPath, ['app-server', 'proxy'], { stdio: ['pipe', 'pipe', 'pipe'] })
  // Drain diagnostics without retaining potentially private server output.
  child.stderr.resume()
  const transport = Duplex.from({ readable: child.stdout, writable: child.stdin })
  const socket = new WebSocket('ws://localhost/', {
    createConnection: () => transport,
    perMessageDeflate: false,
    maxPayload: 16 * 1024 * 1024,
  })
  let fail
  const failed = new Promise((_, reject) => { fail = reject })
  failed.catch(() => {})
  for (const emitter of [child, child.stdin, transport, socket]) emitter.on('error', fail)
  child.on('close', (code, signal) => fail(new Error(`Codex proxy exited (${code ?? signal}); delivery may be unknown`)))
  socket.on('close', () => fail(new Error('Codex connection closed; delivery may be unknown')))
  const timer = setTimeout(() => fail(new Error('Codex completion timed out; delivery may be unknown; do not automatically resend')), timeoutMs)

  const send = (value) => socket.send(JSON.stringify(value))
  let sequence = 0
  let waiting
  socket.on('message', (bytes) => {
    let value
    try { value = JSON.parse(bytes) } catch {}
    if (!value || typeof value !== 'object' || Array.isArray(value)) { fail(new Error('Invalid Codex RPC response')); return }
    if (value.method) {
      if (value.id !== undefined) send({ id: value.id, error: { code: -32601, message: 'Completion client does not handle server requests' } })
      return
    }
    if (value.id !== waiting?.id) return
    const { resolve, reject } = waiting
    waiting = undefined
    if (value.error) {
      const error = new Error(`Codex RPC failed: ${JSON.stringify(value.error)}`)
      // Compact and review turns refuse steering; nothing was delivered, so the caller may queue instead.
      if (/activeTurnNotSteerable|cannot steer a (review|compact) turn/.test(error.message)) error.code = NOT_STEERABLE
      reject(error)
    }
    else if (!Object.hasOwn(value, 'result')) reject(new Error('Invalid Codex RPC response'))
    else resolve(value.result)
  })
  const call = (method, params) => Promise.race([failed, new Promise((resolve, reject) => {
    waiting = { id: ++sequence, resolve, reject }
    send({ id: sequence, method, params })
  })])

  const opened = once(socket, 'open')
  opened.catch(() => {})
  try {
    await Promise.race([failed, opened])
    await call('initialize', { clientInfo: { name: 'mtgallium_durable_run', version: '1' } })
    send({ method: 'initialized' })
    // Rejoin an existing task without overriding its model, policy or goal.
    await call('thread/resume', { threadId, excludeTurns: true })
    await call('turn/start', { threadId, input: [{ type: 'text', text: message, text_elements: [] }] })
  } finally {
    clearTimeout(timer)
    socket.terminate()
    transport.destroy()
    child.kill()
  }
}
