package com.onesay.assistant.v2.ui.motion;

import android.os.SystemClock;
import android.view.View;

import com.onesay.assistant.v2.island.IslandGeometry;

/**
 * [R13 唤醒界面 v2] 用弹簧驱动 View 的 RenderNode 属性（alpha / translationY / scale），每帧 postOnAnimation 推进 ——
 * 不是「固定时长 + 插值器」：可打断、带速度改目标（方案 §8「值来自弹簧，每帧 Choreographer 推进」）。
 *
 * <p>一个 View 同一时刻只有一个 ViewSpring（存在 tag 里）；新的 animate 调用会接管旧的并保留当前速度。
 * 系统「动画时长缩放」= 0 时由调用方传 instant=true → 直接落到终值。
 */
public final class ViewSpring implements Runnable {

    private static final int TAG_KEY = 0x7f0b1313;

    private final View v;
    private final IslandGeometry.Spring p = new IslandGeometry.Spring(1f, 15f, 1f);   // 进度 0 → 1
    private float a0, a1, ty0, ty1, s0, s1;
    private long startAt;
    private long last;
    private Runnable end;
    private boolean running;

    private ViewSpring(View v) {
        this.v = v;
    }

    private static ViewSpring of(View v) {
        Object o = v.getTag(TAG_KEY);
        if (o instanceof ViewSpring) return (ViewSpring) o;
        ViewSpring s = new ViewSpring(v);
        v.setTag(TAG_KEY, s);
        return s;
    }

    /**
     * 从 (alpha a0, translationY ty0, scale s0) 弹到 (a1, ty1, s1)。
     *
     * @param delayMs 错峰（芯片 50 ms 一颗）
     */
    public static ViewSpring animate(View v, MotionSpec.Spec spec, long delayMs, float a0, float a1, float ty0, float ty1,
                                     float s0, float s1, boolean instant, Runnable onEnd) {
        ViewSpring s = of(v);
        s.a0 = a0;
        s.a1 = a1;
        s.ty0 = ty0;
        s.ty1 = ty1;
        s.s0 = s0;
        s.s1 = s1;
        s.end = onEnd;
        if (instant) {
            s.running = false;
            v.removeCallbacks(s);
            s.p.v = 1f;
            s.p.t = 1f;
            s.p.vel = 0f;
            s.apply(1f);
            if (onEnd != null) onEnd.run();
            return s;
        }
        spec.applyTo(s.p);
        s.p.v = 0f;
        s.p.vel = 0f;
        s.p.t = 1f;
        s.apply(0f);
        s.startAt = SystemClock.uptimeMillis() + Math.max(0L, delayMs);
        s.last = 0L;
        if (!s.running) {
            s.running = true;
            v.postOnAnimation(s);
        }
        return s;
    }

    /** 文字 / 内容换入（TEXT_IN：scale 0.96→1 + alpha + translationY 6dp→0，永不回弹）。 */
    public static void textIn(View v, float density, long delayMs, boolean instant) {
        if (v == null) return;
        v.setPivotX(0f);
        v.setPivotY(v.getHeight() > 0 ? v.getHeight() / 2f : 0f);
        animate(v, MotionSpec.TEXT_IN, delayMs, 0f, 1f, 6f * density, 0f, 0.96f, 1f, instant, null);
    }

    /** 芯片入场（CHIP_IN：y+10dp、scale 0.90→1，bounce 0.20）。 */
    public static void chipIn(View v, float density, long delayMs, boolean instant) {
        if (v == null) return;
        v.setPivotX(v.getWidth() > 0 ? v.getWidth() / 2f : 0f);
        v.setPivotY(v.getHeight() > 0 ? v.getHeight() / 2f : 0f);
        animate(v, MotionSpec.CHIP_IN, delayMs, 0f, 1f, 10f * density, 0f, 0.90f, 1f, instant, null);
    }

    public static void cancel(View v) {
        Object o = v == null ? null : v.getTag(TAG_KEY);
        if (o instanceof ViewSpring) {
            ViewSpring s = (ViewSpring) o;
            s.running = false;
            v.removeCallbacks(s);
        }
    }

    private void apply(float k) {
        float a = a0 + (a1 - a0) * Math.max(0f, Math.min(1f, k));   // alpha 不随弹性超调
        v.setAlpha(a);
        v.setTranslationY(ty0 + (ty1 - ty0) * k);
        float sc = s0 + (s1 - s0) * k;
        v.setScaleX(sc);
        v.setScaleY(sc);
    }

    @Override
    public void run() {
        if (!running) return;
        long now = SystemClock.uptimeMillis();
        if (now < startAt) {
            v.postOnAnimation(this);
            return;
        }
        float dt = last == 0L ? 0.016f : Math.min(0.033f, (now - last) / 1000f);
        last = now;
        p.step(dt);
        apply(p.v);
        if (p.settled(0.001f)) {
            p.snap();
            apply(1f);
            running = false;
            Runnable e = end;
            end = null;
            if (e != null) e.run();
            return;
        }
        v.postOnAnimation(this);
    }
}
