// headless VYRSE 电脑端：跑 VYRSE 原 phone-bridge，加一个 127.0.0.1 控制口给 JVM 测试用
import { register } from 'node:module'
import http from 'node:http'
register('./loader.mjs', import.meta.url)
const { handlers } = await import('./stubs/electron.mjs')
const bridge = await import(process.env.VY_BRIDGE)
const main = { isDestroyed: () => false, isMinimized: () => false, webContents: { send() {} } }
bridge.attachPhoneBridge(main, { getConnection: () => null })
const ipc = (op, args) => handlers['vyrse:phone']({ sender: main.webContents }, op, args)
await ipc('enable')

const body = (req) => new Promise((r) => { let s = ''; req.on('data', (c) => (s += c)); req.on('end', () => { try { r(JSON.parse(s || '{}')) } catch { r({}) } }) })
const out = (res, o) => { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(o)) }
const port = Number(process.env.VY_CTL || 47631)
http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://x')
  if (u.pathname === '/pair') { const s = await ipc('pair'); return out(res, { code: s.pairing?.code, expiresIn: s.pairing?.expiresIn }) }
  if (u.pathname === '/status') return out(res, bridge.phoneStatus())
  if (u.pathname === '/cmd') { const b = await body(req); return out(res, await bridge.phoneCommand(String(b.action), b.args || {})) }
  if (u.pathname === '/remove') { const b = await body(req); return out(res, await ipc('remove', { id: b.id })) }
  if (u.pathname === '/quit') { out(res, { bye: true }); setTimeout(() => process.exit(0), 50); return }
  res.writeHead(404); res.end()
}).listen(port, '127.0.0.1', () => console.log(JSON.stringify({ ready: true, bridge: bridge.phoneStatus().running, port })))
