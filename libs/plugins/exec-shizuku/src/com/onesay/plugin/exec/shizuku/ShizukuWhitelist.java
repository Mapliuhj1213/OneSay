package com.onesay.plugin.exec.shizuku;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「Shizuku 特权」插件的命令白名单（纯 Java 7，无 Android 依赖，JVM 可测）。
 *
 * <p>Shizuku 以 shell（uid 2000）或 root 身份替 App 执行命令，权限很大，所以只放行下面几类、参数逐个校验的命令；
 * 其余一律拒绝（不走 shell 解释：命令按空白拆成 argv 直接 exec，并拒绝任何 shell 元字符）。
 * <ol>
 *   <li><b>后台读剪贴板</b>（电脑联动同步剪贴板）：{@code appops set <本包> READ_CLIPBOARD allow|default}、
 *       {@code appops get <本包> READ_CLIPBOARD}；</li>
 *   <li><b>通知使用权</b>（新版 Android 侧载应用的「受限设置」）：{@code appops set <本包> ACCESS_RESTRICTED_SETTINGS allow}、
 *       {@code cmd notification allow_listener <本包>/<类>}、{@code cmd notification disallow_listener <本包>/<类>}、
 *       {@code settings get secure enabled_notification_listeners}（只读）；</li>
 *   <li><b>模拟按键</b>：{@code input keyevent [--longpress] <KEYCODE_名|数字>}（电源键除外，避免误锁屏 / 关机菜单）；</li>
 *   <li><b>截屏</b>：{@code screencap -p <路径>.png}，路径只能是 {@code /data/local/tmp/onesay*.png} 或本包外部私有目录 cache/ 下。</li>
 *   <li><b>常用开关（fix-1118，逐个 argv 精确匹配）</b>：只读 {@code dumpsys battery}、{@code settings get system screen_brightness[_mode]}、
 *       {@code settings get global bluetooth_on|wifi_on}、{@code cmd wifi status}；
 *       写 {@code cmd bluetooth_manager enable|disable}、{@code svc wifi enable}、{@code settings put system screen_brightness <0-255>}。
 *       关 Wi-Fi（{@code svc wifi disable}）不放：无线调试 / 电脑联动会跟着断。</li>
 *   <li><b>系统版本（meizu-1118）</b>：只读 {@code getprop ro.build.version.<键>}（恰好一个键）。</li>
 * </ol>
 * <p><b>读屏助手（a11y-1118，只走 {@link #checkHelperArgv}，不经 {@link #check} 的字符串入口）</b>：只放<b>一条</b>精确 argv
 * {@code /system/bin/app_process -Djava.class.path=<本包 base.apk> /system/bin --nice-name=onesay_fs:<本包> <助手主类> <本包> <本包 uid>}，
 * base.apk 路径与 uid 由宿主从 PackageManager 注入、助手主类由宿主固定给出（{@link SelfInfo}），与请求的 argv 逐项比较，多一项 / 少一项 / 任何一项不同都拒。
 * 字符串入口照旧不认 {@code bixby-fastscreen-start} 这类守护模板名。
 * 键码：电源 / 睡眠键，以及接打电话（CALL 5 / ENDCALL 6 / HEADSETHOOK 79）、相机（CAMERA 27）、语音助手（VOICE_ASSIST 231）一律不放
 * （sec-audit-1118 M4：这些键全局生效，能挂断 / 接起电话、拉起相机或别家助手）。
 */
public final class ShizukuWhitelist {

    /** 判定结果。{@link #argv} 只在放行时非空。 */
    public static final class Verdict {
        public final boolean allowed;
        public final String category;
        public final String reason;
        public final List<String> argv;

        Verdict(boolean allowed, String category, String reason, List<String> argv) {
            this.allowed = allowed;
            this.category = category;
            this.reason = reason;
            this.argv = argv == null ? Collections.<String>emptyList() : Collections.unmodifiableList(argv);
        }

        @Override public String toString() {
            return (allowed ? "ALLOW " + category : "DENY " + reason);
        }
    }

    public static final String CAT_CLIPBOARD = "clipboard", CAT_NOTIFICATION = "notification", CAT_KEYEVENT = "keyevent",
            CAT_SCREENCAP = "screencap", CAT_READ = "read", CAT_TOGGLE = "toggle", CAT_FASTSCREEN = "fastscreen";

    /** 读屏助手：启动器、主类、进程名前缀（a11y-1118）。 */
    public static final String APP_PROCESS = "/system/bin/app_process";
    public static final String HELPER_NICE_PREFIX = "onesay_fs:";

    /** 宿主注入的本包信息（Android 侧从 PackageManager 取；单测注入假值）。 */
    public interface SelfInfo {
        /** 本包 base.apk 的真实路径（ApplicationInfo.sourceDir）。 */
        String baseApk();

        /** 本包 uid（ApplicationInfo.uid）。 */
        int uid();

        /** 读屏助手主类（宿主里的固定常量；插件核心不写死宿主的类名）。 */
        String helperMain();
    }

    private volatile SelfInfo mSelf;

    private static final String META = ";|&`$()<>\\\"'\n\r*?{}[]!~#";
    private static final String TMP_DIR = "/data/local/tmp/";

    private final String mPkg;

    /** @param ownPackage 本 App 的包名（appops / 通知使用权只允许改自己）。 */
    public ShizukuWhitelist(String ownPackage) {
        if (ownPackage == null || !ownPackage.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")) {
            throw new IllegalArgumentException("package");
        }
        mPkg = ownPackage;
    }

    public String ownPackage() {
        return mPkg;
    }

    public void setSelfInfo(SelfInfo self) {
        mSelf = self;
    }

    /** 读屏助手的精确 argv（宿主拼请求、白名单拼期望，都用这一个函数）。 */
    public static List<String> helperArgv(String pkg, String baseApk, int uid, String helperMain) {
        List<String> a = new ArrayList<String>();
        a.add(APP_PROCESS);
        a.add("-Djava.class.path=" + baseApk);
        a.add("/system/bin");
        a.add("--nice-name=" + HELPER_NICE_PREFIX + pkg);
        a.add(helperMain);
        a.add(pkg);
        a.add(String.valueOf(uid));
        return a;
    }

    /**
     * base.apk 路径是否像 PackageManager 给本包的安装路径：{@code /data/app/[~~<随机>/]<本包>-<随机>/base.apk}，
     * 只许字母数字和 {@code . _ - = ~ +}，不许 {@code ..}、空段、空白。
     */
    static boolean apkPathOk(String apk, String pkg) {
        if (apk == null || apk.length() > 300 || !apk.startsWith("/data/app/") || !apk.endsWith("/base.apk")) return false;
        if (!apk.matches("[A-Za-z0-9._=~+/\\-]+") || apk.contains("//") || apk.contains("/../") || apk.contains("/./")) return false;
        String[] seg = apk.substring("/data/app/".length()).split("/");
        if (seg.length < 2 || seg.length > 3) return false;
        String dir = seg[seg.length - 2];
        return dir.startsWith(pkg + "-") && dir.length() > pkg.length() + 1;
    }

    /**
     * 读屏助手 argv 判定：与 {@link #helperArgv}(本包, 注入的 base.apk, 注入的 uid, 注入的主类) 逐项相等才放行。
     */
    public Verdict checkHelperArgv(List<String> argv) {
        SelfInfo s = mSelf;
        if (s == null) return deny("HELPER_NO_SELFINFO");
        String apk, main;
        int uid;
        try {
            apk = s.baseApk();
            uid = s.uid();
            main = s.helperMain();
        } catch (Throwable t) {
            return deny("HELPER_NO_SELFINFO");
        }
        if (main == null || !main.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")) return deny("HELPER_MAIN");
        if (!apkPathOk(apk, mPkg)) return deny("HELPER_APK_PATH");
        if (uid < 10000 || uid % 100000 < 10000 || uid % 100000 > 19999) return deny("HELPER_UID");   // 普通应用 uid（多用户：userId*100000 + appId，appId 10000–19999）
        List<String> want = helperArgv(mPkg, apk, uid, main);
        if (argv == null || argv.size() != want.size()) return deny("HELPER_ARGV_LEN");
        for (int i = 0; i < want.size(); i++) {
            String got = argv.get(i);
            if (got == null || !want.get(i).equals(got)) return deny("HELPER_ARGV(" + i + ")");
        }
        return allow(CAT_FASTSCREEN, want);
    }

    public Verdict check(String command) {
        if (command == null) return deny("EMPTY");
        String c = command.trim();
        if (c.length() == 0) return deny("EMPTY");
        if (c.length() > 512) return deny("TOO_LONG");
        for (int i = 0; i < c.length(); i++) {
            if (META.indexOf(c.charAt(i)) >= 0) return deny("SHELL_META(" + c.charAt(i) + ")");
        }
        List<String> a = new ArrayList<String>();
        for (String t : c.split("\\s+")) if (t.length() > 0) a.add(t);
        String p = a.get(0);
        if ("appops".equals(p)) return appops(a);
        if ("cmd".equals(p)) return cmd(a);
        if ("settings".equals(p)) return settings(a);
        if ("input".equals(p)) return input(a);
        if ("screencap".equals(p)) return screencap(a);
        if ("dumpsys".equals(p)) return dumpsys(a);
        if ("svc".equals(p)) return svc(a);
        if ("getprop".equals(p)) return getprop(a);
        return deny("PROG_NOT_WHITELISTED(" + p + ")");
    }

    private Verdict appops(List<String> a) {
        // appops set <pkg> <OP> <mode> | appops get <pkg> <OP>
        if (a.size() == 5 && "set".equals(a.get(1)) && mPkg.equals(a.get(2))) {
            String op = a.get(3), mode = a.get(4);
            if ("READ_CLIPBOARD".equals(op) && ("allow".equals(mode) || "default".equals(mode))) return allow(CAT_CLIPBOARD, a);
            if ("ACCESS_RESTRICTED_SETTINGS".equals(op) && ("allow".equals(mode) || "default".equals(mode))) return allow(CAT_NOTIFICATION, a);
            return deny("APPOPS_OP(" + op + " " + mode + ")");
        }
        if (a.size() == 4 && "get".equals(a.get(1)) && mPkg.equals(a.get(2))) {
            if ("READ_CLIPBOARD".equals(a.get(3))) return allow(CAT_CLIPBOARD, a);
            if ("ACCESS_RESTRICTED_SETTINGS".equals(a.get(3))) return allow(CAT_NOTIFICATION, a);
        }
        return deny("APPOPS_ARGS");
    }

    private Verdict cmd(List<String> a) {
        if (a.size() == 4 && "notification".equals(a.get(1))
                && ("allow_listener".equals(a.get(2)) || "disallow_listener".equals(a.get(2)))) {
            String comp = a.get(3);
            int slash = comp.indexOf('/');
            if (slash > 0 && mPkg.equals(comp.substring(0, slash)) && comp.substring(slash + 1).matches("\\.?[A-Za-z0-9_.$]+")) {
                return allow(CAT_NOTIFICATION, a);
            }
            return deny("LISTENER_NOT_OWN(" + comp + ")");
        }
        // fix-1118：蓝牙开关、Wi-Fi 状态（argv 精确匹配）
        if (a.size() == 3 && "bluetooth_manager".equals(a.get(1)) && ("enable".equals(a.get(2)) || "disable".equals(a.get(2)))) {
            return allow(CAT_TOGGLE, a);
        }
        if (a.size() == 3 && "wifi".equals(a.get(1)) && "status".equals(a.get(2))) return allow(CAT_READ, a);
        return deny("CMD_ARGS");
    }

    private Verdict settings(List<String> a) {
        if (a.size() == 4 && "get".equals(a.get(1)) && "secure".equals(a.get(2)) && "enabled_notification_listeners".equals(a.get(3))) {
            return allow(CAT_NOTIFICATION, a);
        }
        // fix-1118：亮度 / 蓝牙 / Wi-Fi 状态只读；亮度写只收 0–65535 的十进制整数（★ fix-1118 c32）
        if (a.size() == 4 && "get".equals(a.get(1))) {
            String ns = a.get(2), key = a.get(3);
            if ("system".equals(ns) && ("screen_brightness".equals(key) || "screen_brightness_mode".equals(key))) return allow(CAT_READ, a);
            if ("global".equals(ns) && ("bluetooth_on".equals(key) || "wifi_on".equals(key))) return allow(CAT_READ, a);
        }
        if (a.size() == 5 && "put".equals(a.get(1)) && "system".equals(a.get(2)) && "screen_brightness".equals(a.get(3))) {
            String v = a.get(4);
            // ★ fix-1118 c32：部分厂商亮度刻度远大于 255（真机 Meizu 读到 5968）—— 放到 0–65535 的十进制整数
            if (v.matches("[0-9]{1,5}")) {
                int n = Integer.parseInt(v);
                if (n <= 65535) {
                    a = new ArrayList<String>(a);
                    a.set(4, String.valueOf(n));
                    return allow(CAT_TOGGLE, a);
                }
            }
            return deny("BRIGHTNESS_VALUE(" + v + ")");
        }
        return deny("SETTINGS_ARGS");
    }

    private Verdict input(List<String> a) {
        if (a.size() < 3 || !"keyevent".equals(a.get(1))) return deny("INPUT_ARGS");
        int i = 2;
        if ("--longpress".equals(a.get(i))) i++;
        if (a.size() != i + 1) return deny("INPUT_ARGS");
        String k = a.get(i);
        // 2026-11-14 对抗测试 P2-7：Android 按整数解析键码，026 就是 26（电源键）。先去掉前导零再按原来的名单判断，执行的也是规范化后的写法。
        if (k.matches("[0-9]{1,3}")) {
            k = String.valueOf(Integer.parseInt(k));
            a = new ArrayList<String>(a);
            a.set(i, k);
        }
        // 电源 / 睡眠 / 软睡眠（锁屏）键不放：KEYCODE_SOFT_SLEEP = 276
        if (k.equals("KEYCODE_POWER") || k.equals("26") || k.equals("KEYCODE_SLEEP") || k.equals("223") || k.equals("KEYCODE_SOFT_SLEEP") || k.equals("276"))
            return deny("KEY_BLOCKED(" + k + ")");
        // sec-audit-1118 M4：接 / 挂电话、耳机键、相机、语音助手键全局生效，不放
        if (k.equals("KEYCODE_CALL") || k.equals("5") || k.equals("KEYCODE_ENDCALL") || k.equals("6") || k.equals("KEYCODE_HEADSETHOOK") || k.equals("79")
                || k.equals("KEYCODE_CAMERA") || k.equals("27") || k.equals("KEYCODE_VOICE_ASSIST") || k.equals("231"))
            return deny("KEY_BLOCKED(" + k + ")");
        if (k.matches("KEYCODE_[A-Z0-9_]{1,40}") || k.matches("[0-9]{1,3}")) return allow(CAT_KEYEVENT, a);
        return deny("KEY_ARGS(" + k + ")");
    }

    private Verdict screencap(List<String> a) {
        if (a.size() != 3 || !"-p".equals(a.get(1))) return deny("SCREENCAP_ARGS");
        String path = a.get(2);
        if (path.contains("/../") || path.contains("/./") || path.endsWith("/..") || !path.endsWith(".png")) return deny("SCREENCAP_PATH(" + path + ")");
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (!name.matches("[A-Za-z0-9_.\\-]{1,64}")) return deny("SCREENCAP_PATH(" + path + ")");
        String dir = path.substring(0, path.lastIndexOf('/') + 1);
        if ((dir.equals(TMP_DIR) && name.startsWith("onesay"))
                || dir.equals("/sdcard/Android/data/" + mPkg + "/cache/")
                || dir.equals("/storage/emulated/0/Android/data/" + mPkg + "/cache/")) {
            return allow(CAT_SCREENCAP, a);
        }
        return deny("SCREENCAP_PATH(" + path + ")");
    }

    /** fix-1118：只放 {@code dumpsys battery}（电量 / 充电状态），不带任何别的参数。 */
    private Verdict dumpsys(List<String> a) {
        if (a.size() == 2 && "battery".equals(a.get(1))) return allow(CAT_READ, a);
        return deny("DUMPSYS_ARGS");
    }

    /** fix-1118：只放 {@code svc wifi enable}。关 Wi-Fi 不放（无线调试 / 电脑联动会断）。 */
    private Verdict svc(List<String> a) {
        if (a.size() == 3 && "wifi".equals(a.get(1)) && "enable".equals(a.get(2))) return allow(CAT_TOGGLE, a);
        return deny("SVC_ARGS");
    }

    /**
     * ★ meizu-1118：只读系统版本号 {@code getprop ro.build.version.<键>}（真机「看看安卓版本是多少」agent 读 release / sdk 被拒）。
     * 只放这一个前缀、恰好一个键、键名只许小写字母数字和下划线；不带键（全量 dump）、别的属性一律拒。
     */
    private Verdict getprop(List<String> a) {
        if (a.size() == 2 && a.get(1).matches("ro\\.build\\.version\\.[a-z0-9_]{1,40}")) return allow(CAT_READ, a);
        return deny("GETPROP_ARGS");
    }

    private static Verdict allow(String cat, List<String> argv) {
        return new Verdict(true, cat, null, new ArrayList<String>(argv));
    }

    private static Verdict deny(String why) {
        return new Verdict(false, null, why, null);
    }
}
