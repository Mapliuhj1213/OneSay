package com.onesay.plan.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * [borrow-1112] 待办强提醒（纯 Java）：提醒记录本（谁正在响、谁在稍后、谁最近提醒过）+ 响铃策略 + 按键映射 + 给助手的查询结果。
 * 思路来源：涛声 TaoSheng（MIT，https://github.com/0xCoach/TaoSheng）——待办到点像闹钟一样持续响铃、锁屏整屏提醒，
 * 音量键稍后、电源键停止；说一句「发了」就把对应待办标为完成。代码自写，没有复制它的源码。
 *
 * <ul>
 *   <li>强提醒的待办（todos.strong=1）到点：持续响铃 + 整屏页面；音量键 = 稍后（默认 {@link #DEFAULT_SNOOZE_MIN} 分钟），
 *       电源键或「停止」= 停止，「完成」= 勾掉待办。</li>
 *   <li>没人管：响 {@link #RING_TIMEOUT_MS} 后自动稍后，最多 {@link #MAX_AUTO_SNOOZE} 次，之后停止并留一条通知（不会无限循环）；每条各自计时、各自计次。</li>
 *   <li>并表同时提醒（2026-11-12 用户决定）：同一时刻多条到点、或一条在响时另一条到点，合并成一次响铃，整屏页列出所有在响的，
 *       每条有自己的「完成」；音量键 = 全部稍后，电源键 / 停止 = 全部停止；全部完成后自动停铃，没完成的继续响。</li>
 *   <li>普通待办提醒发通知时也记一笔（state=notified），这样助手问「刚才提醒的是哪条」时两种都能找到。</li>
 * </ul>
 * 记录本只存 id / 标题 / 时刻 / 状态，存在计划 App 私有设置里；最多 {@link #MAX_ENTRIES} 条、只留 {@link #KEEP_MS} 内的。
 */
public final class StrongAlert {
    private StrongAlert() {}

    public static final int DEFAULT_SNOOZE_MIN = 10;
    public static final long RING_TIMEOUT_MS = 5L * 60000L;
    public static final int MAX_AUTO_SNOOZE = 3;
    public static final long RECENT_MS = 30L * 60000L;
    public static final long KEEP_MS = 24L * 3600000L;
    public static final int MAX_ENTRIES = 50;

    public static final String RINGING = "ringing", SNOOZED = "snoozed", NOTIFIED = "notified", STOPPED = "stopped", DONE = "done", MISSED = "missed";

    /** 一条提醒记录。 */
    public static final class Entry {
        public long todoId;
        public String title = "";
        public long dueAt;
        public boolean strong;
        public String state = NOTIFIED;
        /** 这一轮第一次提醒的时刻。 */
        public long firstAt;
        /** 最近一次提醒（响铃开始 / 发通知）的时刻。 */
        public long remindedAt;
        /** 最近一次状态变化的时刻。 */
        public long changedAt;
        public long snoozeUntil;
        public int autoSnoozes;

        public boolean active() { return RINGING.equals(state) || SNOOZED.equals(state); }

        JSONObject toJson() throws Exception {
            return new JSONObject().put("id", todoId).put("title", title).put("due", dueAt).put("strong", strong).put("state", state)
                    .put("first", firstAt).put("at", remindedAt).put("chg", changedAt).put("snz", snoozeUntil).put("auto", autoSnoozes);
        }

        static Entry fromJson(JSONObject o) {
            Entry e = new Entry();
            e.todoId = o.optLong("id");
            e.title = o.optString("title", "");
            e.dueAt = o.optLong("due");
            e.strong = o.optBoolean("strong", false);
            e.state = o.optString("state", NOTIFIED);
            e.firstAt = o.optLong("first");
            e.remindedAt = o.optLong("at");
            e.changedAt = o.optLong("chg");
            e.snoozeUntil = o.optLong("snz");
            e.autoSnoozes = o.optInt("auto");
            return e;
        }

        @Override public String toString() { return "todo#" + todoId + ":" + state; }
    }

    /** 记录本（按待办 id 一条）。 */
    public static final class Book {
        public final List<Entry> entries = new ArrayList<Entry>();

        public Entry get(long id) {
            for (Entry e : entries) if (e.todoId == id) return e;
            return null;
        }

        Entry touch(long id, String title, long due, long now) {
            Entry e = get(id);
            if (e == null) { e = new Entry(); e.todoId = id; e.firstAt = now; entries.add(e); }
            else if (!e.active()) { e.firstAt = now; e.autoSnoozes = 0; }   // 新的一轮
            if (title != null) e.title = title;
            if (due > 0) e.dueAt = due;
            return e;
        }

        /** 普通提醒发了通知。正在响 / 稍后中的强提醒不被它覆盖。 */
        public Entry notified(long id, String title, long due, long now) {
            Entry e = get(id);
            if (e != null && e.active()) return e;
            e = touch(id, title, due, now);
            e.strong = false;
            e.state = NOTIFIED;
            e.remindedAt = now;
            e.changedAt = now;
            e.snoozeUntil = 0;
            return e;
        }

        /** 强提醒开始响（第一次到点或稍后到点）。 */
        public Entry ring(long id, String title, long due, long now) {
            Entry e = touch(id, title, due, now);
            e.strong = true;
            e.state = RINGING;
            e.remindedAt = now;
            e.changedAt = now;
            e.snoozeUntil = 0;
            return e;
        }

        /** 稍后：auto = 没人管自动稍后（计次）。 */
        public Entry snooze(long id, long now, int minutes, boolean auto) {
            Entry e = get(id);
            if (e == null) return null;
            e.state = SNOOZED;
            e.snoozeUntil = now + Math.max(1, minutes) * 60000L;
            e.changedAt = now;
            if (auto) e.autoSnoozes++;
            return e;
        }

        public Entry stop(long id, long now, String state) {
            Entry e = get(id);
            if (e == null) return null;
            e.state = state;
            e.snoozeUntil = 0;
            e.changedAt = now;
            return e;
        }

        /** 现在正在响的那条（有多条时取最早到点的；没有返回 null）。 */
        public Entry ringing() {
            List<Entry> l = ringingAll();
            return l.isEmpty() ? null : l.get(0);
        }

        /**
         * [borrow-1112] 并表同时提醒：所有正在响的（同一次响铃、同一个整屏页），按到点（开始响）时刻先后排，同一时刻按截止时间、再按 id。
         */
        public List<Entry> ringingAll() {
            List<Entry> l = new ArrayList<Entry>();
            for (Entry e : entries) if (RINGING.equals(e.state)) l.add(e);
            Collections.sort(l, BY_RING_TIME);
            return l;
        }

        /** 还有没有正在响的（没有了就停铃、关整屏页）。 */
        public boolean anyRinging() {
            for (Entry e : entries) if (RINGING.equals(e.state)) return true;
            return false;
        }

        /** 整屏页上某一条的「完成」：只停这一条，其余继续响。返回剩下还在响的。 */
        public List<Entry> complete(long id, long now) {
            Entry e = get(id);
            if (e != null && RINGING.equals(e.state)) stop(id, now, DONE);
            return ringingAll();
        }

        /** 音量键 / 「稍后」：正在响的全部稍后（auto = 没人管自动稍后，每条各自计次）。返回被稍后的。 */
        public List<Entry> snoozeAll(long now, int minutes, boolean auto) {
            List<Entry> l = ringingAll();
            for (Entry e : l) snooze(e.todoId, now, minutes, auto);
            return l;
        }

        /** 电源键 / 「停止」：正在响的全部停止。返回被停的。 */
        public List<Entry> stopAll(long now, String state) {
            List<Entry> l = ringingAll();
            for (Entry e : l) stop(e.todoId, now, state);
            return l;
        }

        /** 每条各自的「没人管」计时：开始响之后 {@link #RING_TIMEOUT_MS} 到了的那几条（其余继续响）。 */
        public List<Entry> timedOut(long now) {
            List<Entry> l = new ArrayList<Entry>();
            for (Entry e : ringingAll()) if (e.remindedAt + RING_TIMEOUT_MS <= now) l.add(e);
            return l;
        }

        /** 下一条「没人管」到时的时刻；没有在响的返回 -1。 */
        public long nextTimeoutAt() {
            long best = -1;
            for (Entry e : entries) if (RINGING.equals(e.state) && (best < 0 || e.remindedAt + RING_TIMEOUT_MS < best)) best = e.remindedAt + RING_TIMEOUT_MS;
            return best;
        }

        /** 进程被杀后留在记录本里的「正在响」（不在 live 里）记为已停止，免得助手看到一堆假的 ringing。返回改了几条。 */
        public int clearStaleRinging(java.util.Collection<Long> live, long now) {
            int n = 0;
            for (Entry e : entries) if (RINGING.equals(e.state) && (live == null || !live.contains(e.todoId))) { stop(e.todoId, now, STOPPED); n++; }
            return n;
        }

        /** 稍后到点：(from, to] 内该再响的。 */
        public List<Entry> snoozesDue(long from, long to) {
            List<Entry> l = new ArrayList<Entry>();
            for (Entry e : entries) if (SNOOZED.equals(e.state) && e.snoozeUntil > from && e.snoozeUntil <= to) l.add(e);
            return l;
        }

        /** 下一个稍后到点时刻（&gt; now）；没有返回 -1。 */
        public long nextSnoozeAt(long now) {
            long best = -1;
            for (Entry e : entries) if (SNOOZED.equals(e.state) && e.snoozeUntil > now && (best < 0 || e.snoozeUntil < best)) best = e.snoozeUntil;
            return best;
        }

        /** 过期清理：不活跃、最近一次变化早于 KEEP_MS 的删掉；超出条数删最旧的。返回删了几条。 */
        public int prune(long now) {
            int n = 0;
            for (int i = entries.size() - 1; i >= 0; i--) {
                Entry e = entries.get(i);
                if (!e.active() && Math.max(e.changedAt, e.remindedAt) < now - KEEP_MS) { entries.remove(i); n++; }
            }
            while (entries.size() > MAX_ENTRIES) {
                int oldest = 0;
                for (int i = 1; i < entries.size(); i++) if (entries.get(i).changedAt < entries.get(oldest).changedAt) oldest = i;
                entries.remove(oldest);
                n++;
            }
            return n;
        }

        public String serialize() {
            JSONArray a = new JSONArray();
            for (Entry e : entries) {
                try { a.put(e.toJson()); } catch (Exception ignored) { }
            }
            return a.toString();
        }

        public static Book parse(String s) {
            Book b = new Book();
            if (s == null || s.length() == 0) return b;
            try {
                JSONArray a = new JSONArray(s);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null && o.optLong("id") > 0) b.entries.add(Entry.fromJson(o));
                }
            } catch (Exception ignored) { }
            return b;
        }
    }

    /** 正在响的排序：开始响的时刻先后 → 截止时间 → id。 */
    static final Comparator<Entry> BY_RING_TIME = new Comparator<Entry>() {
        @Override public int compare(Entry x, Entry y) {
            if (x.remindedAt != y.remindedAt) return x.remindedAt < y.remindedAt ? -1 : 1;
            if (x.dueAt != y.dueAt) return x.dueAt < y.dueAt ? -1 : 1;
            return x.todoId < y.todoId ? -1 : x.todoId > y.todoId ? 1 : 0;
        }
    };

    /** [borrow-1112] 合并后的通知标题：一条 = 待办标题；多条 = 「N 条待办到点了」。 */
    public static String ringTitle(List<Entry> ringing) {
        if (ringing == null || ringing.isEmpty()) return "";
        if (ringing.size() == 1) return ringing.get(0).title;
        return ringing.size() + " 条待办到点了";
    }

    /** 合并后的通知正文：一条 = 操作说明；多条 = 各条标题用「、」连起来。 */
    public static String ringText(List<Entry> ringing) {
        if (ringing == null || ringing.size() <= 1) return "强提醒 · 音量键稍后，电源键停止";
        StringBuilder sb = new StringBuilder();
        for (Entry e : ringing) { if (sb.length() > 0) sb.append("、"); sb.append(e.title); }
        return sb.toString();
    }

    /** 响铃超时（没人管）怎么办。 */
    public enum Timeout { SNOOZE, STOP }

    public static Timeout onTimeout(Entry e) {
        return e != null && e.autoSnoozes < MAX_AUTO_SNOOZE ? Timeout.SNOOZE : Timeout.STOP;
    }

    /** 整屏页按键：音量键 = 稍后、电源键（屏幕被按灭）= 停止；其它键不处理。 */
    public enum Key { SNOOZE, STOP, NONE }

    /** KeyEvent 键码：24 音量+、25 音量-、164 静音、26 电源。 */
    public static Key key(int keyCode) {
        if (keyCode == 24 || keyCode == 25 || keyCode == 164) return Key.SNOOZE;
        if (keyCode == 26) return Key.STOP;
        return Key.NONE;
    }

    /** 稍后几分钟（设置值，坏值回默认，1–60）。 */
    public static int snoozeMin(int v) { return v < 1 || v > 60 ? DEFAULT_SNOOZE_MIN : v; }

    /** 这一行待办要不要强提醒。 */
    public static boolean isStrong(Row todo) { return todo != null && todo.l("strong") != 0; }

    // ------------------------------------------------------------------ 给助手

    /**
     * 「当前正在提醒 / 最近 windowMs 内提醒过」的待办（助手 listRemindedTasks 用）。
     * todos：id → 当前行（已删的不在 map 里）；已完成的、已删的不返回。正在响的排最前（可能有多条，按到点先后），其余按最近提醒时刻倒序。
     * 多条同时在响时都返回 state=ringing，助手听到「做完了」对得上不止一条就会追问是哪一条。
     */
    public static List<Entry> recent(Book b, Map<Long, Row> todos, long now, long windowMs) {
        List<Entry> l = new ArrayList<Entry>();
        for (Entry e : b.entries) {
            Row r = todos == null ? null : todos.get(e.todoId);
            if (r == null || r.l("done_at") != 0) continue;
            if (e.active() || e.remindedAt >= now - windowMs) l.add(e);
        }
        Collections.sort(l, new Comparator<Entry>() {
            @Override public int compare(Entry x, Entry y) {
                int rx = RINGING.equals(x.state) ? 0 : 1, ry = RINGING.equals(y.state) ? 0 : 1;
                if (rx != ry) return rx - ry;
                if (rx == 0) return BY_RING_TIME.compare(x, y);
                return x.remindedAt > y.remindedAt ? -1 : x.remindedAt < y.remindedAt ? 1 : 0;
            }
        });
        return l;
    }

    /** 查询结果 JSON（data 字段）：id / title / due_at / state / reminded_at / first_at / strong / snooze_until。 */
    public static String recentJson(List<Entry> l, Map<Long, Row> todos) {
        JSONArray a = new JSONArray();
        for (Entry e : l) {
            Row r = todos == null ? null : todos.get(e.todoId);
            try {
                a.put(new JSONObject().put("id", e.todoId).put("title", r != null ? r.s("title") : e.title)
                        .put("due_at", r != null ? r.l("due_at") : e.dueAt).put("state", e.state).put("reminded_at", e.remindedAt)
                        .put("first_at", e.firstAt).put("strong", e.strong).put("snooze_until", e.snoozeUntil));
            } catch (Exception ignored) { }
        }
        return a.toString();
    }

    /** 助手「按 id 标记完成」能不能做：只认正在提醒 / 最近提醒过的那几条（防止一句「发了」勾错别的待办）。 */
    public static boolean completable(Book b, Map<Long, Row> todos, long id, long now, long windowMs) {
        for (Entry e : recent(b, todos, now, windowMs)) if (e.todoId == id) return true;
        return false;
    }

    /** 窗口分钟数：默认 30，限 1–120。 */
    public static long windowMs(int minutes) {
        int m = minutes <= 0 ? 30 : Math.min(120, minutes);
        return m * 60000L;
    }

    /** 整屏页上的到期描述：「截止 14:30」；没有截止就空。 */
    public static String dueText(long dueAt, java.util.TimeZone tz) {
        if (dueAt <= 0) return "";
        java.util.Calendar c = java.util.Calendar.getInstance(tz);
        c.setTimeInMillis(dueAt);
        int h = c.get(java.util.Calendar.HOUR_OF_DAY), m = c.get(java.util.Calendar.MINUTE);
        return "截止 " + (h < 10 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m;
    }
}
