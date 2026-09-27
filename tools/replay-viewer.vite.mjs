import fs from 'node:fs'
import { createRequire } from 'node:module'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import zlib from 'node:zlib'

const root = process.env.MTG_REPLAY_CLIENT
const exportsDir = process.env.MTG_REPLAY_EXPORT
if (!root || !exportsDir) throw new Error('MTG_REPLAY_CLIENT and MTG_REPLAY_EXPORT are required')
const clientRequire = createRequire(path.join(root, 'package.json'))
const react = (await import(pathToFileURL(clientRequire.resolve('@vitejs/plugin-react')))).default

const read = (name) => JSON.parse(zlib.gunzipSync(fs.readFileSync(path.join(exportsDir, name))))
const send = (res, status, body, type = 'application/json') => {
  res.statusCode = status
  res.setHeader('Content-Type', `${type}; charset=utf-8`)
  res.end(typeof body === 'string' ? body : JSON.stringify(body))
}
const escape = (value) => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('"', '&quot;')

function indexPage() {
  const games = JSON.parse(fs.readFileSync(path.join(exportsDir, 'index.json')))
  const rows = games.map((game) => game.error
    ? `<tr><td>${escape(game.source)}</td><td colspan="5">${escape(game.error)}</td></tr>`
    : `<tr><td>${escape(game.gameId)}</td><td>${game.seed}</td><td>${game.policies.map(escape).join(' / ')}</td><td>${escape(game.winner ?? 'draw')}</td><td>${game.flags}</td><td title="${escape(game.divergence ?? '')}">${game.divergence ? 'Diverged at ' + escape(game.divergence.match(/decision [^:]+/)?.[0] ?? 'rebuild') : 'Exact'}</td><td>${['hindsight', 'p0', 'p1'].map(view => `<a href="/watch/${game.gameId}-${view}">${view}</a>`).join(' · ')}</td></tr>`).join('')
  return `<!doctype html><html><meta charset="utf-8"><title>Recorded games</title><style>body{font:16px system-ui;background:#131723;color:#eee;margin:2rem}table{border-collapse:collapse}td,th{padding:.5rem;border-bottom:1px solid #444;text-align:left}a{color:#8bd7ff}</style><h1>Recorded games</h1><p>Hindsight reveals both hands. Seat views show only that player's own hand. A diverged rebuild still shows the captured game states.</p><table><tr><th>Game</th><th>Seed</th><th>Policies</th><th>Winner</th><th>Flags</th><th>Verification</th><th>Views</th></tr>${rows}</table></html>`
}

function watchPage(id) {
  return `<!doctype html><html><meta charset="utf-8"><title>${escape(id)}</title><style>body{margin:0;background:#111;color:#ddd;font:14px system-ui}header{height:36px;display:flex;align-items:center;padding:0 10px;gap:15px}a{color:#8bd7ff}main{display:flex;height:calc(100vh - 36px)}iframe{border:0;width:78%}aside{width:22%;overflow:auto;padding:12px;box-sizing:border-box;background:#1b2030}p{border-bottom:1px solid #444;padding-bottom:10px;white-space:pre-wrap}</style><header><a href="/">All games</a>${escape(id)}</header><main><iframe id="board" src="/replay/${encodeURIComponent(id)}"></iframe><aside><h3>Decision log</h3><div id="lines"></div></aside></main><script>
const id=${JSON.stringify(id)};
fetch('/api/public/replays/'+encodeURIComponent(id)).then(r=>r.json()).then(data=>{
  const logs=[data.initialSnapshot.gameState.gameLog||[]];
  for(const delta of data.deltas) logs.push(logs.at(-1).concat(delta.gameStateDelta?.newLogEntries||[]));
  let previous=-1;
  setInterval(()=>{
    const slider=document.getElementById('board').contentDocument?.querySelector('input[type="range"]');
    const frame=Number(slider?.value||0);
    if(frame===previous)return;previous=frame;
    const area=document.getElementById('lines');area.replaceChildren();
    for(const entry of (logs[frame]||[]).slice(-30)){const p=document.createElement('p');p.textContent=entry.description;area.append(p)}
    area.scrollTop=area.scrollHeight;
  },150);
});
</script></html>`
}

export default {
  root,
  resolve: { alias: { '@': path.join(root, 'src') } },
  define: { __COMMIT_HASH__: JSON.stringify('replay') },
  server: { host: '127.0.0.1', port: 5173 },
  plugins: [react(), {
    name: 'mtgallium-recorded-replays',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const pathname = new URL(req.url, 'http://localhost').pathname
        if (pathname === '/') return send(res, 200, indexPage(), 'text/html')
        if (pathname.startsWith('/watch/')) {
          const id = decodeURIComponent(pathname.slice(7))
          return send(res, 200, watchPage(id), 'text/html')
        }
        const full = pathname.match(/^\/api\/public\/replays\/([^/]+)\/frames\/(\d+)\/full-state$/)
        if (full) {
          try {
            const id = decodeURIComponent(full[1]).replace(/-(hindsight|p0|p1)$/, '')
            return send(res, 200, read(`${id}-states.json.gz`)[Number(full[2])] ?? {})
          } catch { return send(res, 404, {}) }
        }
        const replay = pathname.match(/^\/api\/public\/replays\/([^/]+)$/)
        if (replay) {
          try { return send(res, 200, read(`${decodeURIComponent(replay[1])}.json.gz`)) }
          catch { return send(res, 404, {}) }
        }
        if (pathname.startsWith('/api/')) return send(res, 200, {})
        next()
      })
    },
  }],
}
