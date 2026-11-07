package com.onesay.assistant.v2.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * [roles] 通用「未安装 X，点此安装」面板底部提示（任何指向未安装包的能力都可复用，不是打车专用）。
 *
 * <p>只负责显示与点击回调；跳转链（应用宝 → market:// → 应用宝网页）由 {@code RoleHooks.openInstall} 每次点击时重新判定。
 * 插在 FloatingSheetView 最底部，默认 GONE。只在主线程调用。
 * 右侧加「不用了」——点了收起提示条；Listener 同时实现 {@link DismissListener} 时回调（取消 InstallWait 登记）。
 */
public class InstallPromptView extends LinearLayout {

    public static final String TAG_IN_SHEET = "install_prompt";
    /** 「不用了」按钮文字（JVM 测试读同一个常量）。 */
    public static final String DISMISS_TEXT = "不用了";

    public interface Listener {
        void onInstallClicked(String pkg, String label);
    }

    /** 点了「不用了」（或用户说「不用了 / 算了 / 不装了」由宿主关卡）。 */
    public interface DismissListener {
        void onDismiss(String pkg, String label);
    }

    private String mPkg;
    private String mLabel;
    private Listener mListener;
    private final TextView mMain;
    private final TextView mNo;

    public InstallPromptView(Context ctx) {
        super(ctx);
        setTag(TAG_IN_SHEET);
        setVisibility(GONE);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#33FFD60A"));
        bg.setStroke(dp(1), Color.parseColor("#66FFD60A"));
        bg.setCornerRadius(dp(12));
        setBackground(bg);
        mMain = new TextView(ctx);
        mMain.setSingleLine(true);
        mMain.setEllipsize(TextUtils.TruncateAt.END);
        mMain.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        mMain.setTextColor(Color.parseColor("#FFD60A"));
        mMain.setGravity(Gravity.CENTER_VERTICAL);
        mMain.setPadding(dp(12), dp(8), dp(8), dp(8));
        mMain.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mListener != null && mPkg != null) mListener.onInstallClicked(mPkg, mLabel);
            }
        });
        addView(mMain, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mNo = new TextView(ctx);
        mNo.setText(DISMISS_TEXT);
        mNo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        mNo.setTextColor(Color.parseColor("#B3FFFFFF"));
        mNo.setGravity(Gravity.CENTER);
        mNo.setMinHeight(dp(36));
        mNo.setPadding(dp(12), dp(8), dp(12), dp(8));
        mNo.setContentDescription("不用了，按钮");
        mNo.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        addView(mNo, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    public void show(String label, String pkg, Listener l) {
        mPkg = pkg;
        mLabel = label;
        mListener = l;
        mMain.setText("未安装 " + label + "，点此安装");
        mMain.setContentDescription("安装提示：未安装 " + label + "，点此安装");
        setVisibility(VISIBLE);
        com.onesay.assistant.v2.roles.InstallCardState.shown(com.onesay.assistant.v2.roles.InstallCardState.KIND_PROMPT, java.util.Collections.singletonList(pkg));
    }

    /** 「不用了」：收起并回调。 */
    public void dismiss() {
        String p = mPkg, l = mLabel;
        Listener li = mListener;
        hide();
        if (li instanceof DismissListener && p != null) ((DismissListener) li).onDismiss(p, l);
    }

    public void hide() {
        mPkg = null;
        setVisibility(GONE);
        com.onesay.assistant.v2.roles.InstallCardState.gone(com.onesay.assistant.v2.roles.InstallCardState.KIND_PROMPT);
    }

    public String pkg() {
        return mPkg;
    }

    public boolean showing() {
        return mPkg != null && getVisibility() == VISIBLE;
    }

    /** 找到或插入面板底部的提示条。 */
    public static InstallPromptView ensureIn(ViewGroup sheet) {
        if (sheet == null) return null;
        View existing = sheet.findViewWithTag(TAG_IN_SHEET);
        if (existing instanceof InstallPromptView) return (InstallPromptView) existing;
        InstallPromptView v = new InstallPromptView(sheet.getContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = v.dp(6);
        sheet.addView(v, lp);
        return v;
    }

    public static void hideIn(ViewGroup sheet) {
        if (sheet == null) return;
        View existing = sheet.findViewWithTag(TAG_IN_SHEET);
        if (existing instanceof InstallPromptView) ((InstallPromptView) existing).hide();
    }

    /** 用户说了「不用了」：在屏就按点「不用了」处理。返回是否真的关了。 */
    public static boolean dismissIn(ViewGroup sheet) {
        if (sheet == null) return false;
        View existing = sheet.findViewWithTag(TAG_IN_SHEET);
        if (existing instanceof InstallPromptView && ((InstallPromptView) existing).showing()) {
            ((InstallPromptView) existing).dismiss();
            return true;
        }
        return false;
    }

    int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
