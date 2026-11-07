package com.onesay.assistant.v2.summary;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.vision.screenref.UiNode;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** [summary] 输入整理与字数预算：去界面零件、单行封顶、头 + 尾 + 可见段落截断、省略标记。 */
public class SummaryInputTest {

    static UiNode n(String text, boolean clickable, int top, int bottom) {
        return new UiNode(text, "", "", "android.widget.TextView", "com.example", clickable, false, 0, top, 1080, bottom);
    }

    static String para(int i, int len) {
        StringBuilder sb = new StringBuilder("第" + i + "段");
        while (sb.length() < len) sb.append("内容");
        return sb.substring(0, len);
    }

    @Test public void shortInputKeptWholeAndChromeDropped() {
        List<UiNode> l = Arrays.asList(
                n("返回", true, 0, 100), n("分享", true, 0, 100), n("1.2万", false, 100, 150), n("99+", false, 100, 150),
                n("这是一篇关于城市骑行的文章。", false, 200, 260), n("这是一篇关于城市骑行的文章。", false, 300, 360),
                n("作者认为共享单车改变了通勤方式，但停放管理仍是难题。", false, 400, 460), n("12:30", false, 0, 50));
        SummaryInput.Built b = SummaryInput.fromNodes(l, 2220, SummaryInput.BUDGET);
        assertFalse(b.truncated);
        assertEquals(2, b.keptLines);
        assertFalse(b.text.contains("返回"));
        assertFalse(b.text.contains("1.2万"));
        assertFalse(b.text.contains("12:30"));
        assertTrue(b.enough() == (b.totalChars >= SummaryInput.MIN_CHARS));
    }

    @Test public void tooLittleTextIsNotEnough() {
        SummaryInput.Built b = SummaryInput.fromLines(Arrays.asList("登录", "注册"), SummaryInput.BUDGET);
        assertFalse(b.enough());
    }

    @Test public void longLineIsCapped() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) sb.append('字');
        SummaryInput.Built b = SummaryInput.fromLines(Arrays.asList(sb.toString()), SummaryInput.BUDGET);
        assertEquals(SummaryInput.LINE_MAX, b.text.length());
        assertTrue(b.text.contains("…"));
    }

    @Test public void truncationKeepsHeadTailAndVisibleWithinBudget() {
        List<UiNode> l = new ArrayList<UiNode>();
        // 40 段、每段 200 字；屏幕高 2000，只有第 20~24 段在屏幕上（WebView 会吐出屏幕外的全文）
        for (int i = 0; i < 40; i++) {
            int top = (i - 20) * 400;
            l.add(n(para(i, 200), false, top, top + 380));
        }
        SummaryInput.Built b = SummaryInput.fromNodes(l, 2000, SummaryInput.BUDGET);
        assertTrue(b.truncated);
        assertEquals(8000, b.totalChars);
        assertTrue("预算 " + b.keptChars, b.keptChars <= SummaryInput.BUDGET);
        assertTrue("头", b.text.startsWith("第0段"));
        assertTrue("尾", b.text.contains("第39段"));
        assertTrue("可见段落", b.text.contains("第20段") && b.text.contains("第22段"));
        assertFalse("屏幕外的中段不在", b.text.contains("第10段"));
        assertTrue("省略标记", b.text.contains("……（中间省略 "));
        assertEquals(b.totalChars - b.keptChars, b.omittedChars);
        // 顺序保持
        assertTrue(b.text.indexOf("第0段") < b.text.indexOf("第20段") && b.text.indexOf("第20段") < b.text.indexOf("第39段"));
    }

    @Test public void unknownScreenHeightTreatsAllVisible() {
        List<String> lines = new ArrayList<String>();
        for (int i = 0; i < 30; i++) lines.add(para(i, 300));
        SummaryInput.Built b = SummaryInput.fromLines(lines, 2000);
        assertTrue(b.truncated);
        assertTrue(b.keptChars <= 2000);
        assertTrue(b.text.startsWith("第0段"));
        assertTrue(b.text.contains("第29段"));
    }

    /** 2026-10-10 真机：三星浏览器没开无障碍时只读到地址栏 + 工具栏 → 地址栏不计字数，直接判「文字太少」改走看图。 */
    @Test
    public void browserChromeOnly_notEnough() {
        java.util.List<UiNode> l = new java.util.ArrayList<UiNode>();
        l.add(n("file:///sdcard/Download/bxa_sum_article.html", false, 40, 120));
        l.add(n("https://m.example.com/news/2026/10/10/very-long-article-path?id=123456", false, 40, 120));
        l.add(n("www.example.com", false, 40, 120));
        l.add(n("工具, 有新内容可用", true, 2100, 2200));
        l.add(n("5", true, 2100, 2200));
        SummaryInput.Built b = SummaryInput.fromNodes(l, 2220, SummaryInput.BUDGET);
        assertFalse(b.enough());
        assertFalse(b.text.contains("file://"));
    }

    @Test
    public void urlInsideSentence_kept() {
        java.util.List<UiNode> l = new java.util.ArrayList<UiNode>();
        l.add(n("详情请访问 https://example.com/a 了解更多，过去五年城市骑行人数增长了近一倍，共享单车是主要推动力。", false, 300, 400));
        l.add(n("Node.js", false, 400, 450));
        SummaryInput.Built b = SummaryInput.fromNodes(l, 2220, SummaryInput.BUDGET);
        assertTrue(b.text.contains("https://example.com/a"));
        assertTrue(b.text.contains("Node.js"));
    }
}
