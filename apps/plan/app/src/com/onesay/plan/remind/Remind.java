package com.onesay.plan.remind;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.onesay.plan.R;
import com.onesay.plan.core.Contract;
import com.onesay.plan.core.Reminders;
import com.onesay.plan.core.Row;
import com.onesay.plan.data.Settings;
import com.onesay.plan.data.Store;
import com.onesay.plan.ui.MainActivity;
import com.onesay.plan.ui.TicketActivity;

import java.util.List;
import java.util.TimeZone;

/**
 * 本地提醒：只排一个精确闹钟（{@link Reminders#nextAt} 最早那个），到点 {@link RemindReceiver} 发通知再排下一个。
 * 数据一变（Store.changed）就重排；开机重排（BootReceiver）。
 */
public final class Remind {
    private Remind() {}

    static final String TAG = "BixbyPlan";
    public static final String CH = "plan_remind";
    public static final String ACTION_FIRE = "com.onesay.plan.action.REMIND_FIRE";
    public static final String ACTION_DONE = "com.onesay.plan.action.MARK_DONE";
    public static final String ACTION_TAKEN = "com.onesay.plan.action.MARK_TAKEN";
    public static final String ACTION_COPY = "com.onesay.plan.action.COPY_CODE";

    static final Object LOCK = new Object();

    public static void schedule(final Context ctx) {
        final Context c = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                scheduleNow(c);
            }
        }, "plan-remind").start();
    }

    public static Reminders.Input input(Context c) {
        Reminders.Input in = new Reminders.Input();
        in.events = Store.list(c, Contract.T_EVENTS, "deleted_at=0", null, null);
        in.todos = Store.list(c, Contract.T_TODOS, "done_at=0 AND remind=1 AND due_at>0", null, null);
        in.tickets = Store.list(c, Contract.T_TICKETS, "status=0", null, null);
        in.pickups = Store.list(c, Contract.T_PICKUPS, "taken_at=0 AND remind=1 AND deadline_at>0", null, null);
        in.defaultEventLeadMin = Settings.eventLead(c);
        in.ticketLeadsMin = Reminders.parseLeads(Settings.ticketLeads(c));
        return in;
    }

    public static long scheduleNow(Context c) {
        synchronized (LOCK) {
            try {
                long now = System.currentTimeMillis();
                if (Settings.lastCheck(c) == 0) Settings.setLastCheck(c, now);
                Store.purgeSoftDeleted(c, now);
                archiveExpiredTickets(c, now);
                long next = Reminders.nextAt(input(c), now, TimeZone.getDefault());
                // [borrow-1112] 强提醒的「稍后」也用这一个闹钟：取更早的；已经过点的稍后马上补
                long snz = AlertLog.load(c).nextSnoozeAt(0);
                if (snz > 0) {
                    if (snz <= now) snz = now + 1000L;
                    if (next <= 0 || snz < next) next = snz;
                }
                AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
                PendingIntent pi = firePi(c);
                am.cancel(pi);
                if (next > 0 && Settings.notify(c)) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
                    Log.i(TAG, "[REMIND] 下一个提醒 " + new java.util.Date(next));
                } else {
                    Log.i(TAG, "[REMIND] 没有要排的提醒");
                }
                return next;
            } catch (Exception e) {
                Log.w(TAG, "[REMIND] 排程失败: " + e);
                return -1;
            }
        }
    }

    /** 过期票自动归档（status 0 → 1 已用）；直接写库不发变更广播循环（changed 只 notify + 重排，重排在本函数之后照常进行）。 */
    static void archiveExpiredTickets(Context c, long now) {
        List<Long> ids = Reminders.ticketsToArchive(Store.list(c, Contract.T_TICKETS, "status=0", null, null), now);
        if (ids.isEmpty()) return;
        int n = Store.archiveTickets(c, ids);
        Log.i(TAG, "[REMIND] 过期票自动归档 " + n + " 张");
    }

    static PendingIntent firePi(Context c) {
        Intent i = new Intent(c, RemindReceiver.class).setAction(ACTION_FIRE);
        return PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** 到点：发 (上次检查, 现在] 的通知，再排下一个。 */
    static void fire(Context c) {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            List<Reminders.Item> due = Reminders.due(input(c), Settings.lastCheck(c), now, TimeZone.getDefault());
            Settings.setLastCheck(c, now);
            boolean on = Settings.notify(c);
            int strong = 0;
            if (on) for (Reminders.Item it : due) {
                // [borrow-1112] 强提醒的待办：响铃 + 整屏；其余照旧发通知
                if (Contract.T_TODOS.equals(it.table) && it.strong) { StrongRingService.ring(c, it.id, it.title, it.at); strong++; }
                else post(c, it);
            }
            if (on) strong += ringSnoozes(c, now);
            Log.i(TAG, "[REMIND] 到点发了 " + due.size() + " 条" + (strong > 0 ? "，强提醒响铃 " + strong + " 次" : ""));
        }
        scheduleNow(c);
    }

    /** [borrow-1112] 强提醒「稍后」到点：待办还在、没完成、还开着提醒 → 再响；否则记为已停止。过点太久（超过补发容忍）记为没人管。 */
    static int ringSnoozes(Context c, long now) {
        int n = 0;
        for (final com.onesay.plan.core.StrongAlert.Entry e : AlertLog.load(c).snoozesDue(0, now)) {
            Row r = Store.get(c, Contract.T_TODOS, e.todoId);
            final String st;
            if (r == null || r.l("done_at") != 0 || r.l("remind", 1) == 0) st = com.onesay.plan.core.StrongAlert.STOPPED;
            else if (e.snoozeUntil < now - Reminders.CATCHUP_MS) st = com.onesay.plan.core.StrongAlert.MISSED;
            else st = null;
            if (st != null) {
                AlertLog.edit(c, new AlertLog.Edit() {
                    public com.onesay.plan.core.StrongAlert.Entry apply(com.onesay.plan.core.StrongAlert.Book b) { return b.stop(e.todoId, System.currentTimeMillis(), st); }
                });
                continue;
            }
            StrongRingService.ring(c, e.todoId, r.s("title"), r.l("due_at"));
            n++;
        }
        return n;
    }

    public static void channels(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CH, "计划提醒", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("日程、待办、票、取件码到点提醒");
        ch.enableVibration(true);
        nm.createNotificationChannel(ch);
    }

    static void post(Context c, Reminders.Item it) {
        channels(c);
        Intent open;
        if (Contract.T_TICKETS.equals(it.table)) open = new Intent(c, TicketActivity.class).putExtra("id", it.id);
        else open = new Intent(c, MainActivity.class).putExtra(com.onesay.plan.core.TabNav.EXTRA_TAB, it.table).putExtra("id", it.id);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Notification.Builder b = new Notification.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(it.title)
                .setContentText(it.text)
                .setAutoCancel(true)
                .setWhen(it.occ)
                .setShowWhen(true)
                .setColor(0xFF0381FE)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setStyle(new Notification.BigTextStyle().bigText(it.text))
                .setContentIntent(PendingIntent.getActivity(c, it.notifyId(), open, PendingIntent.FLAG_UPDATE_CURRENT));
        if (Contract.T_TODOS.equals(it.table)) {
            b.addAction(new Notification.Action.Builder(null, "完成", actionPi(c, ACTION_DONE, it)).build());
        } else if (Contract.T_TICKETS.equals(it.table)) {
            // 锁屏直接看到开场时间和取票码（票不是隐私级数据；用户可在系统通知设置里改成隐藏内容）
            b.setVisibility(Notification.VISIBILITY_PUBLIC);
            b.addAction(new Notification.Action.Builder(null, "出示票码", PendingIntent.getActivity(c, it.notifyId() + 1, open, PendingIntent.FLAG_UPDATE_CURRENT)).build());
        } else if (Contract.T_PICKUPS.equals(it.table)) {
            b.addAction(new Notification.Action.Builder(null, "复制取件码", actionPi(c, ACTION_COPY, it)).build());
            b.addAction(new Notification.Action.Builder(null, "已取", actionPi(c, ACTION_TAKEN, it)).build());
        }
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(it.notifyId(), b.build());
        if (Contract.T_TODOS.equals(it.table)) {   // [borrow-1112] 记一笔：助手问「刚才提醒的是哪条」时能找到
            final Reminders.Item x = it;
            AlertLog.edit(c, new AlertLog.Edit() {
                public com.onesay.plan.core.StrongAlert.Entry apply(com.onesay.plan.core.StrongAlert.Book b) { return b.notified(x.id, x.title, x.occ, System.currentTimeMillis()); }
            });
        }
    }

    static PendingIntent actionPi(Context c, String action, Reminders.Item it) {
        Intent i = new Intent(c, RemindReceiver.class).setAction(action).putExtra("table", it.table).putExtra("id", it.id).putExtra("nid", it.notifyId());
        return PendingIntent.getBroadcast(c, (action + it.notifyId()).hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void action(Context c, Intent i) {
        long id = i.getLongExtra("id", 0);
        int nid = i.getIntExtra("nid", 0);
        String a = i.getAction();
        if (ACTION_DONE.equals(a)) markDone(c, id);
        else if (ACTION_TAKEN.equals(a)) markTaken(c, id);
        else if (ACTION_COPY.equals(a)) {
            Row r = Store.get(c, Contract.T_PICKUPS, id);
            if (r != null) copy(c, r.s("code"));
            return;
        }
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(nid);
    }

    /** 撤销这条数据已弹出的提醒通知（删除 / 软删 / 完成 / 已取 / 过期归档时调用）；闹钟由随后的 changed→schedule 重排。 */
    public static void dismiss(Context c, String table, long id) {
        if (Contract.T_TODOS.equals(table)) StrongRingService.cancelIfRinging(c, id);   // [borrow-1112] 勾掉 / 删除 / 关提醒时在响就停
        try {
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(Reminders.notifyId(table, id));
        } catch (Exception e) {
            Log.w(TAG, "[REMIND] 撤销通知失败: " + e);
        }
    }

    public static void markDone(Context c, long todoId) {
        Store.update(c, Contract.T_TODOS, one(todoId), new Row().with("done_at", System.currentTimeMillis()), null);
    }

    public static void markTaken(Context c, long pickupId) {
        Store.update(c, Contract.T_PICKUPS, one(pickupId), new Row().with("taken_at", System.currentTimeMillis()), null);
    }

    public static void copy(Context c, String code) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("取件码", code));
    }

    static List<Long> one(long id) {
        List<Long> l = new java.util.ArrayList<Long>();
        l.add(id);
        return l;
    }
}
