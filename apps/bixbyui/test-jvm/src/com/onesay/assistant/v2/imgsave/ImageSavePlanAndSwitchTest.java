package com.onesay.assistant.v2.imgsave;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.router.TriageDecider;

import java.util.Arrays;

import org.junit.Test;

/** [imgsave] 存哪几张的决定、回执文案、文件名；开关 / kill-switch / 缓存子开关；分诊开关。 */
public class ImageSavePlanAndSwitchTest {

    static ImageSaveIntent.Hit hit(String q) {
        return ImageSaveIntent.match(q);
    }

    @Test public void plan() {
        assertEquals(ImageSavePlan.Kind.SAVE, ImageSavePlan.decide(hit("保存图片"), 1).kind);
        assertEquals(Arrays.asList(1), ImageSavePlan.decide(hit("保存图片"), 1).picks);
        assertEquals(ImageSavePlan.Kind.CHOOSE, ImageSavePlan.decide(hit("保存图片"), 3).kind);
        assertEquals(Arrays.asList(1, 2, 3), ImageSavePlan.decide(hit("全部保存"), 3).picks);
        assertEquals(Arrays.asList(2), ImageSavePlan.decide(hit("保存第二张图"), 3).picks);
        assertEquals(ImageSavePlan.Kind.OUT_OF_RANGE, ImageSavePlan.decide(hit("保存第五张图"), 3).kind);
        assertEquals(ImageSavePlan.Kind.MANUAL, ImageSavePlan.decide(hit("保存图片"), 0).kind);
    }

    @Test public void summaryAndNames() {
        ImageSavePlan.Tier O = ImageSavePlan.Tier.ORIGINAL, C = ImageSavePlan.Tier.CROP;
        assertEquals("已保存 1 张（原图）", ImageSavePlan.summary(Arrays.asList(O)));
        assertEquals("已保存 2 张（截图裁剪）", ImageSavePlan.summary(Arrays.asList(C, C)));
        assertEquals("已保存 3 张（1 张原图、2 张截图裁剪）", ImageSavePlan.summary(Arrays.asList(C, O, C)));
        assertEquals("没有保存任何图片", ImageSavePlan.summary(Arrays.<ImageSavePlan.Tier>asList()));
        assertEquals("OneSay/com.xingin.xhs", ImageSavePlan.relativeDir("com.xingin.xhs"));
        assertEquals("OneSay/unknown", ImageSavePlan.relativeDir(null));
        assertEquals("OneSay/a_b", ImageSavePlan.relativeDir("a/b"));
        assertEquals("OneSay_20261008_190512_2.png", ImageSavePlan.fileName("20261008_190512", 2, C, "png"));
        assertEquals("OneSay_20261008_190512_1_orig.webp", ImageSavePlan.fileName("20261008_190512", 1, O, "webp"));
        assertTrue(ImageSavePlan.chooseQuestion(3).contains("3 张"));
    }

    @Test public void switches() {
        assertTrue("默认开", ImageSaveSettings.decide(null, false, true));
        assertFalse("kill-switch imgsave.off", ImageSaveSettings.decide(null, true, true));
        assertFalse("设置关", ImageSaveSettings.decide(null, false, false));
        assertTrue("覆盖优先", ImageSaveSettings.decide(Boolean.TRUE, true, false));
        assertFalse("总开关关 → 缓存层关", ImageSaveSettings.decideCache(false, null, false, true));
        assertTrue("缓存层默认开", ImageSaveSettings.decideCache(true, null, false, true));
        assertFalse("imgsave.cache.off", ImageSaveSettings.decideCache(true, null, true, true));
        assertFalse("imgsave.cache=0", ImageSaveSettings.decideCache(true, null, false, false));
        assertEquals("imgsave.off", ImageSaveSettings.KILL);
        assertEquals("imgsave.cache.off", ImageSaveSettings.KILL_CACHE);
        assertEquals("bixby_imgsave", ImageSaveSettings.PREF);
        assertTrue(ImageSaveSettings.CACHE_WINDOW_S > 0 && ImageSaveSettings.CACHE_WINDOW_S <= 60);
    }

    @Test public void triageSwitch() {
        for (String q : ImageSaveIntentTest.POSITIVE) {
            TriageDecider.Decision on = TriageDecider.decide(q, TriageDecider.Signals.offline(), true, true, true);
            assertEquals(q, TriageDecider.Label.DO_ACT, on.label);
            assertEquals(q, "6i-image-save", on.rule);
            assertFalse(q + " 永不进 AgentLoop", on.useAgentLoop);
            TriageDecider.Decision off = TriageDecider.decide(q, TriageDecider.Signals.offline(), true, true, false);
            assertNotEquals("开关关闭回到原判法 " + q, "6i-image-save", off.rule);
        }
        // 「存一下图片」开关开时不是记一下，关时回到 S2 的记一下（原判法）
        assertEquals("6m-memory-write", TriageDecider.decide("存一下图片", TriageDecider.Signals.offline(), true, true, false).rule);
        // 负例：开关开关标签一致、规则永远不是 6i
        for (String q : ImageSaveIntentTest.NEGATIVE) {
            TriageDecider.Decision on = TriageDecider.decide(q, TriageDecider.Signals.offline(), true, true, true);
            TriageDecider.Decision off = TriageDecider.decide(q, TriageDecider.Signals.offline(), true, true, false);
            assertNotEquals(q, "6i-image-save", on.rule);
            assertEquals(q, off.label, on.label);
        }
        // 默认（4 参）= 开关开
        assertEquals("6i-image-save", TriageDecider.decide("保存图片", TriageDecider.Signals.offline()).rule);
        // 即使 L0 信号 / 缓存命中，也不进循环
        assertFalse(TriageDecider.decide("保存图片", new TriageDecider.Signals("image.save", true, false)).useAgentLoop);
    }
}
