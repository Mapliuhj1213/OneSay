package com.onesay.assistant.v2.kernel.plugins;

import static org.junit.Assert.*;

import android.content.Context;

import com.onesay.assistant.v2.brief.BriefIsland;
import com.onesay.assistant.v2.brief.BriefRemote;
import com.onesay.assistant.v2.brief.BriefState;
import com.onesay.assistant.v2.jobs.JobQueue;
import com.onesay.assistant.v2.router.IOfflineProbe;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.wake.WakeWordStripper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * r2/p0 P0-1（modaudit M5-1）：L0 / canHandle 只做识别，不产生副作用。
 *
 * <p>同一句话在分诊、核心短语重试、唤醒热路径半句探针里会被多次调用 L0；起草邮件、入队、给简报发广播只能在面板派发
 * （执行阶段）做一次。
 */
public class L0PurityTest {

    static final class Counting implements CallbackPlugin.Effects {
        int mail, queue;
        final List<String> who = new ArrayList<String>();
        boolean ready = true;

        @Override public boolean mailReady(Context ctx) { return ready; }
        @Override public void beginMail(Context ctx, String raw, String w, String intent) { mail++; who.add(w + "|" + intent); }
        @Override public String queue(Context ctx, JobQueue.Parsed p) { queue++; return "排上了：" + p.command + "，马上开始。"; }
    }

    Counting fx;
    final List<String> sent = new ArrayList<String>();

    @Before public void setUp() {
        fx = new Counting();
        CallbackPlugin.sEffects = fx;
        BriefIsland.resetForTestPublic();
        BriefRemote.setSinkForTest(new BriefRemote.Sink() {
            @Override public boolean exec(Context ctx, String cmd) {
                sent.add(cmd);
                return true;
            }
        });
    }

    @After public void tearDown() {
        CallbackPlugin.sEffects = CallbackPlugin.REAL;
        BriefRemote.setSinkForTest(null);
        BriefIsland.resetForTestPublic();
    }

    // ------------------------------------------------------------------ 发邮件：识别多次，只起草一次

    @Test public void mailRecognizedManyTimesDraftedOnce() {
        CallbackPlugin cb = new CallbackPlugin();
        String s = "给张三发邮件说明天会议改到三点";
        Invocation last = null;
        for (int i = 0; i < 5; i++) {   // 分诊 + 核心短语重试 + 热路径探针 …
            last = cb.l0(null, s);
            assertNotNull(last);
            assertTrue(new ProbeOf(cb).canHandle(s));
        }
        assertEquals("识别阶段不起草", 0, fx.mail);
        assertEquals("ask.reply", last.sign);
        assertEquals("mail", last.args.optString(CallbackPlugin.EFFECT));

        assertTrue(CallbackPlugin.applyEffect(null, last));
        assertEquals("派发时起草一次", 1, fx.mail);
        assertEquals("张三|明天会议改到三点", fx.who.get(0));
        assertTrue(last.args.optString("text").contains("起草给张三"));

        assertTrue("同一个调用对象再派发一次（两条汇入路径 / 分诊复用）", CallbackPlugin.applyEffect(null, last));
        assertEquals("不重复起草", 1, fx.mail);

        // 新的一句（重新识别得到新对象）= 用户又说了一遍 → 再起草一份
        CallbackPlugin.applyEffect(null, cb.l0(null, s));
        assertEquals(2, fx.mail);
    }

    @Test public void mailNotConfiguredNoDraftAndExplains() {
        fx.ready = false;
        Invocation inv = new CallbackPlugin().l0(null, "给张三发邮件说测试一下");
        assertTrue(CallbackPlugin.applyEffect(null, inv));
        assertEquals(0, fx.mail);
        assertEquals(com.onesay.assistant.v2.callback.OutboundMailHooks.NO_MAILBOX, inv.args.optString("text"));
    }

    // ------------------------------------------------------------------ 队列：半句不入队，派发才入队

    @Test public void queueHalfSentenceNeverEnqueued() {
        CallbackPlugin cb = new CallbackPlugin();
        String full = "排队 打开蓝牙";
        for (int n = 1; n <= full.length(); n++) {   // 流式转写逐字长出来
            String partial = full.substring(0, n);
            cb.l0(null, partial);
            new ProbeOf(cb).canHandle(partial);
        }
        assertEquals("半句 / 整句识别都不入队", 0, fx.queue);
        Invocation inv = cb.l0(null, full);
        assertNotNull(inv);
        assertEquals(0, fx.queue);
        assertTrue(CallbackPlugin.applyEffect(null, inv));
        assertEquals(1, fx.queue);
        assertTrue(inv.args.optString("text").startsWith("排上了：打开蓝牙"));
        CallbackPlugin.applyEffect(null, inv);
        assertEquals("同一对象只入队一次", 1, fx.queue);
    }

    @Test public void wakeHotPathSplitProbeHasNoSideEffect() {
        CallbackPlugin cb = new CallbackPlugin();
        ProbeOf probe = new ProbeOf(cb);
        WakeWordStripper.splitWakeCommand("嗨比西比排队打开蓝牙", true, probe);
        WakeWordStripper.splitWakeCommand("嗨比西比给张三发邮件说明天", true, probe);
        WakeWordStripper.splitWakeCommand("嗨比西比给张三发邮件说明天开会", true, probe);
        assertTrue("探针确实调用了多次", probe.calls > 10);
        assertEquals(0, fx.mail);
        assertEquals(0, fx.queue);
    }

    @Test public void notOursUntouched() throws Exception {
        org.json.JSONObject a = new org.json.JSONObject();
        a.put("text", "x");
        assertFalse(CallbackPlugin.applyEffect(null, new Invocation("ask.reply", a, 1.0, Invocation.RouteSource.L0_RULE, "q")));
        assertFalse(CallbackPlugin.applyEffect(null, null));
        assertNull(new CallbackPlugin().l0(null, "打开蓝牙"));
        assertEquals(0, fx.mail + fx.queue);
    }

    // ------------------------------------------------------------------ 简报语音控制：识别不发广播

    @Test public void briefCommandRecognizedManyTimesSentOnce() {
        BriefIsland.apply(BriefState.parse(BriefState.ACTION, "PLAYING", "durationMs=60000"));
        BriefPlugin bp = new BriefPlugin();
        Invocation inv = null;
        for (int i = 0; i < 4; i++) inv = bp.l0(null, "跳过");
        assertNotNull(inv);
        assertTrue("识别阶段不发广播", sent.isEmpty());
        assertTrue(BriefPlugin.applyEffect(null, inv));
        assertEquals(1, sent.size());
        assertFalse("同一对象不重发", BriefPlugin.applyEffect(null, inv));
        assertEquals(1, sent.size());
    }

    @Test public void briefEndedBetweenRecognizeAndDispatchNoSend() {
        BriefIsland.apply(BriefState.parse(BriefState.ACTION, "PLAYING", "durationMs=60000"));
        Invocation inv = new BriefPlugin().l0(null, "跳过");
        assertNotNull(inv);
        BriefIsland.resetForTestPublic();
        assertFalse(BriefPlugin.applyEffect(null, inv));
        assertTrue(sent.isEmpty());
    }

    // ------------------------------------------------------------------ 源码扫描：所有 L0 实现里没有副作用调用

    static final String[] FORBIDDEN = {"begin(", "enqueue(", "Threads.start(", "new Thread(", "BriefRemote.send(", "markDetailRequested(",
            "handleQueue(", ".edit()", "sendBroadcast(", "startActivity(", "startService("};

    @Test public void noL0ImplementationHasSideEffects() throws Exception {
        List<File> files = new ArrayList<File>();
        File dir = new File(System.getProperty("bixby.root", "."), "app/src/com/onesay/assistant/v2");
        if (!dir.isDirectory()) dir = new File("app/src/com/onesay/assistant/v2");
        collect(dir, files);
        int bodies = 0;
        for (File f : files) {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            int from = 0;
            while (true) {
                int i = s.indexOf("Invocation l0(", from);
                if (i < 0) break;
                from = i + 1;
                int open = s.indexOf('{', i);
                int semi = s.indexOf(';', i);
                if (open < 0 || (semi >= 0 && semi < open)) continue;   // 声明 / 调用，不是方法体
                String body = body(s, open);
                bodies++;
                // 方法体里委托出去的 Hooks.l0 也要扫
                for (String bad : FORBIDDEN) {
                    assertFalse(f.getName() + " 的 l0 里不能有副作用调用 " + bad, body.contains(bad));
                }
            }
            // CallbackPlugin 的纯识别函数
            int r = s.indexOf("static com.onesay.assistant.v2.router.Invocation recognize(");
            if (r > 0) {
                String body = body(s, s.indexOf('{', r));
                bodies++;
                for (String bad : FORBIDDEN) assertFalse("recognize 里不能有 " + bad, body.contains(bad));
            }
        }
        assertTrue("至少扫到 Callback / Brief / 存图 / 摘要 四处 L0（实际 " + bodies + "）", bodies >= 5);
    }

    static String body(String s, int open) {
        int depth = 0;
        for (int k = open; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return s.substring(open, k + 1);
        }
        return s.substring(open);
    }

    static void collect(File d, List<File> out) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collect(f, out);
            else if (f.getName().endsWith(".java")) out.add(f);
        }
    }

    /** 唤醒热路径上的离线探针：只问 L0 能不能处理。 */
    static final class ProbeOf implements IOfflineProbe {
        final CallbackPlugin cb;
        int calls;

        ProbeOf(CallbackPlugin cb) { this.cb = cb; }

        @Override public boolean canHandle(String utterance) {
            calls++;
            return cb.l0(null, utterance) != null;
        }

        @Override public int literalLength(String utterance) { return utterance == null ? 0 : utterance.length(); }
    }
}
