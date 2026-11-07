package com.onesay.assistant.v2.rain;

import com.onesay.assistant.v2.notice.IslandNotice;
import com.onesay.assistant.v2.notice.NoticeAction;

/**
 * [rain] 后台检查节奏 + 灵动岛提醒长什么样（纯 Java，JVM 全测）。
 *
 * <h3>节奏（{@link #nextDelayMs}）</h3>
 * <ul>
 *   <li>默认每 15 分钟；</li>
 *   <li>正在下，或预计 30 分钟内开始 / 停 → 每 5 分钟；</li>
 *   <li>息屏 → 30 分钟（Note 8 耗电；息屏时岛也看不见）；</li>
 *   <li>失败退避：连续失败 n 次 → 5 × 2^(n−1) 分钟，封顶 60 分钟（不管亮不亮屏）；没 key / 定位 / 不在覆盖范围这类「不是网络的错」直接 60 分钟。</li>
 * </ul>
 * 估算（文档里也有）：亮屏 16 h / 天每 15 分钟 = 64 次 + 息屏 8 h 每 30 分钟 = 16 次 ≈ 80 次 / 天 ≈ 2.4k / 月；
 * 下雨天亮屏全程每 5 分钟 = 192 + 16 ≈ 210 次 / 天。就算整月天天下雨也 ≈ 6.3k / 月，免费额度 5 万 / 月。
 *
 * <h3>岛（{@link #notice}）</h3>
 * 只有<b>未来 60 分钟内要开始或停止</b>才上岛：「12 分钟后下雨」「雨约 40 分钟后停」；一直下 / 不下 → 不放（已放的由同步撤下 = 自动收起）。
 * 点开 = summary + 2 小时柱状条。去重键带「这一阵」的编号（{@link Episode}），用户点掉这一阵不再出现，下一阵照常提醒。
 */
public final class RainPolicy {

    private RainPolicy() {}

    public static final long MIN = 60000L;
    public static final long BASE_MS = 15L * MIN, FAST_MS = 5L * MIN, SCREEN_OFF_MS = 30L * MIN, MAX_BACKOFF_MS = 60L * MIN;
    /** 预计多少分钟内变化就加密到 5 分钟。 */
    public static final int FAST_WITHIN_MIN = 30;
    /** 岛只提醒多少分钟内的变化。 */
    public static final int ISLAND_WITHIN_MIN = 60;

    /**
     * 下一次联网检查在多久之后。
     *
     * @param a        最近一次分析（null = 还没有数据）
     * @param screenOn 亮屏
     * @param failures 连续失败次数（0 = 上次成功）
     * @param hardFail 上次失败不是网络问题（没 key / 没定位 / 没覆盖 / key 被拒）
     */
    public static long nextDelayMs(RainAnalysis a, boolean screenOn, int failures, boolean hardFail) {
        if (failures > 0) {
            if (hardFail) return MAX_BACKOFF_MS;
            long d = FAST_MS << Math.min(failures - 1, 4);
            return Math.min(d, MAX_BACKOFF_MS);
        }
        if (!screenOn) return SCREEN_OFF_MS;
        if (a == null) return BASE_MS;
        if (a.wetNow()) return FAST_MS;
        int c = a.changeInMin();
        if (c >= 0 && c <= FAST_WITHIN_MIN) return FAST_MS;
        return BASE_MS;
    }

    /** 每月请求量估算（亮屏小时 / 天、下雨小时 / 天）。文档 + 控制台说明用。 */
    public static int monthlyEstimate(int screenOnHours, int rainyScreenOnHours, int days) {
        int dry = Math.max(0, screenOnHours - rainyScreenOnHours);
        int perDay = dry * 4 + rainyScreenOnHours * 12 + (24 - screenOnHours) * 2;
        return perDay * days;
    }

    /** 「这一阵」：同一种事件（开始 / 停）连着出现时编号不变；事件消失或换了种类就换号。 */
    public static final class Episode {
        String kind = "";
        long id;

        /** 喂这次的事件种类（"start" / "stop" / ""），返回当前这一阵的编号（没有事件 = 0）。 */
        public long on(String k, long now) {
            if (k == null || k.length() == 0) {
                kind = "";
                id = 0;
                return 0;
            }
            if (!k.equals(kind) || id == 0) {
                kind = k;
                id = now;
            }
            return id;
        }

        public String kind() {
            return kind;
        }

        public long id() {
            return id;
        }

        /** 恢复（App 重启后从 prefs 读回）。 */
        public void restore(String k, long i) {
            kind = k == null ? "" : k;
            id = i;
        }
    }

    /** 这次分析该上岛的事件种类："start" / "stop" / ""（不上岛）。 */
    public static String eventKind(RainAnalysis a) {
        if (a == null) return "";
        if (a.state == RainAnalysis.State.STARTS && a.startInMin <= ISLAND_WITHIN_MIN) return "start";
        if (a.state == RainAnalysis.State.STOPS && a.stopInMin <= ISLAND_WITHIN_MIN) return "stop";
        return "";
    }

    /** 胶囊标题：「12 分钟后下雨」「雨约 40 分钟后停」「马上要下雨了」「雨马上停」。 */
    public static String title(RainAnalysis a) {
        String w = a.what();
        if (a.state == RainAnalysis.State.STARTS) {
            return a.startInMin <= 1 ? "马上要下" + w + "了" : a.startInMin + " 分钟后下" + w;
        }
        return a.stopInMin <= 1 ? w + "马上停" : w + "约 " + a.stopInMin + " 分钟后停";
    }

    public static final String SOURCE_TAG = "rain";

    /**
     * 这次要不要上岛：要 → 提醒（去重键 rain:&lt;start|stop&gt;:&lt;这一阵编号&gt;，过期 = 事件时刻 + 10 分钟）；不要 → null。
     */
    public static IslandNotice notice(RainAnalysis a, String summary, Episode ep, long now) {
        String k = eventKind(a);
        long id = ep == null ? now : ep.on(k, now);
        if (k.length() == 0) return null;
        IslandNotice n = new IslandNotice(IslandNotice.Source.WEATHER, "rain:" + k + ":" + id, IslandNotice.Priority.NORMAL, title(a),
                summaryLine(a, summary));
        n.sourceTag = SOURCE_TAG;
        n.tone = "start".equals(k) ? "warn" : "ok";
        n.bars = RainAnalysis.barLevels(a.bars);
        n.persist = false;
        long ev = a.eventAt > 0 ? a.eventAt : now;
        n.expireAt = Math.max(ev, now) + 10L * MIN;
        n.action(new NoticeAction("知道了", NoticeAction.Kind.DISMISS, ""));
        return n;
    }

    /** 展开卡正文：summary（没有就用自己算的那句）+「未来 2 小时」。 */
    static String summaryLine(RainAnalysis a, String summary) {
        String s = RainVoice.cleanSummary(summary);
        if (s.length() == 0) s = RainVoice.core(RainVoice.Q.WILL, a).replaceAll("。$", "");
        return s;
    }
}
