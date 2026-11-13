// 把 phone-bridge.mts 的依赖重定向到桩（只用于 headless 互通测试）
const here = new URL('./stubs/', import.meta.url)
const MAP = { electron: 'electron.mjs', ws: 'ws.mjs' }
export async function resolve(specifier, context, next) {
  if (MAP[specifier]) return { url: new URL(MAP[specifier], here).href, shortCircuit: true }
  if (specifier.endsWith('/shared/backend-types')) return { url: new URL('backend-types.mjs', here).href, shortCircuit: true }
  if (specifier === './alexa') return { url: new URL('alexa.mjs', here).href, shortCircuit: true }
  if (specifier === './hermes-backend') return { url: new URL('empty.mjs', here).href, shortCircuit: true }
  return next(specifier, context)
}
