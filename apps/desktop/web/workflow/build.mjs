// [workflow-1120] 把画布页打成一个文件：src/main.jsx → ../../src/main/resources/onesay/ui/workflow.js（离线可用，随 jar 分发）
// 同时生成 workflow-licenses.txt（打进包里的每个 npm 包的名字、版本、许可原文），随 jar 分发。
// 用法：npm ci && npm run build（版本由 package-lock.json 锁定）
import { build } from 'esbuild';
import fs from 'node:fs';
import path from 'node:path';
const out = '../../src/main/resources/onesay/ui/workflow.js';
const r = await build({
  entryPoints: ['src/main.jsx'],
  bundle: true,
  minify: true,
  format: 'iife',
  target: ['chrome110'],
  outfile: out,
  loader: { '.css': 'text', '.js': 'jsx' },
  jsx: 'automatic',
  define: { 'process.env.NODE_ENV': '"production"' },
  legalComments: 'eof',
  metafile: true,
  logLevel: 'info',
});
const pkgs = new Map();
for (const f of Object.keys(r.metafile.inputs)) {
  const m = f.match(/node_modules\/((?:@[^/]+\/)?[^/]+)\//g);
  if (!m) continue;
  const last = m[m.length - 1];
  const dir = f.slice(0, f.lastIndexOf(last) + last.length);
  if (pkgs.has(dir)) continue;
  const pj = JSON.parse(fs.readFileSync(path.join(dir, 'package.json'), 'utf8'));
  const lic = fs.readdirSync(dir).find((x) => /^(license|licence|copying)/i.test(x));
  pkgs.set(dir, { name: pj.name, version: pj.version, license: pj.license || '?', text: lic ? fs.readFileSync(path.join(dir, lic), 'utf8') : '' });
}
const list = [...pkgs.values()].sort((a, b) => a.name.localeCompare(b.name));
const uniq = list.filter((p, i) => i === 0 || p.name !== list[i - 1].name || p.version !== list[i - 1].version);
fs.writeFileSync('bundled-packages.json', JSON.stringify(uniq.map(({ text, ...x }) => x), null, 2) + '\n');
let txt = 'OneSay 工作流画布（workflow.js）里打包的第三方组件及其许可原文\n\n';
for (const p of uniq) txt += '==== ' + p.name + ' ' + p.version + ' (' + p.license + ') ====\n' + (p.text || '（包里没有单独的许可文件，许可见 package.json：' + p.license + '）') + '\n\n';
fs.writeFileSync('../../src/main/resources/onesay/ui/workflow-licenses.txt', txt);
console.log('bundled packages:', uniq.length, uniq.map((p) => p.license).filter((v, i, a) => a.indexOf(v) === i).join(', '));
