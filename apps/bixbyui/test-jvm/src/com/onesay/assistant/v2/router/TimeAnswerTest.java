package com.onesay.assistant.v2.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Calendar;
import java.util.TimeZone;

/** 一般时间问答本地直答（设备时区）+ LLM 提示词带当前时间、不放宽「严禁编造」。 */
public class TimeAnswerTest {

    static final TimeZone SH = TimeZone.getTimeZone("Asia/Shanghai");

    static Calendar now() {   // 2026-11-04 星期三 21:40（上海）
        Calendar c = Calendar.getInstance(SH);
        c.clear();
        c.set(2026, Calendar.NOVEMBER, 4, 21, 40, 0);
        return c;
    }

    static String a(String q) { return TimeAnswer.answer(q, now()); }

    @Test public void dayQuestions() {
        assertEquals("今天是2026年11月4日，星期三。", a("今天几月几号星期几"));
        assertEquals("今天是2026年11月4日，星期三。", a("今天是几月几号？"));
        assertEquals("今天是2026年11月4日，星期三。", a("今天是周几"));
        assertEquals("明天是星期四（11月5日）。", a("明天星期几"));
        assertEquals("明天是11月5日，星期四。", a("明天几号"));
        assertEquals("后天是星期五（11月6日）。", a("后天星期几"));
        assertEquals("昨天是11月3日，星期二。", a("昨天几号"));
        assertEquals("大后天是11月7日，星期六。", a("大后天几号呀"));
    }

    @Test public void weekdayDate() {
        assertEquals("这周五是11月6日。", a("这周五是几号"));
        assertEquals("下周一是11月9日。", a("下周一是几号"));
        assertEquals("这周日是11月8日。", a("周日是几号"));
        assertEquals("上周三是10月28日。", a("上周三是几号"));
    }

    @Test public void clockYearAmPm() {
        assertEquals("现在是 21:40。", a("现在几点几分"));
        assertEquals("现在是 21:40。", a("现在几点钟"));
        assertEquals("今年是2026年。", a("今年是哪一年"));
        assertEquals("今年是2026年。", a("今年是几几年"));
        assertEquals("现在是晚上 21:40。", a("现在是上午还是下午"));
        assertEquals("现在是11月。", a("现在几月"));
    }

    @Test public void notTimeQuestions() {
        assertNull(a("明天星期几有会"));
        assertNull(a("今天几号发工资"));
        assertNull(a("明天天气怎么样"));
        assertNull(a("打开蓝牙"));
        assertNull(a(""));
    }

    @Test public void localRoutingNoAgent() {
        TimeZone saved = TimeZone.getDefault();
        try {
            TimeZone.setDefault(SH);
            RouteLayerModel m = new RouteLayerModel(true);
            for (String q : new String[]{"今天几月几号星期几", "明天星期几", "后天星期几", "昨天几号", "现在几点几分", "今年是哪一年", "现在是上午还是下午", "这周五是几号"}) {
                RouteLayerModel.Result r = m.predict(q);
                assertTrue(q + " → " + r, r.layer != RouteLayerModel.Layer.AGENT && r.layer != RouteLayerModel.Layer.GUI);
                assertNotNull(q, AskEngine.tryAnswer(q));
            }
            // 原有精确表口径不变
            assertTrue(AskEngine.tryAnswer("现在几点").startsWith("现在是北京时间 "));
            assertTrue(AskEngine.tryAnswer("今天几号").startsWith("今天是 "));
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    @Test public void promptsCarryNowWithoutLooseningNoFabrication() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        String lib = new String(Files.readAllBytes(new File(root, "app/src/com/onesay/assistant/v2/router/PromptLibrary.java").toPath()), StandardCharsets.UTF_8);
        assertTrue(lib.contains("**严禁编造**任何数值"));
        assertTrue(lib.contains("日期、星期、钟点一律以下方【当前时间】为准"));
        assertTrue(lib.contains("{{now}}"));
        String loop = new String(Files.readAllBytes(new File(root, "app/src/com/onesay/assistant/v2/router/AgentLoop.java").toPath()), StandardCharsets.UTF_8);
        assertTrue(loop.contains("TimeAnswer.nowLine(java.util.Calendar.getInstance())"));
        String line = TimeAnswer.nowLine(now());
        assertTrue(line, line.contains("2026年11月4日 星期三 21:40") && line.contains("Asia/Shanghai"));
        assertFalse(line.contains("\n"));
    }
}
