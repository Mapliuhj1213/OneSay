package com.onesay.assistant.v2.s2;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [s2] 习惯默认值的裁决（纯函数，JVM 可测）。
 *
 * <ol>
 *   <li><b>显式偏好优先</b>：用户说过「以后打车都用滴滴」→ 就是滴滴，推断值不参与；</li>
 *   <li><b>推断</b>：最近 {@link #WINDOW} 次使用里，某个 App 用了 ≥ {@link #THRESHOLD} 次且<b>严格多于</b>其它任何一个 → 默认它；
 *       打平或不足 3 次 → 没有默认（照旧走原流程：模型/追问）；</li>
 *   <li><b>重置</b>：「别默认用滴滴了」→ 清显式偏好，并记下重置时刻，之前的使用记录不再计入（重新攒 3 次）。</li>
 * </ol>
 * 单句覆盖（「用高德打车」「打车用美团」）不改默认值，只计一次使用。
 */
public final class HabitPolicy {

    private HabitPolicy() {}

    public static final int THRESHOLD = 3;
    public static final int WINDOW = 10;

    public static final String SRC_EXPLICIT = "explicit";
    public static final String SRC_INFERRED = "inferred";

    public static final class Default {
        public final String appId;
        public final String source;
        /** 推断时：窗口内该 App 的次数 / 窗口大小。 */
        public final int hits;
        public final int window;

        Default(String appId, String source, int hits, int window) {
            this.appId = appId;
            this.source = source;
            this.hits = hits;
            this.window = window;
        }

        public boolean explicit() {
            return SRC_EXPLICIT.equals(source);
        }

        @Override public String toString() {
            return appId + "(" + source + (explicit() ? "" : " " + hits + "/" + window) + ")";
        }
    }

    /**
     * @param explicitApp 显式偏好（可 null）
     * @param recentUses  重置之后的使用记录，新的在前（调用方已按 {@link #WINDOW} 截断也可）
     */
    public static Default decide(String explicitApp, List<String> recentUses) {
        if (explicitApp != null && explicitApp.length() > 0) return new Default(explicitApp, SRC_EXPLICIT, 0, 0);
        if (recentUses == null || recentUses.isEmpty()) return null;
        int n = Math.min(WINDOW, recentUses.size());
        Map<String, Integer> cnt = new HashMap<String, Integer>();
        for (int i = 0; i < n; i++) {
            String a = recentUses.get(i);
            Integer c = cnt.get(a);
            cnt.put(a, Integer.valueOf(c == null ? 1 : c.intValue() + 1));
        }
        String best = null;
        int bestN = 0;
        boolean tie = false;
        for (Map.Entry<String, Integer> e : cnt.entrySet()) {
            int v = e.getValue().intValue();
            if (v > bestN) {
                best = e.getKey();
                bestN = v;
                tie = false;
            } else if (v == bestN) {
                tie = true;
            }
        }
        if (best == null || tie || bestN < THRESHOLD) return null;
        return new Default(best, SRC_INFERRED, bestN, n);
    }

    /** 读库 + 裁决。 */
    public static Default resolve(HabitStore store, String group) {
        if (store == null || group == null) return null;
        String exp = store.explicitDefault(group);
        return decide(exp, store.recentUses(group, store.resetAt(group), WINDOW));
    }
}
