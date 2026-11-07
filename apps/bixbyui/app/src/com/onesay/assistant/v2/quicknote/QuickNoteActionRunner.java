package com.onesay.assistant.v2.quicknote;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.util.Log;

import java.util.Calendar;

/**
 * [quicknote] R10 推荐动作执行（Android）。全部打开系统界面由用户确认：日历编辑页、闹钟设置页（不跳过界面）、
 * 地图、通讯录新建页、来源 App；复制写剪贴板；撤销 = 软删 + 把码从岛上拿掉。拨号不在这里。
 */
public final class QuickNoteActionRunner {

    private static final String TAG = "QuickNote";

    private QuickNoteActionRunner() {}

    /** 返回给人看的一句结果（Toast / 日志）。 */
    public static String run(Context ctx, QuickNoteEntry e, QuickNoteActions.Action a) {
        Context app = ctx.getApplicationContext();
        try {
            if (QuickNoteActions.CALENDAR.equals(a.type)) {
                long at = Long.parseLong(a.arg);
                boolean allDay = e.tags.contains("全天");
                Intent it = new Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, at)
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, at + 3600L * 1000L)
                        .putExtra(CalendarContract.Events.TITLE, eventTitle(e))
                        .putExtra(CalendarContract.Events.DESCRIPTION, "来自记一记：" + e.summary);
                if (allDay) it.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true);
                QuickNoteEntry.Ent addr = e.firstEnt("ADDRESS");
                if (addr != null) it.putExtra(CalendarContract.Events.EVENT_LOCATION, addr.value);
                start(app, it);
                return "已打开日历，确认后保存";
            }
            if (QuickNoteActions.ALARM.equals(a.type)) {
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(Long.parseLong(a.arg));
                Intent it = new Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, c.get(Calendar.HOUR_OF_DAY))
                        .putExtra(AlarmClock.EXTRA_MINUTES, c.get(Calendar.MINUTE))
                        .putExtra(AlarmClock.EXTRA_MESSAGE, clip(e.title, 30))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, false);
                start(app, it);
                return "已打开闹钟设置（只设钟点，日期请在闹钟里确认）";
            }
            if (QuickNoteActions.REMIND.equals(a.type) || QuickNoteActions.TODO.equals(a.type)) {
                return remindOrTodo(app, e, a);
            }
            if (QuickNoteActions.NAV.equals(a.type)) {
                start(app, new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(a.arg))));
                return "已打开地图";
            }
            if (QuickNoteActions.CONTACT.equals(a.type)) {
                String[] p = a.arg.split("\\|", 2);
                Intent it = new Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE);
                if (p[0].length() > 0) it.putExtra(ContactsContract.Intents.Insert.NAME, p[0]);
                if (p.length > 1) it.putExtra(ContactsContract.Intents.Insert.PHONE, p[1]);
                start(app, it);
                return "已打开新建联系人，确认后保存";
            }
            if (QuickNoteActions.LINK.equals(a.type)) {
                String u = a.arg.startsWith("http") ? a.arg : "http://" + a.arg;
                start(app, new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
                return "已打开链接";
            }
            if (QuickNoteActions.OPEN_APP.equals(a.type)) {
                Intent it = app.getPackageManager().getLaunchIntentForPackage(a.arg);
                if (it == null) return "来源 App 打不开（可能已卸载）";
                start(app, it);
                return "已打开来源 App";
            }
            if (QuickNoteActions.COPY.equals(a.type)) {
                ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
                com.onesay.assistant.v2.s1.ClipboardMemory.markOwnWrite(a.arg, System.currentTimeMillis());
                cm.setPrimaryClip(ClipData.newPlainText("记一记", a.arg));
                return "已复制";
            }
            if (QuickNoteActions.UNDO.equals(a.type)) {
                QuickNoteHooks.undo(app, e.id);
                return "已撤销这条记一记";
            }
        } catch (Throwable t) {
            Log.w(TAG, "[QUICKNOTE] 动作 " + a.type + " 失败: " + t.getMessage());
            return "没打开：" + t.getMessage();
        }
        return "不支持的动作";
    }

    /** 「邮件 会议 周五评审」→「周五评审」（日历 / 待办标题）。 */
    static String eventTitle(QuickNoteEntry e) {
        String t = e.title == null ? "" : e.title;
        t = t.replaceFirst("^邮件\\s*", "").replaceFirst("^(会议|截止|待办)\\s*", "");
        return t.length() > 0 ? t : e.title;
    }

    /**
     * ★ R11 邮件提醒 / 待办：本机 S3 待办（带提醒时刻 → 到点灵动岛提醒，AlarmManager 精确到日期）。
     * S3 / 待办关着 → 提醒退回系统闹钟设置页（只设钟点），待办说明怎么打开。
     */
    static String remindOrTodo(Context app, QuickNoteEntry e, QuickNoteActions.Action a) throws Exception {
        long remind = 0L, due = 0L;
        if (QuickNoteActions.REMIND.equals(a.type)) {
            String[] p = a.arg.split("\\|");
            remind = Long.parseLong(p[0]);
            due = p.length > 1 ? Long.parseLong(p[1]) : remind;
        }
        boolean s3 = com.onesay.assistant.v2.s3.S3Settings.s3Enabled(app) && com.onesay.assistant.v2.s3.S3Settings.todoEnabled(app);
        if (s3) {
            org.json.JSONObject args = new org.json.JSONObject();
            String text = clip(eventTitle(e), 40);
            args.put("text", text);
            args.put("preview", text);
            if (due > 0) args.put("due_at", String.valueOf(due));
            if (remind > 0) args.put("remind_at", String.valueOf(remind));
            com.onesay.assistant.v2.s3.S3Engine.Exec r = com.onesay.assistant.v2.s3.S3Hooks.engine(app)
                    .execute(com.onesay.assistant.v2.s3.S3Capabilities.SIGN_TODO_ADD, args, System.currentTimeMillis());
            com.onesay.assistant.v2.s3.S3Scheduler.reschedule(app, "quicknote-mail");
            Log.i(TAG, "[QUICKNOTE] 邮件" + (remind > 0 ? "提醒" : "待办") + " → S3 " + (r != null && r.ok ? "ok" : "fail")
                    + " remindAt=" + remind + " due=" + due);
            if (r == null || !r.ok) return "没加上：" + (r == null ? "" : r.message);
            if (remind > 0) {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.CHINA);
                return "已设提醒 " + f.format(new java.util.Date(remind)) + "，到点灵动岛会提醒（也在待办里）";
            }
            return "已加进待办";
        }
        if (remind > 0) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(remind);
            start(app, new Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, c.get(Calendar.HOUR_OF_DAY))
                    .putExtra(AlarmClock.EXTRA_MINUTES, c.get(Calendar.MINUTE))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, clip(eventTitle(e), 30))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, false));
            return "定时任务关着 → 已打开闹钟设置（只设钟点，日期请确认）";
        }
        return "待办功能关着（控制台「定时任务 / 待办」里打开）";
    }

    private static void start(Context app, Intent it) {
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        app.startActivity(it);
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
