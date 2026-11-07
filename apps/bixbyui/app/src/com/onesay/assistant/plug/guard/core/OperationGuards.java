/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: translated from Kotlin (core/guard/OperationGuards.kt) to Java 7; JSON evidence reduced to
 * a short map, nudge texts translated to Chinese (prompts/feedback/tools.md guard.*), read-scope windows merged
 * into one task scope. Upstream: ebb0e44.
 */
package com.onesay.assistant.plug.guard.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 每次任务一份的操作守卫。只有动作确实没有效果、重复读取没有新内容或工具连续失败时才提醒并最终叫停；
 * 新的页面或读到新内容会清掉无进展计数。
 */
public final class OperationGuards {

    /** 一条结论：提醒带 nudge（说清卡在哪 + 出路），叫停的没有。 */
    public static final class Verdict {
        public final String code;
        public final GuardRules.Severity severity;
        public final Map<String, Object> evidence;
        public final String nudge;

        Verdict(String code, GuardRules.Severity s, Map<String, Object> ev, String nudge) {
            this.code = code;
            this.severity = s;
            this.evidence = Collections.unmodifiableMap(ev);
            this.nudge = nudge;
        }

        public boolean stop() {
            return severity == GuardRules.Severity.STOP;
        }

        @Override public String toString() {
            return code + ":" + severity.wire + evidence + (nudge == null ? "" : " " + nudge);
        }
    }

    public static final String WAYS_OUT = "出路：换一条明显不同的路（别的控件、搜索、菜单或入口）；缺只有用户能决定或提供的东西就问用户；或者把已有的交出来，说清还缺什么。";

    static String lead(String code, int count, int states) {
        if ("no_progress".equals(code)) return "最近 " + count + " 个动作都没让界面发生任何变化。";
        if ("same_state".equals(code)) return "你已经第 " + count + " 次回到界面的同一个状态，没到过任何新地方。如果是有意逐个处理条目，继续即可。";
        if ("state_cycle".equals(code)) return "你在同样 " + states + " 个界面状态之间兜圈（" + count + " 圈）。";
        if ("no_new_state".equals(code)) return "连续 " + count + " 个动作都没到过新的界面状态。";
        if ("read_loop".equals(code)) return "页面没变，又读了 " + count + " 遍，读不出新内容。";
        if ("too_many_failures".equals(code)) return "连续 " + count + " 个动作失败了。";
        return "触发了操作保护（" + code + "）。";
    }

    private final Integer stepBudget;
    private String frameId;
    private Long frameHash;
    private boolean acted, effectNoted;
    private int unchanged, unchangedWarnedAt;
    private final Set<Long> seenStates = new HashSet<Long>();
    private final Map<Long, Integer> visits = new HashMap<Long, Integer>();
    private final Map<Long, Integer> visitsWarnedAt = new HashMap<Long, Integer>();
    private final ArrayDeque<Long> trail = new ArrayDeque<Long>();
    private int cycleWarnedAt, sinceNewState, sinceNewWarnedAt;
    private int rereads, rereadWarnedAt, epoch, readEpoch;
    private final Set<Long> taskReads = new HashSet<Long>();
    private int consecutiveFailures, unrecoverableFailures;
    private Verdict stop;
    private long progressVersion;

    public OperationGuards(Integer stepBudget) {
        this.stepBudget = stepBudget;
    }

    public Verdict stop() {
        return stop;
    }

    public int unchangedFrames() {
        return unchanged;
    }

    public long progressVersion() {
        return progressVersion;
    }

    private List<Verdict> verdict(String code, GuardRules.Severity sev, Map<String, Object> ev, int count, boolean waysOut, int period) {
        String nudge = null;
        if (sev == GuardRules.Severity.WARN) nudge = lead(code, count, period) + (waysOut ? " " + WAYS_OUT : "");
        Verdict v = new Verdict(code, sev, ev, nudge);
        if (sev == GuardRules.Severity.STOP) stop = v;
        List<Verdict> l = new ArrayList<Verdict>();
        l.add(v);
        return l;
    }

    private static Map<String, Object> ev(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put(k, v);
        return m;
    }

    /** 一个会改变界面的动作要做了。 */
    public void noteAction() {
        acted = true;
        effectNoted = false;
        sinceNewState++;
    }

    /** 动作报回的效果：no_effect 记一次没效果；其余清零（small_change 两样都不算 → 也清零，同 Mika）。 */
    public List<Verdict> effect(String kind) {
        effectNoted = true;
        if ("no_effect".equals(kind)) {
            unchanged++;
            return noProgress();
        }
        unchanged = 0;
        unchangedWarnedAt = 0;
        return Collections.emptyList();
    }

    private List<Verdict> noProgress() {
        GuardRules.Rung r = GuardRules.climb(unchanged, GuardRules.NO_EFFECT_LADDER, unchangedWarnedAt, true);
        if (r == null) return Collections.emptyList();
        unchangedWarnedAt = r.threshold;
        return verdict("no_progress", r.severity, ev("unchanged", unchanged), unchanged, true, 0);
    }

    /** 模型拿到了一份界面（id + 指纹）。动过之后仍一样才算没效果；还看回到同一状态、兜圈、一直没新状态。 */
    public List<Verdict> frame(String id, long hash) {
        if (id != null && id.equals(frameId)) return Collections.<Verdict>emptyList();
        boolean same = frameHash != null && frameHash == hash;
        boolean didAct = acted, noted = effectNoted;
        acted = false;
        effectNoted = false;
        frameId = id;
        frameHash = hash;
        List<Verdict> out = new ArrayList<Verdict>();
        boolean isNew = false;
        if (!same) {
            pageChanged();
            if (seenStates.add(hash)) {
                progress();
                isNew = true;
            }
            if (trail.isEmpty() || trail.peekLast() != hash) {
                trail.addLast(hash);
                while (trail.size() > 64) trail.removeFirst();
            }
        }
        if (didAct && !noted) {
            if (same) {
                unchanged++;
                out.addAll(noProgress());
            } else {
                unchanged = 0;
                unchangedWarnedAt = 0;
            }
        }
        if (didAct && !same && !isNew) {
            out.addAll(cycle());
            out.addAll(arrived(hash));
        }
        if (didAct) out.addAll(noNewState());
        return out;
    }

    private List<Verdict> arrived(long hash) {
        Integer v = visits.get(hash);
        int n = (v == null ? 1 : v) + 1;
        visits.put(hash, n);
        if (GuardRules.cycle(new ArrayList<Long>(trail)) != null) return Collections.emptyList();
        Integer w = visitsWarnedAt.get(hash);
        GuardRules.Rung r = GuardRules.climb(n, GuardRules.sameStateLadder(stepBudget), w == null ? 0 : w, false);   // 上游现版本已不按旧界面叫停：只提醒
        if (r == null) return Collections.emptyList();
        visitsWarnedAt.put(hash, r.threshold);
        return verdict("same_state", r.severity, ev("visits", n), n, true, 0);
    }

    private List<Verdict> cycle() {
        GuardRules.Cycle c = GuardRules.cycle(new ArrayList<Long>(trail));
        if (c == null) {
            cycleWarnedAt = 0;
            return Collections.emptyList();
        }
        GuardRules.Rung r = GuardRules.climb(c.rounds(), GuardRules.cycleLadder(stepBudget), cycleWarnedAt, false);   // 同上：只提醒
        if (r == null) return Collections.emptyList();
        cycleWarnedAt = r.threshold;
        Map<String, Object> e = ev("states", c.period);
        e.put("rounds", c.rounds());
        return verdict("state_cycle", r.severity, e, c.rounds(), true, c.period);
    }

    private List<Verdict> noNewState() {
        GuardRules.Rung r = GuardRules.climb(sinceNewState, GuardRules.noNewStateLadder(stepBudget), sinceNewWarnedAt, false);
        if (r == null) return Collections.emptyList();
        sinceNewWarnedAt = r.threshold;
        return verdict("no_new_state", r.severity, ev("actions", sinceNewState), sinceNewState, true, 0);
    }

    /** 一次读：fresh = 读到了之前没读到的内容（fingerprint 跨页面去重）。 */
    public List<Verdict> read(String tool, boolean fresh, Long fingerprint) {
        if (fresh) {
            if (fingerprint == null || taskReads.add(fingerprint)) progress();
            return Collections.emptyList();
        }
        if (readEpoch != epoch) {
            readEpoch = epoch;
            rereads = 0;
            rereadWarnedAt = 0;
        }
        rereads++;
        GuardRules.Rung r = GuardRules.climb(rereads, GuardRules.REREAD_LADDER, rereadWarnedAt, true);
        if (r == null) return Collections.emptyList();
        rereadWarnedAt = r.threshold;
        Map<String, Object> e = ev("tool", tool);
        e.put("rereadsOfUnchangedPage", rereads);
        return verdict("read_loop", r.severity, e, rereads, true, 0);
    }

    public void pageChanged() {
        epoch++;
    }

    private void progress() {
        progressVersion++;
        unchanged = 0;
        unchangedWarnedAt = 0;
        sinceNewState = 0;
        sinceNewWarnedAt = 0;
        if (stop != null && ("no_progress".equals(stop.code) || "read_loop".equals(stop.code))) stop = null;
    }

    /** 工具结果：ok=false 记一次失败（recoverable=false 另记）；neutral / 成功清零。 */
    public List<Verdict> result(String tool, boolean ok, boolean recoverable, String error) {
        if (ok) {
            consecutiveFailures = 0;
            unrecoverableFailures = 0;
            return Collections.emptyList();
        }
        consecutiveFailures++;
        if (!recoverable) unrecoverableFailures++;
        GuardRules.Severity s = GuardRules.failures(consecutiveFailures, unrecoverableFailures);
        if (s == null) return Collections.emptyList();
        Map<String, Object> e = ev("consecutive", consecutiveFailures);
        e.put("unrecoverable", unrecoverableFailures);
        if (error != null) e.put("last", error.length() > 80 ? error.substring(0, 80) : error);
        return verdict("too_many_failures", s, e, consecutiveFailures, consecutiveFailures >= GuardRules.FAILURE_WAYS_OUT || unrecoverableFailures > 0, 0);
    }
}
