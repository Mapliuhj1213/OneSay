package com.onesay.assistant.v2.suggest;

import com.onesay.assistant.plug.suggest.core.Outcome;

import org.junit.Test;

import static org.junit.Assert.*;

/** ★ feat/suggest：岛放不下剪贴板 / 链接建议 → 退回旧剪贴板卡片的判定（只 host: / quiet:，频控去重等不退回，避免一次复制弹两次）。 */
public class ClipFallbackRuleTest {

    @Test public void hostAndQuietSuppressionFallBack() {
        assertTrue(ClipFallbackRule.wants("clip", Outcome.SUPPRESSED, "host:presenter"));
        assertTrue(ClipFallbackRule.wants("link", Outcome.SUPPRESSED, "host:busy"));
        assertTrue(ClipFallbackRule.wants("clip", Outcome.SUPPRESSED, "quiet:dnd"));
    }

    @Test public void rateLimitDedupAndOtherOutcomesDoNot() {
        assertFalse(ClipFallbackRule.wants("clip", Outcome.SUPPRESSED, "global_10min"));
        assertFalse(ClipFallbackRule.wants("clip", Outcome.SUPPRESSED, "dedup"));
        assertFalse(ClipFallbackRule.wants("clip", Outcome.SUPPRESSED, null));
        for (Outcome o : Outcome.values())
            if (o != Outcome.SUPPRESSED) assertFalse("超时 / 忽略 / 撤卡都不退回：" + o, ClipFallbackRule.wants("clip", o, "host:presenter"));
    }

    @Test public void otherScenesNeverFallBack() {
        assertFalse(ClipFallbackRule.wants("screenshot", Outcome.SUPPRESSED, "host:presenter"));
        assertFalse(ClipFallbackRule.wants("pickup", Outcome.SUPPRESSED, "host:presenter"));
        assertFalse(ClipFallbackRule.wants(null, Outcome.SUPPRESSED, "host:presenter"));
    }

    @Test public void tokenOnlyForClipScene() {
        assertEquals("7", ClipFallbackRule.token("clip", "7"));
        assertNull("链接卡的 token 是口令标记，退回时用最近一次芯片", ClipFallbackRule.token("link", "1"));
    }
}
