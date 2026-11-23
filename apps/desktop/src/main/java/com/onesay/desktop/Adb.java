package com.onesay.desktop;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * phone-mirror M1：adb 的输出解析与报错翻译（纯 Java，可单测），外加一个极简的进程执行器。
 *
 * <p>只做用户在界面上点出来的动作（列设备、装 APK、推文件、列 App），不给 agent 用。
 * 所有参数都是 argv 数组元素（ProcessBuilder，不经 cmd / PowerShell）；需要进手机 shell 的字符串（媒体扫描的路径）先过 {@link #shQuote}。
 */
public final class Adb {

    private Adb() {}

    // ------------------------------------------------------------------ 设备列表

    /** `adb devices -l` 的一行。state：device / unauthorized / offline / no permissions / recovery / sideload / bootloader / authorizing / host … */
    public static final class Device {
        public final String serial, state;
        public final Map<String, String> props;

        Device(String serial, String state, Map<String, String> props) {
            this.serial = serial;
            this.state = state;
            this.props = props;
        }

        public String model() { return props.getOrDefault("model", "").replace('_', ' '); }

        public String product() { return props.getOrDefault("product", ""); }

        /** 有 usb: 字段 = USB 线；serial 是 ip:port = 网络；emulator-NNNN 或本机回环地址 = 模拟器。 */
        public String transport() {
            if (serial.startsWith("emulator-") || serial.startsWith("127.0.0.1:") || serial.startsWith("localhost:")) return "emulator";
            if (props.containsKey("usb")) return "usb";
            if (serial.matches("\\[?[0-9A-Fa-f:.]+\\]?:\\d{1,5}") || serial.startsWith("adb-")) return "tcp";
            return "usb";
        }

        @Override public String toString() { return serial + "/" + state; }
    }

    /** adb 序列号只认这些字符：传给 -s 之前必须过这个。 */
    public static final Pattern SERIAL = Pattern.compile("[A-Za-z0-9._:\\-\\[\\]]{1,96}");

    public static boolean validSerial(String s) { return s != null && SERIAL.matcher(s).matches(); }

    private static final List<String> STATES = List.of("device", "unauthorized", "offline", "no permissions", "recovery", "sideload",
            "bootloader", "authorizing", "connecting", "host", "rescue", "unknown", "no device");

    /**
     * 解析 `adb devices -l`。忽略标题行、「* daemon …」提示、空行；序列号不合法的行丢弃（不交给后面拼命令）。
     * 状态可以带空格（"no permissions (…); see […]"），所以按已知状态前缀匹配，余下的 key:value 当属性。
     */
    public static List<Device> parseDevices(String out) {
        List<Device> l = new ArrayList<>();
        if (out == null) return l;
        for (String raw : out.split("\\r?\\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("*") || line.startsWith("List of devices") || line.startsWith("adb ")) continue;
            String[] sp = line.split("\\s+", 2);
            if (sp.length < 2) continue;
            String serial = sp[0], rest = sp[1].strip();
            if (!validSerial(serial) || serial.endsWith(":")) continue;
            String state = null;
            for (String st : STATES) {
                if (rest.equals(st) || rest.startsWith(st + " ") || rest.startsWith(st + "\t") || (st.equals("no permissions") && rest.startsWith(st))) { state = st; break; }
            }
            if (state == null) continue; // 不认识的状态 = 不是设备行（如 "error: protocol fault"）
            Map<String, String> props = new LinkedHashMap<>();
            String tail = rest.substring(Math.min(rest.length(), state.length()));
            if (state.equals("no permissions")) tail = tail.replaceAll("^[^\\]]*\\]", ""); // 去掉括号里的说明
            for (String kv : tail.strip().split("\\s+")) {
                int c = kv.indexOf(':');
                if (c > 0 && c < kv.length() - 1 && kv.substring(0, c).matches("[a-z_]{1,20}")) props.put(kv.substring(0, c), kv.substring(c + 1));
            }
            l.add(new Device(serial, state, props));
        }
        return l;
    }

    // ------------------------------------------------------------------ getprop / 包名

    /** `adb shell getprop` 全量输出：[key]: [value]。 */
    public static Map<String, String> parseGetprop(String out) {
        Map<String, String> m = new LinkedHashMap<>();
        if (out == null) return m;
        Matcher x = Pattern.compile("^\\[([^\\]]{1,128})\\]:\\s*\\[(.*)\\]\\s*$", Pattern.MULTILINE).matcher(out);
        while (x.find()) m.put(x.group(1), x.group(2));
        return m;
    }

    public static final Pattern PKG = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");

    public static boolean validPackage(String p) { return p != null && p.length() <= 200 && PKG.matcher(p).matches(); }

    /** `pm list packages -3`：每行 package:com.x.y（老系统可能带 package:/data/app/…=com.x.y），去重排序，只留合法包名。 */
    public static List<String> parsePackages(String out) {
        java.util.TreeSet<String> s = new java.util.TreeSet<>();
        if (out == null) return new ArrayList<>(s);
        for (String raw : out.split("\\r?\\n")) {
            String line = raw.strip();
            if (!line.startsWith("package:")) continue;
            String p = line.substring(8);
            int eq = p.lastIndexOf('=');
            if (eq >= 0) p = p.substring(eq + 1);
            p = p.strip().split("\\s+")[0];
            if (validPackage(p)) s.add(p);
        }
        return new ArrayList<>(s);
    }

    // ------------------------------------------------------------------ 装包 / 推文件结果

    /** 安装结果：ok；失败时 code（INSTALL_FAILED_… / INSTALL_PARSE_FAILED_… / 连接类），中文提示 zh。 */
    public static final class Outcome {
        public final boolean ok;
        public final String code, zh;

        Outcome(boolean ok, String code, String zh) {
            this.ok = ok;
            this.code = code;
            this.zh = zh;
        }
    }

    static final Pattern FAIL_CODE = Pattern.compile("\\b((?:INSTALL_FAILED|INSTALL_PARSE_FAILED|DELETE_FAILED)_[A-Z0-9_]+)\\b");

    /** 解析 `adb install` 的输出。最后有 Success 且没有 Failure 才算成功。 */
    public static Outcome parseInstall(String out, int exit) {
        String o = out == null ? "" : out;
        boolean failure = o.contains("Failure") || o.contains("failed to install") || o.contains("adb: error") || o.contains("error:");
        if (!failure && Pattern.compile("(?m)^\\s*Success\\s*$").matcher(o).find()) return new Outcome(true, "", "已安装");
        Matcher m = FAIL_CODE.matcher(o);
        String code = m.find() ? m.group(1) : connectionCode(o, exit);
        return new Outcome(false, code, installZh(code, o));
    }

    /** 解析 `adb push` 的输出：有「pushed」或「1 file pushed」且退出码 0 才算成功。 */
    public static Outcome parsePush(String out, int exit) {
        String o = out == null ? "" : out;
        if (exit == 0 && o.matches("(?s).*\\bfile(s)? pushed\\b.*")) return new Outcome(true, "", "已放到手机");
        if (exit == 0 && !o.toLowerCase(Locale.ROOT).contains("error")) return new Outcome(true, "", "已放到手机");
        String l = o.toLowerCase(Locale.ROOT);
        String code;
        if (l.contains("no space left")) code = "NO_SPACE";
        else if (l.contains("permission denied") || l.contains("read-only file system")) code = "PERMISSION";
        else if (l.contains("couldn't create file") || l.contains("is a directory")) code = "CANNOT_CREATE";
        else code = connectionCode(o, exit);
        return new Outcome(false, code, pushZh(code));
    }

    static String connectionCode(String o, int exit) {
        String l = o.toLowerCase(Locale.ROOT);
        if (l.contains("unauthorized")) return "UNAUTHORIZED";
        if (l.contains("device offline") || l.contains("device still connecting")) return "OFFLINE";
        if (l.contains("not found") && l.contains("device") || l.contains("no devices") || l.contains("closed")) return "DISCONNECTED";
        if (exit == 124 || l.contains("timed out") || l.contains("timeout")) return "TIMEOUT";
        return "UNKNOWN";
    }

    /** 魅族 / Flyme 默认不让电脑装 App 时给的提示（界面和测试共用）。 */
    public static final String HINT_USB_INSTALL = "手机拦下了电脑装 App（INSTALL_FAILED_USER_RESTRICTED）。魅族 / Flyme：在手机「开发者选项」里打开「USB 安装」"
            + "（可能要登录魅族账号或验证），再拖一次；也可以选「改为放到 Download」，在手机文件管理里点开安装。";

    /** 安装失败码 → 中文说明（含怎么办）。 */
    public static String installZh(String code, String raw) {
        switch (code == null ? "" : code) {
            case "INSTALL_FAILED_USER_RESTRICTED": return HINT_USB_INSTALL;
            case "INSTALL_FAILED_ABORTED": return "安装在手机上被取消了（有的手机会弹框问，要在手机上点「允许 / 继续安装」）";
            case "INSTALL_FAILED_VERIFICATION_FAILURE": return "手机的安全检测没让装（应用验证 / 纯净模式 / 备案检测），只能在手机上处理";
            case "INSTALL_FAILED_UPDATE_INCOMPATIBLE": return "手机上已有同名 App 但签名不同，要先在手机上卸载原来的（会丢它的数据）";
            case "INSTALL_FAILED_VERSION_DOWNGRADE": return "这个 APK 比手机上装的版本旧，不能直接降级";
            case "INSTALL_FAILED_ALREADY_EXISTS": return "手机上已经装了这个 App";
            case "INSTALL_FAILED_INSUFFICIENT_STORAGE": return "手机存储空间不够";
            case "INSTALL_FAILED_OLDER_SDK": return "这个 App 要求的安卓版本比手机高，装不了";
            case "INSTALL_FAILED_NO_MATCHING_ABIS": return "这个 APK 不是给这台手机的处理器架构编译的";
            case "INSTALL_FAILED_TEST_ONLY": return "这是只供测试的 APK，手机默认不让装";
            case "INSTALL_FAILED_INVALID_APK":
            case "INSTALL_PARSE_FAILED_NOT_APK":
            case "INSTALL_PARSE_FAILED_NO_CERTIFICATES":
            case "INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION":
            case "INSTALL_PARSE_FAILED_BAD_MANIFEST": return "APK 文件坏了或没签名，换一个文件试试";
            case "INSTALL_FAILED_MISSING_SPLIT": return "这个 App 是拆包（split APK）发布的，单个 APK 装不了";
            case "UNAUTHORIZED": return "手机还没允许这台电脑调试：在手机上点「允许 USB 调试」";
            case "OFFLINE": return "手机连接还没就绪，等几秒再试，或重新插一下线";
            case "DISCONNECTED": return "手机断开了，重新插上再试";
            case "TIMEOUT": return "安装超时了，手机上可能在弹框等你确认";
            default: {
                if (code != null && code.startsWith("INSTALL_PARSE_FAILED")) return "APK 文件解析失败（" + code + "）";
                if (code != null && code.startsWith("INSTALL_FAILED")) return "安装失败（" + code + "）";
                return "安装失败" + (raw == null || raw.isBlank() ? "" : "：" + Notify.clip(raw.strip().replaceAll("\\s+", " "), 120));
            }
        }
    }

    public static String pushZh(String code) {
        switch (code == null ? "" : code) {
            case "NO_SPACE": return "手机存储空间不够";
            case "PERMISSION": return "手机不让写 Download 文件夹";
            case "CANNOT_CREATE": return "手机上建不了这个文件（名字可能不合法）";
            case "UNAUTHORIZED": return "手机还没允许这台电脑调试：在手机上点「允许 USB 调试」";
            case "OFFLINE": return "手机连接还没就绪，等几秒再试";
            case "DISCONNECTED": return "手机断开了，重新插上再试";
            case "TIMEOUT": return "传文件超时了";
            default: return "没放成";
        }
    }

    /**
     * scrcpy 退出时 stderr / stdout 里的报错 → 中文。返回 null = 没认出来（界面只说「投屏窗口关了」）。
     * 只做关键字匹配，原文不进界面（第三方输出当数据）。
     */
    public static String scrcpyZh(String out, int exit, boolean uhid, boolean newDisplay) {
        if (out == null) return exit == 0 ? null : "投屏没打开（退出码 " + exit + "）";
        String l = out.toLowerCase(Locale.ROOT);
        if (l.contains("unauthorized")) return "手机还没允许这台电脑调试：在手机上点「允许 USB 调试」";
        if (l.contains("could not find any adb device") || l.contains("device not found") || l.contains("no devices")) return "没找到手机，重新插上再试";
        if (newDisplay && (l.contains("virtual display") || l.contains("new-display") || l.contains("new display"))
                && (l.contains("not supported") || l.contains("could not") || l.contains("error") || l.contains("failed")))
            return "此手机系统版本暂不支持手机桌面（需要安卓 10 及以上，建议 13 及以上）";
        if (uhid && (l.contains("uhid") || l.contains("/dev/uhid")) && (l.contains("error") || l.contains("could not") || l.contains("failed") || l.contains("permission")))
            return "「物理键盘（UHID）」模式在这台手机上用不了：去设置改回「普通键盘」，中文用复制粘贴（Ctrl+V 会同步剪贴板）";
        if (l.contains("encoder") && (l.contains("error") || l.contains("could not"))) return "手机的视频编码器启动失败，可以重插线再试";
        if (l.contains("server connection failed") || l.contains("connection refused") || l.contains("could not push")) return "和手机的连接中断了，重新插上再试";
        if (exit == 0) return null;
        return "投屏窗口异常退出（退出码 " + exit + "）";
    }

    /** 给手机 shell 用的单引号转义：'a'\''b'。只用在已经校验过的路径上，再加一层保险。 */
    public static String shQuote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    // ------------------------------------------------------------------ 执行器

    /** 执行结果。exit 124 = 超时被结束。 */
    public static final class Run {
        public final int exit;
        public final String out;

        public Run(int exit, String out) {
            this.exit = exit;
            this.out = out;
        }
    }

    /** 可换（测试用假 adb）。 */
    public interface Runner {
        Run run(List<String> argv, long timeoutMs) throws IOException, InterruptedException;
    }

    /** 真执行：合并 stderr，最多读 1 MB，超时结束进程。 */
    public static final Runner REAL = (argv, timeoutMs) -> {
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().close();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        Thread rd = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) if (bo.size() < 1_000_000) synchronized (bo) { bo.write(b, 0, n); }
            } catch (IOException ignored) { }
        }, "onesay-adb-out");
        rd.setDaemon(true);
        rd.start();
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            rd.join(1000);
            synchronized (bo) { return new Run(124, bo.toString(StandardCharsets.UTF_8)); }
        }
        rd.join(2000);
        synchronized (bo) { return new Run(p.exitValue(), bo.toString(StandardCharsets.UTF_8)); }
    };
}
