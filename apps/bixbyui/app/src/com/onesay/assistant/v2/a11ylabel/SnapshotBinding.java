package com.onesay.assistant.v2.a11ylabel;

import com.onesay.assistant.v2.phone.UiTree;

/**
 * ★ a11y-labels：动作绑定快照（纯 Java，JVM 全测）。
 *
 * <p>模型 / 用户是对着某一个快照（快照 ID）选的控件；真正执行前代码重读一次树，按下面的顺序核对，
 * 任何一条不过就<b>拒绝执行</b>，把新界面交回去重新观察 —— 绝不按旧坐标盲点：
 * <ol>
 *   <li>旧快照没贴标签 / 元素没有键 → {@link Kind#UNLABELED}（调用方退回原来的口径）；</li>
 *   <li>超过有效期（{@code maxAgeMs > 0} 时）→ {@link Kind#EXPIRED}；</li>
 *   <li>重读失败 → {@link Kind#PAGE_CHANGED}；包名变了 / Activity 变了（两边都读到时）→ {@link Kind#PAGE_CHANGED}；</li>
 *   <li>新树里找不到同一个节点键 → {@link Kind#NODE_GONE}；弱键（靠位置兜底的）还要中心偏移不超过短边的 {@link #WEAK_SHIFT}；</li>
 *   <li>同一个键出现不止一次（不该发生）→ {@link Kind#AMBIGUOUS}。</li>
 * </ol>
 * 通过 = {@link Kind#OK}，{@link Verdict#target} 是新树里的那个元素（按它现在的位置执行）。
 */
public final class SnapshotBinding {

    private SnapshotBinding() {
    }

    public static final double WEAK_SHIFT = 0.05;
    public static final String STALE = "STALE_ELEMENT";

    public enum Kind { OK, UNLABELED, EXPIRED, PAGE_CHANGED, NODE_GONE, AMBIGUOUS }

    public static final class Verdict {
        public final Kind kind;
        public final UiTree.El target;
        /** 给模型 / 用户看的一句话（OK 时为空串）。名字只截 20 字。 */
        public final String reason;
        /** 绑定用的快照 ID 和节点键（日志 / 轨迹用，不含文字）。 */
        public final String snapshotId, nodeKey;

        Verdict(Kind kind, UiTree.El target, String reason, String snapshotId, String nodeKey) {
            this.kind = kind;
            this.target = target;
            this.reason = reason == null ? "" : reason;
            this.snapshotId = snapshotId;
            this.nodeKey = nodeKey;
        }

        public boolean ok() {
            return kind == Kind.OK;
        }

        /** 拒绝执行（UNLABELED 不算：退回原口径）。 */
        public boolean refused() {
            return kind != Kind.OK && kind != Kind.UNLABELED;
        }

        public String trace() {
            return "bind " + kind + " snap=" + snapshotId + " key=" + nodeKey;
        }
    }

    /** 绑定：planned = 选控件时看到的快照，id = 那个快照里的编号，fresh = 执行前刚重读的树。 */
    public static Verdict check(UiTree planned, int id, UiTree fresh, long now, long maxAgeMs) {
        UiTree.El p = planned == null || !planned.labeled() ? null : planned.find(id);
        return checkEl(planned, p, fresh, now, maxAgeMs);
    }

    /** 同上，直接给旧快照里的元素（「N号」用）。 */
    public static Verdict checkEl(UiTree planned, UiTree.El p, UiTree fresh, long now, long maxAgeMs) {
        if (planned == null || !planned.labeled() || p == null || p.nodeKey == null || p.nodeKey.length() == 0)
            return new Verdict(Kind.UNLABELED, null, "", planned == null ? null : planned.snapshotId, null);
        String snap = planned.snapshotId, key = p.nodeKey;
        if (maxAgeMs > 0 && now - planned.takenAt > maxAgeMs)
            return new Verdict(Kind.EXPIRED, null, "快照 " + snap + " 已过期（超过 " + (maxAgeMs / 1000) + " 秒），请重新观察界面", snap, key);
        if (fresh == null) return new Verdict(Kind.PAGE_CHANGED, null, "执行前重读界面失败，没有执行；请重新观察", snap, key);
        if (!eq(planned.pkg, fresh.pkg))
            return new Verdict(Kind.PAGE_CHANGED, null, "前台从 " + planned.pkg + " 换成了 " + fresh.pkg + "，控件" + named(p) + "所在的页面已不在，没有执行；请重新观察", snap, key);
        if (planned.activity.length() > 0 && fresh.activity.length() > 0 && !planned.activity.equals(fresh.activity))
            return new Verdict(Kind.PAGE_CHANGED, null, "页面从 " + shortAct(planned.activity) + " 换成了 " + shortAct(fresh.activity) + "，没有执行；请重新观察", snap, key);
        UiTree.El hit = null;
        int n = 0;
        for (UiTree.El e : fresh.els) {
            if (key.equals(e.nodeKey)) {
                n++;
                if (hit == null) hit = e;
            }
        }
        if (n == 0) return new Verdict(Kind.NODE_GONE, null, "控件 [" + p.id + "]" + named(p) + " 在新界面里找不到了（快照 " + snap + " 之后界面变了），没有按旧位置点；请用新界面里的编号", snap, key);
        if (n > 1) return new Verdict(Kind.AMBIGUOUS, null, "控件 [" + p.id + "]" + named(p) + " 在新界面里有 " + n + " 个一样的，分不清，没有执行；请重新观察", snap, key);
        if (p.weakKey) {
            int shortSide = Math.max(1, Math.min(fresh.screenW, fresh.screenH));
            if (Math.hypot(hit.cx() - p.cx(), hit.cy() - p.cy()) > shortSide * WEAK_SHIFT)
                return new Verdict(Kind.NODE_GONE, null, "控件 [" + p.id + "]" + named(p) + " 没有名字也没有资源 id，位置又变了，认不准，没有执行；请重新观察", snap, key);
        }
        return new Verdict(Kind.OK, hit, "", snap, key);
    }

    /**
     * 坐标点（模型给的 0–999 坐标，没有指名控件）：只核对页面没换（包名 / Activity），不核对节点。
     * 页面换了一样拒绝，不在新页面上盲点旧坐标。
     */
    public static Verdict checkPage(UiTree planned, UiTree fresh, long now, long maxAgeMs) {
        if (planned == null || !planned.labeled()) return new Verdict(Kind.UNLABELED, null, "", null, null);
        String snap = planned.snapshotId;
        if (maxAgeMs > 0 && now - planned.takenAt > maxAgeMs)
            return new Verdict(Kind.EXPIRED, null, "快照 " + snap + " 已过期，请重新观察界面", snap, null);
        if (fresh == null) return new Verdict(Kind.PAGE_CHANGED, null, "执行前重读界面失败，没有执行；请重新观察", snap, null);
        if (!eq(planned.pkg, fresh.pkg))
            return new Verdict(Kind.PAGE_CHANGED, null, "前台从 " + planned.pkg + " 换成了 " + fresh.pkg + "，没有按旧坐标点；请重新观察", snap, null);
        if (planned.activity.length() > 0 && fresh.activity.length() > 0 && !planned.activity.equals(fresh.activity))
            return new Verdict(Kind.PAGE_CHANGED, null, "页面从 " + shortAct(planned.activity) + " 换成了 " + shortAct(fresh.activity) + "，没有按旧坐标点；请重新观察", snap, null);
        return new Verdict(Kind.OK, null, "", snap, null);
    }

    static String named(UiTree.El e) {
        if (e.label.length() == 0) return "";
        return "「" + (e.label.length() > 20 ? e.label.substring(0, 20) + "…" : e.label) + "」";
    }

    static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    static String shortAct(String a) {
        int k = a.lastIndexOf('.');
        return k >= 0 ? a.substring(k + 1) : a;
    }
}
