package com.onesay.desktop;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.IDN;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [link-v2 · 执行级别] 电脑 ⇄ 手机远程指令的「执行级别 + 信任检查 + 工作区沙箱」，纯 Java（Java 7 语法），手机端与电脑端用同一份
 * （电脑端是 {@code com.onesay.desktop.LinkPolicy}，只有 package 行不同）。
 *
 * <p>三档级别仿照 Codex CLI 的审批模式（read-only / auto / full-access）与 workspace-write 沙箱的思路，代码自己写的：
 * <ul>
 *   <li><b>只读</b>：只回答状态 / 电量 / 信息类查询，其余拒绝（系统级改动、给自己授权 → 只生成脚本）。</li>
 *   <li><b>自动（默认）</b>：无害指令直接执行；写文件只限工作区；打开网址 / 写剪贴板要过信任检查，需要时在被控端确认。</li>
 *   <li><b>完全访问</b>：少确认，但信任检查、禁止类（支付 / 银行 / 密码 / 钱包 / 应用商店 / 安装器、系统 App、给自己授权）照旧。</li>
 * </ul>
 * 被控端分两种（{@link Target}）：电脑（严格矩阵：A 类系统改动任何级别都只生成脚本，B 类简单设置先问后做，完全访问只多出
 * 「运行工作区里已有脚本」和「操作第三方 App」）和手机（宽一些：简单设置自动档直接做，网络 / 安全设置先问后做）。
 * 两边共同的红线：不给 OneSay / 沙箱自己授权（pm grant、appops、无障碍 / 通知监听、设备管理器、Shizuku 自动批准）→ 只生成脚本。
 */
public final class LinkPolicy {

    private LinkPolicy() {}

    // ================================================================== 级别 / 被控端 / 结论

    public enum Level {
        READ_ONLY("read_only", "只读"), AUTO("auto", "自动"), FULL("full", "完全访问");
        public final String id, zh;

        Level(String id, String zh) {
            this.id = id;
            this.zh = zh;
        }

        /** 认不出来的一律当「自动」（默认档）。 */
        public static Level of(String s) {
            if (s != null) for (Level l : values()) if (l.id.equalsIgnoreCase(s.trim()) || l.zh.equals(s.trim())) return l;
            return AUTO;
        }
    }

    public static final Level DEFAULT_LEVEL = Level.AUTO;

    public enum Target { PC, PHONE }

    public enum Verdict { ALLOW, CONFIRM, DENY, SCRIPT }

    /** 回给发令端的原因码（data.reason）。 */
    public static final String R_DENIED_BY_LEVEL = "denied_by_level", R_CONFIRM_TIMEOUT = "needs_confirm_timeout",
            R_CONFIRM_DENIED = "confirm_denied", R_UNTRUSTED_URL = "untrusted_url", R_BLOCKED_APP = "blocked_app",
            R_CLIPBOARD_REJECTED = "clipboard_rejected", R_SCRIPT_GENERATED = "script_generated", R_SYSTEM_APP = "system_app",
            R_OUTSIDE_WORKSPACE = "outside_workspace", R_UNKNOWN = "unknown_command";

    /** 指令的类别（决定矩阵行）。 */
    public enum Cat {
        /** 状态 / 电量 / 信息 / 能力清单。 */
        QUERY,
        /** 读隐私：截图、读剪贴板、读文件、列工作区。 */
        READ_PRIVATE,
        /** 无害动作：找手机（30 秒自动停 + 还原音量）、手电筒、亮屏、通知、媒体键、锁屏。 */
        HARMLESS,
        /** 简单设置：亮度、音量、勿扰、屏幕超时、Wi-Fi / 蓝牙开关。 */
        SIMPLE_SETTING,
        /** 网络 / 安全设置（超出简单开关的）。电脑上属于 A 类。 */
        NET_SEC_SETTING,
        /** 电脑上的 A 类：注册表、网络配置（网卡 / 防火墙 / 代理 / DNS / hosts / Wi-Fi）、服务 / 驱动。 */
        SYSTEM_A,
        /** 关机 / 睡眠。 */
        POWER,
        OPEN_URL, OPEN_APP, CLIPBOARD_WRITE,
        /** 操作第三方 App（关掉 / 清后台等，不含卸载）。 */
        APP_OPERATE,
        WORKSPACE_WRITE, OUTSIDE_WRITE,
        UNINSTALL_APP,
        /** 运行工作区 scripts/ 里已有的脚本。 */
        RUN_WS_SCRIPT,
        /** 任意 shell / su / Shizuku 命令（分类不出更具体的）。 */
        SHELL_EXEC,
        /** 给自己（或任何 App）授权：pm grant、appops、无障碍 / 通知监听、设备管理器、Shizuku 批准。 */
        SELF_GRANT,
        /** 改动系统 App（卸载 / 停用 / 清数据）。 */
        SYSTEM_APP_OP,
        UNKNOWN
    }

    /** 判定需要的事实（信任检查结果等）；没有的字段保持默认。 */
    public static final class Facts {
        public UrlCheck url;
        public ClipCheck clip;
        public boolean appBlocked, appSystem;
        public String appLabel = "";
        public boolean insideWorkspace = true;
    }

    public static final class Decision {
        public final Verdict verdict;
        public final String reason;
        /** 给人看的一句话（中文）。 */
        public final String text;
        /** 完全访问下不确认、但要给用户提个醒（比如不在信任列表的网址）。 */
        public final boolean headsUp;

        Decision(Verdict v, String reason, String text, boolean headsUp) {
            this.verdict = v;
            this.reason = reason == null ? "" : reason;
            this.text = text == null ? "" : text;
            this.headsUp = headsUp;
        }

        static Decision allow() { return new Decision(Verdict.ALLOW, "", "", false); }
        static Decision heads(String text) { return new Decision(Verdict.ALLOW, "", text, true); }
        static Decision confirm(String text) { return new Decision(Verdict.CONFIRM, "", text, false); }
        static Decision deny(String reason, String text) { return new Decision(Verdict.DENY, reason, text, false); }
        static Decision script(String text) { return new Decision(Verdict.SCRIPT, R_SCRIPT_GENERATED, text, false); }

        @Override public String toString() {
            return verdict + (reason.length() > 0 ? "/" + reason : "") + (headsUp ? "/heads-up" : "");
        }
    }

    // ================================================================== 矩阵

    /**
     * 核心判定：被控端 × 级别 × 类别（+ 信任检查事实）→ 允许 / 先确认 / 拒绝 / 只生成脚本。
     * 顺序：红线（给自己授权、系统 App、禁止类 App）→ 信任检查（网址 / 剪贴板）→ 级别矩阵。
     */
    public static Decision decide(Target t, Level l, Cat c, Facts f) {
        if (f == null) f = new Facts();
        if (l == null) l = DEFAULT_LEVEL;
        if (c == null) c = Cat.UNKNOWN;
        boolean ro = l == Level.READ_ONLY, full = l == Level.FULL, pc = t == Target.PC;

        // ---- 红线：任何级别都一样
        if (c == Cat.SELF_GRANT) return Decision.script("涉及授予权限，只生成脚本，需要你自己执行");
        if (c == Cat.SYSTEM_APP_OP || ((c == Cat.UNINSTALL_APP || c == Cat.APP_OPERATE) && f.appSystem))
            return Decision.deny(R_SYSTEM_APP, "系统 App 不允许远程改动");
        if ((c == Cat.OPEN_APP || c == Cat.UNINSTALL_APP || c == Cat.APP_OPERATE) && f.appBlocked)
            return Decision.deny(R_BLOCKED_APP, "支付 / 银行 / 密码 / 钱包 / 应用商店 / 安装器类 App 不允许远程操作");
        if (c == Cat.UNKNOWN) return Decision.deny(R_UNKNOWN, "不认识的指令");

        // ---- 只读：查询以外都不做；系统级改动改成生成脚本（不执行）
        if (c == Cat.QUERY) return Decision.allow();
        if (pc && (c == Cat.SYSTEM_A || c == Cat.NET_SEC_SETTING || c == Cat.SHELL_EXEC || c == Cat.OUTSIDE_WRITE))
            return Decision.script("电脑上的系统级改动只生成脚本，需要你自己执行");
        if (ro) {
            if (c == Cat.SYSTEM_A || c == Cat.NET_SEC_SETTING || c == Cat.SHELL_EXEC || c == Cat.OUTSIDE_WRITE)
                return Decision.script("只读模式：系统级改动只生成脚本");
            return Decision.deny(R_DENIED_BY_LEVEL, "当前是只读模式，只能查状态");
        }

        // ---- 信任检查（自动 / 完全访问都要过）
        if (c == Cat.OPEN_URL) {
            UrlCheck u = f.url;
            if (u == null || !u.ok) return Decision.deny(R_UNTRUSTED_URL, u == null ? "网址没过检查" : u.why);
            if (u.trusted) return Decision.allow();
            if (full) return Decision.heads("打开不在信任列表的网址：" + u.shown);
            return Decision.confirm("打开网址：" + u.shown);
        }
        if (c == Cat.CLIPBOARD_WRITE) {
            ClipCheck k = f.clip;
            if (k == null || k.verdict == ClipCheck.REJECT) return Decision.deny(R_CLIPBOARD_REJECTED, k == null ? "剪贴板内容没过检查" : k.why);
            if (k.verdict == ClipCheck.SENSITIVE) return Decision.confirm("写入敏感内容（" + k.why + "），60 秒后自动清除");
            return full ? Decision.allow() : Decision.confirm("写剪贴板（" + k.chars + " 字）");
        }

        // ---- 级别矩阵（自动 / 完全访问）
        switch (c) {
            case READ_PRIVATE:
            case HARMLESS:
            case WORKSPACE_WRITE:
            case OPEN_APP:
                return Decision.allow();
            case SIMPLE_SETTING:
            case POWER:
                if (pc) return Decision.confirm("电脑设置改动需要确认");
                return c == Cat.POWER && !full ? Decision.confirm("需要确认") : Decision.allow();
            case NET_SEC_SETTING:
            case SYSTEM_A:
                return full ? Decision.allow() : Decision.confirm("网络 / 安全设置改动需要确认");
            case OUTSIDE_WRITE:
                return full ? Decision.allow() : Decision.confirm("写工作区以外的文件需要确认");
            case UNINSTALL_APP:
                return Decision.confirm("卸载 App 需要确认（走系统卸载界面）");
            case APP_OPERATE:
                if (full) return Decision.allow();
                return pc ? Decision.deny(R_DENIED_BY_LEVEL, "操作第三方 App 要完全访问") : Decision.confirm("操作 App 需要确认");
            case RUN_WS_SCRIPT:
                return full ? Decision.confirm("运行工作区脚本（每次都要确认）") : Decision.deny(R_DENIED_BY_LEVEL, "运行脚本要完全访问");
            case SHELL_EXEC:
                return full ? Decision.confirm("执行系统命令（会显示完整命令）") : Decision.script("系统命令只生成脚本，需要你自己执行");
            default:
                return Decision.deny(R_UNKNOWN, "不认识的指令");
        }
    }

    // ================================================================== 指令 → 类别

    /** 手机作为被控端：电脑发来的指令名 → 类别（shell 命令另走 {@link #classifyShell}）。 */
    public static Cat phoneCat(String cmd) {
        if (cmd == null) return Cat.UNKNOWN;
        if (in(cmd, "ping", "caps", "status", "level")) return Cat.QUERY;
        if (in(cmd, "file_list", "script_list")) return Cat.READ_PRIVATE;
        if (in(cmd, "find_phone", "screen_on", "notify", "flashlight")) return Cat.HARMLESS;
        if (in(cmd, "volume_set", "setting_set")) return Cat.SIMPLE_SETTING;
        if ("open_url".equals(cmd)) return Cat.OPEN_URL;
        if ("open_app".equals(cmd)) return Cat.OPEN_APP;
        if ("clipboard_set".equals(cmd)) return Cat.CLIPBOARD_WRITE;
        if ("file_write".equals(cmd)) return Cat.WORKSPACE_WRITE;
        if ("uninstall_app".equals(cmd)) return Cat.UNINSTALL_APP;
        if ("script_run".equals(cmd)) return Cat.RUN_WS_SCRIPT;
        if ("sys_exec".equals(cmd)) return Cat.SHELL_EXEC;
        return Cat.UNKNOWN;
    }

    /** 电脑作为被控端：手机发来的指令名 → 类别（与 DesktopActions 的能力名对应）。新指令必须在这里登记，否则一律拒绝。 */
    public static Cat pcCat(String cmd) {
        if (cmd == null) return Cat.UNKNOWN;
        if (in(cmd, "ping", "caps", "info", "shortcut_list", "level")) return Cat.QUERY;
        if (in(cmd, "screenshot", "clipboard_get", "file_get", "file_list", "script_list")) return Cat.READ_PRIVATE;
        if (in(cmd, "media", "lock", "notify", "memory_sync", "echo")) return Cat.HARMLESS;
        if (in(cmd, "volume", "brightness", "dnd")) return Cat.SIMPLE_SETTING;
        if (in(cmd, "shutdown", "sleep", "shutdown_abort")) return Cat.POWER;
        if ("open_url".equals(cmd)) return Cat.OPEN_URL;
        if ("open_app".equals(cmd)) return Cat.OPEN_APP;
        if ("clipboard_set".equals(cmd)) return Cat.CLIPBOARD_WRITE;
        if ("file_write".equals(cmd)) return Cat.WORKSPACE_WRITE;
        if ("uninstall_app".equals(cmd)) return Cat.UNINSTALL_APP;
        if ("app_close".equals(cmd)) return Cat.APP_OPERATE;
        if (in(cmd, "script_run", "shortcut_run")) return Cat.RUN_WS_SCRIPT;
        if ("sys_exec".equals(cmd)) return Cat.SHELL_EXEC;
        return Cat.UNKNOWN;
    }

    /** 手机 setting_set 的 name：哪些算简单设置、哪些算网络 / 安全。 */
    public static Cat phoneSettingCat(String name) {
        String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (in(n, "brightness", "volume", "dnd", "screen_timeout", "wifi", "bluetooth", "ringer")) return Cat.SIMPLE_SETTING;
        if (in(n, "airplane", "mobile_data", "hotspot", "location", "private_dns", "proxy", "nfc", "adb", "unknown_sources", "lock_screen"))
            return Cat.NET_SEC_SETTING;
        if (in(n, "accessibility", "notification_listener", "device_admin", "permission", "appops", "shizuku")) return Cat.SELF_GRANT;
        return Cat.UNKNOWN;
    }

    private static final Pattern SELF_GRANT = Pattern.compile(
            "(\\bpm\\s+(grant|revoke|set-permission-flags|set-installer)\\b)|(\\bappops\\s+set\\b)|(\\bcmd\\s+appops\\b)"
                    + "|(enabled_accessibility_services)|(accessibility_enabled)|(enabled_notification_listeners)|(\\ballow_listener\\b)"
                    + "|(\\bdpm\\s+set-(active-admin|device-owner|profile-owner))|(\\bcmd\\s+device_policy\\b)"
                    + "|(\\bshizuku\\b)|(moe\\.shizuku)|(\\bmagisk\\b.*\\b(--sqlite|policy)\\b)|(\\bsupolicy\\b)"
                    + "|(\\bAdd-MpPreference\\b)|(\\bicacls\\b)|(\\btakeown\\b)|(\\bSet-Acl\\b)|(\\bnet\\s+localgroup\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PC_SYSTEM_A = Pattern.compile(
            "(\\breg(\\.exe)?\\s+(add|delete|import|copy|load|restore)\\b)|(\\bregedit\\b)|(\\bHK(LM|CU|CR|U|CC)[:\\\\])|(\\bHKEY_)"
                    + "|(\\b(New|Set|Remove)-ItemProperty\\b)|(\\bnetsh\\b)|(\\b(New|Set|Remove)-Net\\w+)|(\\bSet-DnsClient\\w*)"
                    + "|(\\bipconfig\\s+/(release|renew|flushdns))|(\\broute(\\.exe)?\\s+(add|delete|change)\\b)|(drivers\\\\etc\\\\hosts)"
                    + "|(\\bsc(\\.exe)?\\s+(create|config|delete|start|stop)\\b)|(\\b(New|Set|Start|Stop|Remove)-Service\\b)"
                    + "|(\\bpnputil\\b)|(\\bbcdedit\\b)|(\\bdism\\b)|(\\bwmic\\b)|(\\bproxy\\b)|(\\bfirewall\\b)|(\\bDisable-NetAdapter\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE_SIMPLE = Pattern.compile(
            "^\\s*(settings\\s+put\\s+system\\s+(screen_brightness|screen_brightness_mode|screen_off_timeout)\\s+\\S+"
                    + "|svc\\s+(wifi|bluetooth)\\s+(enable|disable)|cmd\\s+(wifi|bluetooth_manager)\\s+(set-wifi-enabled\\s+(enabled|disabled)|enable|disable)"
                    + "|cmd\\s+notification\\s+set_dnd\\s+\\S+|media\\s+volume\\s+.*)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE_NETSEC = Pattern.compile(
            "\\bsettings\\s+put\\s+(global|secure)\\b|\\bsvc\\s+(data|nfc|usb)\\b|\\bcmd\\s+(connectivity|netpolicy|wifi)\\b|\\biptables\\b|\\bip\\s+(route|rule|addr)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SYS_APP_OP = Pattern.compile("\\bpm\\s+(uninstall|disable|disable-user|hide|clear|suspend)\\b", Pattern.CASE_INSENSITIVE);

    /** 把一条 shell 命令分类：先认红线（授权），再认电脑 A 类 / 手机简单设置 / 网络安全设置，其余都是 SHELL_EXEC。 */
    public static Cat classifyShell(Target t, String cmd) {
        String s = cmd == null ? "" : cmd;
        if (s.trim().length() == 0) return Cat.UNKNOWN;
        if (SELF_GRANT.matcher(s).find()) return Cat.SELF_GRANT;
        if (t == Target.PC) return PC_SYSTEM_A.matcher(s).find() ? Cat.SYSTEM_A : Cat.SHELL_EXEC;
        if (SYS_APP_OP.matcher(s).find()) return Cat.SHELL_EXEC; // 卸载 / 停用要走 uninstall_app（系统卸载界面），shell 形式按系统命令处理
        // 多条命令拼接的（; && | 反引号 $()）一律按任意命令处理，不按其中一段放宽
        if (s.matches("(?s).*(;|&&|\\|\\||\\||`|\\$\\(|\\n|>|<).*")) return Cat.SHELL_EXEC;
        if (PHONE_SIMPLE.matcher(s).find()) return Cat.SIMPLE_SETTING;
        if (PHONE_NETSEC.matcher(s).find()) return Cat.NET_SEC_SETTING;
        return Cat.SHELL_EXEC;
    }

    // ================================================================== 禁止类 App

    private static final Set<String> BLOCKED_PKGS = new LinkedHashSet<String>(Arrays.asList(
            // 支付 / 钱包
            "com.eg.android.alipaygphone", "com.unionpay", "com.jd.jrapp", "com.paypal.android.p2pmobile", "com.venmo",
            "com.google.android.apps.walletnfcrel", "com.google.android.apps.nbu.paisa.user", "com.samsung.android.spay",
            "com.samsung.android.samsungpay.gear", "com.huawei.wallet", "com.mipay.wallet", "com.finshell.wallet", "io.metamask",
            "com.wallet.crypto.trustapp", "com.coinbase.android", "com.binance.dev", "com.okinc.okex.gp", "com.tencent.wetype.pay",
            "com.cmbchina.ccd.pluto.cmbactivity", "com.sankuai.meituan.pay",
            // 银行
            "com.chinamworld.main", "com.icbc", "cmb.pb", "com.android.bankabc", "com.chinamworld.bocmbci", "com.bankcomm",
            "com.ecitic.bank.mobile", "com.cib.cibmb", "com.pingan.paces.ccms", "com.yitong.mbank.psbc", "com.spdb.mobilebank.per",
            "com.cebbank.mobile.cemb", "com.hxb.mobile.client", "cn.com.cmbc.newmbank", "com.webank.wemoney",
            // 密码 / 验证器
            "com.x8bit.bitwarden", "com.agilebits.onepassword", "com.lastpass.lpandroid", "com.keepassdroid", "keepass2android.keepass2android",
            "com.dashlane", "com.google.android.apps.authenticator2", "com.azure.authenticator", "com.authy.authy", "org.fedorahosted.freeotp",
            "com.kunzisoft.keepass.free", "com.enpass.app", "com.nordpass.android.app.password.manager", "proton.android.pass",
            // 应用商店 / 安装器
            "com.android.vending", "com.huawei.appmarket", "com.xiaomi.market", "com.sec.android.app.samsungapps", "com.bbk.appstore",
            "com.oppo.market", "com.heytap.market", "com.tencent.android.qqdownloader", "com.wandoujia.phoenix2", "com.baidu.appsearch",
            "com.qihoo.appstore", "com.meizu.mstore", "com.lenovo.leos.appstore", "com.aurora.store", "org.fdroid.fdroid",
            "com.android.packageinstaller", "com.google.android.packageinstaller", "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller", "com.coloros.safecenter", "com.amazon.venezia",
            // 电脑端（Windows）
            "microsoft.windowsstore", "ms-windows-store", "winget", "msiexec", "keepass", "1password", "bitwarden", "keepassxc"));
    private static final Pattern BLOCKED_PKG_PART = Pattern.compile(
            "(^|[._])([a-z0-9]*(bank|wallet|alipay|unionpay|paypal|password|authenticator|keepass|appstore|appmarket|packageinstaller)[a-z0-9]*"
                    + "|pay|spay|mipay|vault|market|installer)([._]|$)");
    private static final Pattern BLOCKED_LABEL = Pattern.compile(
            "(银行|支付|钱包|付款|密码|验证器|应用商店|应用市场|软件商店|应用宝|安装器|安装程序|Bank|Wallet|Pay\\b|Password|Authenticator|App Store|Play 商店|Play Store|Installer)",
            Pattern.CASE_INSENSITIVE);

    /** 支付 / 银行 / 密码 / 钱包 / 应用商店 / 安装器类：包名清单 + 包名分段关键词 + 名字关键词。 */
    public static boolean blockedApp(String pkg, String label) {
        String p = pkg == null ? "" : pkg.trim().toLowerCase(Locale.ROOT);
        String n = label == null ? "" : label.trim();
        if (p.length() > 0) {
            if (BLOCKED_PKGS.contains(p)) return true;
            for (String b : BLOCKED_PKGS) if (p.startsWith(b + ".")) return true;
            if (BLOCKED_PKG_PART.matcher(p).find()) return true;
        }
        return n.length() > 0 && (BLOCKED_LABEL.matcher(n).find() || BLOCKED_PKGS.contains(n.toLowerCase(Locale.ROOT)));
    }

    // ================================================================== 网址信任检查

    public static final int URL_MAX = 2048;

    public static final class UrlCheck {
        public final boolean ok, trusted;
        /** 只给人看：域名（IDN 同时给 Unicode 与 xn-- 形式）。 */
        public final String host, shown, why;

        UrlCheck(boolean ok, boolean trusted, String host, String shown, String why) {
            this.ok = ok;
            this.trusted = trusted;
            this.host = host == null ? "" : host;
            this.shown = shown == null ? "" : shown;
            this.why = why == null ? "" : why;
        }

        static UrlCheck bad(String why) { return new UrlCheck(false, false, "", "", why); }
    }

    /**
     * 打开网址前的检查：只许 http/https；拒绝带账号密码（user:pass@）、IP 字面量 / 内网主机（除非在信任列表）、
     * 混合文字的仿冒域名（punycode / IDN）、超长、控制字符。trusted = 主机是信任域名或其子域。
     * v1 不做联网信誉查询（以后可以加）。
     */
    public static UrlCheck checkUrl(String raw, List<String> trustedDomains) {
        if (raw == null) return UrlCheck.bad("没有网址");
        String u = raw.trim();
        if (u.length() == 0) return UrlCheck.bad("没有网址");
        if (u.length() > URL_MAX) return UrlCheck.bad("网址太长（超过 " + URL_MAX + " 字符）");
        for (int i = 0; i < u.length(); i++) {
            char ch = u.charAt(i);
            if (ch < 0x21 || ch == 0x7f || ch == '\\' || (ch >= 0x200b && ch <= 0x200f) || (ch >= 0x202a && ch <= 0x202e) || ch == 0xfeff)
                return UrlCheck.bad("网址里有空白 / 控制字符或反斜杠");
        }
        int colon = u.indexOf(':');
        String scheme = colon > 0 ? u.substring(0, colon).toLowerCase(Locale.ROOT) : "";
        if (!("http".equals(scheme) || "https".equals(scheme))) return UrlCheck.bad("只能打开 http / https 网址（拒绝 " + (scheme.length() == 0 ? "无协议" : scheme + ":") + "）");
        if (!u.substring(colon + 1).startsWith("//")) return UrlCheck.bad("网址格式不对");
        // authority：// 之后到第一个 / ? # 为止
        String rest = u.substring(colon + 3);
        int end = rest.length();
        for (char ch : new char[]{'/', '?', '#'}) {
            int k = rest.indexOf(ch);
            if (k >= 0 && k < end) end = k;
        }
        String auth = rest.substring(0, end);
        if (auth.indexOf('@') >= 0) return UrlCheck.bad("网址里带了账号密码（user:pass@），拒绝");
        String hostPort = auth;
        String host;
        if (hostPort.startsWith("[")) {
            int rb = hostPort.indexOf(']');
            if (rb < 0) return UrlCheck.bad("网址格式不对");
            host = hostPort.substring(0, rb + 1);
        } else {
            int pc = hostPort.lastIndexOf(':');
            host = pc >= 0 ? hostPort.substring(0, pc) : hostPort;
            if (pc >= 0 && !hostPort.substring(pc + 1).matches("\\d{0,5}")) return UrlCheck.bad("端口不对");
        }
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.length() == 0) return UrlCheck.bad("网址里没有主机名");
        String ascii;
        try {
            ascii = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return UrlCheck.bad("域名不合法");
        }
        String uni;
        try {
            uni = IDN.toUnicode(ascii, IDN.ALLOW_UNASSIGNED);
        } catch (Exception e) {
            uni = ascii;
        }
        try {
            URI parsed = new URI(scheme + "://" + ascii + u.substring(colon + 3 + auth.length()).replace(" ", "%20"));
            if (parsed.getRawUserInfo() != null) return UrlCheck.bad("网址里带了账号密码（user:pass@），拒绝");
        } catch (Exception e) {
            // 路径部分有 URI 不收的字符：交给浏览器，但主机部分已经检查过
        }
        boolean trusted = trustedHost(ascii, trustedDomains);
        if (isIpLiteral(ascii) && !trusted) return UrlCheck.bad("不打开 IP 地址网址（" + ascii + "），除非你把它加进信任列表");
        if (isPrivateHost(ascii) && !trusted) return UrlCheck.bad("不打开内网 / 本机地址（" + ascii + "），除非你把它加进信任列表");
        String look = lookalike(uni);
        if (look != null) return UrlCheck.bad("疑似仿冒域名（" + look + "）：" + uni + "（" + ascii + "）");
        String shown = uni.equals(ascii) ? ascii : uni + "（" + ascii + "）";
        return new UrlCheck(true, trusted, ascii, shown, "");
    }

    /** 主机是否在信任列表里（等于某个信任域名，或是它的子域）。列表项可写成 *.example.com / example.com / https://example.com/。 */
    public static boolean trustedHost(String asciiHost, List<String> trusted) {
        if (trusted == null || asciiHost == null) return false;
        String h = asciiHost.toLowerCase(Locale.ROOT);
        for (String t0 : trusted) {
            String t = normDomain(t0);
            if (t == null) continue;
            if (h.equals(t) || (!isIpLiteral(t) && h.endsWith("." + t))) return true;
        }
        return false;
    }

    /** 规范化用户填的信任域名；不合法返回 null。 */
    public static String normDomain(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("http://")) t = t.substring(7);
        else if (t.startsWith("https://")) t = t.substring(8);
        if (t.startsWith("*.")) t = t.substring(2);
        int k = t.indexOf('/');
        if (k >= 0) t = t.substring(0, k);
        while (t.endsWith(".")) t = t.substring(0, t.length() - 1);
        if (t.length() == 0 || t.indexOf('@') >= 0 || t.indexOf(' ') >= 0) return null;
        try {
            t = IDN.toASCII(t, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
        return t.matches("[a-z0-9.\\-\\[\\]:]+") ? t : null;
    }

    /** 用户编辑的信任列表（逗号 / 换行 / 空格分隔）→ 规范化、去重。 */
    public static List<String> parseDomains(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) return out;
        for (String p : text.split("[,，;；\\s]+")) {
            String d = normDomain(p);
            if (d != null && !out.contains(d)) out.add(d);
        }
        return out;
    }

    public static boolean isIpLiteral(String h) {
        if (h == null || h.length() == 0) return false;
        if (h.startsWith("[") || h.indexOf(':') >= 0) return true; // IPv6
        if (h.matches("\\d+")) return true; // 2130706433 这类整数写法
        if (h.matches("0x[0-9a-f]+")) return true;
        String[] parts = h.split("\\.", -1);
        if (parts.length < 2 || parts.length > 4) return false;
        for (String p : parts) if (!p.matches("\\d{1,10}|0x[0-9a-f]{1,8}")) return false;
        return true; // 1.2.3.4 / 127.1 / 0x7f.1 等
    }

    public static boolean isPrivateHost(String h) {
        if (h == null) return false;
        String x = h.toLowerCase(Locale.ROOT);
        if (x.equals("localhost") || x.endsWith(".localhost") || x.endsWith(".local") || x.endsWith(".lan") || x.endsWith(".internal")
                || x.endsWith(".home.arpa") || x.endsWith(".intranet") || x.endsWith(".corp") || x.endsWith(".home")) return true;
        return x.indexOf('.') < 0; // 单段主机名（intranet / router）一律当内网
    }

    private static final String CONFUSABLE_CYR = "аеорсухіјѕԁԛԝӏвкмнтпгզ", CONFUSABLE_GREEK = "αοκνρτυχιεβ";

    /** IDN 仿冒：一段里混了多种文字（中日韩的正常组合除外），或整段是西里尔 / 希腊字母但全是长得像拉丁字母的。不是仿冒返回 null。 */
    public static String lookalike(String unicodeHost) {
        if (unicodeHost == null) return null;
        for (String label : unicodeHost.split("\\.")) {
            boolean ascii = true;
            for (int i = 0; i < label.length(); i++) if (label.charAt(i) > 0x7f) { ascii = false; break; }
            if (ascii) continue;
            Set<Character.UnicodeScript> sc = EnumSet.noneOf(Character.UnicodeScript.class);
            int i = 0;
            while (i < label.length()) {
                int cp = label.codePointAt(i);
                i += Character.charCount(cp);
                if (!Character.isLetter(cp)) continue;
                Character.UnicodeScript s = Character.UnicodeScript.of(cp);
                if (s != Character.UnicodeScript.COMMON && s != Character.UnicodeScript.INHERITED) sc.add(s);
            }
            if (sc.size() > 1) {
                Set<Character.UnicodeScript> cjk = EnumSet.of(Character.UnicodeScript.LATIN, Character.UnicodeScript.HAN,
                        Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL, Character.UnicodeScript.BOPOMOFO);
                boolean cjkOnly = cjk.containsAll(sc) && (sc.contains(Character.UnicodeScript.HAN) || sc.contains(Character.UnicodeScript.HIRAGANA)
                        || sc.contains(Character.UnicodeScript.KATAKANA) || sc.contains(Character.UnicodeScript.HANGUL));
                if (!cjkOnly) return "混用多种文字 " + sc;
            }
            if (sc.size() == 1 && (sc.contains(Character.UnicodeScript.CYRILLIC) || sc.contains(Character.UnicodeScript.GREEK))) {
                String pool = sc.contains(Character.UnicodeScript.CYRILLIC) ? CONFUSABLE_CYR : CONFUSABLE_GREEK;
                boolean all = true;
                for (int k = 0; k < label.length(); k++) {
                    char ch = Character.toLowerCase(label.charAt(k));
                    if (Character.isLetter(ch) && pool.indexOf(ch) < 0) { all = false; break; }
                }
                if (all) return "整段都是长得像拉丁字母的" + (pool == CONFUSABLE_CYR ? "西里尔" : "希腊") + "字母";
            }
        }
        return null;
    }

    // ================================================================== 剪贴板信任检查

    public static final int CLIP_MAX_BYTES = 10 * 1024;
    public static final long CLIP_SENSITIVE_CLEAR_MS = 60000;

    public static final class ClipCheck {
        public static final int OK = 0, SENSITIVE = 1, REJECT = 2;
        public final int verdict, chars;
        public final String why;
        public final List<String> kinds;

        ClipCheck(int verdict, int chars, String why, List<String> kinds) {
            this.verdict = verdict;
            this.chars = chars;
            this.why = why == null ? "" : why;
            this.kinds = kinds == null ? Collections.<String>emptyList() : kinds;
        }
    }

    private static final Pattern SCRIPTY = Pattern.compile(
            "(^\\s*#!)|(\\brm\\s+-[a-z]*[rf][a-z]*\\b)|(\\b(curl|wget)\\b[^\\n]*\\|\\s*(ba|z)?sh\\b)|(\\bpowershell(\\.exe)?\\b[^\\n]*-(e|enc|encodedcommand)\\b)"
                    + "|(\\bInvoke-Expression\\b)|(\\biex\\s*\\()|(\\bcmd(\\.exe)?\\s+/c\\b)|(\\bbash\\s+-c\\b)|(\\bsh\\s+-c\\b)|(^\\s*sudo\\s)|(\\bsu\\s+-c\\b)"
                    + "|(\\bpm\\s+(grant|install|uninstall)\\b)|(\\bsettings\\s+put\\b)|(\\breg(\\.exe)?\\s+(add|delete)\\b)|(<\\s*script\\b)"
                    + "|(\\bbase64\\s+(-d|--decode)\\b[^\\n]*\\|)|(\\bchmod\\s+\\+x\\b)|(:\\(\\)\\s*\\{)|(\\bformat\\s+[a-z]:)|(\\bdel\\s+/[fsq])"
                    + "|(\\bmshta\\b)|(\\bcertutil\\b[^\\n]*-(decode|urlcache))|(\\badb\\s+shell\\b)|(\\bStart-Process\\b[^\\n]*-Verb\\s+RunAs)"
                    + "|(\\bdd\\s+if=)|(\\bmkfs\\b)|(\\bnc\\s+-e\\b)|(/dev/tcp/)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern URLISH = Pattern.compile("\\b([a-z][a-z0-9+.\\-]{1,20}):(//)?[^\\s<>\"'）)】]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern OTP_KW = Pattern.compile("(验证码|校验码|动态码|动态密码|确认码|code|otp|verification|passcode|2fa)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIGITS_4_8 = Pattern.compile("(?<!\\d)\\d{4,8}(?!\\d)");
    private static final Pattern CARD = Pattern.compile("(?<!\\d)(\\d[ -]?){12,18}\\d(?!\\d)");
    private static final Pattern PRIVKEY = Pattern.compile(
            "(-----BEGIN [A-Z ]*PRIVATE KEY-----)|(\\b[0-9a-fA-F]{64}\\b)|(\\bxprv[1-9A-HJ-NP-Za-km-z]{100,}\\b)|(\\b[5KL][1-9A-HJ-NP-Za-km-z]{50,51}\\b)"
                    + "|(\\bAKIA[0-9A-Z]{16}\\b)|(\\bsk-[A-Za-z0-9_\\-]{20,}\\b)|(\\bgh[pousr]_[A-Za-z0-9]{30,}\\b)|(\\bxox[baprs]-[A-Za-z0-9\\-]{10,})");
    private static final Pattern PASSWORD_KW = Pattern.compile("(密码|口令|password|passwd|pwd|pin)\\s*[:：=是]\\s*\\S+", Pattern.CASE_INSENSITIVE);

    /** 写手机 / 电脑剪贴板前的检查：超长 / 像命令或脚本 / 含过不了网址检查的链接 → 拒绝；验证码 / 卡号 / 私钥 / 助记词 / 密码 → 敏感。 */
    public static ClipCheck checkClip(String text) {
        if (text == null || text.length() == 0) return new ClipCheck(ClipCheck.REJECT, 0, "没有文字", null);
        int chars = text.codePointCount(0, text.length());
        int bytes;
        try {
            bytes = text.getBytes("UTF-8").length;
        } catch (Exception e) {
            bytes = text.length() * 3;
        }
        if (bytes > CLIP_MAX_BYTES) return new ClipCheck(ClipCheck.REJECT, chars, "内容太大（超过 10 KB）", null);
        if (SCRIPTY.matcher(text).find()) return new ClipCheck(ClipCheck.REJECT, chars, "内容像命令 / 脚本", null);
        Matcher um = URLISH.matcher(text);
        while (um.find()) {
            String scheme = um.group(1).toLowerCase(Locale.ROOT);
            String whole = um.group();
            if (um.group(2) == null && !in(scheme, "javascript", "data", "intent", "file", "content", "vbscript")) continue; // 「时间:10」这类不是链接
            if (!in(scheme, "http", "https")) return new ClipCheck(ClipCheck.REJECT, chars, "含不允许的链接（" + scheme + ":）", null);
            UrlCheck uc = checkUrl(whole, null);
            if (!uc.ok && !uc.why.startsWith("不打开")) return new ClipCheck(ClipCheck.REJECT, chars, "含有问题的链接：" + uc.why, null);
            if (!uc.ok) return new ClipCheck(ClipCheck.REJECT, chars, "含 IP / 内网链接", null);
        }
        List<String> kinds = new ArrayList<String>();
        String t = text.trim();
        if ((t.length() <= 80 && OTP_KW.matcher(t).find() && DIGITS_4_8.matcher(t).find()) || t.matches("\\d{4,8}")) kinds.add("验证码");
        Matcher cm = CARD.matcher(text);
        while (cm.find()) {
            if (luhn(cm.group().replaceAll("[ -]", ""))) { kinds.add("银行卡号"); break; }
        }
        if (PRIVKEY.matcher(text).find()) kinds.add("私钥 / 密钥");
        if (seedLike(t)) kinds.add("助记词");
        if (PASSWORD_KW.matcher(text).find() || passwordLike(t)) kinds.add("密码");
        if (!kinds.isEmpty()) return new ClipCheck(ClipCheck.SENSITIVE, chars, join(kinds, "、"), kinds);
        return new ClipCheck(ClipCheck.OK, chars, "", null);
    }

    public static boolean luhn(String digits) {
        if (digits == null || digits.length() < 13 || digits.length() > 19) return false;
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (d < 0 || d > 9) return false;
            if (dbl) { d *= 2; if (d > 9) d -= 9; }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    /** 12 / 15 / 18 / 21 / 24 个 3–8 个小写字母的词（BIP39 助记词的样子）。 */
    static boolean seedLike(String t) {
        String[] w = t.trim().split("\\s+");
        if (!(w.length == 12 || w.length == 15 || w.length == 18 || w.length == 21 || w.length == 24)) return false;
        Set<String> uniq = new LinkedHashSet<String>();
        for (String x : w) {
            if (!x.matches("[a-z]{3,8}")) return false;
            uniq.add(x);
        }
        return uniq.size() >= w.length - 2;
    }

    /** 单个 8–64 字符、无空白、同时有大写 / 小写 / 数字 / 符号的串。 */
    static boolean passwordLike(String t) {
        if (t.length() < 8 || t.length() > 64 || t.matches(".*\\s.*")) return false;
        if (t.matches("(?i)https?://.*")) return false;
        return t.matches(".*[A-Z].*") && t.matches(".*[a-z].*") && t.matches(".*\\d.*") && t.matches(".*[^A-Za-z0-9].*");
    }

    // ================================================================== 工作区沙箱

    /**
     * 电脑端工作区：%USERPROFILE%\OneSay\workspace；用户目录含非 ASCII（中文用户名）时退到 %ProgramData%\OneSay\workspace
     * （很多命令行工具对非 ASCII 路径不友好）。只算路径，不建目录。
     */
    public static String pcWorkspaceDir(String userProfile, String programData) {
        String up = userProfile == null ? "" : userProfile.trim();
        boolean ascii = up.length() > 0;
        for (int i = 0; i < up.length(); i++) if (up.charAt(i) > 0x7e || up.charAt(i) < 0x20) { ascii = false; break; }
        String base = ascii ? up : (programData == null || programData.trim().length() == 0 ? "C:\\ProgramData" : programData.trim());
        while (base.endsWith("\\") || base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "\\OneSay\\workspace";
    }

    private static final Pattern WIN_RESERVED = Pattern.compile("(?i)^(con|prn|aux|nul|com[0-9]|lpt[0-9]|conin\\$|conout\\$)(\\..*)?$");

    /**
     * 远程给的相对路径 → 工作区里的真实文件；越界返回 null。拒绝：空、绝对路径（/x、\x）、盘符（C:\、C:x）、UNC（\\server、//server）、
     * 设备路径（\\?\、\\.\）、..、NUL、冒号（NTFS 备用流）、Windows 保留名；最后用 getCanonicalFile 比对，符号链接跳出工作区也拒。
     */
    public static File resolveInside(File root, String rel) {
        if (root == null || rel == null) return null;
        String r = rel.trim();
        if (r.length() == 0 || r.length() > 512) return null;
        if (r.indexOf('\0') >= 0 || r.indexOf(':') >= 0) return null; // 盘符 C: / 备用流 a.txt:x / 协议
        if (r.startsWith("/") || r.startsWith("\\") || r.startsWith("~")) return null; // 绝对路径、UNC、设备路径、家目录
        String norm = r.replace('\\', '/');
        List<String> parts = new ArrayList<String>();
        for (String p : norm.split("/")) {
            if (p.length() == 0 || ".".equals(p)) continue;
            if ("..".equals(p) || p.matches("\\.{2,}") || p.endsWith(".") && p.length() > 1 && p.replace(".", "").length() == 0) return null;
            if (WIN_RESERVED.matcher(p).matches() || p.endsWith(" ") || p.endsWith(".")) return null;
            for (int i = 0; i < p.length(); i++) {
                char ch = p.charAt(i);
                if (ch < 0x20 || "<>\"|?*".indexOf(ch) >= 0) return null;
            }
            parts.add(p);
        }
        if (parts.isEmpty()) return null;
        try {
            File base = root.getCanonicalFile();
            File f = base;
            boolean missing = false;
            for (String p : parts) {
                f = new File(f, p);
                if (!missing && !f.exists()) {
                    // 不存在却在上级目录的列表里 = 悬空符号链接（getCanonicalFile 解不开它，写进去会落到工作区外）
                    String[] sib = f.getParentFile() == null ? null : f.getParentFile().list();
                    if (sib != null && Arrays.asList(sib).contains(p)) return null;
                    missing = true;
                }
            }
            File canon = f.getCanonicalFile();
            String b = base.getPath().endsWith(File.separator) ? base.getPath() : base.getPath() + File.separator;
            return canon.getPath().startsWith(b) ? canon : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 写工作区文件（会建上级目录）；越界 / 失败返回 null。写入前后各检查一次（防符号链接在中途换掉）。 */
    public static File writeInside(File root, String rel, byte[] data) throws IOException {
        File f = resolveInside(root, rel);
        if (f == null) return null;
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("mkdirs");
        if (resolveInside(root, rel) == null) return null;
        OutputStream o = new FileOutputStream(f);
        try {
            o.write(data);
        } finally {
            o.close();
        }
        return f;
    }

    // ================================================================== 生成脚本（不执行）

    public enum ScriptKind {
        SH(".sh"), PS1(".ps1"), BAT(".bat");
        public final String ext;

        ScriptKind(String ext) { this.ext = ext; }
    }

    /** 生成的脚本文件：路径（工作区里的相对路径）+ 内容。 */
    public static final class Script {
        public final String relPath, content;
        public final ScriptKind kind;

        Script(String relPath, String content, ScriptKind kind) {
            this.relPath = relPath;
            this.content = content;
            this.kind = kind;
        }
    }

    /** 头注释里的一行：去掉换行 / 控制字符，防止命令从注释里「逃出来」。 */
    static String oneLine(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            b.append(ch < 0x20 || ch == 0x7f || ch == 0x2028 || ch == 0x2029 ? ' ' : ch);
        }
        return b.toString().trim();
    }

    /** 命令正文：去掉 NUL 和 CR，统一换行（.bat 写盘时再转 CRLF）。 */
    static String body(String s) {
        return s == null ? "" : s.replace("\0", "").replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    /**
     * 组一份带中文头注释的脚本（说明它做什么、风险、怎么自己执行），不执行。
     * 手机：.sh（adb shell / Shizuku 里跑）；电脑：.ps1 / .bat。
     */
    public static Script buildScript(ScriptKind kind, String title, String what, String risk, String command, long nowMs) {
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date(nowMs));
        String slug = oneLine(title).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() == 0) slug = "task";
        if (slug.length() > 32) slug = slug.substring(0, 32);
        String rel = "scripts/" + ts + "-" + slug + kind.ext;
        String c = kind == ScriptKind.BAT ? "REM " : "# ";
        String nl = kind == ScriptKind.BAT ? "\r\n" : "\n";
        StringBuilder b = new StringBuilder();
        if (kind == ScriptKind.SH) b.append("#!/system/bin/sh").append(nl);
        if (kind == ScriptKind.BAT) b.append("@echo off").append(nl).append("chcp 65001 >nul").append(nl);
        b.append(c).append("由 OneSay 远程指令生成，没有执行。确认无误后需要你自己执行。").append(nl);
        b.append(c).append("生成时间：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(nowMs))).append(nl);
        b.append(c).append("事项：").append(oneLine(title)).append(nl);
        b.append(c).append("做什么：").append(oneLine(what)).append(nl);
        b.append(c).append("风险：").append(oneLine(risk)).append(nl);
        if (kind == ScriptKind.SH) b.append(c).append("怎么执行：电脑上 adb shell sh <本文件>，或在 Shizuku / 终端里执行；需要 root 的命令请自己判断。").append(nl);
        if (kind == ScriptKind.PS1) b.append(c).append("怎么执行：右键「用 PowerShell 运行」，或管理员 PowerShell 里 powershell -ExecutionPolicy Bypass -File <本文件>。").append(nl);
        if (kind == ScriptKind.BAT) b.append(c).append("怎么执行：右键「以管理员身份运行」。").append(nl);
        b.append(c).append("------------------------------------------------------------").append(nl);
        String cmd = body(command);
        if (kind == ScriptKind.BAT) cmd = cmd.replace("\n", "\r\n");
        b.append(cmd).append(nl);
        return new Script(rel, b.toString(), kind);
    }

    /** 生成并写进工作区 scripts/；返回相对路径，失败返回 null。.ps1 加 UTF-8 BOM（Windows PowerShell 5 才认中文注释）。 */
    public static String writeScript(File root, Script s) {
        try {
            byte[] text = s.content.getBytes("UTF-8");
            byte[] data = text;
            if (s.kind == ScriptKind.PS1) {
                data = new byte[text.length + 3];
                data[0] = (byte) 0xEF;
                data[1] = (byte) 0xBB;
                data[2] = (byte) 0xBF;
                System.arraycopy(text, 0, data, 3, text.length);
            }
            File f = writeInside(root, s.relPath, data);
            return f == null ? null : s.relPath;
        } catch (IOException e) {
            return null;
        }
    }

    /** 工作区 scripts/ 里已有的脚本（只列 .sh / .ps1 / .bat，按名字排序）。 */
    public static List<String> listScripts(File root) {
        List<String> out = new ArrayList<String>();
        File d = resolveInside(root, "scripts");
        String[] names = d == null ? null : d.list();
        if (names == null) return out;
        Arrays.sort(names);
        for (String n : names) {
            String l = n.toLowerCase(Locale.ROOT);
            if (l.endsWith(".sh") || l.endsWith(".ps1") || l.endsWith(".bat")) out.add(n);
        }
        return out;
    }

    // ================================================================== 原因码 → 中文

    public static String reasonZh(String code) {
        if (R_DENIED_BY_LEVEL.equals(code)) return "执行级别不允许";
        if (R_CONFIRM_TIMEOUT.equals(code)) return "对方 30 秒内没确认，已拒绝";
        if (R_CONFIRM_DENIED.equals(code)) return "对方点了拒绝";
        if (R_UNTRUSTED_URL.equals(code)) return "网址没通过安全检查";
        if (R_BLOCKED_APP.equals(code)) return "这类 App 不允许远程操作";
        if (R_CLIPBOARD_REJECTED.equals(code)) return "剪贴板内容没通过安全检查";
        if (R_SCRIPT_GENERATED.equals(code)) return "已生成脚本，需你自己执行";
        if (R_SYSTEM_APP.equals(code)) return "系统 App 不允许远程改动";
        if (R_OUTSIDE_WORKSPACE.equals(code)) return "只能写工作区里的文件";
        if (R_UNKNOWN.equals(code)) return "不认识的指令";
        return "";
    }

    // ================================================================== 小工具

    static boolean in(String s, String... xs) {
        for (String x : xs) if (x.equals(s)) return true;
        return false;
    }

    static String join(List<String> xs, String sep) {
        StringBuilder b = new StringBuilder();
        for (String x : xs) {
            if (b.length() > 0) b.append(sep);
            b.append(x);
        }
        return b.toString();
    }
}
