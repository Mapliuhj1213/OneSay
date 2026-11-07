package com.onesay.clock.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import com.onesay.clock.Prefs;
import com.onesay.clock.core.Alarm;
import com.onesay.clock.core.AlarmMath;
import com.onesay.clock.core.HapticMap;
import com.onesay.clock.sched.Sched;

import java.util.TimeZone;

/** 编辑闹钟（One UI：顶部滚轮时间、星期圆点、卡片行：名称 / 智能播报 / 稍后提醒 / 振动 / 铃声；底部 取消 | 保存）。 */
public class EditActivity extends Activity {
    private Prefs prefs;
    private Alarm a;
    private boolean isNew;
    private TimePicker tp;
    private TextView[] dayV = new TextView[7];
    private TextView when;
    private View rowSnooze, rowSound;
    private EditText name;

    int dp(float v) { return Ui.dp(this, v); }

    static final String[] DAYS = {"日", "一", "二", "三", "四", "五", "六"};
    static final int[] SNZ = {5, 10, 15, 20, 25, 30};

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.window(this);
        prefs = new Prefs(this);
        int id = getIntent().getIntExtra("id", 0);
        a = id > 0 ? prefs.alarm(id) : null;
        isNew = a == null;
        if (isNew) { a = new Alarm(); a.id = 0; a.hour = 7; a.minute = 0; a.days = 0x3E; }

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(Ui.BG);
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(12), 0, dp(12));

        // 时间滚轮卡片
        LinearLayout tcard = Ui.card(this);
        tcard.setGravity(Gravity.CENTER_HORIZONTAL);
        tp = (TimePicker) LayoutInflater.from(new ContextThemeWrapper(this, com.onesay.clock.R.style.SpinnerTime))
                .inflate(com.onesay.clock.R.layout.time_spinner, tcard, false);
        tp.setIs24HourView(DateFormat.is24HourFormat(this));
        tp.setHour(a.hour);
        tp.setMinute(a.minute);
        tp.setOnTimeChangedListener(new TimePicker.OnTimeChangedListener() {
            public void onTimeChanged(TimePicker v, int h, int m) { Haptics.fire(v, HapticMap.Ev.TIME_TICK); refreshWhen(); }
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = dp(8);
        tcard.addView(tp, tlp);
        when = Ui.text(this, "", 14, Ui.T2, Ui.regular());
        when.setPadding(dp(24), dp(4), dp(24), dp(14));
        tcard.addView(when);
        // 星期
        LinearLayout days = new LinearLayout(this);
        days.setOrientation(LinearLayout.HORIZONTAL);
        days.setGravity(Gravity.CENTER);
        days.setPadding(dp(16), 0, dp(16), dp(20));
        for (int i = 0; i < 7; i++) {
            final int d = i;
            TextView t = Ui.text(this, DAYS[i], 15, Ui.T1, Ui.medium());
            t.setGravity(Gravity.CENTER);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { Haptics.fire(v, HapticMap.Ev.DAY_TOGGLE); a.days ^= (1 << d); refreshDays(); refreshWhen(); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(38));
            lp.leftMargin = lp.rightMargin = dp(3);
            days.addView(t, lp);
            dayV[i] = t;
        }
        tcard.addView(days);
        col.addView(tcard, Ui.cardLp(this));

        // 选项卡片
        LinearLayout card = Ui.card(this);
        name = new EditText(this);
        name.setText(a.label);
        name.setHint("闹钟名称");
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT);
        name.setTextSize(17);
        name.setBackground(null);
        name.setPadding(dp(24), dp(18), dp(24), dp(18));
        card.addView(name);
        Ui.add(card, Ui.row(this, "智能播报", "响铃时播报时间、天气和 5 条新闻（Bixby 简报样式）", Ui.sw(this, a.brief,
                new CompoundButton.OnCheckedChangeListener() { public void onCheckedChanged(CompoundButton x, boolean on) { a.brief = on; } }), null));
        rowSnooze = Ui.add(card, Ui.row(this, "稍后提醒", "", Ui.sw(this, a.snoozeOn,
                new CompoundButton.OnCheckedChangeListener() { public void onCheckedChanged(CompoundButton x, boolean on) { a.snoozeOn = on; refreshSnooze(); } }),
                new View.OnClickListener() { public void onClick(View v) { pickSnooze(); } }));
        Ui.add(card, Ui.row(this, "振动", null, Ui.sw(this, a.vibrate,
                new CompoundButton.OnCheckedChangeListener() { public void onCheckedChanged(CompoundButton x, boolean on) { a.vibrate = on; } }), null));
        rowSound = Ui.add(card, Ui.row(this, "闹钟铃声", "", null, new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER).putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false).putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
                if (a.sound.length() > 0) i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(a.sound));
                try { startActivityForResult(i, 7); } catch (Throwable t) { Toast.makeText(EditActivity.this, "打不开铃声选择", Toast.LENGTH_SHORT).show(); }
            }
        }));
        col.addView(card, Ui.cardLp(this));
        if (!isNew) {
            final TextView del = Ui.text(this, "删除闹钟", 16, Ui.WARN, Ui.medium());
            del.setGravity(Gravity.CENTER);
            del.setPadding(0, dp(16), 0, dp(16));
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    new AlertDialog.Builder(EditActivity.this).setMessage("删除这个闹钟？").setPositiveButton("删除", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { Haptics.fire(del, HapticMap.Ev.DELETE_CONFIRM); prefs.delete(a.id); Sched.rescheduleNow(EditActivity.this, "delete"); finish(); }
                    }).setNegativeButton("取消", null).show();
                }
            });
            col.addView(del);
        }
        sv.addView(col);
        outer.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        outer.addView(Ui.bottomBar(this, "取消", new View.OnClickListener() { public void onClick(View v) { finish(); } },
                "保存", new View.OnClickListener() { public void onClick(View v) { save(); } }));
        setContentView(outer);
        refreshDays();
        refreshWhen();
        refreshSnooze();
        refreshSound();
    }

    void refreshDays() {
        for (int i = 0; i < 7; i++) {
            boolean on = a.onDay(i);
            dayV[i].setTextColor(on ? Color.WHITE : (i == 0 ? Ui.WARN : Ui.T1));
            dayV[i].setBackground(on ? Ui.round(Ui.ACCENT, dp(19)) : null);
        }
    }

    void refreshWhen() {
        Alarm t = new Alarm();
        t.hour = tp.getHour(); t.minute = tp.getMinute(); t.days = a.days;
        long now = System.currentTimeMillis();
        when.setText(Alarm.daysLabel(a.days) + " · " + AlarmMath.untilLabel(AlarmMath.next(t, now, TimeZone.getDefault()), now));
    }

    void refreshSnooze() { Ui.setSub(rowSnooze, a.snoozeOn ? a.snoozeMin + " 分钟" : "关"); }

    void refreshSound() {
        String title = "默认闹钟铃声";
        try {
            Uri u = a.sound.length() > 0 ? Uri.parse(a.sound) : RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
            Ringtone r = u == null ? null : RingtoneManager.getRingtone(this, u);
            if (r != null) title = r.getTitle(this);
        } catch (Throwable ignored) { }
        Ui.setSub(rowSound, title);
    }

    void pickSnooze() {
        String[] items = new String[SNZ.length];
        int sel = 0;
        for (int i = 0; i < SNZ.length; i++) { items[i] = SNZ[i] + " 分钟"; if (SNZ[i] == a.snoozeMin) sel = i; }
        new AlertDialog.Builder(this).setTitle("稍后提醒间隔").setSingleChoiceItems(items, sel, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) { a.snoozeMin = SNZ[w]; a.snoozeOn = true; refreshSnooze(); d.dismiss(); }
        }).show();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == 7 && res == RESULT_OK && data != null) {
            Uri u = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            a.sound = u == null || RingtoneManager.isDefault(u) ? "" : u.toString();
            refreshSound();
        }
    }

    void save() {
        a.hour = tp.getHour();
        a.minute = tp.getMinute();
        a.label = name.getText().toString().trim();
        a.on = true;
        prefs.put(a);
        Sched.rescheduleNow(this, isNew ? "add" : "edit");
        long now = System.currentTimeMillis();
        Toast.makeText(this, AlarmMath.untilLabel(AlarmMath.next(a, now, TimeZone.getDefault()), now), Toast.LENGTH_SHORT).show();
        finish();
    }
}
