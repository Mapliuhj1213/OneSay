package com.onesay.assistant.v2.sched;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.pm.PackageManager;
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TimePicker;

import com.onesay.assistant.v2.kernel.ConsoleHost;

import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * [sched] 控制台「日程」页（R12 控制台 v2 页面接口）。
 * <ul>
 *   <li>功能与权限 › 功能：一行「日程」（右边开关、点进列表页）；</li>
 *   <li>列表页 {@link SchedNotices#PAGE_LIST}：撤销条（刚新建 / 删除 / 修改后 10 分钟内）、「今天」「未来 30 天」两组，每条点「编辑」；
 *       顶部「新建日程」；设置组：默认提前量、语音默认走本地日程、从系统日历只读导入；</li>
 *   <li>新建不确认（可撤销）；修改、删除先二次确认（用户 2026-10-24 定）；系统日历导入的副本只能删，不能改。</li>
 * </ul>
 */
public final class SchedConsole {

    private SchedConsole() {}

    static final int FUTURE_DAYS = 30;
    static final int[] LEADS = {0, 5, 10, 15, 30, 60, 120, 1440};
    static final int REQ_CAL = 0x5ced;

    public static void addEntries(final ConsoleHost host, final String page, final LinearLayout root) {
        final Activity act = host.activity();
        if (ConsoleHost.PAGE_FEATURES.equals(page)) {
            final boolean killed = SchedHooks.SWITCH.killed(act);
            LinearLayout g = host.group(root, null);
            host.switchLinkRow(g, "日程", killed ? ConsoleHost.KILLED : "本机日程：语音建 / 查 / 改 / 删，上岛提醒，进晨间播报",
                    SchedHooks.enabled(act), new ConsoleHost.Toggle() {
                        @Override public void onToggle(boolean on) {
                            SchedHooks.SWITCH.set(act, on);
                            SchedHooks.refreshNotices(act);
                        }
                    }, new Runnable() {
                        @Override public void run() {
                            if (!com.onesay.assistant.v2.planner.PlannerLink.manageInPlanner(act, "events")) host.openPage(SchedNotices.PAGE_LIST, "日程");
                        }
                    });
            return;
        }
        if (!SchedNotices.PAGE_LIST.equals(page)) return;

        final SchedEngine e = SchedHooks.engine(act);
        final TimeZone tz = e.tz();
        final long now = System.currentTimeMillis();
        final Runnable reload = new Runnable() {
            @Override public void run() {
                root.removeAllViews();
                addEntries(host, page, root);
            }
        };

        String undo = e.undoLabel(now);
        if (undo != null) {
            LinearLayout ug = host.group(root, null);
            host.actionRow(ug, undo, "10 分钟内可以撤销", "撤销", new Runnable() {
                @Override public void run() {
                    SchedEngine.Reply r = e.undoNow(System.currentTimeMillis());
                    host.toast(r.text);
                    SchedHooks.refreshNotices(act);
                    reload.run();
                }
            });
        }

        LinearLayout top = host.group(root, null);
        host.actionRow(top, "新建日程", "也可以直接说：「明天下午三点开会」", "新建", new Runnable() {
            @Override public void run() {
                SchedEvent d = new SchedEvent();
                Calendar k = Calendar.getInstance(tz);
                k.setTimeInMillis(System.currentTimeMillis() + 3600000L);
                k.set(Calendar.MINUTE, 0);
                k.set(Calendar.SECOND, 0);
                k.set(Calendar.MILLISECOND, 0);
                d.startAt = k.getTimeInMillis();
                d.source = "console";
                editDialog(host, e, d, true, reload);
            }
        });

        long today = CnTime.dayStart(now, tz);
        long tomorrow = CnTime.addDays(today, 1, tz);
        List<SchedRecur.Occ> todayOcc = e.occurrences(today, tomorrow);
        LinearLayout tg = host.group(root, "今天 · " + SchedFormat.dayWord(today, now, tz).replace("今天", "") + monthDay(today, tz));
        if (todayOcc.isEmpty()) host.valueRow(tg, "今天没有日程", null, "", ConsoleHost.TONE_MUTED);
        for (SchedRecur.Occ o : todayOcc) eventRow(host, tg, e, o, now, reload, false);

        List<SchedRecur.Occ> fut = e.occurrences(tomorrow, CnTime.addDays(today, FUTURE_DAYS + 1, tz));
        LinearLayout fg = host.group(root, "未来 " + FUTURE_DAYS + " 天");
        if (fut.isEmpty()) host.valueRow(fg, "没有安排", null, "", ConsoleHost.TONE_MUTED);
        int n = 0;
        for (SchedRecur.Occ o : fut) {
            if (n++ >= 60) {
                host.valueRow(fg, "……还有 " + (fut.size() - 60) + " 个", null, "", ConsoleHost.TONE_MUTED);
                break;
            }
            eventRow(host, fg, e, o, now, reload, true);
        }

        LinearLayout sg = host.group(root, "设置");
        host.actionRow(sg, "默认提醒", SchedFormat.lead(SchedHooks.defaultLead(act)) + "（单条可以说「提前半小时提醒」）", "修改", new Runnable() {
            @Override public void run() {
                final String[] labels = new String[LEADS.length];
                for (int i = 0; i < LEADS.length; i++) labels[i] = SchedFormat.lead(LEADS[i]);
                new AlertDialog.Builder(act).setTitle("默认提前多久提醒").setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        SchedHooks.setDefaultLead(act, LEADS[which]);
                        reload.run();
                    }
                }).show();
            }
        });
        host.switchRow(sg, "语音日程默认存在本机", "关掉后「明天三点开会」这类话回到旧路径（Google 日历 / 系统日历预填页）", SchedHooks.routeLocal(act),
                new ConsoleHost.Toggle() {
                    @Override public void onToggle(boolean on) {
                        SchedHooks.prefs(act).edit().putBoolean(SchedHooks.K_ROUTE_LOCAL, on).apply();
                    }
                });
        host.actionRow(sg, "从系统日历导入", "只读副本：未来 60 天的日程拷进来一起看、一起播报（不改系统日历，不重复提醒）", "导入", new Runnable() {
            @Override public void run() {
                if (act.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                    act.requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, REQ_CAL);
                    host.toast("需要读取日历的权限，允许后再点一次「导入」");
                    return;
                }
                com.onesay.assistant.v2.util.Threads.start("sched-import", new Runnable() {
                    @Override public void run() {
                        final String msg = SchedImport.run(act, e, System.currentTimeMillis());
                        host.ui(new Runnable() {
                            @Override public void run() {
                                host.toast(msg);
                                SchedHooks.refreshNotices(act);
                                reload.run();
                            }
                        });
                    }
                });
            }
        });
        host.footer(root, "日程只存在这台手机上（不联网、不同步）。提醒走灵动岛，跟随「消息提醒 › 日程」开关和免打扰时段。删除的日程 7 天后彻底清除。");
    }

    private static String monthDay(long day, TimeZone tz) {
        Calendar k = Calendar.getInstance(tz);
        k.setTimeInMillis(day);
        return (k.get(Calendar.MONTH) + 1) + "月" + k.get(Calendar.DAY_OF_MONTH) + "日 周" + SchedFormat.WEEK.charAt(k.get(Calendar.DAY_OF_WEEK) - 1);
    }

    private static void eventRow(final ConsoleHost host, LinearLayout g, final SchedEngine e, final SchedRecur.Occ o, long now,
                                 final Runnable reload, boolean withDay) {
        final SchedEvent ev = o.event;
        StringBuilder sub = new StringBuilder();
        if (withDay) sub.append(SchedFormat.dayWord(o.start, now, e.tz())).append(' ');
        sub.append(SchedFormat.hm(o.start, ev.endAt > ev.startAt ? o.end : 0, ev.allDay, e.tz()));
        if (ev.repeating()) sub.append(" · ").append(SchedRecur.describe(ev));
        if (ev.location != null && ev.location.length() > 0) sub.append(" · ").append(ev.location);
        if (ev.imported()) sub.append(" · 系统日历");
        host.actionRow(g, ev.title, sub.toString(), ev.imported() ? "删除" : "编辑", new Runnable() {
            @Override public void run() {
                SchedEvent fresh = e.store().get(ev.id);
                if (fresh == null) return;
                if (fresh.imported()) {
                    confirmDelete(host, e, fresh, reload);
                } else {
                    editDialog(host, e, fresh, false, reload);
                }
            }
        });
    }

    static void confirmDelete(final ConsoleHost host, final SchedEngine e, final SchedEvent ev, final Runnable reload) {
        host.confirm("删除日程", "删除「" + ev.title + "」" + (ev.repeating() ? "（整个重复系列）" : "") + "？删除后 10 分钟内可以撤销。", "删除", new Runnable() {
            @Override public void run() {
                e.deleteNow(ev.id, System.currentTimeMillis());
                SchedHooks.refreshNotices(host.activity());
                host.toast("已删除");
                reload.run();
            }
        });
    }

    /** 新建 / 编辑对话框：标题、日期、时间、全天、地点。新建直接存（可撤销）；编辑先确认。 */
    static void editDialog(final ConsoleHost host, final SchedEngine e, final SchedEvent src, final boolean isNew, final Runnable reload) {
        final Activity act = host.activity();
        final TimeZone tz = e.tz();
        final SchedEvent d = src.copy();
        final Calendar start = Calendar.getInstance(tz);
        start.setTimeInMillis(d.startAt);
        final long[] durMs = {d.endAt > d.startAt ? d.endAt - d.startAt : 0};

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = host.dp(20);
        box.setPadding(pad, host.dp(8), pad, 0);
        final EditText title = new EditText(act);
        title.setHint("要做什么");
        title.setSingleLine(true);
        title.setText(d.title);
        box.addView(title);
        final Button dateBtn = new Button(act);
        final Button timeBtn = new Button(act);
        final CheckBox allDay = new CheckBox(act);
        allDay.setText("全天");
        allDay.setChecked(d.allDay);
        final Runnable label = new Runnable() {
            @Override public void run() {
                dateBtn.setText("日期：" + (start.get(Calendar.MONTH) + 1) + "月" + start.get(Calendar.DAY_OF_MONTH) + "日 周"
                        + SchedFormat.WEEK.charAt(start.get(Calendar.DAY_OF_WEEK) - 1));
                timeBtn.setText("时间：" + SchedFormat.fmt(start.getTimeInMillis(), tz));
                timeBtn.setEnabled(!allDay.isChecked());
            }
        };
        dateBtn.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                new DatePickerDialog(act, new DatePickerDialog.OnDateSetListener() {
                    @Override public void onDateSet(DatePicker p, int y, int m, int day) {
                        start.set(y, m, day);
                        label.run();
                    }
                }, start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH)).show();
            }
        });
        timeBtn.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                new TimePickerDialog(act, new TimePickerDialog.OnTimeSetListener() {
                    @Override public void onTimeSet(TimePicker p, int h, int m) {
                        start.set(Calendar.HOUR_OF_DAY, h);
                        start.set(Calendar.MINUTE, m);
                        label.run();
                    }
                }, start.get(Calendar.HOUR_OF_DAY), start.get(Calendar.MINUTE), true).show();
            }
        });
        allDay.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean c) {
                label.run();
            }
        });
        box.addView(dateBtn);
        box.addView(timeBtn);
        box.addView(allDay);
        final EditText loc = new EditText(act);
        loc.setHint("地点（可不填）");
        loc.setSingleLine(true);
        loc.setInputType(InputType.TYPE_CLASS_TEXT);
        loc.setText(d.location);
        box.addView(loc);
        label.run();

        AlertDialog.Builder b = new AlertDialog.Builder(act).setTitle(isNew ? "新建日程" : "编辑日程" + (d.repeating() ? "（" + SchedRecur.describe(d) + "）" : ""))
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton(isNew ? "保存" : "保存修改", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface di, int w) {
                        String t = SchedEngine.clip(title.getText().toString().trim(), 40);
                        if (t.length() == 0) {
                            host.toast("先写上要做什么");
                            return;
                        }
                        final SchedEvent nw = d.copy();
                        nw.title = t;
                        nw.location = SchedEngine.clip(loc.getText().toString().trim(), 40);
                        nw.allDay = allDay.isChecked();
                        start.set(Calendar.SECOND, 0);
                        start.set(Calendar.MILLISECOND, 0);
                        long s = start.getTimeInMillis();
                        if (nw.allDay) s = CnTime.dayStart(s, tz);
                        // 重复日程：改的是这一系列的起点（从改后的那天起按原规则重复）
                        nw.startAt = s;
                        nw.endAt = nw.allDay || durMs[0] <= 0 ? 0 : s + durMs[0];
                        final long now = System.currentTimeMillis();
                        if (isNew) {
                            e.createNow(nw, now);
                            SchedHooks.refreshNotices(act);
                            host.toast("已添加：" + SchedFormat.when(nw.startAt, nw.allDay, now, tz) + " " + nw.title + "（可撤销）");
                            reload.run();
                            return;
                        }
                        host.confirm("确认修改", "改成：" + SchedFormat.when(nw.startAt, nw.allDay, now, tz) + " " + nw.title
                                + (nw.repeating() ? "\n（重复日程，以后每次都改）" : ""), "修改", new Runnable() {
                            @Override public void run() {
                                e.updateNow(nw, System.currentTimeMillis());
                                SchedHooks.refreshNotices(act);
                                host.toast("已修改（可撤销）");
                                reload.run();
                            }
                        });
                    }
                });
        if (!isNew) {
            b.setNeutralButton("删除", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface di, int w) {
                    confirmDelete(host, e, src, reload);
                }
            });
        }
        b.show();
    }
}
