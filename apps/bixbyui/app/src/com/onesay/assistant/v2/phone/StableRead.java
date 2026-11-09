package com.onesay.assistant.v2.phone;

/**
 * ★ stable-screen：画面稳定后再读（纯 Java，JVM 可测：时钟、读屏、指纹都由调用方注入）。
 *
 * <p>动作（点击 / 输入 / 滑动 / 打开 App）之后，界面常常还在转场、加载、弹出动画里；只读一次可能读到半截画面，
 * 模型据此给出的编号和坐标就是错的。这里在动作之后反复读界面树、比指纹（结构 + 文字 + 位置的哈希），
 * <b>连续 {@link #MATCHES} 次读到同一个指纹</b>（两次之间隔 {@link #POLL_MS}）才算稳定；最长等 {@link #MAX_WAIT_MS}，
 * 到时还没稳定就用最后一次读到的结果，并标记 unstable（由调用方写日志）。
 *
 * <p>只用于「动作之后」的那次读屏；纯查询（任务开始的第一次读屏、用户接手 / 确认之后的重读）不走这里，不多花时间。
 *
 * <p>思路参考开源项目 agent-phone（https://github.com/lurenss/agent-phone，Apache-2.0）的 {@code snapshot --settle}
 * （「读到几次一致才算画面停了」）；代码是按这个思路自己写的，没有复制其源码。
 */
public final class StableRead {

    private StableRead() {
    }

    /** 两次读屏之间的间隔。 */
    public static final long POLL_MS = 150L;
    /** 最长等多久（从第一次读屏算起）。 */
    public static final long MAX_WAIT_MS = 1500L;
    /** 连续几次读到相同指纹才算稳定（2 = 相邻两次一致）。 */
    public static final int MATCHES = 2;
    /** 动作前给模型看的那屏是不是还有效：执行一批里第一个指名元素的步骤前，先重读一次核对编号。 */
    public static final boolean RECHECK_REFS = true;

    /** 可调参数（默认值 = 上面的常量）。 */
    public static final class Params {
        public long pollMs = POLL_MS;
        public long maxWaitMs = MAX_WAIT_MS;
        public int matches = MATCHES;
        public boolean recheckRefs = RECHECK_REFS;

        public static Params defaults() {
            return new Params();
        }

        @Override public String toString() {
            return "poll=" + pollMs + "ms max=" + maxWaitMs + "ms matches=" + matches + (recheckRefs ? " recheckRefs" : "");
        }
    }

    public interface Clock {
        long now();

        void sleep(long ms);
    }

    /** 读一次屏（可返回 null = 读失败）+ 算指纹。 */
    public interface Source<T> {
        T read();

        String fingerprint(T value);
    }

    public static final class Result<T> {
        /** 最后一次读到的（非 null 的）结果；全都读失败时为 null。 */
        public final T value;
        public final boolean stable;
        public final int reads;
        public final long waitedMs;

        Result(T value, boolean stable, int reads, long waitedMs) {
            this.value = value;
            this.stable = stable;
            this.reads = reads;
            this.waitedMs = waitedMs;
        }
    }

    /** 从头读起（第一次读屏也算在 reads 里）。 */
    public static <T> Result<T> await(Clock clock, Source<T> src, Params p) {
        return await(clock, src, null, false, p);
    }

    /** seed：调用方刚读到的一屏（hasSeed = true 时算作第一次读，不再重读）。 */
    public static <T> Result<T> await(Clock clock, Source<T> src, T seed, boolean hasSeed, Params p) {
        return await(clock, src, seed, hasSeed, p, 0);
    }

    /** deadlineCapMs：另外的上限（如条件等待剩下的时间），≤ 0 = 不限；取它和 maxWaitMs 的小者。 */
    public static <T> Result<T> await(Clock clock, Source<T> src, T seed, boolean hasSeed, Params p, long deadlineCapMs) {
        Params q = p == null ? Params.defaults() : p;
        long t0 = clock.now();
        long budget = Math.max(0, q.maxWaitMs);
        if (deadlineCapMs > 0) budget = Math.min(budget, deadlineCapMs);
        long deadline = t0 + budget;
        T cur = hasSeed ? seed : src.read();
        int reads = hasSeed ? 0 : 1;
        T last = cur;
        String prevFp = cur == null ? null : src.fingerprint(cur);
        int same = cur == null ? 0 : 1;
        int need = Math.max(1, q.matches);
        if (same >= need) return new Result<T>(last, true, reads, clock.now() - t0);
        long gap = Math.max(1, q.pollMs);
        while (clock.now() < deadline) {
            clock.sleep(Math.min(gap, Math.max(0, deadline - clock.now())));
            T t = src.read();
            reads++;
            if (t == null) {   // 读失败：不算一致，保留上一次读到的
                same = 0;
                prevFp = null;
                continue;
            }
            last = t;
            String fp = src.fingerprint(t);
            same = fp != null && fp.equals(prevFp) ? same + 1 : 1;
            prevFp = fp;
            if (same >= need) return new Result<T>(last, true, reads, clock.now() - t0);
        }
        return new Result<T>(last, false, reads, clock.now() - t0);
    }

    // ------------------------------------------------------------------ 编号核对（ref 失效检测）

    /** 失效时给模型的错误码前缀（报告里原样出现，模型据此知道要用新界面的编号）。 */
    public static final String REF_STALE = "REF_STALE";

    /**
     * 动作前核对编号：模型看的是 seen，执行前重读得到 fresh。返回 null = 编号仍有效（执行时用 fresh 里的位置）；
     * 否则返回给模型的错误说明（以 {@link #REF_STALE} 开头）。seen 里本来就没有这个编号的情况交给 PhonePlan.validate。
     */
    public static String checkRef(int id, UiTree seen, UiTree fresh) {
        if (id < 0 || seen == null || fresh == null) return null;
        UiTree.El was = seen.find(id);
        if (was == null) return null;
        UiTree.El now = fresh.find(id);
        if (now == null) {
            return REF_STALE + "：编号 [" + id + "]" + named(was) + " 已失效（你看过之后界面变了，它已不在屏上）；已重新读屏，请用新界面里的编号";
        }
        int shortSide = Math.max(1, Math.min(fresh.screenW, fresh.screenH));
        double moved = Math.hypot(now.cx() - was.cx(), now.cy() - was.cy());
        if (moved > shortSide * MOVED_FRACTION && twins(fresh, now) > 1) {
            return REF_STALE + "：编号 [" + id + "]" + named(was) + " 位置变了，屏上还有同名元素，分不清是哪一个；已重新读屏，请用新界面里的编号";
        }
        return null;
    }

    /** 移动超过短边的这个比例、且屏上有同名元素，就不认这个编号。 */
    static final double MOVED_FRACTION = 0.05;

    private static String named(UiTree.El e) {
        return e.label.length() > 0 ? "「" + PhoneAgent.clip(e.label, 20) + "」" : "";
    }

    /** 同一基键（资源 id + 名字 + 类名 + 种类，不含同键序号）的元素有几个。 */
    static int twins(UiTree t, UiTree.El e) {
        String base = base(e);
        int c = 0;
        for (UiTree.El x : t.els) if (base(x).equals(base)) c++;
        return c;
    }

    static String base(UiTree.El e) {
        String k = e.key == null ? "" : e.key;
        int i = k.lastIndexOf('#');
        return i >= 0 ? k.substring(0, i) : k;
    }
}
