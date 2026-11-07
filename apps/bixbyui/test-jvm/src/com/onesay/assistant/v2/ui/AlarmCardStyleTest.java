package com.onesay.assistant.v2.ui;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/** 定闹钟结果卡改成 Bixby 时钟（三星闹钟复刻）列表卡样式：常量照抄、格式化、接线。 */
public class AlarmCardStyleTest {

    static final TimeZone SH = TimeZone.getTimeZone("Asia/Shanghai");

    static long at(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(SH);
        c.clear();
        c.set(y, mo - 1, d, h, mi, 0);
        return c.getTimeInMillis();
    }

    static JSONObject alarm(int id, int h, int m, int d, boolean on, String r, long next) throws Exception {
        return new JSONObject().put("id", id).put("h", h).put("m", m).put("d", d).put("on", on).put("r", r).put("next", next);
    }

    @Test public void colorsAndSizesAreBixbyClocks() {
        // com.onesay.clock ui/Ui.java + MainActivity.item 原值
        assertEquals(0xFFFFFFFF, AlarmCardStyle.CARD);
        assertEquals(0xFF111111, AlarmCardStyle.T1);
        assertEquals(0xFF8A8A8E, AlarmCardStyle.T2);
        assertEquals(0xFFB9B9BD, AlarmCardStyle.T_OFF);
        assertEquals(0xFF3E7BF2, AlarmCardStyle.ACCENT);
        assertEquals(0x803E7BF2, AlarmCardStyle.TRACK_ON);
        assertEquals(0x42000000, AlarmCardStyle.TRACK_OFF);
        assertEquals(26f, AlarmCardStyle.CARD_RADIUS_DP, 0f);
        assertEquals(40f, AlarmCardStyle.TIME_SP, 0f);
        assertEquals(15f, AlarmCardStyle.AMPM_SP, 0f);
        assertEquals(13f, AlarmCardStyle.DAYS_SP, 0f);
        assertEquals(24f, AlarmCardStyle.PAD_LEFT_DP, 0f);
        assertEquals(0x1F3E7BF2, AlarmCardStyle.chipBg(true));
        assertEquals(AlarmCardStyle.T1, AlarmCardStyle.timeColor(true));
        assertEquals(AlarmCardStyle.T_OFF, AlarmCardStyle.timeColor(false));
    }

    @Test public void timeFormatLikeBixbyClock() {
        assertEquals("7:00", AlarmCardStyle.time(false, 7, 0));
        assertEquals("12:05", AlarmCardStyle.time(false, 0, 5));
        assertEquals("10:16", AlarmCardStyle.time(false, 22, 16));
        assertEquals("07:00", AlarmCardStyle.time(true, 7, 0));
        assertEquals("上午", AlarmCardStyle.ampm(false, 7));
        assertEquals("下午", AlarmCardStyle.ampm(false, 12));
        assertEquals("", AlarmCardStyle.ampm(true, 7));
    }

    @Test public void repeatDaysAreSevenCharsSelectedBlue() {
        assertEquals("日 一 二 三 四 五 六", AlarmCardStyle.daysText());
        int workdays = 0x3E;
        assertEquals(AlarmCardStyle.T_OFF, AlarmCardStyle.dayColor(workdays, 0, true));   // 周日没选
        assertEquals(AlarmCardStyle.ACCENT, AlarmCardStyle.dayColor(workdays, 1, true));  // 周一选中、开着 → 蓝
        assertEquals(AlarmCardStyle.T2, AlarmCardStyle.dayColor(workdays, 1, false));     // 关着 → 灰
        assertEquals('六', AlarmCardStyle.daysText().charAt(AlarmCardStyle.dayCharIndex(6)));
    }

    @Test public void fromDumpSortsAndFormats() throws Exception {
        long now = at(2026, 11, 6, 14, 30);
        JSONArray arr = new JSONArray()
                .put(alarm(6, 22, 16, 0, true, "smart", at(2026, 11, 6, 22, 16)))
                .put(alarm(7, 7, 0, 0, true, "smart", at(2026, 11, 7, 7, 0)))
                .put(alarm(2, 6, 30, 0x3E, false, "/sdcard/a.ogg", at(2026, 11, 9, 6, 30)));
        List<AlarmCardStyle.Card> cs = AlarmCardStyle.fromDump(new JSONObject().put("alarms", arr), false, now, SH);
        assertEquals(3, cs.size());
        assertEquals(2, cs.get(0).id);   // 6:30 → 7:00 → 22:16
        assertEquals(7, cs.get(1).id);
        assertEquals(6, cs.get(2).id);
        AlarmCardStyle.Card wake = cs.get(1);
        assertEquals("上午", wake.ampm);
        assertEquals("7:00", wake.time);
        assertEquals("明天", wake.oneShot);
        assertTrue(wake.smart);
        assertEquals("今天", cs.get(2).oneShot);
        assertEquals("10:16", cs.get(2).time);
        AlarmCardStyle.Card rep = cs.get(0);
        assertTrue(rep.repeats());
        assertEquals("", rep.oneShot);
        assertFalse("自定义铃声不显示智能播报胶囊", rep.smart);
        assertFalse(rep.on);
        assertTrue(rep.spoken, rep.spoken.startsWith("工作日 上午 6:30") && rep.spoken.contains("已关闭"));
        assertTrue(AlarmCardStyle.fromDump(null, false, now, SH).isEmpty());
    }

    @Test public void summaryLineIsUntilEarliestEnabled() throws Exception {
        long now = at(2026, 11, 6, 14, 30);
        JSONArray arr = new JSONArray()
                .put(alarm(1, 2, 50, 0, false, "smart", at(2026, 11, 7, 2, 50)))
                .put(alarm(6, 22, 16, 0, true, "smart", at(2026, 11, 6, 22, 16)))
                .put(alarm(7, 7, 0, 0, true, "smart", at(2026, 11, 7, 7, 0)));
        JSONObject dump = new JSONObject().put("alarms", arr);
        assertEquals(at(2026, 11, 6, 22, 16), AlarmCardStyle.earliestOn(dump, now));
        assertEquals("7 小时 46 分钟后响铃", AlarmCardStyle.untilLabel(AlarmCardStyle.earliestOn(dump, now), now));
        assertEquals("所有闹钟均已关闭", AlarmCardStyle.untilLabel(0, now));
        assertEquals("1 天 2 小时后响铃", AlarmCardStyle.untilLabel(now + 26L * 3600000L, now));
    }

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(rel).toPath()), StandardCharsets.UTF_8);
    }

    @Test public void viewUsesNewStyleAndOldDarkCardIsGone() throws Exception {
        String v = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetView.java");
        assertFalse("晨间播报暗色卡常量已删", v.contains("MB_CARD_BG") || v.contains("0xFF171B21"));
        assertFalse("铃声行已并进整卡点击", v.contains("铃声 · "));
        assertTrue(v.contains("AlarmCardStyle.TIME_SP") && v.contains("AlarmCardStyle.dayColor") && v.contains("AlarmCardStyle.TRACK_ON"));
        assertTrue("开关 / 点卡片回调不变", v.contains("mListener.onAlarmToggle(a.id, isChecked)") && v.contains("mListener.onAlarmOpen(a.id)"));
        String s = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        assertTrue(s.contains("AlarmCardStyle.fromDump(") && s.contains("alarmSummary(dump)"));
    }
}
