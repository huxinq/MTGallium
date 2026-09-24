import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { NOT_STEERABLE, sendCompletion } from '../codex-wake.mjs'
import { fakeCodex, readCodexCalls } from './fake-codex.mjs'

for (const behavior of ['success', 'missing', 'reject', 'unsteerable', 'timeout', 'disconnect']) {
  test(`completion uses atomic steer-or-start delivery: ${behavior}`, async t => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mtgallium-wake-'))
    t.after(() => fs.rmSync(root, { recursive: true, force: true }))
    const executable = path.join(root, 'codex')
    const record = path.join(root, 'calls')
    fakeCodex(executable, record, behavior)
    const delivery = sendCompletion(executable, 'exact-task', 'result with spaces; $(literal)', { timeoutMs: 200 })
    if (behavior === 'success') await delivery
    else if (behavior === 'unsteerable') await assert.rejects(delivery, { code: NOT_STEERABLE })
    else await assert.rejects(delivery, ['timeout', 'disconnect'].includes(behavior) ? /delivery may be unknown/ : /RPC failed/)
    const calls = readCodexCalls(record)
    assert.deepEqual(calls.shift(), ['app-server', 'proxy'])
    assert.deepEqual(calls.map(call => call.method), [
      'initialize', 'initialized', 'thread/resume', ...(behavior === 'missing' ? [] : ['turn/start']),
    ])
    assert.deepEqual(calls[2].params, { threadId: 'exact-task', excludeTurns: true })
    if (behavior !== 'missing') assert.deepEqual(calls[3].params, {
      threadId: 'exact-task', input: [{ type: 'text', text: 'result with spaces; $(literal)', text_elements: [] }],
    })
  })
}

test('missing proxy executable fails without an unhandled process error', async () => {
  await assert.rejects(sendCompletion('/no/such/codex', 'exact-task', 'result'), /ENOENT/)
})
