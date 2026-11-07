package com.onesay.assistant.v2.test;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.onesay.assistant.v2.notice.CalendarNotices;
import com.onesay.assistant.v2.notice.IslandNotice;
import com.onesay.assistant.v2.notice.NoticeAction;
import com.onesay.assistant.v2.notice.NoticeHooks;
import com.onesay.assistant.v2.notice.NoticeSettings;
import com.onesay.assistant.v2.notice.ScheduledMailNotices;

/**
 * [notice] 灵动岛消息提醒的上机注入入口（<b>只在 debug 包</b>；TestRunReceiver 受签名级权限保护，adb 需 su）。。
 * <pre>
 * R=com.onesay.assistant.debug/com.onesay.assistant.v2.test.TestRunReceiver
 * su -c "am broadcast -n $R --es notice status"
 * su -c "am broadcast -n $R --es notice post --es title 测试提醒 --es text 摘要 --es prio normal|high|low [--es dedup k1] [--ei ttl_min 30] [--ei delay_min 2] [--es link https://meet.google.com/abc-defg-hij]"
 * su -c "am broadcast -n $R --es notice callback --es title 每日播报 --es text 今天3件事 [--es prio high]"   # 回调中枢岛渠道入口
 * su -c "am broadcast -n $R --es notice cal --ei in_min 11 --es title 周会 [--es link https://meet.google.com/xxx] [--ei dur_min 30] [--ei attendees 2]"
 * su -c "am broadcast -n $R --es notice calclear"
 * su -c "am broadcast -n $R --es notice calfail --es text net|auth|off"   # 模拟日程拉取失败：net = 网络失败（保留提醒）/ auth = 授权失效（撤下）
 * su -c "am broadcast -n $R --es notice mailtask --ei in_min 7 [--es text '给我自己发邮件说测试']"   # 建一条真实的一次性定时邮件任务（没授权，到点只会停在等确认）
 * su -c "am broadcast -n $R --es notice draft [--es text 会话id]"     # 「草稿待确认」提醒（假会话：立即查看 = 卡片提示已处理过）
 * su -c "am broadcast -n $R --es notice dismiss --es id &lt;去重键&gt;" | dismissall | act --es id &lt;去重键&gt; --ei i 0
 * su -c "am broadcast -n $R --es notice tick|restart|clear|rescan"
 * su -c "am broadcast -n $R --es notice quiet --es text on|off|auto"   # 模拟免打扰时段
 * su -c "am broadcast -n $R --es notice call --es text on|off|auto"    # 模拟通话中
 * su -c "am broadcast -n $R --es notice set --es kv 'cal_lead_min=3'"   # enabled / src_callback / src_calendar / src_mail / cal_lead_min / mail_lead_min / quiet_cal_high
 * 新增键：src_privacy / src_call / src_notify / src_system / src_errand / n_otp / n_voip / n_alarm / n_nav / n_music /
 *   n_whitelist（逗号分隔包名）/ reveal_otp / reveal_content / reveal_number / quiet_privacy / quiet_call / low_battery_pct
 * 智能来源注入：mic / cam / privprobe / phone / nl / nlrm / battery / headset / smart（见 {@link #smart}）
 * </pre>
 * 日志 tag：{@code NoticeTestDriver}（结果）、{@code Notice}（功能本身）、{@code Island}（岛）。
 */
final class NoticeTestDriver {

    private static final String TAG = "NoticeTestDriver";

    private NoticeTestDriver() {}

    static void handle(final Context ctx, final Intent intent) {
        final String cmd = intent.getStringExtra("notice");
        com.onesay.assistant.v2.util.Threads.start("notice-test", new Runnable() {
            @Override public void run() {
                try {
                    dispatch(ctx.getApplicationContext(), cmd == null ? "status" : cmd.trim(), intent);
                } catch (Throwable t) {
                    Log.e(TAG, "[NOTICE-TEST] " + cmd + " 异常: " + t.getMessage(), t);
                }
            }
        });
    }

    static IslandNotice.Priority prio(String s) {
        if ("high".equalsIgnoreCase(s)) return IslandNotice.Priority.HIGH;
        if ("low".equalsIgnoreCase(s)) return IslandNotice.Priority.LOW;
        return IslandNotice.Priority.NORMAL;
    }

    static Boolean tri(String s) {
        return "on".equals(s) ? Boolean.TRUE : "off".equals(s) ? Boolean.FALSE : null;
    }

    /**
     * 灵动岛智能化的注入（每个新来源一条入口；都走真实的 IslandSmartHooks → NoticeHooks.post，规则不旁路）：
     * <pre>
     * N mic --es pkg com.tencent.mm [--ez on false]     # 假装某包开始 / 停止用麦克风（不走系统回调和 root）
     * N cam --es pkg com.sec.android.app.camera [--ez on false]
     * N privprobe                                       # 真实读一次 root dumpsys audio / media.camera，打印解析出的包名（不上岛）
     * N phone --es text ring|offhook|idle [--es num 13812345678]   # 电话状态（号码只用来查联系人 / 打码，日志不打印）
     * N nl --es pkg com.samsung.android.messaging --es title 106 --es text '【淘宝】验证码 384712' [--es cat call|alarm|navigation] [--ez ongoing true] [--es tpl MediaStyle] [--es key k1] [--ez chrono true --ei chrono_s 300 --ez countdown true]
     * N nlrm --es key k1                               # 通知被移除（导航 / 外卖 / 计时器跟着撤；验证码不撤）
     * N battery --ei level 15 [--ez charging true]
     * N headset [--es name 'Galaxy Buds'] [--ez bt true]
     * N smart                                          # 智能来源状态（回调登记、在用的包、电话状态、通知使用权）
     * </pre>
     */
    static boolean smart(Context app, String cmd, Intent it, String title, String text) {
        if (!java.util.Arrays.asList("mic", "cam", "privprobe", "phone", "nl", "nlrm", "battery", "headset", "smart").contains(cmd)) return false;
        com.onesay.assistant.v2.notice.IslandSmartHooks.start(app);   // 面板服务没起来时也能测（重复调用无副作用）
        if ("mic".equals(cmd) || "cam".equals(cmd)) {
            String pkg = it.getStringExtra("pkg");
            if (pkg == null) pkg = "com.example.recorder";
            com.onesay.assistant.v2.notice.IslandSmartHooks.injectPrivacy(cmd, pkg, it.getBooleanExtra("on", true));
        } else if ("privprobe".equals(cmd)) {
            Log.i(TAG, "[NOTICE-TEST] privprobe " + com.onesay.assistant.v2.notice.IslandSmartHooks.probePrivacy());
            return true;
        } else if ("phone".equals(cmd)) {
            int st = "ring".equals(text) ? com.onesay.assistant.v2.notice.CallNotices.RINGING
                    : "offhook".equals(text) ? com.onesay.assistant.v2.notice.CallNotices.OFFHOOK : com.onesay.assistant.v2.notice.CallNotices.IDLE;
            com.onesay.assistant.v2.notice.IslandSmartHooks.onPhoneState(st, it.getStringExtra("num"));
        } else if ("nl".equals(cmd)) {
            com.onesay.assistant.v2.notice.NotifyClassifier.Notif n = new com.onesay.assistant.v2.notice.NotifyClassifier.Notif();
            n.pkg = it.getStringExtra("pkg") == null ? "com.example.app" : it.getStringExtra("pkg");
            n.label = n.pkg;
            try {
                n.label = String.valueOf(app.getPackageManager().getApplicationLabel(app.getPackageManager().getApplicationInfo(n.pkg, 0)));
            } catch (Throwable ignored) {
            }
            n.title = title == null ? "" : title;
            n.text = text == null ? "" : text;
            n.category = it.getStringExtra("cat") == null ? "" : it.getStringExtra("cat");
            n.template = it.getStringExtra("tpl") == null ? "" : "android.app.Notification$" + it.getStringExtra("tpl");
            n.ongoing = it.getBooleanExtra("ongoing", false);
            n.keyHash = "test-" + (it.getStringExtra("key") == null ? String.valueOf(System.currentTimeMillis()) : it.getStringExtra("key"));
            if (it.getBooleanExtra("chrono", false)) {
                n.usesChronometer = true;
                n.chronoCountDown = it.getBooleanExtra("countdown", false);
                int sec = it.getIntExtra("chrono_s", 300);
                n.when = n.chronoCountDown ? System.currentTimeMillis() + sec * 1000L : System.currentTimeMillis() - sec * 1000L;
            }
            com.onesay.assistant.v2.notice.NotifyClassifier.Result r = com.onesay.assistant.v2.notice.IslandSmartHooks.onNotif(app, n);
            Log.i(TAG, "[NOTICE-TEST] nl → " + (r == null ? "不上岛（没命中筛选 / 开关关着 / 解析不出）" : r.type + " " + r.notice.dedupKey));
        } else if ("nlrm".equals(cmd)) {
            com.onesay.assistant.v2.notice.IslandSmartHooks.onNotifRemoved(app, "test-" + it.getStringExtra("key"));
        } else if ("battery".equals(cmd)) {
            com.onesay.assistant.v2.notice.IslandSmartHooks.onBattery(it.getIntExtra("level", 50), it.getBooleanExtra("charging", false));
        } else if ("headset".equals(cmd)) {
            com.onesay.assistant.v2.notice.IslandSmartHooks.onHeadset(it.getStringExtra("name"), it.getBooleanExtra("bt", false));
        } else if ("smart".equals(cmd)) {
            Log.i(TAG, "[NOTICE-TEST] smart " + com.onesay.assistant.v2.notice.IslandSmartHooks.status());
            return true;
        } else {
            return false;
        }
        Log.i(TAG, "[NOTICE-TEST] " + cmd + " 完成 → " + com.onesay.assistant.v2.notice.IslandSmartHooks.status() + "\n" + NoticeHooks.status(app));
        return true;
    }

    private static void dispatch(Context app, String cmd, Intent it) {
        long now = System.currentTimeMillis();
        String title = it.getStringExtra("title");
        String text = it.getStringExtra("text");
        if ("status".equals(cmd)) {
            Log.i(TAG, "[NOTICE-TEST] status " + NoticeHooks.status(app));
            return;
        }
        if ("post".equals(cmd)) {
            String dedup = it.getStringExtra("dedup");
            IslandNotice n = new IslandNotice(IslandNotice.Source.EXTERNAL, "test:" + (dedup != null ? dedup : String.valueOf(now)),
                    prio(it.getStringExtra("prio")), title == null ? "测试提醒" : title, text == null ? "来自调试注入" : text);
            n.sourceTag = "test";
            int delay = it.getIntExtra("delay_min", 0);
            if (delay > 0) n.showAt = now + delay * 60000L;
            int ttl = it.getIntExtra("ttl_min", 30);
            n.expireAt = Math.max(now, n.showAt) + ttl * 60000L;
            String link = it.getStringExtra("link");
            if (link != null) n.action(new NoticeAction("加入", NoticeAction.Kind.OPEN_URL, link));
            n.action(new NoticeAction("稍后", NoticeAction.Kind.SNOOZE, "2"));
            n.action(new NoticeAction("知道了", NoticeAction.Kind.DISMISS, ""));
            Log.i(TAG, "[NOTICE-TEST] post " + n.dedupKey + " → " + NoticeHooks.post(app, n));
            return;
        }
        if ("callback".equals(cmd)) {
            boolean ok = NoticeHooks.postCallback(app, "cb.test." + now, title == null ? "定时任务" : title, text == null ? "测试回调结果" : text,
                    "high".equalsIgnoreCase(it.getStringExtra("prio")) ? com.onesay.assistant.v2.callback.DeliveryPolicy.Urgency.HIGH
                            : com.onesay.assistant.v2.callback.DeliveryPolicy.Urgency.NORMAL);
            Log.i(TAG, "[NOTICE-TEST] callback → " + (ok ? "进提醒队列" : "提醒关着（回调中枢会用旧的完成卡）"));
            return;
        }
        if ("cal".equals(cmd)) {
            int in = it.getIntExtra("in_min", NoticeSettings.calLead(app) + 1);
            int dur = it.getIntExtra("dur_min", 30);
            long start = now + in * 60000L;
            CalendarNotices.Event e = new CalendarNotices.Event("test" + now, title == null ? "测试会议" : title, start, start + dur * 60000L,
                    false, "", it.getStringExtra("link"), it.getIntExtra("attendees", 0));
            NoticeHooks.injectFakeEvent(app, e);
            Log.i(TAG, "[NOTICE-TEST] cal 注入 「" + e.title + "」 开始 " + new java.util.Date(start) + " 提前 " + NoticeSettings.calLead(app)
                    + " 分钟 优先级 " + CalendarNotices.priorityOf(e));
            return;
        }
        if ("calclear".equals(cmd)) {
            NoticeHooks.clearFakeEvents(app);
            Log.i(TAG, "[NOTICE-TEST] calclear");
            return;
        }
        if ("calfail".equals(cmd)) {
            String k = text == null ? "off" : text.trim().toLowerCase(java.util.Locale.ROOT);
            String kind = "net".equals(k) ? "NET" : "auth".equals(k) ? "AUTH" : null;
            Log.i(TAG, "[NOTICE-TEST] " + NoticeHooks.simulateCalendarFailure(app, kind) + " → " + NoticeHooks.status(app));
            return;
        }
        if ("mailtask".equals(cmd)) {
            int in = it.getIntExtra("in_min", NoticeSettings.mailLead(app) + 2);
            com.onesay.assistant.v2.s3.S3Store.Task t = new com.onesay.assistant.v2.s3.S3Store.Task();
            t.kind = com.onesay.assistant.v2.s3.S3Parser.KIND_AGENT;
            t.repeat = com.onesay.assistant.v2.s3.S3Schedule.ONCE;
            t.onceAt = now + in * 60000L;
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(t.onceAt);
            t.hour = c.get(java.util.Calendar.HOUR_OF_DAY);
            t.minute = c.get(java.util.Calendar.MINUTE);
            t.nextAt = t.onceAt;
            t.command = text == null ? "给我自己发邮件说灵动岛提醒测试" : text;
            t.title = t.command;
            t.createdAt = now;
            long id = com.onesay.assistant.v2.s3.S3Hooks.engine(app).store().addTask(t);
            com.onesay.assistant.v2.s3.S3Scheduler.reschedule(app, "notice-test-mailtask");
            NoticeHooks.rescanMail(app);
            Log.i(TAG, "[NOTICE-TEST] mailtask task#" + id + " 「" + t.command + "」 到点 " + new java.util.Date(t.onceAt)
                    + " 发邮件=" + ScheduledMailNotices.sendsMail(t.command) + "（没授权：到点只会停在等确认，不会起草或发送）");
            return;
        }
        if ("draft".equals(cmd)) {
            boolean ok = NoticeHooks.postDraftPending(app, text == null ? "demo" + now : text, "我自己", "灵动岛提醒测试");
            Log.i(TAG, "[NOTICE-TEST] draft → " + ok);
            return;
        }
        if (smart(app, cmd, it, title, text)) return;
        if ("dismiss".equals(cmd)) {
            NoticeHooks.listener(app).onNoticeDismiss(it.getStringExtra("id"));
        } else if ("dismissall".equals(cmd)) {
            NoticeHooks.listener(app).onNoticeDismissAll();
        } else if ("act".equals(cmd)) {
            NoticeHooks.listener(app).onNoticeAction(it.getStringExtra("id"), it.getIntExtra("i", 0));
        } else if ("tick".equals(cmd)) {
            NoticeHooks.tickNow(app);
        } else if ("restart".equals(cmd)) {
            NoticeHooks.simulateRestart(app);
        } else if ("clear".equals(cmd)) {
            NoticeHooks.center(app).clear();
            NoticeHooks.tickNow(app);
        } else if ("rescan".equals(cmd)) {
            NoticeHooks.rescanMail(app);
        } else if ("quiet".equals(cmd)) {
            NoticeHooks.sQuietOverride = tri(text);
            NoticeHooks.tickNow(app);
        } else if ("call".equals(cmd)) {
            NoticeHooks.sCallOverride = tri(text);
        } else if ("set".equals(cmd)) {
            String kv = it.getStringExtra("kv");
            int eq = kv == null ? -1 : kv.indexOf('=');
            if (eq > 0) {
                String k = kv.substring(0, eq).trim(), v = kv.substring(eq + 1).trim();
                android.content.SharedPreferences.Editor ed = NoticeSettings.prefs(app).edit();
                if (k.endsWith("_min") || k.endsWith("_pct")) ed.putInt(k, Integer.parseInt(v));
                else if (NoticeSettings.K_N_WHITELIST.equals(k)) ed.putString(k, v);
                else ed.putBoolean(k, "1".equals(v) || "true".equals(v) || "on".equals(v));
                ed.apply();
            }
            NoticeHooks.repoll(app);
        } else {
            Log.w(TAG, "[NOTICE-TEST] 不认识的命令 " + cmd);
            return;
        }
        Log.i(TAG, "[NOTICE-TEST] " + cmd + " 完成 → " + NoticeHooks.status(app));
    }
}
