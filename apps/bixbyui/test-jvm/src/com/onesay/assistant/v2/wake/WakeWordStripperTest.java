package com.onesay.assistant.v2.wake;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.router.CommandVocab;
import com.onesay.assistant.v2.router.IOfflineProbe;
import com.onesay.assistant.v2.router.L0RuleRouter;
import com.onesay.assistant.v2.router.PinyinUtil;

import java.util.regex.Pattern;

import org.junit.Test;

/**
 * WakeWordStripper（唤醒词剥离 / 一句式切分）+ 同组的几个纯函数：
 * PinyinUtil（经 ICU4J 替身）、CommandVocab、L0RuleRouter 的静态归一化与模板编译防护。
 */
public class WakeWordStripperTest {

    @Test public void stripLiteralWakePrefix() {
        // OneSay：不内置唤醒词，也不剥离厂商唤醒词（嗨比西比 / Bixby 原样保留）
        assertTrue(WakeWordStripper.strip("嗨比西比打开蓝牙").contains("比西比"));
        assertTrue(WakeWordStripper.strip("hey bixby 打开蓝牙").contains("bixby"));
        assertEquals("打开蓝牙", WakeWordStripper.strip("打开蓝牙"));   // 无唤醒词原样返回
    }

    @Test public void stripAckEcho() {
        assertTrue(WakeWordStripper.isAckSoundUtterance("我在。"));
        assertTrue(WakeWordStripper.isAckSoundUtterance("在呢"));
        assertFalse(WakeWordStripper.isAckSoundUtterance("打开蓝牙"));
        assertEquals("打开蓝牙", WakeWordStripper.stripAckPrefix("我在打开蓝牙"));
    }

    /** 10-23：结尾的应答语也剥掉（D1 离线 joke_orig_ack_50：「讲个笑话。我在。」）。 */
    @Test public void stripAckSuffix() {
        assertEquals("讲个笑话。", WakeWordStripper.stripAckEcho("讲个笑话。我在。"));
        assertEquals("讲个笑话。", WakeWordStripper.stripAckEcho("讲个笑话。我在"));
        assertEquals("查看最新的邮件，", WakeWordStripper.stripAckEcho("查看最新的邮件，在的。"));
        assertEquals("打开蓝牙", WakeWordStripper.stripAckEcho("打开蓝牙 我在呢"));
        assertEquals("打开蓝牙", WakeWordStripper.stripAckEcho("我在打开蓝牙"));          // 前缀照旧
        assertEquals("讲个笑话。", WakeWordStripper.stripAckEcho("我在讲个笑话。我在。")); // 前后都有
        assertEquals("讲个笑话。", WakeWordStripper.strip("讲个笑话。我在。"));            // strip() 也走新规则
        // 没有句子边界不剥：正常内容的结尾
        assertEquals("跟妈妈说我在", WakeWordStripper.stripAckEcho("跟妈妈说我在"));
        assertEquals("他现在在的", WakeWordStripper.stripAckEcho("他现在在的"));
        // 只收应答语音本身的词：句尾的「嗯 / 请讲 / 我是」不动
        assertEquals("好的，嗯", WakeWordStripper.stripAckEcho("好的，嗯"));
        assertEquals("猜猜，我是", WakeWordStripper.stripAckEcho("猜猜，我是"));
        assertEquals("打开蓝牙", WakeWordStripper.stripAckEcho("打开蓝牙"));
        assertEquals("", WakeWordStripper.stripAckEcho(null));
    }

    /** 整句只有应答语：保持 R14 行为（原样返回 → isAckSoundUtterance 当空 turn）。 */
    @Test public void ackOnlyKeepsR14Behaviour() {
        String[] only = {"我在", "我在。", "在的。", "在呢", "我在。我在。", "。我在。", "我在听请讲"};
        for (String u : only) {
            // 后缀规则对「只有应答语」不起作用：结果与只剥前缀（R14）相同
            assertEquals(u, WakeWordStripper.stripAckPrefix(u), WakeWordStripper.stripAckEcho(u));
            assertTrue(u, WakeWordStripper.isAckSoundUtterance(WakeWordStripper.stripAckEcho(u)));
        }
        // R14 原样：「我在。」剥前缀后剩「。」（空 turn），「我在」不剥（整句相等 → 空 turn）
        assertEquals("。", WakeWordStripper.stripAckEcho("我在。"));
        assertEquals("我在", WakeWordStripper.stripAckEcho("我在"));
    }

    @Test public void wakeWordDetection() {
        assertFalse(WakeWordStripper.isWakeText("嗨比西比"));
        assertFalse(WakeWordStripper.containsWakeWord("Hey Bixby"));
        assertFalse(WakeWordStripper.containsWakeWord("今天天气怎么样"));
    }

    /** 证明 ICU 替身生效：真机上 PinyinUtil 走 android.icu，JVM 上走 ICU4J。 */
    @Test public void pinyinViaIcuShim() {
        assertEquals("lanya", PinyinUtil.toPinyin("蓝牙"));
        assertFalse(WakeWordStripper.isWakeTextPy("嗨比西比"));
    }

    /** 一句式切分：用假探针模拟离线链路「能处理 打开蓝牙」。 */
    @Test public void splitOneShotCommand() {
        IOfflineProbe probe = new IOfflineProbe() {
            @Override public boolean canHandle(String u) { return u.startsWith("打开蓝牙"); }
            @Override public int literalLength(String u) { return canHandle(u) ? 4 : 0; }
        };
        assertEquals("刚唤醒：直接切出命令", "打开蓝牙", WakeWordStripper.splitWakeCommand("嗯打开蓝牙", true, probe));
        assertNull("非唤醒开头且未刚唤醒，不切分",
                WakeWordStripper.splitWakeCommand("请你打开蓝牙", false, probe));
    }

    @Test public void commandVocabPrefersCanonicalWords() {
        assertTrue(CommandVocab.canonicalHits("打开蓝牙") > CommandVocab.canonicalHits("打开烂牙"));
        assertEquals(0, CommandVocab.canonicalHits(null));
    }

    @Test public void l0NormalizeAndVariants() {
        assertEquals("打开蓝牙", L0RuleRouter.normalize("打开 蓝牙！"));
        assertEquals("调到{n}", L0RuleRouter.normalize("调到 {n}"));   // 模板保留占位符
        assertTrue(L0RuleRouter.generateSpokenVariants("请帮我打开一下蓝牙").contains("打开蓝牙"));
    }

    /** 2026-10-06 事故回归：未识别槽位 {subject} 不能让 Pattern.compile 抛异常炸掉整条 L0 路由。 */
    @Test public void l0UnknownSlotDoesNotBreakRegex() {
        String re = L0RuleRouter.debugBuildRegex("写邮件草稿{subject}");
        Pattern p = Pattern.compile(re);
        assertTrue(p.matcher("写邮件草稿{subject}").matches());
        assertFalse(p.matcher("写邮件草稿你好").matches());
    }
}
