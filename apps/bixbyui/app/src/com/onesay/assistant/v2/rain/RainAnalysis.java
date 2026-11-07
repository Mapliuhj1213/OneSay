package com.onesay.assistant.v2.rain;

import java.util.ArrayList;
import java.util.List;

/**
 * [rain] 从 24 个 5 分钟点算出「X 分钟后开始下」「约 X 分钟后停」「下下停停」（纯 Java，JVM 全测）。
 *
 * <h3>口径</h3>
 * <ul>
 *   <li>precip &gt; 0 = 这 5 分钟有雨 / 雪（用户定的阈值）；</li>
 *   <li>第 i 个点覆盖 [fxTime, fxTime + 5 min)。「现在」落在哪个点里就看哪个点；已经整段过去的点丢掉；</li>
 *   <li>分钟数 = 事件点起点 − 现在（向上取整到分钟，最小 0）；</li>
 *   <li>雨还是雪：看第一个有降水的点的 type；</li>
 *   <li>强度（小 / 中 / 大 / 暴）：5 分钟量 ×12 折成小时雨强，按 &lt;2.5 / &lt;8 / &lt;16 / 以上 分（雪：&lt;1 / &lt;3 / 以上）。这是近似，只用来说话。</li>
 * </ul>
 */
public final class RainAnalysis {

    public enum State {
        /** 未来两小时都没有降水。 */
        DRY,
        /** 现在没下，之后会下（{@link #startInMin}）。 */
        STARTS,
        /** 现在在下，两小时内会停（{@link #stopInMin}）。 */
        STOPS,
        /** 现在在下，两小时内都不停。 */
        CONTINUES
    }

    public final State state;
    /** true = 雪（第一个有降水的点是 snow）。 */
    public final boolean snow;
    /** STARTS：几分钟后开始；其余 -1。 */
    public final int startInMin;
    /** STOPS：几分钟后（第一次）停；STARTS 时若两小时内也会停 = 那个时刻；其余 -1。 */
    public final int stopInMin;
    /** 停了之后两小时内又会下（下下停停）。 */
    public final boolean intermittent;
    /** 最后一个有降水的点结束是几分钟后（两小时窗口内；DRY = -1；CONTINUES = 窗口末尾）。 */
    public final int lastWetEndMin;
    /** 现在（或即将开始那段）的最大 5 分钟量（mm），用来说「小雨 / 大雨」。 */
    public final double peak;
    /** 窗口覆盖到几分钟后（最后一个点的结束）。 */
    public final int horizonMin;
    /** 柱状条：剩余每个点的 5 分钟量（mm），从现在所在的点起。 */
    public final double[] bars;
    /** 事件的绝对时刻（epoch ms）：STARTS = 开始时刻，STOPS = 停的时刻；其余 0。岛上倒计时用。 */
    public final long eventAt;

    RainAnalysis(State state, boolean snow, int startInMin, int stopInMin, boolean intermittent, int lastWetEndMin, double peak,
                 int horizonMin, double[] bars, long eventAt) {
        this.state = state;
        this.snow = snow;
        this.startInMin = startInMin;
        this.stopInMin = stopInMin;
        this.intermittent = intermittent;
        this.lastWetEndMin = lastWetEndMin;
        this.peak = peak;
        this.horizonMin = horizonMin;
        this.bars = bars;
        this.eventAt = eventAt;
    }

    public static final long STEP_MS = 5L * 60000L;

    /** 分析。forecast 为空 / 点都已过期 → null（数据太旧，当作拿不到）。 */
    public static RainAnalysis of(RainForecast f, long now) {
        if (f == null || f.points.isEmpty()) return null;
        List<RainForecast.Point> pts = new ArrayList<RainForecast.Point>();
        for (RainForecast.Point p : f.points) if (p.at + STEP_MS > now) pts.add(p);
        if (pts.isEmpty()) return null;
        double[] bars = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) bars[i] = pts.get(i).precip;
        RainForecast.Point last = pts.get(pts.size() - 1);
        int horizon = minutes(last.at + STEP_MS - now);
        int firstWet = -1;
        for (int i = 0; i < pts.size(); i++) if (pts.get(i).wet()) { firstWet = i; break; }
        if (firstWet < 0) return new RainAnalysis(State.DRY, false, -1, -1, false, -1, 0, horizon, bars, 0);
        boolean snow = pts.get(firstWet).snow;
        int lastWet = -1;
        for (int i = pts.size() - 1; i >= 0; i--) if (pts.get(i).wet()) { lastWet = i; break; }
        int lastWetEnd = lastWet == pts.size() - 1 ? horizon : minutes(pts.get(lastWet).at + STEP_MS - now);
        // 从 firstWet 起的第一个干点 = 这一阵停的时刻
        int firstDryAfter = -1;
        for (int i = firstWet + 1; i < pts.size(); i++) if (!pts.get(i).wet()) { firstDryAfter = i; break; }
        boolean again = false;
        if (firstDryAfter >= 0) for (int i = firstDryAfter + 1; i < pts.size(); i++) if (pts.get(i).wet()) { again = true; break; }
        double peak = 0;
        int end = firstDryAfter < 0 ? pts.size() : firstDryAfter;
        for (int i = firstWet; i < end; i++) peak = Math.max(peak, pts.get(i).precip);
        if (firstWet == 0) {
            if (firstDryAfter < 0) {
                return new RainAnalysis(State.CONTINUES, snow, -1, -1, false, lastWetEnd, peak, horizon, bars, 0);
            }
            long at = pts.get(firstDryAfter).at;
            return new RainAnalysis(State.STOPS, snow, -1, minutes(at - now), again, lastWetEnd, peak, horizon, bars, at);
        }
        long at = pts.get(firstWet).at;
        int stop = firstDryAfter < 0 ? -1 : minutes(pts.get(firstDryAfter).at - now);
        return new RainAnalysis(State.STARTS, snow, minutes(at - now), stop, again, lastWetEnd, peak, horizon, bars, at);
    }

    static int minutes(long ms) {
        if (ms <= 0) return 0;
        return (int) ((ms + 59999L) / 60000L);
    }

    public boolean wetNow() {
        return state == State.STOPS || state == State.CONTINUES;
    }

    /** 「雨」或「雪」。 */
    public String what() {
        return snow ? "雪" : "雨";
    }

    /** 「小雨 / 中雨 / 大雨 / 暴雨」（雪：小雪 / 中雪 / 大雪）。 */
    public String intensity() {
        double rate = peak * 12.0;
        if (snow) return rate < 1.0 ? "小雪" : (rate < 3.0 ? "中雪" : "大雪");
        if (rate < 2.5) return "小雨";
        if (rate < 8.0) return "中雨";
        if (rate < 16.0) return "大雨";
        return "暴雨";
    }

    /** 下一个状态变化在几分钟后（STARTS = 开始，STOPS = 停；其余 -1）。调度用。 */
    public int changeInMin() {
        if (state == State.STARTS) return startInMin;
        if (state == State.STOPS) return stopInMin;
        return -1;
    }

    /** 柱状条量化成 0–8 级（0 = 没有；1–8 按 5 分钟量开方，0.5 mm 及以上满格）。岛上画条用，也进提醒库（字符串 "0123…"）。 */
    public static String barLevels(double[] bars) {
        if (bars == null) return "";
        StringBuilder sb = new StringBuilder();
        for (double v : bars) {
            int lv;
            if (v <= 0) lv = 0;
            else {
                lv = (int) Math.ceil(Math.sqrt(Math.min(1.0, v / 0.5)) * 8.0);   // 开方：毛毛雨也看得见
                if (lv < 1) lv = 1;
                if (lv > 8) lv = 8;
            }
            sb.append((char) ('0' + lv));
        }
        return sb.toString();
    }

    @Override public String toString() {
        return "rain[" + state + (snow ? " snow" : "") + " start=" + startInMin + " stop=" + stopInMin + (intermittent ? " intermittent" : "")
                + " lastEnd=" + lastWetEndMin + " peak=" + peak + " horizon=" + horizonMin + "]";
    }
}
