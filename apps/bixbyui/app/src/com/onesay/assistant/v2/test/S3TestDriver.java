package com.onesay.assistant.v2.test;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.onesay.assistant.v2.s3.S3Capabilities;
import com.onesay.assistant.v2.s3.S3Engine;
import com.onesay.assistant.v2.s3.S3Hooks;
import com.onesay.assistant.v2.s3.S3Parser;
import com.onesay.assistant.v2.s3.S3Runner;
import com.onesay.assistant.v2.s3.S3Schedule;
import com.onesay.assistant.v2.s3.S3Scheduler;
import com.onesay.assistant.v2.s3.S3Settings;

import org.json.JSONObject;

/**
 * [s3] 真机验收用的调试驱动（<b>只在 debug 包</b>；TestRunReceiver 受签名级权限保护，adb 需 su）。
 *
 * <pre>
 * R=com.onesay.assistant.debug/com.onesay.assistant.v2.test.TestRunReceiver
 * su -c "am broadcast -n $R --es s3 status"
 * su -c "am broadcast -n $R --es s3 remind_in --es kv sec=120 --es text 交周报"   # 建一个 N 秒后的一次性提醒（直接写库 + 排闹钟）
 * su -c "am broadcast -n $R --es s3 agent_in --es kv sec=120 --es text '读一下QQ邮箱未读'"   # N 秒后跑一遍指令（无人值守）
 * su -c "am broadcast -n $R --es s3 parse --es text '每周一9点提醒我交周报'"   # 只解析，不写库
 * su -c "am broadcast -n $R --es s3 list|todos|fire|reschedule|cleanup|wipe"   # cleanup 只删标题含「验收」的
 * su -c "am broadcast -n $R --es s3 todo_add --es text '周五前交报告'"
 * su -c "am broadcast -n $R --es s3 todo_done --es text '交报告'"
 * su -c "am broadcast -n $R --es s3 set --es kv 's3=0'"   # s3 / todo / chip
 * </pre>
 * 日志 tag：{@code S3TestDriver}（结果，[S3-TEST] 前缀）、{@code S3}（功能本身）。
 */
final class S3TestDriver {

    private static final String TAG = "S3TestDriver";

    private S3TestDriver() {}

    static void handle(final Context ctx, Intent intent) {
        final String cmd = intent.getStringExtra("s3");
        final String text = intent.getStringExtra("text");
        final String kv = intent.getStringExtra("kv");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    dispatch(ctx, cmd == null ? "status" : cmd.trim(), text == null ? "" : text, kv == null ? "" : kv);
                } catch (Throwable t) {
                    Log.e(TAG, "[S3-TEST] " + cmd + " 异常: " + t.getMessage(), t);
                }
            }
        }, "s3-test").start();
    }

    private static int intKv(String kv, String key, int def) {
        for (String p : kv.split("[,;&]")) {
            String[] x = p.split("=", 2);
            if (x.length == 2 && x[0].trim().equals(key)) {
                try {
                    return Integer.parseInt(x[1].trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return def;
    }

    private static void dispatch(Context ctx, String cmd, String text, String kv) throws Exception {
        S3Engine e = S3Hooks.engine(ctx);
        long now = System.currentTimeMillis();
        if ("status".equals(cmd)) {
            Log.i(TAG, "[S3-TEST] status " + S3Hooks.debugStatus(ctx));
        } else if ("remind_in".equals(cmd) || "agent_in".equals(cmd)) {
            boolean agent = "agent_in".equals(cmd);
            long at = now + intKv(kv, "sec", 120) * 1000L;
            JSONObject a = new JSONObject();
            java.util.Calendar c = java.util.Calendar.getInstance(e.tz());
            c.setTimeInMillis(at);
            a.put("kind", agent ? S3Parser.KIND_AGENT : S3Parser.KIND_REMINDER);
            a.put("repeat", S3Schedule.ONCE);
            a.put("hour", c.get(java.util.Calendar.HOUR_OF_DAY));
            a.put("minute", c.get(java.util.Calendar.MINUTE));
            a.put("once_at", String.valueOf(at));
            a.put("title", text.length() > 0 ? text : "验收提醒");
            a.put("command", agent ? text : "");
            S3Engine.Exec x = e.execute(S3Capabilities.SIGN_TASK_ADD, a, now);
            S3Scheduler.reschedule(ctx, "debug");
            Log.i(TAG, "[S3-TEST] " + cmd + " ok=" + x.ok + " at=" + at + " " + x.message);
        } else if ("parse".equals(cmd)) {
            Log.i(TAG, "[S3-TEST] parse " + S3Parser.parse(text, now, e.tz()) + " | engine=" + e.handle(text, now, S3Settings.flags(ctx)));
        } else if ("list".equals(cmd)) {
            Log.i(TAG, "[S3-TEST] list " + e.listText(now).replace("\n", " | "));
        } else if ("todos".equals(cmd)) {
            Log.i(TAG, "[S3-TEST] todos " + e.todoListText(now).replace("\n", " | ") + " dueToday=" + e.todoDueTodayCount(now));
        } else if ("todo_add".equals(cmd) || "todo_done".equals(cmd)) {
            String q = "todo_add".equals(cmd) ? "记个待办 " + text : text + "做完了";
            S3Engine.Result r = e.handle(q, now, S3Settings.flags(ctx));
            if (r == null || r.isLocalReply()) {
                Log.i(TAG, "[S3-TEST] " + cmd + " 没接管: " + (r == null ? "null" : r.reply));
                return;
            }
            S3Engine.Exec x = e.execute(r.sign, r.args, now);
            Log.i(TAG, "[S3-TEST] " + cmd + " ok=" + x.ok + " " + x.message);
        } else if ("fire".equals(cmd)) {
            S3Runner r = S3Runner.get();
            if (r != null) r.fireDue("debug");
            Log.i(TAG, "[S3-TEST] fire runner=" + (r != null));
        } else if ("reschedule".equals(cmd)) {
            S3Scheduler.reschedule(ctx, "debug");
            Log.i(TAG, "[S3-TEST] reschedule alarmAt=" + S3Scheduler.scheduledAt(ctx));
        } else if ("cleanup".equals(cmd)) {   // 只删验收脚本建的（标题含「验收」），不碰你自己的任务
            int n = 0;
            for (com.onesay.assistant.v2.s3.S3Store.Task t : e.store().tasks()) {
                if (t.title.contains("验收") || t.command.contains("验收")) {
                    e.store().deleteTask(t.id);
                    n++;
                }
            }
            for (com.onesay.assistant.v2.s3.S3Store.Todo t : e.store().openTodos()) {
                if (t.text.contains("验收")) {
                    e.todoCheck(t.id, now);
                    n++;
                }
            }
            S3Scheduler.reschedule(ctx, "cleanup");
            Log.i(TAG, "[S3-TEST] cleanup removed=" + n);
        } else if ("wipe".equals(cmd)) {
            e.store().clear();
            S3Scheduler.reschedule(ctx, "wipe");
            Log.i(TAG, "[S3-TEST] wipe ok");
        } else if ("set".equals(cmd) && kv.contains("=")) {
            String[] p = kv.split("=", 2);
            boolean on = "1".equals(p[1].trim()) || "true".equalsIgnoreCase(p[1].trim());
            String k = p[0].trim();
            if ("s3".equals(k)) S3Settings.set(ctx, S3Settings.PREF_S3, S3Settings.KEY_ENABLED, on);
            else if ("todo".equals(k)) S3Settings.set(ctx, S3Settings.PREF_TODO, S3Settings.KEY_ENABLED, on);
            else if ("chip".equals(k)) S3Settings.set(ctx, S3Settings.PREF_TODO, S3Settings.KEY_CHIP, on);
            else if ("wlguide".equals(k)) com.onesay.assistant.v2.s3.S3WhitelistGuide.reset(ctx, on);   // 0 = 重置「首次建任务白名单引导」
            Log.i(TAG, "[S3-TEST] set " + k + "=" + on + " → " + S3Settings.dump(ctx));
        } else {
            Log.i(TAG, "[S3-TEST] 未知子命令: " + cmd);
        }
    }
}
