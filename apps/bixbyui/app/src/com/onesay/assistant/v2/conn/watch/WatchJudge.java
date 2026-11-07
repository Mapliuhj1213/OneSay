package com.onesay.assistant.v2.conn.watch;

import java.util.ArrayList;
import java.util.List;

/**
 * [conn/watch] 判定逻辑（纯函数）：拿这次的搜索结果和状态比，决定「有没有变化、要不要现在通知」。
 *
 * <h3>规则</h3>
 * <ol>
 *   <li>搜索失败 / 结果为空 → 不算变化，不通知，记失败（连续失败由调用方决定要不要告诉用户「监控坏了」）；</li>
 *   <li>第一次成功运行 = 建基线：把当前结果都记成「见过」，<b>不通知</b>；</li>
 *   <li>新结果 = 规范化链接没见过、且满足关键词过滤；{@link WatchSpec#MODE_CHANGE} 下已见链接的标题指纹变了也算；</li>
 *   <li>新结果先进「待通知」；距上次通知不足 notifyGap 就继续攒（{@link Verdict#HOLD}），够了才 {@link Verdict#NOTIFY}；</li>
 *   <li>所有这次出现的链接都更新进「见过」（滚动 300 条），排名抖动不会让老结果重新算新。</li>
 * </ol>
 * 会就地修改传入的 {@link WatchState}。
 */
public final class WatchJudge {

    private WatchJudge() {}

    public enum Outcome { ERROR, BASELINE, NO_CHANGE, HOLD, NOTIFY }

    public static final class Verdict {
        public final Outcome outcome;
        /** NOTIFY 时 = 本次要通知的全部条目（含之前攒的）；HOLD 时 = 本次新增的。 */
        public final List<WatchItem> items;
        public final String reason;

        Verdict(Outcome outcome, List<WatchItem> items, String reason) {
            this.outcome = outcome;
            this.items = items;
            this.reason = reason;
        }

        public boolean notifyNow() {
            return outcome == Outcome.NOTIFY;
        }
    }

    /**
     * @param results 本次搜索结果；null = 搜索失败
     * @param error   失败原因（results 为 null 时）
     */
    public static Verdict judge(WatchSpec spec, WatchState st, List<WatchItem> results, String error, long now) {
        st.lastRunAt = now;
        if (results == null || results.isEmpty()) {
            st.failures++;
            st.lastError = results == null ? (error == null ? "搜索失败" : error) : "没有搜索结果";
            return new Verdict(Outcome.ERROR, new ArrayList<WatchItem>(), st.lastError);
        }
        st.failures = 0;
        st.lastError = "";
        st.lastOkAt = now;
        if (!st.baselined) {
            for (WatchItem it : results) st.remember(it.key(), it.titleHash());
            st.baselined = true;
            return new Verdict(Outcome.BASELINE, new ArrayList<WatchItem>(), "已建立基线（" + results.size() + " 条），之后有新结果才通知");
        }
        List<WatchItem> fresh = new ArrayList<WatchItem>();
        for (WatchItem it : results) {
            String k = it.key();
            if (k.length() == 0) continue;
            String prev = st.seen.get(k);
            boolean isNew = prev == null && it.mentionsAny(spec.keywords);
            boolean changed = prev != null && WatchSpec.MODE_CHANGE.equals(spec.mode) && !prev.equals(it.titleHash()) && it.mentionsAny(spec.keywords);
            if (isNew || changed) fresh.add(it);
            st.remember(k, it.titleHash());
        }
        for (WatchItem it : fresh) st.addPending(it);
        if (st.pending.isEmpty()) return new Verdict(Outcome.NO_CHANGE, fresh, "没有新变化");
        if (st.lastNotifyAt > 0 && now - st.lastNotifyAt < spec.notifyGapMs) {
            return new Verdict(Outcome.HOLD, fresh, "有 " + st.pending.size() + " 条新结果，离上次通知太近，先攒着");
        }
        List<WatchItem> all = new ArrayList<WatchItem>(st.pending);
        st.pending.clear();
        st.lastNotifyAt = now;
        return new Verdict(Outcome.NOTIFY, all, all.size() + " 条新结果");
    }

    /** 到期了吗（禁用的永远不到期）。 */
    public static boolean due(WatchSpec spec, WatchState st, long now) {
        if (!spec.enabled) return false;
        if (st == null || st.lastRunAt == 0) return true;
        long gap = spec.intervalMin * 60000L;
        // 连续失败退避：间隔 × 2^失败次数，最多 × 8
        int f = Math.min(3, st == null ? 0 : st.failures);
        return now - st.lastRunAt >= gap * (1L << f);
    }

    public static long nextDueAt(WatchSpec spec, WatchState st) {
        if (!spec.enabled) return Long.MAX_VALUE;
        if (st == null || st.lastRunAt == 0) return 0;
        int f = Math.min(3, st.failures);
        return st.lastRunAt + spec.intervalMin * 60000L * (1L << f);
    }

    /** 给用户 / 回调的通知文案（外部内容只给标题 + 来源域名，不夹带摘要里的任何「指令」）。 */
    public static String message(WatchSpec spec, List<WatchItem> items) {
        StringBuilder sb = new StringBuilder("「").append(spec.title).append("」有 ").append(items.size()).append(" 条新消息：");
        for (int i = 0; i < items.size() && i < 5; i++) {
            WatchItem it = items.get(i);
            String host = WatchItem.normUrl(it.url);
            int slash = host.indexOf('/');
            if (slash > 0) host = host.substring(0, slash);
            sb.append('\n').append(i + 1).append(". ").append(it.title.length() > 0 ? it.title : "(无标题)").append("（").append(host).append("）");
        }
        if (items.size() > 5) sb.append("\n……另有 ").append(items.size() - 5).append(" 条");
        return sb.toString();
    }
}
