package com.onesay.assistant.v2.island;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ★ arch/island-progress（C 路）：任务账本里的一个<b>子目标</b>（不可变）。
 *
 * <p>事件约定（B 路 AgentLoop 发、C 路岛接）：B 路每次账本变化都把<b>完整列表</b>经
 * {@link com.onesay.assistant.v2.s1.S1Hooks#onSubgoals(List)} 发过来（全量替换，不做增量）；
 * 岛按列表算「第 i/n 项」与当前子目标标题（{@link Progress}）。
 *
 * <ul>
 *   <li>{@code id}：账本内唯一（B 路自定，例如 "g1"）；空 / null 时用下标补成 "#i"。</li>
 *   <li>{@code title}：给用户看的短句（例如「查明天天气」），岛上只显示前若干字。</li>
 *   <li>{@code state}：pending / doing / done / failed（线上字符串见 {@link State#wire()}）。</li>
 * </ul>
 */
public final class Subgoal {

    public enum State {
        PENDING("pending"), DOING("doing"), DONE("done"), FAILED("failed");

        private final String mWire;

        State(String wire) {
            mWire = wire;
        }

        /** 线上字符串（与 B 路约定的小写英文）。 */
        public String wire() {
            return mWire;
        }

        /** 宽松解析：大小写不敏感，接受常见同义词；认不出 → PENDING。 */
        public static State fromWire(String s) {
            if (s == null) return PENDING;
            String t = s.trim().toLowerCase(java.util.Locale.ROOT);
            if (t.equals("doing") || t.equals("running") || t.equals("in_progress") || t.equals("active")) return DOING;
            if (t.equals("done") || t.equals("ok") || t.equals("completed") || t.equals("success")) return DONE;
            if (t.equals("failed") || t.equals("fail") || t.equals("error") || t.equals("blocked")) return FAILED;
            return PENDING;
        }
    }

    public final String id;
    public final String title;
    public final State state;

    public Subgoal(String id, String title, State state) {
        this.id = id == null ? "" : id;
        this.title = title == null ? "" : title.trim();
        this.state = state == null ? State.PENDING : state;
    }

    public Subgoal(String id, String title, String state) {
        this(id, title, State.fromWire(state));
    }

    public Subgoal withState(State s) {
        return new Subgoal(id, title, s);
    }

    /** 拷贝并规整：丢掉 null 项、补 id、最多 {@link #MAX} 个（多出的丢尾部）。 */
    public static List<Subgoal> sanitize(List<Subgoal> in) {
        if (in == null || in.isEmpty()) return Collections.emptyList();
        List<Subgoal> out = new ArrayList<Subgoal>();
        for (Subgoal g : in) {
            if (g == null) continue;
            String id = g.id.length() > 0 ? g.id : "#" + out.size();
            out.add(new Subgoal(id, g.title, g.state));
            if (out.size() >= MAX) break;
        }
        return Collections.unmodifiableList(out);
    }

    /** 账本最多显示这么多个子目标（再多也只算前 20 个）。 */
    public static final int MAX = 20;

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Subgoal)) return false;
        Subgoal g = (Subgoal) o;
        return id.equals(g.id) && title.equals(g.title) && state == g.state;
    }

    @Override
    public int hashCode() {
        return (id.hashCode() * 31 + title.hashCode()) * 31 + state.ordinal();
    }

    @Override
    public String toString() {
        return id + ":" + state.wire() + ":" + title;
    }

    // =====================================================================
    // 进度（纯函数）
    // =====================================================================

    /** 岛上的进度：只在「GUI 操作中」或「子目标 ≥ 2」时显示（{@link #visible}）。 */
    public static final class Progress {
        /** 是否显示进度（进度点 / 「第 i/n 项」/ 进度环）。 */
        public final boolean visible;
        /** 第几项（1 起）；没有账本时为 0。 */
        public final int index;
        /** 共几项；没有账本（或只有 1 个）时为 0。 */
        public final int total;
        /** 当前子目标标题（没有账本时为 null）。 */
        public final String current;
        /** 当前项（或最近一项）失败了。 */
        public final boolean failed;
        /** 「第 i/n 项」；不显示进度或没有账本（纯 GUI）时为 null。 */
        public final String text;

        Progress(boolean visible, int index, int total, String current, boolean failed) {
            this.visible = visible;
            this.index = index;
            this.total = total;
            this.current = current;
            this.failed = failed;
            this.text = visible && total >= 2 ? itemText(index, total) : null;
        }

        static final Progress NONE = new Progress(false, 0, 0, null, false);
    }

    /** 「第 i/n 项」。 */
    public static String itemText(int i, int n) {
        return "第 " + Math.max(1, Math.min(i, n)) + "/" + n + " 项";
    }

    /**
     * 由账本 + 是否在 GUI 操作中算岛上的进度。规则：
     * <ol>
     *   <li>子目标 ≥ 2 → 显示；当前项 = 第一个 doing；没有 doing → 第一个 pending；都没有 → 最后一个失败项，否则最后一项。</li>
     *   <li>子目标 &lt; 2 且 GUI 操作中 → 显示（无账本：只画转动的进度环，不带数字）。</li>
     *   <li>其余 → 不显示（只有动作短词）。</li>
     * </ol>
     */
    public static Progress progress(List<Subgoal> goals, boolean gui) {
        int n = goals == null ? 0 : goals.size();
        if (n < 2) return gui ? new Progress(true, 0, 0, null, false) : Progress.NONE;
        int cur = -1;
        for (int i = 0; i < n && cur < 0; i++) if (goals.get(i).state == State.DOING) cur = i;
        for (int i = 0; i < n && cur < 0; i++) if (goals.get(i).state == State.PENDING) cur = i;
        boolean failed = false;
        if (cur < 0) {
            for (int i = n - 1; i >= 0 && cur < 0; i--) {
                if (goals.get(i).state == State.FAILED) {
                    cur = i;
                    failed = true;
                }
            }
        }
        if (cur < 0) cur = n - 1;
        Subgoal g = goals.get(cur);
        return new Progress(true, cur + 1, n, g.title.length() > 0 ? g.title : null, failed || g.state == State.FAILED);
    }

    /** 已完成几项（done）。 */
    public static int doneCount(List<Subgoal> goals) {
        int k = 0;
        if (goals != null) for (Subgoal g : goals) if (g.state == State.DONE) k++;
        return k;
    }
}
