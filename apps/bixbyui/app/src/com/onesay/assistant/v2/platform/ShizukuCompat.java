package com.onesay.assistant.v2.platform;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ fix-1118：能力模板里有几条旧写法（{@code service call bluetooth_manager 6}、{@code dumpsys battery | grep level}），
 * root 守护能跑，Shizuku 白名单是 argv 精确匹配、不认管道也不认 service call —— 开源版只有 Shizuku 的手机上蓝牙 / 电量全挂（真机 c07、c10、c11）。
 *
 * <p>这里只做<b>等价改写</b>：把模板换成白名单里那条意思相同的命令，管道里的 grep 改成 App 里按行过滤。
 * 改写后的命令照样过 {@code ShizukuWhitelist.check}，不放宽任何东西；改写不出来的照旧拒绝。纯 Java，JVM 可测。
 */
public final class ShizukuCompat {
    private ShizukuCompat() {}

    /** 改写结果：{@link #command} 交给 Shizuku；{@link #grep} 非空时只留含它的第一行（模拟 grep -m1）。 */
    public static final class Rewrite {
        public final String command;
        public final String grep;

        Rewrite(String command, String grep) {
            this.command = command;
            this.grep = grep;
        }
    }

    private static final Pattern BATTERY_GREP = Pattern.compile(
            "^dumpsys\\s+battery\\s*\\|\\s*grep(?:\\s+-m\\s*1)?\\s+'?\\s*(level|temperature|status|AC powered|USB powered)(:)?\\s*'?$");
    private static final Pattern BT_SERVICE = Pattern.compile("^service\\s+call\\s+bluetooth_manager\\s+(\\d+)(?:\\s+.*)?$");
    private static final Pattern SCREENCAP_QUOTED = Pattern.compile("^screencap\\s+-p\\s+'([A-Za-z0-9_./\\-]{1,200}\\.png)'$");
    private static final Pattern BT_SVC = Pattern.compile("^svc\\s+bluetooth\\s+(enable|disable)$");

    public static Rewrite rewrite(String command) {
        if (command == null) return null;
        String c = command.trim().replaceAll("\\s+", " ");
        Matcher m = BATTERY_GREP.matcher(c);
        if (m.matches()) return new Rewrite("dumpsys battery", m.group(1) + ":");
        m = BT_SERVICE.matcher(c);
        if (m.matches()) {
            // AOSP IBluetoothManager：6 = enable，8 / 9 = disable（9 带 persist 参数的写法），其余不认
            String code = m.group(1);
            if ("6".equals(code)) return new Rewrite("cmd bluetooth_manager enable", null);
            if ("8".equals(code) || "9".equals(code)) return new Rewrite("cmd bluetooth_manager disable", null);
            return null;
        }
        m = BT_SVC.matcher(c);
        if (m.matches()) return new Rewrite("cmd bluetooth_manager " + m.group(1), null);
        // 识屏截图（真机 c27）：模板给路径加了单引号（守护按 shell 规则），白名单不认引号 —— 路径本身只含安全字符时去掉引号；
        //   路径仍要过白名单（只认 /data/local/tmp/onesay*.png 与本包外部 cache/）
        m = SCREENCAP_QUOTED.matcher(c);
        if (m.matches()) return new Rewrite("screencap -p " + m.group(1), null);
        return null;
    }

    /** grep 等价过滤：留第一行含 key 的（没有就空串）。 */
    public static String filter(String output, String grep) {
        if (grep == null || output == null) return output;
        for (String line : output.split("\n")) {
            if (line.contains(grep)) return line;
        }
        return "";
    }

    /** 电量类只读命令（本地可用 BatteryManager 答，不需要任何特权）。 */
    public static boolean isBatteryRead(String command) {
        if (command == null) return false;
        String c = command.trim().replaceAll("\\s+", " ");
        return c.equals("dumpsys battery") || BATTERY_GREP.matcher(c).matches();
    }

    /**
     * 本地电量回读，格式与 {@code dumpsys battery} 一致（两格缩进 + 「key: value」），模板的回读解析不用改。
     *
     * @param level 0–100；&lt;0 = 读不到
     * @param tempTenths 电池温度（0.1 °C）；Integer.MIN_VALUE = 读不到
     * @param status BatteryManager.BATTERY_STATUS_*；&lt;0 = 读不到
     */
    public static String localBatteryDump(String command, int level, int tempTenths, int status, boolean ac, boolean usb) {
        StringBuilder sb = new StringBuilder("Current Battery Service state:\n");
        sb.append("  AC powered: ").append(ac).append('\n');
        sb.append("  USB powered: ").append(usb).append('\n');
        if (status >= 0) sb.append("  status: ").append(status).append('\n');
        if (level >= 0) sb.append("  level: ").append(level).append('\n');
        sb.append("  scale: 100\n");
        if (tempTenths != Integer.MIN_VALUE) sb.append("  temperature: ").append(tempTenths).append('\n');
        String all = sb.toString();
        if (command == null) return all;
        Matcher m = BATTERY_GREP.matcher(command.trim().replaceAll("\\s+", " "));
        return m.matches() ? filter(all, m.group(1) + ":") : all;
    }
}
