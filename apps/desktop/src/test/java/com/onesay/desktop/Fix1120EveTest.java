package com.onesay.desktop;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/**
 * [fix-1120 晚] 电脑端预览版三项修复：
 * a. 对话里的提醒工具（电脑本机建 / 列 / 改 / 删，审批，到点弹通知，不建计划任务）；
 * b. 推测的日期不当事实（答复打回一次、提醒标「待确认」）；
 * c. 用户点「停止」：先中断、再不带工具收尾；再点一次立刻结束。
 */
public class Fix1120EveTest {

    static final ZoneId Z = ZoneId.of("Asia/Shanghai");
    static final long NOW = LocalDateTime.of(2026, 11, 20, 21, 0).atZone(Z).toInstant().toEpochMilli();

    static Llm.Turn call(String id, String name, String argsJson) {
        return new Llm.Turn(null, List.of(new Llm.ToolCall(id, name, argsJson)), new TreeMap<>(Llm.msg("assistant", "")));
    }

    static Llm.Turn say(String text) { return new Llm.Turn(text, List.of(), Llm.msg("assistant", text)); }

    static List<Map<String, Object>> ctx(String u) { return new ArrayList<>(List.of(Llm.msg("system", "s"), Llm.msg("user", u))); }

    static Object[] rig(boolean node) throws Exception {
        Path dir = Files.createTempDirectory("rem");
        AtomicLong clock = new AtomicLong(NOW);
        AlwaysOnService svc = new AlwaysOnService(dir.resolve("always-on.json"), clock::get, x -> { });
        svc.nodeConfigured = node;
        List<String> asked = new ArrayList<>();
        ChatReminders cr = new ChatReminders(svc, d -> { asked.add(d); return null; }, clock::get, Z);
        return new Object[] {svc, cr, asked, clock, dir};
    }

    static Map<String, Object> args(String json) { return ChatReminders.mapArgs(Json.obj(json)); }

    // ------------------------------------------------------------------ a. 提醒工具

    @Test public void toolIsRegisteredAndPromptSaysPhoneIsNotNeeded() {
        assertTrue(ChatAgent.toolNames().contains("reminder"));
        String p = ChatAgent.systemPrompt("2026-11-20 21:00 周五", "C:\\ws", "C:\\Users\\me", "");
        assertTrue(p.contains("手机没连不是不建提醒的理由"));
        assertTrue(p.contains("不要用 schtasks"));
        assertTrue("发到手机是另一件事", p.contains("把东西发到手机是另一件事"));
        Object[] m = ChatAgent.map(new Llm.ToolCall("1", "reminder", "{\"action\":\"add\",\"title\":\"体检前别吃东西\",\"at\":\"2026-11-23 20:00\",\"date_verified\":true,\"description\":\"建提醒\"}"));
        assertEquals("reminder", m[0]);
        assertNull("add 缺时间认不出", ChatAgent.map(new Llm.ToolCall("1", "reminder", "{\"action\":\"add\",\"title\":\"x\"}")));
        assertNull("delete 缺 id", ChatAgent.map(new Llm.ToolCall("1", "reminder", "{\"action\":\"delete\"}")));
        assertTrue(ChatAgent.describe("reminder", ChatReminders.mapArgs(Json.obj("{\"action\":\"add\",\"title\":\"体检\",\"at\":\"2026-11-23 20:00\"}"))).startsWith("建提醒「体检」"));
    }

    @Test public void addCreatesLocalReminderWithApprovalAndFiresNotification() throws Exception {
        Object[] r = rig(false);
        AlwaysOnService svc = (AlwaysOnService) r[0];
        ChatReminders cr = (ChatReminders) r[1];
        @SuppressWarnings("unchecked") List<String> asked = (List<String>) r[2];
        AtomicLong clock = (AtomicLong) r[3];
        Actions.Result res = cr.run(args("{\"action\":\"add\",\"title\":\"体检前一晚：20 点后别吃东西\",\"text\":\"明早空腹体检\",\"at\":\"2026-11-23 20:00\",\"date_verified\":true}"));
        assertTrue(res.text, res.ok);
        assertTrue(res.text.contains("2026-11-23（周一）20:00"));
        assertTrue(res.text.contains("不需要手机"));
        assertEquals(1, asked.size());
        assertTrue(asked.get(0).contains("建提醒「体检前一晚：20 点后别吃东西」"));
        AlwaysOn.Job j = svc.jobs().get(0);
        assertEquals(AlwaysOn.Where.LOCAL, j.where);
        assertEquals("chat", j.origin);
        assertFalse(j.unverified);
        // 存在预览版自己的数据文件里（重读还在）
        AlwaysOnService again = new AlwaysOnService(((Path) r[4]).resolve("always-on.json"), clock::get, x -> { });
        again.load();
        assertEquals("chat", again.jobs().get(0).origin);
        // 到点：本机计时弹通知（runner 收到这条提醒）
        List<String> fired = new ArrayList<>();
        svc.runner = (job, due, late) -> { fired.add(job.title); return new Object[] {AlwaysOn.St.DONE, "已提醒"}; };
        svc.tick();
        assertTrue(fired.isEmpty());
        clock.set(j.at + 5_000);
        svc.tick();
        assertEquals(List.of("体检前一晚：20 点后别吃东西"), fired);
        svc.tick();
        assertEquals("只提醒一次", 1, fired.size());
    }

    @Test public void listUpdateDelete() throws Exception {
        Object[] r = rig(false);
        AlwaysOnService svc = (AlwaysOnService) r[0];
        ChatReminders cr = (ChatReminders) r[1];
        @SuppressWarnings("unchecked") List<String> asked = (List<String>) r[2];
        cr.run(args("{\"action\":\"add\",\"title\":\"去南京的高铁\",\"at\":\"2026-11-22 05:30\",\"date_verified\":true}"));
        String id = svc.jobs().get(0).id;
        Actions.Result l = cr.run(args("{\"action\":\"list\"}"));
        assertTrue(l.ok);
        assertTrue(l.text.contains(id) && l.text.contains("2026-11-22（周日）05:30"));
        int before = asked.size();
        Actions.Result u = cr.run(args("{\"action\":\"update\",\"id\":\"" + id + "\",\"at\":\"2026-11-22 05:00\"}"));
        assertTrue(u.text, u.ok);
        assertEquals(before + 1, asked.size());
        assertEquals(LocalDateTime.of(2026, 11, 22, 5, 0).atZone(Z).toInstant().toEpochMilli(), svc.find(id).at);
        assertEquals("去南京的高铁", svc.find(id).title);
        Actions.Result d = cr.run(args("{\"action\":\"delete\",\"id\":\"" + id + "\"}"));
        assertTrue(d.ok);
        assertTrue(svc.jobs().isEmpty());
        assertFalse(cr.run(args("{\"action\":\"delete\",\"id\":\"" + id + "\"}")).ok);
        assertTrue(cr.run(args("{\"action\":\"list\"}")).text.contains("还没有提醒"));
    }

    @Test public void pastTimeAndBadFormatAreRejected() throws Exception {
        ChatReminders cr = (ChatReminders) rig(false)[1];
        Actions.Result past = cr.run(args("{\"action\":\"add\",\"title\":\"x\",\"at\":\"2026-11-15 20:00\",\"date_verified\":true}"));
        assertFalse(past.ok);
        assertTrue(past.text.contains("已经过了"));
        assertFalse(cr.run(args("{\"action\":\"add\",\"title\":\"x\",\"at\":\"下周二早上\",\"date_verified\":true}")).ok);
        assertEquals(LocalDateTime.of(2026, 11, 23, 20, 0).atZone(Z).toInstant().toEpochMilli(), cr.parseAt("2026年11月23日 20:00"));
        assertEquals(LocalDateTime.of(2026, 11, 23, 8, 0).atZone(Z).toInstant().toEpochMilli(), cr.parseAt("2026-11-23T08:00"));
    }

    @Test public void deniedApprovalCreatesNothing() throws Exception {
        Path dir = Files.createTempDirectory("rem2");
        AlwaysOnService svc = new AlwaysOnService(dir.resolve("a.json"), () -> NOW, x -> { });
        ChatReminders cr = new ChatReminders(svc, d -> new Actions.Result(false, "电脑上拒绝了", new TreeMap<>(Map.of("reason", LinkPolicy.R_CONFIRM_DENIED))), () -> NOW, Z);
        Actions.Result res = cr.run(args("{\"action\":\"add\",\"title\":\"x\",\"at\":\"2026-11-23 20:00\",\"date_verified\":true}"));
        assertFalse(res.ok);
        assertEquals(LinkPolicy.R_CONFIRM_DENIED, res.data.get("reason"));
        assertTrue(svc.jobs().isEmpty());
    }

    @Test public void phoneBackupNeedsNodeOtherwiseStaysLocal() throws Exception {
        ChatReminders off = (ChatReminders) rig(false)[1];
        Actions.Result a = off.run(args("{\"action\":\"add\",\"title\":\"x\",\"at\":\"2026-11-23 20:00\",\"date_verified\":true,\"phone_backup\":true}"));
        assertTrue(a.ok);
        assertEquals("local", a.data.get("where"));
        assertTrue(a.text.contains("配好中转"));
        Object[] r = rig(true);
        Actions.Result b = ((ChatReminders) r[1]).run(args("{\"action\":\"add\",\"title\":\"x\",\"at\":\"2026-11-23 20:00\",\"date_verified\":true,\"phone_backup\":true}"));
        assertEquals("node", b.data.get("where"));
        assertEquals(AlwaysOn.Where.NODE, ((AlwaysOnService) r[0]).jobs().get(0).where);
    }

    @Test public void confirmLowOffersTaskGrantOnce() throws Exception {
        Config cfg = AdvSupport.cfg(LinkPolicy.Mode.DEFAULT);
        AtomicInteger asks = new AtomicInteger();
        PcGuard g = new PcGuard(cfg, new PcGuard.Confirmer() {
            @Override public int ask(String from, String cmd, String detail, long t) { asks.incrementAndGet(); return PcGuard.ALLOW; }
            @Override public int ask(String from, String cmd, String detail, long t, boolean a, String rk, boolean offerTask) {
                asks.incrementAndGet();
                assertTrue("默认预设给「允许本次任务」", offerTask);
                return PcGuard.TASK_ALLOW;
            }
        });
        PcGuard.TASK.set("task-1");
        try {
            assertNull(g.confirmLow(ChatAgent.FROM, "reminder", "建提醒「a」"));
            assertNull(g.confirmLow(ChatAgent.FROM, "reminder", "建提醒「b」"));
            assertEquals("一次批准整个任务", 1, asks.get());
        } finally {
            PcGuard.TASK.set("");
        }
        PcGuard deny = new PcGuard(cfg, (f, c, d, t) -> PcGuard.DENY);
        Actions.Result r = deny.confirmLow(ChatAgent.FROM, "reminder", "建提醒「c」");
        assertEquals(LinkPolicy.R_CONFIRM_DENIED, r.data.get("reason"));
    }

    @Test public void schedTaskCommandGetsReminderHintOnce() {
        ChatAgent a = new ChatAgent();
        AtomicInteger n = new AtomicInteger();
        String cmd = "{\"command\":\"schtasks /Create /TN 体检提醒 /TR msg.exe /SC ONCE /ST 20:00\",\"description\":\"建提醒\"}";
        List<String> results = new ArrayList<>();
        ChatAgent.Brain b = (m, t) -> {
            Object last = m.get(m.size() - 1).get("content");
            if ("tool".equals(m.get(m.size() - 1).get("role"))) results.add(String.valueOf(last));
            int k = n.incrementAndGet();
            return k <= 2 ? call("c" + k, "run_command", cmd) : say("好了");
        };
        List<String> ran = new ArrayList<>();
        ChatAgent.Outcome o = a.run(ctx("提醒我"), b, (c, x) -> { ran.add(String.valueOf(x.get("command"))); return Actions.Result.ok("ok"); }, t -> { });
        assertEquals("done", o.status);
        assertEquals("提示一次后照常走审批执行", 1, ran.size());
        assertTrue(results.get(0).contains("use_reminder"));
    }

    @Test public void claimingDoneWithoutAnyStepIsNudged() {
        assertTrue(ChatAgent.claimsDone("已建好提醒。今天是 2026-11-20 星期五"));
        assertTrue(ChatAgent.claimsDone("我已经帮你设好了周一晚上 8 点的提醒"));
        assertTrue(ChatAgent.claimsDone("文件已发送到手机"));
        assertFalse("说用户的不算", ChatAgent.claimsDone("你已经安装了 VMware 16.2.3"));
        assertFalse(ChatAgent.claimsDone("要不要我帮你建一个前一晚的提醒？"));
        ChatAgent a = new ChatAgent();
        a.progressGuard = true;
        AtomicInteger n = new AtomicInteger();
        List<String> ran = new ArrayList<>();
        ChatAgent.Brain b = (m, t) -> {
            int k = n.incrementAndGet();
            if (k == 1) return say("已建好提醒，11 月 23 日 20:00 提醒你。");
            if (k == 2) { assertTrue(String.valueOf(m.get(m.size() - 1).get("content")).contains("一个工具都没调用")); return call("r", "reminder", "{\"action\":\"add\",\"title\":\"体检前别吃东西\",\"at\":\"2026-11-23 20:00\",\"date_verified\":true,\"description\":\"建提醒\"}"); }
            return say("已建好提醒（11-23 周一 20:00，在这台电脑上）。");
        };
        ChatAgent.Outcome o = a.run(ctx("下周二体检，提醒我前一晚别吃东西"), b, (c, x) -> { ran.add(c); return Actions.Result.ok("已建提醒"); }, t -> { });
        assertEquals("done", o.status);
        assertEquals(List.of("reminder"), ran);
        assertEquals(3, n.get());
    }

    // ------------------------------------------------------------------ b. 推测的日期

    @Test public void guessedDatesAreMarkedPending() throws Exception {
        Object[] r = rig(false);
        ChatReminders cr = (ChatReminders) r[1];
        AlwaysOnService svc = (AlwaysOnService) r[0];
        Actions.Result a = cr.run(args("{\"action\":\"add\",\"title\":\"马拉松领物\",\"at\":\"2026-11-27 09:00\",\"date_verified\":false}"));
        assertTrue(a.ok);
        assertTrue(a.text.contains("待确认"));
        AlwaysOn.Job j = svc.jobs().get(0);
        assertTrue(j.title.startsWith("【待确认】"));
        assertTrue(j.text.contains("核实"));
        assertTrue(j.unverified);
        // 没填 date_verified，但自己说了「往年」：也按推测
        cr.run(args("{\"action\":\"add\",\"title\":\"冰雪大世界开园（按往年估计）\",\"at\":\"2026-12-18 09:00\"}"));
        assertTrue(svc.jobs().get(1).title.startsWith("【待确认】"));
        // 已经写了「去核实」：不重复加说明
        String[] m = ChatReminders.markPending("去核实上马日期", "到上马官网核实比赛日");
        assertEquals("到上马官网核实比赛日", m[1]);
        // 核实过的不标
        cr.run(args("{\"action\":\"add\",\"title\":\"考研初试\",\"at\":\"2026-12-18 20:00\",\"date_verified\":true}"));
        assertFalse(svc.jobs().get(2).title.contains("待确认"));
    }

    @Test public void guessedDateAsFactDetection() {
        assertTrue(ChatAgent.guessedDateAsFact("官网没查到今年的日期，按往年估计比赛日是 11月29日（周日），前一天领物。"));
        assertTrue(ChatAgent.guessedDateAsFact("具体日期未公布。行程：11-28 到上海，11-29 比赛。"));
        assertFalse("标了待确认", ChatAgent.guessedDateAsFact("官网没查到今年的日期：比赛日待确认（往年在 11 月底或 12 月初）。"));
        assertFalse("查到了官方", ChatAgent.guessedDateAsFact("上马官网公布：2026年12月6日 7:00 鸣枪。"));
        assertFalse("没有具体日期", ChatAgent.guessedDateAsFact("没查到具体日期，往年一般在 12 月上旬。"));
    }

    @Test public void guessedDatePlanIsNudgedOnce() {
        ChatAgent a = new ChatAgent();
        a.progressGuard = true;
        AtomicInteger n = new AtomicInteger();
        List<String> lastUser = new ArrayList<>();
        ChatAgent.Brain b = (m, t) -> {
            int k = n.incrementAndGet();
            if (k == 1) return call("1", "run_command", "{\"command\":\"Invoke-WebRequest https://www.shmarathon.com -UseBasicParsing\",\"description\":\"查官网\"}");
            lastUser.add(String.valueOf(m.get(m.size() - 1).get("content")));
            if (k == 2) return say("【有警告】官网没查到今年的日期，按往年估计 11月29日 比赛，11月28日 领物。");
            return say("【有警告】官网没查到今年的日期：比赛日待确认（往年在 11 月底 / 12 月初），到上马官网或上马 App 核实。");
        };
        ChatAgent.Outcome o = a.run(ctx("帮我规划上马"), b, (c, x) -> Actions.Result.ok("<html>首页</html>"), t -> { });
        assertEquals("done", o.status);
        assertTrue(o.text.contains("待确认"));
        assertEquals(3, n.get());
        assertTrue(lastUser.get(1).contains("待确认"));
        // 打回只一次：第二次仍然没标也照样收工
        ChatAgent a2 = new ChatAgent();
        a2.progressGuard = true;
        AtomicInteger n2 = new AtomicInteger();
        ChatAgent.Brain b2 = (m, t) -> n2.incrementAndGet() == 1 ? call("1", "run_command", "{\"command\":\"Get-Date\",\"description\":\"看日期\"}") : say("没查到，按往年 11月29日。");
        assertEquals("done", a2.run(ctx("x"), b2, (c, x) -> Actions.Result.ok("ok"), t -> { }).status);
        assertEquals(3, n2.get());
    }

    // ------------------------------------------------------------------ c. 停止后收尾

    @Test public void stopInterruptsStepThenWrapsUpWithoutTools() {
        ChatAgent a = new ChatAgent();
        a.wrapUpOnStop = true;
        AtomicInteger n = new AtomicInteger();
        List<Boolean> toolsGiven = new ArrayList<>();
        List<String> wrapPrompt = new ArrayList<>();
        ChatAgent.Brain b = (m, t) -> {
            toolsGiven.add(!t.isEmpty());
            if (t.isEmpty()) { wrapPrompt.add(String.valueOf(m.get(m.size() - 1).get("content"))); return say("【有警告】已确认：上马官网写 12 月 6 日开跑。还缺：领物地点。说「继续」我接着查。"); }
            int k = n.incrementAndGet();
            return call("c" + k, "run_command", "{\"command\":\"Invoke-WebRequest https://x" + k + ".example.com -UseBasicParsing\",\"description\":\"查第 " + k + " 个站\"}");
        };
        List<String> ran = new ArrayList<>();
        ChatAgent.Outcome o = a.run(ctx("规划上马"), b, (c, x) -> {
            ran.add(String.valueOf(x.get("command")));
            if (ran.size() == 2) a.requestStop(); // 第二步执行中点了停止
            return Actions.Result.ok("12 月 6 日开跑");
        }, t -> { });
        assertEquals("stopped", o.status);
        assertEquals(2, ran.size());
        assertTrue(o.text.startsWith("已停止。做了 2 步"));
        assertTrue("保留查到的内容", o.text.contains("12 月 6 日开跑"));
        assertFalse(o.text.contains("【有警告】"));
        assertEquals(List.of(true, true, false), toolsGiven);
        assertTrue(wrapPrompt.get(0).contains("用户点了「停止」"));
    }

    @Test public void secondStopEndsImmediatelyWithoutWrapUp() throws Exception {
        ChatAgent a = new ChatAgent();
        a.wrapUpOnStop = true;
        CountDownLatch wrapStarted = new CountDownLatch(1);
        ChatAgent.Brain b = (m, t) -> {
            if (t.isEmpty()) { wrapStarted.countDown(); Thread.sleep(20_000); return say("太晚了"); }
            return call("c", "run_command", "{\"command\":\"Get-Date\",\"description\":\"看日期\"}");
        };
        new Thread(() -> {
            try {
                if (wrapStarted.await(10, TimeUnit.SECONDS)) { Thread.sleep(100); a.requestStop(); }
            } catch (InterruptedException ignored) { }
        }).start();
        long t0 = System.currentTimeMillis();
        ChatAgent.Outcome o = a.run(ctx("x"), b, (c, x) -> { a.requestStop(); return Actions.Result.ok("ok"); }, t -> { });
        assertTrue("收尾时再点停止就不等了", System.currentTimeMillis() - t0 < 5_000);
        assertEquals("stopped", o.status);
        assertEquals("已停止。做了 1 步，说「继续」可以接着做。", o.text);
    }

    @Test public void stopWhileWaitingForModelDoesNotWaitForIt() {
        ChatAgent a = new ChatAgent();
        a.wrapUpOnStop = true;
        AtomicInteger n = new AtomicInteger();
        ChatAgent.Brain b = (m, t) -> {
            if (t.isEmpty()) return say("已确认：查到一条结果。");
            if (n.incrementAndGet() == 1) return call("c", "run_command", "{\"command\":\"Get-Date\",\"description\":\"看日期\"}");
            Thread.sleep(20_000); // 模型很慢
            return say("太晚了");
        };
        new Thread(() -> { try { Thread.sleep(400); } catch (InterruptedException ignored) { } a.requestStop(); }).start();
        long t0 = System.currentTimeMillis();
        ChatAgent.Outcome o = a.run(ctx("x"), b, (c, x) -> Actions.Result.ok("一条结果"), t -> { });
        assertTrue(System.currentTimeMillis() - t0 < 5_000);
        assertEquals("stopped", o.status);
        assertTrue(o.text.contains("已确认：查到一条结果"));
    }

    @Test public void stopEndsApprovalWaitAtOnce() throws Exception {
        PcGuard.Confirms c = new PcGuard.Confirms();
        java.util.concurrent.atomic.AtomicBoolean tok = new java.util.concurrent.atomic.AtomicBoolean();
        Progress.TOKEN.set(tok);
        try {
            new Thread(() -> { try { Thread.sleep(300); } catch (InterruptedException ignored) { } tok.set(true); }).start();
            long t0 = System.currentTimeMillis();
            int r = c.ask(ChatAgent.FROM, "sys_exec", "x", 30_000);
            assertEquals(PcGuard.DENY, r);
            assertTrue(System.currentTimeMillis() - t0 < 5_000);
        } finally {
            Progress.TOKEN.remove();
        }
    }

    @Test public void requestStopEscalates() {
        ChatAgent a = new ChatAgent();
        a.requestStop();
        assertTrue(a.cancel.get());
        assertFalse(a.hardStop.get());
        a.requestStop();
        assertTrue(a.hardStop.get());
    }
}
