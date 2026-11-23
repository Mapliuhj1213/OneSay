package com.onesay.desktop;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * [preview-1120 真机收尾] 电脑上预览版实测发现的问题：Markdown 表格 / 代码块、MCP 把 package.json 的仓库地址当服务器、
 * ONESAY_LAN=off 显示、换主题即时生效 / 进设置页重读主题包、旁白露「【需要你操作】」、下载改走下载工具（进度条）、任务通知标签。
 */
public class Preview1120FixTest {

    static String html() throws Exception { return Preview1120UiTest.html(); }

    // ------------------------------------------------------------------ Markdown（在 node 里跑页面里真的 md()）

    static String runMd(String input) throws Exception {
        String h = html();
        Matcher esc = Pattern.compile("(?m)^const esc=.*$").matcher(h);
        assertTrue(esc.find());
        int a = h.indexOf("function md(t){");
        int b = h.indexOf("return h.replace(/\\u0000(\\d+)\\u0000\\n?/g,(m,i)=>B[+i])}", a);
        assertTrue("md() 在页面里", a > 0 && b > a);
        String fn = h.substring(a, b) + "return h.replace(/\\u0000(\\d+)\\u0000\\n?/g,(m,i)=>B[+i])}";
        Path js = Files.createTempFile("md", ".js");
        Files.writeString(js, esc.group() + "\n" + fn + "\nlet s='';process.stdin.on('data',d=>s+=d).on('end',()=>process.stdout.write(md(s)));", StandardCharsets.UTF_8);
        Process p = new ProcessBuilder("node", js.toString()).redirectErrorStream(true).start();
        p.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(20, TimeUnit.SECONDS));
        assertEquals(out, 0, p.exitValue());
        return out;
    }

    static boolean hasNode() {
        for (String d : String.valueOf(System.getenv("PATH")).split(File.pathSeparator)) if (new File(d, "node").canExecute() || new File(d, "node.exe").canExecute()) return true;
        return false;
    }

    @Test public void markdownTableAndCodeBlockRender() throws Exception {
        Assume.assumeTrue("沙箱里有 node 才跑", hasNode());
        String out = runMd("对比：\n| 名称 | 大小 |\n| --- | :---: |\n| **a** | 1 MB |\n| b | `2` |\n\n```powershell\nGet-Item <x> | % { $_ }\n```\n结束");
        assertTrue(out, out.contains("<table class=\"mdt\"><tr><th>名称</th><th>大小</th></tr>"));
        assertTrue(out, out.contains("<td><b>a</b></td>"));
        assertTrue(out, out.contains("<td><code>2</code></td>"));
        assertTrue(out, out.contains("<pre class=\"mdpre\" data-lang=\"powershell\"><code>Get-Item &lt;x&gt; | % { $_ }</code></pre>"));
        assertFalse("不再露出 | --- |", out.contains("---"));
        assertFalse("不再露出 ```", out.contains("```"));
    }

    @Test public void markdownStillEscapesHtmlFirst() throws Exception {
        Assume.assumeTrue(hasNode());
        String out = runMd("| <img src=x onerror=alert(1)> | b |\n|---|---|\n| <script>x</script> | \"q\" |\n```\n<b>raw</b>\n```");
        assertFalse(out, out.contains("<img"));
        assertFalse(out, out.contains("<script"));
        assertFalse(out, out.contains("<b>raw</b>"));
        assertTrue(out, out.contains("&lt;script&gt;"));
        assertFalse("占位符不外露", out.contains("\u0000"));
    }

    @Test public void markdownLeavesPlainPipesAlone() throws Exception {
        Assume.assumeTrue(hasNode());
        String out = runMd("a | b 不是表格\n**粗**");
        assertFalse(out.contains("<table"));
        assertTrue(out.contains("<b>粗</b>"));
    }

    // ------------------------------------------------------------------ MCP：package.json 的 repository 不是服务器

    @Test public void pickingPackageJsonMeansTheProject() throws Exception {
        Path dir = Files.createTempDirectory("mcp-pj");
        Files.writeString(dir.resolve("package.json"), "{\"name\":\"@modelcontextprotocol/server-everything\",\"type\":\"module\","
                + "\"repository\":{\"type\":\"git\",\"url\":\"https://github.com/modelcontextprotocol/servers.git\"},"
                + "\"homepage\":\"https://modelcontextprotocol.io\",\"bin\":{\"mcp-server-everything\":\"dist/index.js\"},"
                + "\"dependencies\":{\"@modelcontextprotocol/server\":\"^2\"}}");
        Mcp.Detected d = Mcp.detect(dir.resolve("package.json"), Map.of());
        assertEquals(String.valueOf(d.notes), 1, d.specs.size());
        Mcp.Spec s = d.specs.get(0);
        assertEquals("server-everything", s.name);
        assertEquals("node", s.command);
        assertTrue(s.url.isEmpty());
        for (Mcp.Spec x : d.specs) assertFalse("仓库地址没被当 HTTP 服务器", x.url.contains("github.com"));
    }

    @Test public void hoistedNodeDepsCountAsInstalled() throws Exception {
        // [真机] npm install @modelcontextprotocol/server-everything 装成依赖：依赖在上层 node_modules，不在包自己目录下
        Path root = Files.createTempDirectory("mcp-hoist");
        Path pkg = root.resolve("node_modules/@modelcontextprotocol/server-everything");
        Files.createDirectories(pkg.resolve("dist"));
        Files.writeString(pkg.resolve("dist/index.js"), "// stub");
        Files.writeString(pkg.resolve("package.json"), "{\"name\":\"@modelcontextprotocol/server-everything\",\"bin\":{\"x\":\"dist/index.js\"},"
                + "\"dependencies\":{\"@modelcontextprotocol/server\":\"^2\",\"zod\":\"^4\"}}");
        Files.createDirectories(root.resolve("node_modules/zod"));
        Mcp.Detected d = Mcp.detect(pkg, Map.of("PATH", ""));
        assertEquals(1, d.specs.size());
        assertTrue("缺一个依赖照样报", d.specs.get(0).problem.contains("依赖还没装"));
        Files.createDirectories(root.resolve("node_modules/@modelcontextprotocol/server"));
        d = Mcp.detect(pkg, Map.of("PATH", ""));
        assertFalse("依赖在上层 node_modules 也算装好了：" + d.specs.get(0).problem, d.specs.get(0).problem.contains("依赖还没装"));
        assertTrue(Mcp.nodeDepsResolvable(pkg, List.of("zod", "@modelcontextprotocol/server")));
        assertFalse(Mcp.nodeDepsResolvable(pkg, List.of("left-pad")));
    }

    @Test public void packageJsonOfNonMcpProjectSaysSo() throws Exception {
        Path dir = Files.createTempDirectory("mcp-pj2");
        Files.writeString(dir.resolve("package.json"), "{\"name\":\"left-pad\",\"repository\":{\"type\":\"git\",\"url\":\"https://github.com/x/left-pad.git\"}}");
        Mcp.Detected d = Mcp.detect(dir.resolve("package.json"), Map.of());
        assertTrue(d.specs.isEmpty());
        assertTrue(String.valueOf(d.notes), String.valueOf(d.notes).contains("不像 MCP 服务器"));
    }

    @Test public void topLevelConfigOnlyTakesServerLikeEntries() throws Exception {
        Path dir = Files.createTempDirectory("mcp-top");
        Path f = dir.resolve("random.json");
        Files.writeString(f, "{\"repository\":{\"type\":\"git\",\"url\":\"https://github.com/a/b.git\"},\"homepage\":{\"url\":\"https://example.com\"},"
                + "\"remote\":{\"type\":\"http\",\"url\":\"https://mcp.example.com/mcp\"},\"local\":{\"command\":\"node\",\"args\":[\"x.js\"]}}");
        Mcp.Detected d = Mcp.detect(f, Map.of());
        List<String> names = new ArrayList<>();
        for (Mcp.Spec s : d.specs) names.add(s.name);
        assertEquals(List.of("remote", "local"), names.size() == 2 && names.get(0).equals("local") ? List.of(names.get(1), names.get(0)) : names);
        // mcpServers 段里的照旧：只有 url 也收
        Files.writeString(f, "{\"mcpServers\":{\"r\":{\"url\":\"https://mcp.example.com/mcp\"}}}");
        assertEquals(1, Mcp.detect(f, Map.of()).specs.size());
    }

    // ------------------------------------------------------------------ 旁白不露结果标记

    @Test public void narrationStripsOutcomeTag() {
        assertEquals("请在手机上点允许", Outcomes.stripTag("【需要你操作】请在手机上点允许"));
        assertEquals("下好了", Outcomes.stripTag("下好了\n【完成】"));
        assertEquals("中间【完成】不动", Outcomes.stripTag("中间【完成】不动"));
        assertEquals("【完成】", Outcomes.stripTag("【完成】"));
        assertEquals("", Outcomes.stripTag(null));
    }

    @Test public void narrationNoteHasNoTag() {
        List<Llm.Turn> turns = List.of(
                new Llm.Turn("【需要你操作】我先看一下目录", List.of(new Llm.ToolCall("1", "run_command", Approvals1118Test.rc("看目录", "Get-ChildItem"))),
                        Llm.msg("assistant", "x")),
                Overnight1119Test.say("【完成】\n看好了。"));
        int[] i = {0};
        List<Map<String, Object>> evs = new ArrayList<>();
        new ChatAgent().run(new ArrayList<>(List.of(Llm.msg("user", "看目录"))), (m, t) -> turns.get(Math.min(i[0]++, turns.size() - 1)),
                (cmd, a) -> Actions.Result.ok("a.txt"), new ChatAgent.Steps() {
                    @Override public void add(String text) { }
                    @Override public void step(Map<String, Object> ev) { evs.add(ev); }
                });
        boolean seen = false;
        for (Map<String, Object> e : evs) if ("note".equals(e.get("phase"))) { seen = true; assertFalse(String.valueOf(e.get("desc")).contains("【")); }
        assertTrue(seen);
    }

    // ------------------------------------------------------------------ 下载改走下载工具

    @Test public void simpleDownloadCommandsAreRecognized() {
        String home = System.getProperty("user.home");
        String[][] ok = {
                {"Invoke-WebRequest -Uri https://x.org/a.zip -OutFile C:\\Users\\me\\Downloads\\a.zip", "https://x.org/a.zip", "C:\\Users\\me\\Downloads\\a.zip"},
                {"iwr 'https://x.org/a b.zip'.Replace(' ','') -OutFile a.zip", null, null},
                {"iwr \"https://x.org/a.zip\" -OutFile \"$env:USERPROFILE\\Downloads\\a.zip\" -UseBasicParsing", "https://x.org/a.zip", home + "\\Downloads\\a.zip"},
                {"Invoke-WebRequest https://x.org/a.zip -OutF D:\\a.zip -TimeoutSec 600", "https://x.org/a.zip", "D:\\a.zip"},
                {"curl.exe -L -o C:\\t\\a.exe https://x.org/a.exe", "https://x.org/a.exe", "C:\\t\\a.exe"},
                {"curl -fsSL https://x.org/dl/tool.msi -o tool.msi", "https://x.org/dl/tool.msi", "tool.msi"},
                {"curl.exe -LO https://x.org/dl/tool.msi", "https://x.org/dl/tool.msi", "tool.msi"},
                {"curl -Uri https://x.org/a.zip -OutFile a.zip", "https://x.org/a.zip", "a.zip"},
                {"wget https://x.org/f/data.tar.gz -P D:\\dl", "https://x.org/f/data.tar.gz", "D:\\dl\\data.tar.gz"},
                {"(New-Object System.Net.WebClient).DownloadFile('https://x.org/a.zip', 'C:\\a.zip')", "https://x.org/a.zip", "C:\\a.zip"},
                {"$ProgressPreference='SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://x.org/a.apk' -OutFile 'D:\\a.apk'", "https://x.org/a.apk", "D:\\a.apk"},
                {"Start-BitsTransfer -Source https://x.org/a.iso -Destination E:\\a.iso", "https://x.org/a.iso", "E:\\a.iso"},
        };
        for (String[] c : ok) {
            DownloadRewrite.Dl d = DownloadRewrite.parse(c[0]);
            if (c[1] == null) { assertNull(c[0], d); continue; }
            assertNotNull(c[0], d);
            assertEquals(c[0], c[1], d.url);
            assertEquals(c[0], c[2], d.path);
        }
        assertEquals(Long.valueOf(600), DownloadRewrite.parse("Invoke-WebRequest https://x.org/a.zip -OutF D:\\a.zip -TimeoutSec 600").timeoutSec);
    }

    @Test public void complexOrNonDownloadCommandsAreNotRewritten() {
        for (String c : new String[] {
                "Invoke-WebRequest -Uri https://x.org/a.zip -OutFile a.zip; Expand-Archive a.zip",
                "Invoke-WebRequest -Uri $u -OutFile a.zip",
                "Invoke-WebRequest -Uri https://x.org/a.zip -OutFile $p",
                "Invoke-WebRequest -Uri https://x.org/a.zip -OutFile a.zip -Headers @{a='b'}",
                "Invoke-WebRequest -Uri https://x.org/api -Method Post -OutFile r.json",
                "curl -k -o a.exe https://x.org/a.exe",
                "curl https://api.x.org/status",
                "Invoke-RestMethod https://api.github.com/repos/a/b/releases/latest",
                "curl -o - https://x.org/a.txt",
                "Get-ChildItem",
                "iwr ftp://x.org/a.zip -OutFile a.zip",
        }) assertNull(c, DownloadRewrite.parse(c));
        assertFalse("查接口不算下载", DownloadRewrite.mentions("Invoke-RestMethod https://api.github.com/repos/a/b/releases/latest"));
        assertFalse(DownloadRewrite.mentions("curl https://api.x.org/status"));
        assertTrue(DownloadRewrite.mentions("Invoke-WebRequest -Uri $u -OutFile a.zip; Expand-Archive a.zip"));
        assertTrue(DownloadRewrite.mentions("$wc=New-Object Net.WebClient; $wc.DownloadFile($u,$p)"));
        assertTrue(DownloadRewrite.mentions("curl.exe -L -o $p $u"));
    }

    @Test public void runCommandDownloadMapsToDownloadTool() {
        Object[] m = ChatAgent.map(new Llm.ToolCall("1", "run_command", Approvals1118Test.rc("下载", "Invoke-WebRequest -Uri https://x.org/a.zip -OutFile C:\\Users\\me\\Downloads\\a.zip")));
        assertEquals("net_download", m[0]);
        @SuppressWarnings("unchecked") Map<String, Object> a = (Map<String, Object>) m[1];
        assertEquals("https://x.org/a.zip", a.get("url"));
        assertEquals("C:\\Users\\me\\Downloads\\a.zip", a.get("path"));
        Object[] n = ChatAgent.map(new Llm.ToolCall("2", "run_command", Approvals1118Test.rc("看", "Get-ChildItem")));
        assertEquals("sys_exec", n[0]);
        assertTrue("工具说明也写了", Json.stringify(ChatAgent.tools()).contains("下载文件不要用它"));
    }

    /** [fix-1123-a] 以前「前两次提示、第三次照原命令执行」；[rel-1123 合并] 现在不执行、直接停下问用户，见 Fix1123ATest.mixedDownloadIsNeverRunAsIs。 */
    @Test public void mixedDownloadGetsHintThenFallsBack() {
        String mixed = "Invoke-WebRequest -Uri $u -OutFile a.zip; Expand-Archive a.zip";
        List<Llm.Turn> turns = List.of(
                Overnight1119Test.call("1", "run_command", Approvals1118Test.rc("下", mixed)),
                Overnight1119Test.call("2", "run_command", Approvals1118Test.rc("下", mixed + " -Force")),
                Overnight1119Test.call("3", "run_command", Approvals1118Test.rc("下", mixed + " -Force -Verbose")),
                Overnight1119Test.say("【完成】\n好了。"));
        int[] i = {0};
        List<String> ran = new ArrayList<>();
        List<Map<String, Object>> evs = new ArrayList<>();
        ChatAgent.Outcome o = new ChatAgent().run(new ArrayList<>(List.of(Llm.msg("user", "下载并解压"))), (m, t) -> turns.get(Math.min(i[0]++, turns.size() - 1)),
                (cmd, a) -> { ran.add(cmd + " " + a.get("command")); return Actions.Result.ok("ok"); }, new ChatAgent.Steps() {
                    @Override public void add(String text) { }
                    @Override public void step(Map<String, Object> ev) { evs.add(ev); }
                });
        // [rel-1123 合并] 用户定：认不全就不执行、马上停下问用户（要下什么、从哪下、存哪），不再让模型反复拆
        assertEquals(Outcomes.NEEDS_USER, o.status);
        assertEquals("不执行", 0, ran.size());
        assertEquals("第一次就停下问，模型只被问了一次", 1, i[0]);
        assertEquals(1, evs.stream().filter(e -> String.valueOf(e.get("desc")).contains("没执行，先问你")).count());
    }

    @Test public void rewrittenDownloadShowsNote() {
        List<Llm.Turn> turns = List.of(
                Overnight1119Test.call("1", "run_command", Approvals1118Test.rc("下", "curl.exe -L -o C:\\t\\a.exe https://x.org/a.exe")),
                Overnight1119Test.say("【完成】\n好了。"));
        int[] i = {0};
        List<String> ran = new ArrayList<>();
        List<Map<String, Object>> evs = new ArrayList<>();
        new ChatAgent().run(new ArrayList<>(List.of(Llm.msg("user", "下载"))), (m, t) -> turns.get(Math.min(i[0]++, turns.size() - 1)),
                (cmd, a) -> { ran.add(cmd + " " + a.get("url")); return Actions.Result.ok("下好了"); }, new ChatAgent.Steps() {
                    @Override public void add(String text) { }
                    @Override public void step(Map<String, Object> ev) { evs.add(ev); }
                });
        assertEquals(List.of("net_download https://x.org/a.exe"), ran);
        assertTrue(evs.stream().anyMatch(e -> String.valueOf(e.get("desc")).contains("改用下载工具，任务卡上显示进度")));
    }

    // ------------------------------------------------------------------ 任务通知标签

    @Test public void taskNotificationLabelFollowsState() throws Exception {
        assertEquals("任务完成", Notify.label("task", Outcomes.DONE));
        assertEquals("需要你操作", Notify.label("task", Outcomes.NEEDS_USER));
        assertEquals("没做成", Notify.label("task", Outcomes.FAILED));
        assertEquals("有警告", Notify.label("task", Outcomes.WARN));
        assertEquals("已停止", Notify.label("task", "stopped"));
        assertEquals("任务结果", Notify.label("task"));
        assertEquals("提醒", Notify.label("reminder", "done"));
        Store s = new Store(Files.createTempDirectory("nt").resolve("state.json"));
        List<String> log = new ArrayList<>();
        Notify n = new Notify(s, log::add);
        Map<String, Object> saved = n.post("task", "任务需要你操作：打开微信", "去手机上点一下", "", Outcomes.NEEDS_USER);
        assertEquals(Outcomes.NEEDS_USER, saved.get("state"));
        assertTrue(log.get(log.size() - 1).contains("［需要你操作］"));
        assertFalse(log.get(log.size() - 1).contains("任务完成"));
        String app = Files.readString(Path.of("src/main/java/com/onesay/desktop/App.java"), StandardCharsets.UTF_8);
        assertTrue("下载失败不再挂「任务完成」", app.contains("\"下载失败\", (t.title.isEmpty() ? t.url : t.title) + \"：\" + t.error, t.from, Outcomes.FAILED)"));
        assertTrue(app.contains("\"文件没发到手机\", r.text, phone, r.ok ? Outcomes.DONE : Outcomes.FAILED)"));
        String h = html();
        assertTrue(h.contains("task:'任务结果'"));
        assertTrue(h.contains("needs_user:'需要你操作'"));
        assertTrue(h.contains("${esc(kindLabel(n))}"));
    }

    // ------------------------------------------------------------------ 设置页：LAN 状态、主题

    @Test public void settingsPageShowsLanOffAndRefreshesThemes() throws Exception {
        String h = html();
        assertTrue(h.contains("s.lan_env_off?'<span class=\"tag\">这次没开（ONESAY_LAN=off）</span>'"));
        assertTrue("换主题马上刷新", h.contains("if(k==='theme'||k==='theme_pack')refresh()"));
        assertTrue("进设置页重读主题包", h.contains("if(p==='settings'&&page!=='settings')THEMES=null"));
        String app = new String(Files.readAllBytes(Path.of("src/main/java/com/onesay/desktop/App.java")), StandardCharsets.UTF_8);
        assertTrue(app.contains("s.put(\"lan_env_off\""));
    }
}
