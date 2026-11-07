package com.onesay.assistant.v2.executor;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.util.Date;
import java.util.Properties;

/**
 * 邮件能力（IMAP）—— 读收件箱 / 新建草稿。
 *
 * <h3>为什么是 IMAP 而不是"读屏幕"</h3>
 * 用户实测要"读 QQ 邮箱第一条邮件说出内容"。走 UI 自动化有三条死路：
 * 腾讯风控、QQ邮箱主界面自绘拿不到稳定控件、以及**{@code adb shell input text} 打不了中文**
 * （连"新建一封中文草稿"都做不到）。而邮件本身**有公开标准协议**：
 * 有标准协议的数据就不该用"看屏幕"去读。
 *
 * <h3>凭据</h3>
 * QQ 邮箱要求用 **16 位授权码**（非 QQ 密码），需先在网页端
 * 「设置 → 账户 → 开启 IMAP/SMTP 服务」再生成。参数：
 * <pre>
 *   imap.qq.com : 993 SSL
 *   用户名 = 完整邮箱地址；密码 = 16 位授权码
 * </pre>
 * 凭据存 SharedPreferences（{@code bixby_mail_cfg}），通过广播写入，见
 * {@code TestRunReceiver} 的 {@code --es mailuser / --es mailpass}。
 *
 * <h3>边界（硬性）</h3>
 * <ul>
 *   <li><b>只读 + 存草稿</b>：本轮**不发送**（用户明确"TTS 先不做"，发送更不在范围内）。</li>
 *   <li><b>不落明文日志</b>：授权码绝不打进日志，只打印脱敏后的用户名。</li>
 *   <li><b>超时收敛</b>：连接 10s / 读 15s，避免拖死路由线程。</li>
 *   <li>正文超长时截断（默认 600 字），全文长度另行回报 —— 语音场景不需要全文。</li>
 * </ul>
 */
public final class MailBridge {

    private static final String TAG = "MailBridge";

    /** 配置存储（一个服务一个文件，不与其他服务串用）。 */
    public static final String PREF_NAME = "bixby_mail_cfg";
    public static final String KEY_HOST = "imap_host";
    public static final String KEY_PORT = "imap_port";
    public static final String KEY_USER = "user";
    public static final String KEY_PASS = "pass";
    /** 服务商预设（qq/163/gmail/outlook/exmail/custom），决定默认 host。 */
    public static final String KEY_PROVIDER = "provider";
    /** 代理：{@code socks5://host:port} / {@code socks://host:port} / {@code http://host:port}；空=直连。 */
    public static final String KEY_PROXY = "proxy";

    /** 默认服务器（QQ 邮箱，腾讯官方帮助文档口径）。 */
    public static final String DEFAULT_HOST = "imap.qq.com";
    public static final int DEFAULT_PORT = 993;

    // ---------- 服务商预设（通用 IMAP，不绑定 QQ） ----------
    //
    //  本引擎是标准 IMAP 客户端，**凡是提供 IMAP over SSL 的邮箱都能接**，
    //  区别只在 host / 端口 / "密码那一栏填什么"。预设只是省去手填 host。
    //  密码栏口径（各家叫法不同，本质都是"专用凭据"，不是登录密码）：
    //    · QQ / 163      → 网页端生成的「授权码 / 客户端授权密码」
    //    · Gmail         → 「应用专用密码」（需先开两步验证）
    //    · Outlook       → 「应用密码」（需先开两步验证）
    //    · 腾讯企业邮    → 登录密码
    //  国内直连可用性：QQ/163/企业邮/Outlook 均可；**Gmail 国内直连不通，需代理** —— 见 {@link #isLikelyBlocked}。
    public static final String PROVIDER_QQ = "qq";
    public static final String PROVIDER_163 = "163";
    public static final String PROVIDER_GMAIL = "gmail";
    public static final String PROVIDER_OUTLOOK = "outlook";
    public static final String PROVIDER_EXMAIL = "exmail";
    public static final String PROVIDER_CUSTOM = "custom";

    /** 服务商 → IMAP 服务器地址；未知返回 null。 */
    public static String presetHost(String provider) {
        if (provider == null) return null;
        String p = provider.trim().toLowerCase();
        if (PROVIDER_QQ.equals(p) || "qqmail".equals(p)) return "imap.qq.com";
        if (PROVIDER_163.equals(p) || "126".equals(p) || "netease".equals(p)) return "imap.163.com";
        if (PROVIDER_GMAIL.equals(p) || "googlemail".equals(p)) return "imap.gmail.com";
        if (PROVIDER_OUTLOOK.equals(p) || "hotmail".equals(p) || "live".equals(p)
                || "office365".equals(p)) return "outlook.office365.com";
        if (PROVIDER_EXMAIL.equals(p) || "tencentexmail".equals(p)) return "imap.exmail.qq.com";
        return null;
    }

    /**
     * 该服务器是否属于**境外服务**（连通性取决于当前网络，不保证直连可达）。
     *
     * <p>⚠️ 实测教训（2026-10-06）：本机在 WiFi「602_5G」、**无任何 VPN/代理**
     * （{@code vpn_state=null}，NetworkAgent 全为 {@code NOT_VPN}）的情况下，
     * 与 {@code imap.gmail.com:993} 完成了 TCP + TLS 并收到服务器的
     * {@code AUTHENTICATIONFAILED} 响应 —— 即**这个网络本身就能直连 Gmail IMAP**。
     * 所以"Gmail 一定要代理"**不是普适结论**，取决于设备所处网络。
     * 这里只用它来决定**失败时给什么建议**，绝不用它去阻止尝试。
     */
    public static boolean isLikelyBlocked(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase();
        return h.endsWith("gmail.com") || h.endsWith("googlemail.com")
                || h.contains("google") || h.endsWith("mail.me.com");
    }

    /** 能力签名（唯一真源：路由 / 执行 / 自测全按它判）。 */
    public static final String SIGN_INBOX_LATEST = "mail.inbox.latest";
    public static final String SIGN_DRAFT_NEW = "mail.draft.new";

    /** 正文回显上限（字）。 */
    private static final int BODY_PREVIEW = 600;

    /**
     * 排序窗口（）：取信时至少抓这么多封，再按时间倒序截取前 count 封。
     *
     * <p>为什么不直接取"最后 count 个序号"：实测这台 QQ 服务器上**序号顺序 ≠ 时间顺序**
     * （fetch(1) 拿到 10-05 的邮件，fetch(5) 的最后 5 封却是 07-31~08-05 的）。
     * 用 12 封做窗口是折中：足够盖住"最近收到的几封"，又不会让每封信都去拉一遍头部。
     */
    private static final int DATE_WINDOW = 12;

    /**
     * 首选检索窗口：最近多少天收到的邮件视为"最新"候选（）。
     *
     * <p>走的是服务端检索（IMAP SINCE），所以这个值只是"要多大一张网"，
     * 不影响正确性；30 天足够覆盖"最近收到的信"，也不至于把上千封全拉回本地。
     */
    private static final int RECENT_DAYS = 30;

    private MailBridge() {}

    /** 是否属于邮件域能力。 */
    public static boolean isMailSign(String sign) {
        return SIGN_INBOX_LATEST.equals(sign) || SIGN_DRAFT_NEW.equals(sign);
    }

    // ---------- 配置 ----------

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static String host(Context ctx) {
        String h = prefs(ctx).getString(KEY_HOST, DEFAULT_HOST);
        return (h == null || h.trim().length() == 0) ? DEFAULT_HOST : h.trim();
    }

    public static int port(Context ctx) {
        return prefs(ctx).getInt(KEY_PORT, DEFAULT_PORT);
    }

    public static String user(Context ctx) {
        String u = prefs(ctx).getString(KEY_USER, "");
        return u == null ? "" : u.trim();
    }

    private static String pass(Context ctx) {
        String p = com.onesay.assistant.v2.security.SecretPrefs.get(prefs(ctx), KEY_PASS);
        return p == null ? "" : p.trim();
    }

    public static void saveConfig(Context ctx, String host, int port, String user, String pass) {
        SharedPreferences sp = prefs(ctx);
        SharedPreferences.Editor e = sp.edit();
        String oldHost = sp.getString(KEY_HOST, "");
        if (host != null && host.trim().length() > 0) e.putString(KEY_HOST, host.trim());
        if (port > 0) e.putInt(KEY_PORT, port);
        if (user != null) e.putString(KEY_USER, user.trim());
        if (pass != null) {
            com.onesay.assistant.v2.security.SecretPrefs.put(e, KEY_PASS, pass.trim());
        } else if (mustDropStoredPassword(oldHost, host)) {
            // （审计 S1 配套）：只改服务器而不重新给授权码时，**清掉已存授权码**。
            //   否则"改 host 指向攻击者服务器 + 触发一次读信"就能把真实授权码发出去。
            e.remove(KEY_PASS);
            Log.w(TAG, "邮件服务器地址变更且未重新提供授权码：已清除旧授权码，需重新配置");
        }
        e.apply();
    }

    /** 纯函数：服务器地址变了（且确实给了新地址）就不能沿用旧授权码。 */
    static boolean mustDropStoredPassword(String oldHost, String newHost) {
        if (newHost == null || newHost.trim().length() == 0) return false;
        String o = oldHost == null ? "" : oldHost.trim();
        return o.length() > 0 && !o.equalsIgnoreCase(newHost.trim());
    }

    public static void clearConfig(Context ctx) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return;   // r2/fixes 9：debug 驱动专用，release 里是空操作
        prefs(ctx).edit().clear().apply();
    }

    public static String provider(Context ctx) {
        String p = prefs(ctx).getString(KEY_PROVIDER, "");
        return p == null ? "" : p.trim();
    }

    public static void saveProvider(Context ctx, String p) {
        if (p == null || p.trim().length() == 0) return;
        String key = p.trim().toLowerCase();
        SharedPreferences.Editor e = prefs(ctx).edit().putString(KEY_PROVIDER, key);
        // 预设服务商顺带把 host 也定下来（用户显式指定过 host 的以 host 为准）
        String h = presetHost(key);
        if (h != null && prefs(ctx).getString(KEY_HOST, "").trim().length() == 0) {
            e.putString(KEY_HOST, h);
        }
        e.apply();
    }

    /** 代理串；空表示直连。 */
    public static String proxy(Context ctx) {
        String p = prefs(ctx).getString(KEY_PROXY, "");
        return p == null ? "" : p.trim();
    }

    public static void saveProxy(Context ctx, String proxy) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return;   // r2/fixes 9：debug 驱动专用，release 里是空操作
        prefs(ctx).edit().putString(KEY_PROXY, proxy == null ? "" : proxy.trim()).apply();
    }

    /** 解析后的代理信息。 */
    static final class ProxySpec {
        final boolean socks;
        final String host;
        final int port;
        ProxySpec(boolean socks, String host, int port) {
            this.socks = socks; this.host = host; this.port = port;
        }
    }

    /**
     * 解析代理串。
     *
     * <p>支持：{@code socks5://host:port} / {@code socks://host:port} /
     * {@code http://host:port}；只有 {@code host:port} 时**按 SOCKS5 处理**
     * （本工程场景里最常见的是本机的 clash/v2ray socks 端口）。
     *
     * <p>⚠️ 两个已被文档明确的限制，不要去"修"：
     * ① JavaMail 的 SOCKS5 **不支持代理用户名/密码**（必须匿名/免认证）；
     * ② 走 HTTP 代理时 JavaMail 用 CONNECT 打隧道（{@code mail.imaps.proxy.*}）。
     */
    /**
     * 供自测：解析代理串的结果描述。
     *
     * @return {@code "socks5 host:port"} / {@code "http host:port"}；无法解析返回 null
     */
    public static String debugParseProxy(String s) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return "（仅 debug 包可用）";   // r2/fixes 9：debug 驱动专用，release 里是空操作
        ProxySpec p = parseProxy(s);
        return p == null ? null : ((p.socks ? "socks5 " : "http ") + p.host + ":" + p.port);
    }

    static ProxySpec parseProxy(String s) {        if (s == null) return null;
        String v = s.trim();
        if (v.length() == 0) return null;
        boolean socks = true;
        if (v.startsWith("http://")) { socks = false; v = v.substring(7); }
        else if (v.startsWith("https://")) { socks = false; v = v.substring(8); }
        else if (v.startsWith("socks5://")) { socks = true; v = v.substring(9); }
        else if (v.startsWith("socks://")) { socks = true; v = v.substring(8); }
        int c = v.lastIndexOf(':');
        if (c <= 0 || c == v.length() - 1) return null;
        String h = v.substring(0, c).trim();
        int port;
        try { port = Integer.parseInt(v.substring(c + 1).trim()); }
        catch (Throwable t) { return null; }
        if (h.length() == 0 || port <= 0 || port > 65535) return null;
        return new ProxySpec(socks, h, port);
    }

    /** 是否已存有授权码（设置页判断"留空=保留旧值"用；绝不返回授权码本身）。 */
    public static boolean hasPassword(Context ctx) {
        return pass(ctx).length() > 0;
    }

    public static boolean isConfigured(Context ctx) {
        return user(ctx).length() > 0 && pass(ctx).length() > 0;
    }

    /**
     * <b>写日志专用</b>的配置摘要 —— 只有「已配置/未配置 + 服务商」。
     * 不含邮箱地址（哪怕打码）、IMAP 服务器、端口、代理、凭据长度。logcat 对 root/adb 可读，且会被验收脚本整段回传。
     */
    public static String configLogInfo(Context ctx) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return "（仅 debug 包可用）";   // r2/fixes 9：debug 驱动专用，release 里是空操作
        return logSummary(isConfigured(ctx), provider(ctx));
    }

    /** {@link #configLogInfo} 的纯函数部分（JVM 可测）。provider 只接受短的字母数字服务商代号，其它一律不打。 */
    public static String logSummary(boolean configured, String provider) {
        String pv = provider == null ? "" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        if (!pv.matches("[a-z0-9]{1,12}")) pv = "";
        return (configured ? "邮件已配置" : "邮件未配置") + "，服务商=" + (pv.length() > 0 ? pv : "未指定");
    }

    /**
     * 脱敏后的配置摘要（**绝不含授权码**）—— 只给设置页界面显示（用户自己看自己的配置）。
     * <b>不要写进日志</b>：日志用 {@link #configLogInfo}（M-3）。
     */
    public static String configInfo(Context ctx) {
        if (!isConfigured(ctx)) {
            return "邮件未配置：缺" + (user(ctx).length() == 0 ? " 邮箱地址" : " 授权码/应用密码");
        }
        String u = user(ctx);
        String masked = u.length() <= 6 ? (u.charAt(0) + "***")
                : (u.substring(0, 3) + "***" + u.substring(u.length() - 6));
        StringBuilder sb = new StringBuilder();
        sb.append("邮件已配置 → ").append(masked).append(" @ ")
          .append(host(ctx)).append(":").append(port(ctx));   // ★ M-3：不再显示凭据长度
        String pv = provider(ctx);
        if (pv.length() > 0) sb.append("，服务商=").append(pv);
        String px = proxy(ctx);
        sb.append("，代理=").append(px.length() == 0 ? "直连" : px);
        if (px.length() == 0 && isLikelyBlocked(host(ctx))) {
            sb.append("  ⚠️ 境外邮箱：连通性取决于当前网络（实测本机网络可直连），连不上再配代理");
        }
        return sb.toString();
    }

    // ---------- 执行 ----------

    /**
     * 未配置时给用户 / 模型的下一步提示（P0-4）。模型据此可以换路：打开邮件 App + 识屏读列表。
     */
    public static final String NOT_CONFIGURED_HINT =
            "QQ邮箱需开启IMAP并使用授权码：网页版QQ邮箱 → 设置 → 账号 → 开启「IMAP/SMTP服务」→ 生成授权码，"
                    + "再到 OneSay 控制台 →「邮箱（IMAP）配置」填写邮箱地址与授权码（不是QQ密码）。"
                    + "未配置前可改用：打开邮件 App（app.launch）后用识屏（vision.screen.understand）读取邮件列表。";

    /**
     * 邮件能力执行入口（由 {@link UnifiedExecutor} 的 custom 分支委托）。
     */
    public static ExecutionResult execute(com.onesay.assistant.v2.capability.Capability cap,
                                          JSONObject args,
                                          Context ctx) {
        if (!isConfigured(ctx)) {
            // 未配置是"去配置 / 换路"的信号，不是参数错误（旧版误用 SCHEMA_INVALID，
            //   AgentLoop 看不出区别，只能如实说"做不到"）。
            return ExecutionResult.failed(ErrorCode.NOT_CONFIGURED,
                    "邮件还没配置。需要先填邮箱地址和凭据"
                            + "（QQ/163 用网页端生成的授权码；Gmail/Outlook 用「应用专用密码」）",
                    "mail not configured", NOT_CONFIGURED_HINT, cap, args);
        }
        mCtxForError = ctx.getApplicationContext();   // 供 describeError 判断"是否国内直连不通的服务商"
        if (args == null) args = new JSONObject();
        String sign = cap != null ? cap.sign : null;
        final Context fctx = ctx;
        final JSONObject fargs = args;
        final com.onesay.assistant.v2.capability.Capability fcap = cap;
        try {
            // 两处操作用 withRetry 包一层 —— 见 MAX_ATTEMPTS 处的实测依据。
            //   重试的是**整段操作**（连接 + 取信），所以"连上了但读一半被掐"也能救回来。
            if (SIGN_INBOX_LATEST.equals(sign)) {
                return withRetry("读收件箱", new MailOp() {
                    @Override public ExecutionResult run() throws Exception {
                        return readLatest(fctx, fargs, fcap);
                    }
                });
            }
            if (SIGN_DRAFT_NEW.equals(sign)) {
                return withRetry("存草稿", new MailOp() {
                    @Override public ExecutionResult run() throws Exception {
                        return createDraft(fctx, fargs, fcap);
                    }
                });
            }
            return ExecutionResult.failed(ErrorCode.UNKNOWN_CAPABILITY,
                    "未知的邮件操作", "unknown sign=" + sign, cap, args);
        } catch (Throwable t) {
            Log.e(TAG, "邮件执行异常: " + t.getClass().getSimpleName() + " " + t.getMessage());
            String hint = describeError(t);
            // 按原因给错误码（凭据/证书/超时/网络），模型据此决定"别重试/换路"
            return ExecutionResult.failed(classifyError(t),
                    "邮件操作失败：" + hint, t.getClass().getSimpleName() + ": " + t.getMessage(),
                    cap, args);
        }
    }

    /** 把底层异常翻译成用户能懂的话（IMAP 的报错原文对普通用户毫无意义）。 */
    private static String describeError(Throwable t) {
        String m = t == null ? "" : String.valueOf(t.getMessage());
        String low = m.toLowerCase();
        if (low.contains("authentication") || (low.contains("auth") && low.contains("fail"))
                || low.contains("login")) {
            return "登录被拒绝 —— 多半是用了登录密码而不是「授权码 / 应用专用密码」，"
                    + "或者该凭据已失效（可去邮箱设置里重置一个）";
        }
        if (low.contains("unknownhost") || low.contains("unable to resolve")
                || low.contains("nodename nor servname")) {
            if (mCtxForError != null && isLikelyBlocked(host(mCtxForError))) {
                return "连不上邮件服务器（域名解析失败）—— 这是境外邮箱，"
                        + "连通性取决于当前网络；可配代理（--es mailproxy），"
                        + "或把它**自动转发到国内邮箱**后代收（更省事）";
            }
            return "找不到服务器地址（检查网络、服务器名与 DNS）";
        }
        if (low.contains("timeout") || low.contains("timed out")
                || low.contains("connect failed") || low.contains("connection refused")
                || low.contains("socket") || low.contains("sockettimeout")) {
            if (mCtxForError != null && isLikelyBlocked(host(mCtxForError))
                    && proxy(mCtxForError).length() == 0) {
                return "连接失败/超时 —— 这是境外邮箱，当前网络可能到不了；"
                        + "可配代理（--es mailproxy \"socks5://127.0.0.1:1080\"），"
                        + "或把它**自动转发到国内邮箱**后代收（更省事，且不必常开代理）";
            }
            return "连接超时（网络不通或服务器没响应）";
        }
        if (isTlsValidationFailure(t)) {
            return "安全连接失败：服务器证书校验未通过（可能是网络被劫持、系统时间不对，"
                    + "或老系统根证书过旧）。为保护授权码，已拒绝连接";
        }
        if (low.contains("ssl") || low.contains("handshake")) {
            return "TLS 握手中断（网络不稳，可稍后重试）";
        }
        if (low.contains("folder") && low.contains("not")) {
            return "找不到该邮件夹";
        }
        return m.length() > 0 ? m : t.getClass().getSimpleName();
    }

    /**
     * 供 {@link #describeError} 判断"是不是国内直连不通的服务商"。
     * 异常翻译发生在静态方法里、又需要读配置，故用一次性的会话级引用传递
     * （只在执行期间赋值，不做长期持有）。
     */
    private static volatile Context mCtxForError;

    /**
     * boundary 宽容开关**必须同时写系统属性**。
     *
     * <p>原因：JavaMail 解析多部分正文时（{@code MimeBodyPart.getContent()}）读的是
     * **系统属性**（内部走 {@code Session.getDefaultInstance(System.getProperties())}），
     * 只写进 Session 的 properties 不一定生效。两处都写才稳。
     * 只影响"解析容错"，不影响证书校验或安全策略。
     */
    static {
        try {
            System.setProperty("mail.mime.multipart.ignoremissingboundaryparameter", "true");
            System.setProperty("mail.mime.multipart.ignoreexistingboundaryparameter", "true");
            System.setProperty("mail.mime.multipart.ignoremissingendboundary", "true");
        } catch (Throwable ignored) {
        }
    }

    /*
     * ★ R14 根因（10-15 真机：LLM 拿到的邮件正文是 com.sun.mail.util.BASE64DecoderStream@…）：
     *
     * JavaMail 把「text/plain → String」「multipart/* → MimeMultipart」这些转换登记在 jar 里的 META-INF/mailcap。
     * 本工程用 d8 打包，jar 里的 META-INF 资源不进 APK（已核对 R13b APK：没有 META-INF/mailcap），于是
     * Part.getContent() 找不到 DataContentHandler，只能把（已做传输解码的）原始流丢回来 ——
     * text/plain 的 c.toString() 就成了 BASE64DecoderStream@…；multipart 拿到 IMAPInputStream
     * （10-06 那次「IMAPInputStream cannot be cast to Multipart」也是它）。
     *
     * 修法：不去补登记 mailcap（JVM 单测里补登记的 text_plain handler 直接 VerifyError「Bad return type」，真机上同样没法保证），
     * 而是让 extractBody 自己不依赖 handler：getContent() 不是 String 时按 Content-Type 的 charset 解码流里的字节，
     * multipart 用 new MimeMultipart(DataSource) 自己解析。有没有 mailcap 都能得到正文。
     */

    /** 排序用时间戳：优先 sentDate，缺失时退回 receivedDate，都没有则 0（排到最后）。 */
    private static long sortDate(javax.mail.Message m) {
        try {
            Date d = m.getSentDate();
            if (d == null) d = m.getReceivedDate();
            return d == null ? 0L : d.getTime();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 组装 JavaMail 会话属性。 */
    static Properties props(Context ctx) {
        return props(host(ctx), port(ctx), proxy(ctx));
    }

    /** 纯函数版（JVM 单测断言：永远严格校验证书与主机名）。 */
    static Properties props(String host, int port, String proxySpec) {
        Properties p = new Properties();
        p.put("mail.store.protocol", "imaps");
        p.put("mail.imaps.host", host);
        p.put("mail.imaps.port", String.valueOf(port));
        p.put("mail.imaps.ssl.enable", "true");
        p.put("mail.imaps.connectiontimeout", "10000");
        p.put("mail.imaps.timeout", "15000");
        // ★boundary 宽容开关（实测 QQ 邮箱连读 3 封正文全空，根因就是它）。
        //   有些邮件头写着 multipart/…，但 boundary 参数缺失或格式不规范，
        //   JavaMail 默认会解析失败并**退回原始流** → 我们的正文抽取拿到 IMAPInputStream
        //   而不是 Multipart（旧代码在这里 ClassCastException，正文整封丢）。
        //   这三个是 JavaMail 官方给的宽容开关，让它在 boundary 不标准时也能解析出结构。
        p.put("mail.mime.multipart.ignoremissingboundaryparameter", "true");
        p.put("mail.mime.multipart.ignoreexistingboundaryparameter", "true");
        p.put("mail.mime.multipart.ignoremissingendboundary", "true");
        // **永远**严格校验 —— 系统信任库校验证书链 + JavaMail 校验主机名。
        //   旧版在首次握手失败后自动降级为 ssl.trust=* 且关闭主机名校验：中间人只要让第一次握手失败，
        //   就能在第二次拿到邮箱授权码。现已删除降级；证书问题以 TLS_ERROR 明确上报（见 classifyError）。
        //   注意：不设置 mail.imaps.ssl.trust（= 使用系统默认 TrustManager）。
        p.put("mail.imaps.ssl.checkserveridentity", "true");

        // ★ 代理（可选）。IMAP 是 TCP 993，**不是 HTTP**，所以必须按协议配：
        //   · SOCKS5 → mail.imaps.socks.host / socks.port（JavaMail 不支持代理账号密码）
        //   · HTTP   → mail.imaps.proxy.host / proxy.port（JavaMail 会用 CONNECT 打隧道）
        //   另一种更省事的做法是让**系统 VPN** 兜住（Android 的 VpnService 对 App 透明，
        //   JavaMail 的 socket 会自动走 VPN，这里一行都不用配）。
        ProxySpec ps = parseProxy(proxySpec);
        if (ps != null) {
            if (ps.socks) {
                p.put("mail.imaps.socks.host", ps.host);
                p.put("mail.imaps.socks.port", String.valueOf(ps.port));
            } else {
                p.put("mail.imaps.proxy.host", ps.host);
                p.put("mail.imaps.proxy.port", String.valueOf(ps.port));
            }
        }
        return p;
    }

    // ==================== 链路抖动重试（） ====================
    //
    // 【为什么必须有这一层】实测（经代理 VPN 出网时）：
    //   对 imap.gmail.com:993 连跑 3 次，**只有 1 次**拿到服务器的 AUTHENTICATIONFAILED
    //   （= TLS + IMAP 全链路打通），另 2 次在建连途中被对端掐断：
    //     #1  8121ms  Connection closed by peer
    //     #2 10118ms  Connection closed by peer
    //     #3  4462ms  [AUTHENTICATIONFAILED] Invalid credentials   ← 成功
    //   设备上的 Gmail Go 体感正常，靠的就是**长连接 + 自动重试**；我们原来是单次尝试，
    //   于是同一条线路上"成功率 1/3"直接表现为"功能经常莫名其妙失败"。
    //
    // 【为什么必须区分两类错误】
    //   · 链路抖动（Connection closed by peer / reset / timeout / EOF）→ **该重试**，退避再来即可成功；
    //   · 凭据错误（AuthenticationFailed）→ **绝不能重试**：它是确定性的，重试只会白等；
    //     更严重的是**反复用错凭据登录会触发服务商风控** —— 本机收件箱里 10-04 就躺着
    //     一条「重要安全提醒：登录尝试已被阻止」，正是这类行为的产物。
    //   所以下面的 isTransient() 里，认证失败被**显式排除**。

    /** 单次操作最大尝试次数（含首次）。3 次足以覆盖实测的 ~1/3 连通率。 */
    private static final int MAX_ATTEMPTS = 3;

    /** 退避基数：第 n 次失败后等 RETRY_BASE_DELAY_MS × n 毫秒。 */
    private static final long RETRY_BASE_DELAY_MS = 700L;

    /** 仅用于真机探针展示（见 --ez mailretryinfo）。 */
    public static int maxAttempts() { return MAX_ATTEMPTS; }

    /** 仅用于真机探针展示。 */
    public static long retryBaseDelayMs() { return RETRY_BASE_DELAY_MS; }

    /** 可重试操作（Java 7，无 lambda，故用单方法接口 + 匿名类）。 */
    private interface MailOp {
        ExecutionResult run() throws Exception;
    }

    /** 异常链里是否有"认证失败"（凭据类，确定性，绝不重试）。 */
    public static boolean hasAuthFailure(Throwable t) {
        int guard = 0;
        for (Throwable c = t; c != null && guard++ < 8; c = c.getCause()) {
            if (c instanceof javax.mail.AuthenticationFailedException) return true;
        }
        return false;
    }

    /**
     * 是不是「链路抖动」类错误（可以重试）。
     *
     * <p>判据分两层：先看异常类型，再看消息文本 —— 因为不同 JDK/Android 版本对
     * "对端掐断"抛的类型不一致（有 SocketException，也有包装成 MessagingException 的）。
     * 消息文本只用来**放宽**判定，不会把认证失败误判进来（它已被 hasAuthFailure 挡在前面）。
     */
    public static boolean isTransient(Throwable t) {
        if (hasAuthFailure(t)) return false;
        // 证书/主机名校验失败是确定性的，重试只会重复失败（也不再降级），不算抖动
        if (isTlsValidationFailure(t)) return false;
        int guard = 0;
        for (Throwable c = t; c != null && guard++ < 8; c = c.getCause()) {
            if (c instanceof java.net.SocketException
                    || c instanceof java.net.SocketTimeoutException
                    || c instanceof java.io.EOFException
                    || c instanceof java.io.InterruptedIOException
                    || c instanceof javax.net.ssl.SSLException) {
                return true;
            }
            String m = c.getMessage();
            if (m != null) {
                String low = m.toLowerCase();
                if (low.contains("connection closed by peer") || low.contains("connection reset")
                        || low.contains("broken pipe") || low.contains("timed out")
                        || low.contains("timeout") || low.contains("connect failed")
                        || low.contains("connection refused") || low.contains("end of stream")
                        || low.contains("unexpected end")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 是否为 TLS 证书链 / 主机名校验失败（确定性，绝不重试、绝不降级）。
     *
     * <p>判据：异常链里出现 {@code CertificateException} / {@code CertPathValidatorException} /
     * {@code SSLPeerUnverifiedException}，或消息含 Android/JavaMail 的典型措辞
     * （"Trust anchor for certification path not found"、"Server is not trusted"、
     * "Can't verify identity of server"、"hostname ... not verified"）。
     * 普通的 SSLException（如握手中途"Connection closed by peer"）仍按链路抖动处理。
     */
    public static boolean isTlsValidationFailure(Throwable t) {
        int guard = 0;
        for (Throwable c = t; c != null && guard++ < 8; c = c.getCause()) {
            if (c instanceof java.security.cert.CertificateException
                    || c instanceof java.security.cert.CertPathValidatorException
                    || c instanceof javax.net.ssl.SSLPeerUnverifiedException) {
                return true;
            }
            String m = c.getMessage();
            if (m != null) {
                String low = m.toLowerCase();
                if (low.contains("trust anchor") || low.contains("certification path")
                        || low.contains("server is not trusted") || low.contains("can't verify identity")
                        || low.contains("not verified") || low.contains("certificate")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 异常 → 结构化错误码（纯函数，JVM 单测覆盖）。顺序即优先级：凭据 > 证书 > 超时 > 网络 > 其它。
     */
    public static ErrorCode classifyError(Throwable t) {
        if (t == null) return ErrorCode.EXEC_FAILED;
        if (hasAuthFailure(t)) return ErrorCode.AUTH_FAILED;
        String low = String.valueOf(t.getMessage()).toLowerCase();
        if (low.contains("authenticationfailed") || low.contains("authentication failed")
                || (low.contains("auth") && low.contains("fail")) || low.contains("login fail")
                || low.contains("invalid credentials")) {
            return ErrorCode.AUTH_FAILED;
        }
        if (isTlsValidationFailure(t)) return ErrorCode.TLS_ERROR;
        int guard = 0;
        for (Throwable c = t; c != null && guard++ < 8; c = c.getCause()) {
            if (c instanceof java.net.SocketTimeoutException) return ErrorCode.TIMEOUT;
        }
        if (low.contains("timed out") || low.contains("timeout")) return ErrorCode.TIMEOUT;
        guard = 0;
        for (Throwable c = t; c != null && guard++ < 8; c = c.getCause()) {
            if (c instanceof java.net.UnknownHostException
                    || c instanceof java.net.ConnectException
                    || c instanceof java.net.SocketException
                    || c instanceof java.io.EOFException
                    || c instanceof javax.net.ssl.SSLException) {
                return ErrorCode.NETWORK_ERROR;
            }
        }
        if (isTransient(t)) return ErrorCode.NETWORK_ERROR;
        return ErrorCode.EXEC_FAILED;
    }

    /**
     * 带退避的重试外壳：只对「链路抖动」重试；凭据错误等确定性失败**立刻上报**。
     *
     * <p>注意日志用 [MAIL-RETRY] 前缀，便于真机排查"这次到底是重试救回来的，还是本来就通"。
     */
    private static ExecutionResult withRetry(final String what, final MailOp op) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return op.run();
            } catch (Exception e) {
                last = e;
                if (!isTransient(e) || attempt >= MAX_ATTEMPTS) throw e;
                long delay = RETRY_BASE_DELAY_MS * attempt;
                Log.w(TAG, "[MAIL-RETRY] " + what + " 第 " + attempt + " 次失败（链路抖动："
                        + e.getClass().getSimpleName() + " " + e.getMessage()
                        + "），" + delay + "ms 后重试（第 " + (attempt + 1) + "/" + MAX_ATTEMPTS + " 次）");
                Thread.sleep(delay);
            }
        }
        throw last;
    }

    /**
     * 建立 IMAP 连接（严格证书 + 主机名校验）。
     *
     * <p>删除"失败后降级为 trust=*"。Android 8 根证书库较旧导致的个别服务器验不过，
     * 现在会以 {@link ErrorCode#TLS_ERROR} 明确报出（提示检查网络劫持 / 系统时间 / 换服务器地址），
     * 而不是悄悄放弃校验把授权码交给可能的中间人。
     */
    private static javax.mail.Store openStore(Context ctx) throws Exception {
        return connect(ctx);
    }

    private static javax.mail.Store connect(Context ctx) throws Exception {
        javax.mail.Session s = javax.mail.Session.getInstance(props(ctx), null);
        javax.mail.Store store = s.getStore("imaps");
        store.connect(host(ctx), port(ctx), user(ctx), pass(ctx));
        return store;
    }

    /** ★ 11-04 F：摘要模式最多看最近多少封的头部。 */
    static final int DIGEST_SCAN = 20;

    // ---------- 读收件箱最新 N 封 ----------

    private static ExecutionResult readLatest(Context ctx, JSONObject args,
                                              com.onesay.assistant.v2.capability.Capability cap) throws Exception {
        int count = args.optInt("count", 1);
        if (count < 1) count = 1;
        if (count > 5) count = 5;
        boolean withBody = !args.has("body") || args.optBoolean("body", true);
        String folderName = args.optString("folder", "INBOX");

        javax.mail.Store store = null;
        javax.mail.Folder folder = null;
        try {
            store = openStore(ctx);
            folder = store.getFolder(folderName);
            if (!folder.exists()) {
                return ExecutionResult.failed(ErrorCode.EXEC_FAILED,
                        "邮箱里找不到「" + folderName + "」这个邮件夹", "folder not exists", cap, args);
            }
            folder.open(javax.mail.Folder.READ_ONLY);

            int total = folder.getMessageCount();
            if (total <= 0) {
                return ExecutionResult.success(cap, args, "收件箱是空的，没有邮件。", false, "count=0");
            }
            // ★「最新」必须**按时间**取，不能信 IMAP 序号顺序。
            //
            //   实测（QQ 邮箱，同一配置连跑）：
            //     fetch(1) → 总数 973，最后一封是 10-05 的 Microsoft 邮件
            //     fetch(5) → 总数 1002，最后 5 封却是 07-31 ~ 08-05 的邮件
            //   ⇒ 这台服务器上"序号最大"和"时间最新"**对不上**，而且 getMessageCount()
            //     本身也在抖（973 / 1002 / 1127）。照原样取"最后 N 个序号"，
            //     用户问"读第一条邮件"会读到几个月前的旧信 —— 是答错，不是答不全。
            //
            //   修法：**首选服务端按收信时间检索**（ReceivedDateTerm → IMAP SINCE），
            //   这在语义上直接就是"最近收到的邮件"，完全绕开序号顺序问题；
            //   检索不到（服务器不支持/时间窗内无信）再退回"序号窗口 + 本地按时间排序"。
            // ★ int/1102：按类型 / 发件人 / 内容读（用户 2026-11-03：先问想看哪类，再按答案读）
            // ★ 11-04 午（「展开第一封」25 s → ≤8 s）：根因 = 按标题过滤时服务端中文检索 0 封 → 退回逐封取最近 200 封的头部（每封一次往返，23 s）。
            //   摘要时记下每封的 UID，展开时按 UID 直接取那一封（一次往返）+ 本地短摘要；UID 对不上 / 没有才退回按标题过滤。
            long wantUid = parseUid(args.optString("uid", ""));
            if (wantUid > 0 && folder instanceof javax.mail.UIDFolder) {
                javax.mail.Message one = null;
                try {
                    one = ((javax.mail.UIDFolder) folder).getMessageByUID(wantUid);
                } catch (Throwable t) {
                    Log.w(TAG, "[MAIL] 按 UID 取信失败，退回按标题: " + t.getMessage());
                }
                String wantSubj = args.optString("keyword", "");
                if (one != null && (wantSubj.length() == 0 || safe(one.getSubject()).contains(wantSubj))) {
                    String body = extractBody(one);
                    String out = MailDigest.expandReply(args.optInt("index", 1), safe(addr(one.getFrom())), safe(one.getSubject()),
                            sortDate(one), body, System.currentTimeMillis());
                    Log.i(TAG, "[MAIL] 按 UID 直读 1 封，输出 " + out.length() + " 字");
                    return ExecutionResult.success(cap, args, out, true, "主题：" + safe(one.getSubject()) + "\n" + out);
                }
                Log.i(TAG, "[MAIL] UID " + wantUid + " 没取到 / 标题对不上，退回按标题过滤");
            }
            String fFilter = args.optString("filter", ""), fFrom = args.optString("from", ""), fKeyword = args.optString("keyword", "");
            if (fFilter.length() > 0 || fFrom.length() > 0 || fKeyword.length() > 0) {
                return readFiltered(folder, total, count, withBody, fFilter, fFrom, fKeyword, cap, args);
            }
            javax.mail.Message[] candidates = null;
            try {
                Date since = new Date(System.currentTimeMillis()
                        - RECENT_DAYS * 24L * 3600L * 1000L);
                candidates = folder.search(new javax.mail.search.ReceivedDateTerm(
                        javax.mail.search.ComparisonTerm.GE, since));
                Log.i(TAG, "[MAIL] 服务端按收信时间检索：最近 " + RECENT_DAYS
                        + " 天内 " + candidates.length + " 封（总数 " + total + "）");
            } catch (Throwable t) {
                Log.w(TAG, "按收信时间检索失败，退回序号窗口: " + t.getMessage());
            }
            if (candidates == null || candidates.length == 0) {
                int window = Math.min(total, Math.max(count, DATE_WINDOW));
                int from = Math.max(1, total - window + 1);
                candidates = folder.getMessages(from, total);
                Log.i(TAG, "[MAIL] 退回序号窗口：取序号 " + from + "~" + total + "（" + candidates.length + " 封）");
            }

            // 按时间倒序（sentDate 缺失时退回 receivedDate），最新的排在最前
            java.util.List<javax.mail.Message> ordered =
                    new java.util.ArrayList<javax.mail.Message>(java.util.Arrays.asList(candidates));
            java.util.Collections.sort(ordered, new java.util.Comparator<javax.mail.Message>() {
                @Override public int compare(javax.mail.Message a, javax.mail.Message b) {
                    long ta = sortDate(a), tb = sortDate(b);
                    return (ta == tb) ? 0 : (ta > tb ? -1 : 1);   // 大在前
                }
            });
            // ★ 11-04 F/G：摘要模式 —— 只读头部（READ_ONLY，不改已读状态），口径 = 收件箱未读数 + 今天新收；挑 2–4 封非广告
            if (args.optBoolean("digest", false)) {
                int unread = -1;
                try {
                    unread = folder.getUnreadMessageCount();
                } catch (Throwable t) {
                    Log.w(TAG, "[MAIL] 未读数读取失败: " + t.getMessage());
                }
                java.util.List<MailDigest.Item> items = new java.util.ArrayList<MailDigest.Item>();
                java.util.List<javax.mail.Message> head = ordered.subList(0, Math.min(DIGEST_SCAN, ordered.size()));
                prefetchHeaders(folder, head.toArray(new javax.mail.Message[0]));   // ★ 11-04 午：一次 FETCH 取完头部 + 标志 + UID
                for (int i = 0; i < head.size(); i++) {
                    javax.mail.Message m = head.get(i);
                    long uid = -1L;
                    try {
                        if (folder instanceof javax.mail.UIDFolder) uid = ((javax.mail.UIDFolder) folder).getUID(m);
                    } catch (Throwable ignored) {
                    }
                    items.add(new MailDigest.Item(safe(addr(m.getFrom())), safe(m.getSubject()), sortDate(m),
                            m.isSet(javax.mail.Flags.Flag.SEEN), m.isSet(javax.mail.Flags.Flag.FLAGGED), uid));
                }
                String digest = MailDigest.render(unread, items, System.currentTimeMillis(), args.optBoolean("value", false));
                StringBuilder raw = new StringBuilder();
                for (MailDigest.Item it : items) raw.append(it.from).append(" | ").append(it.subject).append('\n');
                Log.i(TAG, "[MAIL] 摘要：未读 " + unread + "，窗口 " + items.size() + " 封，输出 " + digest.length() + " 字");
                return ExecutionResult.success(cap, args, digest, true, raw.toString());
            }
            int n = Math.min(count, ordered.size());
            javax.mail.Message[] msgs = new javax.mail.Message[n];
            for (int i = 0; i < n; i++) msgs[i] = ordered.get(i);

            StringBuilder sb = new StringBuilder();
            sb.append("收件箱共 ").append(total).append(" 封，最新 ")
              .append(msgs.length).append(" 封如下：\n");
            // msgs 已在上面按时间倒序排好（msgs[0] = 最新），
            //   所以这里**正序**输出 + 编号从 1 开始递增即可：1 = 最新。
            //   旧代码把这两处都写反了（既倒着打印，又用
            //   `idx = i - msgs.length + count` 算标签）⇒ 实测最新一封被标成 [4]、
            //   最旧的标成 [0]，模型很容易把最旧的当成"第一条"。
            for (int i = 0; i < msgs.length; i++) {
                javax.mail.Message m = msgs[i];
                int idx = i + 1;                        // 1 = 最新
                sb.append("\n[").append(idx).append("] ");
                sb.append("发件人：").append(safe(addr(m.getFrom()))).append("\n");
                sb.append("主题：").append(safe(m.getSubject())).append("\n");
                Date d = m.getSentDate();
                if (d == null) d = m.getReceivedDate();
                if (d != null) sb.append("时间：").append(fmt(d)).append("\n");
                boolean seen = m.isSet(javax.mail.Flags.Flag.SEEN);
                sb.append("状态：").append(seen ? "已读" : "未读").append("\n");
                if (withBody) {
                    String body = extractBody(m);
                    if (body != null && body.length() > 0) {
                        int cut = Math.min(body.length(), BODY_PREVIEW);
                        sb.append("正文").append(body.length() > BODY_PREVIEW
                                ? "（前 " + BODY_PREVIEW + " 字，全文 " + body.length() + " 字）"
                                : "").append("：").append(body.substring(0, cut)).append("\n");
                    } else {
                        sb.append("正文：（这封没取到可读正文）\n");
                    }
                }
            }
            String out = sb.toString();
            Log.i(TAG, "[MAIL] 读取成功，共 " + msgs.length + " 封，输出 " + out.length() + " 字");
            return ExecutionResult.success(cap, args, out, true, out);
        } finally {
            closeQuietly(folder, store);
        }
    }

    /** ★ 11-04 午：批量取头部（信封 + 标志 + UID），一条 IMAP FETCH 代替逐封往返。失败不影响后面逐封取。 */
    static void prefetchHeaders(javax.mail.Folder folder, javax.mail.Message[] msgs) {
        if (folder == null || msgs == null || msgs.length == 0) return;
        try {
            javax.mail.FetchProfile fp = new javax.mail.FetchProfile();
            fp.add(javax.mail.FetchProfile.Item.ENVELOPE);
            fp.add(javax.mail.FetchProfile.Item.FLAGS);
            fp.add(javax.mail.UIDFolder.FetchProfileItem.UID);
            long t0 = System.currentTimeMillis();
            folder.fetch(msgs, fp);
            Log.i(TAG, "[MAIL] 批量取头部 " + msgs.length + " 封 " + (System.currentTimeMillis() - t0) + "ms");
        } catch (Throwable t) {
            Log.w(TAG, "[MAIL] 批量取头部失败（逐封取）: " + t.getMessage());
        }
    }

    /** 「展开」带来的 UID（字符串，空 / 非数字 → -1）。纯函数。 */
    public static long parseUid(String s) {
        if (s == null) return -1L;
        String t = s.trim();
        if (!t.matches("\\d{1,12}")) return -1L;
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /** ★ int/1102：带过滤读信的标题（纯函数，JVM 单测）。 */
    public static String filterLabel(String filter, String from, String keyword) {
        if ("unread".equals(filter)) return "未读邮件";
        if ("flagged".equals(filter)) return "重要（星标）邮件";
        if (from != null && from.length() > 0) return "「" + from + "」发来的邮件";
        if (keyword != null && keyword.length() > 0) return "提到「" + keyword + "」的邮件";
        return "邮件";
    }

    /** ★ int/1102：本地兜底过滤（服务端检索不支持中文 / 失败时）：发件人看名字 + 地址，关键词看主题。纯函数。 */
    public static boolean localMatch(String fromText, String subject, String from, String keyword) {
        String f = fromText == null ? "" : fromText.toLowerCase(java.util.Locale.ROOT);
        String s = subject == null ? "" : subject.toLowerCase(java.util.Locale.ROOT);
        if (from != null && from.length() > 0 && !f.contains(from.toLowerCase(java.util.Locale.ROOT))) return false;
        if (keyword != null && keyword.length() > 0 && !s.contains(keyword.toLowerCase(java.util.Locale.ROOT))) return false;
        return true;
    }

    private static ExecutionResult readFiltered(javax.mail.Folder folder, int total, int count, boolean withBody,
                                                String filter, String from, String keyword,
                                                com.onesay.assistant.v2.capability.Capability cap, JSONObject args) throws Exception {
        String label = filterLabel(filter, from, keyword);
        javax.mail.search.SearchTerm term = null;
        if ("unread".equals(filter)) term = new javax.mail.search.FlagTerm(new javax.mail.Flags(javax.mail.Flags.Flag.SEEN), false);
        else if ("flagged".equals(filter)) term = new javax.mail.search.FlagTerm(new javax.mail.Flags(javax.mail.Flags.Flag.FLAGGED), true);
        else if (from.length() > 0) term = new javax.mail.search.FromStringTerm(from);
        else if (keyword.length() > 0) term = new javax.mail.search.OrTerm(new javax.mail.search.SubjectTerm(keyword), new javax.mail.search.BodyTerm(keyword));
        javax.mail.Message[] found = null;
        boolean local = false;
        try {
            found = folder.search(term);
            Log.i(TAG, "[MAIL] 过滤检索 " + label + "：服务端 " + found.length + " 封");
        } catch (Throwable t) {
            Log.w(TAG, "[MAIL] 服务端过滤检索失败，改本地过滤: " + t.getMessage());
        }
        if ((found == null || found.length == 0) && (from.length() > 0 || keyword.length() > 0)) {
            // 服务端不认中文检索时常返回 0：取最近窗口本地按发件人 / 主题过滤
            int window = Math.min(total, Math.max(200, DATE_WINDOW));
            javax.mail.Message[] win = folder.getMessages(Math.max(1, total - window + 1), total);
            prefetchHeaders(folder, win);   // ★ 11-04 午：一次 FETCH 取 200 封头部（原来逐封往返约 23 s）
            java.util.List<javax.mail.Message> keep = new java.util.ArrayList<javax.mail.Message>();
            for (javax.mail.Message m : win) {
                try {
                    if (localMatch(addr(m.getFrom()), m.getSubject(), from, keyword)) keep.add(m);
                } catch (Throwable ignored) {
                }
            }
            found = keep.toArray(new javax.mail.Message[0]);
            local = true;
            Log.i(TAG, "[MAIL] 本地过滤（最近 " + window + " 封）" + label + "：" + found.length + " 封");
        }
        if (found == null || found.length == 0) {
            String none = "没有" + label + (local ? "（查了最近的邮件）" : "") + "。";
            return ExecutionResult.success(cap, args, none, false, "count=0");
        }
        java.util.List<javax.mail.Message> ordered = new java.util.ArrayList<javax.mail.Message>(java.util.Arrays.asList(found));
        java.util.Collections.sort(ordered, new java.util.Comparator<javax.mail.Message>() {
            @Override public int compare(javax.mail.Message a, javax.mail.Message b) {
                long ta = sortDate(a), tb = sortDate(b);
                return (ta == tb) ? 0 : (ta > tb ? -1 : 1);
            }
        });
        int n = Math.min(count, ordered.size());
        StringBuilder sb = new StringBuilder();
        sb.append(label).append("共 ").append(ordered.size()).append(" 封，最新 ").append(n).append(" 封如下：\n");
        for (int i = 0; i < n; i++) {
            javax.mail.Message m = ordered.get(i);
            sb.append("\n[").append(i + 1).append("] ");
            sb.append("发件人：").append(safe(addr(m.getFrom()))).append("\n");
            sb.append("主题：").append(safe(m.getSubject())).append("\n");
            Date d = m.getSentDate();
            if (d == null) d = m.getReceivedDate();
            if (d != null) sb.append("时间：").append(fmt(d)).append("\n");
            sb.append("状态：").append(m.isSet(javax.mail.Flags.Flag.SEEN) ? "已读" : "未读").append("\n");
            if (withBody) {
                String body = extractBody(m);
                if (body != null && body.length() > 0) {
                    int cut = Math.min(body.length(), BODY_PREVIEW);
                    sb.append("正文：").append(body.substring(0, cut)).append("\n");
                }
            }
        }
        String out = sb.toString();
        Log.i(TAG, "[MAIL] 过滤读取成功 " + label + "，" + n + " 封，输出 " + out.length() + " 字");
        return ExecutionResult.success(cap, args, out, true, out);
    }

    // ---------- 新建草稿 ----------

    private static ExecutionResult createDraft(Context ctx, JSONObject args,
                                               com.onesay.assistant.v2.capability.Capability cap) throws Exception {
        String subject = args.optString("subject", "");
        String body = args.optString("body", "");
        String to = args.optString("to", "");
        if (subject.trim().length() == 0 && body.trim().length() == 0) {
            return ExecutionResult.failed(ErrorCode.NEED_PARAM,
                    "草稿要有主题或正文，你现在什么都没给我", "empty subject+body", cap, args);
        }

        javax.mail.Store store = null;
        javax.mail.Folder drafts = null;
        try {
            store = openStore(ctx);
            drafts = resolveDraftsFolder(store);
            if (drafts == null) {
                return ExecutionResult.failed(ErrorCode.EXEC_FAILED,
                        "这个邮箱里找不到「草稿箱」", "no drafts folder", cap, args);
            }
            drafts.open(javax.mail.Folder.READ_WRITE);

            javax.mail.Session s = javax.mail.Session.getInstance(props(ctx), null);
            javax.mail.internet.MimeMessage msg = new javax.mail.internet.MimeMessage(s);
            msg.setFrom(new javax.mail.internet.InternetAddress(user(ctx)));
            if (to.trim().length() > 0) {
                msg.setRecipients(javax.mail.Message.RecipientType.TO,
                        javax.mail.internet.InternetAddress.parse(to, false));
            }
            msg.setSubject(subject, "UTF-8");
            msg.setText(body, "UTF-8");
            msg.setSentDate(new Date());
            drafts.appendMessages(new javax.mail.Message[]{ msg });

            String out = "草稿已保存到草稿箱：主题「" + (subject.length() > 0 ? subject : "(无主题)")
                    + "」" + (to.trim().length() > 0 ? "，收件人 " + to : "")
                    + "，正文 " + body.length() + " 字。";
            // ★ M-3：日志不打主题/收件人原文（个人数据），只打长度与有无
            Log.i(TAG, "[MAIL] 草稿已保存: 主题 " + subject.length() + " 字，收件人"
                    + (to.trim().length() > 0 ? "已填" : "无") + "，正文 " + body.length() + " 字");
            return ExecutionResult.success(cap, args, out, true, out);
        } finally {
            closeQuietly(drafts, store);
        }
    }

    /** 在服务器上找草稿箱：先按标准名，再按常见中文名。 */
    private static javax.mail.Folder resolveDraftsFolder(javax.mail.Store store) {
        String[] names = { "Drafts", "Draft", "草稿箱", "草稿" };
        for (String n : names) {
            try {
                javax.mail.Folder f = store.getFolder(n);
                if (f != null && f.exists()) return f;
            } catch (Throwable t) {
                Log.w(TAG, "[MAIL] 打开文件夹 " + n + " 异常，试下一个: " + t.getMessage());   // 审计 B-8
            }
        }
        return null;
    }

    // ---------- 工具 ----------

    private static void closeQuietly(javax.mail.Folder f, javax.mail.Store s) {
        try { if (f != null && f.isOpen()) f.close(false); } catch (Throwable ignored) {}
        try { if (s != null && s.isConnected()) s.close(); } catch (Throwable ignored) {}
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String addr(javax.mail.Address[] a) {
        if (a == null || a.length == 0) return "(无)";
        try {
            return javax.mail.internet.MimeUtility.decodeText(a[0].toString());
        } catch (Throwable t) {
            return a[0].toString();
        }
    }

    private static String fmt(Date d) {
        try {
            return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(d);
        } catch (Throwable t) {
            return d.toString();
        }
    }

    /**
     * 抽取正文纯文本：优先 text/plain；只有 HTML 时剥标签。
     * 递归处理 multipart（邮件正文常见 multipart/alternative + 附件混合）。
     */
    static String extractBody(javax.mail.Part p) {
        try {
            if (p.isMimeType("text/plain")) {
                return contentText(p);
            }
            if (p.isMimeType("text/html")) {
                return stripHtml(contentText(p));
            }
            if (p.isMimeType("multipart/*")) {
                Object c;
                try {
                    c = p.getContent();
                } catch (Throwable t) {
                    c = null;
                }
                if (!(c instanceof javax.mail.Multipart)) {
                    // ★ R14：没有 mailcap 时 getContent() 给的是流 —— 直接用 DataSource 构造 MimeMultipart（不依赖 mailcap）
                    try {
                        if (c instanceof java.io.InputStream) {
                            try { ((java.io.InputStream) c).close(); } catch (Throwable ignored) { }
                        }
                        c = new javax.mail.internet.MimeMultipart(p.getDataHandler().getDataSource());
                    } catch (Throwable t) {
                        c = p.getInputStream();   // 解析不了再退回原始流兜底（下面 firstTextFromRawMime）
                    }
                }
                // ★**不能直接强转 Multipart**。
                //   现象：QQ 邮箱连读 3 封，正文**全部**为空，日志只有一句
                //     `正文抽取失败: com.sun.mail.imap.IMAPInputStream cannot be cast to javax.mail.Multipart`
                //   成因：邮件头写着 multipart/…，但 JavaMail 解析不出可用的 boundary
                //   （参数缺失或格式不规范），于是 getContent() 退回**原始流**；
                //   而 isMimeType("multipart/*") 是按**头部字符串**判断的 —— 两者不一致，
                //   旧代码在这一步 ClassCastException，整封正文丢掉。
                //   两层修：① props() 里打开 JavaMail 的 boundary 宽容开关（根治）；
                //          ② 这里做兜底 —— 真拿不到 Multipart 就把流当纯文本读出来，
                //             宁可格式糙一点，也不让用户看到"正文空白"。
                if (c instanceof javax.mail.Multipart) {
                    javax.mail.Multipart mp = (javax.mail.Multipart) c;
                    String htmlFallback = null;
                    for (int i = 0; i < mp.getCount(); i++) {
                        javax.mail.BodyPart bp = mp.getBodyPart(i);
                        // 附件跳过（否则会把附件二进制当正文）
                        if (javax.mail.Part.ATTACHMENT.equalsIgnoreCase(bp.getDisposition())) continue;
                        String sub = extractBody(bp);
                        if (sub == null || sub.trim().length() == 0) continue;
                        if (bp.isMimeType("text/plain")) return sub;
                        if (htmlFallback == null) htmlFallback = sub;
                    }
                    return htmlFallback;
                }
                if (c instanceof java.io.InputStream) {
                    return firstTextFromRawMime(streamToText((java.io.InputStream) c));
                }
                return "";
            }
            if (p.isMimeType("message/rfc822")) {
                Object c = p.getContent();
                return (c instanceof javax.mail.Part) ? extractBody((javax.mail.Part) c) : "";
            }
        } catch (Throwable t) {
            Log.w(TAG, "正文抽取失败: " + t.getMessage());
        }
        return "";
    }

    /**
     * ★ R14：文本段 → String。getContent() 正常时就是 String；没有 mailcap 时是<b>已做传输解码</b>的流
     * （BASE64DecoderStream / QPDecoderStream），按 Content-Type 的 charset 解码（缺省 UTF-8，gb2312/gbk → GB18030）。
     * 绝不再 {@code toString()} 一个流对象。
     */
    static String contentText(javax.mail.Part p) throws Exception {
        Object c;
        try {
            c = p.getContent();
        } catch (Throwable t) {
            c = null;   // handler 坏了（VerifyError / ClassCast…）：直接读传输解码后的流
        }
        if (c instanceof String) return (String) c;
        if (c instanceof java.io.InputStream) {
            try { ((java.io.InputStream) c).close(); } catch (Throwable ignored) { }
        }
        java.io.InputStream in = p.getInputStream();   // getInputStream() = 已做 base64 / QP 传输解码的字节
        return decodeText(readCapped(in), charsetOf(p.getContentType()));
    }

    /** Content-Type 里的 charset（没有 = UTF-8）。纯函数。 */
    static String charsetOf(String contentType) {
        if (contentType == null) return "UTF-8";
        try {
            String cs = new javax.mail.internet.ContentType(contentType).getParameter("charset");
            if (cs == null || cs.trim().length() == 0) return "UTF-8";
            return javax.mail.internet.MimeUtility.javaCharset(cs.trim());
        } catch (Throwable t) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)charset\\s*=\\s*\"?([A-Za-z0-9._-]+)").matcher(contentType);
            return m.find() ? m.group(1) : "UTF-8";
        }
    }

    /** 字节 → 文本：gb2312 / gbk 一律按超集 GB18030 解；不认识的字符集退回 UTF-8。纯函数。 */
    static String decodeText(byte[] b, String charset) {
        if (b == null) return "";
        String cs = charset == null ? "UTF-8" : charset;
        String low = cs.toLowerCase(java.util.Locale.ROOT);
        if (low.equals("gb2312") || low.equals("gbk") || low.equals("x-gbk") || low.equals("euc-cn")) cs = "GB18030";
        try {
            return new String(b, cs);
        } catch (Throwable t) {
            try {
                return new String(b, "UTF-8");
            } catch (Throwable t2) {
                return "";
            }
        }
    }

    private static byte[] readCapped(java.io.InputStream in) {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        if (in == null) return new byte[0];
        final int cap = 256 * 1024;
        try {
            byte[] buf = new byte[4096];
            int n;
            while (bos.size() < cap && (n = in.read(buf)) > 0) bos.write(buf, 0, Math.min(n, cap - bos.size()));
        } catch (Throwable t) {
            Log.w(TAG, "正文流读取中断（已读 " + bos.size() + " 字节）: " + t.getMessage());
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
        return bos.toByteArray();
    }

    /** 兜底：把"本该是 Multipart 却给回流"的正文当纯文本读出来（尽力而为，不抛）。 */
    private static String streamToText(java.io.InputStream in) {
        if (in == null) return "";
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        final int cap = 256 * 1024;          // 上限 256KB，挡住异常大的邮件
        try {
            byte[] buf = new byte[4096];
            int n;
            while (bos.size() < cap && (n = in.read(buf)) > 0) {
                bos.write(buf, 0, Math.min(n, cap - bos.size()));
            }
        } catch (Throwable t) {
            Log.w(TAG, "正文流读取中断（已读 " + bos.size() + " 字节）: " + t.getMessage());
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
        try {
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 从"原始 MIME 文本"里摘出可读正文（兜底路径的正式实现）。
     *
     * <p>走到这里说明 JavaMail 没能把多部分正文结构化（实测 QQ 邮箱：头写 multipart、
     * getContent() 却只给回流）。此时拿到的是**整段原始 MIME**：边界行 + 每段的
     * Content-Type / Content-Transfer-Encoding 小头 + 段正文。
     *
     * <p>★ 只"剥标签"是不够的 —— 第一版就是这么写的，结果正文变成
     * {@code =E5=B7=B2=E5=9C=A8=E2=80=9CiPhone=E2=80=9D...} 这种 quoted-printable 原文，
     * 等于把乱码端给了用户和模型。所以这里必须**按段解析 + 按段的编码真解码**：
     * <ol>
     *   <li>按边界行切段；</li>
     *   <li>每段读小头里的 Content-Type / Content-Transfer-Encoding；</li>
     *   <li>按 quoted-printable / base64 解码；</li>
     *   <li>优先取 text/plain，没有则取 text/html 并去标签。</li>
     * </ol>
     */
    private static String firstTextFromRawMime(String raw) {
        if (raw == null || raw.length() == 0) return "";
        String plain = null;
        String html = null;

        StringBuilder segHead = new StringBuilder();
        StringBuilder segBody = new StringBuilder();
        boolean inHeaders = true;
        boolean seenBoundary = false;

        String[] lines = raw.split("\n", -1);
        for (int i = 0; i <= lines.length; i++) {
            // 末尾补一个假边界，保证最后一段也会被 flush
            boolean last = (i == lines.length);
            String ln = last ? "--end" : lines[i];
            boolean isBoundary = ln.startsWith("--");

            if (isBoundary) {
                if (seenBoundary || segHead.length() > 0 || segBody.length() > 0) {
                    TextPart tp = decodeSegment(segHead.toString(), segBody.toString());
                    if (tp != null) {
                        if (tp.isPlain && (plain == null || plain.trim().length() == 0)) plain = tp.text;
                        else if (!tp.isPlain && (html == null || html.trim().length() == 0)) html = tp.text;
                    }
                }
                segHead.setLength(0);
                segBody.setLength(0);
                inHeaders = true;
                seenBoundary = true;
                if (last) break;
                continue;
            }

            if (inHeaders) {
                if (ln.trim().length() == 0) inHeaders = false;     // 空行 = 小头结束
                else segHead.append(ln).append('\n');
            } else {
                segBody.append(ln).append('\n');
            }
        }

        if (plain != null && plain.trim().length() > 0) return plain.trim();
        if (html != null && html.trim().length() > 0) return stripHtml(html);
        return "";
    }

    /** 一段正文 + 它是纯文本还是 HTML。 */
    private static final class TextPart {
        final String text;
        final boolean isPlain;
        TextPart(String t, boolean plain) { text = t; isPlain = plain; }
    }

    /** 解析一个 MIME 段：看小头决定类型与编码，再真解码。 */
    private static TextPart decodeSegment(String head, String body) {
        if (body == null || body.trim().length() == 0) return null;
        String ctype = headerValue(head, "content-type");
        String cte = headerValue(head, "content-transfer-encoding");
        String ct = ctype == null ? "" : ctype.toLowerCase();
        String enc = cte == null ? "" : cte.trim().toLowerCase();

        boolean isText = ct.startsWith("text/") || ct.length() == 0;   // 小头缺失时按文本试
        if (!isText) return null;                                      // 图片/附件段直接丢
        boolean isPlain = ct.indexOf("text/html") < 0;                 // 默认当纯文本

        String text;
        if (enc.indexOf("base64") >= 0) {
            text = decodeBase64(body);
            if (text == null) return null;
        } else if (enc.indexOf("quoted-printable") >= 0) {
            text = decodeQuotedPrintable(body);
        } else {
            text = body;
        }
        return new TextPart(text, isPlain);
    }

    /** 取小头里的某个头值（大小写不敏感，取第一个匹配行）。 */
    private static String headerValue(String head, String name) {
        if (head == null) return null;
        String[] ls = head.split("\n");
        for (String l : ls) {
            int c = l.indexOf(':');
            if (c <= 0) continue;
            if (l.substring(0, c).trim().equalsIgnoreCase(name)) {
                return l.substring(c + 1).trim();
            }
        }
        return null;
    }

    /**
     * quoted-printable 解码。
     *
     * <p>没有用 {@code MimeUtility.decode}：我们要解的是"整段原始文本"（含边界残留），
     * 而 QP 的规则本身很短（软换行 + =XX），自己解更可控，也不必再绕一层流。
     */
    private static String decodeQuotedPrintable(String s) {
        if (s == null) return "";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '=') {
                // 软换行："=\n" 或 "=\r\n" → 什么都不输出
                if (i + 1 < s.length() && s.charAt(i + 1) == '\n') { i++; continue; }
                if (i + 2 < s.length() && s.charAt(i + 1) == '\r' && s.charAt(i + 2) == '\n') { i += 2; continue; }
                if (i + 2 < s.length()) {
                    int h1 = Character.digit(s.charAt(i + 1), 16);
                    int h2 = Character.digit(s.charAt(i + 2), 16);
                    if (h1 >= 0 && h2 >= 0) {
                        out.write((h1 << 4) | h2);
                        i += 2;
                        continue;
                    }
                }
                out.write('=');
                continue;
            }
            if (ch == '\r') continue;                     // 统一换行，避免 \r\n 混进正文
            try {
                byte[] bs = String.valueOf(ch).getBytes("UTF-8");
                out.write(bs, 0, bs.length);
            } catch (Throwable ignored) {
            }
        }
        try {
            return new String(out.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }

    /** base64 解码（失败返回 null，让调用方跳过这一段）。 */
    private static String decodeBase64(String s) {
        try {
            StringBuilder clean = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                        || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=') {
                    clean.append(c);
                }
            }
            byte[] raw = android.util.Base64.decode(clean.toString(), android.util.Base64.DEFAULT);
            return new String(raw, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 仅用于自测：直接把"原始 MIME 文本"喂进分段解码器，验证摘正文的正确性（离线纯函数）。 */
    public static String debugExtractRawMime(String raw) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return "（仅 debug 包可用）";   // r2/fixes 9：debug 驱动专用，release 里是空操作
        return firstTextFromRawMime(raw);
    }

    /** 仅用于自测：单个 MIME 段的解码（含 QP / base64）。 */
    public static String debugDecodeSegment(String head, String body) {
        TextPart tp = decodeSegment(head, body);
        return tp == null ? null : tp.text;
    }

    /** 仅用于自测：QP 软换行处理（`=\r\n` 必须被吃掉，不能留个等号）。 */
    public static String debugDecodeQuotedPrintable(String s) {
        return decodeQuotedPrintable(s);
    }

    /** 仅用于自测：重试判据（见 --ez mailretryinfo 的同名用途）。 */
    public static boolean debugIsTransient(Throwable t) { return isTransient(t); }

    /** 仅用于自测。 */
    public static boolean debugHasAuthFailure(Throwable t) { return hasAuthFailure(t); }

    /** 仅用于自测：排版时间（最近候选窗口天数）。 */
    public static int debugRecentDays() { return RECENT_DAYS; }

    /** 极简 HTML → 文本（保留可读性即可，不做完整解析）。public 供自测断言。 */
    public static String stripHtml(String html) {
        if (html == null) return "";
        String s = html;
        s = s.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</(p|div|tr|li|h[1-6])>", "\n");
        s = s.replaceAll("(?s)<[^>]+>", " ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&")
             .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }
}
