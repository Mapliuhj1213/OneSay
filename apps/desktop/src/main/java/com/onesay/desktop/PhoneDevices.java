package com.onesay.desktop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * phone-mirror M1：插着的手机的状态机（纯 Java，可单测；线程安全靠 synchronized）。
 *
 * <pre>
 *   (没插) ──插上──▶ 待授权 unauthorized ──手机上点允许──▶ 可投屏 available ──点「投屏」──▶ 投屏中 mirroring
 *                 └▶ 连接中 connecting（offline / authorizing / connecting）     ◀──窗口关了 / 出错──┘
 *                 └▶ 没权限 no_permission（Linux udev）、其他模式 other（recovery / bootloader / sideload）
 *   任何状态 ──拔掉──▶ (没插)，投屏和 App 窗口都算结束
 * </pre>
 *
 * <b>不会自动投屏</b>（用户 11-18 定）：手机授权好只变成「可投屏」，要用户点「投屏」才开窗口。
 */
public final class PhoneDevices {

    public enum State {
        UNAUTHORIZED("待授权"), CONNECTING("连接中"), NO_PERMISSION("没权限"), AVAILABLE("可投屏"), MIRRORING("投屏中"), OTHER("其他模式");

        public final String zh;

        State(String zh) { this.zh = zh; }

        public String id() { return name().toLowerCase(Locale.ROOT); }
    }

    /** 单 App 窗口上限（厂商方案 2–5 个；每个窗口一路编码 + 一块虚拟屏）。 */
    public static final int MAX_APP_WINDOWS = 3;
    /** 单 App 窗口要的最低 SDK（安卓 10 起有可用的副屏输入；13 起 shell 可建可信虚拟屏）。 */
    public static final int MIN_SDK_APP_WINDOW = 29;

    /** 能力探测结果（第一次变成可投屏时读一次 getprop）。 */
    public static final class Info {
        public final String brand, model, release, display;
        public final int sdk;

        public Info(String brand, String model, int sdk, String release, String display) {
            this.brand = brand == null ? "" : brand;
            this.model = model == null ? "" : model;
            this.sdk = sdk;
            this.release = release == null ? "" : release;
            this.display = display == null ? "" : display;
        }

        public static Info of(Map<String, String> p) {
            int sdk;
            try { sdk = Integer.parseInt(p.getOrDefault("ro.build.version.sdk", "0").strip()); } catch (NumberFormatException e) { sdk = 0; }
            return new Info(p.get("ro.product.brand"), p.get("ro.product.model"), sdk, p.get("ro.build.version.release"), p.get("ro.build.display.id"));
        }

        /** 魅族 / Flyme：品牌是 meizu，或版本号里有 Flyme。 */
        public boolean flyme() {
            return brand.toLowerCase(Locale.ROOT).contains("meizu") || display.toLowerCase(Locale.ROOT).contains("flyme");
        }

        /** 音频转发要安卓 11+（scrcpy）；12+ 开箱即用。 */
        public boolean audio() { return sdk >= 30; }

        public boolean appWindows() { return sdk >= MIN_SDK_APP_WINDOW; }
    }

    public static final class Phone {
        public final String serial;
        String adbState = "";
        String model = "", transport = "usb";
        Info info;
        boolean mirroring;
        final Set<String> apps = new LinkedHashSet<>();
        String note = "";
        long since;

        Phone(String serial) { this.serial = serial; }

        public State state() {
            switch (adbState) {
                case "device": return mirroring ? State.MIRRORING : State.AVAILABLE;
                case "unauthorized": return State.UNAUTHORIZED;
                case "offline": case "authorizing": case "connecting": return State.CONNECTING;
                case "no permissions": return State.NO_PERMISSION;
                default: return State.OTHER;
            }
        }

        public Info info() { return info; }

        public String name() {
            if (info != null && !info.model.isEmpty()) return info.model;
            return model.isEmpty() ? serial : model;
        }
    }

    /** 状态变化（写日志 / 弹通知用；不触发投屏）。 */
    public static final class Event {
        public final String serial, kind, zh;

        Event(String serial, String kind, String zh) {
            this.serial = serial;
            this.kind = kind;
            this.zh = zh;
        }

        @Override public String toString() { return kind + ":" + serial; }
    }

    private final Map<String, Phone> phones = new LinkedHashMap<>();

    /** 用一次 `adb devices -l` 的结果更新。返回事件：added / needs_auth / authorized / changed / removed。 */
    public synchronized List<Event> update(List<Adb.Device> snap, long now) {
        List<Event> ev = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Adb.Device d : snap) {
            if (!seen.add(d.serial)) continue;
            Phone p = phones.get(d.serial);
            boolean fresh = p == null;
            if (fresh) {
                p = new Phone(d.serial);
                p.since = now;
                phones.put(d.serial, p);
            }
            State before = fresh ? null : p.state();
            p.adbState = d.state;
            if (!d.model().isEmpty()) p.model = d.model();
            p.transport = d.transport();
            State after = p.state();
            if (!"device".equals(d.state)) { p.mirroring = false; p.apps.clear(); }
            if (fresh) ev.add(new Event(d.serial, "added", "发现手机 " + p.name() + "（" + after.zh + "）"));
            if (after == State.UNAUTHORIZED && before != State.UNAUTHORIZED) ev.add(new Event(d.serial, "needs_auth", "在手机上允许 USB 调试"));
            if (after == State.AVAILABLE && (before == State.UNAUTHORIZED || before == State.CONNECTING)) ev.add(new Event(d.serial, "authorized", p.name() + " 已授权，可以投屏"));
            else if (!fresh && before != after && !(before == State.MIRRORING && after == State.AVAILABLE)) ev.add(new Event(d.serial, "changed", p.name() + "：" + after.zh));
        }
        for (String s : new ArrayList<>(phones.keySet())) {
            if (seen.contains(s)) continue;
            Phone p = phones.remove(s);
            ev.add(new Event(s, "removed", p.name() + " 已拔掉"));
        }
        return ev;
    }

    /** 需要读一次 getprop 的（可投屏但还没探测过）。 */
    public synchronized List<String> needProbe() {
        List<String> l = new ArrayList<>();
        for (Phone p : phones.values()) if ("device".equals(p.adbState) && p.info == null) l.add(p.serial);
        return l;
    }

    public synchronized void setInfo(String serial, Info info) {
        Phone p = phones.get(serial);
        if (p != null) p.info = info;
    }

    public synchronized Phone get(String serial) { return phones.get(serial); }

    public synchronized int size() { return phones.size(); }

    /** 能不能开主屏投屏：null = 可以，否则原因。 */
    public synchronized String canMirror(String serial) {
        Phone p = phones.get(serial);
        if (p == null) return "这台手机已经拔掉了";
        switch (p.state()) {
            case AVAILABLE: return null;
            case MIRRORING: return "已经在投屏了";
            case UNAUTHORIZED: return "先在手机上点「允许 USB 调试」";
            case CONNECTING: return "手机还在连接，等几秒";
            case NO_PERMISSION: return "电脑没权限访问这台手机（Linux 要配 udev 规则）";
            default: return "手机不在正常开机状态";
        }
    }

    /** 能不能再开一个 App 窗口：null = 可以。 */
    public synchronized String canOpenApp(String serial, String pkg) {
        Phone p = phones.get(serial);
        if (p == null) return "这台手机已经拔掉了";
        if (!"device".equals(p.adbState)) return canMirror(serial);
        if (!Adb.validPackage(pkg)) return "包名不对";
        if (p.info != null && !p.info.appWindows()) return "此手机系统版本暂不支持手机桌面（这台是安卓 " + p.info.release + "，需要安卓 10 及以上）";
        if (p.apps.contains(pkg)) return "这个 App 已经开着窗口了（一个 App 只能在一个窗口里）";
        if (p.apps.size() >= MAX_APP_WINDOWS) return "最多同时开 " + MAX_APP_WINDOWS + " 个 App 窗口，先关掉一个";
        return null;
    }

    public synchronized void mirrorStarted(String serial) {
        Phone p = phones.get(serial);
        if (p != null) { p.mirroring = true; p.note = ""; }
    }

    /** 主屏投屏窗口结束；note = 出错原因（null / 空 = 正常关掉）。 */
    public synchronized void mirrorStopped(String serial, String note) {
        Phone p = phones.get(serial);
        if (p != null) { p.mirroring = false; p.note = note == null ? "" : note; }
    }

    public synchronized boolean appOpened(String serial, String pkg) {
        Phone p = phones.get(serial);
        return p != null && p.apps.add(pkg);
    }

    public synchronized void appClosed(String serial, String pkg, String note) {
        Phone p = phones.get(serial);
        if (p != null) { p.apps.remove(pkg); if (note != null && !note.isEmpty()) p.note = note; }
    }

    public synchronized void note(String serial, String note) {
        Phone p = phones.get(serial);
        if (p != null) p.note = note == null ? "" : note;
    }

    /** 界面用：每台手机一行。 */
    public synchronized List<Map<String, Object>> view() {
        List<Map<String, Object>> l = new ArrayList<>();
        for (Phone p : phones.values()) {
            Map<String, Object> m = new TreeMap<>();
            State s = p.state();
            m.put("serial", p.serial);
            m.put("name", p.name());
            m.put("state", s.id());
            m.put("state_zh", s.zh);
            m.put("transport", p.transport);
            m.put("transport_zh", "usb".equals(p.transport) ? "USB" : "tcp".equals(p.transport) ? "无线" : "模拟器");
            m.put("apps", new ArrayList<>(p.apps));
            m.put("note", p.note);
            m.put("can_mirror", s == State.AVAILABLE);
            if (p.info != null) {
                m.put("brand", p.info.brand);
                m.put("android", p.info.release);
                m.put("sdk", (long) p.info.sdk);
                m.put("flyme", p.info.flyme());
                m.put("audio", p.info.audio());
                m.put("app_windows", p.info.appWindows());
            }
            l.add(m);
        }
        return l;
    }
}
