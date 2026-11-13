package com.onesay.desktop;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.IDN;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 *
 * <p>电脑上用户自己定义的快捷指令单独一类（{@link Cat#SHORTCUT}，不算「运行脚本」）：只认电脑快捷指令列表里有的、按稳定 id + 内容哈希对上；
 * 第一次从手机触发（或内容改过、哈希对不上）在电脑上问「允许 / 拒绝」（30 秒 = 拒绝），可选「以后直接执行」；这样批准过的在自动 / 完全访问下直接执行，
 * 只读拒绝。内容碰了严格规则（注册表 / 网络 / 服务 / 给自己授权，或看不出内容的编码命令）的快捷指令只能自己运行，不能批准远程直接执行。
 *
 * <p>确认框只认真人点击：被控端本机的鼠标 / 键盘，或（2026-11-10 起）已配对手机屏幕上确认卡的真人触摸。远程消息或语音想回答确认框
 * （{@link Cat#REMOTE_APPROVAL}：confirm / approve / allow / voice_approve…）任何档位都拒绝，原因码 {@link #R_VOICE_APPROVAL_BLOCKED}
 * （思路参考 Haris 审批卡「不能用说话批准」，MIT；代码自己写的）。
 *
 * <p><b>能批就不拦（2026-11-10 用户决定）</b>：批了也不行的硬规则只剩三条 ——
 * <ol>
 *   <li>付款 / 转账的最后一下必须用户亲手点：远程指令和机器人不能代点最终支付按钮（{@link Cat#PAY_FINAL}，{@link #R_PAY_USER_TAP}）；</li>
 *   <li>机器人不能改自己的权限、不能自己批自己：{@link Cat#SELF_AUTHORIZE}（{@link #R_SELF_GRANT_BLOCKED}）、{@link Cat#SELF_GRANT}
 *       （只生成脚本）、远程 / 语音批准（{@link Cat#REMOTE_APPROVAL}）、远程卸载 / 关掉 OneSay 自己；</li>
 *   <li>电脑不提权：要管理员的只生成 .ps1（{@link #R_NEEDS_ADMIN_SCRIPT}），想提权 / 绕 UAC 的拒绝（{@link #R_PLUGIN_BLOCKED}）。</li>
 * </ol>
 * 其余原来关不掉的拦截改成弹确认（{@link Verdict#CONFIRM}）：打开 / 卸载支付类与系统 App、有风险的网址（电脑上）、像命令的剪贴板（电脑上）、
 * 改工作区里的 .git 等。确认卡可以勾「以后这类不用问」，按动作类型（{@link Decision#kind}）记住（{@link #remembered}）；
 * 硬规则不是 CONFIRM，记住的类型绕不过去。手机作为被控端：网址里的 IP / 内网地址不再拒、剪贴板不再限 10 KB 和「像命令」、
 * 敏感内容 60 秒自动清除改成可选（默认关）。可关的插件项（编码 / 隐藏命令、沙箱断网）照旧，用户自己能关。
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

    /**
     * [link-v2 · 两轴] 沙箱档（Codex 的 sandbox mode）：能动什么。
     * <ul>
     *   <li>{@link #READ_ONLY} 只读：只回答查询，什么都不改。</li>
     *   <li>{@link #WORKSPACE_WRITE} 工作区可写（默认）：工作区里直接写；工作区外的写入、任意命令、跑工作区脚本在你确认后按你自己的权限执行
     *   （子进程关不进工作区，你知道这一点），命令默认断网；审批「从不」下这些直接拒绝。</li>
     *   <li>{@link #FULL_ACCESS} 完全访问：按这台电脑当前用户的权限来（仍然不提权、不碰需要管理员的东西）。</li>
     * </ul>
     */
    public enum Sandbox {
        READ_ONLY("read_only", "只读"), WORKSPACE_WRITE("workspace_write", "工作区可写"), FULL_ACCESS("full_access", "完全访问");
        public final String id, zh;

        Sandbox(String id, String zh) {
            this.id = id;
            this.zh = zh;
        }

        public static Sandbox of(String s) {
            if (s != null) for (Sandbox x : values()) if (x.id.equalsIgnoreCase(s.trim()) || x.zh.equals(s.trim())) return x;
            return WORKSPACE_WRITE;
        }
    }

    /**
     * [link-v2 · 两轴] 审批策略（Codex 的 approval policy）：什么时候问你。
     * <ul>
     *   <li>{@link #UNTRUSTED} 分类（默认）：只有「可信」的那一类（查询 / 无害 / 工作区内写）不问，其余都先问你。</li>
     *   <li>{@link #ON_REQUEST} 按需：沙箱档以内的直接做（不在信任列表的网址只提醒、写剪贴板不问），超出沙箱或会被注意到 / 有破坏性的
     *   （音量等设置、关机、卸载、跑脚本、执行命令）才问。</li>
     *   <li>{@link #NEVER} 从不：永不弹确认。沙箱档以内的照做；要升级的（工作区可写档写工作区外）直接拒绝；
     *   要确认的动作（关机、卸载、跑脚本、执行命令）只在「完全访问」下照做（Codex 的 danger-full-access + never），其余档拒绝。
     *   敏感剪贴板、未批准的快捷指令照样要人确认，所以「从不」下拒绝。</li>
     * </ul>
     */
    public enum Approval {
        UNTRUSTED("untrusted", "分类"), ON_REQUEST("on_request", "按需"), NEVER("never", "从不");
        public final String id, zh;

        Approval(String id, String zh) {
            this.id = id;
            this.zh = zh;
        }

        public static Approval of(String s) {
            if (s != null) for (Approval x : values()) if (x.id.equalsIgnoreCase(s.trim()) || x.zh.equals(s.trim())) return x;
            return UNTRUSTED;
        }
    }

    /** 两轴合起来的一档。旧配置里的三档（只读 / 自动 / 完全访问）照 {@link #ofLegacy} 映射过来，旧界面照 {@link #legacy} 显示。 */
    public static final class Mode {
        public final Sandbox sandbox;
        public final Approval approval;

        public Mode(Sandbox sandbox, Approval approval) {
            this.sandbox = sandbox == null ? Sandbox.WORKSPACE_WRITE : sandbox;
            this.approval = approval == null ? Approval.UNTRUSTED : approval;
        }

        public static final Mode DEFAULT = new Mode(Sandbox.WORKSPACE_WRITE, Approval.UNTRUSTED);

        public static Mode of(String sandbox, String approval) { return new Mode(Sandbox.of(sandbox), Approval.of(approval)); }

        /**
         * 旧的三档 → 两轴（向后兼容：旧配置读进来就是这三种组合）：只读 → 只读·分类；自动 → 工作区可写·分类；完全访问 → 完全访问·按需。
         * 旧档位下原来要确认的照样确认（音量 / 关机 / 卸载 / 跑脚本）；只有新规则明确放开的（完全访问档写工作区外、执行普通命令）按新规则来。
         */
        public static Mode ofLegacy(Level l) {
            if (l == Level.READ_ONLY) return new Mode(Sandbox.READ_ONLY, Approval.UNTRUSTED);
            if (l == Level.FULL) return new Mode(Sandbox.FULL_ACCESS, Approval.ON_REQUEST);
            return DEFAULT;
        }

        /** 两轴 → 最接近的旧档（给旧版手机 / 旧界面显示用，只看沙箱轴）。 */
        public Level legacy() {
            if (sandbox == Sandbox.READ_ONLY) return Level.READ_ONLY;
            return sandbox == Sandbox.FULL_ACCESS ? Level.FULL : Level.AUTO;
        }

        public String zh() { return sandbox.zh + " · " + approval.zh; }

        @Override public String toString() { return sandbox.id + "/" + approval.id; }

        @Override public boolean equals(Object o) {
            return o instanceof Mode && ((Mode) o).sandbox == sandbox && ((Mode) o).approval == approval;
        }

        @Override public int hashCode() { return sandbox.hashCode() * 31 + approval.hashCode(); }
    }

    public enum Target { PC, PHONE }

    public enum Verdict { ALLOW, CONFIRM, DENY, SCRIPT }

    /** 回给发令端的原因码（data.reason）。 */
    public static final String R_DENIED_BY_LEVEL = "denied_by_level", R_CONFIRM_TIMEOUT = "needs_confirm_timeout",
            R_CONFIRM_DENIED = "confirm_denied", R_UNTRUSTED_URL = "untrusted_url", R_BLOCKED_APP = "blocked_app",
            R_CLIPBOARD_REJECTED = "clipboard_rejected", R_SCRIPT_GENERATED = "script_generated", R_SYSTEM_APP = "system_app",
            R_OUTSIDE_WORKSPACE = "outside_workspace", R_UNKNOWN = "unknown_command",
            R_SHORTCUT_NEEDS_APPROVAL = "shortcut_needs_approval", R_SHORTCUT_CHANGED = "shortcut_changed",
            R_SHORTCUT_BLOCKED = "shortcut_category_blocked",
            /** 安全插件拦下的（提权、UAC 绕过、沙箱断网、看不出内容的编码命令…）。 */
            R_PLUGIN_BLOCKED = "plugin_blocked",
            /** 需要管理员：不执行，只生成 .ps1 脚本，由用户自己用管理员身份运行。 */
            R_NEEDS_ADMIN_SCRIPT = "needs_admin_script",
            /** 远程想给自己放权（改 OneSay 的策略 / 配置 / 白名单 / 插件开关）。 */
            R_SELF_GRANT_BLOCKED = "self_grant_blocked",
            /** 想用远程消息 / 语音回答确认框（批准只认电脑本机的鼠标 / 键盘点击）。 */
            R_VOICE_APPROVAL_BLOCKED = "voice_approval_blocked",
            /** 付款 / 转账的最后一下（最终支付按钮）只能用户亲手点：远程指令、机器人都不能代点，批了也不行。 */
            R_PAY_USER_TAP = "pay_needs_user_tap";

    /** 确认框多久没人点按拒绝（2026-11-10：30 秒 → 60 秒）；电脑和手机的确认卡同一个数。 */
    public static final long CONFIRM_TIMEOUT_MS = 60000;
    /** 确认框显示出来之后多久「允许」才算数（防误点）。 */
    public static final long CONFIRM_ARM_MS = 700;

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
        /** 电脑上用户自己定义的快捷指令（快捷指令列表里有的，按 id + 内容哈希批准）。和 RUN_WS_SCRIPT 分开。 */
        SHORTCUT,
        /** 任意 shell / su / Shizuku 命令（分类不出更具体的）。 */
        SHELL_EXEC,
        /** 给自己（或任何 App）授权：pm grant、appops、无障碍 / 通知监听、设备管理器、Shizuku 批准；电脑上的 ACL / Defender 排除 / 本地管理员组。 */
        SELF_GRANT,
        /** 远程给自己放权：改 OneSay 自己的策略 / 配置 / 信任列表 / 插件开关 / 关掉 OneSay。任何档位都拒绝。 */
        SELF_AUTHORIZE,
        /** 改动系统 App（卸载 / 停用 / 清数据）。 */
        SYSTEM_APP_OP,
        /** 远程 / 语音想回答被控端的确认框（允许 / 批准 / 以后直接执行）。任何档位都拒绝：批准只认被控端本机的点击。 */
        REMOTE_APPROVAL,
        /** 付款 / 转账的最后一下（最终支付 / 确认转账 / 提交订单并付款）。任何档位都拒绝，确认也不行：只能用户亲手点。 */
        PAY_FINAL,
        /** 链接下载（media_get）：电脑用 yt-dlp 把链接里的视频 / 音频下到下载文件夹。2026-11-11 新增，走「能批就不拦」（动作类型 pc.media_download）。 */
        MEDIA_DOWNLOAD,
        UNKNOWN
    }

    /** 判定需要的事实（信任检查结果等）；没有的字段保持默认。 */
    public static final class Facts {
        public UrlCheck url;
        public ClipCheck clip;
        public boolean appBlocked, appSystem;
        public String appLabel = "";
        public boolean insideWorkspace = true;
        /** 快捷指令：在电脑的快捷指令列表里；已批准（id 和内容哈希都对上）；批准过但内容变了（哈希对不上）。 */
        public boolean shortcutListed, shortcutApproved, shortcutChanged;
        /** 快捷指令内容碰了严格规则的原因；非空 = 只能自己运行，不能批准远程直接执行。 */
        public String shortcutBlocked = "";
        /** 快捷指令内容的 SHA-256（十六进制），批准时记下。 */
        public String shortcutHash = "";
        /** 安全插件的结论：非空 = 这条被插件拦下（原因码放 {@link #pluginReason}）。 */
        public String pluginBlocked = "", pluginReason = "";
        /** 写的是工作区里的只读目录（.git / .codex / .agents）。 */
        public boolean protectedPath;
        /** 要动的 App 就是 OneSay 自己（远程卸载 / 关掉 / 停用自己 = 改自己的权限，硬规则）。 */
        public boolean appSelf;
        /** 链接下载：电脑上的下载工具（yt-dlp / FFmpeg）还没装，这次要先装（确认卡的动作类型变成 pc.media_tool_install）。 */
        public boolean mediaToolsMissing;
        /** 链接下载：链接没过检查的原因（非空 = 拒绝，不弹卡）。 */
        public String mediaBad = "";
    }

    public static final class Decision {
        public final Verdict verdict;
        public final String reason;
        /** 给人看的一句话（中文）。 */
        public final String text;
        /** 完全访问下不确认、但要给用户提个醒（比如不在信任列表的网址）。 */
        public final boolean headsUp;
        /**
         * 动作类型（「以后这类不用问」按它记住）：只有 {@link Verdict#CONFIRM} 才有；空 = 这条不能记住（快捷指令另有按内容哈希的批准）。
         * 写法 {@code pc.shell_exec}、{@code phone.blocked_app.open}…，见 {@link #kindZh}。
         */
        public final String kind;
        /** 这条是因为「以后这类不用问」记住过才直接执行的。 */
        public final boolean remembered;

        Decision(Verdict v, String reason, String text, boolean headsUp) {
            this(v, reason, text, headsUp, "", false);
        }

        Decision(Verdict v, String reason, String text, boolean headsUp, String kind, boolean remembered) {
            this.verdict = v;
            this.reason = reason == null ? "" : reason;
            this.text = text == null ? "" : text;
            this.headsUp = headsUp;
            this.kind = v == Verdict.CONFIRM || remembered ? (kind == null ? "" : kind) : "";
            this.remembered = remembered;
        }

        static Decision allow() { return new Decision(Verdict.ALLOW, "", "", false); }
        static Decision heads(String text) { return new Decision(Verdict.ALLOW, "", text, true); }
        static Decision confirm(String text) { return new Decision(Verdict.CONFIRM, "", text, false); }
        static Decision confirm(String reason, String text) { return new Decision(Verdict.CONFIRM, reason, text, false); }
        static Decision ask(String text, String kind) { return new Decision(Verdict.CONFIRM, "", text, false, kind, false); }
        static Decision deny(String reason, String text) { return new Decision(Verdict.DENY, reason, text, false); }
        static Decision script(String text) { return new Decision(Verdict.SCRIPT, R_SCRIPT_GENERATED, text, false); }
        static Decision script(String reason, String text) { return new Decision(Verdict.SCRIPT, reason, text, false); }

        /** 确认卡上能不能给「以后这类不用问」：要确认、有动作类型。硬规则（拒绝 / 只生成脚本）永远不是。 */
        public boolean rememberable() { return verdict == Verdict.CONFIRM && kind.length() > 0 && knownKind(kind); }

        @Override public String toString() {
            return verdict + (reason.length() > 0 ? "/" + reason : "") + (headsUp ? "/heads-up" : "") + (kind.length() > 0 ? "/" + kind : "")
                    + (remembered ? "/remembered" : "");
        }
    }

    // ================================================================== 「以后这类不用问」

    /** 能记住的动作类型（键 → 给人看的说法）。不在这里的不收（远程塞进来的乱写的类型也不收）。 */
    private static final String[][] KINDS = {
            {"pc.setting", "电脑：改音量 / 亮度 / 勿扰"}, {"pc.power", "电脑：关机 / 睡眠"}, {"pc.uninstall", "电脑：卸载程序"},
            {"pc.script_run", "电脑：运行工作区脚本"}, {"pc.shell_exec", "电脑：执行命令"}, {"pc.outside_write", "电脑：写工作区以外的文件"},
            {"pc.url", "电脑：打开不在信任列表的网址"}, {"pc.url.risky", "电脑：打开有风险的网址（IP / 内网 / 带账号 / 疑似仿冒）"},
            {"pc.clipboard", "电脑：写剪贴板"}, {"pc.clipboard.command", "电脑：写像命令 / 带特殊链接的剪贴板"}, {"pc.clipboard.sensitive", "电脑：写敏感内容到剪贴板"},
            {"pc.blocked_app.open", "电脑：打开支付 / 银行 / 密码 / 商店类程序"}, {"pc.blocked_app.uninstall", "电脑：卸载支付 / 银行 / 密码 / 商店类程序"},
            {"pc.blocked_app.operate", "电脑：关掉支付 / 银行 / 密码 / 商店类程序"}, {"pc.system_app", "电脑：改动系统程序"},
            {"pc.workspace.protected", "电脑：改工作区里的 .git / .codex / .agents"},
            {"phone.blocked_app.open", "手机：打开支付 / 银行 / 钱包 / 商店类 App"}, {"phone.blocked_app.uninstall", "手机：卸载支付 / 银行 / 钱包 / 商店类 App"},
            {"phone.system_app", "手机：卸载 / 停用系统 App"}, {"phone.url.risky", "手机：打开带账号密码 / 疑似仿冒的网址"},
            {"pc.media_download", "电脑：下载链接里的视频 / 音频"}, {"pc.media_tool_install", "电脑：第一次安装下载工具（yt-dlp / FFmpeg）"}};

    public static boolean knownKind(String k) {
        if (k == null) return false;
        for (String[] x : KINDS) if (x[0].equals(k)) return true;
        return false;
    }

    public static String kindZh(String k) {
        if (k != null) for (String[] x : KINDS) if (x[0].equals(k)) return x[1];
        return k == null ? "" : k;
    }

    /** 记住的类型存成一行：逗号分隔；不认识的、重复的丢掉。 */
    public static List<String> parseKinds(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) return out;
        for (String p : text.split("[,，;；\\s]+")) {
            String k = p.trim().toLowerCase(Locale.ROOT);
            if (knownKind(k) && !out.contains(k)) out.add(k);
        }
        return out;
    }

    public static String formatKinds(java.util.Collection<String> kinds) {
        StringBuilder b = new StringBuilder();
        if (kinds != null) for (String k : parseKinds(join(new ArrayList<String>(kinds), ","))) {
            if (b.length() > 0) b.append(',');
            b.append(k);
        }
        return b.toString();
    }

    /**
     * 「以后这类不用问」：要确认、且这类动作记住过 → 直接执行（带提醒）。其余原样返回 —— 拒绝 / 只生成脚本（三条硬规则）不是 CONFIRM，
     * 所以记住什么都绕不过它们；快捷指令的确认没有类型（另按内容哈希批准），也不受影响。
     */
    public static Decision remembered(Decision d, java.util.Collection<String> kinds) {
        if (d == null || !d.rememberable() || kinds == null || !kinds.contains(d.kind)) return d;
        return new Decision(Verdict.ALLOW, "", "按「以后这类不用问」直接执行：" + d.text, true, d.kind, true);
    }

    // ================================================================== 矩阵

    /** 旧调用口（三档）：映射成两轴再判。 */
    public static Decision decide(Target t, Level l, Cat c, Facts f) {
        return decide(t, Mode.ofLegacy(l == null ? DEFAULT_LEVEL : l), c, f);
    }

    /**
     * 核心判定：被控端 × （沙箱档 × 审批策略）× 类别（+ 信任检查与插件事实）→ 允许 / 先确认 / 拒绝 / 只生成脚本。
     *
     * <p>顺序：三条硬规则（付款最后一下、自授权 / 自批准、不提权）→ 插件结论 → 只读档 → 原来关不掉、现在走审批的（系统 App、禁止类 App、
     * 有风险的网址、像命令的剪贴板、工作区只读目录）→ 快捷指令 → 两轴矩阵。
     * 手机作为被控端不走两轴（见 {@link #phoneLegacy}：分档之前的语义，只看手机上的逐项开关，危险的弹手机确认卡）。
     */
    public static Decision decide(Target t, Mode mode, Cat c, Facts f) {
        if (f == null) f = new Facts();
        if (mode == null) mode = Mode.DEFAULT;
        if (c == null) c = Cat.UNKNOWN;
        // ---- 硬规则 ①②：任何档位、两种被控端都一样，确认 / 记住都不行
        if (c == Cat.REMOTE_APPROVAL) return denyRemoteApproval();
        if (c == Cat.PAY_FINAL) return denyPay();
        if (t == Target.PHONE) return phoneLegacy(c, f);

        // ---- 安全插件：任何档位都先过它（插件自己出错时调用方传 pluginBlocked，这里一律拒）
        if (f.pluginBlocked != null && f.pluginBlocked.length() > 0) {
            String code = f.pluginReason == null || f.pluginReason.length() == 0 ? R_PLUGIN_BLOCKED : f.pluginReason;
            if (R_NEEDS_ADMIN_SCRIPT.equals(code)) return Decision.script(code, f.pluginBlocked);
            return Decision.deny(code, f.pluginBlocked);
        }

        Sandbox sb = mode.sandbox;
        Approval ap = mode.approval;
        boolean ro = sb == Sandbox.READ_ONLY, full = sb == Sandbox.FULL_ACCESS;

        // ---- 硬规则 ②③：任何档位都一样（不是 CONFIRM，确认 / 「以后这类不用问」都绕不过）
        if (c == Cat.SELF_AUTHORIZE)
            return Decision.deny(R_SELF_GRANT_BLOCKED, "远程不能改 OneSay 自己的策略 / 配置 / 信任列表 / 插件开关，只能你在电脑上改");
        if (c == Cat.SELF_GRANT) return Decision.script(R_NEEDS_ADMIN_SCRIPT, "涉及授予权限，只生成脚本，需要你自己执行");
        if (f.appSelf && (c == Cat.UNINSTALL_APP || c == Cat.APP_OPERATE || c == Cat.SYSTEM_APP_OP))
            return Decision.deny(R_SELF_GRANT_BLOCKED, "远程不能卸载 / 关掉 OneSay 自己");
        if (c == Cat.UNKNOWN) return Decision.deny(R_UNKNOWN, "不认识的指令");
        if (c == Cat.QUERY) return Decision.allow();
        // 需要管理员的（注册表 HKLM / 服务驱动 / 防火墙 / 系统级网络…）：任何档位都只生成脚本，从不执行
        if (c == Cat.SYSTEM_A || c == Cat.NET_SEC_SETTING)
            return Decision.script(R_NEEDS_ADMIN_SCRIPT, "这件事需要管理员，OneSay 不提权：只生成 .ps1 脚本，需要你自己用管理员身份执行");
        // 只读档：查询以外一律不做（和以前一样回 denied_by_level，不管后面的信任检查）
        if (ro) return denyBySandbox(sb, "这条指令（只能查状态）");

        // 操作第三方程序只在完全访问档（沙箱档是你自己选的，不是硬规则）
        if (c == Cat.APP_OPERATE && !full) return Decision.deny(R_DENIED_BY_LEVEL, "沙箱档是「工作区可写」，不操作第三方程序（要完全访问）");

        // ---- 原来关不掉、2026-11-10 起改成走审批的（「从不询问」下照旧拒绝，原因码不变）
        if (c == Cat.SYSTEM_APP_OP || ((c == Cat.UNINSTALL_APP || c == Cat.APP_OPERATE) && f.appSystem))
            return askOrDenyCode(ap, R_SYSTEM_APP, "改动系统程序" + appName(f) + "（卸载 / 关掉）", "pc.system_app");
        if ((c == Cat.OPEN_APP || c == Cat.UNINSTALL_APP || c == Cat.APP_OPERATE) && f.appBlocked)
            return askOrDenyCode(ap, R_BLOCKED_APP, (c == Cat.OPEN_APP ? "打开" : c == Cat.UNINSTALL_APP ? "卸载" : "关掉") + "支付 / 银行 / 密码 / 钱包 / 应用商店 / 安装器类程序"
                    + appName(f) + "（付款最后一下仍要你亲手点）", c == Cat.OPEN_APP ? "pc.blocked_app.open" : c == Cat.UNINSTALL_APP ? "pc.blocked_app.uninstall" : "pc.blocked_app.operate");

        // ---- 信任检查（网址 / 剪贴板）
        if (c == Cat.OPEN_URL) {
            UrlCheck u = f.url;
            if (u == null || u.malformed) return Decision.deny(R_UNTRUSTED_URL, u == null ? "网址没过检查" : u.why);
            if (!u.ok) return askOrDenyCode(ap, R_UNTRUSTED_URL, "打开有风险的网址：" + u.why, "pc.url.risky");
            if (u.trusted) return Decision.allow();
            if (ap == Approval.UNTRUSTED) return Decision.ask("打开网址：" + u.shown, "pc.url");
            return Decision.heads("打开不在信任列表的网址：" + u.shown);
        }
        if (c == Cat.CLIPBOARD_WRITE) {
            ClipCheck k = f.clip;
            if (k == null || k.verdict == ClipCheck.REJECT) return Decision.deny(R_CLIPBOARD_REJECTED, k == null ? "剪贴板内容没过检查" : k.why);
            // 敏感内容任何档位都要人确认（「从不询问」下就是拒绝）
            if (k.verdict == ClipCheck.SENSITIVE) return askOrDeny(ap, "写入敏感内容（" + k.why + "）", "pc.clipboard.sensitive");
            // 像命令 / 带特殊链接的：以前直接拒，现在问你（「从不询问」下拒绝）
            if (k.note.length() > 0) return askOrDenyCode(ap, R_CLIPBOARD_REJECTED, "写剪贴板（" + k.chars + " 字，" + k.note + "）", "pc.clipboard.command");
            return ap == Approval.UNTRUSTED ? Decision.ask("写剪贴板（" + k.chars + " 字）", "pc.clipboard") : Decision.allow();
        }

        // ---- 快捷指令：列表里有 → 内容没碰严格规则 → 按 id + 哈希批准过就直接执行，否则先问（批准一定要人点，「从不询问」下拒绝）
        if (c == Cat.SHORTCUT) {
            if (!f.shortcutListed) return Decision.deny(R_UNKNOWN, "电脑的快捷指令列表里没有这个");
            if (f.shortcutBlocked != null && f.shortcutBlocked.length() > 0)
                return Decision.deny(R_SHORTCUT_BLOCKED, "快捷指令" + f.shortcutBlocked + "，只能你在电脑上自己运行，不能批准远程直接执行");
            if (f.shortcutApproved) return Decision.allow();
            if (f.shortcutChanged) return askReason(ap, R_SHORTCUT_CHANGED, "快捷指令内容改过了，需要重新批准");
            return askReason(ap, R_SHORTCUT_NEEDS_APPROVAL, "第一次从手机运行这个快捷指令，需要你批准");
        }

        // ---- 链接下载（2026-11-11）：链接不对直接拒；工具没装先问装不装（这次也一起下）；装好了按 media_download 问。
        // 和关机 / 卸载一样：分类 / 按需 → 弹确认卡（可勾「以后这类不用问」）；从不询问 → 只有完全访问直接跑，其余拒。只读档在上面已经拒了。
        if (c == Cat.MEDIA_DOWNLOAD) {
            if (f.mediaBad != null && f.mediaBad.length() > 0) return Decision.deny(R_UNTRUSTED_URL, f.mediaBad);
            if (f.mediaToolsMissing)
                return needsApproval(sb, ap, "第一次用链接下载：先从官方发布页下载 yt-dlp 和 FFmpeg（LGPL 构建，核对 SHA-256 后才运行），再下载这个链接", "pc.media_tool_install");
            return needsApproval(sb, ap, "下载链接里的视频 / 音频到电脑的下载文件夹", "pc.media_download");
        }

        // ---- 两轴矩阵
        switch (c) {
            case READ_PRIVATE:
            case HARMLESS:
            case OPEN_APP:
                return Decision.allow();
            case WORKSPACE_WRITE:
                // 工作区里的 .git / .codex / .agents：以前直接拒，现在问你（「从不询问」下拒绝）
                if (f.protectedPath) return askOrDenyCode(ap, R_PLUGIN_BLOCKED, "改工作区里的 .git / .codex / .agents（平时只读）", "pc.workspace.protected");
                return Decision.allow();
            case SIMPLE_SETTING:
                // 音量 / 亮度这类看得见的改动：分类和按需都先问（和以前一样）；从不询问时只有完全访问照做
                return needsApproval(sb, ap, "电脑设置改动需要确认", "pc.setting");
            // 工作区可写档（2026-11-08 用户决定）：命令、跑工作区脚本、工作区外写入改成「确认后按你自己的权限执行」——
            // 你知道子进程关不进工作区（Windows 上 Java 做不了系统级沙箱），所以不再只生成脚本；审批策略照常：
            // 分类 / 按需 → 电脑上确认，从不询问 → 拒绝（不升级）。安全插件在这之前已经过了（提权 / 要管理员 / 自授权 / 编码 / 默认断网）。
            // 操作第三方程序仍然只在完全访问档下做。
            case APP_OPERATE:
                return full ? Decision.allow() : Decision.deny(R_DENIED_BY_LEVEL, "沙箱档是「工作区可写」，不操作第三方程序（要完全访问）");
            case OUTSIDE_WRITE:
                return full ? Decision.allow() : askOrDeny(ap, "写工作区以外的文件（按你自己的权限写，Windows 权限为准）", "pc.outside_write");
            case POWER:
                return needsApproval(sb, ap, "关机 / 睡眠需要确认", "pc.power");
            case UNINSTALL_APP:
                return needsApproval(sb, ap, "卸载程序需要确认", "pc.uninstall");
            case RUN_WS_SCRIPT:
                return needsApproval(sb, ap, "运行工作区脚本（每次都要确认，按你自己的权限执行）", "pc.script_run");
            case SHELL_EXEC:
                return needsApproval(sb, ap, "执行命令（会显示完整命令，按你自己的权限执行）", "pc.shell_exec");
            default:
                return Decision.deny(R_UNKNOWN, "不认识的指令");
        }
    }

    /** 要升级 / 要人确认的一条：「从不询问」直接拒（和 Codex 一样不升级），否则弹确认（kind = 动作类型，可记住）。 */
    static Decision askOrDeny(Approval ap, String text, String kind) {
        return ap == Approval.NEVER ? denyNever(text) : Decision.ask(text, kind);
    }

    /** 同上，「从不询问」下拒绝时用原来的原因码（以前就是这个原因码直接拒的）。 */
    static Decision askOrDenyCode(Approval ap, String code, String text, String kind) {
        return ap == Approval.NEVER ? Decision.deny(code, "审批策略是「从不询问」，需要确认的事直接拒绝：" + text) : Decision.ask(text, kind);
    }

    /** 有破坏性 / 要每次确认的动作（关机、卸载、跑脚本、执行命令）：按需 / 分类 → 确认；从不询问 → 只有完全访问照做，其余拒。 */
    static Decision needsApproval(Sandbox sb, Approval ap, String text, String kind) {
        if (ap != Approval.NEVER) return Decision.ask(text, kind);
        return sb == Sandbox.FULL_ACCESS ? Decision.allow() : denyNever(text);
    }

    static String appName(Facts f) {
        return f.appLabel == null || f.appLabel.length() == 0 ? "" : "「" + oneLine(f.appLabel) + "」";
    }

    static Decision denyPay() {
        return Decision.deny(R_PAY_USER_TAP, "付款 / 转账的最后一下只能你自己在屏幕上点，远程指令和机器人都不能代点（批了也不行）");
    }

    static Decision askReason(Approval ap, String reason, String text) {
        return ap == Approval.NEVER ? Decision.deny(reason, "审批策略是「从不询问」：" + text + "，已拒绝") : Decision.confirm(reason, text);
    }

    static Decision denyRemoteApproval() {
        return Decision.deny(R_VOICE_APPROVAL_BLOCKED, "确认框只认被控端本机的鼠标 / 键盘点击，远程消息和语音不能批准");
    }

    static Decision denyNever(String what) {
        return Decision.deny(R_DENIED_BY_LEVEL, "审批策略是「从不询问」，需要确认的事直接拒绝：" + what);
    }

    static Decision denyBySandbox(Sandbox sb, String what) {
        return Decision.deny(R_DENIED_BY_LEVEL, "当前沙箱档是「" + sb.zh + "」，不做：" + what);
    }

    /**
     * 手机作为被控端：分档之前的语义 —— 不分档，只看手机控制台里的逐项开关（个人版默认全开；开源版「接受电脑指令」总开关和加回来的 8 项默认关）。
     * 硬规则（任何时候都不行）：付款最后一下、远程 / 语音批准（在 {@link #decide} 里先判）、远程改 OneSay 自己的设置、给 App 授权（只生成脚本）、
     * 远程卸载 / 停用 OneSay 自己。2026-11-10 起原来拒绝的改成在手机上弹确认卡（{@link Verdict#CONFIRM}，可勾「以后这类不用问」）：
     * 打开 / 卸载支付 / 银行 / 钱包 / 商店类 App、卸载 / 停用系统 App、带账号密码或疑似仿冒的网址。
     * 回到分档之前（round2 之前）：网址里的 IP / 内网地址不拒；剪贴板不限 10 KB、不按「像命令」拒（敏感内容照旧标敏感，60 秒清除是可选项）。
     * 没检查过的事实（url / clip 为 null）按「没有这项检查」处理。
     */
    static Decision phoneLegacy(Cat c, Facts f) {
        if (c == Cat.REMOTE_APPROVAL) return denyRemoteApproval();
        if (c == Cat.PAY_FINAL) return denyPay();
        if (c == Cat.SELF_AUTHORIZE) return Decision.deny(R_SELF_GRANT_BLOCKED, "远程不能改 OneSay 自己的设置");
        if (c == Cat.SELF_GRANT) return Decision.script("涉及授予权限，只生成脚本，需要你自己执行");
        if (c == Cat.UNKNOWN) return Decision.deny(R_UNKNOWN, "不认识的指令");
        if (f.appSelf && (c == Cat.UNINSTALL_APP || c == Cat.APP_OPERATE || c == Cat.SYSTEM_APP_OP))
            return Decision.deny(R_SELF_GRANT_BLOCKED, "远程不能卸载 / 停用 OneSay 自己");
        if (c == Cat.SYSTEM_APP_OP || ((c == Cat.UNINSTALL_APP || c == Cat.APP_OPERATE) && f.appSystem))
            return Decision.ask("卸载 / 停用系统 App" + appName(f) + "，可能影响手机正常使用", "phone.system_app");
        if ((c == Cat.OPEN_APP || c == Cat.UNINSTALL_APP || c == Cat.APP_OPERATE) && f.appBlocked)
            return Decision.ask((c == Cat.OPEN_APP ? "打开" : "卸载") + "支付 / 银行 / 钱包 / 商店类 App" + appName(f) + "（付款最后一下仍要你亲手点）",
                    c == Cat.OPEN_APP ? "phone.blocked_app.open" : "phone.blocked_app.uninstall");
        if (c == Cat.OPEN_URL && f.url != null && !f.url.ok) {
            if (f.url.malformed) return Decision.deny(R_UNTRUSTED_URL, f.url.why);
            if (UrlCheck.RISK_IP.equals(f.url.risk) || UrlCheck.RISK_PRIVATE.equals(f.url.risk)) return Decision.allow(); // 分档之前就不拦（家里 NAS、路由器）
            return Decision.ask("打开网址：" + f.url.why, "phone.url.risky");
        }
        if (c == Cat.CLIPBOARD_WRITE && f.clip != null && f.clip.verdict == ClipCheck.REJECT) return Decision.deny(R_CLIPBOARD_REJECTED, f.clip.why);
        return Decision.allow();
    }

    // ================================================================== 指令 → 类别

    /** 手机作为被控端：电脑发来的指令名 → 类别（shell 命令另走 {@link #classifyShell}）。 */
    public static Cat phoneCat(String cmd) {
        if (cmd == null) return Cat.UNKNOWN;
        if (isRemoteApproval(cmd)) return Cat.REMOTE_APPROVAL;
        if (isPayFinal(cmd)) return Cat.PAY_FINAL;
        if (isSelfAuthorizeCmd(cmd)) return Cat.SELF_AUTHORIZE;
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
        if (isRemoteApproval(cmd)) return Cat.REMOTE_APPROVAL;
        if (isPayFinal(cmd)) return Cat.PAY_FINAL;
        if (in(cmd, "ping", "caps", "info", "shortcut_list", "level", "media_status")) return Cat.QUERY;
        if (in(cmd, "screenshot", "clipboard_get", "file_get", "file_list", "script_list", "media_fetch")) return Cat.READ_PRIVATE;
        if (in(cmd, "media", "lock", "notify", "memory_sync", "echo", "media_cancel")) return Cat.HARMLESS;
        if ("media_get".equals(cmd)) return Cat.MEDIA_DOWNLOAD;
        if (in(cmd, "volume", "brightness", "dnd")) return Cat.SIMPLE_SETTING;
        if (in(cmd, "shutdown", "sleep", "shutdown_abort")) return Cat.POWER;
        if ("open_url".equals(cmd)) return Cat.OPEN_URL;
        if ("open_app".equals(cmd)) return Cat.OPEN_APP;
        if ("clipboard_set".equals(cmd)) return Cat.CLIPBOARD_WRITE;
        if ("file_write".equals(cmd)) return Cat.WORKSPACE_WRITE;
        if ("uninstall_app".equals(cmd)) return Cat.UNINSTALL_APP;
        if ("app_close".equals(cmd)) return Cat.APP_OPERATE;
        if ("script_run".equals(cmd)) return Cat.RUN_WS_SCRIPT;
        if ("shortcut_run".equals(cmd)) return Cat.SHORTCUT;
        if ("sys_exec".equals(cmd)) return Cat.SHELL_EXEC;
        // 远程想改电脑端自己的设置（档位 / 插件 / 信任列表 / 白名单 / 批准表）：一律 self_grant_blocked，只能在电脑界面上改
        if (isSelfAuthorizeCmd(cmd)) return Cat.SELF_AUTHORIZE;
        return Cat.UNKNOWN;
    }

    /** 远程想改被控端 OneSay 自己的设置（档位 / 插件 / 信任列表 / 白名单 / 批准表 / 记住的类型 / 剪贴板清除）：一律 self_grant_blocked。 */
    public static boolean isSelfAuthorizeCmd(String cmd) {
        String c = cmd == null ? "" : cmd.trim().toLowerCase(Locale.ROOT);
        return in(c, "settings", "settings_set", "config_set", "pc_level", "level_set", "set_level", "sandbox_set", "approval_set", "plugin_set", "plugin_off",
                "trust_add", "trusted_add", "net_allow_add", "shortcut_approve", "policy_set", "remember_type", "remember_kind", "remembered_add", "approve_type",
                "approve_types", "clip_autoclear", "rx_enable", "rx_set", "cmd_enable", "switch_set");
    }

    /**
     * 远程想完成付款 / 转账最后一下的指令名（硬规则 ①）：任何档位都拒绝，确认 / 记住都不行（{@link #R_PAY_USER_TAP}）。
     * 现有指令里没有能点屏幕的；这条是给以后加指令时的兜底：名字像「付款 / 转账 / 提交订单」的一律不收。
     */
    public static boolean isPayFinal(String cmd) {
        String c = cmd == null ? "" : cmd.trim().toLowerCase(Locale.ROOT);
        return in(c, "pay", "pay_confirm", "confirm_pay", "pay_final", "pay_now", "payment", "payment_confirm", "transfer", "transfer_confirm", "send_money",
                "checkout", "checkout_confirm", "place_order", "submit_order", "order_pay", "tap_pay", "click_pay");
    }

    /** 屏幕上一个按钮的字是不是付款 / 转账的最后一下（给机器人点屏幕的地方用：这种按钮只能你亲手点）。 */
    public static boolean finalPayLabel(String label) {
        if (label == null) return false;
        String t = label.replaceAll("[\\s\u00a0\u200b]+", "").toLowerCase(Locale.ROOT);
        if (t.length() == 0 || t.length() > 24) return false;
        return FINAL_PAY.matcher(t).find();
    }

    private static final Pattern FINAL_PAY = Pattern.compile(
            "(确认|立即|马上|去)(支付|付款|转账)|提交订单并?支付|确认转账|免密支付|指纹支付|面容支付|刷脸支付|输入支付密码|(支付|付款)[¥￥$]?\\d|"
                    + "^(支付|付款|转账)$|paynow|confirmandpay|confirmpayment|placeorder|completepurchase|sendmoney|confirmtransfer");

    /**
     * 远程想回答确认框的指令名（确认 / 批准 / 允许 / 语音批准…）。确认框只认被控端本机的鼠标 / 键盘点击，这些一律
     * {@link #R_VOICE_APPROVAL_BLOCKED}（思路参考 Haris 审批卡「不能用说话批准」，MIT；代码自己写的）。
     */
    public static boolean isRemoteApproval(String cmd) {
        String c = cmd == null ? "" : cmd.trim().toLowerCase(Locale.ROOT);
        return in(c, "confirm", "confirm_reply", "confirm_answer", "confirm_allow", "confirm_always", "answer_confirm", "approve", "approval",
                "allow", "allow_once", "allow_always", "voice_confirm", "voice_approve", "voice_allow", "voice_approval", "always_allow");
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
        if (t == Target.PC) {
            String n = normalizeCommand(s);
            if (selfAuthorizeReason(n, null).length() > 0) return Cat.SELF_AUTHORIZE;
            if (SELF_GRANT.matcher(n).find()) return Cat.SELF_GRANT;
            if (adminReason(n).length() > 0 || hkcuElevationReason(n).length() > 0) return Cat.SYSTEM_A;
            return Cat.SHELL_EXEC;
        }
        if (SELF_GRANT.matcher(s).find()) return Cat.SELF_GRANT;
        if (SYS_APP_OP.matcher(s).find()) return Cat.SHELL_EXEC; // 卸载 / 停用要走 uninstall_app（系统卸载界面），shell 形式按系统命令处理
        // 多条命令拼接的（; && | 反引号 $()）一律按任意命令处理，不按其中一段放宽
        if (s.matches("(?s).*(;|&&|\\|\\||\\||`|\\$\\(|\\n|>|<).*")) return Cat.SHELL_EXEC;
        if (PHONE_SIMPLE.matcher(s).find()) return Cat.SIMPLE_SETTING;
        if (PHONE_NETSEC.matcher(s).find()) return Cat.NET_SEC_SETTING;
        return Cat.SHELL_EXEC;
    }

    // ================================================================== 快捷指令（电脑上用户自己定义的）

    /** 快捷指令的稳定 id：文件名（含扩展名）转小写。改名 = 新快捷指令，要重新批准。 */
    public static String shortcutId(String fileName) {
        String n = fileName == null ? "" : fileName.trim();
        int k = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        return (k >= 0 ? n.substring(k + 1) : n).toLowerCase(Locale.ROOT);
    }

    public static String sha256Hex(byte[] data) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(data == null ? new byte[0] : data);
            StringBuilder b = new StringBuilder();
            for (byte x : h) b.append(String.format(Locale.ROOT, "%02x", x & 0xff));
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256", e);
        }
    }

    /** 脚本文件字节 → 文字：认 UTF-8 / UTF-16（带 BOM，或看起来是 UTF-16LE 的）；其余按 UTF-8（GBK 的 .bat 里英文关键字照样认得出）。 */
    public static String decodeScript(byte[] b) {
        if (b == null) return "";
        try {
            if (b.length >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF) return new String(b, 3, b.length - 3, "UTF-8");
            if (b.length >= 2 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xFE) return new String(b, 2, b.length - 2, "UTF-16LE");
            if (b.length >= 2 && (b[0] & 0xff) == 0xFE && (b[1] & 0xff) == 0xFF) return new String(b, 2, b.length - 2, "UTF-16BE");
            int zeroOdd = 0;
            for (int i = 1; i < b.length; i += 2) if (b[i] == 0) zeroOdd++;
            if (b.length >= 4 && zeroOdd * 4 >= b.length) return new String(b, "UTF-16LE");
            return new String(b, "UTF-8");
        } catch (Exception e) {
            return new String(b);
        }
    }

    /** 看不出真实内容的写法（编码命令、动态执行），没法按严格规则检查。 */
    private static final Pattern OPAQUE = Pattern.compile(
            "(-(e|ec|enc|encodedcommand)\\s+[A-Za-z0-9+/=]{16,})|(\\bFromBase64String\\b)|(\\bInvoke-Expression\\b)|(\\biex\\b)",
            Pattern.CASE_INSENSITIVE);

    private static String firstHit(Pattern p, String s) {
        Matcher m = p.matcher(s);
        if (!m.find()) return null;
        String h = oneLine(m.group());
        return h.length() > 40 ? h.substring(0, 40) + "…" : h;
    }

    /**
     * 快捷指令内容检查（定义时就判，内容一改重新判）：**只拦普通用户做不到或能影响提权的事** ——
     * 想提权 / 绕 UAC、需要管理员（HKLM / 服务驱动 / 防火墙 / 系统级网络 / 计划任务系统级 / bcdedit / diskpart / 格式化 / vssadmin…）、
     * 能影响提权的 HKCU 键（Classes 下的 shell\open\command 劫持、Environment 的 windir / COR_PROFILER、Policies）、
     * 想改 OneSay 自己的设置、给自己授权（ACL / Defender 排除 / 本地管理员组，沿用之前的红线）、以及看不出内容的编码 / 动态执行（{@link Rules#encoded}，可单独关）。
     * 普通用户自己能做的（HKCU 普通键、删自己的文件、启动程序、复制文件…）一律放行。
     *
     * <p>先按文件类型剥掉注释（.ps1 的 {@code #} 与 {@code <# #>}、.bat 的 REM 与 {@code ::}）再匹配，注释里写到的词不算。
     * 都没碰返回空串。
     */
    public static String shortcutBodyBlocked(String body, String fileName, Rules rules) {
        Rules r = rules == null ? new Rules() : rules;
        boolean bat = fileName != null && fileName.toLowerCase(Locale.ROOT).matches(".*\\.(bat|cmd)$");
        String code = normalizeCommand(stripComments(body, bat));
        String h = elevationReason(code);
        if (h.length() > 0) return "内容" + h;
        h = selfAuthorizeReason(code, r.selfPaths);
        if (h.length() > 0) return "内容" + h;
        h = firstHit(SELF_GRANT, code);
        if (h != null) return "内容涉及授予权限（" + h + "）";
        h = adminReason(code);
        if (h.length() > 0) return "内容" + h;
        h = hkcuElevationReason(code);
        if (h.length() > 0) return "内容" + h;
        if (r.encoded) {
            h = opaqueReason(code);
            if (h.length() > 0) return "内容" + h;
        }
        return "";
    }

    /** 老调用口（按 .ps1 处理、默认规则）。 */
    public static String shortcutBodyBlocked(String body) {
        return shortcutBodyBlocked(body, "x.ps1", null);
    }

    /**
     * 一条快捷指令触发时的判定事实：listed = 在电脑快捷指令列表里；内容哈希和批准表（id → 哈希）比：相同 = 已批准，不同 = 内容变了；
     * 内容碰严格规则 = blocked（不管批准表里有没有）。
     */
    public static Facts shortcutFacts(String id, byte[] content, Map<String, String> approvals, boolean listed) {
        return shortcutFacts(id, content, approvals, listed, null);
    }

    public static Facts shortcutFacts(String id, byte[] content, Map<String, String> approvals, boolean listed, Rules rules) {
        Facts f = new Facts();
        f.shortcutListed = listed && content != null && id != null && id.length() > 0;
        if (!f.shortcutListed) return f;
        f.shortcutHash = sha256Hex(content);
        f.shortcutBlocked = shortcutBodyBlocked(decodeScript(content), id, rules);
        String ok = approvals == null ? null : approvals.get(shortcutId(id));
        f.shortcutApproved = ok != null && ok.equalsIgnoreCase(f.shortcutHash) && f.shortcutBlocked.length() == 0;
        f.shortcutChanged = ok != null && !ok.equalsIgnoreCase(f.shortcutHash);
        return f;
    }

    /** 批准表存成一行：id=哈希,id=哈希（id 做 URL 编码，防文件名里的 , = ）。坏的项跳过。 */
    public static Map<String, String> parseApprovals(String text) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (text == null) return out;
        for (String part : text.split(",")) {
            int eq = part.lastIndexOf('=');
            if (eq <= 0) continue;
            String h = part.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
            if (!h.matches("[0-9a-f]{64}")) continue;
            try {
                String id = shortcutId(URLDecoder.decode(part.substring(0, eq).trim(), "UTF-8"));
                if (id.length() > 0) out.put(id, h);
            } catch (Exception ignored) {
                // 坏的项跳过
            }
        }
        return out;
    }

    public static String formatApprovals(Map<String, String> m) {
        StringBuilder b = new StringBuilder();
        if (m == null) return "";
        for (Map.Entry<String, String> e : m.entrySet()) {
            String h = e.getValue() == null ? "" : e.getValue().toLowerCase(Locale.ROOT);
            if (!h.matches("[0-9a-f]{64}")) continue;
            try {
                if (b.length() > 0) b.append(',');
                b.append(URLEncoder.encode(shortcutId(e.getKey()), "UTF-8")).append('=').append(h);
            } catch (Exception ignored) {
                // UTF-8 一定有
            }
        }
        return b.toString();
    }

    // ================================================================== 安全插件规则（电脑端）

    /**
     * [link-v2 · 安全插件] 插件的可调项。三条硬规则（永不提权、需要管理员只生成脚本、禁止自授权）不在这里 —— 它们关不掉。
     */
    public static final class Rules {
        /** 看不出内容的写法（-enc / FromBase64String / iex）：默认拦，单独一条，可以关。 */
        public boolean encoded = true;
        /** 沙箱默认断网：开着时命令只能访问 {@link #netAllow} 里的域名（含子域）；空 = 一个都不行。完全访问档关掉。 */
        public boolean net = true;
        public List<String> netAllow = Collections.emptyList();
        /** OneSay 自己的配置 / 目录（远程不许碰）。 */
        public List<String> selfPaths = Collections.emptyList();

        public Rules() {}

        public Rules(boolean encoded, List<String> netAllow, List<String> selfPaths) {
            this.encoded = encoded;
            if (netAllow != null) this.netAllow = netAllow;
            if (selfPaths != null) this.selfPaths = selfPaths;
        }
    }

    /** 插件命中的一条规则：原因码（R_*）+ 给人看的中文。 */
    public static final class Hit {
        public final String code, text;

        Hit(String code, String text) {
            this.code = code;
            this.text = text;
        }

        @Override public String toString() { return code + ": " + text; }
    }

    /**
     * 插件对一条命令的检查（规范化之后匹配）。命中返回 {@link Hit}，没命中返回 null。
     * 顺序：想提权 / 绕 UAC → 想改 OneSay 自己的设置 → 编码命令（可关）→ 沙箱断网。
     * 「需要管理员」不在这里：它是类别（{@link Cat#SYSTEM_A}），结果是只生成脚本而不是拒绝。
     */
    public static Hit inspectCommand(String raw, Rules rules) {
        Rules r = rules == null ? new Rules() : rules;
        String n = normalizeCommand(raw);
        if (n.length() == 0) return null;
        String h = elevationReason(n);
        if (h.length() > 0) return new Hit(R_PLUGIN_BLOCKED, "这条命令" + h + "：OneSay 永远不提权，已拒绝");
        h = selfAuthorizeReason(n, r.selfPaths);
        if (h.length() > 0) return new Hit(R_SELF_GRANT_BLOCKED, "这条命令" + h + "，只能你在电脑上自己改");
        if (r.encoded) {
            h = opaqueReason(n);
            if (h.length() > 0) return new Hit(R_PLUGIN_BLOCKED, "这条命令" + h + "，检查不了就不放行");
        }
        if (r.net) {
            h = netReason(n, r.netAllow);
            if (h.length() > 0) return new Hit(R_PLUGIN_BLOCKED, "这条命令" + h);
        }
        return null;
    }

    // ------------------------------------------------------------------ 规范化

    private static final Pattern ENC_B64 = Pattern.compile("-(e|ec|enc|encodedcommand)\\s+([A-Za-z0-9+/=]{16,})", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENV_PCT = Pattern.compile("%([A-Za-z_][A-Za-z0-9_()]*)%");
    private static final Pattern ENV_PS = Pattern.compile("\\$\\{?env:([A-Za-z_][A-Za-z0-9_]*)\\}?", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHORT_83 = Pattern.compile("([A-Za-z0-9]{1,6})~([0-9]{1,2})");
    private static final Pattern WRAPPER = Pattern.compile(
            "^\\s*(cmd(\\.exe)?\\s+(/[a-z]\\s+)*/[ck]\\s+|powershell(\\.exe)?\\s+((-[a-z]+\\s+(?!-)[^\\s]+|-[a-z]+)\\s+)*-(c|command)\\s+"
                    + "|pwsh(\\.exe)?\\s+((-[a-z]+\\s+(?!-)[^\\s]+|-[a-z]+)\\s+)*-(c|command)\\s+|start\\s+(/b\\s+)?|call\\s+)",
            Pattern.CASE_INSENSITIVE);

    /** 8.3 短名 → 长名（常见的几个；其余只去掉 ~N 留前缀）。 */
    private static final String[][] SHORT_NAMES = {
            {"progra~1", "program files"}, {"progra~2", "program files (x86)"}, {"progra~3", "programdata"},
            {"window~1", "windows"}, {"system~1", "system32"}, {"docume~1", "documents and settings"}, {"defaul~1", "default"}};

    /** 环境变量的常见值（只为匹配，不求准确）。 */
    private static final String[][] ENVS = {
            {"systemroot", "c:\\windows"}, {"windir", "c:\\windows"}, {"comspec", "c:\\windows\\system32\\cmd.exe"},
            {"programdata", "c:\\programdata"}, {"allusersprofile", "c:\\programdata"}, {"programfiles", "c:\\program files"},
            {"programfiles(x86)", "c:\\program files (x86)"}, {"systemdrive", "c:"}, {"public", "c:\\users\\public"}};

    /**
     * 命令规范化（匹配之前先做）：大小写、`cmd /c` / `powershell -Command` 外壳、转义符（^ ` 引号）、环境变量、8.3 短名、
     * `powershell -enc <base64>` 解码（解出来的内容接在后面一起匹配）。做几轮直到不再变化。
     */
    public static String normalizeCommand(String raw) {
        String s = raw == null ? "" : raw;
        if (s.length() > 64 * 1024) s = s.substring(0, 64 * 1024);
        s = s.replace("\u0000", " ");
        boolean decoded = false;
        for (int round = 0; round < 4; round++) {
            String before = s;
            s = unescape(s);
            if (!decoded) {
                String d = decodeEncoded(s);
                decoded = !d.equals(s);
                s = d;
            }
            s = expandEnv(s);
            s = stripWrappers(s);
            if (s.equals(before)) break;
        }
        s = shortNames(s.toLowerCase(Locale.ROOT)).replace("hkey_current_user", "hkcu").replace("registry::", "").replace("microsoft.powershell.core\\registry::", "");
        return s.replaceAll("[\\s\u00a0]+", " ").trim();
    }

    /** -enc <base64>：解成文字接在后面（原文保留，这样「用了编码命令」这条规则还看得见）。 */
    static String decodeEncoded(String s) {
        Matcher m = ENC_B64.matcher(s);
        StringBuilder extra = new StringBuilder();
        while (m.find()) {
            try {
                byte[] b = base64(m.group(2));
                if (b == null) continue;
                String t = new String(b, "UTF-16LE");
                if (t.indexOf('\u0000') >= 0) t = new String(b, "UTF-8");
                extra.append(' ').append(t);
            } catch (Exception ignored) {
                // 解不开就算了，编码命令那条规则照样会拦
            }
        }
        return extra.length() == 0 ? s : s + extra;
    }

    /** 不依赖 java.util.Base64（手机端 Java 7）：自己解。 */
    static byte[] base64(String t) {
        String x = t.replace("\n", "").replace("\r", "").trim();
        int pad = 0;
        while (x.endsWith("=")) { x = x.substring(0, x.length() - 1); pad++; }
        final String AB = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (int i = 0; i < x.length(); i++) {
            int v = AB.indexOf(x.charAt(i));
            if (v < 0) return null;
            buf = (buf << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buf >> bits) & 0xff);
            }
        }
        return out.toByteArray();
    }

    /** 去掉 cmd 的 ^、PowerShell 的反引号与引号（只为匹配）。 */
    static String unescape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '^' || c == '`') {
                if (i + 1 < s.length()) {
                    char nx = s.charAt(i + 1);
                    if (nx != 'n' && nx != 'r' && nx != 't' || c == '^') { i++; b.append(nx); continue; }
                }
                continue;
            }
            if (c == '"' || c == '\'') continue;
            b.append(c);
        }
        return b.toString();
    }

    static String expandEnv(String s) {
        Matcher m = ENV_PCT.matcher(s);
        StringBuffer b = new StringBuffer();
        while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement(envValue(m.group(1))));
        m.appendTail(b);
        String out = b.toString();
        m = ENV_PS.matcher(out);
        b = new StringBuffer();
        while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement(envValue(m.group(1))));
        m.appendTail(b);
        return b.toString();
    }

    static String envValue(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String[] e : ENVS) if (e[0].equals(n)) return e[1];
        return n;
    }

    static String stripWrappers(String s) {
        String out = s;
        for (int i = 0; i < 4; i++) {
            Matcher m = WRAPPER.matcher(out);
            if (!m.find()) break;
            out = out.substring(m.end());
        }
        return out.trim();
    }

    static String shortNames(String s) {
        String out = s;
        for (String[] k : SHORT_NAMES) out = out.replace(k[0], k[1]);
        Matcher m = SHORT_83.matcher(out);
        StringBuffer b = new StringBuffer();
        while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement(m.group(1)));
        m.appendTail(b);
        return b.toString();
    }

    /** 剥掉脚本注释再匹配（.bat：REM / ::；.ps1：&lt;# #&gt; 与行内 #，引号里的 # 不算）。 */
    public static String stripComments(String body, boolean batch) {
        String s = body == null ? "" : body.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder();
        if (batch) {
            for (String line : s.split("\n", -1)) {
                String t = line.trim().toLowerCase(Locale.ROOT);
                if (t.startsWith("rem ") || t.equals("rem") || t.startsWith("@rem") || t.startsWith("::")) continue;
                out.append(line).append('\n');
            }
            return out.toString();
        }
        // <# ... #> 块注释
        StringBuilder noBlock = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            if (i + 1 < s.length() && s.charAt(i) == '<' && s.charAt(i + 1) == '#') { depth++; i++; continue; }
            if (depth > 0 && i + 1 < s.length() && s.charAt(i) == '#' && s.charAt(i + 1) == '>') { depth--; i++; continue; }
            if (depth == 0) noBlock.append(s.charAt(i));
        }
        for (String line : noBlock.toString().split("\n", -1)) {
            char quote = 0;
            StringBuilder kept = new StringBuilder();
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (quote != 0) {
                    kept.append(c);
                    if (c == quote) quote = 0;
                    continue;
                }
                if (c == '\'' || c == '"') { quote = c; kept.append(c); continue; }
                if (c == '#') break; // 行内注释到行尾
                kept.append(c);
            }
            out.append(kept).append('\n');
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ 规则

    /** 想提权 / 触发 UAC / 走 UAC 绕过程序。 */
    private static final Pattern ELEVATE = Pattern.compile(
            "(\\brunas\\b)|(-verb\\s+runas)|(verb\\s*[:=]\\s*runas)|(shellexecute\\w*\\s*\\([^)]*runas)|(\\bgsudo\\b)|(\\bsudo\\b)"
                    + "|(\\bpsexec(\\.exe)?\\b)|(\\bfodhelper\\b)|(\\beventvwr\\b)|(\\bcomputerdefaults\\b)|(\\bsdclt\\b)|(\\bslui\\b)"
                    + "|(\\bwsreset\\b)|(\\bcmstp\\b)|(\\bdccw\\b)|(\\bsilentcleanup\\b)|(-runlevel\\s+highest)|(/rl\\s+highest)"
                    + "|(\\bstart-process\\b[^|;]*\\bas\\s*administrator\\b)", Pattern.CASE_INSENSITIVE);

    public static String elevationReason(String normalized) {
        String h = firstHit(ELEVATE, normalized == null ? "" : normalized);
        return h == null ? "" : "想用管理员身份运行 / 触发 UAC（" + h + "）";
    }

    /** 需要管理员的事：机器级注册表、服务与驱动、防火墙 / 网卡 / DNS / 系统级代理 / hosts、系统级计划任务、磁盘与启动项。 */
    private static final Pattern ADMIN_REG = Pattern.compile(
            "(\\bhklm[:\\\\])|(hkey_local_machine)|(\\bhkcr[:\\\\])|(hkey_classes_root)|(\\bhku[:\\\\])|(hkey_users)"
                    + "|(\\breg(\\.exe)?\\s+(load|unload|restore|import|copy)\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ADMIN_SVC = Pattern.compile(
            "(\\bsc(\\.exe)?\\s+(create|config|delete|start|stop|failure|sdset|qc)\\b)|(\\b(new|set|start|stop|restart|remove|suspend|resume)-service\\b)"
                    + "|(\\bnet\\s+(start|stop)\\s+\\S)|(\\bpnputil\\b)|(\\bdevcon\\b)|(\\bdriverquery\\b)|(\\bbcdedit\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ADMIN_NET = Pattern.compile(
            "(\\bnetsh\\b)|(\\b(new|set|remove|enable|disable|restart|rename)-net[a-z]+\\b)|(\\bset-dnsclient\\w*\\b)|(\\bdrivers\\\\etc\\\\hosts\\b)"
                    + "|(\\broute(\\.exe)?\\s+(add|delete|change)\\b)|(\\bipconfig\\s+/(release|renew)\\b)|(\\bbitsadmin\\s+/util\\s+/setieproxy\\b)"
                    + "|(\\bset-netipinterface\\b)|(\\barp\\s+-d\\b)|(\\bnetcfg\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ADMIN_TASK = Pattern.compile(
            "(\\bschtasks\\b[^|;]*(/ru\\s+(system|administrator|nt authority)|/rl\\s+highest))"
                    + "|(\\bregister-scheduledtask\\b[^|;]*(-user\\s+system|-runlevel\\s+highest))|(\\bat\\s+[0-9]{1,2}:[0-9]{2}\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ADMIN_DISK = Pattern.compile(
            "(\\bdiskpart\\b)|(\\bformat\\s+[a-z]:)|(\\bvssadmin\\b)|(\\bdism\\b)|(\\bsfc\\s+/)|(\\bchkdsk\\s+[a-z]:\\s*/)|(\\bwbadmin\\b)"
                    + "|(\\breagentc\\b)|(\\bbootrec\\b)|(\\bmountvol\\b)|(\\bfsutil\\b)|(\\bwusa\\b)|(\\bsecedit\\b)|(\\bauditpol\\b)"
                    + "|(\\bnet\\s+(user|localgroup|accounts)\\b)|(\\bsetx\\b[^|;]*\\s/m\\b)|(\\bmsiexec\\b)"
                    + "|(\\bset-executionpolicy\\b(?![^|;]*-scope\\s+(currentuser|process)))", Pattern.CASE_INSENSITIVE);

    /** 需要管理员就返回中文原因（给脚本头注释用），不需要返回空串。 */
    public static String adminReason(String normalized) {
        String s = normalized == null ? "" : normalized;
        String h = firstHit(ADMIN_REG, s);
        if (h != null) return "要改机器级注册表（" + h + "），需要管理员";
        h = firstHit(ADMIN_SVC, s);
        if (h != null) return "要动服务 / 驱动 / 启动项（" + h + "），需要管理员";
        h = firstHit(ADMIN_NET, s);
        if (h != null) return "要改防火墙 / 网卡 / DNS / 系统级代理 / hosts（" + h + "），需要管理员";
        h = firstHit(ADMIN_TASK, s);
        if (h != null) return "要建系统级计划任务（" + h + "），需要管理员";
        h = firstHit(ADMIN_DISK, s);
        if (h != null) return "要动磁盘 / 系统映像 / 账户（" + h + "），需要管理员";
        return "";
    }

    /**
     * 当前用户注册表（HKCU）放开，但这几类仍然不行，因为它们能影响提权：
     * Classes 下 ms-settings / mscfile / exefile 等的 shell\open\command 劫持（fodhelper / eventvwr 那类 UAC 绕过）、
     * Environment 里的 windir / COR_PROFILER、以及 Policies。
     */
    private static final Pattern HKCU_HIJACK = Pattern.compile(
            "hkcu[:\\\\][^ ]*classes[\\\\][^ ]*(shell[\\\\][^ ]*command|delegateexecute)"
                    + "|hkcu[:\\\\][^ ]*classes[\\\\](ms-settings|mscfile|exefile|progfile|folder|regfile|batfile|cmdfile)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HKCU_ENV = Pattern.compile(
            "hkcu:?\\\\environment[^|;]*\\b(windir|systemroot|cor_profiler|cor_enable_profiling|cor_profiler_path|coreclr_profiler|appinit_dlls)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HKCU_POLICY = Pattern.compile(
            "(hkcu[:\\\\][^ ]*\\\\policies\\b)|(hkcu[:\\\\]software[\\\\]policies\\b)|(currentversion[\\\\]policies\\b)", Pattern.CASE_INSENSITIVE);

    public static String hkcuElevationReason(String normalized) {
        String s = normalized == null ? "" : normalized;
        String h = firstHit(HKCU_HIJACK, s);
        if (h != null) return "要改能劫持系统程序的用户注册表键（" + h + "），这类改动能绕过 UAC";
        h = firstHit(HKCU_ENV, s);
        if (h != null) return "要改 HKCU\\Environment 里能影响提权的变量（" + h + "）";
        h = firstHit(HKCU_POLICY, s);
        if (h != null) return "要改策略（Policies）相关的注册表键（" + h + "）";
        return "";
    }

    /** 远程想改 OneSay 自己的策略 / 配置 / 信任列表 / 插件开关，或把 OneSay 关掉。 */
    private static final Pattern SELF_CFG = Pattern.compile(
            "(desktop\\.properties)|(shortcut_approvals)|(pc_sandbox)|(pc_approval)|(pc_level)|(trusted_domains)|(plugin_enabled)"
                    + "|(plugin_encoded)|(plugin_net_allow)|(\\btaskkill\\b[^|;]*onesay)|(\\bstop-process\\b[^|;]*onesay)"
                    + "|(appdata[\\\\]onesay)|(\\.config/onesay)|(\\bsc(\\.exe)?\\s+\\w+\\s+onesay\\b)|((127\\.0\\.0\\.1|localhost|\\[::1\\]):47830)|(/api/(settings|confirm|shortcuts|remembered))|(approve_kinds)|(clip_autoclear)",
            Pattern.CASE_INSENSITIVE);

    public static String selfAuthorizeReason(String normalized, List<String> selfPaths) {
        String s = normalized == null ? "" : normalized;
        String h = firstHit(SELF_CFG, s);
        if (h != null) return "想改 OneSay 自己的设置 / 把 OneSay 关掉（" + h + "）";
        if (selfPaths != null) for (String p : selfPaths) {
            if (p == null || p.trim().length() == 0) continue;
            String t = p.trim().toLowerCase(Locale.ROOT).replace('/', '\\');
            if (s.replace('/', '\\').indexOf(t) >= 0) return "想改 OneSay 自己的文件（" + oneLine(t) + "）";
        }
        return "";
    }

    /** 看不出真实内容的写法（编码命令、动态执行）。 */
    public static String opaqueReason(String normalized) {
        String h = firstHit(OPAQUE, normalized == null ? "" : normalized);
        return h == null ? "" : "用了编码 / 动态执行，看不出真正做什么（" + h + "）";
    }

    /** 沙箱默认断网：命令要联网时，域名必须在白名单里。 */
    private static final Pattern NET_FETCH = Pattern.compile(
            "(\\bcurl(\\.exe)?\\s)|(\\bwget(\\.exe)?\\s)|(\\binvoke-webrequest\\b)|(\\binvoke-restmethod\\b)|(\\biwr\\b)|(\\birm\\b)"
                    + "|(\\bbitsadmin\\b)|(\\bstart-bitstransfer\\b)|(\\bcertutil\\b[^|;]*-urlcache)|(\\bnet\\s+use\\s+\\\\\\\\)"
                    + "|(\\bnew-object\\s+net\\.webclient\\b)|(\\bdownloadstring\\b)|(\\bdownloadfile\\b)|(\\bftp(\\.exe)?\\s)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_HOST = Pattern.compile("https?://([^/\\s'\"]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern UNC = Pattern.compile("(^|[\\s=(,])(\\\\\\\\|//)([a-z0-9?.][a-z0-9.\\-]*)[\\\\/]", Pattern.CASE_INSENSITIVE);

    /** 命令 / 路径里的 UNC 主机（\\server\share、//server/share）；设备路径（\\?\、\\.\）不算。没有返回 null。 */
    public static String uncHost(String normalized) {
        Matcher m = UNC.matcher(normalized == null ? "" : normalized);
        while (m.find()) {
            String h = m.group(3).toLowerCase(Locale.ROOT);
            if (h.equals("?") || h.equals(".")) continue;
            return h;
        }
        return null;
    }

    public static String netReason(String normalized, List<String> allow) {
        String s = normalized == null ? "" : normalized;
        String unc = uncHost(s);
        if (unc != null && !trustedHost(unc, allow)) return "要访问网络共享 \\\\" + oneLine(unc) + "；沙箱默认断网，只放行白名单里的主机";
        String h = firstHit(NET_FETCH, s);
        if (h == null) return "";
        List<String> hosts = new ArrayList<String>();
        Matcher m = URL_HOST.matcher(s);
        while (m.find()) {
            String host = m.group(1);
            int at = host.indexOf('@');
            if (at >= 0) host = host.substring(at + 1);
            int colon = host.lastIndexOf(':');
            if (colon > 0 && host.indexOf('[') < 0) host = host.substring(0, colon);
            hosts.add(host.toLowerCase(Locale.ROOT));
        }
        if (hosts.isEmpty()) return "要联网（" + h + "），但看不出访问哪个域名；沙箱默认断网，已拒绝";
        for (String host : hosts) if (!trustedHost(host, allow)) return "要访问 " + oneLine(host) + "；沙箱默认断网，只放行白名单里的域名";
        return "";
    }

    // ------------------------------------------------------------------ 工作区里的只读目录

    private static final String[] WS_READONLY = {".git", ".codex", ".agents"};

    /** 工作区里的 .git / .codex / .agents 只读（Codex 沙箱同样的约定）。 */
    public static boolean protectedWorkspacePath(String rel) {
        if (rel == null) return false;
        for (String part : rel.replace('\\', '/').split("/")) {
            String p = part.trim().toLowerCase(Locale.ROOT);
            for (String k : WS_READONLY) if (p.equals(k)) return true;
        }
        return false;
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
        /** 网址有风险的原因（ok = false 且不是 malformed 时）：IP 字面量 / 内网主机 / 带账号密码 / 疑似仿冒。 */
        public static final String RISK_IP = "ip", RISK_PRIVATE = "private", RISK_CREDENTIALS = "credentials", RISK_LOOKALIKE = "lookalike";
        public final boolean ok, trusted;
        /** 格式就不对（空、太长、控制字符、不是 http / https、主机名不合法…）：这不是风险而是坏输入，任何时候都不开。 */
        public final boolean malformed;
        /** 只给人看：域名（IDN 同时给 Unicode 与 xn-- 形式）。 */
        public final String host, shown, why;
        /** 有风险时是哪一种（{@link #RISK_IP} …）；没风险 / 格式不对为空。 */
        public final String risk;

        UrlCheck(boolean ok, boolean trusted, String host, String shown, String why) {
            this(ok, trusted, host, shown, why, false, "");
        }

        UrlCheck(boolean ok, boolean trusted, String host, String shown, String why, boolean malformed, String risk) {
            this.ok = ok;
            this.trusted = trusted;
            this.host = host == null ? "" : host;
            this.shown = shown == null ? "" : shown;
            this.why = why == null ? "" : why;
            this.malformed = !ok && malformed;
            this.risk = ok || malformed || risk == null ? "" : risk;
        }

        static UrlCheck bad(String why) { return new UrlCheck(false, false, "", "", why, true, ""); }

        static UrlCheck risky(String risk, String host, String shown, String why) { return new UrlCheck(false, false, host, shown, why, false, risk); }
    }

    /**
     * 打开网址前的检查：只许 http/https；超长、控制字符、格式不对 = {@link UrlCheck#malformed}（坏输入，不开）；
     * 带账号密码（user:pass@）、IP 字面量 / 内网主机（除非在信任列表）、混合文字的仿冒域名（punycode / IDN）= 有风险（{@link UrlCheck#risk}，
     * 2026-11-10 起由调用方决定是问你还是放行，不再一律拒绝）。trusted = 主机是信任域名或其子域。
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
        if (auth.indexOf('@') >= 0) return UrlCheck.risky(UrlCheck.RISK_CREDENTIALS, "", auth.substring(auth.lastIndexOf('@') + 1), "网址里带了账号密码（user:pass@）");
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
            if (parsed.getRawUserInfo() != null) return UrlCheck.risky(UrlCheck.RISK_CREDENTIALS, ascii, ascii, "网址里带了账号密码（user:pass@）");
        } catch (Exception e) {
            // 路径部分有 URI 不收的字符：交给浏览器，但主机部分已经检查过
        }
        boolean trusted = trustedHost(ascii, trustedDomains);
        if (isIpLiteral(ascii) && !trusted) return UrlCheck.risky(UrlCheck.RISK_IP, ascii, ascii, "是 IP 地址网址（" + ascii + "），不在信任列表");
        if (isPrivateHost(ascii) && !trusted) return UrlCheck.risky(UrlCheck.RISK_PRIVATE, ascii, ascii, "是内网 / 本机地址（" + ascii + "），不在信任列表");
        String look = lookalike(uni);
        if (look != null) return UrlCheck.risky(UrlCheck.RISK_LOOKALIKE, ascii, uni + "（" + ascii + "）", "疑似仿冒域名（" + look + "）：" + uni + "（" + ascii + "）");
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

    /** 敏感内容写进剪贴板后多久清除（2026-11-10 起是可选设置，默认关：{@link #CLIP_AUTOCLEAR_DEFAULT}）。 */
    public static final long CLIP_SENSITIVE_CLEAR_MS = 60000;
    public static final boolean CLIP_AUTOCLEAR_DEFAULT = false;

    public static final class ClipCheck {
        public static final int OK = 0, SENSITIVE = 1, REJECT = 2;
        public final int verdict, chars;
        public final String why;
        public final List<String> kinds;
        /**
         * 内容像命令 / 脚本、或带 http(s) 以外的链接 / 有问题的链接：2026-11-10 起不再拒绝，只记在这里（电脑作为被控端时据此问你，
         * 手机作为被控端不看它）。空 = 没有。
         */
        public final String note;

        ClipCheck(int verdict, int chars, String why, List<String> kinds) {
            this(verdict, chars, why, kinds, "");
        }

        ClipCheck(int verdict, int chars, String why, List<String> kinds, String note) {
            this.verdict = verdict;
            this.chars = chars;
            this.why = why == null ? "" : why;
            this.kinds = kinds == null ? Collections.<String>emptyList() : kinds;
            this.note = note == null ? "" : note;
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

    /**
     * 写手机 / 电脑剪贴板前的检查：只有空的拒绝（文字长度由指令参数检查管，10 万字）；验证码 / 卡号 / 私钥 / 助记词 / 密码 → 敏感。
     * 像命令 / 脚本、带 http(s) 以外或有问题的链接 → 记在 {@link ClipCheck#note}（2026-11-10 起不再拒绝；以前超过 10 KB 和像命令都拒）。
     */
    public static ClipCheck checkClip(String text) {
        if (text == null || text.length() == 0) return new ClipCheck(ClipCheck.REJECT, 0, "没有文字", null);
        int chars = text.codePointCount(0, text.length());
        String note = "";
        String head = text.length() > 64 * 1024 ? text.substring(0, 64 * 1024) : text; // 只为找特征，太长的看前 64K
        if (SCRIPTY.matcher(head).find()) note = "内容像命令 / 脚本";
        Matcher um = URLISH.matcher(head);
        while (note.length() == 0 && um.find()) {
            String scheme = um.group(1).toLowerCase(Locale.ROOT);
            String whole = um.group();
            if (um.group(2) == null && !in(scheme, "javascript", "data", "intent", "file", "content", "vbscript")) continue; // 「时间:10」这类不是链接
            if (!in(scheme, "http", "https")) { note = "含 " + scheme + ": 链接"; break; }
            UrlCheck uc = checkUrl(whole, null);
            if (uc.malformed) note = "含有问题的链接：" + uc.why;
            else if (!uc.ok) note = UrlCheck.RISK_IP.equals(uc.risk) || UrlCheck.RISK_PRIVATE.equals(uc.risk) ? "含 IP / 内网链接" : "含有风险的链接：" + uc.why;
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
        if (!kinds.isEmpty()) return new ClipCheck(ClipCheck.SENSITIVE, chars, join(kinds, "、"), kinds, note);
        return new ClipCheck(ClipCheck.OK, chars, "", null, note);
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
            // 同一秒里生成的同类脚本文件名一样：不覆盖前一份，名字后面加 -2、-3…
            String rel = s.relPath;
            int dot = rel.lastIndexOf('.');
            for (int i = 2; i < 100; i++) {
                File old = resolveInside(root, rel);
                if (old == null || !old.exists()) break;
                rel = dot < 0 ? s.relPath + "-" + i : s.relPath.substring(0, dot) + "-" + i + s.relPath.substring(dot);
            }
            File f = writeInside(root, rel, data);
            return f == null ? null : rel;
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
        if (R_CONFIRM_TIMEOUT.equals(code)) return "对方 60 秒内没确认，已拒绝";
        if (R_CONFIRM_DENIED.equals(code)) return "对方点了拒绝";
        if (R_UNTRUSTED_URL.equals(code)) return "网址没通过安全检查";
        if (R_BLOCKED_APP.equals(code)) return "这类 App 不允许远程操作";
        if (R_CLIPBOARD_REJECTED.equals(code)) return "剪贴板内容没通过安全检查";
        if (R_SCRIPT_GENERATED.equals(code)) return "已生成脚本，需你自己执行";
        if (R_SYSTEM_APP.equals(code)) return "系统 App 不允许远程改动";
        if (R_OUTSIDE_WORKSPACE.equals(code)) return "只能写工作区里的文件";
        if (R_UNKNOWN.equals(code)) return "不认识的指令";
        if (R_SHORTCUT_NEEDS_APPROVAL.equals(code)) return "这个快捷指令还没在电脑上批准";
        if (R_SHORTCUT_CHANGED.equals(code)) return "快捷指令内容改过，需要在电脑上重新批准";
        if (R_SHORTCUT_BLOCKED.equals(code)) return "快捷指令要管理员或能影响提权，不能远程直接执行";
        if (R_PLUGIN_BLOCKED.equals(code)) return "安全插件拦下了（提权 / 看不出内容 / 沙箱断网）";
        if (R_NEEDS_ADMIN_SCRIPT.equals(code)) return "这件事要管理员，已生成脚本，需你自己用管理员身份执行";
        if (R_SELF_GRANT_BLOCKED.equals(code)) return "远程不能改 OneSay 自己的设置";
        if (R_VOICE_APPROVAL_BLOCKED.equals(code)) return "确认只认真人点击（本机鼠标 / 键盘，或已配对手机屏幕上的确认卡），语音、远程消息和机器人不能批准";
        if (R_PAY_USER_TAP.equals(code)) return "付款 / 转账的最后一下只能你自己点";
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
