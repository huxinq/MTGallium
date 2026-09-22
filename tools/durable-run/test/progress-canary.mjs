#!/usr/bin/env node

import fs from 'node:fs'
import path from 'node:path'

const progressFile = process.env.MTGALLIUM_PROGRESS_FILE
if (!progressFile) throw new Error('MTGALLIUM_PROGRESS_FILE is required')
const intervalMs = Number(process.argv[2] || 7_000)
if (!Number.isFinite(intervalMs) || intervalMs < 10) throw new Error('interval must be at least 10 ms')

function publish(completed, phase, detail) {
  const progress = {
    schemaVersion: 1,
    updatedAt: new Date().toISOString(),
    completed,
    total: 100,
    unit: 'canary steps',
    phase,
    detail,
  }
  const temporary = `${progressFile}.tmp-${process.pid}`
  fs.writeFileSync(temporary, `${JSON.stringify(progress, null, 2)}\n`, { flag: 'w', mode: 0o600 })
  fs.renameSync(temporary, progressFile)
}

fs.mkdirSync(path.dirname(progressFile), { recursive: true })
for (const completed of [0, 20, 40, 60, 80, 100]) {
  publish(
    completed,
    completed < 20 ? 'canary setup' : 'canary running',
    `Unmistakable durable progress test: step ${completed}/100`,
  )
  if (process.argv.includes('--await-observation')) {
    // Integration tests coordinate observations instead of relying on timer
    // scheduling under host load. The separate final-write test covers exit.
    const deadline = Date.now() + 5000
    while (true) {
      const status = JSON.parse(fs.readFileSync(path.join(path.dirname(progressFile), 'status.json'), 'utf8'))
      if (status.progress.current?.completed === completed) break
      if (Date.now() >= deadline) throw new Error('runner did not observe canary progress')
      await new Promise(resolve => setTimeout(resolve, 10))
    }
  }
  if (completed < 100) await new Promise((resolve) => setTimeout(resolve, intervalMs))
}
await new Promise((resolve) => setTimeout(resolve, Math.max(20, intervalMs / 2)))
