package com.onesay.clock.ring;

import com.onesay.clock.core.AlertUi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;

/**
 * 响铃页的自绘控件：
 *   Bg      —— sec.color.gradient.view.RadialGradientView：三层径向光晕（radial_gradient.gm 剖面 33 档），入场 + 4 s 呼吸；
 *   Stop    —— AlertSlidingTab + AlertSlidingTabHandle：105dp 黑底细描边圆环 + 175dp 呼吸光晕，按住后 274dp 按压圈、
 *              在圆内按下、拖到 0.95×直径 解除；TalkBack 等「点按模式」下按下即停；
 *   Glyph   —— 顶部小时钟、−/+、提供者小标志、天气线条图标（全部代码绘制，不含任何三星位图；移植自 v2 ac0e72b/9bb14f0）。
 */
final class RingViews {
    private RingViews() { }

    static float dp(Context c, float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()); }

    static float sp(Context c, float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, c.getResources().getDisplayMetrics()); }

    // ------------------------------------------------------------------ 背景
    /**
     * 光晕颜色：原版 full_alert_background_gradient_circle_color_first #5C3B96 / third #412CAB / second #000000（FullAlertActivity.setColors），
     * 透明度 setAlphas(0.85, 1.0, 0.9)。背景是唯一允许参考视频调的部分：first 取视频紫 #8020FF；third 取视频里贪睡行一带的蓝 #2A2BFF。
     */
    static final int BG_FIRST = 0xFF8020FF, BG_THIRD = 0xFF2A2BFF;
    /**
     * 蓝层（gradient3）范围与透明度：原版 scale3 2.0 / alpha 0.9（中心 (0.45, 0.43)·W 不变）。按原版几何，贪睡行（≈1.1W）在蓝层
     * 0.5–0.66 半径处，呼吸低谷时几乎全黑；视频里那一带仍明显发蓝（胶囊被染蓝）。故只把蓝层放大到 2.6、透明度 1.0：
     * 低谷时贪睡行 ≈ #0D0E54、峰值 ≈ #1A1A9C，停止圆环一带仍接近黑（峰值 ≈ #050521）。紫层 / 黑层保持原版。
     */
    static final float BG3_SCALE = 2.6f, BG3_ALPHA = 1.0f;

    /** radial_gradient.gm 径向剖面（半径 0 → 1，33 档，平滑后取样）。 */
    static final float[] PROFILE = {0.996f, 0.995f, 0.988f, 0.976f, 0.959f, 0.938f, 0.913f, 0.884f, 0.851f, 0.814f, 0.775f,
            0.733f, 0.689f, 0.643f, 0.595f, 0.546f, 0.497f, 0.449f, 0.401f, 0.353f, 0.307f, 0.263f, 0.220f, 0.181f, 0.145f,
            0.112f, 0.083f, 0.057f, 0.036f, 0.020f, 0.008f, 0.001f, 0f};

    static final class Bg extends View {
        // LTR（原版 init/setAlphas）：gradient1 (0.25, 0.43)·W scale 1.3 alpha 0.85；gradient2 (1.2, −0.09)·W scale 1.8 alpha 1.0；
        // gradient3 (0.45, 0.43)·W 原版 scale 2.0 alpha 0.9 → 这里 BG3_SCALE / BG3_ALPHA（见上）。坐标 x、y 都乘宽度，同原版。
        private final Shader s1, s2, s3;
        private final Paint p = new Paint(Paint.DITHER_FLAG);
        private final Matrix m = new Matrix();
        private final long t0 = SystemClock.uptimeMillis();
        private static final float UNIT = 1000f;
        boolean running = true;

        Bg(Context c) {
            super(c);
            s1 = shader(BG_FIRST);
            s2 = shader(Color.BLACK);
            s3 = shader(BG_THIRD);
            p.setDither(true);
        }

        static Shader shader(int color) {
            int n = PROFILE.length;
            int[] cs = new int[n];
            float[] pos = new float[n];
            for (int i = 0; i < n; i++) {
                cs[i] = (Math.round(PROFILE[i] * 255f) << 24) | (color & 0xFFFFFF);
                pos[i] = i / (float) (n - 1);
            }
            return new RadialGradient(0, 0, UNIT, cs, pos, Shader.TileMode.CLAMP);
        }

        private void layer(Canvas cv, Shader s, float gx, float gy, float scale, float alpha) {
            if (alpha <= 0f) return;
            float w = getWidth(), r = scale * w / 2f;          // baseScale = W / (2·2048)，剖面半径 2048
            m.setScale(r / UNIT, r / UNIT);
            m.postTranslate(gx * w, gy * w);
            s.setLocalMatrix(m);
            p.setShader(s);
            p.setAlpha(Math.round(Math.min(1f, alpha) * 255f));
            cv.drawRect(0, 0, getWidth(), getHeight(), p);
        }

        @Override protected void onDraw(Canvas cv) {
            cv.drawColor(Color.BLACK);
            float[] b = AlertUi.breath(SystemClock.uptimeMillis() - t0);
            layer(cv, s3, 0.45f, 0.43f, BG3_SCALE * b[2], BG3_ALPHA * b[3]);
            layer(cv, s2, 1.2f, -0.09f, 1.8f, 1.0f);
            layer(cv, s1, 0.25f, 0.43f, 1.3f * b[0], 0.85f * b[1]);
            if (running) postInvalidateOnAnimation();
        }
    }

    // ------------------------------------------------------------------ 停止圆环
    interface StopHost {
        void onStopFired();

        void onGrab(boolean grabbed);

        /** 滑动按住的触感已发出、手已松开：响铃振动若被打断，由服务重新起波形。 */
        void onHapticDone();
    }

    static final int RING_FILL = 0xFF000000, RING_STROKE = 0x99999BFF, HALO = 0x406062F0, TEXT = 0xFFFAFAFA;

    static final class Stop extends View {
        final float d, r, haloD, pressD, stroke;
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
                halo = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path ring = new Path();
        final StopHost host;
        final String label;
        long haloT0 = SystemClock.uptimeMillis(), pressT0;
        boolean grabbed, releasing, done;
        float hole;                                            // 按压圈中间挖空的半径（0 = 不挖）

        Stop(Context c, StopHost host, String label) {
            super(c);
            this.host = host;
            this.label = label;
            d = dp(c, 105); r = d / 2f; haloD = dp(c, 175); pressD = dp(c, 274); stroke = dp(c, 1);
            fill.setColor(RING_FILL);
            line.setColor(RING_STROKE);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(stroke);
            halo.setColor(HALO);
            text.setColor(TEXT);
            text.setTextSize(sp(c, 17));
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            text.setTextAlign(Paint.Align.CENTER);
            setContentDescription(label + " 按钮, 滑动以解除闹钟。");
            setClickable(true);
            setOnClickListener(new OnClickListener() {         // 只有无障碍服务会走到这里（触摸不调用 performClick）
                public void onClick(View v) { fire(); }
            });
        }

        void fire() {
            if (done) return;
            done = true;
            invalidate();
            host.onStopFired();
        }

        boolean tapMode() {
            AccessibilityManager am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
            return am != null && am.isEnabled() && am.isTouchExplorationEnabled();
        }

        @Override protected void onDraw(Canvas cv) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            long now = SystemClock.uptimeMillis();
            boolean anim = false;
            if (!grabbed && !releasing && !done) {             // 待机：175dp 光晕 缩放 0.6→1、后半程淡出
                float[] h = AlertUi.halo(now - haloT0);
                halo.setAlpha(Math.round(0x40 * h[1]));
                cv.drawCircle(cx, cy, haloD / 2f * h[0], halo);
                anim = true;
            }
            if (grabbed || releasing) {                        // 274dp 按压圈：出现 0.2→1 / 0→1，收回 1→0.3 / 1→0，各 300 ms
                float x = Math.min(1f, (now - pressT0) / 300f), s, a;
                if (grabbed) { float e = 1f - (1f - x) * (1f - x); s = 0.2f + 0.8f * e; a = e; }
                else { float e = x * x; s = 1f - 0.7f * e; a = 1f - e; }
                if (!grabbed && x >= 1f) releasing = false;
                halo.setAlpha(Math.round(0x40 * a));
                float outer = pressD / 2f * s;
                ring.rewind();
                ring.setFillType(Path.FillType.EVEN_ODD);
                ring.addCircle(cx, cy, outer, Path.Direction.CW);
                if (hole > 0f && hole < outer) ring.addCircle(cx, cy, hole * s, Path.Direction.CW);
                cv.drawPath(ring, halo);
                anim = anim || x < 1f || releasing;
            }
            cv.drawCircle(cx, cy, r, fill);
            cv.drawCircle(cx, cy, r - stroke / 2f, line);
            Paint.FontMetrics fm = text.getFontMetrics();
            cv.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2f, text);
            if (anim) postInvalidateOnAnimation();
        }

        private boolean grabBuzz;

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (done) return true;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            double dist = Math.hypot(e.getX() - cx, e.getY() - cy);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!AlertUi.inHandle(dist, d)) return false;
                    if (tapMode()) { fire(); return true; }
                    grabbed = true; releasing = false; hole = 0f; pressT0 = SystemClock.uptimeMillis();
                    grabBuzz = com.onesay.clock.ui.Haptics.fire(this, com.onesay.clock.core.HapticMap.Ev.SLIDE_GRAB);
                    host.onGrab(true);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!grabbed) return true;
                    if (AlertUi.dismissReached(dist, d)) {
                        grabbed = false;
                        com.onesay.clock.ui.Haptics.fire(this, com.onesay.clock.core.HapticMap.Ev.SLIDE_TRIGGER);
                        fire();
                        return true;
                    }
                    float w = (float) (pressD * dist / d);   // 原版 clearInnerCircle：按压圈宽 × 比例，小于圆环时取圆环半径
                    hole = w > d ? w / 2f : r;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (grabbed) {
                        grabbed = false; releasing = true; pressT0 = SystemClock.uptimeMillis();
                        haloT0 = pressT0 + 300;                // 收回动画后光晕从头开始
                        host.onGrab(false);
                        if (grabBuzz) { grabBuzz = false; host.onHapticDone(); }   // 按住时的 semVibrate 可能打断响铃波形 → 让服务重起
                        invalidate();
                    }
                    return true;
                default:
                    return true;
            }
        }
    }

    // ------------------------------------------------------------------ 小图形
    abstract static class Glyph extends Drawable {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final int size;

        Glyph(int color, int size) { p.setColor(color); this.size = size; }

        @Override public int getIntrinsicWidth() { return size; }

        @Override public int getIntrinsicHeight() { return size; }

        @Override public void setAlpha(int a) { p.setAlpha(a); invalidateSelf(); }

        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** 顶部小时钟（clock_alarm_ic_alert 为三星私有 SPR 矢量，这里按视频画一个等大的表盘）。 */
    static final class ClockGlyph extends Glyph {
        ClockGlyph(int color, int size) { super(color, size); }

        @Override public void draw(Canvas c) {
            RectF b = new RectF(getBounds());
            float s = Math.min(b.width(), b.height()), cx = b.centerX(), cy = b.centerY(), rr = s * 0.42f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(s * 0.1f);
            p.setStrokeCap(Paint.Cap.ROUND);
            c.drawCircle(cx, cy, rr, p);
            c.drawLine(cx, cy, cx, cy - rr * 0.55f, p);
            c.drawLine(cx, cy, cx + rr * 0.45f, cy, p);
        }
    }

    /** −/+：24×24 视口里 2.5 宽的横 / 竖条（ic_clock_alarm_alert_ic_minus/add），矢量自带透明度 0.75。 */
    static final class PlusMinus extends Glyph {
        final boolean plus;

        PlusMinus(boolean plus, int size) {
            super(TEXT, size);
            this.plus = plus;
            p.setStyle(Paint.Style.FILL);
        }

        private final Path path = new Path();

        @Override public void draw(Canvas c) {
            RectF b = new RectF(getBounds());
            float k = b.width() / 24f;
            path.rewind();                                     // 一条路径填充：「+」交叉处不会叠两次透明度
            path.setFillType(Path.FillType.WINDING);
            path.addRect(b.left + 2.2f * k, b.top + 10.8f * k, b.left + 21.8f * k, b.top + 13.3f * k, Path.Direction.CW);
            if (plus) path.addRect(b.left + 10.7f * k, b.top + 2.2f * k, b.left + 13.2f * k, b.top + 21.8f * k, Path.Direction.CW);
            c.drawPath(path, p);
        }
    }

    /** 「提供者：」后面的小标志：简洁的圆形标记（不使用任何品牌位图）。 */
    static final class CpMark extends Glyph {
        CpMark(int color, int size) { super(color, size); }

        @Override public void draw(Canvas c) {
            RectF b = new RectF(getBounds());
            float s = Math.min(b.width(), b.height()), cx = b.centerX(), cy = b.centerY();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(s * 0.14f);
            c.drawCircle(cx, cy, s * 0.40f, p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, s * 0.17f, p);
        }
    }

    /** 天气线条图标（代码绘制，白色描边）：sunny / clear(月亮) / cloudy / rain / snow / thunderstorm / fog / windy。 */
    static final class WxGlyph extends Glyph {
        final String kind;
        private final Path path = new Path();

        WxGlyph(String kind, int color, int size) {
            super(color, size);
            this.kind = kind;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        private void cloud(Canvas c, float x, float y, float k) {
            path.rewind();
            path.moveTo(x + 13 * k, y + 34 * k);
            path.lineTo(x + 37 * k, y + 34 * k);
            path.cubicTo(x + 45 * k, y + 34 * k, x + 47 * k, y + 22 * k, x + 39 * k, y + 20 * k);
            path.cubicTo(x + 38 * k, y + 10 * k, x + 25 * k, y + 7 * k, x + 21 * k, y + 16 * k);
            path.cubicTo(x + 13 * k, y + 13 * k, x + 7 * k, y + 21 * k, x + 10 * k, y + 25 * k);
            path.cubicTo(x + 3 * k, y + 27 * k, x + 5 * k, y + 34 * k, x + 13 * k, y + 34 * k);
            path.close();
            c.drawPath(path, p);
        }

        @Override public void draw(Canvas c) {
            RectF b = new RectF(getBounds());
            float k = Math.min(b.width(), b.height()) / 50f, x = b.left, y = b.top;
            p.setStrokeWidth(2.2f * k);
            if ("sunny".equals(kind)) {
                c.drawCircle(x + 25 * k, y + 25 * k, 9 * k, p);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    c.drawLine(x + 25 * k + (float) Math.cos(a) * 14 * k, y + 25 * k + (float) Math.sin(a) * 14 * k,
                            x + 25 * k + (float) Math.cos(a) * 19 * k, y + 25 * k + (float) Math.sin(a) * 19 * k, p);
                }
            } else if ("clear".equals(kind)) {
                path.rewind();
                path.addArc(new RectF(x + 11 * k, y + 11 * k, x + 39 * k, y + 39 * k), 300, 280);
                path.cubicTo(x + 24 * k, y + 32 * k, x + 22 * k, y + 16 * k, x + 32 * k, y + 13 * k);
                c.drawPath(path, p);
            } else {
                cloud(c, x, y - 2 * k, k);
                if ("rain".equals(kind)) {
                    for (int i = 0; i < 3; i++) c.drawLine(x + (17 + 8 * i) * k, y + 38 * k, x + (14 + 8 * i) * k, y + 45 * k, p);
                } else if ("snow".equals(kind)) {
                    for (int i = 0; i < 3; i++) c.drawCircle(x + (16 + 9 * i) * k, y + 42 * k, 1.3f * k, p);
                } else if ("thunderstorm".equals(kind)) {
                    path.rewind();
                    path.moveTo(x + 27 * k, y + 35 * k); path.lineTo(x + 22 * k, y + 42 * k); path.lineTo(x + 28 * k, y + 42 * k);
                    path.lineTo(x + 23 * k, y + 49 * k);
                    c.drawPath(path, p);
                } else if ("fog".equals(kind)) {
                    c.drawLine(x + 10 * k, y + 39 * k, x + 40 * k, y + 39 * k, p);
                    c.drawLine(x + 14 * k, y + 45 * k, x + 36 * k, y + 45 * k, p);
                } else if ("windy".equals(kind)) {
                    c.drawLine(x + 8 * k, y + 40 * k, x + 34 * k, y + 40 * k, p);
                    c.drawLine(x + 14 * k, y + 46 * k, x + 40 * k, y + 46 * k, p);
                }
            }
        }
    }
}
