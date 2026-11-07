package com.onesay.assistant.v2.ui.console;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [console R12] 首页 Hero / 状态胶囊 / 提醒条的判定（纯 Java，JVM 可测）。
 *
 * <p>原则（用户硬性要求）：状态全部来自真实探测；<b>探测不到就是「未知」</b>（灰点），绝不默认写绿。
 * 只有确定出了问题才出提醒条，「未知」不出提醒条。
 */
public final class ConsoleStatus {

    private ConsoleStatus() {}

    public enum Level { OK, WARN, BAD, UNKNOWN, OFF }

    /** 一次探测的结果。Boolean 为 null = 没测到 / 测不了（→ 未知）。 */
    public static final class Snapshot {
        public Boolean wakeRunning;
        public boolean micReady;
        public boolean paused;
        public Boolean rootOk;
        /** 按键守护探针的原始输出（root 跑 {@link #DAEMON_PROBE}）；null = 没跑 / root 不通。 */
        public String daemonProbe;
        public Boolean llmConfigured;
        public String visionModel;
        public Boolean asrConfigured;
        public Boolean overlayOk;
        public boolean islandEnabled = true;
        public Boolean batteryWhitelisted;
        public Boolean voiceprint;
        public Boolean micPermission;
        /** r2/p0 P0-2：当前语音识别 Key 就是上次鉴权失败（401 / 403）的那一个（{@code AsrHealth}）；null = 不知道。 */
        public Boolean asrKeyInvalid;
        /** 探测是否全部回来了（没回来时「未知」显示成「检测中」）。 */
        public boolean done;
        /**
         * 控制台整理第二步：上一次探到的按键守护版本（如「v9」，存 prefs），只用来给「还在探」的胶囊占位 ——
         * 刚打开控制台约 1 秒里胶囊先显示「按键 v9」（灰点 = 还没确认），探完再变绿；避免「按键 未知」→「按键 v9」宽度变化把布局顶动。
         */
        public String lastDaemonVer;
    }

    public static final class Pill {
        public final String label;
        public final Level level;

        Pill(String label, Level level) {
            this.label = label;
            this.level = level;
        }

        @Override public String toString() {
            return label + "/" + level;
        }
    }

    public static final class Banner {
        public static final String ACT_WAKE = "wake", ACT_STATUS = "status", ACT_KEY = "key", ACT_OVERLAY = "overlay",
                ACT_BATTERY = "battery", ACT_VOICEPRINT = "voiceprint",
                /* r2/p0 P0-2 */ ACT_ASR_KEY = "asr_key", ACT_MIC = "mic";
        public final String text;
        public final String action;
        public final String actionId;
        public final Level level;

        Banner(String text, String action, String actionId, Level level) {
            this.text = text;
            this.action = action;
            this.actionId = actionId;
            this.level = level;
        }

        @Override public String toString() {
            return actionId + ":" + text;
        }
    }

    public static final class Hero {
        public final String word;
        public final String headline;
        public final Level level;

        Hero(String word, String headline, Level level) {
            this.word = word;
            this.headline = headline;
            this.level = level;
        }
    }

    // ------------------------------------------------------------------ 按键守护

    /**
     * root 下跑的只读探针：pidfile 指向的进程还活着且 cmdline 含 hijack_daemon → ALIVE；否则扫一遍 /proc 再判；
     * 模块目录在不在；版本取守护脚本头里的「- v8.1」。只读、不改任何东西。
     */
    public static final String DAEMON_PROBE =
            "a=DEAD; p=$(cat /data/local/tmp/.bixby_hijack.pid 2>/dev/null); "
            + "if [ -n \"$p\" ] && grep -qa hijack_daemon /proc/$p/cmdline 2>/dev/null; then a=ALIVE; else "
            + "for d in /proc/[0-9]*; do grep -qa hijack_daemon $d/cmdline 2>/dev/null && { a=ALIVE; break; }; done; fi; echo $a; "
            + "M=/data/adb/modules/bixbykey_hijack; if [ -d $M ]; then echo MOD; else echo NOMOD; fi; "
            + "head -n 6 $M/hijack_daemon.sh 2>/dev/null | grep -m1 'daemon - v' ; echo END";   // toybox head 没有 -c（真机 R12）

    private static final Pattern VER = Pattern.compile("\\bv(\\d+(?:\\.\\d+)?)\\b");

    /** 解析探针输出：[alive(Boolean), module(Boolean), version(String)]。输出不完整（没有 END）→ 全 null。 */
    public static Object[] parseDaemon(String out) {
        Object[] r = new Object[]{null, null, null};
        if (out == null || !out.contains("END")) return r;
        if (out.contains("ALIVE")) r[0] = Boolean.TRUE;
        else if (out.contains("DEAD")) r[0] = Boolean.FALSE;
        if (out.contains("NOMOD")) r[1] = Boolean.FALSE;
        else if (out.contains("MOD")) r[1] = Boolean.TRUE;
        for (String line : out.split("\n")) {
            if (!line.contains("daemon - v")) continue;
            Matcher m = VER.matcher(line.substring(line.indexOf("daemon - v") + 9));
            if (m.find()) r[2] = "v" + m.group(1);
            break;
        }
        return r;
    }

    // ------------------------------------------------------------------ 胶囊

    /**
     * 控制台整理第二步：探测还没回来（!done）时的占位胶囊 —— 灰点（UNKNOWN，绝不写绿）+ 和「正常」时同样短的文字，
     * 首帧和探完的胶囊一样宽，FlowLayout 不会从两行缩成一行把下面的内容往上拽。探完仍拿不到才显示「X 未知」。
     */
    static Pill pending(String name, String suffix) {
        return new Pill(suffix == null || suffix.length() == 0 ? name : name + " " + suffix, Level.UNKNOWN);
    }

    public static Pill wake(Snapshot s) {
        if (s.paused) return new Pill("唤醒 已暂停", Level.OFF);
        if (s.wakeRunning == null) return s.done ? new Pill("唤醒 未知", Level.UNKNOWN) : pending("唤醒", null);
        if (!s.wakeRunning) return new Pill("唤醒 未运行", Level.BAD);
        if (!s.micReady) return new Pill("唤醒 启动中", Level.WARN);
        return new Pill("唤醒", Level.OK);
    }

    public static Pill daemon(Snapshot s) {
        boolean waiting = !s.done && !Boolean.FALSE.equals(s.rootOk);   // root 还没回来，或 root 好了守护探针还没回来
        if (s.rootOk == null || !s.rootOk || s.daemonProbe == null) return waiting ? pending("按键", s.lastDaemonVer) : new Pill("按键 未知", Level.UNKNOWN);
        Object[] d = parseDaemon(s.daemonProbe);
        Boolean alive = (Boolean) d[0];
        if (alive == null) return new Pill("按键 未知", Level.UNKNOWN);
        if (!alive) return new Pill(Boolean.FALSE.equals(d[1]) ? "按键 未安装" : "按键 未运行", Level.BAD);
        return new Pill(d[2] != null ? "按键 " + d[2] : "按键", Level.OK);
    }

    public static Pill root(Snapshot s) {
        if (s.rootOk == null) return s.done ? new Pill("Root 未知", Level.UNKNOWN) : pending("Root", null);
        return s.rootOk ? new Pill("Root", Level.OK) : new Pill("Root 未就绪", Level.BAD);
    }

    public static Pill llm(Snapshot s) {
        if (s.llmConfigured == null) return s.done ? new Pill("DeepSeek 未知", Level.UNKNOWN) : pending("DeepSeek", null);
        return s.llmConfigured ? new Pill("DeepSeek", Level.OK) : new Pill("DeepSeek 未配置", Level.WARN);
    }

    /** 探到的守护版本（「v9」），探不到 / 没在运行返回 null —— 存下来给下次首帧占位用。 */
    public static String daemonVersion(Snapshot s) {
        if (s == null || !Boolean.TRUE.equals(s.rootOk) || s.daemonProbe == null) return null;
        Object[] d = parseDaemon(s.daemonProbe);
        return Boolean.TRUE.equals(d[0]) ? (String) d[2] : null;
    }

    public static List<Pill> pills(Snapshot s) {
        List<Pill> out = new ArrayList<Pill>();
        out.add(wake(s));
        out.add(daemon(s));
        out.add(root(s));
        out.add(llm(s));
        return out;
    }

    // ------------------------------------------------------------------ 提醒条（最多 2 条，按优先级）

    public static final int MAX_BANNERS = 2;

    public static List<Banner> allProblems(Snapshot s) {
        List<Banner> out = new ArrayList<Banner>();
        if (Boolean.FALSE.equals(s.wakeRunning)) out.add(new Banner("唤醒服务没在运行，按键和唤醒词都没反应", "启动", Banner.ACT_WAKE, Level.BAD));
        if (Boolean.FALSE.equals(s.rootOk)) out.add(new Banner("Root 通道未就绪，系统设置类操作用不了", "详情", Banner.ACT_STATUS, Level.BAD));
        if (daemon(s).level == Level.BAD) out.add(new Banner("按键守护没在运行，Bixby 键可能没反应", "详情", Banner.ACT_STATUS, Level.BAD));
        // r2/p0 P0-2（M27-1）：语音识别 Key / 录音权限 —— 缺任一项，说话就完全用不了
        if (Boolean.FALSE.equals(s.micPermission)) out.add(new Banner("没有录音权限，听不到你说话", "去授权", Banner.ACT_MIC, Level.BAD));
        if (Boolean.FALSE.equals(s.asrConfigured)) {
            out.add(new Banner("语音识别未配置，说话听不懂（只能打字）", "去配置", Banner.ACT_ASR_KEY, Level.BAD));
        } else if (Boolean.TRUE.equals(s.asrKeyInvalid)) {
            out.add(new Banner("语音识别 Key 失效（鉴权失败），说话听不懂", "去配置", Banner.ACT_ASR_KEY, Level.BAD));
        }
        if (Boolean.FALSE.equals(s.llmConfigured)) out.add(new Banner("还没配 DeepSeek Key，复杂问题答不了", "去配置", Banner.ACT_KEY, Level.WARN));
        if (s.islandEnabled && Boolean.FALSE.equals(s.overlayOk)) out.add(new Banner("灵动岛缺悬浮窗权限，显示不了", "去授权", Banner.ACT_OVERLAY, Level.WARN));
        if (Boolean.FALSE.equals(s.batteryWhitelisted)) out.add(new Banner("不在电池白名单，熄屏久了定时任务会晚", "加入", Banner.ACT_BATTERY, Level.WARN));
        if (Boolean.FALSE.equals(s.voiceprint)) out.add(new Banner("还没录声纹，别人说唤醒词也会唤醒", "去录制", Banner.ACT_VOICEPRINT, Level.WARN));
        return out;
    }

    public static List<Banner> banners(Snapshot s) {
        List<Banner> all = allProblems(s);
        return all.size() > MAX_BANNERS ? new ArrayList<Banner>(all.subList(0, MAX_BANNERS)) : all;
    }

    // ------------------------------------------------------------------ Hero

    public static Hero hero(Snapshot s) {
        if (s.paused) return new Hero("已暂停", "唤醒已暂停", Level.OFF);
        int n = allProblems(s).size();
        boolean critical = wake(s).level == Level.BAD || root(s).level == Level.BAD || daemon(s).level == Level.BAD;
        if (critical) return new Hero("需要处理", "有 " + n + " 项要处理", Level.BAD);
        // r2/p0 P0-2：语音用不了时不能说「随时待命」—— 缺哪个就写哪个
        String voice = voiceBlockers(s);
        if (voice != null) return new Hero("需要处理", voice, Level.BAD);
        if (s.wakeRunning == null || s.rootOk == null) {
            return s.done ? new Hero("状态未知", "有些状态查不到", Level.UNKNOWN) : new Hero("检测中", "正在检查…", Level.UNKNOWN);
        }
        if (wake(s).level == Level.WARN) return new Hero("启动中", "语音引擎启动中", Level.WARN);
        return new Hero("运行中", "随时待命", Level.OK);
    }

    /** r2/p0 P0-2：让语音完全用不了的几项（录音权限 / 语音识别未配置 / Key 失效），用「 · 」连起来；都没问题（或不知道）返回 null。 */
    public static String voiceBlockers(Snapshot s) {
        List<String> out = new ArrayList<String>();
        if (Boolean.FALSE.equals(s.micPermission)) out.add("没有录音权限");
        if (Boolean.FALSE.equals(s.asrConfigured)) out.add("语音识别未配置");
        else if (Boolean.TRUE.equals(s.asrKeyInvalid)) out.add("语音识别 Key 失效");
        if (out.isEmpty()) return null;
        StringBuilder b = new StringBuilder();
        for (String x : out) {
            if (b.length() > 0) b.append(" · ");
            b.append(x);
        }
        return b.toString();
    }

    /** Hero 副标题：按 Bixby 键说话 · 长按 0.8 秒记一记（记一记关着就不提长按）。 */
    public static String heroHint(boolean paused, boolean quickNoteOn) {
        if (paused) return "灵动岛、定时任务照常 · 打开右上开关恢复";
        return quickNoteOn ? "按 Bixby 键说话 · 长按 0.8 秒记一记" : "按 Bixby 键或说「hey bixby」";
    }
}
