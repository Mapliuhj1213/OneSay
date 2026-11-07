package com.onesay.assistant.v2.notice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 灵动岛消息提醒的统一模型。纯 Java，JVM 可测。
 *
 * <p>字段：来源 {@link #source}（+ 扩展来源的 {@link #sourceTag}）、优先级 {@link #priority}、标题 / 摘要、动作 {@link #actions}（≤ 3）、
 * 何时显示 {@link #showAt}、过期 {@link #expireAt}、去重键 {@link #dedupKey}（同一件事只有一条）。
 * 状态由 {@link NoticeCenter} 维护：PENDING（还没到显示时刻 / 免打扰时段里压着）→ ACTIVE（在岛上排队）→ DISMISSED / ACTED / EXPIRED / SUPPRESSED / WITHDRAWN。
 *
 * <p>WITHDRAWN（2026-10-23 BUG-I1 / I3）= 被系统撤下、<b>用户没处理过</b>：来源拉取失败 / 连接器缺失、来源开关关了。
 * 它不是去重记忆——同一个键之后再来（来源恢复、开关重开）会重新进队（没过期的才回来）；DISMISSED / ACTED 才挡住同键。
 */
public final class IslandNotice {

    /**
     * 来源。r2/island新增：PRIVACY（麦克风 / 相机正在使用）、CALL（通话 / 来电 / 未接）、NOTIFY（重要通知上岛：验证码、闹钟、导航、白名单……）、
     * SYSTEM（充电 / 低电量 / 耳机）、ERRAND（外卖 / 打车进度，可选）。只追加在末尾：库里按名字存，旧行照常读回。
     */
    public enum Source { CALLBACK, CALENDAR, MAIL, EXTERNAL, PRIVACY, CALL, NOTIFY, SYSTEM, ERRAND,
        /** ★ feat/rain-minutely：分钟级降水（「12 分钟后下雨」）。 */
        WEATHER }

    public enum Priority { LOW, NORMAL, HIGH }

    public enum State { PENDING, ACTIVE, DISMISSED, ACTED, EXPIRED, SUPPRESSED, WITHDRAWN }

    public static final int MAX_ACTIONS = 3;
    public static final int MAX_TITLE = 40, MAX_SUMMARY = 120;

    public String dedupKey = "";
    public Source source = Source.EXTERNAL;
    /** 扩展来源的 id（{@link NoticeSource#id()}）；内置来源为空也行。 */
    public String sourceTag = "";
    public Priority priority = Priority.NORMAL;
    public String title = "";
    public String summary = "";
    public List<NoticeAction> actions = new ArrayList<NoticeAction>();
    public long createdAt;
    /** 何时可以出现在岛上（0 = 立即）。 */
    public long showAt;
    /** 过了这个时刻自动移除（必须 &gt; 0；没给时由中心按来源补默认值）。 */
    public long expireAt;
    public State state = State.PENDING;
    /** 第一次出现在岛上的时刻（每日上限按它计数；0 = 还没出现过）。 */
    public long shownAt;
    /** 免打扰时段压着、要到这个时刻才放出来（0 = 没压着）。 */
    public long heldUntil;
    /** false = 不落库（依赖内存会话的提醒，例如「邮件草稿待确认」：App 被杀后会话没了，恢复出来也点不开）。 */
    public boolean persist = true;
    /** 收尾原因（日志 / 调试用）。 */
    public String note = "";
    /**
     * 计时：&gt; 0 时岛上标题后面跟一个自己走的时间（不必每秒重发提醒）。
     * {@link #countdown} = false：正计时（已过去多久，通话时长）；true：倒计时到这个时刻（计时器）。
     */
    public long chronoBase;
    public boolean countdown;
    /** 岛上的点的颜色提示：""（默认 / 高优先级红）、mic（橙）、cam（绿）、call（绿）、ok（绿）、warn（黄）。 */
    public String tone = "";
    /** ★ feat/rain-minutely：展开卡里的小柱状条（每个字符 '0'–'8' 一根；空 = 不画）。 */
    public String bars = "";

    public IslandNotice() {}

    public IslandNotice(Source source, String dedupKey, Priority priority, String title, String summary) {
        this.source = source;
        this.dedupKey = dedupKey == null ? "" : dedupKey;
        this.priority = priority == null ? Priority.NORMAL : priority;
        this.title = clip(title, MAX_TITLE);
        this.summary = clip(summary, MAX_SUMMARY);
    }

    public IslandNotice action(NoticeAction a) {
        if (a != null && actions.size() < MAX_ACTIONS) actions.add(a);
        return this;
    }

    public boolean isLive() {
        return state == State.PENDING || state == State.ACTIVE;
    }

    public List<String> actionLabels() {
        List<String> l = new ArrayList<String>();
        for (NoticeAction a : actions) l.add(a.label);
        return Collections.unmodifiableList(l);
    }

    public IslandNotice copy() {
        IslandNotice n = new IslandNotice();
        n.dedupKey = dedupKey; n.source = source; n.sourceTag = sourceTag; n.priority = priority; n.title = title; n.summary = summary;
        n.actions = new ArrayList<NoticeAction>(actions); n.createdAt = createdAt; n.showAt = showAt; n.expireAt = expireAt;
        n.state = state; n.shownAt = shownAt; n.heldUntil = heldUntil; n.persist = persist; n.note = note;
        n.chronoBase = chronoBase; n.countdown = countdown; n.tone = tone; n.bars = bars;
        return n;
    }

    static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("[\\u0000-\\u001f\\u200b-\\u200f\\u202a-\\u202e\\u2066-\\u2069]", " ").trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("k", dedupKey).put("src", source.name()).put("tag", sourceTag).put("p", priority.name()).put("t", title).put("s", summary)
                    .put("c", createdAt).put("show", showAt).put("exp", expireAt).put("st", state.name()).put("shown", shownAt)
                    .put("held", heldUntil).put("note", note);
            if (chronoBase > 0) o.put("chrono", chronoBase).put("cd", countdown);
            if (tone != null && tone.length() > 0) o.put("tone", tone);
            if (bars != null && bars.length() > 0) o.put("bars", bars);
            JSONArray a = new JSONArray();
            for (NoticeAction x : actions) a.put(x.toJson());
            o.put("a", a);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public static IslandNotice fromJson(String s) {
        try {
            JSONObject o = new JSONObject(s);
            IslandNotice n = new IslandNotice();
            n.dedupKey = o.optString("k", "");
            n.source = Source.valueOf(o.optString("src", "EXTERNAL"));
            n.sourceTag = o.optString("tag", "");
            n.priority = Priority.valueOf(o.optString("p", "NORMAL"));
            n.title = o.optString("t", "");
            n.summary = o.optString("s", "");
            n.createdAt = o.optLong("c", 0L);
            n.showAt = o.optLong("show", 0L);
            n.expireAt = o.optLong("exp", 0L);
            n.state = State.valueOf(o.optString("st", "PENDING"));
            n.shownAt = o.optLong("shown", 0L);
            n.heldUntil = o.optLong("held", 0L);
            n.note = o.optString("note", "");
            n.chronoBase = o.optLong("chrono", 0L);
            n.countdown = o.optBoolean("cd", false);
            n.tone = o.optString("tone", "");
            n.bars = o.optString("bars", "");
            JSONArray a = o.optJSONArray("a");
            if (a != null) for (int i = 0; i < a.length() && n.actions.size() < MAX_ACTIONS; i++) {
                NoticeAction x = NoticeAction.fromJson(a.optJSONObject(i));
                if (x != null) n.actions.add(x);
            }
            return n.dedupKey.length() == 0 ? null : n;
        } catch (Exception e) {
            return null;
        }
    }

    @Override public String toString() {
        return "notice[" + dedupKey + " " + source + " " + priority + " " + state + "]";
    }
}
