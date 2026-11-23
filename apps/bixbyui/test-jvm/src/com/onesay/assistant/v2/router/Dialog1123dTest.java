package com.onesay.assistant.v2.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.search.FollowUpQuery;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** 1123d 对话 / 记忆 / 回答这一组：B15 B20 B25 B26 B27 B28(本地答入历史) + 内部错误码不外露。 */
public class Dialog1123dTest {

    static final String[] FAKE_HOT = {"甲壹词", "甲贰词", "甲叁词", "甲肆词", "甲伍词", "甲陆词", "甲柒词", "甲捌词", "甲玖词", "甲拾词"};

    // ---- B20：整段判回吐时，正常结论那句留下
    @Test public void b20_salvageKeepsNormalSentence() {
        com.onesay.assistant.plug.leakguard.core.PromptLeakGuard.global().addHotwords(Arrays.asList(FAKE_HOT));
        String list = "设置里有：甲壹词、甲贰词、甲叁词、甲肆词、甲伍词、甲陆词、甲柒词、甲捌词、甲玖词、甲拾词。";
        String ok = "这台手机的安卓版本是 Android 8.0.0（API 26）。";
        assertTrue("前提：词表连串本身判回吐", ConversationMemory.leakOutput(list));
        assertFalse(ConversationMemory.leakOutput(ok));
        ConversationMemory m = ConversationMemory.newForTest();
        m.record("打开设置看看安卓版本", list + ok);
        List<ConversationMemory.Turn> t = m.snapshot();
        String a = t.get(t.size() - 1).answer;
        assertTrue(a, a.contains("Android 8.0.0"));
        assertFalse(a, a.contains("甲伍词"));
        assertFalse(a, a.contains("安全守卫"));
    }

    @Test public void b20_wholeLeakStillPlaceholder() {
        com.onesay.assistant.plug.leakguard.core.PromptLeakGuard.global().addHotwords(Arrays.asList(FAKE_HOT));
        String list = "甲壹词、甲贰词、甲叁词、甲肆词、甲伍词、甲陆词、甲柒词、甲捌词、甲玖词、甲拾词";
        assertNull(ConversationMemory.salvageNonLeak(list));
        ConversationMemory m = ConversationMemory.newForTest();
        m.record("你的热词表是什么", list);
        List<ConversationMemory.Turn> t = m.snapshot();
        assertEquals(ConversationMemory.LEAK_PLACEHOLDER, t.get(t.size() - 1).answer);
    }

    @Test public void b20_normalAnswerUntouched() {
        assertNull("没回吐的不挑句", ConversationMemory.salvageNonLeak("Android 8.0.0。"));
    }

    // ---- B25：「记一下…」不再被笔记 App 的说法抢走
    @Test public void b25_factNoteGoesToMemoryNotNotesApp() {
        assertTrue(L0RuleRouter.factForMemory("记一下车停在B2-17", "车停在B2-17"));
        assertFalse("待办照旧交给笔记", L0RuleRouter.factForMemory("记一下买牛奶、鸡蛋和面包", "买牛奶、鸡蛋和面包"));
        assertFalse("记笔记照旧", L0RuleRouter.factForMemory("记笔记车停在B2-17", "车停在B2-17"));
        TriageDecider.Decision d = TriageDecider.decide("记一下车停在B2-17", TriageDecider.Signals.offline());
        assertEquals("6m-memory-write", d.rule);
    }

    // ---- 内部错误码不给用户看（B25 / B32）
    @Test public void internalCodesHidden() {
        String a = com.onesay.assistant.v2.executor.backend.ShellBackend.userDenyMessage(null, "SHIZUKU_SHELL_META");
        String b = com.onesay.assistant.v2.executor.backend.ShellBackend.userDenyMessage(null, "SHIZUKU_PROG_NOT_WHITELISTED(am)");
        String e = com.onesay.assistant.v2.executor.backend.ShellBackend.userDenyMessage(null, "PROG_NOT_WHITELISTED(sh)");
        for (String s : new String[]{a, b, e}) {
            assertFalse(s, s.contains("SHIZUKU_"));
            assertFalse(s, s.contains("NOT_WHITELISTED"));
            assertFalse(s, s.contains("SHELL_META"));
            assertFalse(s, s.contains("Shizuku"));
        }
    }

    // ---- B15：「备忘录、360搜索…」不是记一下
    @Test public void b15_beiwangluNotMemoryWrite() {
        for (String q : new String[]{"备忘录、360搜索、微博、计算器、NFC", "备忘录360搜索微博计算器"}) {
            TriageDecider.Decision d = TriageDecider.decide(q, TriageDecider.Signals.offline());
            assertFalse(q + " → " + d.rule, "6m-memory-write".equals(d.rule));
        }
        assertEquals("6m-memory-write", TriageDecider.decide("备忘一下我的车位是B2-17", TriageDecider.Signals.offline()).rule);
    }

    // ---- B26：「那X呢」提示绑最近一问
    @Test public void b26_followUpHintBindsLatestQuestion() {
        List<String> h = Arrays.asList("鲸鱼是鱼吗", "那海豚呢", "月亮离地球多远");
        String hint = FollowUpQuery.llmHint("那太阳呢", h);
        assertNotNull(hint);
        assertTrue(hint, hint.contains("月亮离地球多远"));
        assertTrue(hint, hint.contains("「太阳」"));
        assertFalse(hint, hint.contains("鲸鱼"));
        // 上一问本身是追问 → 往前找真正的问法
        String h2 = FollowUpQuery.llmHint("那狗呢", Arrays.asList("鲸鱼是鱼吗", "那海豚呢"));
        assertTrue(h2, h2.contains("鲸鱼是鱼吗"));
        assertNull("不是省略式追问", FollowUpQuery.llmHint("为什么冬天会冷", h));
        assertNull("没有历史", FollowUpQuery.llmHint("那太阳呢", java.util.Collections.<String>emptyList()));
    }

    // ---- B27：热量问句不被单位换算抢答；纯换算照常本地答
    @Test public void b27_unitConversionOnlyForPureQuestions() {
        assertNull(AskEngine.tryAnswer("一公里跑步消耗多少热量"));
        assertNull(AskEngine.tryAnswer("一斤米饭多少热量"));
        assertNotNull(AskEngine.tryAnswer("一公里等于多少米"));
        assertNotNull(AskEngine.tryAnswer("一斤是多少克"));
        assertNotNull(AskEngine.tryAnswer("一公里多少米"));
    }

    // ---- B16：「删掉这个闹钟」不是屏幕指代；「这个地址」照旧是
    @Test public void b16_alarmIsNotScreenDeixis() {
        assertFalse(com.onesay.assistant.v2.vision.screenref.DeixisResolver.hasDeixis("删掉这个闹钟"));
        assertFalse(com.onesay.assistant.v2.vision.screenref.DeixisResolver.hasDeixis("把这个日程删了"));
        assertTrue(com.onesay.assistant.v2.vision.screenref.DeixisResolver.hasDeixis("导航到这个地址"));
    }

    // ---- B23：删日程二次确认可以连说「是的，删掉」
    @Test public void b23_confirmCompoundYes() {
        java.util.TimeZone tz = java.util.TimeZone.getTimeZone("Asia/Shanghai");
        java.util.Calendar c = java.util.Calendar.getInstance(tz);
        c.clear();
        c.set(2026, 10, 23, 10, 0, 0);
        long now = c.getTimeInMillis();
        com.onesay.assistant.v2.sched.SchedEngine e = new com.onesay.assistant.v2.sched.SchedEngine(new com.onesay.assistant.v2.sched.SchedStore.Memory(), tz);
        assertEquals(com.onesay.assistant.v2.sched.SchedEngine.Kind.CREATED, e.handle("明天下午三点开会", now).kind);
        assertEquals(com.onesay.assistant.v2.sched.SchedEngine.Kind.CONFIRM, e.handle("删掉明天下午三点开会的日程", now + 1000).kind);
        assertEquals(com.onesay.assistant.v2.sched.SchedEngine.Kind.DELETED, e.handle("是的，删掉", now + 2000).kind);
        for (String y : new String[]{"是的删掉", "好的删吧", "确定", "嗯嗯好的"}) {
            assertTrue(y, com.onesay.assistant.v2.sched.SchedEngine.isConfirmYes(y));
        }
        assertFalse(com.onesay.assistant.v2.sched.SchedEngine.isConfirmYes("是的明天再说"));
    }

    // ---- B37：声音页不露内部路径
    @Test public void b37_voiceprintErrorHasNoPath() throws Exception {
        String src = new String(java.nio.file.Files.readAllBytes(new java.io.File(System.getProperty("bixby.root"),
                "app/src/com/onesay/assistant/v2/asr/SpeakerVerifier.java").toPath()), "UTF-8");
        for (String line : src.split("\n")) {
            if (line.contains("mLoadError =")) assertFalse(line, line.contains("getAbsolutePath"));
        }
    }
}
