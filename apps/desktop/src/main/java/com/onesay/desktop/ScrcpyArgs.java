package com.onesay.desktop;

import java.util.ArrayList;
import java.util.List;

/**
 * phone-mirror M1：拼 scrcpy 的命令行（固定白名单参数，纯 Java，可单测）。序列号 / 包名先校验，标题去掉控制字符和引号。
 * 剪贴板双向同步是 scrcpy 自带的（默认开），这里不用加参数。
 */
public final class ScrcpyArgs {

    private ScrcpyArgs() {}

    /** 单 App 窗口的初始虚拟屏：竖屏 720x1280 / 240dpi（≈ 480dp 宽，手机布局），配合 --flex-display 随窗口大小变。 */
    public static final String APP_DISPLAY = "720x1280/240";

    /** 主屏投屏。uhid = 用「物理键盘」模式（中文走手机输入法）；audio = 转发声音（安卓 11+，只给主窗口）。 */
    public static List<String> mirror(String exe, String serial, String name, boolean uhid, boolean audio) {
        if (!Adb.validSerial(serial)) throw new IllegalArgumentException("序列号不对");
        List<String> a = new ArrayList<>();
        a.add(exe);
        a.add("--serial=" + serial);
        a.add("--window-title=" + title("OneSay 投屏 · " + name));
        if (uhid) a.add("--keyboard=uhid");
        if (!audio) a.add("--no-audio");
        return a;
    }

    /** 单 App 窗口：新建虚拟屏 + 在上面打开 App；输入法显示在这块屏上；关窗时 App 退回手机（不销毁）；不转声音。 */
    public static List<String> app(String exe, String serial, String pkg, boolean uhid) {
        if (!Adb.validSerial(serial)) throw new IllegalArgumentException("序列号不对");
        if (!Adb.validPackage(pkg)) throw new IllegalArgumentException("包名不对");
        List<String> a = new ArrayList<>();
        a.add(exe);
        a.add("--serial=" + serial);
        a.add("--new-display=" + APP_DISPLAY);
        a.add("--start-app=" + pkg);
        a.add("--flex-display");
        a.add("--display-ime-policy=local");
        a.add("--no-vd-destroy-content");
        a.add("--no-audio");
        if (uhid) a.add("--keyboard=uhid");
        a.add("--window-title=" + title("OneSay · " + pkg));
        return a;
    }

    /** 窗口标题：去掉控制字符、引号、方向控制符，最长 80 字。 */
    static String title(String s) {
        StringBuilder b = new StringBuilder();
        s.codePoints().forEach(c -> {
            if (c < 0x20 || c == 0x7f || c == '"' || c == '\\' || (c >= 0x200e && c <= 0x200f) || (c >= 0x202a && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069)) return;
            b.appendCodePoint(c);
        });
        String t = b.toString().strip();
        return t.codePointCount(0, t.length()) > 80 ? t.substring(0, t.offsetByCodePoints(0, 80)) : t;
    }
}
