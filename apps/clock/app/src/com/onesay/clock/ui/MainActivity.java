package com.onesay.clock.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.onesay.clock.Prefs;
import com.onesay.clock.core.Alarm;
import com.onesay.clock.core.AlarmMath;
import com.onesay.clock.sched.Sched;

import java.util.List;
import java.util.TimeZone;

/** 闹钟列表（One UI：大标题「闹钟」+ 下次响铃倒计时；每个闹钟一张圆角卡片）。点卡片编辑，长按删除，开关即时重排。 */
public class MainActivity extends Activity {
    private LinearLayout list;
    private TextView sub;
    private Prefs prefs;

    int dp(float v) { return Ui.dp(this, v); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.window(this);
        prefs = new Prefs(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, dp(24));
        LinearLayout header = Ui.header(this, "闹钟", "");
        sub = (TextView) header.findViewWithTag("sub");
        col.addView(header);
        LinearLayout tb = Ui.toolbar(this);
        tb.addView(Ui.iconBtn(this, new Ui.Glyph(this, "plus", Ui.T1), "添加闹钟", new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(MainActivity.this, EditActivity.class)); }
        }));
        final View more = Ui.iconBtn(this, new Ui.Glyph(this, "more", Ui.T1), "更多选项", null);
        more.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                PopupMenu pm = new PopupMenu(MainActivity.this, more);
                pm.getMenu().add(0, 1, 0, "设置");
                pm.getMenu().add(0, 2, 1, "权限与引导");
                pm.getMenu().add(0, 3, 2, "可靠性检查");   // [borrow-1112]
                pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    public boolean onMenuItemClick(android.view.MenuItem it) {
                        startActivity(new Intent(MainActivity.this, it.getItemId() == 1 ? SettingsActivity.class
                                : it.getItemId() == 3 ? CheckActivity.class : OnboardActivity.class));
                        return true;
                    }
                });
                pm.show();
            }
        });
        tb.addView(more);
        col.addView(tb);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list, new LinearLayout.LayoutParams(-1, -2));
        sv.addView(col);
        root.addView(sv, Ui.match());
        setContentView(root);
        if (!prefs.onboarded()) startActivity(new Intent(this, OnboardActivity.class));
    }

    @Override protected void onResume() {
        super.onResume();
        render();
    }

    void render() {
        list.removeAllViews();
        List<Alarm> as = prefs.alarms();
        long now = System.currentTimeMillis();
        AlarmMath.Event e = AlarmMath.earliest(as, prefs.snoozes(), now, TimeZone.getDefault());
        sub.setText(AlarmMath.untilLabel(e == null ? 0 : e.at, now));
        healthBanner(as);
        if (as.isEmpty()) {
            TextView t = Ui.text(this, "没有闹钟\n点右上角「+」添加一个", 16, Ui.T2, Ui.regular());
            t.setGravity(Gravity.CENTER);
            t.setLineSpacing(dp(6), 1f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(60);
            list.addView(t, lp);
            return;
        }
        for (final Alarm a : as) list.addView(item(a), Ui.cardLp(this));
    }

    static final String[] DAYS = {"日", "一", "二", "三", "四", "五", "六"};

    /** [borrow-1112] 有开着的闹钟、且自检发现问题时，列表顶上放一条提示，点了去自检页。 */
    void healthBanner(List<Alarm> as) {
        boolean anyOn = false;
        for (Alarm a : as) anyOn |= a.on;
        if (!anyOn) return;
        List<com.onesay.clock.core.Health.Issue> l = CheckActivity.issues(this);
        if (l.isEmpty()) return;
        LinearLayout card = Ui.card(this);
        View r = Ui.add(card, Ui.row(this, com.onesay.clock.core.Health.banner(l), l.get(0).title, null, new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(MainActivity.this, CheckActivity.class)); }
        }));
        Ui.setSubColor(r, Ui.WARN);
        list.addView(card, Ui.cardLp(this));
    }

    View item(final Alarm a) {
        LinearLayout card = Ui.card(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(24), dp(16), dp(16), dp(16));
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        String label = a.label.length() > 0 ? a.label : "";
        LinearLayout tags = new LinearLayout(this);
        tags.setOrientation(LinearLayout.HORIZONTAL);
        tags.setGravity(Gravity.CENTER_VERTICAL);
        if (label.length() > 0) tags.addView(Ui.text(this, label, 13.5f, a.on ? Ui.T2 : Ui.T_OFF, Ui.regular()));
        if (a.brief) {
            TextView chip = Ui.text(this, "智能播报", 11, a.on ? Ui.ACCENT : Ui.T_OFF, Ui.medium());
            chip.setPadding(dp(7), dp(2), dp(7), dp(2));
            chip.setBackground(Ui.round((a.on ? Ui.ACCENT : Ui.T_OFF) & 0x1FFFFFFF, dp(8)));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
            if (label.length() > 0) clp.leftMargin = dp(8);
            tags.addView(chip, clp);
        }
        if (tags.getChildCount() > 0) {
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
            tlp.bottomMargin = dp(6);
            left.addView(tags, tlp);
        }
        LinearLayout time = new LinearLayout(this);
        time.setOrientation(LinearLayout.HORIZONTAL);
        time.setGravity(Gravity.BOTTOM);
        boolean h24 = DateFormat.is24HourFormat(this);
        int color = a.on ? Ui.T1 : Ui.T_OFF;
        if (!h24) {
            TextView ap = Ui.text(this, a.hour < 12 ? "上午" : "下午", 15, color, Ui.regular());
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
            alp.rightMargin = dp(5);
            alp.bottomMargin = dp(5);
            time.addView(ap, alp);
        }
        int hh = h24 ? a.hour : (a.hour % 12 == 0 ? 12 : a.hour % 12);
        time.addView(Ui.text(this, (h24 && hh < 10 ? "0" : "") + hh + ":" + (a.minute < 10 ? "0" : "") + a.minute, 40, color, Ui.light()));
        left.addView(time);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView days = Ui.text(this, "", 13, Ui.T2, Ui.regular());
        days.setText(daysSpan(a));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.rightMargin = dp(10);
        row.addView(days, dlp);
        Switch s = Ui.sw(this, a.on, new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                Alarm x = prefs.alarm(a.id);
                if (x == null) return;
                x.on = on;
                prefs.put(x);
                if (!on) { com.onesay.clock.core.SnoozeBook sb = prefs.snoozes(); if (sb.remove(x.id)) prefs.saveSnoozes(sb); }
                Sched.rescheduleNow(MainActivity.this, "toggle");
                render();
            }
        });
        row.addView(s);
        card.addView(row);
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(MainActivity.this, EditActivity.class).putExtra("id", a.id)); }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {                // 触感：返回 true 时框架自动 performHapticFeedback(LONG_PRESS)（与三星 7.0.92 一致，不另调）
                new AlertDialog.Builder(MainActivity.this).setMessage("删除这个闹钟？")
                        .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { prefs.delete(a.id); Sched.rescheduleNow(MainActivity.this, "delete"); render(); }
                        }).setNegativeButton("取消", null).show();
                return true;
            }
        });
        card.setForeground(Ui.ripple(null, dp(26)));
        return card;
    }

    /** 单次：「明天」/「今天」；重复：七个字，选中的蓝色；[borrow-1112] 其它规则写规则名，暂停中再加一行。 */
    CharSequence daysSpan(Alarm a) {
        CharSequence base = baseSpan(a);
        String p = com.onesay.clock.core.Pause.label(a, System.currentTimeMillis(), TimeZone.getDefault());
        if (p.length() == 0) return base;
        SpannableStringBuilder sb = new SpannableStringBuilder(base);
        sb.append('\n');
        int st = sb.length();
        sb.append(p);
        sb.setSpan(new ForegroundColorSpan(a.on ? Ui.WARN : Ui.T_OFF), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    CharSequence baseSpan(Alarm a) {
        if (a.rule != com.onesay.clock.core.RepeatRule.WEEKLY) return com.onesay.clock.core.RepeatRule.shortLabel(a);
        if (!a.repeats()) {
            long nx = AlarmMath.next(a, System.currentTimeMillis(), TimeZone.getDefault());
            if (nx <= 0) { Alarm t = new Alarm(); t.hour = a.hour; t.minute = a.minute; nx = AlarmMath.next(t, System.currentTimeMillis(), TimeZone.getDefault()); }
            boolean today = AlarmMath.dayKey(nx, TimeZone.getDefault()) == AlarmMath.dayKey(System.currentTimeMillis(), TimeZone.getDefault());
            return today ? "今天" : "明天";
        }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < 7; i++) {
            int st = sb.length();
            sb.append(DAYS[i]);
            if (i < 6) sb.append(' ');
            boolean on = a.onDay(i);
            sb.setSpan(new ForegroundColorSpan(on ? (a.on ? Ui.ACCENT : Ui.T2) : Ui.T_OFF), st, st + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }
}
