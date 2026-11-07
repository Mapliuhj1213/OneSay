package com.onesay.assistant.v2.brief;

import org.json.JSONObject;

/**
 * 晨间播报 v2 → 灵动岛：BRIEF_STATE 广播的解析、来源校验与上岛计划（纯 Java，JVM 可测）。
 *
 * <h3>广播格式（对照 morning-brief 仓库 feature/mb-v2 的 V2State / V2GenJob / V2PlayService，只读）</h3>
 * <pre>
 *   action   com.onesay.clock.BRIEF_STATE（同一条广播先发 v2 自己，再 setPackage("com.onesay.assistant") 发给我们）
 *   extras   state（见下）· detail · briefPath（v2 私有目录 files/v2/brief.json）
 *            有成品时另带 title · l1（天气句）· l2/l3（前两条新闻）· generatedAt · forAlarmAt
 *   state    GENERATING            开始生成
 *            READY                 生成成功
 *            IDLE + "error:xxx"    生成失败（IDLE 且 detail 为空 = 空闲，不上岛）
 *            PLAYING               开始播（detail "durationMs=…"；"ringtone" = 成品无效，闹钟改响系统铃声）
 *            DONE                  播完 / 跳到结尾（"done"、"skipped_to_end"…）；
 *                                  "error:…"、"invalid:…"、"no_audio" 是播放失败
 *            DETAIL / DUMP         调试 / 展开请求，不上岛
 * </pre>
 * v2 在发广播<b>之前</b>先把 {@code {"state","detail","at"}} 原子写进自己的 {@code files/v2/state.json}。
 *
 * <h3>来源校验（{@link #verifySource}）</h3>
 * Android 8 的 BroadcastReceiver 拿不到发送方包名；v2 也没有声明或持有任何可用作保护的权限（那个仓库不改），
 * 所以「加权限保护」走不通。改为<b>到发送方的私有目录里对账</b>：经 root 守护读
 * {@code /data/data/com.onesay.clock/files/state.json} 和它的属主 uid ——
 * 只有 OneSay Clock（com.onesay.clock）本身（或 root）能写这个文件。属主 uid 必须等于 PackageManager 里 v2 的 uid，
 * 文件里的 state / detail 必须与广播一致，且写入时间在 {@link #FRESH_MS} 之内。任一不符就丢弃；读不到（没 root、没装 v2）也丢弃。
 */
public final class BriefState {

    private BriefState() {}

    public static final String SENDER_PKG = "com.onesay.clock";
    public static final String ACTION = "com.onesay.clock.BRIEF_STATE";
    /** v2 的状态文件（Context.getFilesDir() = /data/data/&lt;pkg&gt;/files，V2Wiring.store = files/v2）。 */
    public static final String STATE_FILE = "/data/data/" + SENDER_PKG + "/files/state.json";
    /** 读属主 uid + 内容的只读命令（常量，不拼接任何广播内容）。 */
    public static final String VERIFY_CMD = "stat -c %u " + STATE_FILE + " && cat " + STATE_FILE;
    /** 状态文件写入时间与收到广播的最大间隔。 */
    public static final long FRESH_MS = 2L * 60L * 1000L;
    /** 岛上活动 id（同一时刻只有一份简报）。 */
    public static final String ISLAND_ID = "brief.v2";
    public static final String ISLAND_TITLE = "晨间简报";

    static final int MAX_TEXT = 60;

    /** 上岛语义。 */
    public enum Phase { GENERATING, PLAYING, READY, PLAYED, FAILED, IGNORE }

    /** 一条解析后的广播。 */
    public static final class Event {
        public final String rawState;
        public final String detail;
        public final Phase phase;
        /** 岛上标题（终态会换成一句结论）。 */
        public final String title;
        /** 运行态的步骤文字 / 失败原因。 */
        public final String text;

        Event(String rawState, String detail, Phase phase, String title, String text) {
            this.rawState = rawState;
            this.detail = detail;
            this.phase = phase;
            this.title = title;
            this.text = text;
        }

        @Override public String toString() {
            return phase + "(" + rawState + (detail.length() > 0 ? "," + detail : "") + ")";
        }
    }

    /** 去控制字符、截断。广播内容来自另一个 App，只当展示文本用。 */
    static String clean(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < MAX_TEXT; i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f || (c >= 0x2028 && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069)) continue;
            sb.append(c);
        }
        return sb.toString().trim();
    }

    /** 解析一条广播；不认识的 state 返回 IGNORE。action 不对返回 null。 */
    public static Event parse(String action, String state, String detail) {
        if (!ACTION.equals(action)) return null;
        String st = clean(state);
        String d = clean(detail);
        if ("GENERATING".equals(st)) return new Event(st, d, Phase.GENERATING, ISLAND_TITLE, "生成中");
        if ("READY".equals(st)) return new Event(st, d, Phase.READY, "简报已生成", null);
        if ("PLAYING".equals(st)) {
            return new Event(st, d, Phase.PLAYING, ISLAND_TITLE, "ringtone".equals(d) ? "播放中 · 闹钟铃声" : "播放中");
        }
        if ("IDLE".equals(st)) {
            if (d.startsWith("error:")) return new Event(st, d, Phase.FAILED, ISLAND_TITLE, "生成失败" + reason(d));
            return new Event(st, d, Phase.IGNORE, null, null);
        }
        if ("DONE".equals(st)) {
            if (d.startsWith("error:") || d.startsWith("invalid:") || "no_audio".equals(d)) {
                return new Event(st, d, Phase.FAILED, ISLAND_TITLE, "播放失败" + reason(d));
            }
            return new Event(st, d, Phase.PLAYED, "简报播完了", null);
        }
        return new Event(st, d, Phase.IGNORE, null, null);
    }

    /** 失败原因的人话（只给认识的几类，其余不展开，避免把内部错误串搬上岛）。 */
    static String reason(String d) {
        if (d.startsWith("invalid:")) return "：不是这次闹钟的稿子";
        if ("no_audio".equals(d)) return "：没有音频";
        if (d.equals("error:crash")) return "：程序出错";
        if (d.startsWith("error:") && d.toLowerCase(java.util.Locale.ROOT).contains("timeout")) return "：超时";
        return "";
    }

    /**
     * 来源校验（纯函数）。
     *
     * @param shellOut    {@link #VERIFY_CMD} 的输出（第一行属主 uid，其余是 state.json）；null = 没跑成
     * @param expectedUid PackageManager 里 com.onesay.clock 的 uid；&lt; 0 = 没装
     * @param ev          广播解析结果
     * @param nowMs       收到广播的时刻
     * @return null = 通过；否则是拒绝原因（写日志用）
     */
    public static String verifySource(String shellOut, int expectedUid, Event ev, long nowMs) {
        if (ev == null) return "action 不符";
        if (expectedUid < 0) return "未安装 " + SENDER_PKG;
        if (expectedUid < 10000) return "uid 异常 " + expectedUid;
        if (shellOut == null) return "读不到状态文件";
        String out = shellOut.trim();
        int nl = out.indexOf('\n');
        if (nl <= 0) return "状态文件输出不完整";
        String uidLine = out.substring(0, nl).trim();
        String body = out.substring(nl + 1).trim();
        int fileUid;
        try {
            fileUid = Integer.parseInt(uidLine);
        } catch (NumberFormatException e) {
            return "属主 uid 读不出";
        }
        if (fileUid != expectedUid) return "状态文件属主 " + fileUid + " ≠ " + SENDER_PKG + " 的 uid " + expectedUid;
        JSONObject o;
        try {
            o = new JSONObject(body);
        } catch (Exception e) {
            return "状态文件不是 JSON";
        }
        String st = clean(o.optString("state", ""));
        String d = clean(o.optString("detail", ""));
        long at = o.optLong("at", 0L);
        if (!st.equals(ev.rawState)) return "状态不符（文件 " + st + " / 广播 " + ev.rawState + "）";
        if (!d.equals(ev.detail)) return "detail 不符";
        if (at <= 0 || Math.abs(nowMs - at) > FRESH_MS) return "状态文件不新鲜（" + (nowMs - at) + " ms）";
        return null;
    }

    // ------------------------------------------------------------------ 上岛计划

    /** 对岛上活动的一步操作（由 {@link BriefIsland} 交给 IslandHost.Channel 执行）。 */
    public enum Op { REMOVE, START, STEP, END_OK, END_FAIL }

    public static final class Step {
        public final Op op;
        public final String text;

        Step(Op op, String text) {
            this.op = op;
            this.text = text;
        }

        @Override public String toString() {
            return text == null ? op.name() : op + ":" + text;
        }
    }

    /** 运行中（岛上是 RUN 态）的两种 phase。 */
    public static boolean isRunning(Phase p) {
        return p == Phase.GENERATING || p == Phase.PLAYING;
    }

    /**
     * 从上一个 phase 走到 ev：返回要执行的操作。
     * <ul>
     *   <li>运行 → 运行：只换步骤文字（不重来、不闪）；</li>
     *   <li>其它 → 运行：重新放一个运行态活动；</li>
     *   <li>终态（已生成 / 播完 / 失败）：换上结论标题，再收尾（完成 3 s 后自动消失；失败展开 6 s、挂 5 分钟，与后台任务一致）；</li>
     *   <li>IGNORE：什么都不做。</li>
     * </ul>
     */
    public static java.util.List<Step> plan(Phase last, Event ev) {
        java.util.List<Step> s = new java.util.ArrayList<Step>();
        if (ev == null || ev.phase == Phase.IGNORE) return s;
        if (isRunning(ev.phase)) {
            if (!isRunning(last)) {
                s.add(new Step(Op.REMOVE, null));
                s.add(new Step(Op.START, ev.title));
            }
            s.add(new Step(Op.STEP, ev.text));
            return s;
        }
        s.add(new Step(Op.REMOVE, null));
        s.add(new Step(Op.START, ev.title));
        if (ev.phase == Phase.FAILED) s.add(new Step(Op.END_FAIL, ev.text));
        else s.add(new Step(Op.END_OK, null));
        return s;
    }
}
