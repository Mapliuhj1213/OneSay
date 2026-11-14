package com.onesay.clock.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.DialogInterface;
import android.widget.DatePicker;
import android.widget.Toast;

import com.onesay.clock.core.Alarm;
import com.onesay.clock.core.AlarmMath;
import com.onesay.clock.core.Pause;
import com.onesay.clock.core.RepeatRule;

import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * [borrow-1112] 编辑页的「重复规则」「暂停」对话框与预览文字。规则与暂停的计算都在 core（RepeatRule / Pause，JVM 测试）。
 * 思路来源：Wake Me（WeckMich，GPL-3.0）——只读思路，代码自写。
 */
final class RuleUi {
    private RuleUi() { }

    static final String[] WD_ORDER = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    static final int[] WD_BIT = {1, 2, 3, 4, 5, 6, 0};
    static final int[] EVERY = {2, 3, 4, 5, 6, 7, 10, 14, 21, 28};
    static final int[] SKIP_N = {1, 2, 3, 4, 5, 7, 10};

    static int today() { return AlarmMath.dayKey(System.currentTimeMillis(), TimeZone.getDefault()); }

    /** 选重复规则。changed 在规则改完后调用（刷新预览）。 */
    static void pickRule(final Activity act, final Alarm a, final Runnable changed) {
        String[] items = {"每周（按上面选的星期）", "大小周（两周交替）", "每月第几个周几", "每隔几天"};
        new AlertDialog.Builder(act).setTitle("重复规则").setSingleChoiceItems(items, a.rule, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                d.dismiss();
                if (w == 0) { a.rule = RepeatRule.WEEKLY; changed.run(); }
                else if (w == 1) pickBiweekly(act, a, changed);
                else if (w == 2) pickMonthly(act, a, changed);
                else pickEvery(act, a, changed);
            }
        }).setNegativeButton("取消", null).show();
    }

    static void pickBiweekly(final Activity act, final Alarm a, final Runnable changed) {
        final int[] maskB = {a.rule == RepeatRule.BIWEEKLY ? a.daysB : 0x3E};
        final int maskA = a.days == 0 ? 0x7E : a.days;
        boolean[] checked = new boolean[7];
        for (int i = 0; i < 7; i++) checked[i] = (maskB[0] & (1 << WD_BIT[i])) != 0;
        new AlertDialog.Builder(act).setTitle("另一周响哪几天").setMultiChoiceItems(WD_ORDER, checked, new DialogInterface.OnMultiChoiceClickListener() {
            public void onClick(DialogInterface d, int i, boolean on) { maskB[0] = on ? maskB[0] | (1 << WD_BIT[i]) : maskB[0] & ~(1 << WD_BIT[i]); }
        }).setPositiveButton("下一步", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                String[] which = {"这周按上面选的星期响", "下周才按上面选的星期响"};
                new AlertDialog.Builder(act).setTitle("这周是哪一周").setItems(which, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d2, int k) {
                        a.rule = RepeatRule.BIWEEKLY;
                        a.days = maskA;
                        a.daysB = maskB[0];
                        a.anchor = k == 0 ? today() : RepeatRule.addDays(today(), 7);
                        changed.run();
                    }
                }).show();
            }
        }).setNegativeButton("取消", null).show();
    }

    static void pickMonthly(final Activity act, final Alarm a, final Runnable changed) {
        String[] nth = {"第 1 个", "第 2 个", "第 3 个", "第 4 个", "最后一个"};
        new AlertDialog.Builder(act).setTitle("每月第几个").setItems(nth, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, final int n) {
                new AlertDialog.Builder(act).setTitle("周几").setItems(WD_ORDER, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d2, int k) {
                        a.rule = RepeatRule.MONTHLY_NTH;
                        a.nth = n == 4 ? -1 : n + 1;
                        a.nthDow = WD_BIT[k];
                        changed.run();
                    }
                }).show();
            }
        }).setNegativeButton("取消", null).show();
    }

    static void pickEvery(final Activity act, final Alarm a, final Runnable changed) {
        String[] items = new String[EVERY.length];
        for (int i = 0; i < EVERY.length; i++) items[i] = "每隔 " + EVERY[i] + " 天";
        new AlertDialog.Builder(act).setTitle("每隔几天").setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, final int i) {
                String[] from = {"从今天算起", "从明天算起"};
                new AlertDialog.Builder(act).setTitle("从哪天开始").setItems(from, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d2, int k) {
                        a.rule = RepeatRule.EVERY_N;
                        a.everyN = EVERY[i];
                        a.anchor = RepeatRule.addDays(today(), k);
                        changed.run();
                    }
                }).show();
            }
        }).setNegativeButton("取消", null).show();
    }

    /** 暂停：暂停到某天 / 跳过接下来 N 次 / 取消暂停。 */
    static void pickPause(final Activity act, final Alarm a, final Runnable changed) {
        if (!a.repeats()) { Toast.makeText(act, "只响一次的闹钟不能暂停，直接关掉即可", Toast.LENGTH_SHORT).show(); return; }
        String[] items = a.hasPause() ? new String[]{"暂停到某天", "跳过接下来几次", "取消暂停，马上恢复"} : new String[]{"暂停到某天", "跳过接下来几次"};
        new AlertDialog.Builder(act).setTitle("暂停（不删除闹钟）").setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                if (w == 0) pickResume(act, a, changed);
                else if (w == 1) pickSkip(act, a, changed);
                else { Pause.clear(a); changed.run(); }
            }
        }).setNegativeButton("取消", null).show();
    }

    static void pickResume(final Activity act, final Alarm a, final Runnable changed) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, 7);
        DatePickerDialog dp = new DatePickerDialog(act, new DatePickerDialog.OnDateSetListener() {
            public void onDateSet(DatePicker v, int y, int m, int d) {
                String err = Pause.until(a, y * 10000 + (m + 1) * 100 + d, today());
                if (err != null) Toast.makeText(act, err, Toast.LENGTH_SHORT).show();
                changed.run();
            }
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        dp.setTitle("哪天起恢复响铃");
        dp.getDatePicker().setMinDate(System.currentTimeMillis() + 86400_000L);
        dp.show();
    }

    static void pickSkip(final Activity act, final Alarm a, final Runnable changed) {
        String[] items = new String[SKIP_N.length];
        for (int i = 0; i < SKIP_N.length; i++) items[i] = "跳过接下来 " + SKIP_N[i] + " 次";
        new AlertDialog.Builder(act).setTitle("跳过几次").setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int i) {
                List<Integer> added = Pause.skipNext(a, SKIP_N[i], System.currentTimeMillis(), TimeZone.getDefault());
                if (added.isEmpty()) Toast.makeText(act, "接下来没有要响的，不用跳过", Toast.LENGTH_SHORT).show();
                changed.run();
            }
        }).setNegativeButton("取消", null).show();
    }

    /** 预览：接下来 5 次 +（暂停中）会跳过的日期。 */
    static String preview(Alarm a) {
        long now = System.currentTimeMillis();
        TimeZone tz = TimeZone.getDefault();
        StringBuilder sb = new StringBuilder();
        List<RepeatRule.Occ> next = RepeatRule.preview(a, now, 5, tz);
        if (next.isEmpty()) sb.append("按这个规则不会响铃");
        else {
            sb.append(a.repeats() ? "接下来 " + next.size() + " 次：" : "响铃：");
            for (RepeatRule.Occ o : next) sb.append('\n').append(RepeatRule.occText(o, a.hour, a.minute));
        }
        List<RepeatRule.Occ> sk = RepeatRule.skipped(a, now, 12, tz);
        if (!sk.isEmpty()) {
            sb.append("\n会跳过：");
            for (int i = 0; i < sk.size(); i++) sb.append(i == 0 ? "" : "、").append(RepeatRule.dayText(sk.get(i).day));
        }
        return sb.toString();
    }

    static String pauseSub(Alarm a) {
        if (!a.repeats()) return "只响一次的闹钟不能暂停";
        String p = Pause.label(a, System.currentTimeMillis(), TimeZone.getDefault());
        return p.length() > 0 ? p : "没有暂停";
    }
}
