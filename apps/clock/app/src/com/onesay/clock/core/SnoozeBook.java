package com.onesay.clock.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 贪睡的独立记录（P0-2）：每个闹钟最多一条待响贪睡。只有「贪睡到点 / 用户停止 / 闹钟被删或关」会移除它，
 * 别的排程动作（增删改其它闹钟、时区变化、开机）都不碰它。序列化成「id:at,id:at」。
 */
public final class SnoozeBook {
    /** 到点后最多晚这么久仍算有效（开机慢、Doze）。 */
    public static final long LATE_MS = 10 * 60_000L;

    public static final class Entry {
        public final int alarmId;
        public final long at;

        Entry(int id, long at) { this.alarmId = id; this.at = at; }
    }

    private final List<Entry> list = new ArrayList<Entry>();

    public List<Entry> entries() { return new ArrayList<Entry>(list); }

    public void put(int alarmId, long at) {
        remove(alarmId);
        list.add(new Entry(alarmId, at));
    }

    public boolean remove(int alarmId) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).alarmId == alarmId) { list.remove(i); return true; }
        return false;
    }

    public Entry get(int alarmId) {
        for (Entry e : list) if (e.alarmId == alarmId) return e;
        return null;
    }

    /** 到点时核对：这条贪睡还在、时刻对得上（±90 秒）。和任何全局时刻无关。 */
    public boolean matches(int alarmId, long at) {
        Entry e = get(alarmId);
        return e != null && Math.abs(e.at - at) <= 90_000L;
    }

    /** 清掉过期太久的（开机后发现错过的贪睡由调用方补响）。返回清掉的条数。 */
    public int purge(long now) {
        int n = 0;
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).at < now - LATE_MS) { list.remove(i); n++; }
        return n;
    }

    public String serialize() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : list) { if (sb.length() > 0) sb.append(','); sb.append(e.alarmId).append(':').append(e.at); }
        return sb.toString();
    }

    public static SnoozeBook parse(String s) {
        SnoozeBook b = new SnoozeBook();
        if (s == null) return b;
        for (String p : s.split(",")) {
            int k = p.indexOf(':');
            if (k <= 0) continue;
            try { b.put(Integer.parseInt(p.substring(0, k).trim()), Long.parseLong(p.substring(k + 1).trim())); }
            catch (Exception ignored) { }
        }
        return b;
    }
}
