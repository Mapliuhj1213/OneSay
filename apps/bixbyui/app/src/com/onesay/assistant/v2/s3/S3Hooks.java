package com.onesay.assistant.v2.s3;

import android.content.Context;
import android.os.PowerManager;
import android.util.Log;
import android.widget.LinearLayout;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.router.Invocation;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.List;
import java.util.TimeZone;

/**
 * [s3] 定时任务 + 待办的<b>全部挂钩入口</b>（写法同 S2Hooks：既有文件里只有几行 {@code // [s3]} 调用指向这里）。
 *
 * <table>
 *   <tr><td>S3-H1</td><td>UnifiedRouter.cascade：S2 之前</td><td>{@link #route}：本地回答（列表）或 S3 能力调用</td></tr>
 *   <tr><td>S3-H2</td><td>UnifiedRouter.shouldUseAgentLoop</td><td>{@link #claims}：S3 接管的句子永不进 AgentLoop</td></tr>
 *   <tr><td>S3-H3</td><td>FloatingSheetService.onSubmitCommand：识屏指代之前</td><td>{@link #bypassScreenRef}：「取消读邮件那个」的「那个」不是屏幕指代</td></tr>
 *   <tr><td>S3-H4</td><td>FloatingSheetService.dispatch：注册表查不到能力时</td><td>{@link #describe}</td></tr>
 *   <tr><td>S3-H5</td><td>UnifiedExecutor 后端列表</td><td>{@code S3Backend}（executor="s3"）→ {@link #executeLocal}</td></tr>
 *   <tr><td>S3-H6</td><td>BootReceiver</td><td>开机 / 应用更新 / 改时间 / 改时区 → 重排闹钟 + 补触发；闹钟到点 → 唤起面板服务</td></tr>
 *   <tr><td>S3-H7</td><td>FloatingSheetService.onCreate / onStartCommand(ACTION_S3_FIRE) / onDestroy</td><td>{@link S3Runner}</td></tr>
 *   <tr><td>S3-H8</td><td>MainActivity 控制台</td><td>{@link #addConsoleEntries}（R12 控制台 v2）：功能与权限两行 + 定时任务 / 待办细项页（开关、电池白名单、列表）</td></tr>
 * </table>
 */
public final class S3Hooks {

    private static final String TAG = "S3";

    private S3Hooks() {}

    private static volatile S3Engine sEngine;

    public static S3Engine engine(Context ctx) {
        S3Engine e = sEngine;
        if (e != null) return e;
        synchronized (S3Hooks.class) {
            if (sEngine == null) sEngine = new S3Engine(com.onesay.assistant.v2.planner.PlannerHooks.s3(ctx, SqliteS3Store.get(ctx, SqliteS3Store.DB_PROD)), TimeZone.getDefault());   // ★ planner
            return sEngine;
        }
    }

    // ------------------------------------------------------------------ 路由

    /** [S3-H1] 后台线程。null = 不接管。 */
    public static Invocation route(Context ctx, String query) {
        try {
            if (ctx == null || query == null) return null;
            S3Engine.Flags f = S3Settings.flags(ctx);
            if (!f.any()) return null;
            S3Engine.Result r = engine(ctx).handle(query, System.currentTimeMillis(), f);
            if (r == null) return null;
            Log.i(TAG, "[S3] 接管: " + r);
            if (r.isLocalReply()) {
                JSONObject a = new JSONObject();
                a.put("text", r.reply);
                a.put("query", query.trim());
                a.put("s3", r.type.name());
                return new Invocation("ask.reply", a, 1.0, Invocation.RouteSource.L0_RULE, query);
            }
            return new Invocation(r.sign, r.args, 1.0, Invocation.RouteSource.L0_RULE, query);
        } catch (Throwable t) {
            Log.w(TAG, "[S3] route 异常，回落原流程: " + t.getMessage());
            return null;
        }
    }

    /** [S3-H2] 后台线程。 */
    public static boolean claims(Context ctx, String query) {
        try {
            if (ctx == null || query == null) return false;
            S3Engine.Flags f = S3Settings.flags(ctx);
            return f.any() && engine(ctx).handle(query, System.currentTimeMillis(), f) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** [S3-H3] 主线程（纯正则，不读库）。 */
    public static boolean bypassScreenRef(Context ctx, String command) {
        try {
            if (ctx == null || command == null || !S3Settings.flags(ctx).any()) return false;
            S3Parser.Parsed p = S3Parser.parse(command, System.currentTimeMillis(), TimeZone.getDefault());
            boolean b = p != null && (p.type == S3Parser.Type.CANCEL || p.type == S3Parser.Type.TODO_DONE || p.type == S3Parser.Type.CREATE
                    || p.type == S3Parser.Type.TODO_ADD || p.type == S3Parser.Type.TODO_CLEAR);
            if (b) Log.i(TAG, "[S3] 定时 / 待办句式，不交给识屏指代");
            return b;
        } catch (Throwable t) {
            return false;
        }
    }

    /** [S3-H4] */
    public static Capability describe(String sign) {
        return S3Capabilities.get(sign);
    }

    /** [S3-H5] S3Backend 调用（门禁已放行或令牌已批准）。 */
    public static ExecutionResult executeLocal(Context ctx, Capability cap, JSONObject args) {
        if (cap == null) return null;
        try {
            if (ctx == null) return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "定时任务库不可用", "no context", cap, args);
            boolean todo = cap.sign.startsWith("s3.todo.");
            if (todo ? !S3Settings.todoEnabled(ctx) : !S3Settings.s3Enabled(ctx)) {
                return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "这项功能已关闭", "s3 disabled", cap, args);
            }
            // lane-a 权限卡：新建「到点让我做事」（AGENT）任务 → 先静态分析权限、弹卡逐项勾选，确认后才入库（授权随任务存）
            if ((S3Capabilities.SIGN_TASK_ADD.equals(cap.sign) || S3Capabilities.SIGN_TASK_ADD_CONFIRM.equals(cap.sign))
                    && args != null && args.optInt("kind", 0) == S3Parser.KIND_AGENT && args.optString("command", "").trim().length() > 0) {
                com.onesay.assistant.v2.jobs.PermissionCards.Card card = com.onesay.assistant.v2.jobs.PermissionCards.forNewS3(cap.sign, args, System.currentTimeMillis());
                com.onesay.assistant.v2.jobs.PermissionCards.show(ctx, card);
                Log.i(TAG, "[S3] 新建 AGENT 任务 → 权限卡 " + com.onesay.assistant.v2.jobs.TaskGrant.encode(card.required) + "（确认后才入库）");
                return ExecutionResult.success(cap, args, "请在弹出的权限卡上确认这条任务能用哪些工具，确认后才会创建。",
                        ExecutionResult.Verification.VERIFIED, "s3");
            }
            S3Engine.Exec x = engine(ctx).execute(cap.sign, args, System.currentTimeMillis());
            Log.i(TAG, "[S3] 执行 " + cap.sign + " ok=" + x.ok);
            if (!x.ok) return ExecutionResult.failed(ErrorCode.EXEC_FAILED, x.message, "s3 exec failed", cap, args);
            S3Scheduler.reschedule(ctx, cap.sign);
            // D-1c（用户 14:25 拍板）：第一次建定时任务、且不在电池白名单 → 回执后附一次引导（只给文字，root 仍要用户点）
            String msg = S3WhitelistGuide.apply(ctx, cap.sign, true, x.message);
            return ExecutionResult.success(cap, args, msg, ExecutionResult.Verification.VERIFIED, "s3");
        } catch (Throwable t) {
            return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "定时任务库异常：" + t.getMessage(), t.toString(), cap, args);
        }
    }

    /** 确认卡摘要：args 里的字符串值用 · 连起来，最多 60 字。 */
    static String detail(JSONObject args) {
        if (args == null) return null;
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = args.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (k == null || k.toLowerCase().contains("token") || k.startsWith("_")) continue;
            Object v = args.opt(k);
            if (!(v instanceof String)) continue;
            String s = ((String) v).trim();
            if (s.length() == 0) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(s);
        }
        if (sb.length() == 0) return null;
        String r = sb.toString();
        return r.length() > 60 ? r.substring(0, 59) + "…" : r;
    }

    // ------------------------------------------------------------------ 电池优化白名单

    public static boolean batteryWhitelisted(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 只在用户点按钮时调用（绝不静默执行）：经 root 守护把本 App 加进 deviceidle 白名单。后台线程。 */
    public static String whitelistByRoot(Context ctx) {
        try {
            com.onesay.assistant.v2.executor.ShellExecutor.ShellResponse r = new com.onesay.assistant.v2.executor.ShellExecutor(ctx)
                    .execute("dumpsys deviceidle whitelist +" + ctx.getPackageName());
            Log.i(TAG, "[S3] 用户点击：加入电池白名单 rc=" + r.rc + " " + r.output);
            return r.isSuccess() ? "已加入电池优化白名单" : "没成功：" + r.output;
        } catch (Throwable t) {
            return "没成功：" + t.getMessage();
        }
    }

    // ------------------------------------------------------------------ 控制台（R12 v2 页面）

    /**
     * [R12] 控制台 v2：功能与权限里「定时任务」「待办」两行（右边开关、左边点进细项页）；细项页 s3.tasks / s3.todo 列出全部任务 / 待办
     * （以前整段长文本塞在卡片里，现在一行一项）。kill-switch 文件 s3.off / todo.off 优先。
     */
    public static void addConsoleEntries(final com.onesay.assistant.v2.kernel.ConsoleHost host, String page, final LinearLayout root) {
        final android.app.Activity act = host.activity();
        final boolean killS3 = S3Settings.pref(act, S3Settings.PREF_S3, S3Settings.KEY_ENABLED) && !S3Settings.s3Enabled(act);
        final boolean killTodo = S3Settings.pref(act, S3Settings.PREF_TODO, S3Settings.KEY_ENABLED) && !S3Settings.todoEnabled(act);
        if (com.onesay.assistant.v2.kernel.ConsoleHost.PAGE_FEATURES.equals(page)) {
            final com.onesay.assistant.v2.kernel.ConsoleHost.Row tasks = host.switchLinkRow(root, "定时任务", killS3 ? com.onesay.assistant.v2.kernel.ConsoleHost.KILLED : "…",
                    S3Settings.pref(act, S3Settings.PREF_S3, S3Settings.KEY_ENABLED), new com.onesay.assistant.v2.kernel.ConsoleHost.Toggle() {
                        @Override public void onToggle(boolean on) {
                            S3Settings.set(act, S3Settings.PREF_S3, S3Settings.KEY_ENABLED, on);
                        }
                    }, new Runnable() {
                        @Override public void run() {
                            // feat/planner-link：装了计划 App 就跳过去管（自动任务页）；没装照旧本地管理页
                            if (!com.onesay.assistant.v2.planner.PlannerLink.manageInPlanner(act, "automations")) host.openPage("s3.tasks", "定时任务");
                        }
                    });
            final com.onesay.assistant.v2.kernel.ConsoleHost.Row todos = host.switchLinkRow(root, "待办", killTodo ? com.onesay.assistant.v2.kernel.ConsoleHost.KILLED : "…",
                    S3Settings.pref(act, S3Settings.PREF_TODO, S3Settings.KEY_ENABLED), new com.onesay.assistant.v2.kernel.ConsoleHost.Toggle() {
                        @Override public void onToggle(boolean on) {
                            S3Settings.set(act, S3Settings.PREF_TODO, S3Settings.KEY_ENABLED, on);
                        }
                    }, new Runnable() {
                        @Override public void run() {
                            if (!com.onesay.assistant.v2.planner.PlannerLink.manageInPlanner(act, "todos")) host.openPage("s3.todo", "待办");
                        }
                    });
            if (killS3) tasks.setEnabled(false);
            if (killTodo) todos.setEnabled(false);
            com.onesay.assistant.v2.util.Threads.start("s3-console", new Runnable() {
                @Override public void run() {
                    try {
                        S3Engine e = engine(act);
                        long now = System.currentTimeMillis();
                        final int n = e.activeTasks().size();
                        long at = S3Scheduler.scheduledAt(act);
                        final String next = at > 0 ? " · 下次 " + new java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(new java.util.Date(at)) : "";
                        final int open = e.store().openTodos().size();
                        final int due = e.todoDueTodayCount(now);
                        host.ui(new Runnable() {
                            @Override public void run() {
                                if (!killS3) tasks.setSub(n == 0 ? "还没有 · 说「每天早上 8 点…」" : n + " 个" + next);
                                if (!killTodo) todos.setSub(open == 0 ? "没有未完成的" : open + " 项未完成" + (due > 0 ? " · 今天到期 " + due : ""));
                            }
                        });
                    } catch (Throwable t) {
                        Log.w(TAG, "[S3] 控制台读取失败: " + t.getMessage());
                    }
                }
            });
            return;
        }
        final boolean isTasks = "s3.tasks".equals(page);
        if (!isTasks && !"s3.todo".equals(page)) return;
        final String pref = isTasks ? S3Settings.PREF_S3 : S3Settings.PREF_TODO;
        final boolean killed = isTasks ? killS3 : killTodo;
        final List<com.onesay.assistant.v2.kernel.ConsoleHost.Row> subs = new java.util.ArrayList<com.onesay.assistant.v2.kernel.ConsoleHost.Row>();
        com.onesay.assistant.v2.kernel.ConsoleHost.Row main = host.mainSwitch(root, isTasks ? "使用定时任务" : "使用待办", S3Settings.pref(act, pref, S3Settings.KEY_ENABLED),
                new com.onesay.assistant.v2.kernel.ConsoleHost.Toggle() {
                    @Override public void onToggle(boolean on) {
                        S3Settings.set(act, pref, S3Settings.KEY_ENABLED, on);
                        for (com.onesay.assistant.v2.kernel.ConsoleHost.Row r : subs) r.setEnabled(on && !killed);
                    }
                });
        if (!isTasks) {
            LinearLayout g = host.group(root, "显示");
            subs.add(host.switchRow(g, "岛上显示今天到期的待办数", "空闲时显示，为 0 不显示", S3Settings.pref(act, S3Settings.PREF_TODO, S3Settings.KEY_CHIP),
                    new com.onesay.assistant.v2.kernel.ConsoleHost.Toggle() {
                        @Override public void onToggle(boolean on) {
                            S3Settings.set(act, S3Settings.PREF_TODO, S3Settings.KEY_CHIP, on);
                            S3Runner r = S3Runner.get();
                            if (r != null) r.refreshTodos();
                        }
                    }));
        } else {
            LinearLayout g = host.group(root, "准点");
            if (batteryWhitelisted(act)) {
                host.valueRow(g, "电池优化白名单", "熄屏时也能准点", "已加入", com.onesay.assistant.v2.kernel.ConsoleHost.TONE_OK);
            } else {
                final com.onesay.assistant.v2.kernel.ConsoleHost.Row[] wl = new com.onesay.assistant.v2.kernel.ConsoleHost.Row[1];
                wl[0] = host.actionRow(g, "电池优化白名单", "不在白名单，熄屏久了定时任务会晚", "加入", new Runnable() {
                    @Override public void run() {
                        com.onesay.assistant.v2.util.Threads.start("s3-whitelist", new Runnable() {
                            @Override public void run() {
                                final String msg = whitelistByRoot(act);
                                final boolean ok = batteryWhitelisted(act);
                                host.ui(new Runnable() {
                                    @Override public void run() {
                                        host.toast(msg);
                                        if (ok) {
                                            wl[0].setSub("已加入");
                                            wl[0].setSubTone(com.onesay.assistant.v2.kernel.ConsoleHost.TONE_OK);
                                        }
                                    }
                                });
                            }
                        });
                    }
                });
                wl[0].setSubTone(com.onesay.assistant.v2.kernel.ConsoleHost.TONE_WARN);
            }
        }
        for (com.onesay.assistant.v2.kernel.ConsoleHost.Row r : subs) r.setEnabled(main.isOn() && !killed);
        if (killed) {
            main.setEnabled(false);
            host.footer(root, com.onesay.assistant.v2.kernel.ConsoleHost.KILLED);
        }
        final LinearLayout list = host.group(root, isTasks ? "全部任务" : "未完成");
        final android.widget.TextView foot = host.footer(root, isTasks
                ? "说「每天早上8点读一下QQ邮箱未读」「明天下午3点提醒我取快递」建任务；「取消早上8点的任务」删除。"
                : "说「记个待办 买牛奶」「我的待办」「买牛奶做完了」。");
        com.onesay.assistant.v2.util.Threads.start("s3-console-list", new Runnable() {
            @Override public void run() {
                String text;
                String nextAt = null;
                try {
                    S3Engine e = engine(act);
                    long now = System.currentTimeMillis();
                    text = isTasks ? e.listText(now) : e.todoListText(now);
                    if (isTasks && !e.activeTasks().isEmpty()) {
                        long at = S3Scheduler.scheduledAt(act);
                        if (at > 0) nextAt = new java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(new java.util.Date(at));
                    }
                } catch (Throwable t) {
                    text = "读取失败：" + t.getMessage();
                }
                final String[] lines = text.split("\n");
                final String next = nextAt;
                host.ui(new Runnable() {
                    @Override public void run() {
                        if (lines.length <= 1) {
                            host.valueRow(list, lines[0], null, "", com.onesay.assistant.v2.kernel.ConsoleHost.TONE_NORMAL);
                            return;
                        }
                        for (int i = 1; i < lines.length; i++) {
                            String l = lines[i].replaceFirst("^\\d+\\.\\s*", "");
                            host.valueRow(list, l, null, "", com.onesay.assistant.v2.kernel.ConsoleHost.TONE_NORMAL);
                        }
                        if (next != null) foot.setText("下一次闹钟：" + next + "\n" + foot.getText());
                    }
                });
            }
        });
    }



    public static String debugStatus(Context ctx) {
        S3Engine e = engine(ctx);
        long now = System.currentTimeMillis();
        return S3Settings.dump(ctx) + " tasks=" + e.activeTasks().size() + " todos=" + e.store().openTodos().size()
                + " dueToday=" + e.todoDueTodayCount(now) + " alarmAt=" + S3Scheduler.scheduledAt(ctx) + " whitelisted=" + batteryWhitelisted(ctx);
    }
}
