package com.onesay.assistant.v2.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.events.EventPickHint;
import com.onesay.assistant.v2.island.IslandModel;
import com.onesay.assistant.v2.notice.CalendarNotices;
import com.onesay.assistant.v2.notice.NoticeSettings;
import com.onesay.assistant.v2.notice.ScheduledMailNotices;
import com.onesay.assistant.v2.s3.S3Parser;
import com.onesay.assistant.v2.ui.chat.ChatWindowPolicy;
import com.onesay.assistant.v2.ui.console.FormChoices;
import com.onesay.assistant.v2.vision.VisionAvailability;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.TimeZone;

/** ★ fix/1123-dmerge：第二轮合并后补的 bug（B14 B21 B30 B31 B33 B34 B35 B37 后半 12.3 B24 展开 B4 用词）。 */
public class Fix1123dmTest {

    private static final TimeZone SH = TimeZone.getTimeZone("Asia/Shanghai");
    private static final String[] BAD = {"DashScope", "阿里", "百炼", "Bixby", "三星", "微信", "啥", "咋", "呗", "SHIZUKU_", "_DENIED"};

    private static void clean(String t) {
        assertNotNull(t);
        for (String b : BAD) assertFalse("用户可见文字不该有「" + b + "」：" + t, t.contains(b));
    }

    private static TriageDecider.Decision tri(String q) {
        return TriageDecider.decide(q, TriageDecider.Signals.offline());
    }

    // ---------------- B14 / B21：语音带空格的「每天 6 点 50 叫我吃药」= 带内容的提醒，不进智能体建无标签闹钟

    @Test public void b14_spacedDailyRemindIsS3Create() {
        for (String q : new String[] {"每天 6 点 50 叫我吃药", "每天6点50叫我吃药", "每天 早上 7 点 提醒我 吃药"}) {
            S3Parser.Parsed p = S3Parser.parse(q, System.currentTimeMillis(), SH);
            assertNotNull(q, p);
            assertEquals(q, S3Parser.Type.CREATE, p.type);
            assertEquals(q, "吃药", p.text);
            TriageDecider.Decision d = tri(q);   // 提醒插件或日程（提醒）本地接，都行；不能落到 6-act 智能体
            assertTrue(q + " → " + d.rule, "6t-s3-create".equals(d.rule) || "6d-sched".equals(d.rule));
            assertFalse(q, d.useAgentLoop);
        }
        assertEquals("6t-s3-create", tri("每天 6 点 50 叫我吃药").rule);
        S3Parser.Parsed p = S3Parser.parse("每天 6 点 50 叫我吃药", System.currentTimeMillis(), SH);
        assertEquals(6, p.hour);
        assertEquals(50, p.minute);
    }

    @Test public void b14_bareAlarmStillAlarmAndEnglishSpacesKept() {
        assertEquals("clock.alarm.set", FastIntents.route("每天6点50叫我").sign);
        assertEquals("每天6点50叫我吃药", S3Parser.norm("每天 6 点 50 叫我 吃药"));
        assertEquals("明天开会 with Tom", S3Parser.norm("明天 开会 with Tom"));
    }

    // ---------------- B30：没点名应用的「清空聊天记录」本地问清楚，不交给模型、不清存档

    @Test public void b30_bareChatRecordClearAsksLocally() {
        for (String q : new String[] {"清空聊天记录", "清空聊天记录。", "把聊天记录清了", "删掉对话记录", "帮我清空所有聊天记录"}) {
            TriageDecider.Decision d = tri(q);
            assertEquals(q, TriageDecider.Label.ASK, d.label);
            assertEquals(q, "3c-chat-record-clarify", d.rule);
            assertEquals(q, AskEngine.CHAT_RECORD_CLARIFY, AskEngine.tryAnswer(q));
        }
        clean(AskEngine.CHAT_RECORD_CLARIFY);
        assertTrue(AskEngine.CHAT_RECORD_CLARIFY.contains("还没动"));
        assertTrue(AskEngine.CHAT_RECORD_CLARIFY.contains("清空聊天存档"));
    }

    @Test public void b30_namedAppAndArchiveUntouched() {
        assertFalse(AskEngine.isBareChatRecordClear("清空微信聊天记录"));
        assertFalse(AskEngine.isBareChatRecordClear("清空聊天存档"));
        assertNull(AskEngine.tryAnswer("清空微信聊天记录"));
        assertNotEquals("3c-chat-record-clarify", tri("清空聊天存档").rule);
        assertNotEquals("3c-chat-record-clarify", tri("聊天记录在哪看").rule);
    }

    // ---------------- 12.3：「我航班几点起飞来着」问的是前面说过的事 → 问答，不进智能体读设备

    @Test public void p123_recallQuestionIsAsk() {
        for (String q : new String[] {"我航班几点起飞来着", "我航班几点起飞来着？", "我刚才说的会是几点", "我之前跟你说的车位号是多少"}) {
            TriageDecider.Decision d = tri(q);
            assertEquals(q, TriageDecider.Label.ASK, d.label);
            assertFalse(q, d.useAgentLoop);
        }
        assertNotEquals(TriageDecider.Label.ASK, tri("打开设置，看看安卓版本是多少").label);
    }

    // ---------------- B31：没识屏 Key 先说，不截屏；文案不带服务商名

    @Test public void b31_noKeyTextCleanAndPreflight() {
        clean(VisionAvailability.NO_KEY_USER);
        assertTrue(VisionAvailability.NO_KEY_USER.contains("识屏要先在设置里填好识屏用的 Key"));
        assertTrue(VisionAvailability.keyMissing(null));
        assertTrue(VisionAvailability.keyMissing("  "));
        assertFalse(VisionAvailability.keyMissing("x"));
    }

    // ---------------- B37 后半：日历新建页只是填好，没保存

    @Test public void b37_calendarInsertSaysNotSavedYet() {
        String t = com.onesay.assistant.v2.apifirst.ApiIntent.CAL_INSERT_OPENED;
        assertTrue(t.contains("还没保存"));
        assertTrue(t.contains("不要说已经建好"));
        clean(t);
    }

    // ---------------- B33：提前量档位首次打开勾在用的默认值

    @Test public void b33_leadDefaultIsInUseValue() {
        assertEquals(CalendarNotices.DEFAULT_LEAD_MIN, NoticeSettings.leadDefault(NoticeSettings.K_CAL_LEAD));
        assertEquals(10, NoticeSettings.leadDefault(NoticeSettings.K_CAL_LEAD));
        assertEquals(ScheduledMailNotices.DEFAULT_LEAD_MIN, NoticeSettings.leadDefault(NoticeSettings.K_MAIL_LEAD));
        assertTrue(FormChoices.minuteSteps(0, 120, NoticeSettings.leadDefault(NoticeSettings.K_CAL_LEAD)).contains(10));
    }

    // ---------------- B34 / B35：选 Wi‑Fi / 蓝牙列表空时说原因

    @Test public void b34_b35_emptyPickHints() {
        assertEquals(EventPickHint.WIFI_NEED_LOCATION, EventPickHint.emptyHint(true, Collections.<String>emptyList(), true, false));
        assertEquals(EventPickHint.WIFI_NEED_LOCATION, EventPickHint.emptyHint(true, Arrays.asList("<unknown ssid>", "\"\""), true, false));
        assertEquals(EventPickHint.WIFI_EMPTY, EventPickHint.emptyHint(true, new ArrayList<String>(), true, true));
        assertNull(EventPickHint.emptyHint(true, Arrays.asList("\"Home\""), true, false));
        assertEquals(EventPickHint.BT_OFF, EventPickHint.emptyHint(false, null, false, true));
        assertEquals(EventPickHint.BT_EMPTY, EventPickHint.emptyHint(false, null, true, true));
        assertNull(EventPickHint.emptyHint(false, Arrays.asList("耳机"), false, true));
        for (String t : new String[] {EventPickHint.WIFI_NEED_LOCATION, EventPickHint.WIFI_EMPTY, EventPickHint.BT_OFF, EventPickHint.BT_EMPTY}) {
            clean(t);
            assertTrue(t, t.contains(FormChoices.MANUAL));
        }
    }

    // ---------------- B24：超过 3 行出「展开」

    @Test public void b24_expandToggle() {
        assertFalse(ChatWindowPolicy.showExpandToggle(1, false));
        assertFalse(ChatWindowPolicy.showExpandToggle(3, false));
        assertTrue(ChatWindowPolicy.showExpandToggle(4, false));
        assertTrue(ChatWindowPolicy.showExpandToggle(1, true));
        assertEquals(5, ChatWindowPolicy.maxLines(false, 5));
        assertEquals(ChatWindowPolicy.EXPANDED_LINES, ChatWindowPolicy.maxLines(true, 5));
        assertTrue(ChatWindowPolicy.EXPANDED_LINES > 5);
    }

    // ---------------- B4：卡住 / 到预算自动停下来问的，岛上叫「已暂停」，不叫「待确认」

    @Test public void b4_stuckPauseWord() {
        assertEquals("已暂停", IslandModel.waitWord(null, "继续执行这个任务（已用 3 分钟）"));
        assertEquals("已暂停", IslandModel.waitWord(null, "好像卡住了：连续 3 轮界面没有任何变化"));
        assertEquals("已暂停", IslandModel.waitWord(null, "agent.budget.continue"));
        assertEquals("待确认", IslandModel.waitWord(null, "删除全部闹钟"));
        assertEquals("待确认", IslandModel.waitWord(null, null));
        assertEquals("待你选择", IslandModel.waitWord("a1", "继续执行这个任务"));
    }
}
