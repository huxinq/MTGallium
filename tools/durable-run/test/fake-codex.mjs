import fs from 'node:fs'
import { createRequire } from 'node:module'
import { CODEX_PROXY_HELP } from '../durable-run.mjs'
const wsPath = createRequire(import.meta.url).resolve('ws')

/** Behaviors: success, missing thread, reject or unsteerable turn/start, timeout, disconnect. `queue` always succeeds. */
export function fakeCodex(file, record, behavior = 'success') {
  fs.writeFileSync(file, `#!${process.execPath}
const fs = require('node:fs')
const record = ${JSON.stringify(record)}
const behavior = ${JSON.stringify(behavior)}
if (process.argv.includes('--help')) { console.log(${JSON.stringify(CODEX_PROXY_HELP)}); process.exit(0) }
fs.appendFileSync(record, JSON.stringify(process.argv.slice(2)) + '\\n')
if (process.argv[2] === 'queue') process.exit(0)
const transport = require('node:stream').Duplex.from({ readable: process.stdin, writable: process.stdout })
const server = require('node:http').createServer()
const wss = new (require(${JSON.stringify(wsPath)}).WebSocketServer)({ noServer: true })
server.on('upgrade', (request, socket, head) => wss.handleUpgrade(request, socket, head, ws => wss.emit('connection', ws)))
server.emit('connection', transport)
wss.on('connection', ws => ws.on('message', bytes => {
  const line = bytes.toString()
  const request = JSON.parse(line)
  fs.appendFileSync(record, line + '\\n')
  if (request.id === undefined) return
  if (request.method === 'turn/start' && behavior === 'timeout') return
  if (request.method === 'turn/start' && behavior === 'disconnect') { ws.terminate(); return }
  if (request.method === 'thread/resume' && behavior === 'missing') {
    ws.send(JSON.stringify({ id: request.id, error: { code: -32600, message: 'Thread not found' } }))
  } else if (request.method === 'turn/start' && behavior === 'unsteerable') {
    ws.send(JSON.stringify({ id: request.id, error: { code: -32600, message: 'cannot steer a compact turn',
      data: { codexErrorInfo: { activeTurnNotSteerable: { turnKind: 'compact' } } } } }))
  } else if (request.method === 'turn/start' && behavior === 'reject') {
    ws.send(JSON.stringify({ id: request.id, error: { code: -32600, message: 'Rejected' } }))
  } else {
    ws.send(JSON.stringify({ id: request.id, result: {} }))
  }
}))
`, { mode: 0o700 })
}

/** The recorded argv line followed by each JSON-RPC message the client sent. */
export function readCodexCalls(record) {
  return fs.readFileSync(record, 'utf8').trim().split('\n').map(JSON.parse)
}
