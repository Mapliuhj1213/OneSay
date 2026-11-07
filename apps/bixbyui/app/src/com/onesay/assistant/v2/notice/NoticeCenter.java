package com.onesay.assistant.v2.notice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 提醒中枢（纯 Java，JVM 可测）：队列 + 去重 + 过期 + 免打扰 / 通话中 + 每日上限 + 持久化 + 重启恢复 + 动作。
 * Android 胶水（{@code NoticeHooks}）只负责：取环境、到点调 {@link #tick}、把 {@link #active()} 交给灵动岛、执行 {@link Outcome}。
 *
 * <p>岛上一次只显示队首一条（其余「+N」），由 {@code IslandModel} 画；「要不要自动展开」由中心给出（{@link Tick#alert}），
 * 灵动岛再按「有没有在全屏回答 / 岛上任务」做最后裁决（不打断）。
 */
public final class NoticeCenter {

    public interface Clock {
        long now();
    }

    public enum PostResult {
        /** 立刻排进岛上队列。 */
        SHOWN,
        /** 还没到显示时刻（日程 / 定时邮件提前量），到点再出。 */
        SCHEDULED,
        /** 免打扰时段，压到时段结束。 */
        HELD_QUIET,
        /** 同一个去重键已有一条在排：更新内容，不重复提醒。 */
        DUP_UPDATED,
        /** 同一个去重键已被处理过（忽略 / 点过）：不再出现。 */
        DUP_IGNORED,
        DROP_DISABLED, DROP_EXPIRED, DROP_CAP, DROP_INVALID
    }

    /** 一次 tick / post 之后要交给岛的结果。 */
    public static final class Tick {
        /** 本次新出现在岛上的。 */
        public final List<IslandNotice> surfaced = new ArrayList<IslandNotice>();
        /** 本次过期 / 撤下的。 */
        public final List<IslandNotice> gone = new ArrayList<IslandNotice>();
        /** 有新提醒且此刻不静音 → 请求自动展开（岛再按忙闲裁决）。 */
        public boolean alert;
        /** 新出现的里有高优先级。 */
        public boolean high;

        public boolean changed() {
            return !surfaced.isEmpty() || !gone.isEmpty();
        }
    }

    /** 点了一个动作：中心已改好状态，胶水照 kind 执行。kind = null 表示什么都不用做（动作无效）。 */
    public static final class Outcome {
        public final NoticeAction.Kind kind;
        public final String arg;
        public final IslandNotice notice;
        public final NoticeAction action;

        Outcome(NoticeAction.Kind kind, String arg, IslandNotice notice, NoticeAction action) {
            this.kind = kind;
            this.arg = arg;
            this.notice = notice;
            this.action = action;
        }

        /**：COPY_TEXT 的 arg（验证码）不进日志。 */
        @Override public String toString() {
            return "outcome[" + kind + " " + NoticeAction.logArg(kind, arg) + "]";
        }
    }

    public static final long SNOOZE_DEFAULT_MS = 10L * 60000L;
    /** 已收尾的行留多久（每日上限按它计数 + 去重记忆），之后删掉。 */
    public static final long KEEP_DONE_MS = 2L * 24L * 3600000L;
    /** 最多同时在排的提醒（超出的最低优先级 / 最早的被挤掉）。 */
    public static final int MAX_ACTIVE = 20;
    /** WITHDRAWN 的原因：来源拉取失败 / 连接器缺失（BUG-I1）。 */
    public static final String NOTE_SOURCE_LOST = "来源不可用";
    /** WITHDRAWN 的原因：总开关 / 来源开关关了（BUG-I3：开关重开时没过期的从库里放回来）。 */
    public static final String NOTE_SWITCHED_OFF = "来源已关闭";
    /**：状态型提醒（麦克风在用、通话中……）对应的状态已经结束。 */
    public static final String NOTE_STATE_ENDED = "状态结束";

    private final NoticeStore mStore;
    private final Clock mClock;
    private final Map<String, IslandNotice> mAll = new LinkedHashMap<String, IslandNotice>();
    private boolean mRestored;

    public NoticeCenter(NoticeStore store, Clock clock) {
        mStore = store == null ? new NoticeStore.Mem() : store;
        mClock = clock;
    }

    public NoticeStore store() {
        return mStore;
    }

    long now() {
        return mClock.now();
    }

    // ================================================================== 恢复

    /** App 被杀 / 重启后：把库里没过期、没处理的提醒放回来（不自动展开）。重复调用无副作用。 */
    public synchronized Tick restore(NoticePolicy.Env env) {
        mAll.clear();
        long now = now();
        for (IslandNotice n : mStore.all()) {
            if (n.state == IslandNotice.State.ACTIVE || n.state == IslandNotice.State.PENDING || now - Math.max(n.expireAt, n.createdAt) < KEEP_DONE_MS) {
                mAll.put(n.dedupKey, n);
            } else {
                mStore.delete(n.dedupKey);
            }
        }
        mRestored = true;
        Tick t = tickLocked(env, now);
        t.alert = false;   // 恢复不打扰
        t.high = false;
        return t;
    }

    public synchronized boolean restored() {
        return mRestored;
    }

    // ================================================================== 投递

    public synchronized PostResult post(IslandNotice in, NoticePolicy.Env env, Tick out) {
        if (in == null || in.dedupKey == null || in.dedupKey.length() == 0 || in.title == null || in.title.length() == 0) return PostResult.DROP_INVALID;
        long now = now();
        IslandNotice n = in.copy();
        if (n.createdAt <= 0) n.createdAt = now;
        if (n.expireAt <= 0) n.expireAt = Math.max(now, n.showAt) + NoticePolicy.defaultTtl(n.source);
        if (!NoticePolicy.sourceOn(env, n)) return PostResult.DROP_DISABLED;
        if (n.expireAt <= now) return PostResult.DROP_EXPIRED;
        IslandNotice old = mAll.get(n.dedupKey);
        long keepShownAt = 0;
        if (old != null && old.state == IslandNotice.State.WITHDRAWN) {
            // 被系统撤下过（来源不可用 / 开关关过）、用户没处理过：同键再来 = 重新进队。
            // 已经在岛上出现过的保留首次出现时刻（每日上限不重复计数），回来时也不再自动展开一次。
            keepShownAt = old.shownAt;
            old = null;
        }
        if (old != null && old.expireAt > now) {
            if (old.isLive()) {
                // 去重：同一件事只留一条，内容按新的，状态 / 首次出现时刻 / 排队位置不变，也不再提醒一次
                old.title = n.title;
                old.summary = n.summary;
                old.actions = n.actions;
                old.priority = n.priority;
                old.expireAt = n.expireAt;
                old.bars = n.bars;   // ★ feat/rain-minutely：降水柱状条随新数据更新
                if (old.state == IslandNotice.State.PENDING && old.shownAt == 0) old.showAt = n.showAt;
                save(old);
                return PostResult.DUP_UPDATED;
            }
            return PostResult.DUP_IGNORED;
        }
        n.state = IslandNotice.State.PENDING;
        n.shownAt = keepShownAt;
        n.heldUntil = 0;
        n.note = "";
        mAll.put(n.dedupKey, n);
        Tick t = out != null ? out : new Tick();
        int before = t.surfaced.size();
        promote(n, env, now, t, keepShownAt == 0);
        save(n);
        trim(t);
        if (n.state == IslandNotice.State.SUPPRESSED) return PostResult.DROP_CAP;
        if (t.surfaced.size() > before) return PostResult.SHOWN;
        return n.heldUntil > 0 ? PostResult.HELD_QUIET : PostResult.SCHEDULED;
    }

    /**
     * 拉取型来源的整批同步：逐条 post；这个来源（kind + tag）里<b>还没处理</b>、但这批里没有的旧提醒撤掉（日程删了 / 改时间、定时任务删了）。
     * fresh = null 表示这次拿不到数据 → 什么都不动。
     */
    public synchronized Tick sync(IslandNotice.Source kind, String tag, List<IslandNotice> fresh, NoticePolicy.Env env) {
        Tick t = new Tick();
        if (fresh == null) return t;
        Set<String> keep = new HashSet<String>();
        for (IslandNotice n : fresh) {
            if (n == null) continue;
            if (tag != null && n.sourceTag.length() == 0) n.sourceTag = tag;
            keep.add(n.dedupKey);
            post(n, env, t);
        }
        long now = now();
        for (IslandNotice o : new ArrayList<IslandNotice>(mAll.values())) {
            if (o.source != kind || !o.isLive() || keep.contains(o.dedupKey)) continue;
            if (tag != null && !tag.equals(o.sourceTag)) continue;
            finish(o, IslandNotice.State.EXPIRED, "来源里已没有", now, t);
        }
        if (t.surfaced.isEmpty()) {
            t.alert = false;
        }
        return t;
    }

    /**
     * 拉型来源这次<b>拿不到</b>（拉取失败 / 连接器缺失，BUG-I1）：把这个来源（kind + tag）所有没处理的提醒撤下——
     * 还没展示的（PENDING）和已经在岛上的（ACTIVE）都撤；别的来源、用户处理过的不动。记 WITHDRAWN（不是去重记忆），
     * 来源恢复后同键再来会重新进队（没过期的才回来）。
     */
    public synchronized Tick withdrawSource(IslandNotice.Source kind, String tag, String why) {
        Tick t = new Tick();
        long now = now();
        for (IslandNotice o : new ArrayList<IslandNotice>(mAll.values())) {
            if (o.source != kind || !o.isLive()) continue;
            if (tag != null && !tag.equals(o.sourceTag)) continue;
            finish(o, IslandNotice.State.WITHDRAWN, why == null ? NOTE_SOURCE_LOST : why, now, t);
        }
        return t;
    }

    /**
     * 状态型来源（麦克风 / 相机在用、通话中、导航、外卖……）：<b>状态结束</b>时撤下这一条（记 EXPIRED「状态结束」）。
     * 只动还没处理的；用户已经忽略过的保持 DISMISSED（去重记忆照旧）。返回是否撤了。
     */
    public synchronized boolean retract(String key, String why, Tick t) {
        IslandNotice n = key == null ? null : mAll.get(key);
        if (n == null || !n.isLive()) return false;
        finish(n, IslandNotice.State.EXPIRED, why == null ? NOTE_STATE_ENDED : why, now(), t);
        return true;
    }

    /**：这个来源（kind + tag）里键以 prefix 开头、还没处理的全部撤下（例如通话结束撤掉「来电 / 通话中」）。返回撤了几条。 */
    public synchronized int retractPrefix(IslandNotice.Source kind, String prefix, String why, Tick t) {
        int c = 0;
        long now = now();
        for (IslandNotice o : new ArrayList<IslandNotice>(mAll.values())) {
            if (o.source != kind || !o.isLive() || prefix == null || !o.dedupKey.startsWith(prefix)) continue;
            finish(o, IslandNotice.State.EXPIRED, why == null ? NOTE_STATE_ENDED : why, now, t);
            c++;
        }
        return c;
    }

    /** 这个来源（kind + tag）还有几条没处理的（PENDING + ACTIVE）。 */
    public synchronized int liveCount(IslandNotice.Source kind, String tag) {
        int c = 0;
        for (IslandNotice o : mAll.values()) {
            if (o.source != kind || !o.isLive()) continue;
            if (tag != null && !tag.equals(o.sourceTag)) continue;
            c++;
        }
        return c;
    }

    // ================================================================== 到点

    public synchronized Tick tick(NoticePolicy.Env env) {
        return tickLocked(env, now());
    }

    private Tick tickLocked(NoticePolicy.Env env, long now) {
        Tick t = new Tick();
        for (IslandNotice n : new ArrayList<IslandNotice>(mAll.values())) {
            if (n.state == IslandNotice.State.WITHDRAWN && NOTE_SWITCHED_OFF.equals(n.note)
                    && n.expireAt > now && NoticePolicy.sourceOn(env, n)) {
                // BUG-I3：开关重开 → 关之前在排、还没过期的放回来（关闭期间已经过期的不补发）。
                // 在岛上出现过的不再自动展开、不重复计每日上限；还没出现过的照常到点 / 展开。
                boolean seen = n.shownAt > 0;
                n.state = IslandNotice.State.PENDING;
                n.note = "";
                n.heldUntil = 0;
                promote(n, env, now, t, !seen);
                save(n);
                continue;
            }
            if (!n.isLive()) {
                if (now - Math.max(n.expireAt, n.createdAt) >= KEEP_DONE_MS) {
                    mAll.remove(n.dedupKey);
                    mStore.delete(n.dedupKey);
                }
                continue;
            }
            if (n.expireAt <= now) {
                finish(n, IslandNotice.State.EXPIRED, "过期", now, t);
                continue;
            }
            if (!NoticePolicy.sourceOn(env, n)) {
                // 开关关了：在排的撤下，记 WITHDRAWN（不算处理过）；开关再开时没过期的放回来（上面那段，BUG-I3）
                finish(n, IslandNotice.State.WITHDRAWN, NOTE_SWITCHED_OFF, now, t);
                continue;
            }
            if (n.state == IslandNotice.State.PENDING) {
                promote(n, env, now, t, true);
                save(n);
            }
        }
        trim(t);
        return t;
    }

    /** PENDING → ACTIVE（到点、不在免打扰里、没超每日上限）。mayAlert = false：回到岛上但不请求自动展开（撤下后恢复的）。 */
    private void promote(IslandNotice n, NoticePolicy.Env env, long now, Tick t, boolean mayAlert) {
        if (n.state != IslandNotice.State.PENDING) return;
        if (n.showAt > now) return;
        if (NoticePolicy.inQuiet(env, now) && !NoticePolicy.quietExempt(env, n)) {
            n.heldUntil = NoticePolicy.quietEndAt(env, now);
            return;
        }
        n.heldUntil = 0;
        if (n.shownAt == 0) {
            if (countToday(n.source, now, env) >= NoticePolicy.cap(env, n.source)) {
                n.state = IslandNotice.State.SUPPRESSED;
                n.note = "今天次数已满";
                return;
            }
            n.shownAt = now;
        }
        n.state = IslandNotice.State.ACTIVE;
        t.surfaced.add(n.copy());
        if (!mayAlert) return;
        if (!NoticePolicy.silent(env)) t.alert = true;
        if (n.priority == IslandNotice.Priority.HIGH) t.high = true;
    }

    private int countToday(IslandNotice.Source s, long now, NoticePolicy.Env env) {
        long start = NoticePolicy.dayStart(now, env == null ? null : env.tz);
        int c = 0;
        for (IslandNotice n : mAll.values()) if (n.source == s && n.shownAt >= start && n.shownAt <= now) c++;
        return c;
    }

    /** 超过 {@link #MAX_ACTIVE} 时挤掉队尾（最低优先级里最早的那条不动，挤最新的低优先级）。 */
    private void trim(Tick t) {
        List<IslandNotice> act = activeLocked();
        long now = now();
        for (int i = act.size() - 1; i >= MAX_ACTIVE; i--) finish(mAll.get(act.get(i).dedupKey), IslandNotice.State.EXPIRED, "队列已满", now, t);
    }

    private void finish(IslandNotice n, IslandNotice.State st, String why, long now, Tick t) {
        if (n == null) return;
        boolean wasActive = n.state == IslandNotice.State.ACTIVE;
        n.state = st;
        n.note = why == null ? "" : why;
        n.heldUntil = 0;
        save(n);
        if (wasActive && t != null) t.gone.add(n.copy());
    }

    private void save(IslandNotice n) {
        if (n.persist) mStore.put(n);
    }

    // ================================================================== 查询

    /** 岛上队列（已排序；队首 = 岛上显示的那条）。 */
    public synchronized List<IslandNotice> active() {
        List<IslandNotice> l = new ArrayList<IslandNotice>();
        for (IslandNotice n : activeLocked()) l.add(n.copy());
        return l;
    }

    private List<IslandNotice> activeLocked() {
        List<IslandNotice> l = new ArrayList<IslandNotice>();
        for (IslandNotice n : mAll.values()) if (n.state == IslandNotice.State.ACTIVE) l.add(n);
        Collections.sort(l, NoticePolicy.ORDER);
        return l;
    }

    public synchronized IslandNotice get(String key) {
        IslandNotice n = key == null ? null : mAll.get(key);
        return n == null ? null : n.copy();
    }

    public synchronized List<IslandNotice> all() {
        List<IslandNotice> l = new ArrayList<IslandNotice>();
        for (IslandNotice n : mAll.values()) l.add(n.copy());
        return l;
    }

    /** 下一次需要醒来的时刻（-1 = 不需要）：待显示的 showAt、免打扰结束、在排提醒的过期。 */
    public synchronized long nextWakeAt() {
        long best = -1L;
        for (IslandNotice n : mAll.values()) {
            if (n.state == IslandNotice.State.PENDING) {
                best = min(best, n.heldUntil > 0 ? n.heldUntil : n.showAt);
                best = min(best, n.expireAt);
            } else if (n.state == IslandNotice.State.ACTIVE) {
                best = min(best, n.expireAt);
            }
        }
        return best;
    }

    static long min(long a, long b) {
        if (b <= 0) return a;
        if (a <= 0) return b;
        return Math.min(a, b);
    }

    // ================================================================== 用户操作

    /** 左滑 / 点叉：忽略一条（同一个去重键之后不会再出现，直到它过期）。 */
    public synchronized boolean dismiss(String key, Tick t) {
        IslandNotice n = key == null ? null : mAll.get(key);
        if (n == null || !n.isLive()) return false;
        finish(n, IslandNotice.State.DISMISSED, "忽略", now(), t);
        return true;
    }

    public synchronized int dismissAll(Tick t) {
        int c = 0;
        for (IslandNotice n : activeLocked()) {
            finish(n, IslandNotice.State.DISMISSED, "全部忽略", now(), t);
            c++;
        }
        return c;
    }

    /**
     * 点了第 index 个动作。中心先改状态，返回胶水要执行的事。
     * 这里<b>没有、也不能有</b>任何发送邮件的路径：邮件动作只会变成「打开确认卡 / 看任务 / 推迟 / 取消」。
     */
    public synchronized Outcome act(String key, int index, Tick t) {
        IslandNotice n = key == null ? null : mAll.get(key);
        if (n == null || n.state != IslandNotice.State.ACTIVE || index < 0 || index >= n.actions.size()) return new Outcome(null, "", null, null);
        NoticeAction a = n.actions.get(index);
        long now = now();
        switch (a.kind) {
            case OPEN_URL:
                if (!NoticeAction.safeUrl(a.arg)) return new Outcome(null, a.arg, n.copy(), a);
                finish(n, IslandNotice.State.ACTED, a.label, now, t);
                break;
            case SNOOZE: {
                long ms = parseMinutes(a.arg, SNOOZE_DEFAULT_MS);
                if (now + ms >= n.expireAt) return new Outcome(null, a.arg, n.copy(), a);   // 推到过期之后就没意义了
                boolean wasActive = n.state == IslandNotice.State.ACTIVE;
                n.state = IslandNotice.State.PENDING;
                n.showAt = now + ms;
                n.note = "稍后提醒";
                save(n);
                if (wasActive && t != null) t.gone.add(n.copy());
                break;
            }
            case DISMISS:
                finish(n, IslandNotice.State.DISMISSED, a.label, now, t);
                break;
            case INLINE:
                //：提醒留在岛上，状态不变（来源执行后自己更新按钮文字）
                break;
            case OPEN_APP:
                if (!NoticeAction.safePackage(a.arg)) return new Outcome(null, a.arg, n.copy(), a);
                finish(n, IslandNotice.State.ACTED, a.label, now, t);
                break;
            default:
                finish(n, IslandNotice.State.ACTED, a.label, now, t);
                break;
        }
        return new Outcome(a.kind, a.arg, n.copy(), a);
    }

    static long parseMinutes(String s, long def) {
        try {
            int m = Integer.parseInt(s == null ? "" : s.trim());
            return m > 0 && m <= 24 * 60 ? m * 60000L : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 调试 / 单测：清空（库也清）。 */
    public synchronized void clear() {
        for (String k : new ArrayList<String>(mAll.keySet())) mStore.delete(k);
        mAll.clear();
    }

    /** 调试 / 单测：模拟进程被杀（只丢内存，库还在）。 */
    public synchronized void dropMemory() {
        mAll.clear();
        mRestored = false;
    }
}
