package com.onesay.assistant.v2.ui.console;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ feat/console-ui（设计稿 ①）：首页顶部状态小标签。纯 Java，JVM 可测。
 *
 * <p>只报真实状态：绿 = 开着、黄 = 要处理、红 = 坏了、灰 = 关着 / 查不到。<b>查不到绝不写绿</b>。
 * 只有黄 / 红的标签可以点，点了直接去对应的处理处（{@link Chip#action}）；绿 / 灰的只看不点。
 */
public final class ConsoleChips {

    private ConsoleChips() {}

    /** 点标签去哪。 */
    public enum Action {
        NONE,
        /** 电脑联动页（重新配对 / 看为什么不在线）。 */
        PC_PAGE,
        /** 拉起唤醒服务。 */
        START_WAKE,
        /** 运行状态页。 */
        STATUS_PAGE,
        /** 「权限配置」页。 */
        PERM_PAGE,
        /** 本 App 的应用信息页（给录音权限）。 */
        MIC_SETTINGS,
        /** 语音识别 Key 弹窗。 */
        ASR_KEY,
        /** 系统定位开关页。 */
        LOCATION_SETTINGS
    }

    public static final class Chip {
        public final String label;
        public final ConsoleStatus.Level level;
        public final Action action;

        Chip(String label, ConsoleStatus.Level level, Action action) {
            this.label = label;
            this.level = level;
            this.action = ConsoleChips.clickable(level) ? action : Action.NONE;
        }

        /** 能点吗（只有黄 / 红）。 */
        public boolean clickable() {
            return action != Action.NONE;
        }

        @Override public String toString() {
            return label + "/" + level + "/" + action;
        }
    }

    static boolean clickable(ConsoleStatus.Level l) {
        return l == ConsoleStatus.Level.WARN || l == ConsoleStatus.Level.BAD;
    }

    /** 电脑那一枚的输入（页面在后台线程里攒好）。 */
    public static final class Pc {
        /** {@link PcEntry} 的状态。 */
        public String state;
        public String name;
        /** 这次探测电脑在不在线：null = 还在找 / 没探。 */
        public Boolean online;
    }

    /** 电脑名太长时截短（标签不换行）。 */
    static final int NAME_MAX = 14;

    static String shortName(String n) {
        if (n == null) return "";
        n = n.trim();
        return n.length() <= NAME_MAX ? n : n.substring(0, NAME_MAX - 1) + "…";
    }

    /** 电脑：关着 / 没配对 = 灰；配对信息坏了 = 红；在线 = 绿「已连电脑 X」；不在线 = 黄；还在找 = 灰。联动被停用 = 不显示。 */
    public static Chip pc(Pc p) {
        if (p == null || p.state == null || PcEntry.KILLED.equals(p.state)) return null;
        if (PcEntry.OFF.equals(p.state) || PcEntry.UNPAIRED.equals(p.state)) return new Chip("没连电脑", ConsoleStatus.Level.OFF, Action.NONE);
        if (PcEntry.KEY_LOST.equals(p.state)) return new Chip("电脑要重新配对", ConsoleStatus.Level.BAD, Action.PC_PAGE);
        String n = shortName(p.name);
        if (p.online == null) return new Chip(n.length() > 0 ? "正在找电脑 " + n : "正在找电脑", ConsoleStatus.Level.UNKNOWN, Action.NONE);
        if (p.online) return new Chip(n.length() > 0 ? "已连电脑 " + n : "已连电脑", ConsoleStatus.Level.OK, Action.NONE);
        return new Chip("电脑没在线", ConsoleStatus.Level.WARN, Action.PC_PAGE);
    }

    /** 唤醒服务：暂停 = 灰；没在跑 = 红（点了拉起）；引擎启动中 = 黄；在跑 = 绿「随叫随到」；查不到 = 灰。 */
    public static Chip wake(ConsoleStatus.Snapshot s) {
        if (s == null) return null;
        if (s.paused) return new Chip("已暂停", ConsoleStatus.Level.OFF, Action.NONE);
        if (s.wakeRunning == null) return new Chip(s.done ? "唤醒状态查不到" : "检测中", ConsoleStatus.Level.UNKNOWN, Action.NONE);
        if (!s.wakeRunning) return new Chip("唤醒服务没开", ConsoleStatus.Level.BAD, Action.START_WAKE);
        if (!s.micReady) return new Chip("语音还在启动", ConsoleStatus.Level.WARN, Action.STATUS_PAGE);
        return new Chip("随叫随到", ConsoleStatus.Level.OK, Action.NONE);
    }

    /** 说话用不了的几项（缺一项就整段语音用不了）：各出一枚红的，点了直接去配。 */
    public static List<Chip> voice(ConsoleStatus.Snapshot s) {
        List<Chip> out = new ArrayList<Chip>();
        if (s == null) return out;
        if (Boolean.FALSE.equals(s.micPermission)) out.add(new Chip("没有录音权限", ConsoleStatus.Level.BAD, Action.MIC_SETTINGS));
        if (Boolean.FALSE.equals(s.asrConfigured)) out.add(new Chip("语音识别没配好", ConsoleStatus.Level.BAD, Action.ASR_KEY));
        else if (Boolean.TRUE.equals(s.asrKeyInvalid)) out.add(new Chip("语音识别 Key 失效", ConsoleStatus.Level.BAD, Action.ASR_KEY));
        return out;
    }

    /** 其余必须的权限 / 密钥（录音、语音识别已单独报了，不重复）：有红 = 红「权限没配好」；只有黄 = 黄「有权限要确认」。 */
    public static Chip perm(ConsoleStatus.Snapshot s) {
        if (s == null) return null;
        boolean any = false, bad = false;
        for (PermSetup.Item i : PermSetup.problems(s)) {
            if (PermSetup.MIC.equals(i.id) || PermSetup.ASR_KEY.equals(i.id)) continue;
            any = true;
            if (i.level == ConsoleStatus.Level.BAD) bad = true;
        }
        if (!any) return null;
        return bad ? new Chip("权限没配好", ConsoleStatus.Level.BAD, Action.PERM_PAGE)
                : new Chip("有权限要确认", ConsoleStatus.Level.WARN, Action.PERM_PAGE);
    }

    /** 系统定位总开关：关着 = 黄「定位没开」（点了去系统定位页）；开着或查不到 = 不显示。 */
    public static Chip location(Boolean on) {
        if (!Boolean.FALSE.equals(on)) return null;
        return new Chip("定位没开", ConsoleStatus.Level.WARN, Action.LOCATION_SETTINGS);
    }

    /** 首页一排：电脑 → 唤醒 → 说话 → 权限 → 定位（没有的不占位）。 */
    public static List<Chip> build(ConsoleStatus.Snapshot s, Pc pc, Boolean locationOn) {
        List<Chip> out = new ArrayList<Chip>();
        add(out, pc(pc));
        add(out, wake(s));
        out.addAll(voice(s));
        add(out, perm(s));
        add(out, location(locationOn));
        return out;
    }

    private static void add(List<Chip> l, Chip c) {
        if (c != null) l.add(c);
    }
}
