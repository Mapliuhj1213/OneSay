// [workflow-1120] OneSay 电脑端「工作流」页：画布 + 节点表单 + 运行日志 + AI 改图差异预览 + 技能库 + 连接器。
// 画布用 MIT 许可的自由布局画布库（版权与许可见 THIRD_PARTY_NOTICES.md）；表单是自己写的轻量表单（不用第三方表单组件）。
// 页面上的文字一律中文大白话，不出现画布库 / 别家产品的名字，也不显示内部能力编号以外的英文标识。
import 'reflect-metadata';
import React, { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import { createRoot } from 'react-dom/client';
import {
  FreeLayoutEditorProvider,
  EditorRenderer,
  useNodeRender,
  WorkflowNodeRenderer,
  useClientContext,
  useService,
  WorkflowDragService,
  WorkflowSelectService,
} from '@flowgram.ai/free-layout-editor';
import editorCss from '@flowgram.ai/free-layout-editor/index.css';
import pageCss from './page.css';

const ZH = { start: '开始', end: '结束', llm: '大模型', condition: '条件', loop: '循环', code: '代码', tool: '能力', skill: '技能', api: '接口' };
const PALETTE = ['llm', 'condition', 'loop', 'code', 'tool', 'api', 'end'];
const OPS = [['==', '等于'], ['!=', '不等于'], ['>', '大于'], ['>=', '大于等于'], ['<', '小于'], ['<=', '小于等于'], ['contains', '包含'], ['not_contains', '不包含'], ['empty', '为空'], ['not_empty', '不为空'], ['true', '为真'], ['false', '为假']];
const RISK_ZH = { read: '只读', local: '本机改动', outbound: '外发', spend: '花钱' };
const RUN_ZH = { pending: '等待', running: '运行中', ok: '完成', error: '出错', skipped: '跳过', denied: '没批准', cancelled: '已取消' };

function defaults(type) {
  switch (type) {
    case 'start': return { title: '开始', inputs: { type: 'object', properties: {} } };
    case 'end': return { title: '结束', output: {} };
    case 'llm': return { title: '大模型', prompt: '', system: '' };
    case 'condition': return { title: '条件', left: '', op: '==', right: '' };
    case 'loop': return { title: '循环', items: '', max: 100 };
    case 'code': return { title: '代码', code: 'function main(input) {\n  return { result: input };\n}', inputs: {}, codeTimeoutMs: 3000 };
    case 'tool': return { title: '能力', capability: '', args: {} };
    case 'api': return { title: '接口', connector: '', method: 'GET', path: '', query: {} };
    default: return { title: ZH[type] || type };
  }
}

const PORTS = {
  start: [{ type: 'output' }],
  end: [{ type: 'input' }],
  condition: [{ type: 'input' }, { type: 'output', portID: 'true', location: 'right', locationConfig: { right: 0, top: '40%' } }, { type: 'output', portID: 'false', location: 'right', locationConfig: { right: 0, top: '78%' } }],
  loop: [{ type: 'input' }, { type: 'output', portID: 'next', location: 'right', locationConfig: { right: 0, top: '40%' } }, { type: 'output', portID: 'body', location: 'bottom', locationConfig: { left: '50%', bottom: 0 } }],
};
const REGISTRIES = Object.keys(ZH).map((t) => ({
  type: t,
  meta: { defaultPorts: PORTS[t] || [{ type: 'input' }, { type: 'output' }], isStart: t === 'start', deleteDisable: t === 'start', copyDisable: t === 'start', size: { width: 240, height: 90 } },
}));

// ---------- 小状态仓库（节点标红、运行状态） ----------
const store = {
  status: {}, runNodes: {}, subs: new Set(),
  set(p) { Object.assign(this, p); this.subs.forEach((f) => f()); },
};
function useStore() {
  const [, f] = useState(0);
  useEffect(() => { const l = () => f((x) => x + 1); store.subs.add(l); return () => store.subs.delete(l); }, []);
  return store;
}

const clip = (s, n) => { s = s == null ? '' : String(s); return s.length > n ? s.slice(0, n) + '…' : s; };
const jstr = (v) => { try { return v == null ? '' : typeof v === 'string' ? v : JSON.stringify(v); } catch (e) { return ''; } };

function summary(type, d) {
  d = d || {};
  switch (type) {
    case 'start': { const p = (d.inputs && d.inputs.properties) || {}; const k = Object.keys(p); return k.length ? '参数：' + k.join('、') : '没有参数'; }
    case 'end': { const k = Object.keys(d.output || {}); return k.length ? '输出：' + k.join('、') : '不输出'; }
    case 'llm': return clip(d.prompt || '（还没写提示词）', 40);
    case 'condition': { const o = OPS.find((x) => x[0] === d.op); return clip((d.left || '?') + ' ' + (o ? o[1] : d.op || '') + ' ' + (d.right || ''), 40); }
    case 'loop': return '按 ' + clip(d.items || '?', 30) + ' 每项一轮';
    case 'code': return clip((d.code || '').split('\n').find((l) => l.trim() && !l.trim().startsWith('//')) || '（没有代码）', 40);
    case 'tool': return clip(d.capabilityName || d.capability || '（还没选能力）', 40);
    case 'skill': return clip(((d.schemaSnapshot && d.schemaSnapshot.name) || (d.skill && d.skill.id) || '技能') + (d.skill && d.skill.version ? ' v' + d.skill.version : ''), 40);
    case 'api': return clip((d.method || 'GET') + ' ' + (d.connectorName || d.connector || '?') + ' ' + (d.path || ''), 40);
    default: return '';
  }
}

function NodeRender(props) {
  const { node, data, selected } = useNodeRender();
  const st = useStore();
  const type = node.flowNodeType;
  const s = st.status[node.id] || {};
  const rs = st.runNodes[node.id];
  const cls = ['wf-node', 't-' + type, selected ? 'sel' : '', s.missing ? 'missing' : '', rs ? 'run-' + rs : ''].join(' ');
  return (
    <WorkflowNodeRenderer className={cls} node={props.node}>
      <div className="wf-node-head" data-node-id={node.id}>
        <span className="wf-badge">{ZH[type] || type}</span>
        <b className="wf-title">{(data && data.title) || ZH[type]}</b>
        {s.risk && s.risk !== 'read' ? <span className="wf-risk">{RISK_ZH[s.risk]}</span> : null}
        {rs ? <span className={'wf-rs rs-' + rs}>{RUN_ZH[rs] || rs}</span> : null}
      </div>
      <div className="wf-node-body">{summary(type, data)}</div>
      {s.missing ? <div className="wf-miss">⚠ {s.why}</div> : null}
      {type === 'condition' ? <div className="wf-plabels"><span style={{ top: '34%' }}>是</span><span style={{ top: '72%' }}>否</span></div> : null}
      {type === 'loop' ? <div className="wf-plabels"><span style={{ top: '34%' }}>之后</span><span className="bottom">循环体</span></div> : null}
    </WorkflowNodeRenderer>
  );
}

// ---------- 缩放 ----------
// 画布能缩放的范围；「适应窗口」时最小只缩到 FIT_MIN（字还看得清），放不下的部分拖动 / 滚轮平移去看，最大不超过 100%。
const ZOOM_MIN = 0.3, ZOOM_MAX = 2, FIT_MIN = 0.86, FIT_MAX = 1; // 节点字号 14px × 0.86 ≈ 12px
function zoomLimits(ctx) {
  const cfg = ctx.playground.config;
  if (cfg.config.minZoom !== ZOOM_MIN || cfg.config.maxZoom !== ZOOM_MAX) cfg.updateConfig({ minZoom: ZOOM_MIN, maxZoom: ZOOM_MAX });
}
/** 适应窗口：放得下就居中；放不下就按 FIT_MIN 显示，左边从开始节点那头对齐（往右拖着看）。 */
function fitCanvas(ctx, easing) {
  zoomLimits(ctx);
  const cfg = ctx.playground.config;
  const W = cfg.config.width, H = cfg.config.height;
  const nodes = ctx.document.getAllNodes().filter((n) => n.flowNodeType !== 'root');
  if (!W || !H || !nodes.length) return;
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  nodes.forEach((n) => { const b = n.bounds; if (!b) return; x0 = Math.min(x0, b.x); y0 = Math.min(y0, b.y); x1 = Math.max(x1, b.x + b.width); y1 = Math.max(y1, b.y + b.height); });
  if (!isFinite(x0)) return;
  const pad = 36;
  const bw = x1 - x0 + pad * 2, bh = y1 - y0 + pad * 2;
  const z = Math.max(FIT_MIN, Math.min(FIT_MAX, Math.min(W / bw, H / bh)));
  let sx, sy;
  if (bw * z <= W) sx = ((x0 + x1) / 2) * z - W / 2; else sx = (x0 - pad) * z;
  if (bh * z <= H) sy = ((y0 + y1) / 2) * z - H / 2;
  else { const st = nodes.find((n) => n.flowNodeType === 'start') || nodes[0]; const c = st.bounds.y + st.bounds.height / 2; sy = Math.max((y0 - pad) * z, c * z - H / 2); }
  cfg.scroll({ scrollX: sx, scrollY: sy, zoom: z }, !!easing);
}
function zoomTo(ctx, z) { zoomLimits(ctx); ctx.playground.config.updateZoom(Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, z)), false); }
function zoomStep(ctx, dir) {
  const cur = ctx.playground.config.config.zoom;
  const steps = [0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1, 1.1, 1.25, 1.5, 1.75, 2];
  const next = dir > 0 ? steps.find((v) => v > cur + 0.001) : [...steps].reverse().find((v) => v < cur - 0.001);
  zoomTo(ctx, next == null ? cur : next);
}

// ---------- 画布内的桥：把画布上下文交给外面 ----------
function Bridge({ onReady, onSelect, onChange, onZoom }) {
  const ctx = useClientContext();
  const sel = useService(WorkflowSelectService);
  const drag = useService(WorkflowDragService);
  useEffect(() => {
    onReady({ ctx, drag, sel });
    zoomLimits(ctx);
    const cfg = ctx.playground.config;
    let last = -1;
    const zr = () => { const z = Math.round(cfg.config.zoom * 100); if (z !== last) { last = z; onZoom(z); } };
    zr();
    const d0 = cfg.onConfigChanged(zr);
    const d1 = sel.onSelectionChanged(() => onSelect(sel.selectedNodes.length === 1 ? sel.selectedNodes[0] : null));
    const d2 = ctx.document.onContentChange(() => onChange());
    return () => { d0.dispose(); d1.dispose(); d2.dispose(); };
  }, []);
  return null;
}

function Canvas({ initial, onReady, onSelect, onChange, onZoom }) {
  const props = useMemo(() => ({
    initialData: initial,
    nodeRegistries: REGISTRIES,
    materials: { renderDefaultNode: NodeRender },
    canDeleteNode: (ctx, node) => node.flowNodeType !== 'start',
    canDeleteLine: () => true,
    history: { enable: true, enableChangeNode: true },
    nodeEngine: { enable: false },
    onAllLayersRendered: (ctx) => {
      zoomLimits(ctx);
      setTimeout(() => fitCanvas(ctx, false), 30);
      // 连线位置在第一帧按节点还没量好的大小算过一次，之后节点量好了（字体加载完、内容撑高）它不一定重算 → 有的线画不出来。
      // 打开后、字体加载完后各让连线重算一次。
      const relines = () => { try { ctx.document.linesManager.forceUpdate(); } catch (e) { /* 画布已关 */ } };
      [120, 450, 1200].forEach((t) => setTimeout(relines, t));
      if (document.fonts && document.fonts.ready) document.fonts.ready.then(() => setTimeout(relines, 30));
    },
    getNodeDefaultRegistry: (type) => ({ type, meta: { defaultPorts: [{ type: 'input' }, { type: 'output' }] } }),
  }), []);
  return (
    <FreeLayoutEditorProvider {...props}>
      <EditorRenderer className="wf-editor" />
      <Bridge onReady={onReady} onSelect={onSelect} onChange={onChange} onZoom={onZoom} />
    </FreeLayoutEditorProvider>
  );
}

// ---------- 小表单件 ----------
function Field({ label, hint, children }) {
  return <label className="wf-field"><span className="wf-lab">{label}</span>{children}{hint ? <span className="wf-hint">{hint}</span> : null}</label>;
}
function Txt({ value, onChange, rows, placeholder, mono, name }) {
  return rows ? <textarea name={name} className={mono ? 'mono' : ''} rows={rows} value={value == null ? '' : value} placeholder={placeholder} onChange={(e) => onChange(e.target.value)} />
    : <input name={name} value={value == null ? '' : value} placeholder={placeholder} onChange={(e) => onChange(e.target.value)} />;
}
function JsonTxt({ value, onChange, rows, name, placeholder }) {
  const [t, setT] = useState(() => (value == null ? '' : JSON.stringify(value, null, 2)));
  const [bad, setBad] = useState(false);
  useEffect(() => { setT(value == null ? '' : JSON.stringify(value, null, 2)); setBad(false); }, [JSON.stringify(value)]);
  return <div><textarea name={name} className={'mono' + (bad ? ' bad' : '')} rows={rows || 4} value={t} placeholder={placeholder}
    onChange={(e) => { setT(e.target.value); if (!e.target.value.trim()) { setBad(false); onChange(null); return; } try { const v = JSON.parse(e.target.value); setBad(false); onChange(v); } catch (x) { setBad(true); } }} />
    {bad ? <span className="wf-hint err">JSON 写法不对（还没生效）</span> : null}</div>;
}
/** 名字 → 值 的小表（结束节点输出、代码输入、能力参数）。 */
function KvEdit({ value, onChange, keyHint, valHint }) {
  const rows = Object.entries(value || {});
  const set = (i, k, v) => { const r = rows.slice(); r[i] = [k, v]; const o = {}; r.forEach(([a, b]) => { if (a) o[a] = b; }); onChange(o); };
  return <div className="wf-kv">
    {rows.map(([k, v], i) => <div className="wf-kvrow" key={i}>
      <input value={k} placeholder={keyHint || '名字'} onChange={(e) => set(i, e.target.value, v)} />
      <input value={jstr(v)} placeholder={valHint || '值或 {{节点.字段}}'} onChange={(e) => set(i, k, e.target.value)} />
      <button className="wf-x" title="删掉" onClick={() => { const o = { ...value }; delete o[k]; onChange(o); }}>×</button>
    </div>)}
    <button className="wf-btn ghost sm" onClick={() => { let n = 'x', i = 1; while ((value || {})[n]) n = 'x' + (++i); onChange({ ...(value || {}), [n]: '' }); }}>＋ 加一项</button>
  </div>;
}
/** 按 JSON Schema 子集画表单（技能节点的输入）。 */
function SchemaForm({ schema, value, onChange }) {
  const props = (schema && schema.properties) || {};
  const req = (schema && schema.required) || [];
  const keys = Object.keys(props);
  if (!keys.length) return <div className="wf-hint">这个技能不要输入</div>;
  return <div>{keys.map((k) => {
    const p = props[k] || {};
    const v = (value || {})[k];
    const set = (x) => onChange({ ...(value || {}), [k]: x });
    let input;
    if (p.enum) input = <select value={v == null ? '' : v} onChange={(e) => set(e.target.value)}><option value="">（选一个）</option>{p.enum.map((o) => <option key={o} value={o}>{o}</option>)}</select>;
    else if (p.type === 'boolean') input = <select value={String(v)} onChange={(e) => set(e.target.value === 'true' ? true : e.target.value === 'false' ? false : e.target.value)}><option value="">（不填）</option><option value="true">是</option><option value="false">否</option></select>;
    else if (p.type === 'object' || p.type === 'array') input = <Txt mono rows={3} value={jstr(v)} onChange={set} placeholder="JSON 或 {{节点.字段}}" />;
    else input = <Txt name={'in-' + k} value={jstr(v)} onChange={set} placeholder={p.type === 'number' || p.type === 'integer' ? '数字或 {{节点.字段}}' : '文字或 {{节点.字段}}'} />;
    return <Field key={k} label={(p.title || k) + (req.includes(k) ? ' *' : '')} hint={p.description}>{input}</Field>;
  })}</div>;
}

// ---------- 节点表单（右侧） ----------
function NodePanel({ node, caps, conns, skills, onPatch, onRelink, onClose }) {
  const [, force] = useState(0);
  const st = useStore();
  if (!node) return null; // 没选中节点时收起，画布多占地方（提示放在画布左下角）
  const type = node.flowNodeType;
  const d = node.getExtInfo() || {};
  const set = (k, v) => { const nd = { ...d, [k]: v }; node.updateExtInfo(nd); onPatch(); force((x) => x + 1); };
  const s = st.status[node.id] || {};
  let body = null;
  switch (type) {
    case 'start':
      body = <Field label="开始参数（JSON Schema）" hint="运行时会按这个弹表单。例：{&quot;type&quot;:&quot;object&quot;,&quot;properties&quot;:{&quot;city&quot;:{&quot;type&quot;:&quot;string&quot;,&quot;title&quot;:&quot;城市&quot;}}}"><JsonTxt rows={6} value={d.inputs} onChange={(v) => set('inputs', v)} /></Field>;
      break;
    case 'end':
      body = <Field label="输出" hint="名字 → 值，值可以写 {{节点编号.字段}}"><KvEdit value={d.output} onChange={(v) => set('output', v)} /></Field>;
      break;
    case 'llm':
      body = <>
        <Field label="提示词" hint="可以引用 {{节点编号.字段}}"><Txt rows={5} value={d.prompt} onChange={(v) => set('prompt', v)} /></Field>
        <Field label="系统提示（可不填）"><Txt rows={2} value={d.system} onChange={(v) => set('system', v)} /></Field>
        <Field label="用哪个模型"><select value={d.connector || ''} onChange={(e) => set('connector', e.target.value)}><option value="">OneSay 设置里的模型</option>{conns.filter((c) => c.type === 'llm' && c.preset === 'openai').map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}</select></Field>
      </>;
      break;
    case 'condition':
      body = <>
        <Field label="左边"><Txt name="cond-left" value={d.left} onChange={(v) => set('left', v)} placeholder="{{节点.字段}}" /></Field>
        <Field label="比较"><select value={d.op || '=='} onChange={(e) => set('op', e.target.value)}>{OPS.map(([k, z]) => <option key={k} value={k}>{z}</option>)}</select></Field>
        <Field label="右边"><Txt name="cond-right" value={d.right} onChange={(v) => set('right', v)} /></Field>
        <div className="wf-hint">成立走「是」出口，不成立走「否」出口；没走的那条线后面的节点会跳过。</div>
      </>;
      break;
    case 'loop':
      body = <>
        <Field label="按哪个列表循环" hint="例：{{input.list}}；也可以写一个数字表示循环几次"><Txt name="loop-items" value={jstr(d.items)} onChange={(v) => set('items', v)} /></Field>
        <Field label="最多循环几次"><input type="number" value={d.max == null ? 100 : d.max} onChange={(e) => set('max', Number(e.target.value) || 100)} /></Field>
        <div className="wf-hint">从下面的「循环体」出口连出去的节点每一项跑一遍，里面用 {'{{' + node.id + '.item}}'} 取当前这一项、{'{{' + node.id + '.index}}'} 取第几项。跑完从右边「之后」出口继续，{'{{' + node.id + '.results}}'} 是每一轮的结果。</div>
      </>;
      break;
    case 'code':
      body = <>
        <Field label="输入" hint="代码里用 input.名字 取"><KvEdit value={d.inputs} onChange={(v) => set('inputs', v)} /></Field>
        <Field label="代码（JavaScript）" hint="写 function main(input) { return {...} }。在沙箱里跑：不能联网、不能读写文件，超时会被中断。"><Txt name="code" mono rows={10} value={d.code} onChange={(v) => set('code', v)} /></Field>
        <Field label="最长运行（毫秒）"><input type="number" value={d.codeTimeoutMs == null ? 3000 : d.codeTimeoutMs} onChange={(e) => set('codeTimeoutMs', Math.max(100, Math.min(30000, Number(e.target.value) || 3000)))} /></Field>
      </>;
      break;
    case 'tool':
      body = <>
        <Field label="能力"><select value={d.capability || ''} onChange={(e) => { const c = caps.find((x) => x.id === e.target.value); const nd = { ...d, capability: e.target.value, capabilityName: c ? c.name : '' }; node.updateExtInfo(nd); onPatch(); force((x) => x + 1); }}>
          <option value="">（选一个）</option>{caps.filter((c) => c.kind !== 'skill').map((c) => <option key={c.id} value={c.id}>{c.kind_zh} · {c.name}（{c.risk_zh}）</option>)}</select></Field>
        <Field label="参数"><KvEdit value={d.args} onChange={(v) => set('args', v)} /></Field>
      </>;
      break;
    case 'skill': {
      const snap = d.schemaSnapshot || {};
      body = <>
        <div className="wf-hint">技能：{snap.name || (d.skill && d.skill.id)}　版本 {d.skill && d.skill.version}</div>
        {s.missing && skills.find((k) => d.skill && k.id === d.skill.id) ? <button className="wf-btn sm" onClick={() => onRelink(node)}>换成已装版本</button> : null}
        <SchemaForm schema={snap.inputs} value={d.inputs} onChange={(v) => set('inputs', v)} />
      </>;
      break;
    }
    case 'api':
      body = <>
        <Field label="连接器"><select name="api-conn" value={d.connector || ''} onChange={(e) => { const c = conns.find((x) => x.id === e.target.value); const nd = { ...d, connector: e.target.value, connectorName: c ? c.name : '' }; node.updateExtInfo(nd); onPatch(); force((x) => x + 1); }}>
          <option value="">（选一个，在「连接」里添加）</option>{conns.map((c) => <option key={c.id} value={c.id}>{c.name}{c.verified ? '' : '（还没测试通过）'}</option>)}</select></Field>
        <Field label="请求方法" hint="GET 只读；其他方法每次运行都要你批准"><select value={d.method || 'GET'} onChange={(e) => set('method', e.target.value)}>{['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((m) => <option key={m}>{m}</option>)}</select></Field>
        <Field label="路径"><Txt value={d.path} onChange={(v) => set('path', v)} placeholder="/chat/completions" /></Field>
        <Field label="网址参数"><KvEdit value={d.query} onChange={(v) => set('query', v)} /></Field>
        <Field label="请求体（JSON，可不填）"><JsonTxt rows={4} value={d.body} onChange={(v) => set('body', v)} /></Field>
      </>;
      break;
    default:
      body = null;
  }
  return <div className="wf-panel">
    <div className={'wf-panel-h t-' + type}><span className="wf-badge">{ZH[type]}</span> 编号 <code>{node.id}</code><span className="wf-sp" /><button className="wf-x" title="收起（取消选中）" onClick={onClose}>×</button></div>
    {s.missing ? <div className="wf-miss big">⚠ {s.why}</div> : null}
    <Field label="名字"><Txt name="title" value={d.title} onChange={(v) => set('title', v)} /></Field>
    {body}
    {['condition', 'loop', 'start', 'end'].includes(type) ? null : <Field label="出错时"><select value={d.onError || 'stop'} onChange={(e) => set('onError', e.target.value)}><option value="stop">停下整个工作流</option><option value="continue">记下错误，接着往下</option></select></Field>}
    {type !== 'start' ? <button className="wf-btn ghost sm danger" onClick={() => node.dispose()}>删除这个节点</button> : null}
  </div>;
}

// ---------- 主页面 ----------
let seq = 0;
const uid = (t) => t + '_' + Date.now().toString(36).slice(-4) + (++seq);

function blank(name) {
  return {
    schema: 'onesay.workflow/1', name: name || '新工作流',
    nodes: [
      { id: 'start', type: 'start', meta: { position: { x: 0, y: 0 } }, data: defaults('start') },
      { id: 'end', type: 'end', meta: { position: { x: 520, y: 0 } }, data: defaults('end') },
    ],
    edges: [{ sourceNodeID: 'start', targetNodeID: 'end' }],
  };
}

function App({ api, snack, live }) {
  const [tab, setTab] = useState('flow');
  return <div className="wf-root">
    <div className="wf-tabs">
      <button className={tab === 'flow' ? 'on' : ''} onClick={() => setTab('flow')}>工作流</button>
      <button className={tab === 'skills' ? 'on' : ''} onClick={() => setTab('skills')}>技能</button>
      <button className={tab === 'conn' ? 'on' : ''} onClick={() => setTab('conn')}>连接</button>
    </div>
    {tab === 'flow' ? <FlowTab api={api} snack={snack} /> : tab === 'skills' ? <SkillsTab api={api} snack={snack} /> : <ConnTab api={api} snack={snack} />}
  </div>;
}

function FlowTab({ api, snack }) {
  const [list, setList] = useState([]);
  const [shortcuts, setShortcuts] = useState([]);
  const [cur, setCur] = useState(null); // 当前打开的工作流（加载时的 JSON）
  const [canvasKey, setCanvasKey] = useState(0);
  const [selected, setSelected] = useState(null);
  const [caps, setCaps] = useState([]);
  const [conns, setConns] = useState([]);
  const [palette, setPalette] = useState([]);
  const [skills, setSkills] = useState([]);
  const [run, setRun] = useState(null);
  const [proposal, setProposal] = useState(null);
  const [aiText, setAiText] = useState('');
  const [busy, setBusy] = useState('');
  const [dialog, setDialog] = useState(null); // {kind:'export'|'import'|'input'|'shortcut', ...}
  const [name, setName] = useState('');
  const [dirty, setDirty] = useState(false);
  const [issues, setIssues] = useState([]);
  const [zoom, setZoom] = useState(100);
  const [listFold, setListFold] = useState(() => { try { return localStorage.getItem('wf.listFold') === '1'; } catch (e) { return false; } });
  const fold = (v) => { setListFold(v); try { localStorage.setItem('wf.listFold', v ? '1' : '0'); } catch (e) { /* 存不了就算了 */ } };
  const ed = useRef(null);
  const pollT = useRef(0);

  const loadList = useCallback(async () => {
    const r = await api('wf', { op: 'list' });
    if (r && r.ok) { setList(r.workflows || []); setShortcuts(r.shortcuts || []); }
  }, [api]);
  const loadLib = useCallback(async () => {
    const [c, k, s] = await Promise.all([api('wf', { op: 'caps' }), api('wf', { op: 'conn_list' }), api('wf', { op: 'skills' })]);
    if (c && c.ok) setCaps(c.capabilities || []);
    if (k && k.ok) setConns(k.connectors || []);
    if (s && s.ok) { setPalette(s.palette || []); setSkills(s.skills || []); }
  }, [api]);
  useEffect(() => { loadList(); loadLib(); return () => clearTimeout(pollT.current); }, []);

  const toJSON = () => {
    const e = ed.current;
    const j = e ? e.ctx.document.toJSON() : { nodes: [], edges: [] };
    return { ...(cur || {}), schema: 'onesay.workflow/1', name: name || (cur && cur.name) || '新工作流', nodes: j.nodes, edges: j.edges };
  };
  const validate = async (wf) => {
    const r = await api('wf', { op: 'validate', workflow: wf || toJSON() });
    if (r) { store.set({ status: r.status || {} }); setIssues(r.issues || []); }
    return r;
  };
  const open = async (id) => {
    const r = await api('wf', { op: 'get', id });
    if (!r || !r.ok) { snack(r ? r.text : '打不开'); return; }
    store.set({ status: r.status || {}, runNodes: {} });
    setCur(r.workflow); setName(r.workflow.name || ''); setSelected(null); setRun(null); setDirty(false); setIssues([]);
    setProposal((r.proposals || [])[0] || null);
    setCanvasKey((k) => k + 1);
  };
  const create = () => {
    const w = blank();
    store.set({ status: {}, runNodes: {} });
    setCur(w); setName(w.name); setSelected(null); setRun(null); setDirty(true); setIssues([]); setProposal(null);
    setCanvasKey((k) => k + 1);
  };
  const save = async () => {
    const wf = toJSON();
    setBusy('save');
    const r = await api('wf', { op: 'save', workflow: wf });
    setBusy('');
    snack(r.text);
    setIssues(r.issues || []);
    if (r.ok) { setCur(r.workflow); setDirty(false); store.set({ status: r.status || {} }); loadList(); }
    return r;
  };
  const doRun = async (input) => {
    const wf = toJSON();
    const r = await api('wf', { op: 'run', workflow: wf, input: input || {} });
    if (!r.ok) { snack(r.text); await validate(wf); return; }
    setRun(r.run); store.set({ runNodes: {} });
    const tick = async () => {
      const s = await api('wf', { op: 'run_status', run: r.run.id });
      if (s && s.ok) { setRun(s.run); store.set({ runNodes: s.run.nodes || {} }); if (s.run.status === 'running') pollT.current = setTimeout(tick, 400); }
    };
    tick();
  };
  const startRun = () => {
    const wf = toJSON();
    const st = wf.nodes.find((n) => n.type === 'start');
    const props = (st && st.data && st.data.inputs && st.data.inputs.properties) || {};
    if (Object.keys(props).length) setDialog({ kind: 'input', schema: st.data.inputs, values: {} });
    else doRun({});
  };
  const cancelRun = async () => { if (run) { const r = await api('wf', { op: 'cancel', run: run.id }); snack(r.text); } };
  const aiEdit = async () => {
    if (!aiText.trim()) return;
    setBusy('ai');
    const r = await api('wf', { op: 'ai_edit', id: cur && cur.id, workflow: toJSON(), instruction: aiText });
    setBusy('');
    if (!r.ok) { snack(r.text); return; }
    setProposal(r.proposal); setAiText('');
  };
  const applyProposal = async () => {
    const r = await api('wf', { op: 'proposal_apply', proposal: proposal.id });
    setProposal(null);
    if (!r.ok) { snack(r.text); return; }
    const e = ed.current;
    e.ctx.operation.fromJSON({ nodes: r.workflow.nodes, edges: r.workflow.edges }); // 带撤销的热重载
    setTimeout(() => fitCanvas(e.ctx, true), 50);
    store.set({ status: r.status || {} });
    setDirty(true);
    snack(r.text);
  };
  const discardProposal = async () => { await api('wf', { op: 'proposal_discard', proposal: proposal.id }); setProposal(null); snack('已放弃这次修改'); };
  const undo = () => { const e = ed.current; if (e) { e.ctx.history.undo(); setDirty(true); } };
  const redo = () => { const e = ed.current; if (e) { e.ctx.history.redo(); setDirty(true); } };
  const addNode = (type, data, ev) => {
    const e = ed.current;
    if (!e) return;
    const id = type === 'end' ? uid('end') : uid(type);
    const d = data || defaults(type);
    if (ev && ev.type === 'mousedown') { if (ev.button !== 0 || ev.detail > 1) return; e.drag.startDragCard(type, ev, { id, data: d }); return; }
    const n = e.ctx.document.createWorkflowNode({ id, type, data: d });
    if (n) e.sel.selectNode(n);
  };
  const exportWf = async () => {
    const r = await api('wf', { op: 'export', workflow: toJSON() });
    if (r.ok) setDialog({ kind: 'export', text: JSON.stringify(JSON.parse(r.json), null, 2) }); else snack(r.text);
  };
  const del = async () => {
    if (!cur || !cur.id) return;
    if (!confirm('删除工作流「' + (cur.name || '') + '」？')) return;
    const r = await api('wf', { op: 'delete', id: cur.id });
    snack(r.text); setCur(null); loadList();
  };
  const runShortcut = async (n) => {
    const r = await api('wf', { op: 'shortcut_run', name: n });
    snack(r.text);
    if (r.ok) {
      setRun(r.run);
      const tick = async () => { const s = await api('wf', { op: 'run_status', run: r.run.id }); if (s && s.ok) { setRun(s.run); if (s.run.status === 'running') pollT.current = setTimeout(tick, 400); else snack('「' + n + '」' + s.run.status_zh); } };
      tick();
    }
  };
  const relink = async (node) => {
    const d = node.getExtInfo() || {};
    const r = await api('wf', { op: 'skill_relink', id: d.skill && d.skill.id });
    if (!r.ok) { snack(r.text); return; }
    node.updateExtInfo({ ...d, skill: r.ref, schemaSnapshot: r.snapshot });
    snack(r.text); setDirty(true); validate();
  };
  const onChange = useCallback(() => { setDirty(true); }, []);

  return <div className="wf-flow">
    {cur && listFold ? null : <aside className="wf-list">
      <div className="wf-list-h"><button className="wf-btn" onClick={create}>＋ 新建工作流</button>{cur ? <button className="wf-fold" title="收起列表，画布更宽" onClick={() => fold(true)}>«</button> : null}</div>
      <button className="wf-btn ghost" onClick={() => setDialog({ kind: 'import', text: '' })}>导入</button>
      {shortcuts.length ? <div className="wf-sec">快捷指令</div> : null}
      {shortcuts.map((s) => <button key={s.name} className="wf-chip" title={'运行「' + s.workflow_name + '」'} onClick={() => runShortcut(s.name)}>▶ {s.name}</button>)}
      <div className="wf-sec">我的工作流</div>
      {list.length ? null : <div className="wf-hint">还没有工作流</div>}
      {list.map((w) => <button key={w.id} className={'wf-item' + (cur && cur.id === w.id ? ' on' : '')} onClick={() => open(w.id)}>{w.name}<span>{w.nodes} 个节点</span></button>)}
    </aside>}
    {cur ? <div className="wf-main">
      <div className="wf-bar">
        {listFold ? <button className="wf-fold" title="展开工作流列表" onClick={() => fold(false)}>»</button> : null}
        <input className="wf-name" name="wf-name" value={name} onChange={(e) => { setName(e.target.value); setDirty(true); }} />
        <button className="wf-btn" onClick={save} disabled={busy === 'save'}>{dirty ? '保存*' : '保存'}</button>
        <button className="wf-btn" onClick={startRun} disabled={run && run.status === 'running'}>运行</button>
        {run && run.status === 'running' ? <button className="wf-btn ghost danger" onClick={cancelRun}>停止</button> : null}
        <button className="wf-btn ghost" onClick={undo} title="撤销">撤销</button>
        <button className="wf-btn ghost" onClick={redo} title="重做">重做</button>
        <span className="wf-zoom" title="缩放：Ctrl + 滚轮、触控板双指捏合也行">
          <button name="zoom-out" title="缩小" onClick={() => ed.current && zoomStep(ed.current.ctx, -1)}>−</button>
          <button name="zoom-100" className="pct" title="回到 100%" onClick={() => ed.current && zoomTo(ed.current.ctx, 1)}>{zoom}%</button>
          <button name="zoom-in" title="放大" onClick={() => ed.current && zoomStep(ed.current.ctx, 1)}>＋</button>
        </span>
        <button className="wf-btn ghost" onClick={() => ed.current && fitCanvas(ed.current.ctx, true)}>适应窗口</button>
        <span className="wf-sp" />
        <button className="wf-btn ghost" onClick={exportWf}>导出</button>
        <button className="wf-btn ghost" disabled={!cur.id} onClick={() => setDialog({ kind: 'shortcut', name: (cur.shortcut && cur.shortcut.name) || cur.name || '' })}>存成快捷指令</button>
        <button className="wf-btn ghost danger" disabled={!cur.id} onClick={del}>删除</button>
      </div>
      <div className="wf-ai">
        <input name="ai" placeholder="让 AI 改：例如「在代码节点后面加一个大模型节点总结结果」" value={aiText} onChange={(e) => setAiText(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') aiEdit(); }} />
        <button className="wf-btn" onClick={aiEdit} disabled={busy === 'ai'}>{busy === 'ai' ? '改图中…' : '让 AI 改'}</button>
      </div>
      <div className="wf-body">
        <div className="wf-palette">
          <div className="wf-sec">节点（拖进画布）</div>
          {PALETTE.map((t) => <div key={t} className={'wf-pal t-' + t} data-type={t} onMouseDown={(e) => addNode(t, null, e)} onDoubleClick={() => addNode(t)}>{ZH[t]}</div>)}
          <div className="wf-sec">技能</div>
          {palette.length ? null : <div className="wf-hint">还没装技能（去「技能」页）</div>}
          {palette.map((k) => <div key={k.id} className="wf-pal t-skill" data-skill={k.id} title={k.description} onMouseDown={(e) => addNode('skill', JSON.parse(JSON.stringify(k.node)), e)} onDoubleClick={() => addNode('skill', JSON.parse(JSON.stringify(k.node)))}>{k.name}</div>)}
        </div>
        <div className="wf-canvas">
          <Canvas key={canvasKey} initial={{ nodes: cur.nodes || [], edges: cur.edges || [] }}
            onReady={(x) => { ed.current = x; }} onSelect={setSelected} onChange={onChange} onZoom={setZoom} />
          {selected ? null : <div className="wf-tip">点节点改设置 · 从左边拖节点进来 · 从小圆点拖线连接 · 滚轮 / 按住空格拖动来移动 · Ctrl + 滚轮缩放</div>}
        </div>
        <NodePanel node={selected} caps={caps} conns={conns} skills={skills} onPatch={() => setDirty(true)} onRelink={relink} onClose={() => { const e = ed.current; if (e) e.sel.clear ? e.sel.clear() : (e.sel.selection = []); setSelected(null); }} />
      </div>
      {issues.filter((i) => i.fatal || i.code === 'unreachable').length ? <div className="wf-issues">{issues.filter((i) => i.fatal || i.code === 'unreachable').slice(0, 4).map((i, k) => <div key={k} className={i.fatal ? 'err' : ''}>{i.fatal ? '✗ ' : '· '}{i.text}</div>)}</div> : null}
      {run ? <RunLog run={run} onClose={() => { setRun(null); store.set({ runNodes: {} }); }} /> : null}
    </div> : <div className="wf-main wf-empty">选一个工作流，或者「新建工作流」。<br />工作流把多个步骤串起来：大模型、条件、循环、代码、技能、接口……一次跑完。</div>}
    {proposal ? <DiffDialog p={proposal} onApply={applyProposal} onDiscard={discardProposal} /> : null}
    {dialog && dialog.kind === 'export' ? <Modal title="导出（不含任何 key）" onClose={() => setDialog(null)}>
      <div className="wf-hint">发给别人后，对方导入时会提示缺哪些技能和连接；key 要对方自己填。</div>
      <textarea className="mono" name="export" rows={16} readOnly value={dialog.text} />
      <button className="wf-btn" onClick={() => { navigator.clipboard && navigator.clipboard.writeText(dialog.text); snack('已复制'); }}>复制</button>
    </Modal> : null}
    {dialog && dialog.kind === 'import' ? <ImportDialog api={api} snack={snack} onDone={(id) => { setDialog(null); loadList(); if (id) open(id); }} onClose={() => setDialog(null)} /> : null}
    {dialog && dialog.kind === 'input' ? <Modal title="运行参数" onClose={() => setDialog(null)}>
      <SchemaForm schema={dialog.schema} value={dialog.values} onChange={(v) => setDialog({ ...dialog, values: v })} />
      <button className="wf-btn" onClick={() => { const v = {}; Object.entries(dialog.values || {}).forEach(([k, x]) => { const p = dialog.schema.properties[k] || {}; if ((p.type === 'array' || p.type === 'object') && typeof x === 'string') { try { v[k] = JSON.parse(x); } catch (e) { v[k] = x; } } else v[k] = x; }); setDialog(null); doRun(v); }}>开始运行</button>
    </Modal> : null}
    {dialog && dialog.kind === 'shortcut' ? <Modal title="存成快捷指令" onClose={() => setDialog(null)}>
      <Field label="快捷指令名字" hint="存好后在左边「快捷指令」里一键运行；留空 = 取消快捷指令"><Txt name="sc-name" value={dialog.name} onChange={(v) => setDialog({ ...dialog, name: v })} /></Field>
      <button className="wf-btn" onClick={async () => { if (dirty) { const s = await save(); if (!s.ok) return; } const r = await api('wf', { op: 'shortcut_save', id: cur.id, name: dialog.name }); snack(r.text); if (r.ok) { setCur(r.workflow); setDialog(null); loadList(); } }}>保存</button>
    </Modal> : null}
  </div>;
}

function RunLog({ run, onClose }) {
  const box = useRef(null);
  useEffect(() => { if (box.current) box.current.scrollTop = box.current.scrollHeight; }, [run.log && run.log.length]);
  return <div className="wf-run">
    <div className="wf-run-h"><b>运行日志</b> <span className={'wf-st st-' + run.status}>{run.status_zh}</span>{run.error ? <span className="wf-err">{run.error}</span> : null}<span className="wf-sp" /><button className="wf-x" onClick={onClose}>×</button></div>
    <div className="wf-run-log" ref={box}>{(run.log || []).map((e, i) => <div key={i} className={'l-' + e.status}>{new Date(e.at).toLocaleTimeString()} {e.text}{e.ms ? <span className="ms"> {e.ms} 毫秒</span> : null}</div>)}</div>
    {run.status === 'done' && run.result ? <div className="wf-result">结果：<code>{clip(jstr(run.result), 600)}</code></div> : null}
  </div>;
}

function Modal({ title, children, onClose }) {
  return <div className="wf-modal-bg" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
    <div className="wf-modal"><div className="wf-modal-h"><b>{title}</b><button className="wf-x" onClick={onClose}>×</button></div>{children}</div>
  </div>;
}

function DiffDialog({ p, onApply, onDiscard }) {
  const d = p.diff || {};
  const line = (n) => <span>{n.type_zh}「{n.title}」<code>{n.id}</code></span>;
  return <Modal title={(p.by === 'AI' || p.by === '对话' ? 'AI 想这样改' : '修改预览') + '（确认后才生效）'} onClose={onDiscard}>
    <div className="wf-diff">
      <div className="wf-hint">{d.summary}</div>
      {(d.added || []).map((n, i) => <div key={'a' + i} className="add">＋ 加 {line(n)}</div>)}
      {(d.removed || []).map((n, i) => <div key={'r' + i} className="del">－ 删 {line(n)}</div>)}
      {(d.changed || []).map((n, i) => <div key={'c' + i} className="chg">～ 改 {line(n)}：{(n.fields || []).join('、')}</div>)}
      {(d.edges_added || []).map((e, i) => <div key={'ea' + i} className="add">＋ 连线 {e}</div>)}
      {(d.edges_removed || []).map((e, i) => <div key={'er' + i} className="del">－ 连线 {e}</div>)}
    </div>
    <div className="wf-row"><button className="wf-btn" onClick={onApply}>采用</button><button className="wf-btn ghost" onClick={onDiscard}>放弃</button><span className="wf-hint">采用后可以点「撤销」退回去</span></div>
  </Modal>;
}

function ImportDialog({ api, snack, onDone, onClose }) {
  const [text, setText] = useState('');
  const [chk, setChk] = useState(null);
  return <Modal title="导入工作流" onClose={onClose}>
    <textarea className="mono" name="import" rows={10} placeholder="把导出的 JSON 粘贴到这里" value={text} onChange={(e) => { setText(e.target.value); setChk(null); }} />
    {chk ? <div className="wf-check">
      <div>{chk.text}</div>
      {(chk.missing_skills || []).map((s, i) => <div key={'s' + i} className="err">缺技能「{s.id}」：{s.why}</div>)}
      {(chk.missing_connectors || []).map((c, i) => <div key={'c' + i} className="err">缺连接「{c.name || c.id}」{c.url ? '（' + c.url + '）' : ''}：{c.why}</div>)}
    </div> : null}
    <div className="wf-row">
      <button className="wf-btn ghost" onClick={async () => setChk(await api('wf', { op: 'import_check', json: text }))}>检查</button>
      <button className="wf-btn" onClick={async () => { const r = await api('wf', { op: 'import', json: text }); snack(r.text); if (r.ok) onDone(r.workflow && r.workflow.id); else setChk(r); }}>导入</button>
    </div>
  </Modal>;
}

// ---------- 技能页 ----------
function SkillsTab({ api, snack }) {
  const [skills, setSkills] = useState([]);
  const [src, setSrc] = useState('');
  const [busy, setBusy] = useState(false);
  const load = async () => { const r = await api('wf', { op: 'skills' }); if (r && r.ok) setSkills(r.skills || []); };
  useEffect(() => { load(); }, []);
  const install = async (op, extra) => {
    setBusy(true);
    snack('等你在确认卡上批准（卡上列了它要的权限）');
    const r = await api('wf', { op, ...(extra || {}) });
    setBusy(false); snack(r.text); load();
  };
  return <div className="wf-page">
    <h2>技能</h2>
    <div className="wf-hint">技能是一个文件夹：SKILL.md 写说明，skill.json 声明输入、输出和权限，scripts/ 里是脚本。装好后在工作流的节点库里直接能用。安装每次都要你在确认卡上批准，不能「记住」。</div>
    <div className="wf-row">
      <input name="skill-src" placeholder="技能文件夹路径、.zip 文件路径，或 https 的 zip 地址" value={src} onChange={(e) => setSrc(e.target.value)} />
      <button className="wf-btn" disabled={busy || !src.trim()} onClick={() => install('skill_install', { source: src.trim() })}>安装</button>
      <button className="wf-btn ghost" disabled={busy} onClick={() => install('skill_install_example')}>导入示例技能</button>
    </div>
    {skills.length ? null : <div className="card-empty">还没装技能</div>}
    {skills.map((k) => <div key={k.id} className="wf-card">
      <div><b>{k.name}</b> <span className="wf-hint">v{k.version}　风险：{RISK_ZH[k.risk]}</span>{k.runnable ? <span className="wf-tag ok">能当节点</span> : <span className="wf-tag">只能当说明</span>}</div>
      <div className="wf-hint">{k.description}</div>
      <div className="wf-hint">权限：{(k.perm_lines || []).join('；')}</div>
      {k.reason ? <div className="wf-hint err">{k.reason}</div> : null}
      <div className="wf-hint">输入：{(k.fields || []).map((f) => f.title).join('、') || '无'}　输出：{(k.out_fields || []).map((f) => f.title).join('、') || '无'}</div>
      <button className="wf-btn ghost sm danger" onClick={async () => { if (!confirm('删除技能「' + k.name + '」？')) return; const r = await api('wf', { op: 'skill_uninstall', id: k.id }); snack(r.text); load(); }}>删除</button>
    </div>)}
  </div>;
}

// ---------- 连接页 ----------
const TYPES = [['llm', '大模型'], ['image', '图片'], ['video', '视频'], ['backend', '后台接口']];
const MODES = [['bearer', 'Bearer（Authorization 头）'], ['header', '自定义请求头'], ['query', '网址参数'], ['none', '不用 key']];
function ConnTab({ api, snack }) {
  const [list, setList] = useState([]);
  const [oauth, setOauth] = useState([]);
  const [where, setWhere] = useState('');
  const [f, setF] = useState(null);
  const [det, setDet] = useState(null);
  const [msg, setMsg] = useState('');
  const [pend, setPend] = useState({});
  const [cid, setCid] = useState({});
  const load = async () => { const r = await api('wf', { op: 'conn_list' }); if (r && r.ok) { setList(r.connectors || []); setOauth(r.oauth || []); setWhere(r.where || ''); } };
  useEffect(() => { load(); }, []);
  useEffect(() => {
    if (!f || !f.url) { setDet(null); return; }
    const t = setTimeout(async () => setDet(await api('wf', { op: 'conn_detect', url: f.url, type: f.type })), 250);
    return () => clearTimeout(t);
  }, [f && f.url, f && f.type]);
  const set = (k, v) => setF({ ...f, [k]: v });
  const save = async () => {
    const r = await api('wf', { op: 'conn_save', ...f });
    setMsg(r.text); snack(r.text);
    if (r.ok) { setF({ ...f, id: r.connector.id, key: '' }); load(); }
  };
  const test = async (id) => { setMsg('测试中…'); const r = await api('wf', { op: 'conn_test', id }); setMsg(r.text); snack(r.text); load(); };
  return <div className="wf-page">
    <h2>连接</h2>
    <div className="wf-hint">把任何 HTTP 接口接进 OneSay：填地址和 key，测试通过后就能在工作流里用。key 存在 {where || '系统凭据库'}，不会写进配置、日志、导出文件，也不会发给模型。</div>
    <div className="wf-row"><button className="wf-btn" onClick={() => { setF({ name: '', url: '', key: '', key_mode: '', key_name: '', type: 'llm', note: '', costs: false, test_path: '' }); setMsg(''); }}>＋ 添加连接</button></div>
    {f ? <div className="wf-card wf-form">
      <Field label="名称"><Txt name="c-name" value={f.name} onChange={(v) => set('name', v)} placeholder="例如：我的绘图接口" /></Field>
      <Field label="API 类型"><select name="c-type" value={f.type} onChange={(e) => set('type', e.target.value)}>{TYPES.map(([k, z]) => <option key={k} value={k}>{z}</option>)}</select></Field>
      <Field label="请求地址" hint={det ? det.text : '例如 https://api.deepseek.com/v1'}><Txt name="c-url" value={f.url} onChange={(v) => set('url', v)} placeholder="https://…" /></Field>
      <Field label="key" hint={f.id ? '留空 = 不改原来的 key' : '只存进系统凭据库'}><input name="c-key" type="password" autoComplete="off" value={f.key} onChange={(e) => set('key', e.target.value)} /></Field>
      <Field label="key 的放法"><select name="c-mode" value={f.key_mode || (det && det.key_mode) || 'bearer'} onChange={(e) => set('key_mode', e.target.value)}>{MODES.map(([k, z]) => <option key={k} value={k}>{z}</option>)}</select></Field>
      {(f.key_mode || (det && det.key_mode)) === 'header' || (f.key_mode || (det && det.key_mode)) === 'query' ? <Field label={(f.key_mode || det.key_mode) === 'header' ? '请求头名字' : '参数名'}><Txt name="c-kname" value={f.key_name || (det && det.key_name) || ''} onChange={(v) => set('key_name', v)} placeholder={(f.key_mode || det.key_mode) === 'header' ? 'X-API-Key' : 'key'} /></Field> : null}
      <Field label={'备注' + (det && det.cold ? '（没认出来是哪家，必须写清楚这是什么服务）' : '（可不填）')}><Txt name="c-note" value={f.note} onChange={(v) => set('note', v)} placeholder="例如：公司内部的报表接口" /></Field>
      <Field label="测试路径（可不填）" hint="冷门接口可以填一个能 GET 的路径用来测试"><Txt name="c-tpath" value={f.test_path} onChange={(v) => set('test_path', v)} placeholder="/health" /></Field>
      <label className="wf-check"><input type="checkbox" checked={!!f.costs} onChange={(e) => set('costs', e.target.checked)} /> 调用会花钱（POST 等算「花钱」档）</label>
      <div className="wf-row"><button className="wf-btn" onClick={save}>保存</button>{f.id ? <button className="wf-btn ghost" onClick={() => test(f.id)}>测试</button> : null}<button className="wf-btn ghost" onClick={() => setF(null)}>收起</button></div>
      {msg ? <div className="wf-msg">{msg}</div> : null}
    </div> : null}
    {list.map((c) => <div key={c.id} className="wf-card" data-conn={c.id}>
      <div><b>{c.name}</b> <span className="wf-hint">{c.type_zh} · {c.preset_name}</span> {c.verified ? <span className="wf-tag ok">已测试通过</span> : <span className="wf-tag">还没测试通过</span>}</div>
      <div className="wf-hint">{c.url}　key：{c.key_mode === 'none' ? '不用' : c.has_key ? '已存（不显示）' : '没填'}{c.note ? '　备注：' + c.note : ''}</div>
      <div className="wf-row">
        <button className="wf-btn ghost sm" onClick={() => test(c.id)}>测试</button>
        <button className="wf-btn ghost sm" onClick={() => { setF({ id: c.id, name: c.name, url: c.url, key: '', key_mode: c.key_mode, key_name: c.key_name, type: c.type, note: c.note, costs: c.costs, test_path: c.test_path }); setMsg(''); }}>修改</button>
        <button className="wf-btn ghost sm danger" onClick={async () => { if (!confirm('删除连接「' + c.name + '」？key 也会从凭据库删掉')) return; const r = await api('wf', { op: 'conn_delete', id: c.id }); snack(r.text); load(); }}>删除</button>
      </div>
    </div>)}
    <h3>账号授权（OAuth）</h3>
    <div className="wf-hint">用你自己在服务商那里注册的应用（填 client_id）。令牌只存进系统凭据库。</div>
    {oauth.map((o) => <div key={o.id} className="wf-card">
      <div><b>{o.name}</b> <span className="wf-hint">{o.flow_zh}　范围 {o.scopes}</span> {o.connected ? <span className="wf-tag ok">已连接</span> : null}</div>
      {!o.client_id_set ? <div className="wf-row"><input placeholder="client_id" value={cid[o.id] || ''} onChange={(e) => setCid({ ...cid, [o.id]: e.target.value })} /><button className="wf-btn ghost sm" onClick={async () => { const r = await api('wf', { op: 'oauth_client', id: o.id, client_id: cid[o.id] || '' }); snack(r.text); load(); }}>记下</button></div> : null}
      <div className="wf-row">
        {o.connected ? <button className="wf-btn ghost sm danger" onClick={async () => { const r = await api('wf', { op: 'oauth_disconnect', id: o.id }); snack(r.text); load(); }}>断开</button>
          : <button className="wf-btn ghost sm" disabled={!o.client_id_set} onClick={async () => {
            const r = await api('wf', { op: 'oauth_start', id: o.id }); snack(r.text);
            if (r.ok) { setPend({ ...pend, [o.id]: r.pending }); const tick = async () => { const p = await api('wf', { op: 'oauth_poll', pending: r.pending.id }); if (p && p.ok) { setPend((x) => ({ ...x, [o.id]: p.pending })); if (p.pending.state === 'waiting') setTimeout(tick, 2000); else load(); } }; setTimeout(tick, 2000); }
          }}>连接</button>}
      </div>
      {pend[o.id] && pend[o.id].state === 'waiting' && pend[o.id].user_code ? <div className="wf-msg">在浏览器打开 <b>{pend[o.id].verify_url}</b>，输入验证码 <b className="mono">{pend[o.id].user_code}</b></div> : null}
      {pend[o.id] && pend[o.id].error ? <div className="wf-msg err">{pend[o.id].error}</div> : null}
    </div>)}
  </div>;
}

// ---------- 挂载 ----------
let root = null;
window.OneSayWorkflow = {
  mount(el, opts) {
    if (!document.getElementById('wf-css')) {
      const st = document.createElement('style');
      st.id = 'wf-css';
      st.textContent = editorCss + '\n' + pageCss;
      document.head.appendChild(st);
    }
    el.innerHTML = '';
    root = createRoot(el);
    root.render(<App api={opts.api} snack={opts.snack || ((t) => console.log(t))} live={opts.live} />);
  },
};
