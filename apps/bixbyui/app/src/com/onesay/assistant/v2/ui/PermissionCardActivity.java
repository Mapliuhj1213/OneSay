package com.onesay.assistant.v2.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.onesay.assistant.v2.jobs.Perm;
import com.onesay.assistant.v2.jobs.PermissionCardPolicy;
import com.onesay.assistant.v2.jobs.PermissionCards;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 权限卡界面（lane-a 第 3 项）：逐项勾选这条定时 / 事件任务能用的工具；发出邮件 / 付款 / 红卡只列出、不能勾（每次执行单独确认）。
 * 不导出；只由本 App 打开。卡片数据在 {@link PermissionCards}（内存，10 分钟过期）。
 *
 * <p>2026-10-23（用户原始需求「逐项勾选才入库」）：可勾的项<b>默认不勾</b>；至少勾一项「确认授权」才可用（变灰 + 文案说明），
 * 规则在 {@link PermissionCardPolicy}。只勾一部分照旧：没授权的步骤到点进「等你确认」。
 */
public class PermissionCardActivity extends Activity {

    public static final String EXTRA_TOKEN = "perm_token";

    private String mToken;
    private final Map<Perm, CheckBox> mBoxes = new LinkedHashMap<Perm, CheckBox>();
    private Set<Perm> mGrantable = EnumSet.noneOf(Perm.class);
    private Button mOk;
    private TextView mHint;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        mToken = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_TOKEN);
        PermissionCards.Card c = PermissionCards.card(mToken);
        if (c == null) {
            Toast.makeText(this, "这张权限卡已过期", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1F2420"));
        bg.setCornerRadius(dp(20));
        root.setBackground(bg);

        root.addView(text("授权这条任务", 20, "#E6F0E0", true));
        root.addView(text(c.desc, 14, "#B8C4B2", false));
        root.addView(text("它运行时只能用你勾选的工具；越权的步骤不会执行，任务会停下来等你。", 12, "#8A9686", false));

        Set<Perm> grantable = c.grantable();
        mGrantable = grantable;
        if (!grantable.isEmpty()) root.addView(text("可以预先授权（逐项勾选，默认都不勾）", 14, "#C9E3B5", true));
        CompoundButton.OnCheckedChangeListener onCheck = new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean isChecked) {
                updateConfirm();
            }
        };
        for (Perm p : grantable) {
            CheckBox cb = new CheckBox(this);
            cb.setText(p.label);
            cb.setTextColor(Color.parseColor("#E6F0E0"));
            cb.setChecked(PermissionCardPolicy.defaultChecked(p));   // 先摆初值（不勾），再挂监听
            cb.setOnCheckedChangeListener(onCheck);
            mBoxes.put(p, cb);
            root.addView(cb);
        }
        Set<Perm> perRun = c.perRun();
        if (!perRun.isEmpty()) {
            root.addView(text("每次执行时单独确认（不能预先授权）", 14, "#F2C48D", true));
            for (Perm p : perRun) {
                CheckBox cb = new CheckBox(this);
                cb.setText(p.label);
                cb.setChecked(false);
                cb.setEnabled(false);
                cb.setTextColor(Color.parseColor("#8A9686"));
                root.addView(cb);
            }
        }
        if (grantable.isEmpty() && perRun.isEmpty()) root.addView(text("这条任务不需要额外权限。", 14, "#E6F0E0", false));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END);
        row.setPadding(0, dp(12), 0, 0);
        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                PermissionCards.cancel(mToken);
                Toast.makeText(PermissionCardActivity.this, "已取消，任务没有创建", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
        Button ok = new Button(this);
        mOk = ok;
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final Set<Perm> checked = checkedNow();
                if (!PermissionCardPolicy.confirmEnabled(mGrantable, checked)) {   // 双保险：按钮灰着时点不进来
                    updateConfirm();
                    return;
                }
                final String token = mToken;
                final android.content.Context app = getApplicationContext();
                com.onesay.assistant.v2.util.Threads.start("perm-confirm", new Runnable() {
                    @Override public void run() {
                        final String msg = PermissionCards.confirm(app, token, checked);
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                            @Override public void run() {
                                Toast.makeText(app, msg, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                });
                finish();
            }
        });
        row.addView(cancel);
        row.addView(ok);
        root.addView(row);
        mHint = text("", 12, "#8A9686", false);
        mHint.setGravity(Gravity.END);
        root.addView(mHint);
        updateConfirm();

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private Set<Perm> checkedNow() {
        Set<Perm> checked = EnumSet.noneOf(Perm.class);
        for (Map.Entry<Perm, CheckBox> e : mBoxes.entrySet()) if (e.getValue().isChecked()) checked.add(e.getKey());
        return checked;
    }

    /** 按勾选刷新「确认授权」：可用 / 变灰、文案写明已选几项，下面一行说明没勾的会怎样。 */
    private void updateConfirm() {
        if (mOk == null) return;
        Set<Perm> checked = checkedNow();
        boolean on = PermissionCardPolicy.confirmEnabled(mGrantable, checked);
        mOk.setEnabled(on);
        mOk.setAlpha(on ? PermissionCardPolicy.ALPHA_ON : PermissionCardPolicy.ALPHA_OFF);
        mOk.setText(PermissionCardPolicy.confirmLabel(mGrantable, checked));
        if (mHint != null) {
            mHint.setText(PermissionCardPolicy.hint(mGrantable, checked));
            mHint.setTextColor(Color.parseColor(on ? "#8A9686" : "#F2C48D"));
        }
    }

    @Override public void onBackPressed() {
        PermissionCards.cancel(mToken);
        super.onBackPressed();
    }

    private TextView text(String s, int sp, String color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.parseColor(color));
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(6), 0, dp(4));
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
