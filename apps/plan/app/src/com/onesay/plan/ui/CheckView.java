package com.onesay.plan.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** 待办前面的圆形勾（自己画，避开系统把 ✓ 渲染成彩色表情）。 */
final class CheckView extends View {
    final boolean done;
    final int color;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();

    CheckView(Context c, boolean done, int color) {
        super(c);
        this.done = done;
        this.color = color;
        setContentDescription(done ? "标为没做完" : "完成");
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f - getResources().getDisplayMetrics().density;
        float cx = w / 2f, cy = h / 2f, sw = getResources().getDisplayMetrics().density * 2f;
        if (done) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            c.drawCircle(cx, cy, r, p);
            p.setColor(0xFFFFFFFF);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(sw);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            path.reset();
            path.moveTo(cx - r * 0.45f, cy + r * 0.02f);
            path.lineTo(cx - r * 0.1f, cy + r * 0.36f);
            path.lineTo(cx + r * 0.48f, cy - r * 0.3f);
            c.drawPath(path, p);
        } else {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(sw);
            p.setColor(0x55000000);
            c.drawCircle(cx, cy, r - sw / 2f, p);
        }
    }
}
