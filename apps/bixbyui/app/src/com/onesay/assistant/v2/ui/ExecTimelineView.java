package com.onesay.assistant.v2.ui;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.onesay.assistant.v2.timeline.ExecTimeline;

import java.util.List;

/**
 * ★ feat/api-first（2026-11-11）：回答上方那一行可折叠的执行时间轴（视图）。数据只来自 {@link ExecTimeline}。
 *
 * <p>标题行：执行中「正在调用 X ▲」，完成后「已完成 ▼」，点一下展开 / 折叠；展开后左侧一条细竖线，
 * 每步一行：✓ 已调用 X（灰白）、● 进行中（灰点）、✗ 失败带原因（红）、? 等你确认（橙）、■ 已停止（灰）。
 */
public final class ExecTimelineView extends LinearLayout {

    private static final int T_TITLE = 0xFFB2AFAA;
    private static final int T_ROW = 0xFFD8D5D0;
    private static final int T_DIM = 0xFF807E7A;
    private static final int C_FAIL = 0xFFFF6B5E;
    private static final int C_WAIT = 0xFFFF9F0A;
    private static final int C_OK = 0xFF8FD19E;
    private static final int LINE = 0x33FFFFFF;

    private final TextView mHeader;
    private final LinearLayout mWrap;
    private final LinearLayout mBody;
    private ExecTimeline mModel;

    public ExecTimelineView(Context c) {
        super(c);
        setOrientation(VERTICAL);
        mHeader = new TextView(c);
        mHeader.setTextColor(T_TITLE);
        mHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        mHeader.setPadding(0, dp(2), 0, dp(4));
        mHeader.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (mModel == null) return;
                mModel.toggle();
                render();
            }
        });
        addView(mHeader, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        // 展开区：左边一条 1dp 竖线（时间轴的「轴」）+ 右边每步一行
        mWrap = new LinearLayout(c);
        mWrap.setOrientation(HORIZONTAL);
        View rule = new View(c);
        rule.setBackgroundColor(LINE);
        LayoutParams rlp = new LayoutParams(dp(1), LayoutParams.MATCH_PARENT);
        rlp.leftMargin = dp(8);
        rlp.topMargin = dp(4);
        rlp.bottomMargin = dp(4);
        mWrap.addView(rule, rlp);
        mBody = new LinearLayout(c);
        mBody.setOrientation(VERTICAL);
        mBody.setPadding(dp(4), dp(2), 0, dp(4));
        mWrap.addView(mBody, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        addView(mWrap, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        setVisibility(GONE);
    }

    public ExecTimeline model() {
        return mModel;
    }

    /** 换一份数据（null = 隐藏）。主线程。 */
    public void bind(ExecTimeline t) {
        mModel = t;
        render();
    }

    /** 数据变了，重画（主线程）。 */
    public void render() {
        ExecTimeline t = mModel;
        if (t == null || !t.visible()) {
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);
        mHeader.setText(t.header());
        mHeader.setContentDescription(t.title() + (t.expanded() ? "，已展开，点按收起" : "，点按展开每一步"));
        mBody.removeAllViews();
        if (!t.expanded()) {
            mWrap.setVisibility(GONE);
            return;
        }
        mWrap.setVisibility(VISIBLE);
        List<ExecTimeline.Row> rows = t.rows();
        for (ExecTimeline.Row r : rows) mBody.addView(row(r.icon(), r.text(), r.state));
        if (t.phase() == ExecTimeline.Phase.STOPPED) mBody.addView(row("■", "已停止", ExecTimeline.State.STOPPED));
    }

    private View row(String icon, String text, ExecTimeline.State st) {
        LinearLayout l = new LinearLayout(getContext());
        l.setOrientation(HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(0, dp(2), 0, dp(2));
        TextView i = new TextView(getContext());
        i.setText(icon);
        i.setTextSize(TypedValue.COMPLEX_UNIT_SP, st == ExecTimeline.State.RUNNING ? 9f : 12f);
        int c = st == ExecTimeline.State.FAILED ? C_FAIL : st == ExecTimeline.State.WAITING ? C_WAIT
                : st == ExecTimeline.State.DONE ? C_OK : T_DIM;
        i.setTextColor(c);
        i.setGravity(Gravity.CENTER);
        l.addView(i, new LayoutParams(dp(18), LayoutParams.WRAP_CONTENT));
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tv.setTextColor(st == ExecTimeline.State.FAILED ? C_FAIL : st == ExecTimeline.State.RUNNING || st == ExecTimeline.State.STOPPED ? T_DIM : T_ROW);
        l.addView(tv, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        return l;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
