package com.onesay.assistant.v2.ui.wake;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** 2026-11-05 用户真机反馈：截图分析后处理面板卡顿 → 流式 ≥50 ms 合并、排版复用、正文没变不 setText、芯片规划挪工作线程。 */
public class PanelJankTest {

    @Test public void streamCoalesces50ms() {
        StreamThrottle t = new StreamThrottle();
        assertEquals("第一块立刻上屏", 0L, t.offer(1000));
        assertEquals("10 ms 后：排尾刷新 40 ms 后", 40L, t.offer(1010));
        assertEquals("已排过：不再排", -1L, t.offer(1020));
        assertEquals(-1L, t.offer(1049));
        assertTrue(t.flushDue(1050));
        assertFalse("刷过不重复刷", t.flushDue(1051));
        assertEquals("距上次刷 ≥50 ms：立刻", 0L, t.offer(1100));
        // 100 个 token 每 5 ms 一个（500 ms）：上屏次数 ≤ 1 + 500/50 + 1
        StreamThrottle s = new StreamThrottle();
        int renders = 0;
        long pendingAt = -1;
        for (int i = 0; i < 100; i++) {
            long now = 10000 + i * 5;
            if (pendingAt >= 0 && now >= pendingAt) {
                if (s.flushDue(now)) renders++;
                pendingAt = -1;
            }
            long d = s.offer(now);
            if (d == 0) renders++;
            else if (d > 0) pendingAt = now + d;
        }
        assertTrue("渲染次数 " + renders, renders <= 12 && renders >= 9);
        // 终稿 reset：尾刷新作废、下一块立刻
        s.reset();
        assertFalse(s.flushDue(20000));
        assertEquals(0L, s.offer(20001));
    }

    @Test public void bodyMemoReusesSameText() {
        BodyMemo<CharSequence> m = new BodyMemo<CharSequence>();
        final int[] made = {0};
        BodyMemo.Maker<CharSequence> mk = new BodyMemo.Maker<CharSequence>() {
            @Override public CharSequence make(String text) {
                made[0]++;
                return new StringBuilder("<").append(text).append(">");
            }
        };
        CharSequence a = m.get("摘要", 20, mk);
        CharSequence b = m.get("摘要", 20, mk);
        assertSame("同文同排版 → 同一对象，不重排", a, b);
        assertEquals(1, made[0]);
        m.get("摘要", 21, mk);
        assertEquals("排版方式变了 → 重排", 2, made[0]);
        m.get("摘要 更多", 21, mk);
        assertEquals(3, made[0]);
        m.clear();
        m.get("摘要 更多", 21, mk);
        assertEquals(4, made[0]);
    }

    @Test public void wiring() throws Exception {
        String v = read("app/src/com/onesay/assistant/v2/ui/FloatingSheetView.java");
        assertTrue(v.contains("mStreamThrottle.offer(") && v.contains("postDelayed(mStreamFlush, due)"));
        assertTrue("终稿取消尾刷新", v.contains("streamThrottleReset();   // 终稿直接上屏"));
        assertTrue(v.contains("mBodyMemo.get(mV2Body, chat ? 11 : 10") && v.contains("mBodyMemo.get(mV2Body, chat ? 21 : 20"));
        assertTrue("正文没变不 setText", v.contains("bodyChanged || body != mLastSetBody"));
        String a = read("app/src/com/onesay/assistant/v2/suggest/shot/ScreenshotAnalyzer.java");
        int plan = a.indexOf("final ChipPlan plan = planChips(r);");
        int post = a.indexOf("MAIN.post(", plan);
        assertTrue("芯片规划在工作线程（MAIN.post 之前）", plan > 0 && post > plan);
        assertTrue(a.contains("showChips(svc, plan);"));
    }

    static String read(String rel) throws Exception {
        File dir = com.onesay.assistant.v2.testutil.TestRoot.start(PanelJankTest.class);
        for (int i = 0; i < 6 && dir != null; i++) {
            if (new File(dir, "app/AndroidManifest.xml").exists()) break;
            dir = dir.getParentFile();
        }
        return new String(Files.readAllBytes(new File(dir, rel).toPath()), StandardCharsets.UTF_8);
    }
}
