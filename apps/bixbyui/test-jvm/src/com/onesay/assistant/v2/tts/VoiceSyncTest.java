package com.onesay.assistant.v2.tts;

import com.onesay.assistant.v2.agent.Outcome;
import com.onesay.assistant.v2.island.IslandModel;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * ★ arch/voice-sync：场景 → 说不说 / 说哪句；应答与首个 GUI 动作的门控；8 秒只催一次；静音条件；结局与屏幕一致；接线守卫。
 */
public class VoiceSyncTest {

    // ------------------------------------------------------------------ 假的出声 / 定时器

    static final class FakeOut implements VoiceSyncSession.Out {
        final List<String> played = new ArrayList<String>();
        final List<String> captions = new ArrayList<String>();
        final List<Runnable> pendingDone = new ArrayList<Runnable>();
        String silence;
        boolean autoDone;

        @Override public synchronized boolean playCue(VoiceTemplate t, Runnable done) {
            played.add(t.text);
            if (autoDone) done.run();
            else pendingDone.add(done);
            return true;
        }

        @Override public String silenceReason() { return silence; }

        @Override public synchronized void caption(String text) { captions.add(text); }

        @Override public void log(String msg) {}

        synchronized void finishAll() {
            List<Runnable> l = new ArrayList<Runnable>(pendingDone);
            pendingDone.clear();
            for (Runnable r : l) r.run();
        }
    }

    static final class FakeSched implements VoiceSyncSession.Scheduler {
        static final class Job { Runnable r; long delay; boolean cancelled; }
        final List<Job> jobs = new ArrayList<Job>();

        @Override public synchronized Object schedule(Runnable r, long delayMs) {
            Job j = new Job();
            j.r = r;
            j.delay = delayMs;
            jobs.add(j);
            return j;
        }

        @Override public synchronized void cancel(Object token) {
            if (token instanceof Job) ((Job) token).cancelled = true;
        }

        /** 跑最近一个没取消的任务；返回它的延时（没有 → -1）。 */
        long fireLatest() {
            Job j = null;
            synchronized (this) {
                for (int i = jobs.size() - 1; i >= 0; i--) if (!jobs.get(i).cancelled) { j = jobs.get(i); break; }
                if (j != null) j.cancelled = true;
            }
            if (j == null) return -1;
            j.r.run();
            return j.delay;
        }

        synchronized int live() {
            int n = 0;
            for (Job j : jobs) if (!j.cancelled) n++;
            return n;
        }
    }

    // ------------------------------------------------------------------ 场景表（纯函数）

    @Test public void ackChoice() {
        assertEquals(VoiceTemplate.ACK_ON_IT, VoiceSyncPolicy.ack(VoiceSyncPolicy.AckWhy.ISLAND_NOW, 2));
        assertEquals(VoiceTemplate.ACK_MANY, VoiceSyncPolicy.ack(VoiceSyncPolicy.AckWhy.ISLAND_NOW, 3));
        assertEquals(VoiceTemplate.ACK_MANY, VoiceSyncPolicy.ack(VoiceSyncPolicy.AckWhy.SUBGOALS, 4));
        assertEquals(VoiceTemplate.ACK_ON_IT, VoiceSyncPolicy.ack(VoiceSyncPolicy.AckWhy.GUI, 1));
        assertEquals(VoiceTemplate.ACK_ON_IT, VoiceSyncPolicy.ack(VoiceSyncPolicy.AckWhy.SUBGOALS, 2));
        assertEquals("懒上岛任务第 2 次工具调用才拿到资格 → 简单「好的」", VoiceTemplate.ACK_OK, VoiceSyncPolicy.ack(VoiceSyncPolicy.AckWhy.STEPS, 1));
    }

    @Test public void estimateSteps() {
        assertEquals(1, VoiceSyncPolicy.estimateSteps("打开蓝牙"));
        assertEquals(2, VoiceSyncPolicy.estimateSteps("打开蓝牙然后把音量调到一半"));
        assertEquals(3, VoiceSyncPolicy.estimateSteps("关掉WiFi，打开飞行模式，再把亮度调低"));
        assertEquals(3, VoiceSyncPolicy.estimateSteps("查一下明天天气、定个七点闹钟，最后提醒我带伞"));
        assertEquals(1, VoiceSyncPolicy.estimateSteps(null));
        assertEquals(1, VoiceSyncPolicy.estimateSteps("  "));
    }

    @Test public void silenceConditions() {
        assertNull(VoiceSyncPolicy.silenceReason(true, false, false, false));
        assertNotNull("设置关了语音", VoiceSyncPolicy.silenceReason(false, false, false, false));
        assertNotNull("用户正在说话", VoiceSyncPolicy.silenceReason(true, true, false, false));
        assertNotNull("通话中", VoiceSyncPolicy.silenceReason(true, false, true, false));
        assertNotNull("媒体在播", VoiceSyncPolicy.silenceReason(true, false, false, true));
    }

    @Test public void outcomeMapping() {
        assertEquals(VoiceTemplate.EXCEPTION, VoiceSyncPolicy.outcome(true, null, null, false, false));
        assertEquals(VoiceTemplate.DONE, VoiceSyncPolicy.outcome(false, Outcome.DONE, null, false, false));
        assertEquals(VoiceTemplate.PLAN_ALL, VoiceSyncPolicy.outcome(false, Outcome.DONE, null, true, false));
        assertEquals(VoiceTemplate.UNVERIFIED, VoiceSyncPolicy.outcome(false, Outcome.DONE, null, true, true));
        assertEquals(VoiceTemplate.NEED_LOGIN, VoiceSyncPolicy.outcome(false, Outcome.NEEDS_USER, Outcome.Reason.LOGIN, false, false));
        assertEquals(VoiceTemplate.NEED_VERIFY, VoiceSyncPolicy.outcome(false, Outcome.NEEDS_USER, Outcome.Reason.CAPTCHA, false, false));
        assertEquals(VoiceTemplate.NEED_PAY, VoiceSyncPolicy.outcome(false, Outcome.NEEDS_USER, Outcome.Reason.PAYMENT, false, false));
        assertEquals(VoiceTemplate.CONFIRM_TIMEOUT, VoiceSyncPolicy.outcome(false, Outcome.NEEDS_USER, Outcome.Reason.NO_ANSWER, false, false));
        assertEquals(VoiceTemplate.NEED_INFO, VoiceSyncPolicy.outcome(false, Outcome.NEEDS_USER, Outcome.Reason.NEEDS_INFO, false, false));
        assertEquals(VoiceTemplate.NET_WEAK, VoiceSyncPolicy.outcome(false, Outcome.BLOCKED, Outcome.Reason.MODEL_UNAVAILABLE, false, false));
        assertEquals(VoiceTemplate.GATE, VoiceSyncPolicy.outcome(false, Outcome.BLOCKED, Outcome.Reason.POLICY, false, false));
        assertEquals(VoiceTemplate.UNSUPPORTED, VoiceSyncPolicy.outcome(false, Outcome.BLOCKED, Outcome.Reason.NO_TOOLS, false, false));
        assertEquals(VoiceTemplate.NO_KEY, VoiceSyncPolicy.outcome(false, Outcome.BLOCKED, Outcome.Reason.NO_KEY, false, false));
        assertEquals(VoiceTemplate.DEVICE_BUSY, VoiceSyncPolicy.outcome(false, Outcome.BLOCKED, Outcome.Reason.DEVICE_UNAVAILABLE, false, false));
        assertEquals(VoiceTemplate.ERROR, VoiceSyncPolicy.outcome(false, Outcome.BLOCKED, "blocked", false, false));
        assertEquals(VoiceTemplate.CANCELLED, VoiceSyncPolicy.outcome(false, Outcome.CANCELLED, Outcome.Reason.USER_STOPPED, false, false));
        assertEquals(VoiceTemplate.TIMEOUT, VoiceSyncPolicy.outcome(false, Outcome.NOT_DONE, Outcome.Reason.BUDGET, false, false));
        assertEquals(VoiceTemplate.STUCK, VoiceSyncPolicy.outcome(false, Outcome.NOT_DONE, Outcome.Reason.LOOP_GUARD, false, false));
        assertEquals(VoiceTemplate.EXCEPTION, VoiceSyncPolicy.outcome(false, Outcome.NOT_DONE, Outcome.Reason.ERROR, false, false));
        assertEquals(VoiceTemplate.ERROR, VoiceSyncPolicy.outcome(false, Outcome.NOT_DONE, Outcome.Reason.ROUTE_FAILED, false, false));
        assertEquals(VoiceTemplate.PLAN_PARTIAL, VoiceSyncPolicy.outcome(false, Outcome.NOT_DONE, Outcome.Reason.PARTIAL, false, false));
        assertEquals("没做成时不说「我点了…」", VoiceTemplate.TIMEOUT, VoiceSyncPolicy.outcome(false, Outcome.NOT_DONE, Outcome.Reason.BUDGET, false, true));
        // 每个结局都是回答类模板（走回答状态机：续听 / duck / 打断照旧）
        for (Outcome o : Outcome.values()) {
            for (String r : new String[]{null, Outcome.Reason.LOGIN, Outcome.Reason.BUDGET, Outcome.Reason.POLICY, "x"}) {
                assertTrue(o + "/" + r, VoiceSyncPolicy.outcome(false, o, r, false, false).isReply());
            }
        }
    }

    /** 屏幕与所播一致：状态行副文字以所播那句开头；成功类结局只配成功标题，失败类只配失败标题（OutcomeText）。 */
    @Test public void screenMatchesVoice() {
        assertEquals("都弄好了 · 共 3 步", VoiceSyncPolicy.statusSub(VoiceTemplate.PLAN_ALL, "共 3 步"));
        assertEquals("好了", VoiceSyncPolicy.statusSub(VoiceTemplate.DONE, ""));
        assertEquals("好了", VoiceSyncPolicy.statusSub(VoiceTemplate.DONE, "好了"));
        assertEquals("x", VoiceSyncPolicy.statusSub(null, "x"));
        java.util.Set<VoiceTemplate> okTpl = java.util.EnumSet.of(VoiceTemplate.DONE, VoiceTemplate.PLAN_ALL, VoiceTemplate.UNVERIFIED);
        for (Outcome o : Outcome.values()) {
            for (String r : new String[]{null, Outcome.Reason.LOGIN, Outcome.Reason.NO_ANSWER, Outcome.Reason.MODEL_UNAVAILABLE,
                    Outcome.Reason.POLICY, Outcome.Reason.BUDGET, Outcome.Reason.LOOP_GUARD}) {
                VoiceTemplate t = VoiceSyncPolicy.outcome(false, o, r, false, false);
                boolean okTitle = "执行完毕".equals(com.onesay.assistant.v2.agent.OutcomeText.title(o));
                assertEquals(o + "/" + r + " → " + t.text + " vs " + com.onesay.assistant.v2.agent.OutcomeText.title(o), okTitle, okTpl.contains(t));
            }
        }
        assertEquals(VoiceTemplate.PLAN_ALL, VoiceSyncPolicy.plan(true, false, true));
        assertEquals(VoiceTemplate.HANDOFF, VoiceSyncPolicy.plan(false, true, true));
        assertEquals(VoiceTemplate.HANDOFF, VoiceSyncPolicy.plan(false, true, false));
        assertEquals("有成有败 → 弄好了一部分", VoiceTemplate.PLAN_PARTIAL, VoiceSyncPolicy.plan(false, false, true));
        assertEquals("一步都没成不说「弄好了一部分」", VoiceTemplate.ERROR, VoiceSyncPolicy.plan(false, false, false));
    }

    /** ★ v3b：VOICE-SYNC.md §7 的 8 处暂代句全部换成补合成专句；屏幕状态行以同一句开头，成功 / 失败标题不错配。 */
    @Test public void v3bReplacesPlaceholders() {
        Object[][] cases = {
                {Outcome.NEEDS_USER, Outcome.Reason.NEEDS_INFO, VoiceTemplate.NEED_INFO, "还缺点信息，你补一句？"},
                {Outcome.NEEDS_USER, Outcome.Reason.LOGIN, VoiceTemplate.NEED_LOGIN, "得你先登录一下"},
                {Outcome.NEEDS_USER, Outcome.Reason.CAPTCHA, VoiceTemplate.NEED_VERIFY, "验证得你自己来"},
                {Outcome.NEEDS_USER, Outcome.Reason.PAYMENT, VoiceTemplate.NEED_PAY, "付款得你自己点"},
                {Outcome.BLOCKED, Outcome.Reason.NO_KEY, VoiceTemplate.NO_KEY, "这个还没配好，我用不了"},
                {Outcome.BLOCKED, Outcome.Reason.DEVICE_UNAVAILABLE, VoiceTemplate.DEVICE_BUSY, "手机这会儿腾不开手，等会儿再试"},
                {Outcome.NOT_DONE, Outcome.Reason.PARTIAL, VoiceTemplate.PLAN_PARTIAL, "弄好了一部分，剩下的没弄成"},
        };
        for (Object[] c : cases) {
            Outcome o = (Outcome) c[0];
            String r = (String) c[1];
            VoiceTemplate t = VoiceSyncPolicy.outcome(false, o, r, true, true);
            assertEquals(o + "/" + r, c[2], t);
            assertEquals(c[3], t.text);
            assertTrue("结局句走回答状态机：" + t, t.isReply());
            assertNotEquals("不再用暂代句：" + t, VoiceTemplate.ERROR, t);
            assertNotEquals("不再用暂代句：" + t, VoiceTemplate.HANDOFF, t);
            String detail = com.onesay.assistant.v2.agent.OutcomeText.line(o, r, "部分完成");
            String sub = VoiceSyncPolicy.statusSub(t, detail);
            assertTrue("状态行以所播那句开头：" + sub, sub.startsWith(t.text));
            assertNotEquals("失败类结局不配「执行完毕」", "执行完毕", com.onesay.assistant.v2.agent.OutcomeText.title(o));
        }
        assertEquals("你选一下", VoiceTemplate.ASK_PICK.text);
        assertFalse("ask_user 提问是一次性提示", VoiceTemplate.ASK_PICK.isReply());
        assertEquals("需要你确认", VoiceTemplate.CONFIRM.text);   // 确认卡 / 执行模式确认照旧
        assertEquals("多步计划部分完成（有成有败）", VoiceTemplate.PLAN_PARTIAL, VoiceSyncPolicy.plan(false, false, true));
        assertEquals("得你先登录一下 · 要你先登录，登录好再叫我",
                VoiceSyncPolicy.statusSub(VoiceSyncPolicy.outcome(false, Outcome.NEEDS_USER, Outcome.Reason.LOGIN, false, false),
                        com.onesay.assistant.v2.agent.OutcomeText.line(Outcome.NEEDS_USER, Outcome.Reason.LOGIN, null)));
    }

    @Test public void unverifiedWriteOnlyForWrites() {
        assertTrue(VoiceSyncPolicy.unverifiedWrite(true, false, false, false));
        assertFalse("回读确认了", VoiceSyncPolicy.unverifiedWrite(true, true, false, false));
        assertFalse("跳转界面看得见", VoiceSyncPolicy.unverifiedWrite(true, false, true, false));
        assertFalse("只读", VoiceSyncPolicy.unverifiedWrite(true, false, false, true));
        assertFalse("失败不算", VoiceSyncPolicy.unverifiedWrite(false, false, false, false));
    }

    @Test public void cancelPhraseAndMissCount() {
        for (String s : new String[]{"取消", "停止", "停", "算了", "别弄了", "不弄了", "取消吧", "停一下", "停下"}) {
            assertTrue(s, VoiceSyncPolicy.isCancelPhrase(s));
        }
        for (String s : new String[]{"取消闹钟", "停止播放音乐", "打开蓝牙", "", null, "算了吧帮我关灯"}) {
            assertFalse(String.valueOf(s), VoiceSyncPolicy.isCancelPhrase(s));
        }
        assertEquals(1, VoiceSyncPolicy.nextMissCount(0, 0, 1000));
        assertEquals(2, VoiceSyncPolicy.nextMissCount(1, 1000, 20_000));
        assertEquals(3, VoiceSyncPolicy.nextMissCount(2, 20_000, 30_000));
        assertEquals("超过 60 s 重新数", 1, VoiceSyncPolicy.nextMissCount(2, 20_000, 81_000));
    }

    // ------------------------------------------------------------------ 会话时序

    @Test public void singleStepLazyTaskNeverAcks() {
        FakeOut out = new FakeOut();
        FakeSched sch = new FakeSched();
        VoiceSyncSession s = new VoiceSyncSession(out, sch);
        s.start(false, 1);
        s.onStep();   // 一次工具调用就做完
        s.onSubgoals(1);
        s.end();
        assertTrue("单步不先应答：" + out.played, out.played.isEmpty());
        assertFalse(s.acked());
        assertEquals("收工后不再催", 0, sch.live());
        assertEquals(VoiceTemplate.DONE, s.outcomeTemplate(false, Outcome.DONE, null, 0));
    }

    @Test public void islandNowAcksImmediately() {
        FakeOut out = new FakeOut();
        VoiceSyncSession s = new VoiceSyncSession(out, new FakeSched());
        s.start(true, 2);
        assertEquals(java.util.Arrays.asList("收到，我来弄"), out.played);
        assertEquals("岛 / 面板同一句字幕", java.util.Arrays.asList("收到，我来弄"), out.captions);
        s.onStep();
        s.onStep();
        s.onSubgoals(4);
        assertEquals("只应答一次", 1, out.played.size());
    }

    @Test public void manyStepsSayOneByOne() {
        FakeOut out = new FakeOut();
        VoiceSyncSession s = new VoiceSyncSession(out, new FakeSched());
        s.start(true, 3);
        assertEquals("这个有点多，我一步步来", out.played.get(0));
        assertEquals("开局按多步应答 → 收尾「都弄好了」", VoiceTemplate.PLAN_ALL, s.outcomeTemplate(false, Outcome.DONE, null, 0));
    }

    @Test public void lazyTaskAcksWhenItEarnsTheIsland() {
        FakeOut out = new FakeOut();
        VoiceSyncSession s = new VoiceSyncSession(out, new FakeSched());
        s.start(false, 1);
        s.onStep();
        assertTrue(out.played.isEmpty());
        s.onStep();
        assertEquals(java.util.Arrays.asList("好的"), out.played);

        FakeOut o2 = new FakeOut();
        VoiceSyncSession s2 = new VoiceSyncSession(o2, new FakeSched());
        s2.start(false, 1);
        s2.onSubgoals(3);
        assertEquals("账本 ≥3 → 这个有点多", java.util.Arrays.asList("这个有点多，我一步步来"), o2.played);
        assertEquals(VoiceTemplate.PLAN_ALL, s2.outcomeTemplate(false, Outcome.DONE, null, 3));
    }

    @Test(timeout = 5000) public void firstGuiActionWaitsForAck() throws Exception {
        final FakeOut out = new FakeOut();
        final VoiceSyncSession s = new VoiceSyncSession(out, new FakeSched());
        s.start(false, 1);
        final long[] waited = {-1};
        Thread loop = new Thread(new Runnable() {
            @Override public void run() {
                waited[0] = s.beforeGui(3000);
            }
        });
        loop.start();
        Thread.sleep(250);
        assertTrue("GUI 前先应答", out.played.contains("收到，我来弄"));
        assertTrue("应答没播完，第一个 GUI 动作还在等", loop.isAlive());
        out.finishAll();   // 应答播完
        loop.join(1000);
        assertFalse(loop.isAlive());
        assertTrue("等了 " + waited[0], waited[0] >= 200 && waited[0] < 1500);
        assertEquals("之后的 GUI 动作直接放行", 0L, s.beforeGui(3000));
    }

    @Test(timeout = 5000) public void gateIsCapped() {
        FakeOut out = new FakeOut();   // 应答一直不报播完（播放卡住）
        VoiceSyncSession s = new VoiceSyncSession(out, new FakeSched());
        s.start(true, 2);
        long w = s.beforeGui(300);
        assertTrue("最多等上限 " + w, w >= 280 && w < 1500);
    }

    @Test(timeout = 2000) public void gateOpensImmediatelyWhenSilenced() {
        FakeOut out = new FakeOut();
        out.silence = "媒体在播";
        VoiceSyncSession s = new VoiceSyncSession(out, new FakeSched());
        s.start(true, 2);
        assertTrue("静音：不出声", out.played.isEmpty());
        assertEquals("静音：字幕照出", java.util.Arrays.asList("收到，我来弄"), out.captions);
        assertTrue(s.beforeGui(3000) < 200);
    }

    @Test(timeout = 2000) public void endReleasesGate() throws Exception {
        final FakeOut out = new FakeOut();
        final VoiceSyncSession s = new VoiceSyncSession(out, new FakeSched());
        s.start(true, 2);
        Thread loop = new Thread(new Runnable() {
            @Override public void run() {
                s.beforeGui(3000);
            }
        });
        loop.start();
        Thread.sleep(100);
        s.end();
        loop.join(1000);
        assertFalse("任务取消 / 收工 → 门控立即放开", loop.isAlive());
    }

    @Test public void nudgeOnlyOnce() {
        FakeOut out = new FakeOut();
        out.autoDone = true;
        FakeSched sch = new FakeSched();
        VoiceSyncSession s = new VoiceSyncSession(out, sch);
        s.start(true, 2);
        assertEquals(1, sch.live());
        assertEquals(VoiceSyncPolicy.STILL_WORKING_MS, sch.fireLatest());
        assertEquals(java.util.Arrays.asList("收到，我来弄", "还在弄，快好了"), out.played);
        assertTrue(s.nudged());
        s.onAskUser("选哪个？");
        s.onAskResolved();
        s.onConfirm(true);
        s.onConfirm(false);
        assertEquals("催过就不再排", 0, sch.live());
        assertEquals(-1, sch.fireLatest());
        assertEquals(1, java.util.Collections.frequency(out.played, "还在弄，快好了"));
    }

    @Test public void nudgeWaitsWhileAskingThenRestarts() {
        FakeOut out = new FakeOut();
        out.autoDone = true;
        FakeSched sch = new FakeSched();
        VoiceSyncSession s = new VoiceSyncSession(out, sch);
        s.start(false, 1);
        s.onAskUser("要哪个颜色？");
        assertEquals("提问提示 = 你选一下（v3b，原暂代「需要你确认」）", "你选一下", out.played.get(0));
        assertEquals("字幕是问题本身", "要哪个颜色？", out.captions.get(0));
        assertTrue("提问本身算开口，不再补应答", s.acked());
        sch.fireLatest();   // 8 s 到了但在等用户回答
        assertFalse(out.played.contains("还在弄，快好了"));
        s.onAskResolved();
        assertEquals("答完重新计 8 s", VoiceSyncPolicy.STILL_WORKING_MS, sch.fireLatest());
        assertTrue(out.played.contains("还在弄，快好了"));
    }

    @Test public void nudgeDefersWhileAckStillPlaying() {
        FakeOut out = new FakeOut();   // 应答不报播完
        FakeSched sch = new FakeSched();
        VoiceSyncSession s = new VoiceSyncSession(out, sch);
        s.start(true, 2);
        sch.fireLatest();
        assertFalse("应答还在播，不叠着催", out.played.contains("还在弄，快好了"));
        out.finishAll();
        assertEquals("往后挪 1 s", VoiceSyncPolicy.NUDGE_RETRY_MS, sch.fireLatest());
        assertTrue(out.played.contains("还在弄，快好了"));
    }

    @Test public void nudgeSkippedAfterEnd() {
        FakeOut out = new FakeOut();
        FakeSched sch = new FakeSched();
        VoiceSyncSession s = new VoiceSyncSession(out, sch);
        s.start(false, 1);
        s.end();
        assertEquals(-1, sch.fireLatest());
        assertTrue(out.played.isEmpty());
    }

    @Test public void silencedNudgeNoCannedCaption() {
        // ★ 11-04 午：催一次的字幕不再出「还在弄，快好了」套话 —— 岛 / 面板到 8 s 自己补真实状态（ProgressFeedback.stallLine）
        FakeOut out = new FakeOut();
        out.silence = "通话中";
        FakeSched sch = new FakeSched();
        VoiceSyncSession s = new VoiceSyncSession(out, sch);
        s.start(false, 1);
        sch.fireLatest();
        assertTrue(out.played.isEmpty());
        assertTrue("不出套话字幕", out.captions.isEmpty());
        assertTrue("静音也算催过（不会稍后再补一句）", s.nudged());
    }

    @Test public void unverifiedTracksLastWrite() {
        VoiceSyncSession s = new VoiceSyncSession(new FakeOut(), new FakeSched());
        s.start(true, 2);
        s.onAction(true, false, false, false);   // 改了设置但没回读
        s.onAction(true, false, false, true);    // 之后读了一下状态（只读不改结论）
        assertEquals(VoiceTemplate.UNVERIFIED, s.outcomeTemplate(false, Outcome.DONE, null, 0));
        s.onAction(true, true, false, false);    // 最后一个改动回读确认了
        assertEquals(VoiceTemplate.DONE, s.outcomeTemplate(false, Outcome.DONE, null, 0));
        s.onAction(true, false, true, false);    // 打开了界面
        assertEquals(VoiceTemplate.DONE, s.outcomeTemplate(false, Outcome.DONE, null, 0));
    }

    // ------------------------------------------------------------------ 岛字幕不算一步

    @Test public void islandCaptionIsNotAStep() {
        IslandModel m = new IslandModel();
        m.taskStarted(1000L, "打开蓝牙", false);
        m.caption("收到，我来弄");
        m.caption("还在弄，快好了");
        assertFalse("字幕不触发懒上岛", m.isIslandWorthy());
        m.step("正在：打开蓝牙");
        assertFalse(m.isIslandWorthy());
        m.step("正在：调音量");
        assertTrue("真正的第 2 步才上岛", m.isIslandWorthy());
    }

    // ------------------------------------------------------------------ 接线守卫

    private static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."), rel).toPath()), "UTF-8");
    }

    @Test public void wiring() throws Exception {
        String fss = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        int gate = fss.indexOf("com.onesay.assistant.v2.s1.S1Hooks.beforeGuiAction()");
        int exec = fss.indexOf("mExecutor.execute(cap, args, token, tainted)");
        assertTrue("GUI 门控在循环执行回调里、执行之前", gate > 0 && exec > gate && exec - gate < 400);
        assertTrue(fss.contains("ToolTiers.SIGN_GUI_OPERATE.equals(cap.sign)) com.onesay.assistant.v2.s1.S1Hooks.beforeGuiAction()"));
        assertTrue("结局选句在 showAskReply 之前、之后清掉",
                fss.indexOf("S1Hooks.agentOutcomeVoice(FloatingSheetService.this,\r\n                                false")
                        < fss.indexOf("mSheetView.showAskReply(\"任务结果\""));
        assertTrue(fss.contains("TtsSpeaker.clearReplyOverride();"));
        assertTrue("多步计划收尾选句", fss.contains("S1Hooks.planOutcomeVoice(FloatingSheetService.this, ok, didAbort, someOk)"));
        assertTrue("状态行与所播一致", fss.contains("VoiceSyncPolicy.statusSub(spoken,"));
        assertFalse("提问提示不再由面板单独播（VoiceSync 统一播一次）",
                fss.substring(fss.indexOf("private String askUserFromLoop"), fss.indexOf("cancelActiveAgent(UnifiedRouter.Cancel keep")).contains("playConfirmPrompt();"));
        assertTrue("FSS 仍是 CRLF", fss.contains("\r\n") && !fss.replace("\r\n", "").contains("\n"));

        String hooks = src("app/src/com/onesay/assistant/v2/s1/S1Hooks.java");
        assertTrue(hooks.contains("VoiceSync.taskStart(svc, command, islandNow)"));
        assertTrue(hooks.contains("VoiceSync.taskEnded()"));
        assertTrue(hooks.contains("vs.onAskUser(question)"));
        assertTrue(hooks.contains("vs.onStep()"));
        assertTrue(hooks.contains("vs.onConfirm(true)"));

        String ctl = src("app/src/com/onesay/assistant/v2/exec/ExecModeController.java");
        assertTrue("循环里的点界面收尾不单独说「好了」",
                ctl.contains("if (!S1Hooks.voiceSyncOwnsOutcome()) ExecVoice.play(mApp, ExecVoice.forResult(ok, state.stopped()))"));

        String tts = src("app/src/com/onesay/assistant/v2/ui/TtsSpeaker.java");
        String stopAll = tts.substring(tts.indexOf("public static void stopAll()"), tts.indexOf("public static boolean isSpeaking()"));
        assertTrue("打断（barge-in / ttsstop）照旧即停，含应答 / 催一次", stopAll.contains("cancelCueLocked()"));
        String tpl = tts.substring(tts.indexOf("private void playTemplateLocked"), tts.indexOf("private void playTemplateLocked") + 400);
        assertTrue("结果模板顶掉应答", tpl.contains("cancelCueLocked()"));
        String cue = tts.substring(tts.indexOf("public boolean playCue("), tts.indexOf("private void cancelCueLocked()"));
        assertFalse("提示不进回答状态机（不开续听）", cue.contains("fireStart") || cue.contains("fireDone"));

        String loop = src("app/src/com/onesay/assistant/v2/router/AgentLoop.java");
        assertFalse("AgentLoop 内部不改", loop.contains("voice-sync") || loop.contains("VoiceSync"));

        String wake = src("app/src/com/onesay/assistant/v2/wake/WakeService.java");
        assertTrue(wake.contains("fs.playVoiceCue(com.onesay.assistant.v2.s1.VoiceSync.nextMiss())"));
        assertTrue(wake.contains("fs.playVoiceCue(com.onesay.assistant.v2.tts.VoiceTemplate.EXIT)"));
    }
}
