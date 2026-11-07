package com.onesay.assistant.v2.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ScrollView;

/**
 * 可设最大高度的 ScrollView —— 供底部 Sheet 的「会话内容区」使用。
 *
 * <p><b>解决的用户实测缺陷（2026-10-05）</b>：
 * 面板内容总高超过窗口可用高度（长答案 / 键盘弹起挤占空间）时，原先底部的
 * 「⌨ 文字输入栏 + 发送按钮 + 快捷建议条」会被布局顺序挤出窗口底边被裁掉 ——
 * 用户看到的现象是"点击文字弹出键盘后，输入栏和发送按钮全都不见了"。
 *
 * <p>修复思路：把"会长高的部分"（状态行 + 模式胶囊 + 问答卡）装进本容器，
 * 由父布局（FloatingSheetView.onMeasure）根据窗口可用高度给它设一个上限 ——
 * 超出的部分进入<b>滚动</b>，而不再挤占底部固定件（搜索栏/建议条）的空间。
 *
 * <p><b>实现注意</b>：{@link #setMaxHeightPx(int)} 只写字段、<b>不触发 requestLayout</b>——
 * 父布局会在同一次测量流程里紧接着重测（故意如此，避免在 layout 期间 requestLayout 告警）。
 */
public class MaxHeightScrollView extends ScrollView {

    /** 0 = 不限制（自然高度） */
    private int mMaxHeightPx = 0;

    /** 滚动回调（避免使用 API 23 的 View.OnScrollChangeListener，兼容性更稳）。 */
    public interface ScrollListener {
        void onScrollChanged(int scrollY, int contentHeight, int viewHeight);
    }

    private ScrollListener mScrollListener;

    public MaxHeightScrollView(Context context) {
        super(context);
        init();
    }

    public MaxHeightScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setFillViewport(false);
    }

    public void setScrollListener(ScrollListener listener) {
        mScrollListener = listener;
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (mScrollListener != null) {
            int ch = getChildCount() > 0 ? getChildAt(0).getHeight() : 0;
            mScrollListener.onScrollChanged(t, ch, getHeight());
        }
    }

    /**
     * 设置最大高度（px）。px &lt;= 0 表示取消限制。
     * 只写字段；由父布局在同一测量流程里重测生效。
     */
    public void setMaxHeightPx(int px) {
        mMaxHeightPx = px;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int mode = MeasureSpec.getMode(heightMeasureSpec);
        int size = MeasureSpec.getSize(heightMeasureSpec);
        if (mMaxHeightPx > 0 && mode != MeasureSpec.UNSPECIFIED && size > mMaxHeightPx) {
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(mMaxHeightPx, MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

}
