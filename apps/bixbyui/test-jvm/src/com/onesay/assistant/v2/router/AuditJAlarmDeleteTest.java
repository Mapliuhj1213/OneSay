package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.executor.AlarmBridge;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** ★ audit-J 3（Gemini 真机 DEL-01）：「删掉6点40的闹钟」L0 → alarm.cancel{hour,minute(,alt_hour)}；执行器只删时、分完全匹配的那一个。 */
public class AuditJAlarmDeleteTest {

    static Invocation r(String q) { return FastIntents.route(q); }

    static void del(String q, String sign, int h, int m, int alt) throws Exception {
        Invocation f = r(q);
        assertNotNull(q, f);
        assertEquals(q, sign, f.sign);
        assertEquals(q, h, f.args.getInt("hour"));
        assertEquals(q, m, f.args.getInt("minute"));
        assertEquals(q, alt, f.args.optInt("alt_hour", -1));
    }

    @Test public void l0Parses() throws Exception {
        del("删掉6点40的闹钟", "alarm.cancel", 6, 40, 18);
        del("删除6:40的闹钟", "alarm.cancel", 6, 40, 18);
        del("取消早上6点半的闹钟", "alarm.cancel", 6, 30, -1);
        del("删掉晚上8点的闹钟", "alarm.cancel", 20, 0, -1);
        del("删掉23点47的闹钟", "alarm.cancel", 23, 47, -1);
        del("删掉23:47的闹钟", "alarm.cancel", 23, 47, -1);
        del("把下午三点二十的闹钟删掉", "alarm.cancel", 15, 20, -1);
        del("关掉6点40的闹钟", "alarm.toggle", 6, 40, 18);
        assertFalse(r("关掉6点40的闹钟").args.optBoolean("on", true));
    }

    @Test public void othersUnchanged() {
        Invocation all = r("删除所有闹钟");
        assertNotNull(all);
        assertEquals("alarm.cancel.all", all.sign);
        Invocation set = r("明天早上6点40叫醒我");
        assertTrue(set == null || !set.sign.startsWith("alarm.cancel"));
    }

    static JSONObject dump(int[][] hm) throws Exception {
        JSONArray a = new JSONArray();
        for (int[] x : hm) a.put(new JSONObject().put("id", x[0]).put("h", x[1]).put("m", x[2]).put("on", true));
        return new JSONObject().put("count", hm.length).put("alarms", a);
    }

    @Test public void exactMatchOnly() throws Exception {
        JSONObject d = dump(new int[][]{{1, 6, 0}, {2, 18, 40}, {3, 6, 41}});
        List<JSONObject> l = AlarmBridge.exactMatches(d, 6, 40, 18);
        assertEquals(1, l.size());
        assertEquals(2, l.get(0).getInt("id"));
        assertEquals("6:40 不会匹配到 6:00 / 6:41", 1, l.size());
        assertEquals(1, AlarmBridge.exactMatches(d, 6, 0, -1).get(0).getInt("id"));
        assertEquals(0, AlarmBridge.exactMatches(d, 7, 40, 19).size());
        JSONObject both = dump(new int[][]{{1, 7, 0}, {2, 6, 40}, {3, 18, 40}});
        assertEquals("上午下午都有 → 2 个，执行器只回话不删", 2, AlarmBridge.exactMatches(both, 6, 40, 18).size());
        assertEquals("说了晚上 → 只有 18:40", 1, AlarmBridge.exactMatches(both, 18, 40, -1).size());
        assertEquals("7:00、6:40、18:40", AlarmBridge.listTimes(both));
    }
}
