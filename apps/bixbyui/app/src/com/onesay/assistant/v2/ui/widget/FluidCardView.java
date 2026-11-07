package com.onesay.assistant.v2.ui.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.widget.LinearLayout;

/**
 * 流体卡片容器（FluidCardView）
 *
 * 遵循 Apple HIG 卡片分层设计：
 * 1. 18dp 超感圆角与 1dp 微弱活力内描边；
 * 2. 赋予宽松舒展的内边距（水平 16dp，垂直 14dp），彻底解决原界面「视觉上有些挤」的逼仄感；
 * 3. 规范化底部留白外边距（12dp），建立清晰的内容节奏与呼吸感。
 */
public class FluidCardView extends LinearLayout {

    private int mCornerRadiusDp = 18;
    private int mBgColor = Color.parseColor("#18FFFFFF");
    private int mStrokeColor = Color.parseColor("#1FFFFFFF");

    public FluidCardView(Context context) {
        this(context, null);
    }

    public FluidCardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        // 宽松舒适的内边距，告别局促拥挤
        setPadding(dp(16), dp(14), dp(16), dp(14));
        applyBackground();
    }

    public void setCardStyle(int bgColor, int strokeColor) {
        this.mBgColor = bgColor;
        this.mStrokeColor = strokeColor;
        applyBackground();
    }

    public void setCornerRadiusDp(int radiusDp) {
        this.mCornerRadiusDp = radiusDp;
        applyBackground();
    }

    private void applyBackground() {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(mBgColor);
        gd.setCornerRadius(dp(mCornerRadiusDp));
        if (mStrokeColor != 0) {
            gd.setStroke(dp(1), mStrokeColor);
        }
        setBackground(gd);
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
