package com.onesay.assistant.v2.executor;

/**
 * 邮箱（IMAP）设置页的纯逻辑（P1-13，从 MailSettingsActivity 抽出，JVM 可测）。
 *
 * <p>只做三件事：服务商预设、表单校验、决定授权码是否要写入。真正的存储仍走
 * {@link MailBridge#saveConfig} / {@link MailBridge#saveProvider}（与 TestRunReceiver 配置口同一条路），
 * 授权码<b>不进日志、不回显</b>。
 */
public final class MailConfigForm {

    private MailConfigForm() {}

    /** 设置页入口文案（AgentLoop 方案 / NOT_CONFIGURED 提示统一引用，便于用户在控制台里找到）。 */
    public static final String SETTINGS_PATH = "OneSay 控制台 → 「邮箱（IMAP）配置」";

    /** 服务商预设（显示名, provider key）。端口一律 993（IMAP over SSL）。 */
    public static final String[][] PRESETS = {
            {"QQ邮箱", MailBridge.PROVIDER_QQ},
            {"163/126", MailBridge.PROVIDER_163},
            {"企业邮", MailBridge.PROVIDER_EXMAIL},
            {"Outlook", MailBridge.PROVIDER_OUTLOOK},
            {"Gmail", MailBridge.PROVIDER_GMAIL},
            {"自定义", MailBridge.PROVIDER_CUSTOM},
    };

    /** 不同服务商"密码栏"该填什么。 */
    public static String credentialHint(String provider) {
        String p = provider == null ? "" : provider.trim().toLowerCase();
        if (MailBridge.PROVIDER_QQ.equals(p) || MailBridge.PROVIDER_163.equals(p)) {
            return "授权码（网页版邮箱 → 设置 → 账号 → 开启 IMAP/SMTP → 生成授权码；不是登录密码）";
        }
        if (MailBridge.PROVIDER_GMAIL.equals(p) || MailBridge.PROVIDER_OUTLOOK.equals(p)) {
            return "应用专用密码（需先开启两步验证）";
        }
        if (MailBridge.PROVIDER_EXMAIL.equals(p)) return "登录密码或客户端专用密码";
        return "授权码 / 应用专用密码";
    }

    /** 校验结果。 */
    public static final class Result {
        /** null = 通过；否则为给用户看的错误。 */
        public final String error;
        public final String host;
        public final int port;
        public final String user;
        /** 要写入的授权码；null = 不修改已存的（注意：换服务器时 MailBridge 会清掉旧授权码）。 */
        public final String pass;
        /** 非错误的提醒（如"换服务器需重填授权码"）。 */
        public final String warning;

        Result(String error, String host, int port, String user, String pass, String warning) {
            this.error = error;
            this.host = host;
            this.port = port;
            this.user = user;
            this.pass = pass;
            this.warning = warning;
        }

        public boolean ok() { return error == null; }
    }

    /**
     * 校验表单。
     *
     * @param oldHost        当前已存的服务器（判断"换服务器"用）
     * @param hasStoredPass  是否已存有授权码（授权码框留空 = 保留旧值）
     */
    public static Result validate(String hostRaw, String portRaw, String userRaw, String passRaw,
                                  String oldHost, boolean hasStoredPass) {
        String host = hostRaw == null ? "" : hostRaw.trim().toLowerCase();
        String user = userRaw == null ? "" : userRaw.trim();
        String pass = passRaw == null ? "" : passRaw.trim();
        if (host.length() == 0) return err("请填写 IMAP 服务器（QQ 邮箱为 imap.qq.com）");
        if (!host.matches("^[a-z0-9]([a-z0-9\\-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9\\-]*[a-z0-9])?)+$")) {
            return err("服务器地址格式不对：只填域名，例如 imap.qq.com（不要带 imaps:// 或端口）");
        }
        int port;
        try {
            port = Integer.parseInt(portRaw == null ? "" : portRaw.trim());
        } catch (NumberFormatException e) {
            return err("端口必须是数字（IMAP over SSL 一般是 993）");
        }
        if (port <= 0 || port > 65535) return err("端口范围 1–65535（IMAP over SSL 一般是 993）");
        if (!user.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) return err("邮箱地址格式不对，例如 12345@qq.com");
        if (pass.indexOf(' ') >= 0 && pass.replace(" ", "").length() == 16) {
            pass = pass.replace(" ", "");   // QQ 授权码常被复制成 "abcd efgh ijkl mnop"
        }
        boolean hostChanged = oldHost != null && oldHost.trim().length() > 0
                && !oldHost.trim().equalsIgnoreCase(host);
        if (pass.length() == 0) {
            if (!hasStoredPass) return err("请填写授权码（不是邮箱登录密码）");
            if (hostChanged) {
                return err("更换了服务器，为防授权码被发往别处，请重新填写授权码");
            }
            return new Result(null, host, port, user, null, null);
        }
        String warn = port != 993 ? "提示：本应用只走 SSL（IMAPS），端口一般是 993" : null;
        return new Result(null, host, port, user, pass, warn);
    }

    /** 预设服务商对应的服务器（未知/自定义返回 null，保留用户输入）。 */
    public static String presetHost(String provider) {
        return MailBridge.presetHost(provider);
    }

    /**
     * 面板/方案文字里是否应该附上"去填写邮箱设置"的入口：
     * 邮件能力返回 NOT_CONFIGURED / AUTH_FAILED，或文字里在讲授权码/IMAP 配置且当前确实未配置。
     */
    public static boolean shouldOfferSettings(String text, ErrorCode code, String sign, boolean configured) {
        if (sign != null && MailBridge.isMailSign(sign)
                && (code == ErrorCode.NOT_CONFIGURED || code == ErrorCode.AUTH_FAILED)) {
            return true;
        }
        if (configured || text == null) return false;
        return text.contains("授权码") || (text.contains("IMAP") && (text.contains("配置") || text.contains("开启")));
    }

    private static Result err(String msg) {
        return new Result(msg, null, 0, null, null, null);
    }
}
