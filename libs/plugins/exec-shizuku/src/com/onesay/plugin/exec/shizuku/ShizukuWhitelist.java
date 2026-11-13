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
 * </ol>
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
            CAT_SCREENCAP = "screencap";

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
        return deny("CMD_ARGS");
    }

    private Verdict settings(List<String> a) {
        if (a.size() == 4 && "get".equals(a.get(1)) && "secure".equals(a.get(2)) && "enabled_notification_listeners".equals(a.get(3))) {
            return allow(CAT_NOTIFICATION, a);
        }
        return deny("SETTINGS_ARGS");
    }

    private Verdict input(List<String> a) {
        if (a.size() < 3 || !"keyevent".equals(a.get(1))) return deny("INPUT_ARGS");
        int i = 2;
        if ("--longpress".equals(a.get(i))) i++;
        if (a.size() != i + 1) return deny("INPUT_ARGS");
        String k = a.get(i);
        if (k.equals("KEYCODE_POWER") || k.equals("26") || k.equals("KEYCODE_SLEEP") || k.equals("223")) return deny("KEY_BLOCKED(" + k + ")");
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

    private static Verdict allow(String cat, List<String> argv) {
        return new Verdict(true, cat, null, new ArrayList<String>(argv));
    }

    private static Verdict deny(String why) {
        return new Verdict(false, null, why, null);
    }
}
