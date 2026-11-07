package com.onesay.assistant.v2.vision.screenref;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

import org.junit.Test;

import com.onesay.assistant.v2.router.TriageDecider;
import com.onesay.assistant.v2.vision.screenref.DeixisResolver.Action;
import com.onesay.assistant.v2.vision.screenref.DeixisResolver.Kind;

/**
 * [round7] 10-10 真机：「看看屏幕上写的什么然后打开微信」在面板入口被识屏指代接管（NO_MATCH/OPEN_URL「屏幕上没找到链接」），
 * 不进 AgentLoop、不出红卡。复合任务（读屏 + 然后另起动作）现在不由识屏接管；纯指代照旧。
 */
public class DeixisCompoundTest {

    static final String D2 = "看看屏幕上写的什么然后打开微信";

    @Test public void deviceSentenceIsCompoundNotTakenOver() {
        assertTrue("仍是指代（AgentLoop 要据此追加 screen.* 工具 / 注入屏幕上下文）", DeixisResolver.hasDeixis(D2));
        assertTrue(DeixisResolver.isCompoundTask(D2));
        assertTrue(DeixisResolver.isCompoundTask("看看屏幕上写的什么，然后打开微信"));
        assertTrue(DeixisResolver.isCompoundTask("读一下屏幕上的内容再打开QQ"));
        assertTrue(DeixisResolver.isCompoundTask("看看这个页面说了什么，接着发给妈妈"));
        assertTrue(DeixisResolver.isCompoundTask("复制这个取件码然后打开淘宝"));
        assertTrue(DeixisResolver.isCompoundTask("屏幕上有什么之后告诉我"));
        // 消解层口径一致：复合 → NEEDS_AGENT（不再是 NO_MATCH/OPEN_URL）
        DeixisResolver.Resolution r = DeixisResolver.resolve(D2, Collections.<ScreenEntity>emptyList());
        assertEquals(Kind.NEEDS_AGENT, r.kind);
        assertEquals(Action.NONE, r.action);
    }

    @Test public void genuineDeixisStillTakenOver() {
        String[] pure = {"打开屏幕上这个链接", "打开这个链接", "导航去这里", "复制这个", "打这个电话", "把屏幕上的时间加到日历",
                "刚才那个地址怎么走", "复制取件码", "查快递", "拨一下上面的号码", "再拨一下这个号码", "看看这个链接",
                "这个是什么意思", "导航到刚复制的地址"};
        for (String q : pure) {
            assertTrue(q, DeixisResolver.hasDeixis(q));
            assertFalse("纯指代不能被当成复合任务：" + q, DeixisResolver.isCompoundTask(q));
        }
        DeixisResolver.Resolution r = DeixisResolver.resolve("打开屏幕上这个链接", DeixisResolverTest.CHAT);
        assertEquals(Kind.RESOLVED, r.kind);
        assertEquals(Action.OPEN_URL, r.action);
        assertEquals(ScreenEntity.Type.URL, r.entity.type);
    }

    @Test public void nonScreenSequencesAreNotCompoundDeixis() {
        assertFalse("没有读屏 / 指代的前半句", DeixisResolver.isCompoundTask("打开wifi然后打开蓝牙"));
        assertFalse(DeixisResolver.isCompoundTask("半小时之后提醒我喝水"));
        assertFalse(DeixisResolver.isCompoundTask("然后打开微信"));
        assertFalse("后半句没有另起动作", DeixisResolver.isCompoundTask("看看屏幕上写的什么然后呢"));
        assertFalse(DeixisResolver.isCompoundTask(""));
        assertFalse(DeixisResolver.isCompoundTask(null));
    }

    @Test public void routerSendsCompoundToAgentLoop() {
        // 放行后走路由层：分诊必须判 AgentLoop（真机 10-09 调试入口同句：rule#6-act agentLoop=true，l0=vision.screen.understand）
        TriageDecider.Decision d = TriageDecider.decide(D2, new TriageDecider.Signals("vision.screen.understand", false, false));
        assertTrue(d.toString(), d.useAgentLoop);
        assertTrue(TriageDecider.decide(D2, TriageDecider.Signals.offline()).useAgentLoop);
    }

    @Test public void panelInterceptChecksCompoundBeforeTakingOver() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        String src = new String(Files.readAllBytes(new File(root,
                "app/src/com/onesay/assistant/v2/vision/screenref/ScreenRefHooks.java").toPath()), StandardCharsets.UTF_8);
        int deixis = src.indexOf("if (!DeixisResolver.hasDeixis(command)) return false;");
        int compound = src.indexOf("if (DeixisResolver.isCompoundTask(command))", deixis);
        int takeover = src.indexOf("[DEIXIS] 指代命中，接管", deixis);
        assertTrue("拦截处必须先判复合任务再接管", deixis > 0 && compound > deixis && takeover > compound);
        assertTrue(src.substring(compound, takeover).contains("return false;"));
    }
}
