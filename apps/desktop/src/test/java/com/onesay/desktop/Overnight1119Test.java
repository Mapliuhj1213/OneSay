package com.onesay.desktop;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/**
 * [overnight-1119] 用户 11-19 凌晨手测反馈（pctest-1119/NOTES.md）逐条的测试：
 * 1 审批不进通知 + 权限审批记录（按类型、按单次点击计数）；2 状态按目标判定；3 截图核对；4 不露内部能力名、区分开关没开 / 版本太旧；
 * 5 附件；6 按住说话的识别来源；10 目标不确定先弹选择卡；11 旁白 markdown。
 */
public class Overnight1119Test {

    @After public void reset() {
        PcGuard.LOCAL.set(Boolean.FALSE);
        PcGuard.TASK.set("");
        SafeExec.REMOTE.set(Boolean.FALSE);
        SafeExec.reset(() -> false, null);
    }

    static String html() throws Exception {
        return Files.readString(Path.of("src/main/resources/onesay/ui/index.html"), StandardCharsets.UTF_8);
    }

    static String src(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/com/onesay/desktop/" + name), StandardCharsets.UTF_8);
    }

    static Llm.Turn call(String id, String name, String argsJson) {
        return new Llm.Turn(null, List.of(new Llm.ToolCall(id, name, argsJson)), Llm.msg("assistant", ""));
    }

    static Llm.Turn callSay(String text, String id, String name, String argsJson) {
        return new Llm.Turn(text, List.of(new Llm.ToolCall(id, name, argsJson)), Llm.msg("assistant", text));
    }

    static Llm.Turn say(String text) { return new Llm.Turn(text, List.of(), Llm.msg("assistant", text)); }

    static Chat chat() throws Exception {
        Store s = new Store(Files.createTempDirectory("on").resolve("state.json"));
        Config c = new Config(Files.createTempFile("on", ".properties"));
        c.workspaceDir = Files.createTempDirectory("onws").toFile().getCanonicalPath();
        Chat ch = new Chat(c, s, new Main.TestActions(), List::of, x -> { });
        ch.workspace = c.workspaceDir;
        return ch;
    }

    // ================================================================== 1 审批不进通知 + 权限审批记录

    @Test public void approvalsNeverEnterNotifications() throws Exception {
        Store s = new Store(Files.createTempDirectory("on").resolve("state.json"));
        List<String> log = new ArrayList<>();
        Notify n = new Notify(s, log::add);
        assertNull("确认类不收", n.post("pc", "OneSay 要在这台电脑上执行操作，需要你确认", "回到窗口里点", ""));
        assertNull(n.post("pc", "OneSay 等你批准这一步", "", ""));
        assertNull(n.post("app", "手机请求，需要你确认", "", ""));
        assertNull(n.post("pc", "权限", "以后「执行命令」不用再问（可在设置页撤销）", ""));
        assertEquals(0, s.list("notes").size());
        assertNotNull("提醒照收", n.post("reminder", "喝水", "下午三点", ""));
        assertNotNull("自动任务结果照收", n.post("auto", "自动任务", "到家开灯：已执行", ""));
        assertNotNull("任务完成照收（正文里有「需要你确认」也收）", n.post("task", "任务需要你操作：打开微信", "需要你确认手机上的开关", ""));
        assertEquals(3, s.list("notes").size());
        assertEquals("任务完成", Notify.label("task"));
    }

    @Test public void oldApprovalNotesArePurgedOnStart() throws Exception {
        Store s = new Store(Files.createTempDirectory("on").resolve("state.json"));
        for (String t : new String[] {"OneSay 要在这台电脑上执行操作，需要你确认", "手机请求，需要你确认", "喝水"}) {
            Map<String, Object> m = new TreeMap<>();
            m.put("kind", "pc");
            m.put("title", t);
            m.put("text", "");
            s.add("notes", m, 0);
        }
        assertEquals(2, s.purgeApprovalNotes());
        assertEquals(1, s.list("notes").size());
        assertEquals("喝水", s.list("notes").get(0).get("title"));
        assertTrue("App 启动时清", src("App.java").contains("store.purgeApprovalNotes()"));
    }

    @Test public void appNoLongerFlashesTrayForConfirmations() throws Exception {
        String app = src("App.java");
        assertFalse("确认卡不弹托盘通知", app.contains("notify.flash("));
        assertFalse(src("Notify.java").contains("public void flash("));
        assertTrue(app.contains("agent.confirms.onNew = d -> trayHint("));
        assertFalse("白名单「要你确认」不进通知", app.contains("notify.post(\"pc\", \"应用白名单\""));
    }

    @Test public void eachClickCountsOnceByEventType() throws Exception {
        Approvals1118Test.Card card = new Approvals1118Test.Card();
        Config c = Approvals1118Test.cfg();
        PcGuard g = Approvals1118Test.guard(c, card);
        Map<String, Object> stats = new TreeMap<>();
        g.stats = (t, f) -> PermStats.bump(stats, t, f, System.currentTimeMillis());
        // 一个任务里：第一张卡点「允许一次」，第二张点「允许一次」，第三张点「拒绝」→ 三次点击三条计数
        card.answer = PcGuard.ALLOW;
        Approvals1118Test.sh(g, "t1", "winget install --id A.App");
        Approvals1118Test.sh(g, "t1", "winget install --id B.App");
        card.answer = PcGuard.DENY;
        Approvals1118Test.sh(g, "t1", "winget install --id C.App");
        List<Object> v = PermStats.view(stats);
        assertEquals(1, v.size());
        @SuppressWarnings("unchecked") Map<String, Object> row = (Map<String, Object>) v.get(0);
        assertEquals("安装程序 / App", row.get("type"));
        assertEquals(2L, row.get("allow"));
        assertEquals(1L, row.get("deny"));
        assertTrue(PermStats.line(row).contains("允许 2 次 / 拒绝 1 次"));
        // 「允许本次任务」点一次 = 一次；之后同任务自动放行的不算点击（另记）
        g.taskStarted("t2");
        card.answer = PcGuard.TASK_ALLOW;
        Approvals1118Test.sh(g, "t2", "winget install --id D.App");
        Approvals1118Test.sh(g, "t2", "winget install --id E.App");
        @SuppressWarnings("unchecked") Map<String, Object> r2 = (Map<String, Object>) PermStats.view(stats).get(0);
        assertEquals("「允许本次任务」点一次就是一次", 3L, r2.get("allow"));
        assertEquals(1L, r2.get("auto"));
        // 没人点：不算点击，另记
        g.taskStarted("t3");
        card.answer = PcGuard.TIMEOUT;
        Approvals1118Test.sh(g, "t3", "winget install --id F.App");
        @SuppressWarnings("unchecked") Map<String, Object> r3 = (Map<String, Object>) PermStats.view(stats).get(0);
        assertEquals(3L, r3.get("allow"));
        assertEquals(1L, r3.get("deny"));
        assertEquals(1L, r3.get("timeout"));
    }

    @Test public void eventTypesAreChineseWithoutInternalIds() {
        assertEquals("下载文件 / 联网", PermStats.eventType(ChatAgent.FROM, "sys_exec", Map.of("command", "Invoke-WebRequest -Uri https://x/y.apk -OutFile a.apk")));
        assertEquals("安装程序 / App", PermStats.eventType(ChatAgent.FROM, "sys_exec", Map.of("command", "& 'C:\\x\\adb.exe' -s 127.0.0.1:16384 install -r a.apk")));
        assertEquals("操作安卓设备（adb）", PermStats.eventType(ChatAgent.FROM, "sys_exec", Map.of("command", "adb shell su -c id")));
        assertEquals("删除文件", PermStats.eventType(ChatAgent.FROM, "sys_exec", Map.of("command", "Remove-Item D:\\a.txt")));
        assertEquals("写文件", PermStats.eventType(ChatAgent.FROM, "file_write", Map.of()));
        assertEquals("手机请求 · 打开网址", PermStats.eventType("phone-1", "open_url", Map.of()));
        assertEquals("其他操作", PermStats.eventType(ChatAgent.FROM, "weird_cmd", Map.of()));
        assertEquals(PermStats.ALLOW, PermStats.field(PcGuard.SESSION_ALLOW));
        assertEquals(PermStats.ALLOW, PermStats.field(PcGuard.ALWAYS));
        assertEquals(PermStats.DENY, PermStats.field(PcGuard.DENY));
    }

    @Test public void statsPersistInStateJson() throws Exception {
        Path f = Files.createTempDirectory("on").resolve("state.json");
        Store s = new Store(f);
        s.bumpPerm("写文件", PermStats.ALLOW);
        s.bumpPerm("写文件", PermStats.ALLOW);
        s.bumpPerm("写文件", PermStats.DENY);
        Store again = new Store(f);
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) again.permStats().get("写文件");
        assertEquals(2L, ((Number) m.get("allow")).longValue());
        assertEquals(1L, ((Number) m.get("deny")).longValue());
        again.clearPermStats();
        assertTrue(new Store(f).permStats().isEmpty());
    }

    @Test public void settingsHasPermissionRecordPage() throws Exception {
        String h = html();
        assertTrue(h.contains("['set-permlog','权限审批记录']"));
        assertTrue(h.contains("grp('set-permlog','权限审批记录'"));
        assertTrue(h.contains("允许 ${x.allow} 次"));
        assertTrue(h.contains("拒绝 ${x.deny} 次"));
        assertTrue("通知页说明不含审批", h.contains("审批不进通知"));
        assertTrue(src("App.java").contains("s.put(\"perm_stats\", PermStats.view(store.permStats()))"));
        assertFalse("旧提示「会弹通知提醒」去掉了", h.contains("会弹通知提醒"));
    }

    // ================================================================== 2 状态按目标判定

    @Test public void adbAlreadyConnectedIsSuccess() {
        Outcomes.Judged j = Outcomes.judgeExec("& 'C:\\MuMu\\shell\\adb.exe' connect 127.0.0.1:16384", 1, "already connected to 127.0.0.1:16384");
        assertEquals(Outcomes.Level.OK, j.level);
        assertTrue(j.note.contains("already connected"));
        assertEquals(Outcomes.Level.OK, Outcomes.judgeExec("adb connect 127.0.0.1:16384", 0, "connected to 127.0.0.1:16384").level);
        assertEquals(Outcomes.Level.FAIL, Outcomes.judgeExec("adb connect 127.0.0.1:16384", 1, "failed to connect to '127.0.0.1:16384': Connection refused").level);
        // 一步里多条：adb connect 回 1，后面 su 有输出
        Outcomes.Judged m = Outcomes.judgeExec("adb connect 127.0.0.1:16384; adb -s 127.0.0.1:16384 shell su -c id", 1, "already connected to 127.0.0.1:16384\nuid=0(root) gid=0(root) context=u:r:su:s0");
        assertEquals(Outcomes.Level.OK, m.level);
    }

    @Test public void multiCommandStepsJudgedByGoal() {
        assertEquals("中间报错、最后正常", Outcomes.Level.WARN, Outcomes.judgeExec("Get-Item nope; Get-Date", 1, "Get-Item : 找不到路径\n+ CategoryInfo : ObjectNotFound\n\n2026年11月19日 1:00:00").level);
        assertEquals("最后报错", Outcomes.Level.FAIL, Outcomes.judgeExec("Get-Date; Get-Item nope", 1, "2026年11月19日\nGet-Item : 找不到路径 nope\n+ FullyQualifiedErrorId : PathNotFound").level);
        assertEquals("单条非 0", Outcomes.Level.FAIL, Outcomes.judgeExec("Get-Item nope", 1, "找不到路径").level);
        assertEquals("退出 0 但有错误记录", Outcomes.Level.WARN, Outcomes.judgeExec("Get-Item a,b", 0, "Get-Item : 找不到\n+ CategoryInfo : ObjectNotFound\nMode LastWriteTime").level);
        assertEquals(Outcomes.Level.OK, Outcomes.judgeExec("robocopy a b", 1, "已复制 3 个文件").level);
        assertEquals(Outcomes.Level.WARN, Outcomes.judgeExec("New-Item -ItemType Directory x", 1, "An item with the specified name C:\\x already exists.").level);
    }

    @Test public void execResultCarriesWarnAndOk() {
        Map<String, Object> d = new TreeMap<>();
        Actions.Result r = PcGuard.judged("adb connect 127.0.0.1:16384", 1, "already connected to 127.0.0.1:16384", d);
        assertTrue("already connected 不是失败", r.ok);
        Map<String, Object> d2 = new TreeMap<>();
        Actions.Result w = PcGuard.judged("Get-Item nope; Get-Date", 1, "+ CategoryInfo : x\nok", d2);
        assertTrue(w.ok);
        assertNotNull(d2.get("warn"));
        assertFalse(PcGuard.judged("Get-Item nope", 1, "找不到", new TreeMap<>()).ok);
    }

    @Test public void blockedTaskIsNotGreenDone() {
        List<Object> steps = new ArrayList<>();
        steps.add(new TreeMap<>(Map.of("kind", "step", "state", "fail", "summary", "手机上没开「打开 App」")));
        // 截图 00:17：被挡下 → 不能是绿色完成
        Outcomes.TaskEnd e = Outcomes.judgeTask("done", "手机上「打开 App」开关没开，所以没打开微信。请你在手机上打开这一项再试。", steps);
        assertEquals(Outcomes.NEEDS_USER, e.state);
        assertEquals(Outcomes.FAILED, Outcomes.judgeTask("done", "没能装上，安装包下载失败了。", List.of()).state);
        assertEquals("标了结果就按标的", Outcomes.WARN, Outcomes.judgeTask("done", "【有警告】\n装好了，但截图没核对上。", List.of()).state);
        Outcomes.TaskEnd t = Outcomes.judgeTask("done", "【完成】\n已经装好并打开了。", List.of());
        assertEquals(Outcomes.DONE, t.state);
        assertEquals("标记去掉", "已经装好并打开了。", t.text);
        assertEquals(Outcomes.NEEDS_USER, Outcomes.judgeTask("done", "【需要你操作】请在手机上打开「按目标操作手机」。", List.of()).state);
        assertEquals(Outcomes.FAILED, Outcomes.judgeTask("done", "【没做成】没找到这个仓库。", List.of()).state);
        assertEquals("最后一步失败却说完成：有警告", Outcomes.WARN, Outcomes.judgeTask("done", "【完成】好了", steps).state);
        List<Object> warn = new ArrayList<>();
        warn.add(new TreeMap<>(Map.of("kind", "step", "state", "warn")));
        assertEquals(Outcomes.WARN, Outcomes.judgeTask("done", "已经装好了。", warn).state);
        assertEquals(Outcomes.DONE, Outcomes.judgeTask("done", "已经装好了。", List.of()).state);
        assertEquals("其他结束原因原样", "denied", Outcomes.judgeTask("denied", "x", List.of()).state);
        assertTrue(Outcomes.finished("warn"));
        assertFalse(Outcomes.finished("needs_user"));
    }

    @Test public void chatCardGetsJudgedStateAndTaskNotification() throws Exception {
        Chat ch = chat();
        ch.pcExec = (c, a) -> Actions.Result.ok("ok");
        ch.phones = () -> new TreeMap<>(Map.of("p1", "局域网"));
        ch.phone = (cmd, a) -> cmd.equals("open_app") ? new Actions.Result(false, "手机上没开「打开 App」（控制台 › 电脑联动 › 电脑可以让手机做的事）", null) : Actions.Result.ok("ok");
        AtomicInteger n = new AtomicInteger();
        ch.brain = (m, t) -> n.incrementAndGet() == 1 ? call("1", "phone_open_app", "{\"description\":\"在手机上打开微信\",\"name\":\"微信\"}")
                : say("【需要你操作】\n手机上的「打开 App」开关没开，微信已经打开不了。在手机上打开这一项后再让我做。");
        List<String> ended = new ArrayList<>();
        ch.onTaskEnd = (st, t) -> ended.add(st + "|" + t);
        Map<String, Object> card = ch.send("打开手机的微信并在文件传输助手发送一句你好", "text");
        assertEquals(Outcomes.NEEDS_USER, card.get("state"));
        String ans = String.valueOf(card.get("answer"));
        assertFalse("标记去掉", ans.contains("【"));
        assertFalse("病句改掉", ans.contains("已经打开不了"));
        assertEquals(1, ended.size());
        assertTrue(ended.get(0).startsWith("needs_user|"));
        @SuppressWarnings("unchecked") List<Object> steps = (List<Object>) card.get("steps");
        @SuppressWarnings("unchecked") Map<String, Object> last = (Map<String, Object>) steps.get(steps.size() - 1);
        assertEquals("fail", last.get("state"));
        assertTrue("下一步说清楚", String.valueOf(last.get("summary")).contains("下一步"));
    }

    @Test public void uiShowsWarnFailedNeedsUserColors() throws Exception {
        String h = html();
        assertTrue(h.contains("warn:['有警告','warnc']"));
        assertTrue(h.contains("failed:['没做成','fail']"));
        assertTrue(h.contains("needs_user:['需要你操作','wait']"));
        assertTrue(h.contains(".st.warnc{"));
        assertTrue(h.contains("x.state==='warn'?'<span class=\"ic warn\">!</span>'"));
        assertTrue("系统提示要求标结果", ChatAgent.systemPrompt("now", "ws", "home", "").contains("【需要你操作】"));
    }

    // ================================================================== 3 截图核对

    @Test public void ocrMismatchSaysWrongScreen() {
        ShotCheck.Result r = ShotCheck.fromOcr("su 的输出里有没有 uid=0", "武魂觉醒 立即下载 领取礼包 开始游戏");
        assertEquals(ShotCheck.Verdict.MISMATCH, r.verdict);
        assertTrue(ShotCheck.line(r).contains("截到的不是目标画面"));
        assertEquals(ShotCheck.Verdict.MATCH, ShotCheck.fromOcr("有没有 uid=0", "uid=0(root) gid=0(root) context=u:r:su:s0").verdict);
        assertEquals("像广告页", ShotCheck.Verdict.MISMATCH, ShotCheck.fromOcr("关闭 root 后的画面", "新服开启 首充 送 VIP").verdict);
        assertEquals(ShotCheck.Verdict.UNKNOWN, ShotCheck.fromOcr("看看现在屏幕上是什么", "设置 显示 声音").verdict);
        assertEquals(List.of("永久记住选择", "uid=0"), ShotCheck.expected("有没有「永久记住选择」和 uid=0"));
    }

    @Test public void visionVerdictParsed() {
        assertEquals(ShotCheck.Verdict.MATCH, ShotCheck.fromVision("终端窗口……\n核对：符合").verdict);
        ShotCheck.Result r = ShotCheck.fromVision("这是模拟器桌面，有一个游戏广告。\n核对：不符合（这是广告页，不是 su 的输出）");
        assertEquals(ShotCheck.Verdict.MISMATCH, r.verdict);
        assertTrue(r.why.contains("广告页"));
        assertEquals(ShotCheck.Verdict.UNKNOWN, ShotCheck.fromVision("看不清").verdict);
        assertTrue(ShotCheck.visionAsk("su 输出 uid=0").contains("核对：符合"));
    }

    @Test public void screenshotMismatchMarksStepYellowAndWarnsModel() throws Exception {
        Chat ch = chat();
        ch.shooter = t -> new Object[] {png(), "127.0.0.1:16384"};
        ch.looker = () -> null;
        ch.ocr = p -> "武魂觉醒 立即下载";
        Actions.Result r = ch.screenLook(new TreeMap<>(Map.of("target", "127.0.0.1:16384", "question", "su 输出里有没有 uid=0")));
        assertTrue(r.ok);
        assertTrue(r.text.contains("截到的不是目标画面"));
        assertEquals("no", r.data.get("verified"));
        assertNotNull(r.data.get("warn"));
        assertTrue(String.valueOf(r.data.get("output")).contains("不要拿这张图当证据"));
        // 看图模型说符合
        ch.looker = () -> (png, q) -> { assertTrue(q.contains("核对")); return "终端里是 uid=0(root)\n核对：符合"; };
        Actions.Result ok = ch.screenLook(new TreeMap<>(Map.of("target", "pc", "question", "有没有 uid=0")));
        assertEquals("yes", ok.data.get("verified"));
        assertNull(ok.data.get("warn"));
        // 没写 question：按这一步的说明核对
        Object[] m = ChatAgent.map(new Llm.ToolCall("1", "screenshot", "{\"description\":\"截图证明 root 已关闭\",\"target\":\"127.0.0.1:16384\"}"));
        @SuppressWarnings("unchecked") Map<String, Object> a = (Map<String, Object>) m[1];
        assertEquals("截图证明 root 已关闭", a.get("goal"));
    }

    static byte[] png() throws Exception {
        java.awt.image.BufferedImage im = new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB);
        return DeviceTools.png(im);
    }

    // ================================================================== 4 不露内部能力名

    @Test public void internalIdsBecomeChinese() {
        String t = Humanize.text("手机上开着的：ping、caps、find_phone、screen_on、volume_set、notify、status，phone_operate 会被手机拒绝");
        for (String id : new String[] {"ping", "caps", "find_phone", "screen_on", "volume_set", "notify", "status", "phone_operate"}) assertFalse(id + "：" + t, t.contains(id));
        assertTrue(t.contains("找手机（响铃）"));
        assertTrue(t.contains("按目标操作手机"));
        assertEquals("微信现在打不开", Humanize.text("微信已经打开不了"));
        assertEquals("命令行里的 status 不动", "git status", Humanize.text("git status"));
        assertEquals(List.of("查看电量和状态", "打开 App"), Humanize.phoneCaps(List.of("ping", "caps", "status", "open_app")));
    }

    @Test public void distinguishSwitchOffFromPhoneTooOld() throws Exception {
        assertEquals("off", Humanize.operateState(List.of("ping", "open_app"), List.of("open_app", "operate")));
        assertEquals("old", Humanize.operateState(List.of("ping", "open_app"), List.of("open_app", "status")));
        assertEquals("on", Humanize.operateState(List.of("operate"), List.of("operate")));
        assertTrue(Humanize.operateHint("off").contains("开关没开"));
        assertTrue(Humanize.operateHint("old").contains("版本太旧"));
        assertTrue(Humanize.phoneFailure("手机不支持「operate」", "operate").contains("版本太旧"));
        assertTrue(Humanize.phoneFailure("手机上没开「按目标操作手机」（控制台 › 电脑联动）", "operate").contains("开关没开"));
        Chat ch = chat();
        ch.phones = () -> new TreeMap<>(Map.of("p1", "局域网"));
        ch.phone = (cmd, a) -> {
            if (cmd.equals("status")) return Actions.Result.ok("ok", new TreeMap<>(Map.of("battery", 80L)));
            if (cmd.equals("caps")) return Actions.Result.ok("ok", new TreeMap<>(Map.of("caps", List.of("ping", "caps", "status", "find_phone", "open_app"), "known", List.of("status", "find_phone", "open_app"))));
            return new Actions.Result(false, "手机不支持「" + cmd + "」", null);
        };
        Actions.Result st = ch.phoneTool("phone_status", Map.of());
        assertTrue(st.text, st.text.contains("版本太旧"));
        for (String id : new String[] {"ping", "caps", "find_phone"}) assertFalse(st.text.contains(id));
        Actions.Result op = ch.phoneTool("phone_operate", Map.of("goal", "在微信文件传输助手发你好"));
        assertFalse(op.ok);
        assertTrue(op.text.contains("版本太旧"));
        assertFalse(op.text.contains("operate"));
    }

    // ================================================================== 10 目标不确定先弹选择卡

    @Test public void hedgedDownloadBlockedUntilUserPicks() throws Exception {
        List<Llm.Turn> turns = new ArrayList<>(List.of(
                call("1", "run_command", Approvals1118Test.rc("搜 GitHub 上的 Mika", "Invoke-RestMethod https://api.github.com/search/repositories?q=mika")),
                callSay("最可能是 wangzan101/Mika，直接下载。", "2", "run_command", Approvals1118Test.rc("下载 Mika 安装包", "Invoke-WebRequest -Uri https://github.com/wangzan101/Mika/releases/download/v1/mika.apk -OutFile C:\\Users\\me\\Downloads\\mika.apk")),
                call("3", "ask_choice", Json.stringify(Map.of("description", "请你选是哪个 Mika", "question", "你说的 Mika 是哪个？",
                        "options", List.of(Map.of("title", "wangzan101/Mika", "detail", "★1.2k · 安卓客户端 · v1.0"), Map.of("title", "other/mika", "detail", "★30 · 命令行"))))),
                call("4", "run_command", Approvals1118Test.rc("下载 Mika 安装包", "Invoke-WebRequest -Uri https://github.com/wangzan101/Mika/releases/download/v1/mika.apk -OutFile C:\\Users\\me\\Downloads\\mika.apk")),
                say("【完成】\n下载好了。")));
        int[] i = {0};
        List<String> ran = new ArrayList<>();
        List<String> picked = new ArrayList<>();
        ChatAgent.Exec ex = (cmd, a) -> {
            if (cmd.equals("ask_choice")) { picked.add(String.valueOf(a.get("question"))); return Actions.Result.ok("用户选了：wangzan101/Mika"); }
            ran.add(String.valueOf(a.get("command")));
            return Actions.Result.ok("命令执行完了");
        };
        List<Map<String, Object>> evs = new ArrayList<>();
        ChatAgent.Outcome o = new ChatAgent().run(new ArrayList<>(List.of(Llm.msg("system", "s"), Llm.msg("user", "github拉mika的安装包安装到mumu"))),
                (m, t) -> turns.get(Math.min(i[0]++, turns.size() - 1)), ex, new ChatAgent.Steps() {
                    @Override public void add(String text) { }
                    @Override public void step(Map<String, Object> ev) { evs.add(ev); }
                });
        assertEquals("done", o.status);
        assertEquals("只读搜索先做了；猜的下载被拦下；选了以后才下载", 2, ran.size());
        assertTrue(ran.get(0).startsWith("Invoke-RestMethod"));
        assertTrue(ran.get(1).startsWith("Invoke-WebRequest"));
        assertEquals(1, picked.size());
        assertTrue(evs.stream().anyMatch(e -> String.valueOf(e.get("desc")).contains("先列出候选请你选")));
    }

    @Test public void choiceOtherEndsAsNeedsUser() {
        List<Llm.Turn> turns = List.of(call("1", "ask_choice", Json.stringify(Map.of("description", "选", "question", "哪个？", "options", List.of(Map.of("title", "a/b"))))), say("x"));
        int[] i = {0};
        ChatAgent.Outcome o = new ChatAgent().run(new ArrayList<>(List.of(Llm.msg("user", "x"))), (m, t) -> turns.get(i[0]++),
                (cmd, a) -> new Actions.Result(false, "都不是", new TreeMap<>(Map.of("reason", "choice_other"))), t -> { });
        assertEquals(Outcomes.NEEDS_USER, o.status);
    }

    @Test public void choiceCardWaitsForClickAndOther() throws Exception {
        OpenTarget.Choices c = new OpenTarget.Choices();
        AtomicReference<String> got = new AtomicReference<>();
        Thread t = new Thread(() -> got.set(c.askOptions("t1", "哪个？", List.of(Map.of("title", "a"), Map.of("title", "b")))));
        t.start();
        while (c.list().isEmpty()) Thread.sleep(5);
        @SuppressWarnings("unchecked") Map<String, Object> info = (Map<String, Object>) c.list().get(0);
        assertEquals("pick", info.get("kind"));
        int id = ((Number) info.get("id")).intValue();
        assertFalse("越界不收", c.answer(id, "o5", false));
        assertFalse(c.answer(id, "app", false));
        assertTrue(c.answer(id, "o1", false));
        t.join(2000);
        assertEquals("o1", got.get());
        assertTrue(html().contains("都不是，我来补充"));
        assertTrue(ChatAgent.toolNames().contains("ask_choice"));
        assertNull("没有候选不收", ChatAgent.map(new Llm.ToolCall("1", "ask_choice", "{\"question\":\"x\",\"options\":[]}")));
    }

    // ================================================================== 11 旁白 markdown

    @Test public void narrationRendersMarkdown() throws Exception {
        String h = html();
        assertTrue(h.contains("function md(t){let h=esc("));
        assertTrue("旁白用 md", h.contains("<div class=\"note md\">${md(x.desc)}</div>"));
        assertTrue("答复用 md", h.contains("${md(x.answer)}"));
        assertTrue(ChatAgent.systemPrompt("now", "ws", "home", "").contains("不要用 markdown 符号"));
    }

    // ================================================================== 5 附件

    @Test public void attachmentsAreSavedAndGivenToModel() throws Exception {
        Chat ch = chat();
        ch.pcExec = (c, a) -> Actions.Result.ok("ok");
        ch.looker = () -> null;
        ch.ocr = p -> "错误 0x80070005 拒绝访问";
        Map<String, Object> a = ch.attach("屏幕截图.png", Base64.getEncoder().encodeToString(png()));
        assertEquals(Boolean.TRUE, a.get("ok"));
        assertEquals("image", a.get("kind"));
        String path = String.valueOf(a.get("path"));
        assertTrue(path.contains("inbox"));
        Map<String, Object> t = ch.attach("log.txt", Base64.getEncoder().encodeToString("line1\nERROR here".getBytes(StandardCharsets.UTF_8)));
        assertEquals("text", t.get("kind"));
        List<List<Map<String, Object>>> seen = new ArrayList<>();
        ch.brain = (m, tl) -> { seen.add(new ArrayList<>(m)); return say("【完成】\n看到了，是权限问题。"); };
        ch.send("这个报错怎么办", "text", List.of(path, String.valueOf(t.get("path")), "C:\\not\\attached.png"));
        String all = Json.stringify(seen.get(0));
        assertTrue("OCR 文字给了模型", all.contains("0x80070005"));
        assertTrue("文本内容给了模型", all.contains("ERROR here"));
        assertFalse("没登记的路径不收", all.contains("not\\\\attached"));
        // 对话记录里有图片，能取回来显示
        Map<String, Object> user = null;
        for (Map<String, Object> m : ch.store().list("chat")) if ("user".equals(m.get("role"))) user = m;
        assertNotNull(user.get("images"));
        assertEquals(Boolean.TRUE, ch.artifact(path).get("ok"));
        assertEquals(Boolean.FALSE, ch.attach("big.bin", Base64.getEncoder().encodeToString(new byte[Chat.ATTACH_MAX + 1])).get("ok"));
    }

    @Test public void composeSupportsPasteDropAndButton() throws Exception {
        String h = html();
        assertTrue(h.contains("ta.addEventListener('paste'"));
        assertTrue(h.contains("cp.addEventListener('drop'"));
        assertTrue(h.contains("id=\"attfile\""));
        assertTrue(h.contains("api('attach'"));
        assertTrue(h.contains("api('chat',{text:t,attachments:att})"));
        assertTrue(src("UiServer.java").contains("path.equals(\"/api/attach\")"));
    }

    // ================================================================== 6 按住说话

    @Test public void winrtIsThirdSourceAndNoneSaysFillKey() throws Exception {
        Config c = new Config(Files.createTempFile("on", ".properties"));
        AsrRoute r = new AsrRoute(c);
        r.localAvailable = () -> false;
        r.winrtAvailable = () -> true;
        assertEquals(AsrRoute.Engine.WINRT, r.choose());
        assertTrue(r.why.contains("Windows 语音识别"));
        assertTrue(r.anySource());
        r.winrtAvailable = () -> false;
        assertEquals(AsrRoute.Engine.NONE, r.choose());
        assertTrue(r.why.contains("填识别 Key"));
        assertFalse(r.anySource());
        assertTrue(Voice.cannotStart(AsrRoute.Pref.AUTO, false, false).contains("填识别 Key"));
        assertNull(Voice.cannotStart(AsrRoute.Pref.AUTO, false, true));
        assertTrue(WinAsr.errZh("privacy").contains("联机语音识别"));
        String ps = Files.readString(Path.of("src/main/resources/onesay/asr-winrt.ps1"), StandardCharsets.UTF_8);
        assertTrue(ps.contains("Windows.Media.SpeechRecognition.SpeechRecognizer"));
        assertTrue(ps.contains("'READY'"));
        assertFalse("不用任何 Key", ps.toLowerCase().contains("apikey"));
        String h = html();
        assertTrue(h.contains("按住空格说话现在用不了"));
        assertTrue(h.contains("去设置填识别 Key"));
    }

    // ================================================================== 14 下载进度条

    @Test public void progressLineAndIndeterminate() {
        Map<String, Object> v = Progress.view("下载 a.apk", 50L * 1048576, 100L * 1048576, 10_000);
        assertEquals(50L, v.get("percent"));
        assertEquals(5L * 1048576, v.get("speed"));
        assertEquals(10L, v.get("eta_sec"));
        assertTrue(String.valueOf(v.get("text")), String.valueOf(v.get("text")).matches("50%  50\\.0 MB / 100\\.0 MB · 5\\.0 MB/s · 剩 10 秒"));
        Map<String, Object> u = Progress.view("下载", 3L * 1048576, -1, 1000);
        assertEquals(-1L, u.get("percent"));
        assertTrue(String.valueOf(u.get("text")).contains("总大小未知"));
        // 节流：300 毫秒内只报一次，结束那次一定报
        List<Map<String, Object>> got = new ArrayList<>();
        long[] now = {0};
        Progress.Meter m = new Progress.Meter("x", 100, got::add, () -> now[0]);
        m.tick(1, false); now[0] = 100; m.tick(2, false); now[0] = 400; m.tick(3, false); m.tick(100, true);
        assertEquals(3, got.size());
    }

    @Test public void downloaderReportsProgressAndCanCancel() throws Exception {
        byte[] data = new byte[700_000];
        new java.util.Random(1).nextBytes(data);
        Downloader.Net old = Downloader.net;
        try {
            Downloader.net = (url, t) -> new Object[] {200, (long) data.length, new java.io.ByteArrayInputStream(data)};
            List<Map<String, Object>> prog = new ArrayList<>();
            Progress.SINK.set(prog::add);
            Path dest = Files.createTempDirectory("dl").resolve("a.apk");
            Actions.Result r = Downloader.download("https://example.com/a.apk", dest, 60);
            assertTrue(r.text, r.ok);
            assertArrayEquals(data, Files.readAllBytes(dest));
            assertEquals(FileXfer.hex(java.security.MessageDigest.getInstance("SHA-256").digest(data)), r.data.get("sha256"));
            assertEquals(100L, prog.get(prog.size() - 1).get("percent"));
            // 总大小未知
            Downloader.net = (url, t) -> new Object[] {200, -1L, new java.io.ByteArrayInputStream(data)};
            prog.clear();
            assertTrue(Downloader.download("https://example.com/b.apk", dest.resolveSibling("b.apk"), 60).ok);
            assertEquals(-1L, prog.get(0).get("percent"));
            // 取消
            Downloader.net = (url, t) -> new Object[] {200, (long) data.length, new java.io.InputStream() {
                int n;
                @Override public int read() { return -1; }
                @Override public int read(byte[] b, int off, int len) { if (++n == 3) Progress.CANCEL.set(true); return n > 50 ? -1 : Math.min(len, 1000); }
            }};
            Actions.Result c = Downloader.download("https://example.com/c.apk", dest.resolveSibling("c.apk"), 60);
            assertFalse(c.ok);
            assertTrue(c.text.contains("已取消"));
            assertFalse("取消后不留半截文件", Files.exists(dest.resolveSibling("c.apk.part")));
            Downloader.net = (url, t) -> new Object[] {404, -1L, new java.io.ByteArrayInputStream(new byte[0])};
            assertTrue(Downloader.download("https://example.com/d", dest.resolveSibling("d"), 60).text.contains("404"));
            assertFalse(Downloader.download("file:///etc/passwd", dest.resolveSibling("e"), 60).ok);
        } finally {
            Downloader.net = old;
            Progress.SINK.remove();
            Progress.CANCEL.set(false);
        }
    }

    @Test public void downloadToolGoesThroughSameApprovalAsInvokeWebRequest() throws Exception {
        Object[] m = ChatAgent.map(new Llm.ToolCall("1", "download", "{\"description\":\"下载\",\"url\":\"https://github.com/a/b/releases/download/v1/x.apk\",\"path\":\"C:\\\\Users\\\\me\\\\Downloads\\\\x.apk\"}"));
        assertEquals("net_download", m[0]);
        assertTrue(ChatAgent.sideEffect("net_download", Map.of()));
        assertEquals("Invoke-WebRequest -Uri 'https://x/y' -OutFile 'C:\\a''b.apk'", Downloader.equivalentCommand("https://x/y", "C:\\a'b.apk"));
        assertTrue(src("Agent.java").contains("if (\"net_download\".equals(cmd)) return netDownload(ChatAgent.FROM, args);"));
        String h = html();
        assertTrue(h.contains("function progHtml(p)"));
        assertTrue("不定进度", h.contains("pbar ${ind?'ind':''}"));
        assertTrue("可取消", h.contains("api('dl_cancel'"));
        assertTrue(ChatAgent.systemPrompt("n", "w", "h", "").contains("下载文件用 download 工具"));
    }
}
