package com.onesay.desktop;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/**
 * [fix-1123-c] 2026-11-22 远程预跑（REMOTE-RUN-1122）里的电脑端问题：
 * ① 手机交来的任务：只读检查（Test-Path）又要批一次、没人点 60 秒超时；一个任务要批好几次（规则：任务是最小单位，一次允许管到做完，危险 / 越界再问）；
 * ② 事做了（文件夹已建好），最后一步检查没批，整张卡写「没人批准」；③ 在手机上拒绝的，电脑写「电脑上拒绝了」；
 * ④ 对话记忆把任务进度 / 开关状态 / 启动方式当长期事实记下，后面的任务拿它说错话；记忆页要能一条条删。
 */
public class Fix1123cTest {

    @After public void reset() {
        PcGuard.LOCAL.set(Boolean.FALSE);
        PcGuard.TASK.set("");
        PcGuard.ANSWERED_ON_PHONE.set(Boolean.FALSE);
        SafeExec.REMOTE.set(Boolean.FALSE);
        SafeExec.reset(() -> false, null);
    }

    static final String FROM = "phone-3fe019";

    /** 手机任务里的一步（Agent.runAsPeer 的判定部分：远程、TASK = 任务卡 id）。 */
    static PcGuard.Outcome peer(PcGuard g, String task, String cmd, Map<String, Object> args) {
        PcGuard.LOCAL.set(Boolean.FALSE);
        PcGuard.TASK.set(task);
        try {
            return g.check(FROM, cmd, new TreeMap<>(args));
        } finally {
            PcGuard.TASK.set("");
        }
    }

    static PcGuard.Outcome peerSh(PcGuard g, String task, String command) {
        return peer(g, task, "sys_exec", Map.of("command", command));
    }

    static PcGuard guard(Approvals1118Test.Card card) throws Exception {
        Config c = Approvals1118Test.cfg();
        c.peers = FROM; // 允许这台手机下令
        return Approvals1118Test.guard(c, card);
    }

    static final String MK = "New-Item -ItemType Directory -Path \"$env:USERPROFILE\\Desktop\\周末\"";
    static final String CHECK = "Test-Path \"$env:USERPROFILE\\Desktop\\周末\"";

    // ------------------------------------------------------------------ ① 任务级审批

    @Test public void readOnlyCheckInPhoneTaskDoesNotAsk() throws Exception {
        Approvals1118Test.Card card = new Approvals1118Test.Card();
        PcGuard g = guard(card);
        for (String q : new String[] {CHECK, "Get-ChildItem \"$env:USERPROFILE\\Desktop\" | Select-Object Name", "Get-Item \"$env:USERPROFILE\\Desktop\\周末\""}) {
            PcGuard.Outcome o = peerSh(g, "task-a1", q);
            assertNull(q + " → " + (o.reply == null ? "" : o.reply.text), o.reply);
            assertNotNull(o.pass);
            assertEquals(LinkPolicy.Cat.QUERY, o.cat);
        }
        assertTrue("只读检查不弹卡：" + card.asked, card.asked.isEmpty());
        PcGuard.Outcome dirOnly = peerSh(g, "task-a1", "Test-Path \"$env:USERPROFILE\\Desktop\\周末\" -PathType Container");
        assertNull(dirOnly.reply);
        assertTrue(card.asked.isEmpty());
        // 读文件内容的不算：照旧问（读隐私，之后外发也按读过隐私判）
        card.answer = PcGuard.DENY;
        for (String q : new String[] {"Get-Content \"$env:USERPROFILE\\Desktop\\密码.txt\"", "cat C:\\Users\\me\\a.txt", "Select-String -Path C:\\a.txt -Pattern key"}) {
            int before = card.asked.size();
            PcGuard.Outcome o = peerSh(g, "task-a1", q);
            assertNotEquals(q, LinkPolicy.Cat.QUERY, o.cat);
            assertEquals(q, before + 1, card.asked.size());
        }
    }

    @Test public void directPhoneCommandUnchanged() throws Exception {
        // 不是手机任务（手机直接下的单条命令）：判定和以前一样，不因为这次修改变松
        Approvals1118Test.Card card = new Approvals1118Test.Card();
        PcGuard g = guard(card);
        PcGuard.Outcome o = peerSh(g, "", CHECK);
        assertNotEquals(LinkPolicy.Cat.QUERY, o.cat);
        assertEquals(1, card.asked.size());
        assertFalse("单条命令不给「允许本次任务」", card.taskOffered.get(0));
    }

    @Test public void readOnlyButSuspiciousStillAsks() throws Exception {
        // 只读也照样过安全插件 / 可疑写法：编码命令照旧拒，8.3 短名照旧问
        Approvals1118Test.Card card = new Approvals1118Test.Card();
        PcGuard g = guard(card);
        PcGuard.Outcome enc = peerSh(g, "task-a1", "powershell -EncodedCommand ZQBjAGgAbwAgAGgAaQA=");
        assertNotNull(enc.reply);
        assertFalse(enc.reply.ok);
        assertTrue(card.asked.isEmpty());
    }

    @Test public void oneApprovalCoversThePhoneTask() throws Exception {
        Approvals1118Test.Card card = new Approvals1118Test.Card();
        card.answer = PcGuard.TASK_ALLOW;
        PcGuard g = guard(card);
        PcGuard.Outcome a = peerSh(g, "task-a1", MK);
        assertNull(a.reply);
        assertEquals("第一步弹一次", 1, card.asked.size());
        assertTrue("手机任务的卡也给「允许本次任务」", card.taskOffered.get(0));
        // 同一任务后面的普通步骤：不再问
        PcGuard.Outcome b = peerSh(g, "task-a1", "New-Item -ItemType Directory -Path \"$env:USERPROFILE\\Desktop\\周日\"");
        assertNull(b.reply);
        PcGuard.Outcome w = peer(g, "task-a1", "file_write", Map.of("path", "C:\\Users\\me\\Desktop\\周末\\说明.txt", "text", "x"));
        assertNull(w.reply);
        assertEquals("一次允许管到任务做完：" + card.asked, 1, card.asked.size());
        boolean auto = false;
        for (Object o : g.permLogView()) if (String.valueOf(((Map<?, ?>) o).get("result")).equals("自动放行") && String.valueOf(((Map<?, ?>) o).get("detail")).contains("允许本次任务")) auto = true;
        assertTrue("权限记录里写「按允许本次任务自动放行」", auto);
    }

    @Test public void grantDoesNotCoverRiskyStepsOrOtherTasks() throws Exception {
        Approvals1118Test.Card card = new Approvals1118Test.Card();
        card.answer = PcGuard.TASK_ALLOW;
        PcGuard g = guard(card);
        peerSh(g, "task-a1", MK);
        assertEquals(1, card.asked.size());
        // 删除：任务批准不覆盖，每次都问，且不给「允许本次任务」
        card.answer = PcGuard.DENY;
        PcGuard.Outcome del = peerSh(g, "task-a1", "Remove-Item \"$env:USERPROFILE\\Desktop\\周末\" -Recurse");
        assertNotNull(del.reply);
        assertEquals(2, card.asked.size());
        assertFalse(card.taskOffered.get(1));
        // 改系统设置 / 关机：照旧问
        PcGuard.Outcome off = peer(g, "task-a1", "shutdown", Map.of());
        assertNotNull(off.reply);
        // 新任务：上一个任务的批准不带过来
        g.taskStarted("task-a3");
        card.answer = PcGuard.ALLOW;
        int before = card.asked.size();
        peerSh(g, "task-a3", "New-Item -ItemType Directory -Path \"$env:USERPROFILE\\Desktop\\周日\"");
        assertEquals(before + 1, card.asked.size());
        // 本机对话不受影响：同一个任务 id 的本机步骤走本机规则
        assertTrue(PcGuard.phoneTaskGrantable("sys_exec", Map.of("command", "mkdir x"), LinkPolicy.Cat.SHELL_EXEC,
                LinkPolicy.Decision.confirm("", "x"), new PcGuard.Outcome()));
    }

    @Test public void layeredConfirmNotCoveredByTaskGrant() {
        PcGuard.Outcome o = new PcGuard.Outcome();
        o.layered = new ActionPolicy.Decision(ActionPolicy.Verdict.CONFIRM, ActionPolicy.Layer.values()[0], "读过隐私后外发，先问你", false, false);
        assertFalse(PcGuard.phoneTaskGrantable("sys_exec", Map.of("command", "mkdir x"), LinkPolicy.Cat.SHELL_EXEC, LinkPolicy.Decision.confirm("", "x"), o));
        // 可疑写法：任务批准不覆盖
        assertFalse(PcGuard.phoneTaskGrantable("sys_exec", Map.of("command", "mkdir x"), LinkPolicy.Cat.SHELL_EXEC,
                LinkPolicy.Decision.confirm(LinkPolicy.R_SUSPECT, "x"), new PcGuard.Outcome()));
    }

    @Test public void phoneTaskNeedsRealClickForTaskScope() throws Exception {
        // 手机卡上的批准只算「允许一次」；「允许本次任务」只在电脑上点才算（ConfirmGate 的加固不放宽）
        PcGuard.Confirms c = new PcGuard.Confirms();
        AtomicReference<PcGuard.PhoneOffer> offer = new AtomicReference<>();
        c.phoneOffer = o -> { offer.set(o); return true; };
        AtomicLong now = new AtomicLong(1_000_000);
        c.clock = now::get;
        c.tickMs = 5;
        AtomicInteger res = new AtomicInteger(Integer.MIN_VALUE);
        PcGuard.TASK.set("task-x");
        Thread t = new Thread(() -> {
            PcGuard.TASK.set("task-x");
            res.set(c.ask(FROM, "sys_exec", "手机任务：新建文件夹", 60_000, false, "", true));
        });
        t.start();
        while (offer.get() == null) Thread.sleep(5);
        int id = offer.get().id;
        // 语音 / 远程：不算
        assertEquals(ConfirmGate.Answer.NOT_LOCAL, c.answerPhone(FROM, id, offer.get().token, true, false, false, "voice", 5000));
        // 太快：不算（0.7 秒防误点）
        assertEquals(ConfirmGate.Answer.TOO_EARLY, c.answerPhone(FROM, id, offer.get().token, true, false, false, "touch", 100));
        now.addAndGet(5000);
        assertEquals(ConfirmGate.Answer.ACCEPTED, c.answerPhone(FROM, id, offer.get().token, true, false, false, "touch", 5000));
        t.join(5000);
        assertEquals("手机上批准 = 允许一次", PcGuard.ALLOW, res.get());
    }

    // ------------------------------------------------------------------ ③ 在哪一端拒绝的

    @Test public void denyOnPhoneSaysPhone() throws Exception {
        PcGuard.Confirms c = new PcGuard.Confirms();
        AtomicReference<PcGuard.PhoneOffer> offer = new AtomicReference<>();
        c.phoneOffer = o -> { offer.set(o); return true; };
        c.tickMs = 5;
        Config cfg = Approvals1118Test.cfg();
        cfg.peers = FROM;
        PcGuard g = new PcGuard(cfg, c);
        AtomicReference<PcGuard.Outcome> out = new AtomicReference<>();
        Thread t = new Thread(() -> out.set(peerSh(g, "", "mkdir C:\\dual-test")));
        t.start();
        while (offer.get() == null) Thread.sleep(5);
        assertEquals(ConfirmGate.Answer.ACCEPTED, c.answerPhone(FROM, offer.get().id, offer.get().token, false, false, false, "touch", 2000));
        t.join(5000);
        assertNotNull(out.get().reply);
        assertEquals("你在手机上拒绝了", out.get().reply.text);
        assertEquals(LinkPolicy.R_CONFIRM_DENIED, out.get().reply.data.get("reason"));

        // 电脑上拒绝：照旧「电脑上拒绝了」
        offer.set(null);
        Thread t2 = new Thread(() -> out.set(peerSh(g, "", "mkdir C:\\dual-test2")));
        t2.start();
        while (offer.get() == null) Thread.sleep(5);
        assertEquals(ConfirmGate.Answer.ACCEPTED, c.answer(offer.get().id, false, false, ConfirmGate.Input.MOUSE));
        t2.join(5000);
        assertEquals("电脑上拒绝了", out.get().reply.text);
    }

    // ------------------------------------------------------------------ ② 事做了不报「没人批准」

    @Test public void doneThenCheckTimesOutIsWarnNotTimeout() {
        ChatAgent.Outcome o = ChatAgent.approvalStop(false, "确认文件夹已建好", true, new ArrayList<>(List.of("在桌面新建周末文件夹")), 1);
        assertEquals(Outcomes.WARN, o.status);
        assertTrue(o.text, o.text.startsWith("已经做好了：在桌面新建周末文件夹。"));
        assertTrue(o.text, o.text.contains("没核对"));
        assertFalse(o.text, o.text.contains("没执行"));
        // 交到状态判定：照原样（不是「没人批准」）
        Outcomes.TaskEnd te = Outcomes.judgeTask(o.status, o.text, List.of());
        assertEquals(Outcomes.WARN, te.state);
        assertEquals("完成（有警告）", Outcomes.zh(te.state));
    }

    @Test public void midTaskTimeoutSaysWhatWasDone() {
        ChatAgent.Outcome o = ChatAgent.approvalStop(false, "把文件拷到 D 盘", false, new ArrayList<>(List.of("在桌面新建周末文件夹")), 1);
        assertEquals("timeout", o.status);
        assertTrue(o.text, o.text.startsWith("已经做好了：在桌面新建周末文件夹。后面这一步没人批准"));
        ChatAgent.Outcome none = ChatAgent.approvalStop(false, "新建文件夹", false, new ArrayList<>(), 0);
        assertEquals("timeout", none.status);
        assertTrue(none.text.startsWith("这一步没人批准"));
        ChatAgent.Outcome den = ChatAgent.approvalStop(true, "新建文件夹", false, new ArrayList<>(List.of("查桌面")), 1);
        assertEquals("denied", den.status);
        assertTrue(den.text, den.text.startsWith("已经做好了：查桌面。后面你在确认框里拒绝了这一步"));
    }

    @Test public void agentLoopA1Replay() throws Exception {
        // A1 原样重放：第一步建文件夹（弹一次卡，点允许一次），第二步 Test-Path（以前又弹、没人点超时 → 整张卡「没人批准」）
        Approvals1118Test.Card card = new Approvals1118Test.Card();
        card.answer = PcGuard.ALLOW;
        PcGuard g = guard(card);
        List<Llm.Turn> turns = new ArrayList<>(List.of(
                Approvals1118Test.call("1", "run_command", Approvals1118Test.rc("在桌面新建周末文件夹", MK)),
                Approvals1118Test.call("2", "run_command", Approvals1118Test.rc("确认文件夹已建好", CHECK)),
                Approvals1118Test.say("【完成】\n桌面上的「周末」文件夹建好了。")));
        ChatAgent.Brain b = (m, tl) -> turns.remove(0);
        ChatAgent.Exec ex = (cmd, args) -> {
            PcGuard.Outcome o = peer(g, "task-a1", cmd, args);
            if (o.reply != null) return o.reply;
            assertTrue(o.pass.consume(cmd, o.args));
            return Actions.Result.ok("命令执行完了", new TreeMap<>(Map.of("output", "True", "exit", 0L)));
        };
        ChatAgent.Outcome out = new ChatAgent().run(new ArrayList<>(List.of(Llm.msg("system", "s"), Llm.msg("user", "在桌面建一个周末文件夹"))), b, ex, t -> { });
        assertEquals(out.text, "done", out.status);
        assertEquals("只弹一次：" + card.asked, 1, card.asked.size());

        // 就算检查那步真的没批下来（比如旧电脑端 / 不是只读）：卡上也不说「没人批准、没执行」盖掉已经做好的事
        Approvals1118Test.Card c2 = new Approvals1118Test.Card();
        c2.answer = PcGuard.ALLOW;
        PcGuard g2 = guard(c2);
        AtomicInteger n = new AtomicInteger();
        List<Llm.Turn> t2 = new ArrayList<>(List.of(
                Approvals1118Test.call("1", "run_command", Approvals1118Test.rc("在桌面新建周末文件夹", MK)),
                Approvals1118Test.call("2", "run_command", Approvals1118Test.rc("确认文件夹已建好", CHECK))));
        ChatAgent.Exec ex2 = (cmd, args) -> {
            if (n.incrementAndGet() == 2) return new Actions.Result(false, "没人确认", new TreeMap<>(Map.of("reason", LinkPolicy.R_CONFIRM_TIMEOUT)));
            PcGuard.Outcome o = peer(g2, "task-a1b", cmd, args);
            if (o.reply != null) return o.reply;
            assertTrue(o.pass.consume(cmd, o.args));
            return Actions.Result.ok("命令执行完了", new TreeMap<>(Map.of("output", "", "exit", 0L)));
        };
        ChatAgent.Outcome o2 = new ChatAgent().run(new ArrayList<>(List.of(Llm.msg("system", "s"), Llm.msg("user", "在桌面建一个周末文件夹"))), (m, tl) -> t2.remove(0), ex2, t -> { });
        assertEquals(o2.text, Outcomes.WARN, o2.status);
        assertTrue(o2.text, o2.text.startsWith("已经做好了：在桌面新建周末文件夹。"));
    }

    // ------------------------------------------------------------------ ④ 记忆

    static final String OLD = "- 用户叫刘泓江，用 Windows 电脑和 Note 8 手机\n- 偏好简短回答，先给结论";

    @Test public void transientLinesFromTheNightAreNotKept() {
        String[] bad = {
                "- 用户想买一台联想拯救者，预算一万，尚未确认是整机还是仅主机",
                "- 「周末」文件夹仍未建",
                "- 用户电脑上的 OneSay 以管理员身份运行",
                "- 已开启「按目标操作手机」权限",
                "- 这次任务没人批准，文件夹还没建好",
                "- 手机的「接受电脑指令」开关现在是开着的",
        };
        StringBuilder p = new StringBuilder(OLD);
        for (String b : bad) p.append('\n').append(b);
        p.append("\n- 用户的电脑叫 desktop-demo01");
        p.append("\n- 用户喜欢用百度地图导航");
        List<String> dropped = new ArrayList<>();
        String mem = MemoryRules.filterNew(OLD, p.toString(), dropped);
        assertEquals(dropped.toString(), bad.length, dropped.size());
        assertTrue(mem, mem.contains("用户叫刘泓江"));
        assertTrue(mem, mem.contains("偏好简短回答"));
        assertTrue(mem, mem.contains("desktop-demo01"));
        assertTrue(mem, mem.contains("百度地图"));
        for (String b : bad) assertFalse(b, mem.contains(MemoryRules.norm(b)));
    }

    @Test public void longTermFactsAreNotDropped() {
        String[] good = {"用户叫刘泓江", "偏好简短回答，先给结论", "用户在做开源项目 OneSay", "生日 10 月 18 日", "常用的打车软件是滴滴",
                "用户的手机是 Note 8，电脑是 Windows", "用户不吃香菜", "用户平日上班，周末集中测试设备"};
        for (String s : good) assertEquals(s, "", MemoryRules.transientWhy(s));
    }

    @Test public void existingLinesAreLeftForTheUserToDelete() {
        // 旧记忆里已经有的（哪怕像状态）不自动删：那是用户的数据，用户在记忆页删
        String old = "- 「周末」文件夹仍未建\n- 用户叫刘泓江";
        String mem = MemoryRules.filterNew(old, old, new ArrayList<>());
        assertTrue(mem.contains("仍未建"));
        String after = MemoryRules.deleteLine(mem, "- 「周末」文件夹仍未建");
        assertEquals("- 用户叫刘泓江", after);
        assertEquals("不带「- 」也能删", "- 用户叫刘泓江", MemoryRules.deleteLine(mem, "「周末」文件夹仍未建"));
        assertNull("不在了", MemoryRules.deleteLine(after, "不存在的一条"));
        assertNull(MemoryRules.deleteLine(after, "  "));
        assertEquals("模型写的「（空）」不收", "", MemoryRules.filterNew("", "（空）", new ArrayList<>()));
        assertEquals("", MemoryRules.filterNew("", "- 没有值得记的", new ArrayList<>()));
    }

    @Test public void onlyUserWordsFeedMemory() {
        assertTrue(MemoryRules.userLine(Map.of("role", "user", "text", "我叫刘泓江")));
        assertFalse(MemoryRules.userLine(Map.of("role", "assistant", "text", "之前那个周末还没建")));
        assertFalse(MemoryRules.userLine(Map.of("role", "assistant", "via", "task", "answer", "没人批准")));
        assertFalse(MemoryRules.userLine(Map.of("role", "user", "via", "cmd", "text", "锁屏")));
        String p = MemoryRules.prompt(1200);
        for (String w : new String[] {"只记用户自己明确说过", "还没做完", "开关", "管理员身份", "测试过程"}) assertTrue(w, p.contains(w));
    }

    @Test public void chatDeleteMemoryLine() throws Exception {
        Store s = new Store(Files.createTempFile("st", ".json"));
        s.setMemory("- 用户叫刘泓江\n- 「周末」文件夹仍未建", 1);
        Chat chat = new Chat(new Config(Files.createTempFile("c", ".properties")), s, null, List::of, x -> { });
        Actions.Result r = chat.deleteMemoryLine("- 「周末」文件夹仍未建");
        assertTrue(r.text, r.ok);
        assertEquals("- 用户叫刘泓江", s.memory().get("text"));
        assertTrue("改动时间更新（和手机同步时新者胜，删除会带过去）", ((Number) s.memory().get("updated_at")).longValue() > 1);
        assertFalse(chat.deleteMemoryLine("- 「周末」文件夹仍未建").ok);
    }

    @Test public void systemPromptSaysCheckStateNow() {
        String sp = ChatAgent.systemPrompt("2026-11-23 08:00", "C:\\ws", "C:\\Users\\me", "- 用户叫刘泓江");
        assertTrue(sp.contains("要用时现查"));
    }

    @Test public void memoryPageCanDelete() throws Exception {
        String html = new String(Files.readAllBytes(Paths.get("src/main/resources/onesay/ui/index.html")), StandardCharsets.UTF_8);
        assertTrue(html.contains("id=\"memlist\""));
        assertTrue(html.contains("api('memory_del',{line:l})"));
        assertTrue(html.contains("b.textContent='删掉'"));
        assertTrue("文字用 textContent，不拼 HTML", html.contains("t.textContent=l.replace("));
        String app = new String(Files.readAllBytes(Paths.get("src/main/java/com/onesay/desktop/App.java")), StandardCharsets.UTF_8);
        assertTrue(app.contains("ui.on(\"memory_del\""));
    }

    static final class AtomicLong extends java.util.concurrent.atomic.AtomicLong {
        AtomicLong(long v) { super(v); }
    }
}
