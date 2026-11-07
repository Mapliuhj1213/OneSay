package com.onesay.assistant.v2.s3;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * [s3] 定时任务 + 待办的<b>纯逻辑</b>（JVM 可测；Android 侧 S3Hooks / S3Scheduler / S3Runner 只做接线）。
 *
 * <ul>
 *   <li>{@link #handle}：一句话 → 本地回答（列表）或能力调用（新建 / 取消 / 记待办 / 勾销，交给 ExecutionGate）；</li>
 *   <li>{@link #execute}：S3Backend 执行能力（写库、重算下一次触发）；</li>
 *   <li>{@link #due} / {@link #fired} / {@link #snooze}：到点、补触发、错过、稍后 10 分钟。</li>
 * </ul>
 */
public final class S3Engine {

    public static final long SNOOZE_MS = 10L * 60L * 1000L;
    public static final int MAX_LIST = 8;

    public static final class Flags {
        public final boolean s3, todo;

        public Flags(boolean s3, boolean todo) {
            this.s3 = s3;
            this.todo = todo;
        }

        public boolean any() {
            return s3 || todo;
        }
    }

    public enum Type { CREATE, LIST, CANCEL, CANCEL_MISS, TODO_ADD, TODO_LIST, TODO_DONE, TODO_CLEAR }

    public static final class Result {
        public final Type type;
        public final String reply;
        public final String sign;
        public final JSONObject args;

        Result(Type type, String reply, String sign, JSONObject args) {
            this.type = type;
            this.reply = reply;
            this.sign = sign;
            this.args = args;
        }

        public boolean isLocalReply() {
            return sign == null;
        }

        @Override
        public String toString() {
            return type + (sign != null ? " → " + sign : " reply");
        }
    }

    public static final class Exec {
        public final boolean ok;
        public final String message;

        Exec(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    /** 到点要做的一件事。 */
    public static final class Fire {
        public final S3Store.Task task;
        public final long plannedAt;
        public final int lateMin;
        public final boolean missed;

        Fire(S3Store.Task task, long plannedAt, int lateMin, boolean missed) {
            this.task = task;
            this.plannedAt = plannedAt;
            this.lateMin = lateMin;
            this.missed = missed;
        }

        @Override
        public String toString() {
            return "task#" + task.id + (missed ? " MISSED" : " fire") + (lateMin > 0 ? " late=" + lateMin + "min" : "");
        }
    }

    private final S3Store mStore;
    private final TimeZone mTz;
    private Runnable mOnChange;

    public S3Engine(S3Store store, TimeZone tz) {
        mStore = store;
        mTz = tz;
    }

    public S3Store store() {
        return mStore;
    }

    public TimeZone tz() {
        return mTz;
    }

    /** 库变了（新建 / 取消 / 触发后重排）→ 宿主重排闹钟、刷新岛上的待办。 */
    public void setOnChange(Runnable r) {
        mOnChange = r;
    }

    private void changed() {
        Runnable r = mOnChange;
        if (r != null) {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
        }
    }

    // ================================================================== 路由

    /** null = 不接管（原流程照旧）。 */
    public Result handle(String query, long now, Flags f) {
        if (f == null || !f.any()) return null;
        // ★ install-wait：等装应用的等待任务也算定时任务（可列出、可删除）
        if (f.s3 && query != null && com.onesay.assistant.v2.jobs.InstallWait.CANCEL_Q.matcher(query).find()) {
            com.onesay.assistant.v2.jobs.InstallWait.Job j = com.onesay.assistant.v2.jobs.InstallWait.live().cancelMatching(query);
            if (j != null) {
                if (sWaitChanged != null) sWaitChanged.run();
                return new Result(Type.CANCEL, "已删掉等装" + j.firstLabel() + "的任务，装好后不会再接着做。", null, null);
            }
        }
        S3Parser.Parsed p = S3Parser.parse(query, now, mTz);
        if (p == null) return null;
        try {
            switch (p.type) {
                case CREATE: return f.s3 ? create(p, now) : null;
                case LIST: return f.s3 ? new Result(Type.LIST, listText(now), null, null) : null;
                case CANCEL: return f.s3 ? cancel(p, query) : null;
                case TODO_ADD: return f.todo ? todoAdd(p, now) : null;
                case TODO_LIST: return f.todo ? new Result(Type.TODO_LIST, todoListText(now), null, null) : null;
                case TODO_DONE: return f.todo ? todoDone(p, query) : null;
                case TODO_CLEAR: return f.todo ? todoClear() : null;
                default: return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private Result create(S3Parser.Parsed p, long now) throws Exception {
        String desc = S3Schedule.describe(p.repeat, p.hour, p.minute, p.weekMask, p.onceAt, now, mTz);
        String what = p.kind == S3Parser.KIND_AGENT ? p.command : "提醒你" + p.text;
        JSONObject a = new JSONObject();
        a.put("kind", p.kind);
        a.put("repeat", p.repeat);
        a.put("week_mask", p.weekMask);
        a.put("hour", p.hour);
        a.put("minute", p.minute);
        a.put("once_at", String.valueOf(p.onceAt));
        a.put("title", p.kind == S3Parser.KIND_AGENT ? p.command : p.text);
        a.put("command", p.command);
        a.put("preview", desc + " " + what);
        boolean confirm = p.kind == S3Parser.KIND_AGENT && S3Safety.commandNeedsConfirm(p.command);
        return new Result(Type.CREATE, null, confirm ? S3Capabilities.SIGN_TASK_ADD_CONFIRM : S3Capabilities.SIGN_TASK_ADD, a);
    }

    private Result cancel(S3Parser.Parsed p, String query) throws Exception {
        S3Store.Task best = null;
        double bs = 0;
        for (S3Store.Task t : mStore.tasks()) {
            if (!t.enabled || t.todoId > 0) continue;
            double s = S3Parser.similarity(p.text, t.label());
            if (s > bs) {
                bs = s;
                best = t;
            }
        }
        if (p.text.length() == 0 && (best == null || bs < 0.5)) {
            // ★ AL-07：没点名的「别提醒我了 / 取消提醒」：只有一条在跑 → 直接取消它；一条都没有 → 直说；多条 → 照旧问哪一个
            List<S3Store.Task> act = activeTasks();
            if (act.size() == 1) best = act.get(0);
            else if (act.isEmpty()) return new Result(Type.CANCEL_MISS, "现在没有在跑的提醒或定时任务。", null, null);
            if (best != null) bs = 1.0;
        }
        if (best == null || bs < 0.5) {
            // 没点名（「取消那个提醒」）或找不到：只有句子里明确说了提醒/定时才接管，否则让别的模块（S2「删掉停车那个」）处理
            if (!(query.contains("提醒") || query.contains("定时"))) return null;
            return new Result(Type.CANCEL_MISS, p.text.length() == 0 ? "要取消哪一个？" + shortList() : "没找到「" + p.text + "」相关的定时任务。" + shortList(),
                    null, null);
        }
        JSONObject a = new JSONObject();
        a.put("id", String.valueOf(best.id));
        a.put("preview", best.label());
        return new Result(Type.CANCEL, null, S3Capabilities.SIGN_TASK_CANCEL, a);
    }

    private Result todoAdd(S3Parser.Parsed p, long now) throws Exception {
        JSONObject a = new JSONObject();
        a.put("text", p.text);
        a.put("due_at", String.valueOf(p.dueAt));
        a.put("remind_at", String.valueOf(p.remindAt));
        String due = p.dueAt > 0 ? "（" + S3Schedule.describe(S3Schedule.ONCE, hourOf(p.dueAt), minuteOf(p.dueAt), 0, p.dueAt, now, mTz)
                .replace(" 23:59", "") + " 前）" : "";
        a.put("preview", p.text + due);
        return new Result(Type.TODO_ADD, null, S3Capabilities.SIGN_TODO_ADD, a);
    }

    /** ★ int/1102 审计：清空待办 —— 没有待办就本地答一句；有就出确认卡（s3.todo.clear risk 2），不追问、不进 AgentLoop。 */
    private Result todoClear() throws Exception {
        int n = mStore.openTodos().size();
        if (n == 0) return new Result(Type.TODO_CLEAR, "待办本来就是空的。", null, null);
        JSONObject a = new JSONObject();
        a.put("count", n);
        a.put("preview", "清空全部 " + n + " 条待办");
        return new Result(Type.TODO_CLEAR, null, S3Capabilities.SIGN_TODO_CLEAR, a);
    }

    private Result todoDone(S3Parser.Parsed p, String query) throws Exception {
        S3Store.Todo best = null;
        double bs = 0;
        for (S3Store.Todo t : mStore.openTodos()) {
            double s = S3Parser.similarity(p.text, t.text);
            if (s > bs) {
                bs = s;
                best = t;
            }
        }
        if (best == null || bs < 0.5) {
            if (!S3Parser.explicitTodoDone(query)) return null;   // 「作业做完了」不是待办勾销
            return new Result(Type.TODO_DONE, "没找到「" + p.text + "」这条待办。", null, null);
        }
        JSONObject a = new JSONObject();
        a.put("id", String.valueOf(best.id));
        a.put("preview", best.text);
        return new Result(Type.TODO_DONE, null, S3Capabilities.SIGN_TODO_DONE, a);
    }

    // ================================================================== 文案

    /** ★ install-wait：等待任务被「定时任务」删掉后持久化（Android 侧设置）。 */
    public static volatile Runnable sWaitChanged;

    public String listText(long now) {
        String w = com.onesay.assistant.v2.jobs.InstallWait.live().listLines(now);
        String base = listText0(now);
        if (w.length() == 0) return base;
        return (activeTasks().isEmpty() ? "现在只有等装应用的任务：" : base + "\n另有等装应用的任务：") + w;
    }

    private String listText0(long now) {
        List<S3Store.Task> l = activeTasks();
        if (l.isEmpty()) return "现在没有定时任务。可以说「每天早上8点读一下QQ邮箱未读」或「明天下午3点提醒我取快递」。";
        StringBuilder sb = new StringBuilder("你有 " + l.size() + " 个定时任务：");
        int i = 0;
        for (S3Store.Task t : l) {
            if (i >= MAX_LIST) {
                sb.append("\n…还有 ").append(l.size() - MAX_LIST).append(" 个");
                break;
            }
            i++;
            sb.append("\n").append(i).append(". ").append(S3Schedule.describe(t.repeat, t.hour, t.minute, t.weekMask, t.onceAt, now, mTz))
                    .append(" ").append(t.kind == S3Parser.KIND_AGENT ? t.command : "提醒" + t.title);
            if (t.lastMissedAt > 0) sb.append("（上次错过）");
        }
        return sb.toString();
    }

    private String shortList() {
        List<S3Store.Task> l = activeTasks();
        if (l.isEmpty()) return "现在没有定时任务。";
        StringBuilder sb = new StringBuilder("现在有：");
        for (int i = 0; i < l.size() && i < 4; i++) {
            if (i > 0) sb.append("、");
            sb.append(l.get(i).label());
        }
        return sb.toString();
    }

    public String todoListText(long now) {
        List<S3Store.Todo> l = mStore.openTodos();
        if (l.isEmpty()) return "没有未完成的待办。可以说「记个待办 买牛奶」。";
        StringBuilder sb = new StringBuilder("还有 " + l.size() + " 项待办：");
        for (int i = 0; i < l.size() && i < MAX_LIST; i++) {
            S3Store.Todo t = l.get(i);
            sb.append("\n").append(i + 1).append(". ").append(t.text);
            if (t.dueAt > 0) {
                sb.append("（").append(t.dueAt < now ? "已过期" : S3Schedule.describe(S3Schedule.ONCE, hourOf(t.dueAt), minuteOf(t.dueAt), 0,
                        t.dueAt, now, mTz).replace(" 23:59", "") + " 前").append("）");
            }
        }
        if (l.size() > MAX_LIST) sb.append("\n…还有 ").append(l.size() - MAX_LIST).append(" 项");
        return sb.toString();
    }

    /** 用户可见的定时任务（不含待办派生的提醒）。 */
    public List<S3Store.Task> activeTasks() {
        List<S3Store.Task> l = new ArrayList<S3Store.Task>();
        for (S3Store.Task t : mStore.tasks()) if (t.enabled && t.todoId == 0) l.add(t);
        return l;
    }

    /** 今天到期或已过期的未完成待办数（岛上空闲计数块）。 */
    public int todoDueTodayCount(long now) {
        long end = S3Schedule.dayEnd(now, mTz);
        int n = 0;
        for (S3Store.Todo t : mStore.openTodos()) if (t.dueAt > 0 && t.dueAt <= end) n++;
        return n;
    }

    private int hourOf(long ms) {
        java.util.Calendar c = java.util.Calendar.getInstance(mTz);
        c.setTimeInMillis(ms);
        return c.get(java.util.Calendar.HOUR_OF_DAY);
    }

    private int minuteOf(long ms) {
        java.util.Calendar c = java.util.Calendar.getInstance(mTz);
        c.setTimeInMillis(ms);
        return c.get(java.util.Calendar.MINUTE);
    }

    // ================================================================== 执行

    /** lane-a：最近一次 s3.task.add 入库的任务 id（权限卡确认后要给它存授权）。 */
    private volatile long mLastAdded;

    public long lastAddedTaskId() {
        return mLastAdded;
    }

    public Exec execute(String sign, JSONObject args, long now) {
        try {
            if (S3Capabilities.SIGN_TASK_ADD.equals(sign) || S3Capabilities.SIGN_TASK_ADD_CONFIRM.equals(sign)) {
                S3Store.Task t = new S3Store.Task();
                t.kind = args.optInt("kind", 0);
                t.repeat = args.optInt("repeat", 0);
                t.weekMask = args.optInt("week_mask", 0);
                t.hour = args.optInt("hour", 0);
                t.minute = args.optInt("minute", 0);
                t.onceAt = parseLong(args.optString("once_at", "0"));
                t.title = args.optString("title", "");
                t.command = args.optString("command", "");
                t.createdAt = now;
                t.nextAt = S3Schedule.next(t.repeat, t.hour, t.minute, t.weekMask, t.onceAt, now, mTz);
                if (t.nextAt <= 0) return new Exec(false, "这个时间已经过了");
                if (t.kind == S3Parser.KIND_AGENT && t.command.trim().length() == 0) return new Exec(false, "没听清到点要做什么");
                mLastAdded = mStore.addTask(t);
                changed();
                return new Exec(true, "好的，" + S3Schedule.describe(t.repeat, t.hour, t.minute, t.weekMask, t.onceAt, now, mTz) + " "
                        + (t.kind == S3Parser.KIND_AGENT ? t.command : "提醒你" + t.title));
            }
            if (S3Capabilities.SIGN_TASK_CANCEL.equals(sign)) {
                long id = parseLong(args.optString("id", "0"));
                S3Store.Task t = mStore.task(id);
                if (t == null) return new Exec(false, "这个定时任务已经不在了");
                mStore.deleteTask(id);
                changed();
                return new Exec(true, "已取消：" + t.label());
            }
            if (S3Capabilities.SIGN_TODO_ADD.equals(sign)) {
                S3Store.Todo td = new S3Store.Todo();
                td.text = args.optString("text", "").trim();
                if (td.text.length() == 0) return new Exec(false, "待办内容是空的");
                td.dueAt = parseLong(args.optString("due_at", "0"));
                td.createdAt = now;
                mStore.addTodo(td);
                long remindAt = parseLong(args.optString("remind_at", "0"));
                if (remindAt > now) {
                    S3Store.Task t = new S3Store.Task();
                    t.kind = S3Parser.KIND_REMINDER;
                    t.repeat = S3Schedule.ONCE;
                    t.onceAt = remindAt;
                    t.hour = hourOf(remindAt);
                    t.minute = minuteOf(remindAt);
                    t.title = "待办：" + td.text;
                    t.createdAt = now;
                    t.nextAt = remindAt;
                    t.todoId = td.id;
                    td.remindTaskId = mStore.addTask(t);
                    mStore.updateTodo(td);
                }
                changed();
                return new Exec(true, "记下待办：" + args.optString("preview", td.text));
            }
            if (S3Capabilities.SIGN_TODO_DONE.equals(sign)) {
                return todoCheck(parseLong(args.optString("id", "0")), now);
            }
            if (S3Capabilities.SIGN_TODO_CLEAR.equals(sign)) {
                int n = 0;
                for (S3Store.Todo td : mStore.openTodos()) {
                    td.doneAt = now;
                    mStore.updateTodo(td);
                    if (td.remindTaskId > 0) mStore.deleteTask(td.remindTaskId);
                    n++;
                }
                changed();
                return new Exec(true, n == 0 ? "待办本来就是空的" : "已清空 " + n + " 条待办");
            }
            return new Exec(false, "未知的 S3 能力");
        } catch (Exception e) {
            return new Exec(false, "定时任务库异常：" + e.getMessage());
        }
    }

    /** 勾销待办（语音或岛上点一条）；同时删掉它派生的提醒。 */
    public Exec todoCheck(long id, long now) {
        S3Store.Todo td = mStore.todo(id);
        if (td == null || td.doneAt > 0) return new Exec(false, "这条待办已经不在了");
        td.doneAt = now;
        mStore.updateTodo(td);
        if (td.remindTaskId > 0) mStore.deleteTask(td.remindTaskId);
        changed();
        return new Exec(true, "已完成：" + td.text);
    }

    // ================================================================== 触发

    /** 所有该触发 / 补触发 / 判错过的任务（闹钟回调、开机、改时间后调用）。不改库 —— 处理完调 {@link #fired}。 */
    public List<Fire> due(long now) {
        List<Fire> out = new ArrayList<Fire>();
        for (S3Store.Task t : mStore.tasks()) {
            if (!t.enabled || t.nextAt <= 0) continue;
            S3Schedule.Miss m = S3Schedule.missDecision(t.nextAt, now);
            if (m == S3Schedule.Miss.NOT_DUE) continue;
            int late = m == S3Schedule.Miss.FIRE_LATE ? (int) ((now - t.nextAt) / 60000L) : 0;
            out.add(new Fire(t, t.nextAt, late, m == S3Schedule.Miss.MISSED));
        }
        return out;
    }

    /** 一次触发（或错过）已处理：记下时刻，重复任务排下一次，一次性任务结束（删掉）。 */
    public void fired(Fire f, long now) {
        S3Store.Task t = mStore.task(f.task.id);
        if (t == null) return;
        if (f.missed) t.lastMissedAt = f.plannedAt;
        else t.lastFiredAt = now;
        t.nextAt = S3Schedule.next(t.repeat, t.hour, t.minute, t.weekMask, t.onceAt, Math.max(now, f.plannedAt), mTz);
        if (t.repeat == S3Schedule.ONCE || t.nextAt <= 0) {
            if (f.missed) {
                t.nextAt = -1L;
                t.enabled = false;   // 错过的一次性任务留一条记录（列表不显示），不再触发
                mStore.updateTask(t);
            } else {
                mStore.deleteTask(t.id);
            }
        } else {
            mStore.updateTask(t);
        }
        changed();
    }

    /** 记下 AGENT 任务最近一次的结果（列表 / 控制台显示）。 */
    public void recordResult(long taskId, String result) {
        S3Store.Task t = mStore.task(taskId);
        if (t == null) return;
        t.lastResult = result == null ? "" : (result.length() > 200 ? result.substring(0, 200) : result);
        mStore.updateTask(t);
    }

    /** 稍后 10 分钟：新建一条一次性提醒（标题不变）。返回新任务 id。 */
    public long snooze(String title, long todoId, long now) {
        S3Store.Task t = new S3Store.Task();
        t.kind = S3Parser.KIND_REMINDER;
        t.repeat = S3Schedule.ONCE;
        t.onceAt = now + SNOOZE_MS;
        t.hour = hourOf(t.onceAt);
        t.minute = minuteOf(t.onceAt);
        t.title = title == null ? "" : title;
        t.createdAt = now;
        t.nextAt = t.onceAt;
        t.todoId = todoId;
        long id = mStore.addTask(t);
        changed();
        return id;
    }

    /**
     * 灵动岛提醒「推迟」：只把<b>这一次</b>的计划触发往后挪 ms（只改 nextAt，不改 hour/minute/onceAt —— 任务规格不变，授权不失效）。
     * 计划时刻对不上（已经触发 / 被改过）返回 false。
     */
    public boolean postpone(long taskId, long plannedAt, long ms, long now) {
        S3Store.Task t = mStore.task(taskId);
        if (t == null || !t.enabled || t.nextAt != plannedAt || ms <= 0 || plannedAt <= now) return false;
        t.nextAt = plannedAt + ms;
        mStore.updateTask(t);
        changed();
        return true;
    }

    /** 灵动岛提醒「取消发送」：跳过这一次。重复任务排到下一次；一次性任务停用（留记录，不触发）。 */
    public boolean skipOccurrence(long taskId, long plannedAt, long now) {
        S3Store.Task t = mStore.task(taskId);
        if (t == null || !t.enabled || t.nextAt != plannedAt) return false;
        long next = t.repeat == S3Schedule.ONCE ? -1L
                : S3Schedule.next(t.repeat, t.hour, t.minute, t.weekMask, t.onceAt, Math.max(now, plannedAt), mTz);
        if (next <= 0) {
            t.nextAt = -1L;
            t.enabled = false;
            t.lastResult = "已取消这次发送";
        } else {
            t.nextAt = next;
            t.lastResult = "跳过了一次（取消发送）";
        }
        mStore.updateTask(t);
        changed();
        return true;
    }

    /** 最近的一次计划触发（排闹钟用；-1 = 没有）。 */
    public long earliestNext() {
        long best = -1L;
        for (S3Store.Task t : mStore.tasks()) {
            if (!t.enabled || t.nextAt <= 0) continue;
            if (best < 0 || t.nextAt < best) best = t.nextAt;
        }
        return best;
    }

    static long parseLong(String s) {
        try {
            return Long.parseLong(s == null ? "0" : s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
