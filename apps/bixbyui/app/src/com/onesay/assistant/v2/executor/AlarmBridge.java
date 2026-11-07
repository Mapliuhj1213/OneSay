package com.onesay.assistant.v2.executor;

import android.content.Context;
import android.text.format.DateFormat;
import android.util.Log;

import com.onesay.assistant.v2.capability.Capability;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 闹钟桥：「设个 7 点的闹钟」「关掉八点的闹钟」「删除所有闹钟」「我有哪些闹钟」。
 *
 * <h3>落点（按顺序）</h3>
 * <ol>
 *   <li><b>OneSay 时钟（{@value #CLOCK_PKG}）已安装且与 OneSay 同一证书签名</b> → 有序广播进它的
 *       {@code AlarmApiReceiver}（exported，但受 signature 级权限 {@value #CLOCK_PERMISSION} 保护，
 *       协议见时钟工程 core/AlarmApi）。时钟自己落盘、排程，把最新闹钟表写回 resultData，
 *       面板据此画闹钟卡片（大时间 + 重复 / 倒计时 + 开关）。设 / 查 / 开关 / 删 / 全部删 / 全部开关都走这里。</li>
 *   <li><b>没装 OneSay 时钟</b>（或装了但签名不同、版本太旧没有接口）→ 设闹钟交给系统
 *       {@code AlarmClock.ACTION_SET_ALARM}（HOUR / MINUTES / MESSAGE / 重复日 DAYS / SKIP_UI）；
 *       系统里没有任何 App 能接这个 Intent → 如实回「没有设置成功」，绝不假装成功。
 *       查闹钟打开系统闹钟列表（ACTION_SHOW_ALARMS）；开关 / 删除系统时钟不提供可靠接口 → 如实说明。</li>
 * </ol>
 *
 * <p>Android 相关的部分（PackageManager / 有序广播 / startActivity）在 {@link AndroidAlarmLink}；
 * 本类只依赖 {@link Link} 接口，路由与回话全部可在 JVM 单测里用假 Link 覆盖。
 *
 * <h3>闹钟表格式（时钟接口 resultData）</h3>
 * <pre>
 *   {"ts":毫秒,"count":N,"lastId":本次操作 id,"alarms":[
 *       {"id":1,"h":8,"m":0,"d":0,"on":true,"r":"smart","next":毫秒,"label":""}]}
 * </pre>
 */
public final class AlarmBridge {

    private static final String TAG = "AlarmBridge";

    // ------------------------------------------------------------ 常量

    /** OneSay 时钟包名。 */
    public static final String CLOCK_PKG = "com.onesay.clock";
    /** 时钟接口权限（signature 级；OneSay 清单里 uses-permission 并同名声明）。 */
    public static final String CLOCK_PERMISSION = "com.onesay.clock.permission.ALARM_CONTROL";

    static final String A_SET    = "com.onesay.clock.action.ALARM_SET";
    static final String A_TOGGLE = "com.onesay.clock.action.ALARM_TOGGLE";
    static final String A_CANCEL = "com.onesay.clock.action.ALARM_CANCEL";
    static final String A_QUERY  = "com.onesay.clock.action.ALARM_QUERY";

    /** 时钟接口结果码（与时钟 core/AlarmApi 一致）。0 = 没有接收器回应（时钟太旧 / 没投递到）。 */
    public static final int RC_NONE = 0, RC_OK = 1, RC_BAD_ARGS = 2, RC_NOT_FOUND = 3, RC_UNKNOWN = 4;

    /** 系统闹钟提醒文字（EXTRA_MESSAGE）。 */
    public static final String SYSTEM_MESSAGE = "OneSay";

    /** 闹钟域能力签名（唯一真源：路由 / 卡片渲染 / 回执全按它判）。 */
    public static final String SIGN_SET = "clock.alarm.set";
    public static final String SIGN_QUERY = "alarm.query";
    public static final String SIGN_TOGGLE = "alarm.toggle";
    public static final String SIGN_CANCEL = "alarm.cancel";
    /** ★ device-1102：「删除所有闹钟」——目标就是全部，不追问时间；不可撤销 → 能力声明 risk 2 + confirm（确认卡一次）。 */
    public static final String SIGN_CANCEL_ALL = "alarm.cancel.all";
    /** ★ device-1102：「关掉全部闹钟」——可逆，直接执行。 */
    public static final String SIGN_TOGGLE_ALL = "alarm.toggle.all";

    /** 执行器类型（ClockCaps 里闹钟能力的 executor）。 */
    public static final String EXECUTOR = "alarmbridge";

    private static final String[] WEEK_CN = {"日", "一", "二", "三", "四", "五", "六"};

    private AlarmBridge() { }

    /** 是否属于闹钟域能力。 */
    public static boolean isAlarmSign(String sign) {
        if (sign == null) return false;
        return SIGN_SET.equals(sign) || SIGN_QUERY.equals(sign)
                || SIGN_TOGGLE.equals(sign) || SIGN_CANCEL.equals(sign)
                || SIGN_CANCEL_ALL.equals(sign) || SIGN_TOGGLE_ALL.equals(sign);
    }

    // ------------------------------------------------------------ 通道抽象（JVM 可测）

    /** OneSay 时钟的可用状态。 */
    public enum ClockState { MISSING, UNTRUSTED, READY }

    /** 闹钟落点。 */
    public enum Route { CLOCK, SYSTEM }

    /** 系统 Intent 的结果。 */
    public enum SystemOutcome { STARTED, NO_HANDLER, FAILED }

    /** 发给时钟的一条命令（纯数据；Android 层转成 Intent extras）。 */
    public static final class ClockCmd {
        public final String action;
        public final int id, hour, minute, days;
        public final boolean on;

        ClockCmd(String action, int id, int hour, int minute, int days, boolean on) {
            this.action = action; this.id = id; this.hour = hour; this.minute = minute; this.days = days & 0x7F; this.on = on;
        }

        public static ClockCmd set(int hour, int minute, int days) { return new ClockCmd(A_SET, -1, hour, minute, days, false); }
        public static ClockCmd toggle(int id, boolean on) { return new ClockCmd(A_TOGGLE, id, -1, -1, 0, on); }
        public static ClockCmd cancel(int id) { return new ClockCmd(A_CANCEL, id, -1, -1, 0, false); }
        public static ClockCmd query() { return new ClockCmd(A_QUERY, -1, -1, -1, 0, false); }

        @Override public String toString() {
            return action.substring(action.lastIndexOf('.') + 1) + " id=" + id + " " + hour + ":" + minute + " d=" + days + " on=" + on;
        }
    }

    /** 时钟的回应：resultCode + 解析好的闹钟表（可能为 null）。 */
    public static final class ClockReply {
        public final int code;
        public final JSONObject dump;

        public ClockReply(int code, JSONObject dump) { this.code = code; this.dump = dump; }

        public boolean ok() { return code == RC_OK && dump != null; }
    }

    /** 系统闹钟 Intent 的内容（纯数据；Android 层转成 AlarmClock Intent）。 */
    public static final class SystemAlarmSpec {
        /** true = ACTION_SET_ALARM；false = ACTION_SHOW_ALARMS（只打开闹钟列表）。 */
        public final boolean set;
        public final int hour, minutes;
        public final String message;
        /** EXTRA_DAYS：java.util.Calendar.SUNDAY(1) … SATURDAY(7)；空 = 只响一次（不放这个 extra）。 */
        public final List<Integer> days;
        public final boolean skipUi;

        SystemAlarmSpec(boolean set, int hour, int minutes, String message, List<Integer> days, boolean skipUi) {
            this.set = set; this.hour = hour; this.minutes = minutes; this.message = message; this.days = days; this.skipUi = skipUi;
        }
    }

    /** 通道：真实实现见 {@link AndroidAlarmLink}，单测用假实现。 */
    public interface Link {
        ClockState clockState();

        /** 发一条命令给时钟并等结果；超时 / 没回应 → null。 */
        ClockReply callClock(ClockCmd cmd);

        /** 启动系统闹钟 Intent（先查有没有 App 能接）。 */
        SystemOutcome startSystem(SystemAlarmSpec spec);
    }

    /** 路由（纯函数）：时钟可用走时钟，否则走系统。 */
    public static Route route(ClockState s) {
        return s == ClockState.READY ? Route.CLOCK : Route.SYSTEM;
    }

    /** 位掩码（bit0=周日 … bit6=周六）→ AlarmClock.EXTRA_DAYS 用的 Calendar 星期（周日=1 … 周六=7），按周一起排。 */
    public static List<Integer> calendarDays(int days) {
        days &= 0x7F;
        List<Integer> l = new ArrayList<Integer>();
        for (int i = 1; i <= 7; i++) {
            int b = i % 7;   // 周一(1)…周六(6)、周日(0)
            if ((days & (1 << b)) != 0) l.add(Integer.valueOf(b + 1));
        }
        return l;
    }

    /** 设闹钟的系统 Intent 内容：时间明确 → SKIP_UI（不弹时钟界面）。 */
    public static SystemAlarmSpec systemSet(int hour, int minute, int days) {
        return new SystemAlarmSpec(true, hour, minute, SYSTEM_MESSAGE, calendarDays(days), true);
    }

    /** 只打开系统闹钟列表。 */
    public static SystemAlarmSpec systemShow() {
        return new SystemAlarmSpec(false, -1, -1, null, new ArrayList<Integer>(), false);
    }

    // ------------------------------------------------------------ 执行入口

    /** 单测可注入的通道（null = 用真实 Android 通道）。 */
    private static volatile Link sLinkOverride;

    static void setLinkForTest(Link l) { sLinkOverride = l; }

    /** UnifiedExecutor 的 "alarmbridge" 分支入口。 */
    public static ExecutionResult execute(Capability cap, JSONObject args, Context ctx) {
        Link l = sLinkOverride;
        if (l == null) {
            if (ctx == null) {
                return ExecutionResult.failed(ErrorCode.UNKNOWN_CAPABILITY, "闹钟联动参数缺失", "no context", cap, args);
            }
            l = new AndroidAlarmLink(ctx);
        }
        return execute(cap, args, l, ctx);
    }

    /**
     * 执行（通道显式传入）。返回的 {@link ExecutionResult#detail} 约定为<b>闹钟表 JSON 全文本</b>
     * （UI 层据此渲染闹钟卡片）；走系统时钟时没有闹钟表，detail 为 null。
     */
    public static ExecutionResult execute(Capability cap, JSONObject args, Link link, Context ctx) {
        final String sign = cap != null ? cap.sign : null;
        if (sign == null || link == null) {
            return ExecutionResult.failed(ErrorCode.UNKNOWN_CAPABILITY, "闹钟联动参数缺失", sign, cap, args);
        }
        if (args == null) args = new JSONObject();
        try {
            if (SIGN_SET.equals(sign))    return doSet(cap, args, link, ctx);
            if (SIGN_QUERY.equals(sign))  return doQuery(cap, args, link, ctx);
            if (SIGN_TOGGLE.equals(sign)) return doToggle(cap, args, link, ctx);
            if (SIGN_CANCEL.equals(sign)) return doCancel(cap, args, link, ctx);
            if (SIGN_CANCEL_ALL.equals(sign)) return doCancelAll(cap, args, link, ctx);
            if (SIGN_TOGGLE_ALL.equals(sign)) return doToggleAll(cap, args, link, ctx);
            return ExecutionResult.failed(ErrorCode.UNKNOWN_CAPABILITY,
                    "未知的闹钟联动操作", sign, cap, args);
        } catch (Throwable t) {
            Log.w(TAG, "执行异常 " + sign + ": " + t);
            return ExecutionResult.failed(ErrorCode.EXEC_FAILED,
                    "闹钟联动失败，请重试", String.valueOf(t), cap, args);
        }
    }

    /** 时钟没回应时的统一失败文案。 */
    static final String NO_REPLY = "OneSay 时钟没有响应";
    /** 没装（或用不了）OneSay 时钟、又要开关 / 删除时的说明。 */
    static final String NEED_CLOCK = "没有安装 OneSay 时钟，系统时钟不支持由 OneSay 开关或删除闹钟，请在时钟 App 里操作";

    /** 发给时钟；时钟可用但这一次没接口（版本太旧，没有接收器回应）→ 当作不可用。 */
    static ClockReply call(Link link, ClockCmd cmd) {
        ClockReply r = link.callClock(cmd);
        if (r == null) Log.w(TAG, "时钟没有回应 " + cmd);
        return r;
    }

    /** 当前闹钟（查询）；时钟不可用 / 没回应 → null。 */
    static JSONObject query(Link link) {
        if (route(link.clockState()) != Route.CLOCK) return null;
        ClockReply r = call(link, ClockCmd.query());
        return r != null && r.ok() ? r.dump : null;
    }

    // ------------------------------------------------------------ 六个动作

    private static ExecutionResult doSet(Capability cap, JSONObject args, Link link, Context ctx) {
        int hour = args.optInt("hour", -1);
        int minute = args.optInt("minute", -1);
        if (hour < 0 || hour > 23) {
            return ExecutionResult.failed(ErrorCode.NEED_PARAM,
                    "没听清要设几点，请说“早上七点”这样的时间", "hour=" + hour, cap, args);
        }
        if (minute < 0 || minute > 59) minute = 0;
        int days = args.optInt("days", 0) & 0x7F;   // ★ int/1102 重复闹钟（位掩码）

        if (route(link.clockState()) == Route.CLOCK) {
            ClockReply r = call(link, ClockCmd.set(hour, minute, days));
            if (r != null && r.code != RC_NONE) {
                if (!r.ok()) {
                    return ExecutionResult.failed(ErrorCode.EXEC_FAILED,
                            "OneSay 时钟没有接受这个闹钟，没有设置成功", "rc=" + r.code, cap, args);
                }
                JSONObject target = findById(r.dump, r.dump.optInt("lastId", -1));
                if (target == null) target = findEnabledAt(r.dump, hour, minute);
                long next = target != null ? target.optLong("next", 0L) : 0L;
                if (next <= 0) next = nextAt(System.currentTimeMillis(), hour, minute, days);
                String reply = "已设定" + repeatLabel(days) + "闹钟 · " + describe(ctx, next) + " · OneSay 时钟";
                return ExecutionResult.success(cap, args, reply, true, r.dump.toString());
            }
            if (r == null) {   // 超时：可能已经设上了，不再交给系统重复设
                return ExecutionResult.failed(ErrorCode.EXEC_FAILED,
                        NO_REPLY + "，闹钟可能没有设置成功，请打开时钟确认", "clock timeout", cap, args);
            }
            Log.w(TAG, "OneSay 时钟没有闹钟接口（版本太旧？），改用系统时钟");
        }
        SystemOutcome o = link.startSystem(systemSet(hour, minute, days));
        String when = describe(ctx, nextAt(System.currentTimeMillis(), hour, minute, days));
        if (o == SystemOutcome.STARTED) {
            return ExecutionResult.success(cap, args,
                    "已交给系统时钟设定" + repeatLabel(days) + "闹钟 · " + when, false, null);
        }
        if (o == SystemOutcome.NO_HANDLER) {
            return ExecutionResult.failed(ErrorCode.EXEC_FAILED,
                    "手机上没有能设闹钟的时钟 App，闹钟没有设置成功（可以安装 OneSay 时钟）", "no ACTION_SET_ALARM handler", cap, args);
        }
        return ExecutionResult.failed(ErrorCode.EXEC_FAILED,
                "系统时钟没能打开，闹钟没有设置成功", "ACTION_SET_ALARM failed", cap, args);
    }

    private static ExecutionResult doQuery(Capability cap, JSONObject args, Link link, Context ctx) {
        if (route(link.clockState()) != Route.CLOCK) {
            SystemOutcome o = link.startSystem(systemShow());
            if (o == SystemOutcome.STARTED) {
                return ExecutionResult.success(cap, args, "已打开系统时钟的闹钟列表", false, null);
            }
            return ExecutionResult.failed(ErrorCode.EXEC_FAILED,
                    "没有安装 OneSay 时钟，也没找到系统闹钟列表，暂时查不到闹钟", "no clock, no SHOW_ALARMS", cap, args);
        }
        JSONObject dump = query(link);
        if (dump == null) {
            return ExecutionResult.failed(ErrorCode.EXEC_FAILED,
                    NO_REPLY + "，暂时查不到闹钟", "clock no reply", cap, args);
        }
        int n = dump.optInt("count", 0);
        String reply = (n == 0) ? "还没有闹钟，说“设个七点的闹钟”就行" : ("共 " + n + " 个闹钟");
        return ExecutionResult.success(cap, args, reply, true, dump.toString());
    }

    /** 开关 / 删除前的共同检查：时钟不可用 → 失败回执；否则 null。 */
    private static ExecutionResult needClock(Capability cap, JSONObject args, Link link) {
        if (route(link.clockState()) == Route.CLOCK) return null;
        return ExecutionResult.failed(ErrorCode.EXEC_FAILED, NEED_CLOCK, "clock " + link.clockState(), cap, args);
    }

    private static ExecutionResult doToggle(Capability cap, JSONObject args, Link link, Context ctx) {
        ExecutionResult no0 = needClock(cap, args, link);
        if (no0 != null) return no0;
        boolean on = parseBool(args.opt("on"), false);
        int id = args.optInt("id", -1);
        int hour = args.optInt("hour", -1);
        boolean hasHour = hour >= 0 && hour <= 23;

        JSONObject dump = query(link);
        if (dump == null) {
            return ExecutionResult.failed(ErrorCode.EXEC_FAILED,
                    NO_REPLY + "，闹钟状态未知", "clock no reply", cap, args);
        }
        // ★ audit-J 3：带分钟 = 时、分完全匹配才动（「关掉6点40的闹钟」不会动到 6:00 / 别的闹钟）；0 个 / 多个都只回话不动
        if (id <= 0 && hasHour && args.has("minute")) {
            JSONObject[] one = new JSONObject[1];
            ExecutionResult no = exactPick(cap, args, dump, "改动", one);
            if (no != null) return no;
            return toggleTarget(cap, args, link, ctx, one[0], on);
        }
        JSONObject target = pickTarget(dump, id, hour, hasHour);
        if (target == null) {
            int n = dump.optInt("count", 0);
            if (n == 0) {
                return ExecutionResult.failed(ErrorCode.EXEC_FAILED,
                        "还没有可以操作的闹钟", "count=0",
                        "当前没有任何闹钟，无需操作；如需新建请用 clock.alarm.set", cap, args);
            }
            return ExecutionResult.failed(ErrorCode.NEED_PARAM,
                    "有多个闹钟，请说清楚是哪一个（比如“关掉八点的闹钟”）",
                    "count=" + n + " 未指定目标", cap, args);
        }
        return toggleTarget(cap, args, link, ctx, target, on);
    }

    private static ExecutionResult toggleTarget(Capability cap, JSONObject args, Link link, Context ctx, JSONObject target, boolean on) {
        int tid = target.optInt("id", -1);
        ClockReply r = call(link, ClockCmd.toggle(tid, on));
        if (r == null || r.dump == null) {
            return ExecutionResult.failed(ErrorCode.EXEC_FAILED,
                    NO_REPLY + "，没有改动任何闹钟", "clock no reply", cap, args);
        }
        JSONObject after = findById(r.dump, tid);
        boolean nowOn = after != null && after.optBoolean("on", false);
        if (!r.ok() || nowOn != on) {
            return ExecutionResult.failed(ErrorCode.EXEC_FAILED,
                    "开关没有生效，没有改动", "rc=" + r.code, cap, args);
        }
        long next = after.optLong("next", 0L);
        String reply = (on ? "已开启 · " : "已关闭 · ") + describe(ctx, on ? next : target.optLong("next", 0L));
        return ExecutionResult.success(cap, args, reply, true, r.dump.toString());
    }

    private static ExecutionResult doCancel(Capability cap, JSONObject args, Link link, Context ctx) {
        ExecutionResult no0 = needClock(cap, args, link);
        if (no0 != null) return no0;
        int id = args.optInt("id", -1);
        int hour = args.optInt("hour", -1);
        boolean hasHour = hour >= 0 && hour <= 23;

        JSONObject dump = query(link);
        if (dump == null) {
            return ExecutionResult.failed(ErrorCode.EXEC_FAILED,
                    NO_REPLY + "，暂时不能删除", "clock no reply", cap, args);
        }
        JSONObject target;
        if (id <= 0 && hasHour && args.has("minute")) {   // ★ audit-J 3：时、分完全匹配才删；0 个 / 多个只回话不删
            JSONObject[] one = new JSONObject[1];
            ExecutionResult no = exactPick(cap, args, dump, "删", one);
            if (no != null) return no;
            target = one[0];
        } else {
            target = pickTarget(dump, id, hour, hasHour);
        }
        if (target == null) {
            int n = dump.optInt("count", 0);
            if (n == 0) {
                return ExecutionResult.failed(ErrorCode.EXEC_FAILED,
                        "还没有可以删除的闹钟", "count=0", "当前没有任何闹钟，无需删除",
                        cap, args);
            }
            return ExecutionResult.failed(ErrorCode.NEED_PARAM,
                    "有 " + n + " 个闹钟，要删哪一个？（比如“删掉八点的闹钟”，或者说“全部删掉”）",
                    "count=" + n + " 未指定目标", cap, args);
        }
        long next = target.optLong("next", 0L);
        int tid = target.optInt("id", -1);
        ClockReply r = call(link, ClockCmd.cancel(tid));
        if (r == null || r.dump == null) {
            return ExecutionResult.failed(ErrorCode.EXEC_FAILED,
                    NO_REPLY + "，没有删除任何闹钟", "clock no reply", cap, args);
        }
        if (!r.ok() || findById(r.dump, tid) != null) {
            return ExecutionResult.failed(ErrorCode.EXEC_FAILED,
                    "删除没有生效，请再试一次", "id=" + tid + " rc=" + r.code, cap, args);
        }
        String reply = "已删除 · " + (next > 0 ? describe(ctx, next) : hhmm(true, target.optInt("h", 0), target.optInt("m", 0)));
        return ExecutionResult.success(cap, args, reply, true, r.dump.toString());
    }

    /** ★ device-1102：全部闹钟的 id（纯函数）。 */
    public static java.util.List<Integer> allIds(JSONObject dump) {
        java.util.List<Integer> l = new java.util.ArrayList<Integer>();
        JSONArray arr = dump == null ? null : dump.optJSONArray("alarms");
        if (arr == null) return l;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            int id = o == null ? -1 : o.optInt("id", -1);
            if (id > 0) l.add(Integer.valueOf(id));
        }
        return l;
    }

    /** ★ device-1102：删除全部（门禁的确认卡已在 UnifiedExecutor 第 1 步完成）。 */
    private static ExecutionResult doCancelAll(Capability cap, JSONObject args, Link link, Context ctx) {
        ExecutionResult no0 = needClock(cap, args, link);
        if (no0 != null) return no0;
        JSONObject dump = query(link);
        if (dump == null) {
            return ExecutionResult.failed(ErrorCode.EXEC_FAILED, NO_REPLY + "，暂时不能删除", "clock no reply", cap, args);
        }
        java.util.List<Integer> ids = allIds(dump);
        if (ids.isEmpty()) {
            return ExecutionResult.success(cap, args, "现在没有闹钟，不用删", true, dump.toString());
        }
        JSONObject last = dump;
        int left = 0;
        for (Integer id : ids) {
            ClockReply r = call(link, ClockCmd.cancel(id.intValue()));
            if (r != null && r.dump != null) last = r.dump;
        }
        for (Integer id : ids) if (findById(last, id.intValue()) != null) left++;
        if (left == ids.size()) {
            return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "删除没有生效，闹钟都还在", "left=" + left, cap, args);
        }
        String reply = left == 0 ? "已删除全部 " + ids.size() + " 个闹钟" : "删掉了 " + (ids.size() - left) + " 个，还有 " + left + " 个没删掉";
        return ExecutionResult.success(cap, args, reply, left == 0, last.toString());
    }

    /** ★ device-1102：全部开 / 关（可逆，不弹卡）。 */
    private static ExecutionResult doToggleAll(Capability cap, JSONObject args, Link link, Context ctx) {
        ExecutionResult no0 = needClock(cap, args, link);
        if (no0 != null) return no0;
        boolean on = parseBool(args.opt("on"), false);
        JSONObject dump = query(link);
        if (dump == null) {
            return ExecutionResult.failed(ErrorCode.EXEC_FAILED, NO_REPLY + "，闹钟状态未知", "clock no reply", cap, args);
        }
        java.util.List<Integer> ids = allIds(dump);
        if (ids.isEmpty()) return ExecutionResult.success(cap, args, "现在没有闹钟", true, dump.toString());
        JSONObject last = dump;
        int changed = 0;
        for (Integer id : ids) {
            ClockReply r = call(link, ClockCmd.toggle(id.intValue(), on));
            if (r == null || r.dump == null) continue;
            last = r.dump;
            JSONObject a = findById(r.dump, id.intValue());
            if (a != null && a.optBoolean("on", !on) == on) changed++;
        }
        String reply = (on ? "已开启 " : "已关闭 ") + changed + " 个闹钟" + (changed < ids.size() ? "（" + (ids.size() - changed) + " 个没改动）" : "");
        return ExecutionResult.success(cap, args, reply, changed == ids.size(), last.toString());
    }

    /** 卡片上的开关（面板直接调用，不经路由）：时钟不可用 / 没回应 → null。 */
    public static JSONObject toggleFromCard(Context ctx, int id, boolean on) {
        Link l = sLinkOverride != null ? sLinkOverride : (ctx == null ? null : new AndroidAlarmLink(ctx));
        if (l == null || route(l.clockState()) != Route.CLOCK) return null;
        ClockReply r = call(l, ClockCmd.toggle(id, on));
        return r != null ? r.dump : null;
    }

    // ------------------------------------------------------------ 重复日

    /** 重复日的口语标签（「每天」「工作日」「周末」或「每周一三五」）；0 = 空串。 */
    public static String repeatLabel(int days) {
        days &= 0x7F;
        if (days == 0) return "";
        if (days == 0x7F) return "每天的";
        if (days == 0x3E) return "工作日的";
        if (days == 0x41) return "周末的";
        String[] zh = {"日", "一", "二", "三", "四", "五", "六"};
        StringBuilder b = new StringBuilder("每周");
        for (int i = 1; i <= 7; i++) if ((days & (1 << (i % 7))) != 0) b.append(zh[i % 7]);
        return b.append("的").toString();
    }

    // ------------------------------------------------------------ dump 解析（纯函数）

    /**
     * 从时钟接口的 resultData（或混有杂行的文本）里摘出闹钟表 JSON。
     *
     * <p>在"行首"找 JSON 起点：杂行（如 {@code Broadcasting: Intent { act=... }}）行中自带 `{`，
     * 按"第一个 { 字符"截取会整段失败；行首判定天然跳过这类杂行。没有 alarms 字段 → null。
     *
     * <p>纯解析（自测可直接喂样例）。
     */
    public static JSONObject parseDumpJson(String out) {
        if (out == null) return null;
        int start = -1;
        int i = 0;
        while (i < out.length()) {
            int lineEnd = out.indexOf('\n', i);
            if (lineEnd < 0) lineEnd = out.length();
            int p = i;
            while (p < lineEnd && (out.charAt(p) == ' ' || out.charAt(p) == '\t')) p++;
            if (p < lineEnd && out.charAt(p) == '{') {
                start = p;
                break;
            }
            i = lineEnd + 1;
        }
        if (start < 0) return null;
        try {
            JSONObject o = new JSONObject(out.substring(start));
            if (!o.has("alarms")) return null;
            return o;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按 id 找（找不到返回 null）。 */
    public static JSONObject findById(JSONObject dump, int id) {
        if (dump == null || id <= 0) return null;
        JSONArray arr = dump.optJSONArray("alarms");
        if (arr == null) return null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && o.optInt("id", -1) == id) return o;
        }
        return null;
    }

    /** 找开着且时刻匹配的条目（SET 回执用）。 */
    public static JSONObject findEnabledAt(JSONObject dump, int hour, int minute) {
        if (dump == null) return null;
        JSONArray arr = dump.optJSONArray("alarms");
        if (arr == null) return null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (o.optBoolean("on", false)
                    && o.optInt("h", -1) == hour && o.optInt("m", -1) == minute) {
                return o;
            }
        }
        return null;
    }

    /**
     * toggle/cancel 的目标选择（纯函数）。
     *
     * <p>优先级：显式 id &gt; 指定小时（找 h 匹配的第一条） &gt; 唯一闹钟。
     * 多个且未指定 ⇒ null（上层给"请说清楚哪一个"的提示）。
     */
    public static JSONObject pickTarget(JSONObject dump, int id, int hour, boolean hasHour) {
        if (dump == null) return null;
        if (id > 0) return findById(dump, id);
        JSONArray arr = dump.optJSONArray("alarms");
        if (arr == null || arr.length() == 0) return null;
        if (hasHour) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && o.optInt("h", -1) == hour) return o;
            }
            return null;
        }
        if (arr.length() == 1) return arr.optJSONObject(0);
        return null;
    }

    /**
     * ★ audit-J 3：时、分完全匹配的闹钟（纯函数）。altHour &ge; 0 = 用户没说上午下午（「6点40」），两个钟点都算候选，
     * 按现有闹钟里实际有的那个来；minute 必须相等。返回全部匹配（调用方：1 个才动，0 / 多个只回话）。
     */
    public static java.util.List<JSONObject> exactMatches(JSONObject dump, int hour, int minute, int altHour) {
        java.util.List<JSONObject> l = new java.util.ArrayList<JSONObject>();
        JSONArray arr = dump == null ? null : dump.optJSONArray("alarms");
        if (arr == null || minute < 0) return l;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || o.optInt("m", -1) != minute) continue;
            int h = o.optInt("h", -1);
            if (h == hour || (altHour >= 0 && h == altHour)) l.add(o);
        }
        return l;
    }

    /** 现有闹钟钟点列表（「6:40、7:00（已关）」；没有 = 「没有闹钟」）。 */
    public static String listTimes(JSONObject dump) {
        JSONArray arr = dump == null ? null : dump.optJSONArray("alarms");
        if (arr == null || arr.length() == 0) return "没有闹钟";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < arr.length() && i < 8; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (b.length() > 0) b.append("、");
            b.append(String.format(java.util.Locale.US, "%d:%02d", o.optInt("h", 0), o.optInt("m", 0)));
            if (!o.optBoolean("on", true)) b.append("（已关）");
        }
        return b.toString();
    }

    static String timesOf(java.util.List<JSONObject> l) {
        StringBuilder b = new StringBuilder();
        for (JSONObject o : l) {
            if (b.length() > 0) b.append("、");
            b.append(String.format(java.util.Locale.US, "%d:%02d", o.optInt("h", 0), o.optInt("m", 0)));
        }
        return b.toString();
    }

    /** 精确匹配选目标：唯一 → out[0]、返回 null；0 个 / 多个 → 失败回执（什么都不动）。 */
    static ExecutionResult exactPick(Capability cap, JSONObject args, JSONObject dump, String verb, JSONObject[] out) {
        int hour = args.optInt("hour", -1), minute = args.optInt("minute", -1), alt = args.optInt("alt_hour", -1);
        java.util.List<JSONObject> l = exactMatches(dump, hour, minute, alt);
        String hm = String.format(java.util.Locale.US, "%d:%02d", hour, minute)
                + (alt >= 0 ? String.format(java.util.Locale.US, "（或 %d:%02d）", alt, minute) : "");
        if (l.isEmpty()) {
            return ExecutionResult.failed(ErrorCode.EXEC_FAILED,
                    "没有 " + hm + " 的闹钟，没" + verb + "任何闹钟。现在有：" + listTimes(dump),
                    "no exact match h=" + hour + " m=" + minute + " alt=" + alt, cap, args);
        }
        if (l.size() > 1) {
            return ExecutionResult.failed(ErrorCode.NEED_PARAM,
                    "有 " + l.size() + " 个对得上的闹钟（" + timesOf(l) + "），没" + verb
                            + "。请说清楚上午还是下午（比如“删掉晚上6点40的闹钟”）",
                    "multi exact match " + l.size(), cap, args);
        }
        out[0] = l.get(0);
        return null;
    }

    /** args 里的布尔兼容解析（addBoolean 默认值进 JSON 后可能是 String）。 */
    public static boolean parseBool(Object v, boolean def) {
        if (v == null) return def;
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        String s = String.valueOf(v).trim().toLowerCase();
        if ("true".equals(s) || "1".equals(s)) return true;
        if ("false".equals(s) || "0".equals(s)) return false;
        return def;
    }

    // ------------------------------------------------------------ 时间格式化（纯函数）

    /** "7:00" 或 "07:00"（跟随系统制式）。 */
    public static String hhmm(boolean is24, int h24, int minute) {
        if (is24) {
            return String.format(java.util.Locale.US, "%02d:%02d", h24, minute);
        }
        int h = h24 % 12;
        if (h == 0) h = 12;
        return String.format(java.util.Locale.US, "%d:%02d", h, minute);
    }

    /** 12 小时制下的「上午 / 下午」。 */
    public static String period(int h24) {
        return h24 < 12 ? "上午" : "下午";
    }

    /**
     * 绝对时刻说人话："今天 上午 7:00 / 明天 上午 7:00 / 周三 上午 7:00"。
     * 全部通过参数显式传入 ⇒ 可离线单测。
     */
    public static String describe(long at, long nowMs, boolean is24) {
        if (at <= 0) return "时间未知";
        Calendar t = Calendar.getInstance();
        t.setTimeInMillis(at);
        int h = t.get(Calendar.HOUR_OF_DAY);
        int m = t.get(Calendar.MINUTE);
        String day = dayWord(t, nowMs);
        String p = is24 ? "" : period(h) + " ";
        return day + " " + p + hhmm(is24, h, m);
    }

    /** 便捷重载：真实调用环境。 */
    public static String describe(Context ctx, long at) {
        boolean is24 = true;
        try {
            is24 = DateFormat.is24HourFormat(ctx);
        } catch (Throwable ignored) { }
        return describe(at, System.currentTimeMillis(), is24);
    }

    /** 今天 / 明天 / 后天 / 周三 / 9月28日（超过一周说日期）。 */
    static String dayWord(Calendar target, long nowMs) {
        Calendar now = Calendar.getInstance();
        now.setTimeInMillis(nowMs);
        int d = dayDiff(now, target);
        if (d == 0) return "今天";
        if (d == 1) return "明天";
        if (d == 2) return "后天";
        if (d > 2 && d < 7) return "周" + WEEK_CN[target.get(Calendar.DAY_OF_WEEK) - 1];
        return (target.get(Calendar.MONTH) + 1) + "月" + target.get(Calendar.DAY_OF_MONTH) + "日";
    }

    /**
     * 相隔几天（都归零到当天 00:00 再除 —— 直接除 86400000 会被跨零点
     * 两小时/夏令时算错，踩过的坑）。
     */
    static int dayDiff(Calendar from, Calendar to) {
        Calendar a = (Calendar) from.clone();
        Calendar b = (Calendar) to.clone();
        zero(a);
        zero(b);
        long ms = b.getTimeInMillis() - a.getTimeInMillis();
        return (int) Math.round(ms / 86400000.0);
    }

    private static void zero(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    /** "还有 3 小时 46 分"（已过 ⇒ "时间已过"）。 */
    public static String countdown(long at, long nowMs) {
        long left = at - nowMs;
        if (left <= 0) return "时间已过";
        long mins = left / 60000L;
        long hrs = mins / 60L;
        if (hrs >= 24) return String.format(java.util.Locale.US, "还有 %d 天 %d 小时", hrs / 24, hrs % 24);
        if (hrs >= 1) return String.format(java.util.Locale.US, "还有 %d 小时 %d 分", hrs, mins % 60);
        if (mins >= 1) return String.format(java.util.Locale.US, "还有 %d 分钟", mins);
        return "不到 1 分钟";
    }

    /**
     * 下次触发时刻（与 OneSay 时钟 AlarmMath.next 同口径；nowMs 显式传入便于单测）。
     * days 位掩码：bit0=周日 … bit6=周六；0=只响一次。
     */
    public static long nextAt(long nowMs, int hour, int minute, int days) {
        days &= 0x7F;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(nowMs);
        setHm(c, hour, minute);
        if (days == 0) {
            if (c.getTimeInMillis() <= nowMs) {
                c.add(Calendar.DAY_OF_YEAR, 1);
                setHm(c, hour, minute);
            }
            return c.getTimeInMillis();
        }
        for (int k = 0; k < 8; k++) {
            int idx = c.get(Calendar.DAY_OF_WEEK) - 1;   // 周日 = 0 … 周六 = 6
            if ((days & (1 << idx)) != 0 && c.getTimeInMillis() > nowMs) {
                return c.getTimeInMillis();
            }
            c.add(Calendar.DAY_OF_YEAR, 1);
            setHm(c, hour, minute);
        }
        return c.getTimeInMillis();
    }

    private static void setHm(Calendar c, int hour, int minute) {
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }
}
