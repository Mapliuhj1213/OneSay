package com.onesay.assistant.v2.util;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** ★ feat/api-first：可点追问短语 [[…]] 的解析、渲染文字、朗读 / 纯文本剥离、流式半截。 */
public class FollowupMarksTest {

    static final String A = "有两条路：14:07 的高铁当天到，18:11 的夜间卧铺省钱。要哪一趟？[[订14:07那趟当天到的高铁]] [[订18:11出发的夜间卧铺]]";

    @Test public void parseSegments() {
        List<FollowupMarks.Seg> s = FollowupMarks.parse(A);
        assertEquals(4, s.size());
        assertFalse(s.get(0).chip);
        assertTrue(s.get(1).chip);
        assertEquals("订14:07那趟当天到的高铁", s.get(1).text);
        assertEquals(" ", s.get(2).text);
        assertEquals("订18:11出发的夜间卧铺", s.get(3).text);
        assertEquals(Arrays.asList("订14:07那趟当天到的高铁", "订18:11出发的夜间卧铺"), FollowupMarks.chips(A));
        assertTrue(FollowupMarks.has(A));
    }

    @Test public void plainAndSpeechStripMarks() {
        String p = FollowupMarks.plain(A);
        assertFalse(p.contains("[["));
        assertFalse(p.contains("]]"));
        assertTrue(p.endsWith("订14:07那趟当天到的高铁 订18:11出发的夜间卧铺"));
        // 朗读：SpeechText 统一入口也剥掉
        String sp = SpeechText.forSpeech(A, 200);
        assertFalse(sp, sp.contains("[") || sp.contains("]"));
        assertTrue(sp, sp.contains("订18:11出发的夜间卧铺"));
        // 对话窗纯文本（复制 / 朗读）
        String chat = com.onesay.assistant.v2.ui.chat.ChatMarkdown.plain("**结论**\n" + A);
        assertFalse(chat, chat.contains("[[") || chat.contains("↵"));
    }

    @Test public void displayAddsEnterGlyph() {
        assertEquals("要哪趟？订A↵ 订B↵", FollowupMarks.display("要哪趟？[[订A]] [[订B]]"));
        com.onesay.assistant.v2.ui.chat.ChatMarkdown.Doc d = com.onesay.assistant.v2.ui.chat.ChatMarkdown.parse("要哪趟？[[订A]]");
        assertEquals("要哪趟？订A↵", d.text);
        assertEquals(1, d.followups.size());
        assertEquals("订A", d.followupText.get(0));
        int[] r = d.followups.get(0);
        assertEquals("订A↵", d.text.substring(r[0], r[1]));
    }

    @Test public void limitsAndNonMarks() {
        assertEquals(3, FollowupMarks.chips("[[a]][[b]][[c]][[d]]").size());
        assertEquals("abcd", FollowupMarks.plain("[[a]][[b]][[c]][[d]]"));
        // 第 4 个不可点，变普通文字
        List<FollowupMarks.Seg> s = FollowupMarks.parse("[[a]][[b]][[c]][[d]]");
        assertEquals("d", s.get(s.size() - 1).text);
        assertFalse(s.get(s.size() - 1).chip);
        // 太长 / 跨行 / 单括号都不算
        String longOne = "[[" + new String(new char[31]).replace('\0', '字') + "]]";
        assertFalse(FollowupMarks.has(longOne));
        assertFalse(FollowupMarks.has("[[跨\n行]]"));
        assertFalse(FollowupMarks.has("数组 a[1] 和 [链接](https://x)"));
        assertEquals("数组 a[1]", FollowupMarks.plain("数组 a[1]"));
        assertEquals("", FollowupMarks.plain("[[  ]]").trim().replace("[[  ]]", ""));
    }

    @Test public void streamingHidesDanglingOpen() {
        assertEquals("要哪趟？", FollowupMarks.streaming("要哪趟？[[订18"));
        assertEquals("要哪趟？", FollowupMarks.streaming("要哪趟？["));
        assertEquals("要哪趟？[[订A]]", FollowupMarks.streaming("要哪趟？[[订A]]"));
        assertEquals("a[1]", FollowupMarks.streaming("a[1]"));
    }
}
