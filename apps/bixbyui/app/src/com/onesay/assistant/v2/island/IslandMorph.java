package com.onesay.assistant.v2.island;

/**
 * [island-cutout-1118] 岛形状的状态机（纯 Java）：宽 / 高 / 圆角 / 水平中心 / 上沿 / 颈 六根弹簧 + 「从源点长出、缩回源点」。
 *
 * <p>IslandView 直接持有它的弹簧（同一批对象），JVM 演示（{@code IslandMorphDemo}）和单测也用它 —— 画出来的帧与手机上同一套数。
 * 源点 = {@link IslandGeometry.Anchor} 的 origin（挖孔，或上方居中的 28dp 种子）。
 */
public final class IslandMorph {

    public final IslandGeometry.Spring w, h, r, x, y, neck;
    private IslandGeometry.Anchor mAnchor;
    private float mD = 1f;
    private LiquidMotion.Phase mPhase = LiquidMotion.Phase.COLLAPSE;
    private boolean mToOrigin;
    private final float[] mNeckQ = new float[16];

    public IslandMorph() {
        w = new IslandGeometry.Spring(0f, IslandGeometry.SPRING_OMEGA, IslandGeometry.SPRING_ZETA);
        h = new IslandGeometry.Spring(0f, IslandGeometry.SPRING_OMEGA, IslandGeometry.SPRING_ZETA);
        r = new IslandGeometry.Spring(0f, IslandGeometry.SPRING_OMEGA, IslandGeometry.SPRING_ZETA);
        x = new IslandGeometry.Spring(0f, IslandGeometry.omegaOf(0.4f), 0.9f);
        y = new IslandGeometry.Spring(0f, IslandGeometry.omegaOf(0.4f), 0.9f);
        neck = new IslandGeometry.Spring(0f, IslandGeometry.omegaOf(0.5f), 1f);
    }

    /** 换锚点（旋转 / 分辨率 / 设置变了）：位置直接跳到新锚点（屏幕都变了，不做横穿屏幕的动画）。 */
    public void setAnchor(IslandGeometry.Anchor a, float density) {
        boolean jump = mAnchor == null || a == null || mAnchor.cx != a.cx || mAnchor.top != a.top || mAnchor.wrap != a.wrap;
        mAnchor = a;
        mD = density <= 0 ? 1f : density;
        if (jump && a != null) {
            x.v = x.t = a.cx;
            y.v = y.t = a.top;
            x.vel = y.vel = 0f;
        }
    }

    public IslandGeometry.Anchor anchor() {
        return mAnchor;
    }

    public LiquidMotion.Phase phase() {
        return mPhase;
    }

    /** 每根弹簧换成这一阶段的参数（速度保留 → 中途改方向不硬切）。 */
    public void setPhase(LiquidMotion.Phase p) {
        mPhase = p;
        set(w, p, LiquidMotion.Axis.W);
        set(h, p, LiquidMotion.Axis.H);
        set(r, p, LiquidMotion.Axis.R);
        set(x, p, LiquidMotion.Axis.X);
        set(y, p, LiquidMotion.Axis.Y);
        set(neck, p, LiquidMotion.Axis.NECK);
    }

    private static void set(IslandGeometry.Spring s, LiquidMotion.Phase p, LiquidMotion.Axis a) {
        float[] q = LiquidMotion.spring(p, a);
        s.setParams(q[0], q[1]);
    }

    /** 目标形状（可见）。 */
    public void targetShape(float tw, float th, float tr, float tcx, float ttop) {
        mToOrigin = false;
        w.t = tw;
        h.t = th;
        r.t = tr;
        x.t = tcx;
        y.t = ttop;
        neck.t = 0f;
    }

    /** 放到源点（从孔里长出来的第一帧）：大小 = 孔，颈拉满。 */
    public void seedAtOrigin() {
        IslandGeometry.Anchor a = mAnchor;
        if (a == null) return;
        float ow = originW(), oh = originH();
        w.v = ow;
        h.v = oh;
        r.v = oh / 2f;
        x.v = a.hasOrigin() ? a.originCx : a.cx;
        y.v = a.hasOrigin() ? a.originTop() : a.top;
        w.vel = h.vel = r.vel = x.vel = y.vel = 0f;
        neck.v = 1f;
        neck.vel = 0f;
    }

    /** 收起：目标 = 源点（缩回孔里），颈重新长出来。 */
    public void targetOrigin() {
        IslandGeometry.Anchor a = mAnchor;
        if (a == null) return;
        mToOrigin = true;
        float ow = originW(), oh = originH();
        w.t = ow;
        h.t = oh;
        r.t = oh / 2f;
        x.t = a.hasOrigin() ? a.originCx : a.cx;
        y.t = a.hasOrigin() ? a.originTop() : a.top;
        neck.t = a.wrap || !a.hasOrigin() ? 0f : 1f;
    }

    public boolean toOrigin() {
        return mToOrigin;
    }

    float originW() {
        IslandGeometry.Anchor a = mAnchor;
        if (a != null && a.hasOrigin()) return a.originW;
        return IslandGeometry.COMPACT_H_DP * mD;
    }

    float originH() {
        IslandGeometry.Anchor a = mAnchor;
        if (a != null && a.hasOrigin()) return a.originH;
        return IslandGeometry.COMPACT_H_DP * mD;
    }

    /** 源点是真的挖孔（不是上方居中的种子）。 */
    public boolean originIsHole() {
        IslandGeometry.Anchor a = mAnchor;
        return a != null && a.hasOrigin() && (a.cutout == CutoutGeometry.Kind.ROUND || a.cutout == CutoutGeometry.Kind.PILL);
    }

    /** 已缩到源点附近（宽高都 ≤ 源点 × 1.3）→ 可以淡出了（黑色已经和摄像头融在一起）。 */
    public boolean nearOrigin() {
        return w.v <= originW() * 1.3f + 0.5f && h.v <= originH() * 1.3f + 0.5f;
    }

    public void step(float dt) {
        w.step(dt);
        h.step(dt);
        r.step(dt);
        x.step(dt);
        y.step(dt);
        neck.step(dt);
    }

    public void snapAll() {
        w.snap();
        h.snap();
        r.snap();
        x.snap();
        y.snap();
        neck.snap();
    }

    public boolean settled(float eps) {
        return w.settled(eps) && h.settled(eps) && r.settled(eps) && x.settled(eps) && y.settled(eps) && neck.settled(0.01f);
    }

    // ------------------------------------------------------------------ 画的时候用

    public float left() {
        return x.v - Math.max(0f, w.v) / 2f;
    }

    public float top() {
        return y.v;
    }

    public float right() {
        return x.v + Math.max(0f, w.v) / 2f;
    }

    public float bottom() {
        return y.v + Math.max(0f, h.v);
    }

    public float radius() {
        return Math.max(0f, Math.min(r.v, Math.min(Math.max(0f, w.v), Math.max(0f, h.v)) / 2f));
    }

    public float sag() {
        return LiquidMotion.sag(h.vel, Math.max(0f, h.v), mD);
    }

    /**
     * 整个形状写进 sink（屏幕坐标减去 offX/offY）：岛轮廓（带下垂）+ 需要时孔圆 + 颈。
     * 返回是否画了颈（画了颈说明是多个子路径，调用方半透明时要 saveLayer 防止叠加处变深）。
     */
    public boolean build(LiquidMotion.PathSink sink, float offX, float offY) {
        float l = left() - offX, t = top() - offY, rr = right() - offX, b = bottom() - offY;
        if (rr - l < 0.5f || b - t < 0.5f) return false;
        float rad = radius();
        LiquidMotion.outline(sink, l, t, rr, b, rad, sag());
        return buildNeck(sink, offX, offY);
    }

    /** 只写颈（孔圆 + 粘连）；不需要 → false。 */
    public boolean buildNeck(LiquidMotion.PathSink sink, float offX, float offY) {
        IslandGeometry.Anchor a = mAnchor;
        float amt = neck.v;
        if (a == null || !a.hasOrigin() || amt <= 0.02f || !originIsHole()) return false;
        float r1 = originH() / 2f * (0.55f + 0.45f * Math.min(1f, amt));
        float c1x = a.originCx - offX, c1y = a.originCy - offY;
        float rad = radius();
        float r2 = Math.max(1f, Math.min(rad, Math.min(w.v, h.v) / 2f));
        float c2x = x.v - offX, c2y = y.v + r2 - offY;
        // 形状已经整个盖住孔 → 不用颈
        if (left() - offX <= c1x - r1 && right() - offX >= c1x + r1 && top() - offY <= c1y - r1 && bottom() - offY >= c1y + r1) {
            return false;
        }
        LiquidMotion.circle(sink, c1x, c1y, r1);
        if (LiquidMotion.neck(c1x, c1y, r1, c2x, c2y, r2, amt, mNeckQ)) LiquidMotion.neckPath(sink, mNeckQ);
        return true;
    }
}
