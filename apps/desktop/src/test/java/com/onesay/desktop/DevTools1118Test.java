package com.onesay.desktop;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * [phone-tools-1118] 对话里的手机工具（走已配对的手机联动）；后面几批（截屏 / 看图、安卓界面、按任务分段的上下文）的测试也放这里。
 */
public class DevTools1118Test {

    static Llm.Turn call(String id, String name, String argsJson) {
        return new Llm.Turn(null, List.of(new Llm.ToolCall(id, name, argsJson)), Llm.msg("assistant", ""));
    }

    static Llm.Turn say(String text) { return new Llm.Turn(text, List.of(), Llm.msg("assistant", text)); }

    static Chat chat() throws Exception {
        Store s = new Store(Files.createTempDirectory("dt").resolve("state.json"));
        Config c = new Config(Files.createTempFile("dt", ".properties"));
        return new Chat(c, s, new Main.TestActions(), List::of, x -> { });
    }

    // ------------------------------------------------------------------ 手机工具

    @Test public void phoneToolsAreRegisteredAndMapped() {
        assertTrue(ChatAgent.toolNames().containsAll(List.of("phone_status", "phone_open_app", "phone_operate")));
        assertEquals("phone_operate", ChatAgent.map(new Llm.ToolCall("1", "phone_operate", "{\"description\":\"x\",\"goal\":\"打开设置看系统版本\"}"))[0]);
        assertNull("目标为空不收", ChatAgent.map(new Llm.ToolCall("1", "phone_operate", "{\"goal\":\" \"}")));
        assertEquals("phone_open_app", ChatAgent.map(new Llm.ToolCall("1", "phone_open_app", "{\"name\":\"微信\"}"))[0]);
        assertEquals("phone_status", ChatAgent.map(new Llm.ToolCall("1", "phone_status", "{}"))[0]);
        assertTrue(ChatAgent.badArgs(new Llm.ToolCall("1", "phone_operate", "{}")).contains("goal"));
        assertTrue(ChatAgent.badArgs(new Llm.ToolCall("1", "nope", "{}")).contains("phone_operate"));
        assertNull(PhoneCmds.check("operate", Map.of("goal", "打开设置")));
        assertNotNull(PhoneCmds.check("operate", Map.of()));
        assertEquals(LinkPolicy.Cat.APP_OPERATE, LinkPolicy.phoneCat("operate"));
    }

    @Test public void promptSaysItCanControlPhonesAndListsConnectedOnes() throws Exception {
        Chat ch = chat();
        ch.pcExec = (c, a) -> Actions.Result.ok("ok");
        ch.phones = () -> new TreeMap<>(Map.of("meizu21", "局域网·加密"));
        String p = ch.systemPrompt();
        assertTrue(p.contains("不要说你没法控制手机"));
        assertTrue(p.contains("meizu21（局域网·加密）"));
        ch.phones = TreeMap::new;
        p = ch.systemPrompt();
        assertTrue(p.contains("现在没有手机连着这台电脑"));
        assertTrue("没连着就写怎么连", p.contains("扫码配对"));
    }

    @Test public void phoneToolsGoOverTheLinkAndExplainHowToConnect() throws Exception {
        Chat ch = chat();
        List<String> sent = new ArrayList<>();
        ch.phone = (cmd, a) -> {
            sent.add(cmd + a);
            if (cmd.equals("status")) return Actions.Result.ok("ok", new TreeMap<>(Map.of("battery", 80L, "screen_on", true)));
            if (cmd.equals("caps")) return Actions.Result.ok("ok", new TreeMap<>(Map.of("caps", List.of("ping", "status", "open_app"))));
            return Actions.Result.ok("已交给手机上的 OneSay 去做");
        };
        // 没连着：说怎么连，不往手机发
        ch.phones = TreeMap::new;
        Actions.Result r = ch.phoneTool("phone_operate", Map.of("goal", "打开设置"));
        assertFalse(r.ok);
        assertTrue(r.text.contains("扫码配对"));
        assertTrue(sent.isEmpty());
        // 连着
        ch.phones = () -> new TreeMap<>(Map.of("p1", "局域网"));
        r = ch.phoneTool("phone_status", Map.of());
        assertTrue(r.ok);
        assertTrue(r.text.contains("电量 80%"));
        assertTrue("没开按目标操作要说", r.text.contains("按目标操作手机"));
        r = ch.phoneTool("phone_operate", Map.of("goal", "打开设置查系统版本"));
        assertTrue(r.ok);
        assertTrue(sent.get(sent.size() - 1).startsWith("operate{goal=打开设置查系统版本}"));
        // 只读预设：手机动作不做，状态照查
        ch.phoneTool("phone_status", Map.of());
        Config c = new Config(Files.createTempFile("dt", ".properties"));
        c.chatPreset = "read_only";
        Chat ro = new Chat(c, new Store(Files.createTempDirectory("dt").resolve("state.json")), new Main.TestActions(), List::of, x -> { });
        ro.phone = ch.phone;
        ro.phones = ch.phones;
        assertFalse(ro.phoneTool("phone_open_app", Map.of("name", "微信")).ok);
        assertTrue(ro.phoneTool("phone_status", Map.of()).ok);
        // 手机不在线：原因 + 怎么连
        ch.phone = (cmd, a) -> Actions.Result.fail("手机没有回应");
        r = ch.phoneTool("phone_open_app", Map.of("name", "微信"));
        assertTrue(r.text.contains("手机没有回应") && r.text.contains("接受电脑指令"));
    }

    @Test public void phoneOperateRunsInsideATaskWithoutGoingThroughPcExec() throws Exception {
        Chat ch = chat();
        AtomicInteger pc = new AtomicInteger();
        ch.pcExec = (c, a) -> { pc.incrementAndGet(); return Actions.Result.ok("ok"); };
        ch.phones = () -> new TreeMap<>(Map.of("p1", "局域网"));
        List<String> sent = new ArrayList<>();
        ch.phone = (cmd, a) -> { sent.add(cmd); return Actions.Result.ok("已交给手机上的 OneSay 去做"); };
        AtomicInteger n = new AtomicInteger();
        ch.brain = (m, t) -> n.incrementAndGet() == 1 ? call("1", "phone_operate", "{\"description\":\"让手机打开设置\",\"goal\":\"打开设置\"}") : say("交给手机了");
        Map<String, Object> card = ch.send("帮我把手机上最近一次系统更新的说明找出来，告诉我改了什么", "text");
        assertEquals("done", card.get("state"));
        assertEquals(List.of("operate"), sent);
        assertEquals("手机工具不走电脑执行链", 0, pc.get());
    }

    // ------------------------------------------------------------------ 截屏 / 看图 / 显示文件

    static byte[] tinyPng() throws Exception {
        java.awt.image.BufferedImage im = new java.awt.image.BufferedImage(4, 3, java.awt.image.BufferedImage.TYPE_INT_RGB);
        return DeviceTools.png(im);
    }

    @Test public void screenshotFallsBackToLabelledOcrAndShowsImageInCard() throws Exception {
        Chat ch = chat();
        java.nio.file.Path ws = Files.createTempDirectory("ws");
        ch.workspace = ws.toString();
        ch.pcExec = (c, a) -> Actions.Result.ok("ok");
        byte[] png = tinyPng();
        List<String> shot = new ArrayList<>();
        ch.shooter = t -> { shot.add(t); return new Object[] {png, t.equals("pc") ? "pc" : t}; };
        ch.looker = () -> null;
        ch.ocr = f -> "uid=0(root) gid=0(root) context=u:r:su:s0";
        AtomicInteger n = new AtomicInteger();
        List<List<Map<String, Object>>> seen = new ArrayList<>();
        ch.brain = (m, t) -> { seen.add(new ArrayList<>(m)); return n.incrementAndGet() == 1 ? call("1", "screenshot", "{\"description\":\"看模拟器屏幕\",\"target\":\"127.0.0.1:16384\",\"question\":\"有没有 uid=0\"}") : say("拿到 root 了"); };
        Map<String, Object> card = ch.send("帮我确认模拟器里 su 拿到 root 了没有，截图给我看", "text");
        assertEquals("done", card.get("state"));
        assertEquals(List.of("127.0.0.1:16384"), shot);
        Map<?, ?> st = (Map<?, ?>) ((List<?>) card.get("steps")).get(0);
        String img = String.valueOf(st.get("image"));
        assertTrue("存在工作区 shots/ 里", img.startsWith(ws.resolve("shots").toString()) && img.endsWith(".png"));
        assertTrue(Files.isRegularFile(java.nio.file.Paths.get(img)));
        // 给模型的结果标明是 OCR，带读到的文字
        String toolRes = "";
        for (Map<String, Object> m : seen.get(1)) if ("tool".equals(m.get("role"))) toolRes = String.valueOf(m.get("content"));
        assertTrue(toolRes, toolRes.contains("OCR") && toolRes.contains("uid=0(root)"));
        // 界面能取到这张图；没登记的路径不给
        Map<String, Object> a = ch.artifact(img);
        assertEquals(Boolean.TRUE, a.get("ok"));
        assertEquals("image/png", a.get("mime"));
        assertArrayEquals(png, java.util.Base64.getDecoder().decode(String.valueOf(a.get("b64"))));
        java.nio.file.Path other = Files.writeString(ws.resolve("secret.txt"), "x");
        assertEquals(Boolean.FALSE, ch.artifact(other.toString()).get("ok"));
        assertEquals(Boolean.FALSE, ch.artifact("").get("ok"));
    }

    @Test public void screenshotUsesVisionModelWhenConfiguredAndFallsBackOnError() throws Exception {
        Chat ch = chat();
        ch.workspace = Files.createTempDirectory("ws").toString();
        byte[] png = tinyPng();
        ch.shooter = t -> new Object[] {png, "pc"};
        ch.ocr = f -> "OCR 文字";
        ch.looker = () -> new Chat.Looker() {
            @Override public String look(byte[] p, String q) { return "桌面上开着 MuMu，弹出了超级用户请求：允许 / 拒绝，下方有「永久记住选择」复选框（未勾选）"; }
            @Override public String toString() { return "qwen-vl-max"; }
        };
        Actions.Result r = ch.screenLook(Map.of("target", "pc"));
        assertTrue(r.ok);
        assertTrue(String.valueOf(r.data.get("output")).contains("看图模型（qwen-vl-max）"));
        assertTrue(String.valueOf(r.data.get("output")).contains("永久记住选择"));
        ch.looker = () -> new Chat.Looker() { @Override public String look(byte[] p, String q) throws Exception { throw new Exception("看图模型返回 401"); } };
        r = ch.screenLook(Map.of("target", "pc"));
        assertTrue(String.valueOf(r.data.get("output")).contains("改用 OCR"));
        assertTrue(String.valueOf(r.data.get("output")).contains("OCR 文字"));
        // 截不到：如实失败
        ch.shooter = t -> { throw new Exception("adb 没连着任何安卓设备"); };
        r = ch.screenLook(Map.of("target", "android"));
        assertFalse(r.ok);
        assertTrue(r.text.contains("adb 没连着"));
    }

    @Test public void promptForbidsGuessingFromFileSizeAndFileUrls() {
        String p = ChatAgent.systemPrompt("now", "C:\\ws", "C:\\Users\\me", "", ChatPolicy.Preset.DEFAULT);
        assertTrue(p.contains("不要用截图文件的大小"));
        assertTrue(p.contains("show_file"));
        assertTrue(p.contains("不要用 open_url 打开 file:"));
        assertEquals("screen_look", ChatAgent.map(new Llm.ToolCall("1", "screenshot", "{\"description\":\"看\"}"))[0]);
        assertEquals("pc", ((Map<?, ?>) ChatAgent.map(new Llm.ToolCall("1", "screenshot", "{}"))[1]).get("target"));
    }

    @Test public void visionConfigComesFromVisionOrBailianKeysNeverElsewhere() throws Exception {
        Config c = new Config(Files.createTempFile("dt", ".properties"));
        assertNull("什么都没配 = 没有看图模型", DeviceTools.Vision.from(c));
        c.llmProvider = "deepseek";
        c.llmKey = "sk-deepseek";
        assertNull("DeepSeek 不能看图", DeviceTools.Vision.from(c));
        c.asrKey = "sk-asr";
        DeviceTools.Vision v = DeviceTools.Vision.from(c);
        assertEquals(DeviceTools.Vision.DASHSCOPE, v.base);
        assertEquals("sk-asr", v.key);
        assertEquals("qwen-vl-max", v.model);
        c.llmProvider = "bailian";
        c.llmKey = "sk-bl";
        assertEquals("sk-bl", DeviceTools.Vision.from(c).key);
        c.visionKey = "sk-v";
        c.visionBase = "https://example.com/v1/";
        c.visionModel = "gpt-4o-mini";
        v = DeviceTools.Vision.from(c);
        assertEquals("https://example.com/v1", v.base);
        assertEquals("gpt-4o-mini", v.model);
        String body = Json.stringify(v.body(new byte[] {1, 2, 3}, "看看"));
        assertTrue(body.contains("data:image/jpeg;base64,AQID"));
        assertTrue(body.contains("image_url"));
        // 存取设置
        c.save();
        Config d = Config.load(c.file);
        assertEquals("sk-v", d.visionKey);
        assertEquals("gpt-4o-mini", d.visionModel);
    }

    @Test public void showFileAndWrittenFilesAreViewableOnlyFromSafePlaces() throws Exception {
        Chat ch = chat();
        java.nio.file.Path ws = Files.createTempDirectory("ws");
        ch.workspace = ws.toString();
        Files.writeString(ws.resolve("report.txt"), "结果：好");
        Actions.Result r = ch.showFile("report.txt");
        assertTrue(r.ok);
        assertEquals(ws.resolve("report.txt").toString(), r.data.get("file"));
        assertFalse(ch.showFile("nope.txt").ok);
        Files.write(ws.resolve("a.exe"), new byte[] {1});
        assertFalse("不是图片 / 文本", ch.showFile("a.exe").ok);
        // write_file 写好的文件带上「查看」
        ch.pcExec = (c, a) -> {
            if (c.equals("file_write")) Files.writeString(ws.resolve(String.valueOf(a.get("path"))), String.valueOf(a.get("text")));
            return Actions.Result.ok("已写入");
        };
        Actions.Result w = ch.local(ch.pcExec, "file_write", new TreeMap<>(Map.of("path", "ocr.ps1", "text", "x")));
        assertEquals(ws.resolve("ocr.ps1").toString(), w.data.get("file"));
        assertEquals("Windows OCR 中文空格去掉", "设置 超级用户", DeviceTools.tidyOcr("设 置 超 级 用 户").replace("置超", "置 超"));
        assertEquals("uid=0(root) 允许", DeviceTools.tidyOcr("uid=0(root) 允 许"));
    }

    // ------------------------------------------------------------------ 安卓界面：先勾「永久记住选择」再点「允许」

    static String suDialog(boolean checked) {
        return "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">"
                + "<node index=\"0\" text=\"\" resource-id=\"\" class=\"android.widget.FrameLayout\" content-desc=\"\" checkable=\"false\" checked=\"false\" clickable=\"false\" enabled=\"true\" bounds=\"[0,0][1920,1080]\">"
                + "<node index=\"0\" text=\"超级用户请求\" class=\"android.widget.TextView\" checkable=\"false\" checked=\"false\" clickable=\"false\" enabled=\"true\" bounds=\"[660,300][1260,360]\" />"
                + "<node index=\"1\" text=\"Shell 请求 &quot;root&quot; 权限\" class=\"android.widget.TextView\" checkable=\"false\" checked=\"false\" clickable=\"false\" enabled=\"true\" bounds=\"[660,380][1260,430]\" />"
                + "<node index=\"2\" text=\"\" class=\"android.widget.LinearLayout\" checkable=\"false\" checked=\"false\" clickable=\"true\" enabled=\"true\" bounds=\"[660,700][1260,770]\">"
                + "<node index=\"0\" text=\"\" class=\"android.widget.CheckBox\" checkable=\"true\" checked=\"" + checked + "\" clickable=\"false\" enabled=\"true\" bounds=\"[670,710][720,760]\" />"
                + "<node index=\"1\" text=\"永久记住选择\" class=\"android.widget.TextView\" checkable=\"false\" checked=\"false\" clickable=\"false\" enabled=\"true\" bounds=\"[730,710][1000,760]\" />"
                + "</node>"
                + "<node index=\"3\" text=\"拒绝\" class=\"android.widget.Button\" checkable=\"false\" checked=\"false\" clickable=\"true\" enabled=\"true\" bounds=\"[660,800][950,860]\" />"
                + "<node index=\"4\" text=\"允许(9)\" class=\"android.widget.Button\" checkable=\"false\" checked=\"false\" clickable=\"true\" enabled=\"true\" bounds=\"[970,800][1260,860]\" />"
                + "</node></hierarchy>";
    }

    @Test public void parsesUiautomatorDumpWithChineseAndBounds() {
        List<DeviceTools.Node> n = DeviceTools.parseUi(suDialog(false));
        assertEquals(8, n.size());
        DeviceTools.Node allow = DeviceTools.findByText(n, "允许");
        assertEquals("允许(9)", allow.text);
        assertEquals(1115, allow.cx());
        assertEquals(830, allow.cy());
        assertEquals("Shell 请求 \"root\" 权限", n.get(2).text);
        DeviceTools.Node rem = DeviceTools.findByText(n, "永久记住选择");
        DeviceTools.Node cb = DeviceTools.checkboxFor(n, rem);
        assertEquals("android.widget.CheckBox", cb.cls);
        assertEquals(695, cb.cx());
        assertTrue(DeviceTools.summarize(n, 80).contains("「永久记住选择」"));
        assertTrue(DeviceTools.summarize(n, 80).contains("未勾选"));
        assertNull(DeviceTools.findByText(n, "确定"));
        // 复选框自己带文字
        List<DeviceTools.Node> n2 = DeviceTools.parseUi("<hierarchy><node text=\"记住选择\" class=\"android.widget.CheckBox\" checkable=\"true\" checked=\"true\" clickable=\"true\" bounds=\"[0,0][100,50]\"/></hierarchy>");
        assertTrue(DeviceTools.checkboxFor(n2, DeviceTools.findByText(n2, "记住选择")).checked);
        // 同一行、不同父节点的复选框也能配上
        List<DeviceTools.Node> n3 = DeviceTools.parseUi("<hierarchy><node bounds=\"[0,0][500,500]\"><node bounds=\"[0,0][500,100]\"><node text=\"永久记住\" bounds=\"[60,10][300,60]\"/></node>"
                + "<node bounds=\"[0,0][60,100]\"><node class=\"android.widget.CheckBox\" checkable=\"true\" checked=\"false\" bounds=\"[5,15][45,55]\"/></node>"
                + "<node class=\"android.widget.CheckBox\" checkable=\"true\" checked=\"false\" bounds=\"[5,300][45,340]\"/></node></hierarchy>");
        assertEquals(25, DeviceTools.checkboxFor(n3, DeviceTools.findByText(n3, "永久记住")).cx());
        assertEquals(35, DeviceTools.checkboxFor(n3, DeviceTools.findByText(n3, "永久记住")).cy());
    }

    @Test public void adbHelpersParseDevicesAndPickMuMu() {
        Map<String, String> d = DeviceTools.parseDevices("List of devices attached\n127.0.0.1:16384\tdevice\nemulator-5554\toffline\n\n");
        assertEquals("device", d.get("127.0.0.1:16384"));
        assertEquals("127.0.0.1:16384", DeviceTools.pickSerial(d, ""));
        assertEquals("127.0.0.1:16384", DeviceTools.pickSerial(d, "android"));
        assertEquals("abc", DeviceTools.pickSerial(d, "abc"));
        assertNull(DeviceTools.pickSerial(DeviceTools.parseDevices("List of devices attached\na\tdevice\nb\tdevice\n"), ""));
        assertTrue(DeviceTools.userMustTap("确认付款"));
        assertTrue(DeviceTools.userMustTap("卸载"));
        assertFalse(DeviceTools.userMustTap("允许(9)"));
        assertTrue(DeviceTools.ocrScript("C:\\a'b.png").contains("'C:\\a''b.png'"));
    }

    @Test public void ticksRememberThenTapsAllowInOneStep() throws Exception {
        Chat ch = chat();
        List<String> taps = new ArrayList<>();
        boolean[] checked = {false};
        ch.droid = new Chat.Droid() {
            @Override public String serial(String w) { return w.isEmpty() ? "127.0.0.1:16384" : w; }
            @Override public String dump(String s) { return suDialog(checked[0]); }
            @Override public void tap(String s, int x, int y) { taps.add(s + "@" + x + "," + y); if (x == 695 && y == 735) checked[0] = true; }
        };
        Actions.Result r = ch.androidUi(new TreeMap<>(Map.of("action", "check_text", "text", "永久记住选择", "then_tap", "允许")));
        assertTrue(r.text, r.ok);
        assertEquals(List.of("127.0.0.1:16384@695,735", "127.0.0.1:16384@1115,830"), taps);
        assertTrue(r.text.contains("勾上了") && r.text.contains("允许(9)"));
        // 已经勾着：不再点复选框
        taps.clear();
        r = ch.androidUi(new TreeMap<>(Map.of("action", "check_text", "text", "永久记住选择")));
        assertTrue(r.ok);
        assertTrue(r.text.contains("本来就勾着"));
        assertTrue(taps.isEmpty());
        // 找不到：失败并把界面上有的列给模型
        r = ch.androidUi(new TreeMap<>(Map.of("action", "tap_text", "text", "确定")));
        assertFalse(r.ok);
        assertTrue(String.valueOf(r.data.get("output")).contains("「允许(9)」"));
        // dump
        r = ch.androidUi(new TreeMap<>(Map.of("action", "dump")));
        assertTrue(String.valueOf(r.data.get("output")).contains("超级用户请求"));
        // 只读预设只能 dump
        Config c = new Config(Files.createTempFile("dt", ".properties"));
        c.chatPreset = "read_only";
        Chat ro = new Chat(c, new Store(Files.createTempDirectory("dt").resolve("state.json")), new Main.TestActions(), List::of, x -> { });
        ro.droid = ch.droid;
        assertFalse(ro.androidUi(new TreeMap<>(Map.of("action", "tap_text", "text", "允许"))).ok);
        assertTrue(ro.androidUi(new TreeMap<>(Map.of("action", "dump"))).ok);
        // 映射：check 要有 text
        assertNull(ChatAgent.map(new Llm.ToolCall("1", "android_ui", "{\"action\":\"check_text\"}")));
        assertEquals("android_ui", ChatAgent.map(new Llm.ToolCall("1", "android_ui", "{\"action\":\"dump\",\"device\":\"127.0.0.1:16384\"}"))[0]);
        assertTrue(ChatAgent.systemPrompt("now", "w", "h", "", ChatPolicy.Preset.DEFAULT).contains("不要硬编码 input tap 坐标"));
    }

    @Test public void paymentButtonsAreNeverTappedForTheUser() throws Exception {
        Chat ch = chat();
        List<String> taps = new ArrayList<>();
        ch.droid = new Chat.Droid() {
            @Override public String serial(String w) { return "p1"; }
            @Override public String dump(String s) { return "<hierarchy><node text=\"确认付款\" clickable=\"true\" bounds=\"[0,0][100,50]\"/></hierarchy>"; }
            @Override public void tap(String s, int x, int y) { taps.add(x + "," + y); }
        };
        Actions.Result r = ch.androidUi(new TreeMap<>(Map.of("action", "tap_text", "text", "确认付款")));
        assertFalse(r.ok);
        assertTrue(r.text.contains("不替用户点"));
        assertTrue(taps.isEmpty());
    }

    // ------------------------------------------------------------------ 按任务分段的上下文

    static Map<String, Object> card(Store s, String task, String title, String state, String answer, int steps, String outPrefix) {
        List<Object> st = new ArrayList<>();
        for (int i = 1; i <= steps; i++) {
            Map<String, Object> x = new TreeMap<>();
            x.put("kind", "step");
            x.put("n", (long) i);
            x.put("desc", title + " 第" + i + "步");
            x.put("detail", "执行命令：cmd" + i);
            x.put("state", "ok");
            x.put("summary", "ok" + i);
            x.put("output", outPrefix + i + " " + "o".repeat(400));
            st.add(x);
        }
        Map<String, Object> m = new TreeMap<>();
        m.put("role", "assistant");
        m.put("via", "task");
        m.put("task", task);
        m.put("title", title);
        m.put("state", state);
        m.put("answer", answer);
        m.put("steps", st);
        m.put("text", "");
        m.put("at", System.currentTimeMillis());
        return s.add("chat", m, Store.MAX_CHAT);
    }

    static String joined(List<Map<String, Object>> ctx) {
        StringBuilder b = new StringBuilder();
        for (Map<String, Object> m : ctx) b.append(m.get("role")).append(':').append(m.get("content")).append('|');
        return b.toString();
    }

    @Test public void continueBindsToLatestUnfinishedTaskWithItsFullRecordAndOutputs() throws Exception {
        Store s = new Store(Files.createTempDirectory("dt").resolve("state.json"));
        Chat ch = new Chat(new Config(Files.createTempFile("dt", ".properties")), s, new Main.TestActions(), List::of, x -> { });
        s.add("chat", Map.of("role", "user", "text", "查一下天气预报", "at", 1L), Store.MAX_CHAT);
        card(s, "t1", "查一下天气预报", "done", "晴，25 度", 2, "WEATHER");
        s.add("chat", Map.of("role", "user", "text", "整理桌面上的截图文件夹", "at", 2L), Store.MAX_CHAT);
        card(s, "t2", "整理桌面上的截图文件夹", "done", "整理好了", 2, "DESK");
        s.add("chat", Map.of("role", "user", "text", "让 MuMu 模拟器拿到 root，截图证明", "at", 3L), Store.MAX_CHAT);
        Map<String, Object> root = card(s, "t3", "让 MuMu 模拟器拿到 root，截图证明", "time_limit", "到时长上限，先停下", 6, "ROOTOUT");
        s.add("chat", Map.of("role", "user", "text", "MuMu root 的事先放一放，看看下载文件夹多大", "at", 4L), Store.MAX_CHAT);
        card(s, "t4", "看看下载文件夹多大", "done", "3.2 GB", 1, "DL");
        // 程序重启过：taskCard 是空的；「继续」也要找到没做完的 root 任务
        assertTrue(ch.continuing());
        List<Map<String, Object>> latest = s.list("chat");
        // 最近的任务（t4）做完了 → 最近一个没做完的就是它前面那个吗？按约定：最近一张卡没做完才算；这里 t4 完成 → 退回最近的卡
        assertNull(Chat.latestUnfinished(latest));
        s.update("chat", String.valueOf(root.get("id")), Map.of("at", System.currentTimeMillis()));
        // 让 root 任务成为最后一张卡（再停一次）
        Map<String, Object> root2 = card(s, "t5", "让 MuMu 模拟器拿到 root，截图证明（接着）", "stopped", "已停止", 5, "ROOT2OUT");
        assertEquals(root2.get("id"), Chat.latestUnfinished(s.list("chat")).get("id"));
        s.add("chat", Map.of("role", "user", "text", "继续", "at", System.currentTimeMillis()), Store.MAX_CHAT);
        String id = ch.startTask(true);
        assertEquals("「继续」接着最近一个没做完的任务", root2.get("id"), id);
        assertEquals("t5", ch.taskId);
        String all = joined(ch.context());
        assertTrue("当前任务完整步骤", all.contains("当前任务「让 MuMu 模拟器拿到 root，截图证明（接着）」") && all.contains("5. "));
        assertTrue("最近几步的输出原样给", all.contains("ROOT2OUT5") && all.contains("ROOT2OUT2"));
        assertFalse("只给最近 4 步的输出", all.contains("ROOT2OUT1 "));
        assertFalse("更早任务的输出不给", all.contains("WEATHER1") || all.contains("DESK1"));
        assertTrue("相关的旧任务给一句摘要", all.contains("更早的相关任务") && all.contains("「让 MuMu 模拟器拿到 root，截图证明」没做完"));
        assertTrue(all.endsWith("user:继续|"));
        assertTrue(ChatAgent.systemPrompt("n", "w", "h", "", ChatPolicy.Preset.DEFAULT).contains("不要把做过的步骤原样再做一遍"));
    }

    @Test public void newTaskSeesOnlySummariesOfOldTasksAndOldOutputsGetCompacted() throws Exception {
        Store s = new Store(Files.createTempDirectory("dt").resolve("state.json"));
        Chat ch = new Chat(new Config(Files.createTempFile("dt", ".properties")), s, new Main.TestActions(), List::of, x -> { });
        for (int i = 0; i < 4; i++) {
            s.add("chat", Map.of("role", "user", "text", "任务" + i), Store.MAX_CHAT);
            card(s, "t" + i, "任务" + i, "done", "答" + i, 2, "OUT" + i + "_");
        }
        ch.compactOldCards();
        int compacted = 0;
        for (Map<String, Object> m : s.list("chat")) if (Boolean.TRUE.equals(m.get("compacted"))) {
            compacted++;
            for (Object o : (List<?>) m.get("steps")) assertTrue(String.valueOf(((Map<?, ?>) o).get("output")).length() < 340);
        }
        assertEquals("最近两张以外的都压缩", 2, compacted);
        s.add("chat", Map.of("role", "user", "text", "新的事：查 IP"), Store.MAX_CHAT);
        ch.taskCard = "";
        String id = ch.startTask(false);
        String all = joined(ch.context());
        assertFalse("新任务不带旧任务的命令输出", all.contains("OUT3_1 ooo"));
        assertTrue("上一个任务给简短步骤记录", all.contains("任务「任务3」实际执行过的步骤"));
        assertNotNull(id);
    }

    @Test public void oldToolResultsInsideOneRunAreCompressed() {
        List<Map<String, Object>> msgs = new ArrayList<>();
        for (int i = 0; i < 9; i++) msgs.add(ChatAgent.toolMsg("c" + i, Json.stringify(Map.of("ok", true, "output", "R" + i + "x".repeat(5000)))));
        ChatAgent.compactToolResults(msgs, 6, 1200);
        for (int i = 0; i < 3; i++) assertTrue(String.valueOf(msgs.get(i).get("content")).contains("较早的输出已压缩"));
        for (int i = 3; i < 9; i++) assertTrue(String.valueOf(msgs.get(i).get("content")).length() > 5000);
        assertEquals("c0", msgs.get(0).get("tool_call_id"));
    }
}
