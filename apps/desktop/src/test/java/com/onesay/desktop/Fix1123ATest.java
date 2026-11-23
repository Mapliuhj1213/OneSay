package com.onesay.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

/**
 * [fix-1123-a] 电脑端界面线程 A：
 * 一句话改设置 / 跳页（AppControl）、网络唤醒检查认结果行、lan_port=0、MCP 去重 + 子进程环境变量、认不全的下载命令不再第三次照原样执行、界面（配对入口、手机页控件、侧栏）。
 */
public class Fix1123ATest {

    // ------------------------------------------------------------------ 假的设置 / 批准 / 跳页

    static final class Rig {
        final Map<String, Object> cur = new TreeMap<>();
        final List<String> sets = new ArrayList<>();
        final List<String> navs = new ArrayList<>();
        final List<String> asked = new ArrayList<>();
        Actions.Result answer = null; // null = 允许
        final AppControl ac;

        Rig() {
            cur.put("theme", "system");
            cur.put("theme_pack", "");
            cur.put("ptt", true);
            cur.put("asr_pref", "auto");
            cur.put("mirror_keyboard", "sdk");
            cur.put("media_quality", "best");
            cur.put("chat_timeout_min", 30L);
            cur.put("autostart", false);
            List<Object> packs = new ArrayList<>();
            packs.add(new TreeMap<>(Map.of("id", "nord", "name", "Nord", "mode", "dark")));
            packs.add(new TreeMap<>(Map.of("id", "solarized-light", "name", "Solarized Light", "mode", "light")));
            ac = new AppControl(d -> { asked.add(d); return answer; }, (k, v) -> { sets.add(k + "=" + v); cur.put(k, v); return new TreeMap<>(Map.of("ok", true, "text", "已保存")); },
                    () -> cur, () -> packs, pa -> navs.add(pa[0] + "#" + pa[1]));
        }
    }

    static String nav(String s) {
        AppControl.Place p = AppControl.parseNav(Intent.normalize(s));
        return p == null ? null : p.page + "#" + p.anchor;
    }

    // ------------------------------------------------------------------ 跳页

    @Test public void navPhrasesFindTheRightPage() {
        assertEquals("workflows#", nav("打开工作流页"));
        assertEquals("workflows#", nav("打开工作流"));
        assertEquals("workflows#", nav("工作流页"));
        assertEquals("settings#", nav("打开设置"));
        assertEquals("settings#set-look", nav("去设置里的外观"));
        assertEquals("settings#set-look", nav("打开外观设置"));
        assertEquals("settings#set-perm", nav("打开权限与审批"));
        assertEquals("settings#set-plugins", nav("打开安全插件"));
        assertEquals("settings#set-mcp", nav("打开 MCP 连接器"));
        assertEquals("phone#", nav("打开手机页"));
        assertEquals("mirror#", nav("打开手机投屏"));
        assertEquals("today#", nav("回到主页"));
        assertEquals("today#home-pair", nav("打开配对"));
        assertEquals("settings#set-voice", nav("跳到语音设置"));
        assertEquals("shortcuts#", nav("切到快捷指令页面"));
    }

    @Test public void navDoesNotHijackOtherSentences() {
        assertNull("一个词不算", nav("工作流"));
        assertNull("一个词不算", nav("设置"));
        assertNull("手机指令", nav("手机打开微信"));
        assertNull("手机系统设置", nav("打开手机设置"));
        assertNull("只说「打开手机」照旧给手机的帮助", nav("打开手机"));
        assertNull("看看日程是要查内容", nav("看看日程"));
        assertNull("后面还有事", nav("打开工作流页然后新建一个"));
        assertNull("别的程序", nav("打开微信"));
        assertNull(nav("打开B站"));
        assertNull("可能是下载文件夹", nav("打开下载"));
        assertNull("可能是要开始投屏", nav("打开投屏"));
        assertEquals("settings#set-download", nav("打开下载设置"));
        assertEquals("settings#set-look", nav("打开外观页"));
    }

    // ------------------------------------------------------------------ 改设置（规则）

    static String req(Rig r, String s) {
        AppControl.SetReq q = r.ac.parseSet(Intent.normalize(s));
        if (q == null) return null;
        return q.item == null ? "protected:" + q.protectedAnchor : q.item.key + "=" + q.value;
    }

    @Test public void setPhrasesAreUnderstood() {
        Rig r = new Rig();
        assertEquals("theme=dark", req(r, "把主题改成深色"));
        assertEquals("theme=light", req(r, "主题换成浅色"));
        assertEquals("theme=system", req(r, "主题跟随系统"));
        assertEquals("theme=dark", req(r, "打开深色模式"));
        assertEquals("theme=light", req(r, "关掉深色模式"));
        assertEquals("theme=dark", req(r, "切到深色"));
        assertEquals("theme_pack=nord", req(r, "主题换成 Nord"));
        assertEquals("theme_pack=nord", req(r, "配色换成nord"));
        assertEquals("theme_pack=", req(r, "配色包换回默认"));
        assertEquals("ptt=false", req(r, "关掉按住空格说话"));
        assertEquals("ptt=true", req(r, "把按住空格说话打开"));
        assertEquals("autostart=true", req(r, "打开开机自启"));
        assertEquals("asr_pref=local", req(r, "识别方式改成仅本机"));
        assertEquals("media_quality=1080", req(r, "下载画质改成1080p"));
        assertEquals("chat_timeout_min=120", req(r, "任务时长改成2小时"));
        assertEquals("mirror_keyboard=uhid", req(r, "投屏键盘换成物理键盘"));
        assertEquals("认出是哪项、没听出值", "theme=null", req(r, "改一下主题"));
        assertNull("不是改设置", req(r, "今天天气怎么样"));
        assertNull("太长的交给模型", req(r, "帮我把主题改成深色然后再打开工作流页看看昨天建的那个"));
    }

    /** [fix-1123-a 审计] 点了别的对象的「深色模式」不是改 OneSay 的明暗（以前「让手机打开深色模式」会把电脑上的 OneSay 改成深色）。 */
    @Test public void otherTargetsAreNotOneSaySettings() {
        Rig r = new Rig();
        for (String s : new String[] {"让手机打开深色模式", "把手机改成深色模式", "手机识别方式改成本地", "把手机主题换成浅色", "把Windows改成深色模式",
                "电脑切到深色模式", "系统切到深色", "浏览器换成深色", "把手机的权限打开"})
            assertNull(s, req(r, s));
        assertEquals("「跟随系统」是值，不算别的对象", "theme=system", req(r, "主题跟随系统"));
        assertEquals("说了 OneSay 就是它", "theme=dark", req(r, "把OneSay改成深色模式"));
        assertEquals("电脑权限照样认成受保护", "protected:set-perm", req(r, "把电脑权限改成完全访问"));
        assertNull("整句也不动", r.ac.handle("让手机打开深色模式", false, false));
        assertTrue(r.sets.isEmpty() && r.asked.isEmpty());
    }

    @Test public void protectedSettingsAreRecognized() {
        Rig r = new Rig();
        assertEquals("protected:set-perm", req(r, "把沙箱档改成完全访问"));
        assertEquals("protected:set-perm", req(r, "审批策略改成从不"));
        assertEquals("protected:set-plugins", req(r, "关掉安全插件"));
        assertEquals("protected:set-plugins", req(r, "关掉拦截编码"));
        assertEquals("protected:set-chatperm", req(r, "对话权限改成完全访问"));
        assertEquals("protected:set-perm", req(r, "信任域名加上example.com"));
        assertEquals("protected:set-model", req(r, "把 API Key 换了"));
    }

    @Test public void approvedChangeGoesThroughSetter() {
        Rig r = new Rig();
        String said = r.ac.handle("把主题改成深色", false, false);
        assertEquals(List.of("theme=dark"), r.sets);
        assertEquals(1, r.asked.size());
        assertTrue(r.asked.get(0), r.asked.get(0).contains("明暗 → 深色"));
        assertTrue(said, said.contains("改成深色了"));
        // 本来就是：不问、不改
        r.asked.clear();
        r.sets.clear();
        String again = r.ac.handle("主题换成深色", false, false);
        assertTrue(again, again.contains("本来就是深色"));
        assertTrue(r.asked.isEmpty());
        assertTrue(r.sets.isEmpty());
    }

    @Test public void deniedOrTimedOutChangeDoesNothing() {
        Rig r = new Rig();
        r.answer = new Actions.Result(false, "电脑上拒绝了", new TreeMap<>(Map.of("reason", LinkPolicy.R_CONFIRM_DENIED)));
        String said = r.ac.handle("关掉按住空格说话", false, false);
        assertTrue(r.sets.isEmpty());
        assertTrue(said, said.startsWith("没改「按住空格说话」") && said.contains("拒绝"));
        r.answer = new Actions.Result(false, "没人确认（60 秒到了…），已拒绝", null);
        assertTrue(r.ac.handle("打开开机自启", false, false).contains("没人确认"));
        assertTrue(r.sets.isEmpty());
    }

    @Test public void protectedSettingOnlyOpensThePage() {
        Rig r = new Rig();
        String said = r.ac.handle("把沙箱档改成完全访问", false, false);
        assertTrue(r.asked.isEmpty());
        assertTrue(r.sets.isEmpty());
        assertEquals(List.of("settings#set-perm"), r.navs);
        assertTrue(said, said.contains("不能一句话改") && said.contains("权限与审批"));
        r.navs.clear();
        r.ac.handle("关掉安全插件", false, false);
        assertEquals(List.of("settings#set-plugins"), r.navs);
        assertTrue(r.sets.isEmpty());
    }

    @Test public void remoteCannotTouchUiOrSettings() {
        Rig r = new Rig();
        String said = r.ac.handle("把主题改成深色", true, false);
        assertTrue(said, said.contains("手机交来的"));
        assertTrue(r.ac.handle("打开工作流页", true, false).contains("手机交来的"));
        assertTrue(r.sets.isEmpty() && r.navs.isEmpty() && r.asked.isEmpty());
        Actions.Result t = r.ac.run(new TreeMap<>(Map.of("action", "set", "setting", "theme", "value", "dark")), true);
        assertFalse(t.ok);
        assertEquals("remote_app_control", t.data.get("reason"));
        assertTrue(r.sets.isEmpty());
    }

    @Test public void themeWithPackClearsPackFirst() {
        Rig r = new Rig();
        r.cur.put("theme_pack", "nord");
        r.cur.put("theme", "dark");
        String said = r.ac.handle("主题改成浅色", false, false);
        assertEquals(List.of("theme_pack=", "theme=light"), r.sets);
        assertTrue(said, said.contains("配色包换回了默认"));
    }

    @Test public void unclearValueGoesToModelOrGivesHint() {
        Rig r = new Rig();
        assertNull("有模型：交给模型", r.ac.handle("改一下主题", false, true));
        String h = r.ac.handle("改一下主题", false, false);
        assertTrue(h, h.contains("没听出") && h.contains("深色"));
        assertNull("不相干的话不接", r.ac.handle("帮我查一下明天的天气", false, false));
    }

    @Test public void navigationIsRecorded() {
        Rig r = new Rig();
        assertEquals("打开了「工作流」。", r.ac.handle("打开工作流页", false, true));
        assertEquals(List.of("workflows#"), r.navs);
        r.ac.handle("打开外观设置", false, true);
        assertEquals("settings#set-look", r.navs.get(1));
        assertTrue("跳页不弹批准", r.asked.isEmpty());
    }

    // ------------------------------------------------------------------ 模型工具

    @Test public void toolIsRegisteredAndMapped() {
        assertTrue(ChatAgent.toolNames().contains(AppControl.TOOL));
        Object[] m = ChatAgent.map(new Llm.ToolCall("1", "app_control", "{\"action\":\"set\",\"setting\":\"theme\",\"value\":\"dark\",\"description\":\"换深色\"}"));
        assertEquals("app_control", m[0]);
        @SuppressWarnings("unchecked") Map<String, Object> a = (Map<String, Object>) m[1];
        assertEquals("set", a.get("action"));
        assertNull("缺 setting 的不收", ChatAgent.map(new Llm.ToolCall("2", "app_control", "{\"action\":\"set\"}")));
        assertNull(ChatAgent.map(new Llm.ToolCall("3", "app_control", "{\"action\":\"rm -rf\"}")));
        assertTrue(Humanize.TOOLS.containsKey("app_control"));
        assertFalse("说明里不露内部 id 给人看", Humanize.TOOLS.get("app_control").contains("app_control"));
    }

    @Test public void toolSetOpenAndList() {
        Rig r = new Rig();
        Actions.Result a = r.ac.run(new TreeMap<>(Map.of("action", "set", "setting", "明暗", "value", "深色")), false);
        assertTrue(a.text, a.ok);
        assertEquals(List.of("theme=dark"), r.sets);
        Actions.Result p = r.ac.run(new TreeMap<>(Map.of("action", "set", "setting", "pc_sandbox", "value", "full_access")), false);
        assertFalse(p.ok);
        assertEquals("protected_setting", p.data.get("reason"));
        assertEquals(1, r.sets.size());
        assertEquals("settings#set-perm", r.navs.get(r.navs.size() - 1));
        Actions.Result k = r.ac.run(new TreeMap<>(Map.of("action", "set", "setting", "plugin_enabled", "value", "false")), false);
        assertFalse(k.ok);
        Actions.Result bad = r.ac.run(new TreeMap<>(Map.of("action", "set", "setting", "chat_timeout_min", "value", "99999")), false);
        assertFalse(bad.ok);
        assertTrue(bad.text.contains("5 分钟到 24 小时"));
        Actions.Result o = r.ac.run(new TreeMap<>(Map.of("action", "open_page", "page", "设置 › 外观")), false);
        assertTrue(o.text, o.ok);
        assertEquals("settings#set-look", r.navs.get(r.navs.size() - 1));
        assertFalse(r.ac.run(new TreeMap<>(Map.of("action", "open_page", "page", "不存在的页")), false).ok);
        Actions.Result l = r.ac.run(new TreeMap<>(Map.of("action", "list")), false);
        assertTrue(l.text.contains("明暗") && l.text.contains("不能在对话里改"));
        Actions.Result pk = r.ac.run(new TreeMap<>(Map.of("action", "set", "setting", "theme_pack", "value", "Nord")), false);
        assertTrue(pk.text, pk.ok);
        assertEquals("theme_pack=nord", r.sets.get(r.sets.size() - 1));
    }

    // ------------------------------------------------------------------ 接进对话

    @Test public void chatShortSentenceUsesAppControl() throws Exception {
        Path d = Files.createTempDirectory("f1123a");
        Store s = new Store(d.resolve("state.json"));
        Config c = new Config(d.resolve("c.properties"));
        Chat ch = new Chat(c, s, new Main.TestActions(), List::of, x -> { });
        Rig r = new Rig();
        ch.appControl = r.ac;
        Map<String, Object> m = ch.send("把主题改成深色", "text");
        assertEquals("cmd", m.get("via"));
        assertTrue(String.valueOf(m.get("text")), String.valueOf(m.get("text")).contains("改成深色了"));
        assertEquals(List.of("theme=dark"), r.sets);
        Map<String, Object> n = ch.send("打开工作流页", "text");
        assertEquals("打开了「工作流」。", n.get("text"));
        // 别的指令照旧走原来的规则（不被这里接走）
        ch.send("锁屏", "text");
        ch.send("打开微信", "text");
        assertEquals(1, r.sets.size());
        assertEquals(List.of("workflows#"), r.navs);
    }

    @Test public void clipUrlOnlyReturnsAUrl() {
        assertEquals("https://example.com/a?b=1", App.clipUrl("  https://example.com/a?b=1 \n"));
        assertEquals("", App.clipUrl("看看这个 https://example.com"));
        assertEquals("", App.clipUrl("我的密码是 123456"));
        assertEquals("", App.clipUrl("javascript:alert(1)"));
        assertEquals("", App.clipUrl(null));
    }

    // ------------------------------------------------------------------ 网络唤醒检查：CLIXML

    @Test public void wolCheckReadsOnlyTheResultLine() {
        String json = "{\"fast_startup\":0,\"nics\":[{\"name\":\"以太网\",\"desc\":\"Realtek PCIe GbE\",\"mac\":\"AA-BB-CC-DD-EE-01\",\"media\":\"802.3\",\"status\":\"Up\",\"magic\":\"Enabled\",\"armed\":true,\"adv\":[]}]}";
        String noisy = "#< CLIXML\r\n<Objs Version=\"1.1.0.1\" xmlns=\"http://schemas.microsoft.com/powershell/2004/04\"><Obj S=\"progress\" RefId=\"0\"><TN RefId=\"0\"><T>System.Management.Automation.PSCustomObject</T></TN><MS><I64 N=\"SourceId\">1</I64><PR N=\"Record\"><AV>正在准备首次使用模块。</AV></PR></MS></Obj></Objs>\r\n"
                + "WARNING: {not json}\r\n" + WakeOnLan.MARK + json + "\r\n";
        assertEquals(json, WakeOnLan.resultJson(noisy));
        Map<String, Object> v = WakeOnLan.verdict(WakeOnLan.resultJson(noisy), true);
        assertEquals(Boolean.TRUE, v.get("ok"));
        assertNull("没有结果行", WakeOnLan.resultJson("#< CLIXML\n<Objs>{</Objs>"));
        assertNull(WakeOnLan.resultJson(null));
        assertEquals("带 BOM 也认", json, WakeOnLan.resultJson("\uFEFF" + WakeOnLan.MARK + json));
        assertTrue(WakeOnLan.CHECK_PS.contains("$ProgressPreference='SilentlyContinue'"));
        assertTrue(WakeOnLan.CHECK_PS.contains("'" + WakeOnLan.MARK + "'"));
        // 只读：没有 Set- / Enable- / Disable- 这类改设置的命令
        assertFalse(Pattern.compile("(?i)\\b(Set|Enable|Disable|New|Remove)-\\w+").matcher(WakeOnLan.CHECK_PS).find());
    }

    // ------------------------------------------------------------------ lan_port=0

    @Test public void lanPortZeroStaysOff() throws Exception {
        Path f = Files.createTempFile("lan0", ".properties");
        Files.writeString(f, "device_id=pc-t\nlan_port=0\npair_key=" + "A".repeat(43) + "\n", StandardCharsets.UTF_8);
        Config c = Config.load(f);
        assertTrue(c.lanOffByUser);
        assertFalse(c.lanEnabled());
        App.autoSetup(c);
        assertEquals("自己关的局域网，启动时不再改回默认端口", 0, c.lanPort);
        assertFalse(Config.load(f).lanEnabled());
        // 没写 → 默认端口；写坏了（负数 / 超范围）照旧在启动时改回默认端口，不算「自己关了」
        Path g = Files.createTempFile("lan1", ".properties");
        Files.writeString(g, "device_id=pc-t\n", StandardCharsets.UTF_8);
        assertEquals(LanServer.DEFAULT_PORT, Config.load(g).lanPort);
        for (String bad : new String[] {"99999", "-5"}) {
            Files.writeString(g, "device_id=pc-t\nlan_port=" + bad + "\n", StandardCharsets.UTF_8);
            Config b = Config.load(g);
            assertFalse(b.lanOffByUser);
            App.autoSetup(b);
            assertEquals(bad, LanServer.DEFAULT_PORT, b.lanPort);
        }
        Files.writeString(g, "device_id=pc-t\nlan_port=48000\n", StandardCharsets.UTF_8);
        assertEquals(48000, Config.load(g).lanPort);
    }

    // ------------------------------------------------------------------ MCP

    final List<McpHub> hubs = new ArrayList<>();

    @After public void tearDown() { for (McpHub h : hubs) h.shutdown(); }

    @Test public void mcpChildGetsOnlyNeededEnv() {
        Map<String, String> sys = new TreeMap<>();
        sys.put("Path", "C:\\Windows");
        sys.put("SystemRoot", "C:\\Windows");
        sys.put("TEMP", "C:\\t");
        sys.put("APPDATA", "C:\\a");
        sys.put("DEEPSEEK_API_KEY", "sk-secret");
        sys.put("GITHUB_TOKEN", "ghp_x");
        sys.put("ONESAY_LAN", "off");
        sys.put("HTTPS_PROXY", "http://127.0.0.1:7890");
        Map<String, String> m = Mcp.Stdio.minimalEnv(sys);
        assertEquals(Map.of("Path", "C:\\Windows", "SystemRoot", "C:\\Windows", "TEMP", "C:\\t", "APPDATA", "C:\\a", "HTTPS_PROXY", "http://127.0.0.1:7890").keySet(), new java.util.TreeSet<>(m.keySet()));
    }

    static final String ENV_MOCK = String.join("\n",
            "import sys, json, os",
            "def send(m): sys.stdout.write(json.dumps(m) + '\\n'); sys.stdout.flush()",
            "for line in sys.stdin:",
            "    line = line.strip()",
            "    if not line: continue",
            "    m = json.loads(line)",
            "    if 'id' not in m: continue",
            "    mid, meth = m['id'], m.get('method')",
            "    if meth == 'initialize': send({'jsonrpc':'2.0','id':mid,'result':{'protocolVersion':'2025-06-18','capabilities':{'tools':{}},'serverInfo':{'name':'envmock','version':'1'}}})",
            "    elif meth == 'tools/list': send({'jsonrpc':'2.0','id':mid,'result':{'tools':[{'name':'env','description':'list env','inputSchema':{'type':'object','properties':{'n':{'type':'integer','description':'how many'},'mode':{'type':'string','enum':['a','b']}},'required':['n']},'annotations':{'readOnlyHint':True}}]}})",
            "    elif meth == 'tools/call': send({'jsonrpc':'2.0','id':mid,'result':{'content':[{'type':'text','text':','.join(sorted(os.environ.keys()))}]}})",
            "    else: send({'jsonrpc':'2.0','id':mid,'error':{'code':-32601,'message':'no'}})",
            "");

    @Test public void mcpChildEnvIsFilteredEndToEnd() throws Exception {
        String py = McpTest.python();
        Assume.assumeNotNull(py);
        String leak = null;
        for (String k : System.getenv().keySet()) if (!Mcp.Stdio.ENV_KEEP.contains(k.toUpperCase(java.util.Locale.ROOT)) && k.matches("[A-Za-z_][A-Za-z0-9_]*")) { leak = k; break; }
        Assume.assumeNotNull("测试进程里要有一个不在白名单里的环境变量", leak);
        Path d = Files.createTempDirectory("mcp-env");
        Files.writeString(d.resolve("env_server.py"), ENV_MOCK);
        Files.writeString(d.resolve("mcp.json"), "{\"mcpServers\":{\"envmock\":{\"command\":\"" + py.replace("\\", "\\\\") + "\",\"args\":[\"env_server.py\"],\"env\":{\"MY_OWN\":\"1\"}}}}");
        McpHub h = new McpHub(Files.createTempDirectory("mcp-st").resolve("mcp.json"), null);
        hubs.add(h);
        assertEquals(true, h.addSource(d.toString()).get("ok"));
        McpHub.Server s = McpTest.waitReady(h, "envmock");
        assertEquals(s.error, "ready", s.status);
        Actions.Result r = h.call("mcp__envmock__env", new TreeMap<>(Map.of("n", 1L)));
        String out = String.valueOf(r.data == null ? r.text : r.data.get("output")) + " " + r.text;
        assertTrue(out, out.contains("MY_OWN"));
        assertTrue(out, out.contains("PATH") || out.contains("Path"));
        assertFalse("不该继承 " + leak + "：" + out, Pattern.compile("(^|[ ,:])" + Pattern.quote(leak) + "(,|$| )").matcher(out).find());
        // 界面表单要的参数说明
        @SuppressWarnings("unchecked") List<Map<String, Object>> tools = (List<Map<String, Object>>) s.view().get("tools");
        @SuppressWarnings("unchecked") Map<String, Object> sc = (Map<String, Object>) tools.get(0).get("schema");
        assertNotNull(sc);
        @SuppressWarnings("unchecked") Map<String, Object> props = (Map<String, Object>) sc.get("properties");
        assertEquals("integer", ((Map<?, ?>) props.get("n")).get("type"));
        assertEquals(List.of("a", "b"), ((Map<?, ?>) props.get("mode")).get("enum"));
        assertEquals(List.of("n"), sc.get("required"));
    }

    @Test public void sameMcpServerIsNotAddedTwice() throws Exception {
        // 配置文件：先选文件夹，再选里面的 mcp.json
        Path d = Files.createTempDirectory("mcp-dup");
        Files.writeString(d.resolve("mcp.json"), "{\"mcpServers\":{\"remote\":{\"url\":\"http://127.0.0.1:9/mcp\"}}}");
        McpHub h = new McpHub(Files.createTempDirectory("mcp-st2").resolve("mcp.json"), null);
        hubs.add(h);
        assertEquals(true, h.addSource(d.toString()).get("ok"));
        Map<String, Object> again = h.addSource(d.resolve("mcp.json").toString());
        assertEquals(again.toString(), false, again.get("ok"));
        assertTrue(String.valueOf(again.get("text")), String.valueOf(again.get("text")).contains("已经加过了"));
        assertEquals(1, h.servers.size());
        // 同一个地址写在另一个目录的配置里，也只算一条（地址末尾的 / 和大小写不算不同）
        Path e = Files.createTempDirectory("mcp-dup2");
        Files.writeString(e.resolve(".mcp.json"), "{\"mcpServers\":{\"remote2\":{\"url\":\"HTTP://127.0.0.1:9/mcp/\"}}}");
        assertEquals(false, h.addSource(e.toString()).get("ok"));
        assertEquals(1, h.servers.size());
        // Node 项目：先选文件夹、再选它的 package.json = 同一个来源
        Path n = Files.createTempDirectory("mcp-node");
        Files.writeString(n.resolve("package.json"), "{\"name\":\"my-mcp\",\"main\":\"index.js\"}");
        Files.writeString(n.resolve("index.js"), "process.exit(0)\n");
        Map<String, Object> a1 = h.addSource(n.toString());
        Map<String, Object> a2 = h.addSource(n.resolve("package.json").toString());
        assertEquals(a1.toString(), true, a1.get("ok"));
        assertEquals("同一个来源重新识别，不多一条：" + a2, 2, h.servers.size());
        // 状态文件里也只记一个来源
        McpHub h2 = new McpHub(h.stateFile, null);
        hubs.add(h2);
        h2.load();
        assertEquals(2, h2.sources.size());
    }

    // ------------------------------------------------------------------ 认不全的下载命令

    @Test public void mixedDownloadIsNeverRunAsIs() {
        String mixed = "Invoke-WebRequest -Uri $u -OutFile a.zip; Expand-Archive a.zip";
        List<Llm.Turn> turns = new ArrayList<>();
        for (int i = 0; i < 8; i++) turns.add(Overnight1119Test.call(String.valueOf(i), "run_command", Approvals1118Test.rc("下", mixed + " -V" + i)));
        turns.add(Overnight1119Test.say("【完成】\n好了。"));
        AtomicInteger i = new AtomicInteger();
        List<String> ran = new ArrayList<>();
        List<String> toolText = new ArrayList<>();
        ChatAgent ag = new ChatAgent();
        ag.wrapUpOnStop = false;
        ChatAgent.Outcome o = ag.run(new ArrayList<>(List.of(Llm.msg("user", "下载并解压"))), (m, t) -> {
            Map<String, Object> last = m.get(m.size() - 1);
            if ("tool".equals(last.get("role"))) toolText.add(String.valueOf(last.get("content")));
            return turns.get(Math.min(i.getAndIncrement(), turns.size() - 1));
        }, (cmd, a) -> { ran.add(cmd); return Actions.Result.ok("ok"); }, new ChatAgent.Steps() {
            @Override public void add(String text) { }
            @Override public void step(Map<String, Object> ev) { }
        });
        assertTrue("一次都没照原样执行：" + ran, ran.isEmpty());
        assertEquals("拆了四次还是混着下载就停下", "no_progress", o.status);
        assertTrue(o.text.contains("不会照原样执行"));
        assertTrue("第三次起明说不会执行", toolText.stream().anyMatch(t -> t.contains("不管发几次都不会执行")));
    }

    // ------------------------------------------------------------------ 界面

    static String html() throws Exception { return Preview1120UiTest.html(); }

    static String fn(String h, String name) {
        int a = h.indexOf("function " + name + "(");
        if (a < 0) return "";
        int b = h.indexOf("\nfunction ", a + 10), c = h.indexOf("\nasync function ", a + 10), e = h.indexOf("\nconst ", a + 10);
        int end = h.length();
        for (int x : new int[] {b, c, e}) if (x > 0 && x < end) end = x;
        return h.substring(a, end);
    }

    @Test public void pairingIsOnHomeAndPhonePage() throws Exception {
        String h = html();
        assertTrue("主页最上面有连接手机", h.contains("`<button class=\"btn\" onclick=\"go('chat')\">问 OneSay</button>`)+homePair()+"));
        assertTrue(h.contains("id=\"home-pair\""));
        assertTrue("「手机」页离线卡上有配对", h.contains("<span class=\"tag\">离线</span><button class=\"btn\" onclick=\"showPair()\">"));
        assertTrue("侧栏的手机状态能点去配对", h.contains("onclick=\"devchipGo()\""));
        assertTrue("设置页的配对还在（跟正式版一样）", h.contains("grp('set-connect','连接'"));
        assertTrue("二维码只画在当前页", fn(h, "drawQr").contains("root.querySelector('#qrbox')"));
    }

    @Test public void sidebarKeepsSettingsVisible() throws Exception {
        String h = html();
        int navs = h.indexOf("<div class=\"navs\">"), foot = h.indexOf("<div class=\"foot\">\n    <button class=\"nav\" data-p=\"settings\">");
        assertTrue(navs > 0 && foot > navs);
        assertTrue("设置不在滚动区里", h.indexOf("data-p=\"settings\"") > foot);
        assertTrue(h.contains(".side .navs{flex:1;min-height:0;overflow-y:auto"));
        assertTrue(h.contains(".side .foot{flex:none"));
        assertTrue("矮窗口先藏说话提示", h.contains("@media (max-height:940px){.side .ptt{display:none}}"));
    }

    @Test public void phonePageHasNoBrowserPrompts() throws Exception {
        String h = html();
        assertFalse("整个界面不再用浏览器 prompt() 弹框", h.contains("prompt("));
        for (String gone : new String[] {"function phoneSet(", "function phoneVol(", "function phoneFile(", "function phoneScript(", "function phoneUrl(", "function phoneApp(", "function phoneNote(", "function phoneOperate("})
            assertFalse(gone, h.contains(gone));
        // 每一项都换成了控件
        assertTrue("音量拉条，松手才发", fn(h, "volPanel").contains("type=\"range\"") && fn(h, "volPanel").contains("onchange=\"this.blur();phoneVolSet("));
        assertTrue("改设置 = 名称 + 开关", h.contains("{n:'wifi',zh:'Wi-Fi',t:'bool'") && fn(h, "psetRow").contains("[['on','开'],['off','关']]"));
        assertTrue("铃声三选一", h.contains("o:[['normal','响铃'],['vibrate','振动'],['silent','静音']]"));
        assertTrue("亮度拉条", h.contains("{n:'brightness',zh:'亮度',t:'range'"));
        assertTrue("App 列表", h.contains("const PAPPS=[") && fn(h, "appChips").contains("phoneAppOpen("));
        assertTrue("卸载从已装 App 里选", fn(h, "phonePkgsLoad").contains("pm list packages -3") && fn(h, "unRows").contains("phoneUninstall("));
        assertTrue("脚本列表每条有运行", fn(h, "scriptPanel").contains("phoneScriptRun("));
        assertTrue("写文件：选文件夹 + 文件名 + 多行内容", fn(h, "filePanel").contains("<select") && fn(h, "filePanel").contains("<textarea"));
        assertTrue("网址预填剪贴板", fn(h, "clipUrl").contains("api('clip_url'"));
        assertTrue("通知常用句", h.contains("const PNOTES=["));
        assertTrue("交给手机：例句", h.contains("const POPS=["));
        assertTrue("系统命令收进「高级」", h.contains("<details class=\"adv\"") && fn(h, "execPanel").contains("phone_cmds"));
        assertTrue("MCP 试用按参数生成表单", fn(h, "mcpField").contains("type=\"number\"") && fn(h, "mcpField").contains("this.dataset.k"));
        // 危险的手机设置不放一键开关
        for (String n : new String[] {"'adb'", "'unknown_sources'", "'lock_screen'", "'proxy'", "'private_dns'"}) assertFalse(n, h.contains("{n:" + n));
    }

    @Test public void chatNavAndOrphanApproval() throws Exception {
        String h = html();
        assertTrue(fn(h, "refresh").contains("uiNav()"));
        assertTrue("窗口刚打开不跳旧的", fn(h, "uiNav").contains("if(navSeen===null){navSeen=seq;return}"));
        assertTrue("不在任务里的批准也看得到", h.contains("+orphanAsk()+") && fn(h, "orphanAsk").contains("askHtml(c)"));
    }

    @Test public void newUiTextHasNoBannedWordsOrHanziIcons() throws Exception {
        String h = html();
        int a = h.indexOf("// ---------- [fix-1123-a] 「手机」页");
        int b = h.indexOf("async function unpin(){");
        assertTrue(a > 0 && b > a);
        String seg = h.substring(a, b);
        for (String w : new String[] {"Bixby", "三星", "DeX", "小布", "啥", "咋", "呗"}) assertFalse(w, seg.contains(w));
        // 给人看的字里不露内部能力名（发给后端的参数可以有）
        Matcher m = Pattern.compile(">([^<>`$\\n'(){};=]*?(setting_set|volume_set|open_app|sys_exec|file_write)[^<>`$\\n'(){};=]*)<").matcher(seg);
        if (m.find()) org.junit.Assert.fail("界面文字露出内部能力名：" + m.group(1));
        Matcher ic = Pattern.compile("<span class=\"ic\"[^>]*>([^<$]+)</span>").matcher(seg);
        assertFalse("图标位置是文字", ic.find());
        assertTrue(h.contains("<symbol id=\"i-qr-code\""));
        assertTrue(h.contains("<symbol id=\"i-link\""));
    }

    // ------------------------------------------------------------------ 对比度（深浅两套）

    static double lum(String hex) {
        int v = Integer.parseInt(hex.substring(1), 16);
        double[] c = {(v >> 16) & 255, (v >> 8) & 255, v & 255};
        for (int i = 0; i < 3; i++) { double x = c[i] / 255; c[i] = x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    /** 半透明的黑 / 白（v = 0 / 255）叠在 hex 上。 */
    static String blend(String hex, int v, double a) {
        int x = Integer.parseInt(hex.substring(1), 16);
        int[] c = {(x >> 16) & 255, (x >> 8) & 255, x & 255};
        StringBuilder b = new StringBuilder("#");
        for (int i : c) b.append(String.format("%02X", (int) Math.round(i * (1 - a) + v * a)));
        return b.toString();
    }

    static double ratio(String a, String b) { double x = lum(a), y = lum(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); }

    static Map<String, String> tokens(String h, String selector) {
        int i = h.indexOf(selector + "{");
        String blk = h.substring(i, h.indexOf('}', i));
        Map<String, String> m = new TreeMap<>();
        Matcher t = Pattern.compile("(--[a-zA-Z0-9]+):\\s*(#[0-9A-Fa-f]{6})").matcher(blk);
        while (t.find()) m.put(t.group(1), t.group(2));
        return m;
    }

    @Test public void newControlsMeetContrastInBothThemes() throws Exception {
        String h = html();
        // 浅色：设计 token 那一段（带 --surface2 的那个 :root{）
        int li = h.indexOf("--surface2:#EEECE7");
        String lb = h.substring(h.lastIndexOf("{", li), h.indexOf("}", li));
        Map<String, String> light = new TreeMap<>();
        Matcher t = Pattern.compile("(--[a-zA-Z0-9]+):\\s*(#[0-9A-Fa-f]{6})").matcher(lb);
        while (t.find()) light.put(t.group(1), t.group(2));
        Map<String, String> dark = tokens(h, ":root[data-theme=dark]");
        for (Map<String, String> k : List.of(light, dark)) {
            String card = k.get("--card"), s2 = k.get("--surface2");
            // 新控件用到的组合：正文 / 次要文字 / 主色文字 在卡片和二级底色上；按钮文字（--card）在主色上；提示色在卡片上
            String chip = k == dark ? blend(card, 255, 0.08) : blend(card, 0, 0.05); // .chipb 的底：--chip 叠在卡片上
            for (String fg : new String[] {"--text", "--text2"}) {
                assertTrue(fg + " on card " + ratio(k.get(fg), card), ratio(k.get(fg), card) >= 4.5);
                assertTrue(fg + " on surface2 " + ratio(k.get(fg), s2), ratio(k.get(fg), s2) >= 4.5);
                assertTrue(fg + " on chip " + ratio(k.get(fg), chip), ratio(k.get(fg), chip) >= 4.5);
            }
            assertTrue("accent on card " + ratio(k.get("--accent"), card), ratio(k.get("--accent"), card) >= 4.5);
            assertTrue("accent on chip " + ratio(k.get("--accent"), chip), ratio(k.get("--accent"), chip) >= 4.5);
            assertTrue("button text " + ratio(card, k.get("--accent")), ratio(card, k.get("--accent")) >= 4.5);
            assertTrue("warn " + ratio(k.get("--warn"), card), ratio(k.get("--warn"), card) >= 4.5);
            assertTrue("red " + ratio(k.get("--red"), card), ratio(k.get("--red"), card) >= 4.5);
            // 主页「连接手机」的小图标：主色画在浅主色底上（图形，非文字，要 ≥ 3:1）
            assertTrue("accent icon on accentC " + ratio(k.get("--accent"), k.get("--accentC")), ratio(k.get("--accent"), k.get("--accentC")) >= 3);
            assertTrue("MCP 试用表单放在卡片底上（红字报错要清楚）", Preview1120UiTest.html().contains(".mcpform{margin-top:10px;padding:12px 14px;border-radius:12px;background:var(--card)"));
        }
    }
}
