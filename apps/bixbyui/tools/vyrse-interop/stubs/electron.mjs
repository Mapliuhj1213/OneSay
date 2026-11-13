import os from 'node:os'
export const handlers = {}
export const app = { getPath: () => process.env.VY_TMP || os.tmpdir(), on() {} }
export const ipcMain = { handle(ch, fn) { handlers[ch] = fn } }
// 不加密 = phone-bridge 的 save()/load() 不落盘（测试间互不影响）
export const safeStorage = { isEncryptionAvailable: () => false, encryptString: (s) => Buffer.from(s), decryptString: (b) => b.toString() }
