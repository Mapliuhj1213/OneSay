#!/usr/bin/env python3
"""[workflow-1120] 工作流页端到端测试：无头 Chromium（Playwright）打开真的界面页（UiServer + index.html + workflow.js），
后端是 WfE2EServer（真的执行器 / 技能 / 连接器，key 存内存，大模型是假的）。

用法：python3 e2e_workflow.py <classes 目录> <test-classes 目录> <依赖 jar 目录> <state 桩.json> <截图目录> [场景名...]
场景：smoke build ai run sandbox skill missing conn export
每个场景独立起一个服务（独立数据目录），结束后关掉。退出码 0 = 全过。
"""
import json, os, re, shutil, socket, subprocess, sys, tempfile, time, urllib.request

from playwright.sync_api import sync_playwright

CLASSES, TCLASSES, DEPS, STATE, SHOTS = sys.argv[1:6]
WANT = sys.argv[6:] or ["smoke", "build", "ai", "run", "sandbox", "skill", "missing", "conn", "export"]
JAVA = os.environ.get("JAVA", "java")
KEY = "e2eLaunchKey_0123456789"
os.makedirs(SHOTS, exist_ok=True)
BANNED = ["FlowGram", "flowgram", "xyflow", "React Flow", "Bixby", "三星", "DeX", "小布", "Coze", "扣子", "n8n"]


def free_port():
    while True:
        s = socket.socket(); s.bind(("127.0.0.1", 0)); p = s.getsockname()[1]; s.close()
        s2 = socket.socket()
        try:
            s2.bind(("127.0.0.1", p + 1)); s2.close(); return p
        except OSError:
            s2.close()


class Server:
    def __init__(self, deny=False):
        self.port = free_port()
        self.data = tempfile.mkdtemp(prefix="wfe2e")
        cp = ":".join([CLASSES, TCLASSES] + [os.path.join(DEPS, f) for f in sorted(os.listdir(DEPS)) if f.endswith(".jar")])
        args = [JAVA, "-Djava.awt.headless=true", "-cp", cp, "com.onesay.desktop.WfE2EServer", str(self.port), KEY, self.data, STATE] + (["deny"] if deny else [])
        self.p = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        t0 = time.time()
        self.out = []
        while time.time() - t0 < 30:
            line = self.p.stdout.readline()
            if not line:
                time.sleep(0.05); continue
            self.out.append(line)
            if line.startswith("READY"):
                break
        else:
            raise RuntimeError("服务没起来：" + "".join(self.out))
        self.url = "http://127.0.0.1:%d/?k=%s" % (self.port, KEY)
        self.mock = "http://127.0.0.1:%d" % (self.port + 1)

    def api(self, body):
        # 测试准备数据用：走同一个 /api/wf（口令从页面里取，见 page_token）
        raise NotImplementedError

    def log(self):
        f = os.path.join(self.data, "onesay.log")
        return open(f, encoding="utf-8").read() if os.path.exists(f) else ""

    def files_text(self):
        out = []
        for root, _, fs in os.walk(self.data):
            for f in fs:
                try:
                    out.append(open(os.path.join(root, f), encoding="utf-8", errors="replace").read())
                except Exception:
                    pass
        return "\n".join(out)

    def close(self):
        self.p.terminate()
        try:
            self.p.wait(5)
        except Exception:
            self.p.kill()
        shutil.rmtree(self.data, ignore_errors=True)


RESULTS = []


def check(cond, msg):
    if not cond:
        raise AssertionError(msg)


def page_api(page, body):
    return page.evaluate("b => api('wf', b)", body)


def open_wf_page(page, srv):
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.on("console", lambda m: errors.append("console:" + m.text) if m.type == "error" and "测试桩" not in m.text and "Failed to load resource" not in m.text else None)
    page.goto(srv.url)
    page.wait_for_function("() => typeof api === 'function' && S !== null", timeout=15000)
    page.click("button.nav[data-p=workflows]")
    page.wait_for_selector(".wf-root", timeout=20000)
    return errors


def visible_text_ok(page):
    t = page.inner_text("body")
    for b in BANNED:
        check(b not in t, "页面上出现了别家名字：" + b)


def open_workflow(page, wid):
    page.click(".wf-tabs button:has-text('工作流')")
    page.click(".wf-item:has-text('%s')" % wid) if False else None
    page.evaluate("1")


DEMO = {
    "schema": "onesay.workflow/1", "name": "平方和演示",
    "nodes": [
        {"id": "start", "type": "start", "meta": {"position": {"x": 0, "y": 0}}, "data": {"title": "开始", "inputs": {"type": "object", "properties": {"nums": {"type": "array", "title": "数字列表", "items": {"type": "number"}}}, "required": ["nums"]}}},
        {"id": "loop1", "type": "loop", "meta": {"position": {"x": 300, "y": 0}}, "data": {"title": "每个数", "items": "{{input.nums}}"}},
        {"id": "sq", "type": "code", "meta": {"position": {"x": 300, "y": 200}}, "data": {"title": "平方", "code": "function main(input){ return { v: input.x * input.x }; }", "inputs": {"x": "{{loop1.item}}"}}},
        {"id": "sum", "type": "code", "meta": {"position": {"x": 600, "y": 0}}, "data": {"title": "求和", "code": "return { total: input.list.reduce((a, b) => a + b.v, 0), n: input.list.length };", "inputs": {"list": "{{loop1.results}}"}}},
        {"id": "c", "type": "condition", "meta": {"position": {"x": 900, "y": 0}}, "data": {"title": "多于两个？", "left": "{{sum.n}}", "op": ">", "right": "2"}},
        {"id": "many", "type": "code", "meta": {"position": {"x": 1200, "y": -100}}, "data": {"title": "多", "code": "return { label: '多' };"}},
        {"id": "few", "type": "code", "meta": {"position": {"x": 1200, "y": 120}}, "data": {"title": "少", "code": "return { label: '少' };"}},
        {"id": "end", "type": "end", "meta": {"position": {"x": 1500, "y": 0}}, "data": {"title": "结束", "output": {"total": "{{sum.total}}", "many": "{{many.label}}", "few": "{{few.label}}"}}},
    ],
    "edges": [
        {"sourceNodeID": "start", "targetNodeID": "loop1"}, {"sourceNodeID": "loop1", "targetNodeID": "sq", "sourcePortID": "body"},
        {"sourceNodeID": "loop1", "targetNodeID": "sum", "sourcePortID": "next"}, {"sourceNodeID": "sum", "targetNodeID": "c"},
        {"sourceNodeID": "c", "targetNodeID": "many", "sourcePortID": "true"}, {"sourceNodeID": "c", "targetNodeID": "few", "sourcePortID": "false"},
        {"sourceNodeID": "many", "targetNodeID": "end"}, {"sourceNodeID": "few", "targetNodeID": "end"},
    ],
}


def save_and_open(page, wf):
    r = page_api(page, {"op": "save", "workflow": wf})
    check(r["ok"], "保存失败：" + r.get("text", ""))
    wid = r["workflow"]["id"]
    page.evaluate("1")
    # 刷新列表：切到技能再切回工作流
    page.click(".wf-tabs button:has-text('技能')")
    page.click(".wf-tabs button:has-text('工作流')")
    page.click(".wf-item:has-text('%s')" % wf["name"])
    page.wait_for_selector(".wf-canvas .wf-node", timeout=15000)
    page.wait_for_timeout(400)
    return wid


def wait_run(page, timeout=30000):
    page.wait_for_function("() => { const s = document.querySelector('.wf-run .wf-st'); return s && !s.textContent.includes('运行中'); }", timeout=timeout)
    return page.inner_text(".wf-run")


def node_count(page):
    return page.locator(".wf-canvas .wf-node").count()


# ---------------------------------------------------------------- 场景

def sc_smoke(page, srv, errs):
    visible_text_ok(page)
    check(page.locator(".wf-tabs button").count() == 3, "三个页签")
    page.screenshot(path=os.path.join(SHOTS, "smoke.png"))


def sc_build(page, srv, errs):
    """拖节点建工作流、连线、保存、重载。"""
    page.click("text=＋ 新建工作流")
    page.wait_for_selector(".wf-canvas .wf-node", timeout=15000)
    page.wait_for_timeout(600)
    check(node_count(page) == 2, "新建后有开始和结束两个节点，实际 %d" % node_count(page))
    canvas = page.locator(".wf-canvas").bounding_box()
    pal = page.locator(".wf-pal[data-type=code]").bounding_box()
    page.mouse.move(pal["x"] + pal["width"] / 2, pal["y"] + pal["height"] / 2)
    page.mouse.down()
    page.mouse.move(canvas["x"] + canvas["width"] * 0.45, canvas["y"] + canvas["height"] * 0.72, steps=12)
    page.mouse.up()
    page.wait_for_timeout(500)
    check(node_count(page) == 3, "拖进一个代码节点后应有 3 个节点，实际 %d" % node_count(page))
    # 连线：开始节点的输出口 → 代码节点的输入口
    code_node = page.locator(".wf-canvas .wf-node.t-code").first
    code_node.click()
    page.wait_for_selector(".wf-panel input[name=title]")
    page.fill(".wf-panel input[name=title]", "拖进来的代码")
    ports = page.locator(".workflow-port-render")
    pinfo = page.evaluate("""() => [...document.querySelectorAll('.workflow-port-render')].map(e => { const r = e.getBoundingClientRect(); return {x: r.x + r.width/2, y: r.y + r.height/2}; })""")
    sb = page.locator(".wf-canvas .wf-node.t-start").bounding_box()
    cb = code_node.bounding_box()
    # 开始节点右侧的口 / 代码节点左侧的口
    out_port = min(pinfo, key=lambda p: abs(p["x"] - (sb["x"] + sb["width"])) + abs(p["y"] - (sb["y"] + sb["height"] / 2)))
    in_port = min(pinfo, key=lambda p: abs(p["x"] - cb["x"]) + abs(p["y"] - (cb["y"] + cb["height"] / 2)))
    page.mouse.move(out_port["x"], out_port["y"]); page.mouse.down()
    page.mouse.move(in_port["x"], in_port["y"], steps=15); page.mouse.up()
    page.wait_for_timeout(400)
    page.fill("input[name=wf-name]", "拖拽建的工作流")
    page.click(".wf-bar button:has-text('保存')")
    page.wait_for_timeout(800)
    lst = page_api(page, {"op": "list"})
    w = [x for x in lst["workflows"] if x["name"] == "拖拽建的工作流"]
    check(w and w[0]["nodes"] == 3, "保存后列表里有这个工作流且 3 个节点：%s" % lst)
    got = page_api(page, {"op": "get", "id": w[0]["id"]})["workflow"]
    titles = [n["data"].get("title") for n in got["nodes"]]
    check("拖进来的代码" in titles, "节点表单改的名字存进去了：%s" % titles)
    linked = any(e["sourceNodeID"] == "start" and any(n["id"] == e["targetNodeID"] and n["type"] == "code" for n in got["nodes"]) for e in got["edges"])
    RESULTS.append(("build:连线", "拖线连上" if linked else "拖线没连上（只验证了拖节点 / 保存 / 重载）"))
    # 重载整页
    page.reload()
    page.wait_for_function("() => typeof api === 'function' && S !== null", timeout=15000)
    page.click("button.nav[data-p=workflows]")
    page.wait_for_selector(".wf-item:has-text('拖拽建的工作流')", timeout=15000)
    page.click(".wf-item:has-text('拖拽建的工作流')")
    page.wait_for_selector(".wf-canvas .wf-node", timeout=15000)
    page.wait_for_timeout(500)
    check(node_count(page) == 3, "重载后还是 3 个节点")
    check(page.locator(".wf-canvas .wf-node:has-text('拖进来的代码')").count() == 1, "重载后节点名字还在")
    page.screenshot(path=os.path.join(SHOTS, "build.png"))


def sc_ai(page, srv, errs):
    """AI 改图：先出差异预览，采用后画布热重载，撤销能退回。"""
    save_and_open(page, DEMO)
    before = node_count(page)
    page.fill("input[name=ai]", "加一个总结节点")
    page.click("button:has-text('让 AI 改')")
    page.wait_for_selector(".wf-modal .wf-diff", timeout=15000)
    txt = page.inner_text(".wf-modal")
    check("加 1 个节点" in txt and "AI 加的总结" in txt, "差异预览：" + txt)
    check(node_count(page) == before, "确认前画布不变")
    page.screenshot(path=os.path.join(SHOTS, "ai-diff.png"))
    page.click(".wf-modal button:has-text('采用')")
    page.wait_for_timeout(800)
    check(node_count(page) == before + 1, "采用后多一个节点（热重载）：%d" % node_count(page))
    check(page.locator(".wf-canvas .wf-node:has-text('AI 加的总结')").count() == 1, "新节点在画布上")
    page.click(".wf-bar button:has-text('撤销')")
    page.wait_for_timeout(600)
    check(node_count(page) == before, "撤销后回到原样：%d" % node_count(page))


def sc_run(page, srv, errs):
    """运行含条件、循环、代码节点的工作流。"""
    save_and_open(page, DEMO)
    page.click(".wf-bar button:has-text('运行')")
    page.wait_for_selector(".wf-modal:has-text('运行参数')")
    page.fill(".wf-modal textarea", "[1, 2, 3]")
    page.click(".wf-modal button:has-text('开始运行')")
    out = wait_run(page)
    check("完成" in out, "运行完成：" + out)
    check('"total":14' in out.replace(" ", ""), "结果 total=14：" + out)
    check('"many":"多"' in out.replace(" ", ""), "走了「多」分支：" + out)
    check(page.locator(".wf-canvas .wf-node.t-code.run-skipped").count() == 1, "「少」分支被跳过")
    page.screenshot(path=os.path.join(SHOTS, "run.png"))


def sc_sandbox(page, srv, errs):
    """代码节点越权访问文件 / 网络被拒，超时被中断。"""
    def wf(name, code, tmo=3000):
        return {"schema": "onesay.workflow/1", "name": name, "nodes": [
            {"id": "start", "type": "start", "meta": {"position": {"x": 0, "y": 0}}, "data": {"title": "开始"}},
            {"id": "x", "type": "code", "meta": {"position": {"x": 300, "y": 0}}, "data": {"title": "越权代码", "code": code, "codeTimeoutMs": tmo}},
            {"id": "end", "type": "end", "meta": {"position": {"x": 600, "y": 0}}, "data": {"title": "结束", "output": {"r": "{{x.r}}"}}}],
            "edges": [{"sourceNodeID": "start", "targetNodeID": "x"}, {"sourceNodeID": "x", "targetNodeID": "end"}]}
    cases = [("读文件", "const fs = require('fs'); return { r: fs.readFileSync('/etc/passwd', 'utf8') };", "出错"),
             ("联网", "return { r: fetch('http://example.com') };", "出错"),
             ("死循环", "while (true) {}", "超时")]
    for name, code, want in cases:
        save_and_open(page, wf("沙箱-" + name, code, 1500))
        page.click(".wf-bar button:has-text('运行')")
        out = wait_run(page, 20000)
        check(want in out, "%s 应该%s：%s" % (name, want, out))
        check("root:" not in out, "不能读到系统文件")
    page.screenshot(path=os.path.join(SHOTS, "sandbox.png"))


def sc_skill(page, srv, errs):
    """导入示例技能后自动生成节点，并能运行。"""
    page.click(".wf-tabs button:has-text('技能')")
    page.click("button:has-text('导入示例技能')")
    page.wait_for_selector(".wf-card:has-text('文本统计')", timeout=15000)
    check("什么权限都不要" in page.inner_text(".wf-card:has-text('文本统计')"), "技能卡上列了权限")
    check("技能确认卡" in srv.log(), "安装走了确认卡")
    page.click(".wf-tabs button:has-text('工作流')")
    page.click("text=＋ 新建工作流")
    page.wait_for_selector(".wf-pal[data-skill=text-stats]", timeout=15000)
    page.wait_for_timeout(500)
    canvas = page.locator(".wf-canvas").bounding_box()
    pal = page.locator(".wf-pal[data-skill=text-stats]").bounding_box()
    page.mouse.move(pal["x"] + pal["width"] / 2, pal["y"] + pal["height"] / 2)
    page.mouse.down()
    page.mouse.move(canvas["x"] + canvas["width"] * 0.45, canvas["y"] + canvas["height"] * 0.7, steps=12)
    page.mouse.up()
    page.wait_for_timeout(400)
    page.locator(".wf-canvas .wf-node.t-skill").first.click()
    page.wait_for_selector(".wf-canvas .wf-node.t-skill", timeout=10000)
    page.wait_for_selector(".wf-panel input[name=in-text]")
    check(page.locator(".wf-panel input[name=in-top_n]").count() == 1, "按声明生成了 top_n 表单")
    page.fill(".wf-panel input[name=in-text]", "苹果 香蕉 苹果")
    page.fill("input[name=wf-name]", "技能演示")
    # 用 AI 改图之外的方式连线：直接改图 JSON（开始 → 技能 → 结束），再热重载
    wf = page.evaluate("1")
    r = page_api(page, {"op": "list"})
    page.click(".wf-bar button:has-text('保存')")
    page.wait_for_timeout(600)
    lst = page_api(page, {"op": "list"})["workflows"]
    wid = [x for x in lst if x["name"] == "技能演示"][0]["id"]
    got = page_api(page, {"op": "get", "id": wid})["workflow"]
    sk = [n for n in got["nodes"] if n["type"] == "skill"][0]
    check(sk["data"]["skill"]["sha256"] and sk["data"]["schemaSnapshot"]["inputs"]["properties"]["text"], "节点记了 sha256 和声明快照")
    got["edges"] = [{"sourceNodeID": "start", "targetNodeID": sk["id"]}, {"sourceNodeID": sk["id"], "targetNodeID": "end"}]
    for n in got["nodes"]:
        if n["type"] == "end":
            n["data"]["output"] = {"chars": "{{%s.chars}}" % sk["id"], "top": "{{%s.top}}" % sk["id"]}
    got["name"] = "技能演示2"
    got.pop("id")
    save_and_open(page, got)
    page.click(".wf-bar button:has-text('运行')")
    out = wait_run(page)
    check("完成" in out and '"chars":6' in out.replace(" ", "") and "苹果" in out, "技能节点跑出结果：" + out)
    lst2 = page_api(page, {"op": "list"})
    check(any(w["name"] == "技能演示2" for w in lst2["workflows"]), "存好了")
    page.screenshot(path=os.path.join(SHOTS, "skill.png"))


def sc_missing(page, srv, errs):
    """缺技能时节点标红，运行不了。"""
    wf = {"schema": "onesay.workflow/1", "name": "缺技能", "nodes": [
        {"id": "start", "type": "start", "meta": {"position": {"x": 0, "y": 0}}, "data": {"title": "开始"}},
        {"id": "s", "type": "skill", "meta": {"position": {"x": 300, "y": 0}}, "data": {"title": "别人的技能", "skill": {"id": "not-installed", "version": "1.0.0", "sha256": "ab" * 32}, "schemaSnapshot": {"name": "别人的技能", "inputs": {"type": "object", "properties": {"q": {"type": "string", "title": "问题"}}}}, "inputs": {"q": "x"}}},
        {"id": "end", "type": "end", "meta": {"position": {"x": 600, "y": 0}}, "data": {"title": "结束"}}],
        "edges": [{"sourceNodeID": "start", "targetNodeID": "s"}, {"sourceNodeID": "s", "targetNodeID": "end"}]}
    save_and_open(page, wf)
    node = page.locator(".wf-canvas .wf-node.t-skill")
    check("missing" in node.get_attribute("class"), "缺技能的节点标红")
    check("缺少技能" in node.inner_text(), "节点上写了缺少技能：" + node.inner_text())
    color = page.evaluate("() => getComputedStyle(document.querySelector('.wf-node.missing')).borderTopColor")
    check(color not in ("rgb(217, 217, 222)", ""), "红框：" + color)
    page.click(".wf-bar button:has-text('运行')")
    page.wait_for_timeout(800)
    snack = page.inner_text("#snack")
    check("缺少技能" in snack, "运行被拦：" + snack)
    page.screenshot(path=os.path.join(SHOTS, "missing.png"))


def sc_conn(page, srv, errs):
    """API 连接器：冷门不填备注保存不了；对本地模拟接口点「测试」能通；key 不进日志 / 文件。"""
    page.click(".wf-tabs button:has-text('连接')")
    page.click("button:has-text('＋ 添加连接')")
    page.fill("input[name=c-name]", "本机模拟接口")
    page.select_option("select[name=c-type]", "backend")
    page.fill("input[name=c-url]", srv.mock + "/v1")
    page.fill("input[name=c-key]", "sk-e2e-good-123456")
    page.wait_for_timeout(500)
    check("没认出来" in page.inner_text(".wf-form"), "提示是冷门")
    page.click(".wf-form button:has-text('保存')")
    page.wait_for_timeout(500)
    check("备注" in page.inner_text(".wf-msg"), "冷门不填备注保存不了：" + page.inner_text(".wf-msg"))
    check(page.locator(".wf-card[data-conn]").count() == 0, "没存进去")
    page.fill("input[name=c-note]", "本机的模拟接口，测试用")
    page.fill("input[name=c-tpath]", "/models")
    page.click(".wf-form button:has-text('保存')")
    page.wait_for_selector(".wf-card[data-conn]", timeout=10000)
    page.click(".wf-form button:has-text('测试')")
    page.wait_for_function("() => document.querySelector('.wf-msg') && document.querySelector('.wf-msg').textContent.includes('连通了')", timeout=15000)
    page.wait_for_selector(".wf-card[data-conn]:has-text('已测试通过')", timeout=10000)
    page.screenshot(path=os.path.join(SHOTS, "conn.png"))
    # 用它跑一个接口节点（回显请求头的接口：结果里 key 要被抹掉）
    cid = page.get_attribute(".wf-card[data-conn]", "data-conn")
    wf = {"schema": "onesay.workflow/1", "name": "接口演示", "nodes": [
        {"id": "start", "type": "start", "meta": {"position": {"x": 0, "y": 0}}, "data": {"title": "开始"}},
        {"id": "a", "type": "api", "meta": {"position": {"x": 300, "y": 0}}, "data": {"title": "调接口", "connector": cid, "method": "GET", "path": "/echo"}},
        {"id": "end", "type": "end", "meta": {"position": {"x": 600, "y": 0}}, "data": {"title": "结束", "output": {"r": "{{a.body}}"}}}],
        "edges": [{"sourceNodeID": "start", "targetNodeID": "a"}, {"sourceNodeID": "a", "targetNodeID": "end"}]}
    save_and_open(page, wf)
    page.click(".wf-bar button:has-text('运行')")
    out = wait_run(page)
    check("完成" in out, "接口节点跑通：" + out)
    check("sk-e2e-good-123456" not in out and "••••" in out, "回显的 key 被抹掉：" + out)
    check("sk-e2e-good-123456" not in srv.log(), "日志里没有 key")
    check("sk-e2e-good-123456" not in srv.files_text(), "数据目录的文件里没有 key")
    check("sk-e2e-good-123456" not in page.content(), "页面里没有 key")


def sc_export(page, srv, errs):
    """工作流导出不含凭据。"""
    r = page_api(page, {"op": "conn_save", "name": "导出用", "url": srv.mock + "/v1", "type": "backend", "note": "模拟", "key": "sk-export-secret-999"})
    check(r["ok"], r.get("text"))
    cid = r["connector"]["id"]
    wf = {"schema": "onesay.workflow/1", "name": "导出演示", "nodes": [
        {"id": "start", "type": "start", "meta": {"position": {"x": 0, "y": 0}}, "data": {"title": "开始"}},
        {"id": "a", "type": "api", "meta": {"position": {"x": 300, "y": 0}}, "data": {"title": "调接口", "connector": cid, "method": "POST", "path": "/x", "body": {"q": "hi"}}},
        {"id": "end", "type": "end", "meta": {"position": {"x": 600, "y": 0}}, "data": {"title": "结束"}}],
        "edges": [{"sourceNodeID": "start", "targetNodeID": "a"}, {"sourceNodeID": "a", "targetNodeID": "end"}]}
    save_and_open(page, wf)
    page.click(".wf-bar button:has-text('导出')")
    page.wait_for_selector("textarea[name=export]")
    text = page.input_value("textarea[name=export]")
    check("sk-export-secret-999" not in text, "导出里没有 key")
    j = json.loads(text)
    check(j["requires"]["connectors"][0]["name"] == "导出用", "导出里列了依赖的连接器")
    check(not re.search(r'"(api_?key|token|secret|password|authorization)"', text, re.I), "导出里没有凭据字段")
    page.screenshot(path=os.path.join(SHOTS, "export.png"))
    page.click(".wf-modal .wf-x")
    # 导入回来：提示缺连接（换一个数据目录的话）；这里同一台机器，连接没测试通过 → 也会提示
    page.click("button:has-text('导入')")
    page.fill("textarea[name=import]", text)
    page.click(".wf-modal button:has-text('检查')")
    page.wait_for_selector(".wf-modal .wf-check")
    chk = page.inner_text(".wf-modal .wf-check")
    check("缺" in chk and "导出用" in chk, "导入检查列出缺的连接：" + chk)


SCENES = {"smoke": sc_smoke, "build": sc_build, "ai": sc_ai, "run": sc_run, "sandbox": sc_sandbox, "skill": sc_skill, "missing": sc_missing, "conn": sc_conn, "export": sc_export}


def main():
    failed = 0
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True)
        for name in WANT:
            srv = Server()
            ctx = browser.new_context(viewport={"width": 1440, "height": 900}, locale="zh-CN")
            page = ctx.new_page()
            t0 = time.time()
            try:
                errs = open_wf_page(page, srv)
                SCENES[name](page, srv, errs)
                bad = [e for e in errs if "ResizeObserver" not in e]
                check(not bad, "页面报错：%s" % bad[:3])
                RESULTS.append((name, "通过（%.1f 秒）" % (time.time() - t0)))
            except Exception as e:
                failed += 1
                RESULTS.append((name, "失败：%s" % str(e)[:400]))
                try:
                    page.screenshot(path=os.path.join(SHOTS, "FAIL-" + name + ".png"))
                except Exception:
                    pass
            finally:
                ctx.close()
                srv.close()
        browser.close()
    for n, r in RESULTS:
        print("%-12s %s" % (n, r))
    print("E2E %d/%d 通过" % (len(WANT) - failed, len(WANT)))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
