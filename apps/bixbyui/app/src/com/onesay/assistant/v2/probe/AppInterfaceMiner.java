package com.onesay.assistant.v2.probe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 应用接口深挖（P1-14 / 用户投诉 #1「QQ 邮箱挖不到接口」）。纯 Java，可 JVM 单测。
 *
 * <h3>旧探针为什么挖不到 QQ 邮箱</h3>
 * <p>{@link ProbeEngine#parseDump} 只认 {@code android.intent.action.*} 标准动作与 LAUNCHER/VOICE，
 * 于是 QQ 邮箱这类应用真正可用的入口全被丢掉：
 * <ul>
 *   <li><b>深链</b>：intent-filter 的 {@code Scheme/Authority/Path}（如真机 QQ 邮箱的
 *       {@code qqmail_3dtouch://com.tencent.androidqqmail.touch/open_inbox}）；</li>
 *   <li><b>私有 action</b>：{@code com.tencent.qqmail.action.*} 这类应用自定义动作；</li>
 *   <li><b>导出 Activity 的中文标签</b>（"写邮件""收件箱"）——dump 里没有，由宿主从 PackageManager 补；</li>
 *   <li><b>ContentProvider authority</b>。</li>
 * </ul>
 *
 * <h3>流程</h3>
 * {@link #mine} 解析 {@code pm dump <pkg>} / {@code dumpsys package <pkg>} 文本 → {@link #rank} 按用户目标打分 →
 * {@link Iface#toIntentJson} 产出与 {@code mined_runtime.json} 同构的条目，交
 * {@code MinedCapabilities.buildFromIntent} 注册为新工具（AgentLoop 下一轮即可调用）。
 *
 * <h3>只声明 scheme 的 filter（真机报告 M-1）</h3>
 * <p>真机 dump 证实 QQ 邮箱的 {@code qqmail} scheme 只由 {@code LaunchWebPush} 声明、<b>没有 Host/Path</b>：
 * {@code qqmail://inbox} 与 {@code qqmail://compose} 的 host 被完全忽略，落到同一个主界面。规则：
 * <ul>
 *   <li>filter 没有 Host 时<b>不编造 host 语义</b>，也不生成 scheme×host 组合；只产出一条
 *       {@link Iface#launcherOnly}「拉起 App 主界面」接口（按组件区分，命令用 {@code -n} 显式组件）；</li>
 *   <li>例外是语义由标准定义的不透明 scheme（{@code mailto:}/{@code tel:}/{@code geo:} 等），记为
 *       {@link Iface#standardScheme}，语义来自标准而不是猜测（mailto → 写邮件）；同一 scheme 被多个组件声明时
 *       （真机 mailto 同时有 LaunchComposeMail 与 LaunchComposeNote）各出一条，组件名与标准语义相符者加分；</li>
 *   <li>有 Host 的 filter 才按 Android 的匹配语义做 scheme×host×path 组合。</li>
 * </ul>
 * <p>另外排除两类"有 filter 但不是给人用的入口"：第三方登录/授权回调组件（OAuth RedirectUri、
 * tauth.AuthActivity、wxapi 等），以及声明了 MIME 类型的私有 action（必须附带数据，单独拉起没有意义）。
 *
 * <h3>安全</h3>
 * <p>dump 内容来自第三方应用的 Manifest，<b>不可信</b>：scheme/host/path/action/组件名都按严格白名单字符校验，
 * 不合规的条目直接丢弃（绝不拼进 shell 模板）；模板里的字面量再用单引号包裹。
 */
public final class AppInterfaceMiner {

    private AppInterfaceMiner() {}

    /** 接口类型。 */
    public enum Kind {
        /** 深链：am start -a VIEW -d uri -p pkg */
        DEEPLINK,
        /** 私有 action：am start -a action -n comp */
        ACTION,
        /** 带中文标签的导出 Activity：am start -n comp */
        ACTIVITY,
        /** ContentProvider：content query（读隐私数据，需确认） */
        PROVIDER
    }

    /** 一个挖到的接口。 */
    public static final class Iface {
        public final Kind kind;
        public final String pkg;
        /** am start 可用的完整组件名（PROVIDER 为 provider 类组件），可能为 null（深链无组件时）。 */
        public final String component;
        public final String action;
        public final String uri;
        public final String authority;
        /** 中文标签（来自 PackageManager），可能为 null。 */
        public String label;
        public boolean exported;
        public int score;
        /** ★ M-1：filter 只声明了 scheme、没有 host/path（深链语义只到"拉起这个组件"为止）。 */
        public boolean hostless;
        /** ★ M-1：hostless 且不是标准 scheme → 只代表「拉起 App 主界面」，不能当作收件箱/写信等具体页面。 */
        public boolean launcherOnly;
        /** ★ M-1：hostless 的标准不透明 scheme（mailto/tel/sms/geo…），语义来自标准。 */
        public String standardScheme;

        Iface(Kind kind, String pkg, String component, String action, String uri, String authority) {
            this.kind = kind;
            this.pkg = pkg;
            this.component = component;
            this.action = action;
            this.uri = uri;
            this.authority = authority;
        }

        /** 唯一键（去重用）。 */
        public String key() {
            switch (kind) {
                case DEEPLINK: return "L|" + uri + (hostless && component != null ? "|" + component : "");
                case ACTION: return "A|" + action + "|" + component;
                case ACTIVITY: return "C|" + component;
                default: return "P|" + authority;
            }
        }

        /** 工具名中的动作段（不含软件段），例如 link_inbox / act_compose_mail / do_compose / provider_mail。 */
        public String actionKey() {
            switch (kind) {
                case DEEPLINK: {
                    String scheme = uri.substring(0, uri.indexOf(':'));
                    if (standardScheme != null) {
                        // mailto_compose_mail / mailto_compose_note：同一标准 scheme 按组件区分
                        String c = stripActivity(lastSeg(component, '.'));
                        if (c.startsWith("launch_")) c = c.substring(7);
                        return sanitize(scheme + "_" + c);
                    }
                    if (launcherOnly) return "link_" + sanitize(scheme) + "_main";
                    String rest = uri.substring(uri.indexOf("://") + 3);
                    int slash = rest.indexOf('/');
                    // 域名式 host（含 '.'）+ path：工具名只取 path，否则 4 个同 host 的入口会被截成同名
                    if (slash > 0 && rest.substring(0, slash).indexOf('.') > 0 && rest.length() > slash + 1) {
                        rest = rest.substring(slash + 1);
                    }
                    String k = sanitize(rest.replace('/', '_'));
                    return "link_" + (k.length() == 0 ? sanitize(scheme) : k);
                }
                case ACTION: return "do_" + sanitize(lastSeg(action, '.'));
                case ACTIVITY: return "act_" + sanitize(stripActivity(lastSeg(component, '.')));
                default: {
                    String a = authority;
                    if (pkg != null && a.startsWith(pkg + ".")) a = a.substring(pkg.length() + 1);
                    return "provider_" + sanitize(a);
                }
            }
        }

        /** 给模型看的一行描述。 */
        public String describe() {
            String l = label != null && label.length() > 0 ? label : null;
            switch (kind) {
                case DEEPLINK:
                    if (launcherOnly) {
                        return "拉起「" + (l != null ? l : "应用") + "」主界面（" + uri
                                + " 只声明了 scheme、没有 host/path：任何 host 都落到同一入口，不能直达收件箱/写信等具体页面）";
                    }
                    if (standardScheme != null) {
                        String sem = STANDARD_SCHEMES.get(standardScheme);
                        return "用 " + uri + "（标准协议：" + sem + "）打开「"
                                + (l != null ? l : lastSeg(component, '.')) + "」";
                    }
                    return (l != null ? "打开「" + l + "」" : "打开深链") + " " + uri;
                case ACTION: return (l != null ? "打开「" + l + "」" : "调用私有动作") + " " + action;
                case ACTIVITY: return "打开「" + (l != null ? l : lastSeg(component, '.')) + "」界面";
                default: return "查询内容提供者 content://" + authority + "（读取应用数据，需确认）";
            }
        }

        /**
         * 与 mined_runtime.json 同构的条目（新增字段 kind/uri/intentAction/authority/label/pkg，
         * 旧版只读 sign/component/software/summary/flags）。
         */
        public JSONObject toIntentJson(String sign, String software) {
            try {
                JSONObject it = new JSONObject();
                it.put("sign", sign);
                it.put("software", software);
                it.put("kind", kind.name());
                it.put("pkg", pkg);
                it.put("summary", describe());
                if (component != null) it.put("component", component);
                if (hostless) it.put("hostless", true);
                if (launcherOnly) it.put("launcherOnly", true);
                if (standardScheme != null) it.put("standardScheme", standardScheme);
                if (action != null) it.put("intentAction", action);
                if (uri != null) it.put("uri", uri);
                if (authority != null) it.put("authority", authority);
                if (label != null) it.put("label", label);
                it.put("keywords", new JSONArray(keywords()));
                return it;
            } catch (Exception e) {
                return null;
            }
        }

        /** 检索关键词：标签 + 英文词元反查出的中文提示词。 */
        public List<String> keywords() {
            List<String> out = new ArrayList<String>();
            if (label != null && label.length() > 0) out.add(label);
            if (launcherOnly) { out.add("主界面"); return out; }   // 不从组件名反推页面语义
            Set<String> toks = tokens();
            for (Map.Entry<String, String[]> e : CUES.entrySet()) {
                for (String en : e.getValue()) {
                    if (toks.contains(en)) {
                        String cn = e.getKey().split("\\|")[0];
                        if (!out.contains(cn)) out.add(cn);
                        break;
                    }
                }
            }
            return out;
        }

        /** 英文词元（小写）：组件类名驼峰拆分、host/path 段、action 段。 */
        public Set<String> tokens() {
            Set<String> t = new HashSet<String>();
            if (component != null) addTokens(t, component.substring(component.indexOf('/') + 1));
            if (action != null) addTokens(t, action);
            if (uri != null) {
                int p = uri.indexOf("://");
                if (p >= 0) addTokens(t, uri.substring(p + 3));
                if (standardScheme != null) t.add(standardScheme);
            }
            if (authority != null) addTokens(t, authority);
            return t;
        }
    }

    // ------------------------------------------------------------------ 解析

    private static final Pattern P_COMP = Pattern.compile("^\\s+[0-9a-f]+\\s+(\\S+)\\s+filter\\s+([0-9a-f]+)");
    private static final Pattern P_ACTION = Pattern.compile("^\\s+Action:\\s+\"([^\"]+)\"");
    private static final Pattern P_CAT = Pattern.compile("^\\s+Category:\\s+\"([^\"]+)\"");
    private static final Pattern P_TYPE = Pattern.compile("^\\s+Type:\\s+\"([^\"]+)\"");
    private static final Pattern P_SCHEME = Pattern.compile("^\\s+Scheme:\\s+\"([^\"]*)\"");
    private static final Pattern P_AUTH = Pattern.compile("^\\s+Authority:\\s+\"([^\"]*)\":\\s*(-?\\d+)(\\s+WILD)?");
    private static final Pattern P_PATH = Pattern.compile(
            "^\\s+Path:\\s+\"PatternMatcher\\{(LITERAL|PREFIX|GLOB|ADVANCED_GLOB):\\s*([^}]*)\\}\"");
    private static final Pattern P_TABLE_END = Pattern.compile("^\\s*(Receiver|Service|Provider) Resolver Table");
    private static final Pattern P_PROVIDER_AUTH = Pattern.compile("^\\s+\\[([^\\]]+)\\]:\\s*$");
    private static final Pattern P_PROVIDER_REC = Pattern.compile("^\\s+Provider\\{[0-9a-f]+\\s+(\\S+)\\}");

    /** 白名单字符（dump 来自第三方 Manifest，不可信）。 */
    // ★ M-1：允许 '_'（真机 QQ 邮箱的 qqmail_3dtouch；Android 不校验 RFC 3986，下划线在单引号里也无害）
    private static final Pattern SAFE_SCHEME = Pattern.compile("^[a-z][a-z0-9+._\\-]{0,30}$");
    private static final Pattern SAFE_HOST = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._\\-]{0,62}$");
    private static final Pattern SAFE_PATH = Pattern.compile("^/[A-Za-z0-9/._~%\\-]{0,80}$");
    private static final Pattern SAFE_ACTION = Pattern.compile("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+){1,15}$");
    private static final Pattern SAFE_COMP = Pattern.compile("^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$");
    private static final Pattern SAFE_AUTH = Pattern.compile("^[A-Za-z0-9_.\\-]{1,100}$");

    /** 私有 action 的噪音（推送/统计/小部件/系统广播类）。 */
    private static final Pattern NOISE_PRIVATE = Pattern.compile(
            "(?i)(push|widget|notif|analytics|report|stat|sync|alarm_receiver|boot|package_|"
                    + "keepalive|daemon|wakeup|heartbeat|xg\\.|mipush|hwpush|vivo|oppo|meizu|jpush|getui|"
                    + "\\.service|_service|crash|upgrade|update)");

    /**
     * ★ M-1：第三方登录/授权/分享 SDK 的回调组件 —— 有 VIEW filter，但不是给人用的入口
     * （真机：net.openid.appauth.RedirectUriReceiverActivity、com.tencent.tauth.AuthActivity、LaunchWeWorkAuthActivity）。
     */
    private static final Pattern NOISE_COMP = Pattern.compile(
            "(?i)(appauth|RedirectUri|AuthActivity|\\.wxapi\\.|WXEntry|WXPayEntry|alipay|AssistActivity)");

    /** ★ M-1：语义由标准定义的不透明 scheme（无 host 也有确定语义）→ 中文语义。 */
    static final Map<String, String> STANDARD_SCHEMES = new LinkedHashMap<String, String>();
    /** 标准 scheme → 组件名里与该语义相符的英文词（同一 scheme 多组件时给相符者加分）。 */
    static final Map<String, String[]> STANDARD_HINTS = new LinkedHashMap<String, String[]>();
    static {
        STANDARD_SCHEMES.put("mailto", "写邮件");
        STANDARD_HINTS.put("mailto", new String[]{"mail"});
        STANDARD_SCHEMES.put("tel", "拨号");
        STANDARD_HINTS.put("tel", new String[]{"dial", "call", "phone"});
        STANDARD_SCHEMES.put("sms", "发短信");
        STANDARD_HINTS.put("sms", new String[]{"sms", "message", "compose"});
        STANDARD_SCHEMES.put("smsto", "发短信");
        STANDARD_HINTS.put("smsto", new String[]{"sms", "message", "compose"});
        STANDARD_SCHEMES.put("geo", "地图定位");
        STANDARD_HINTS.put("geo", new String[]{"map", "location", "poi"});
    }

    private static final Set<String> SKIP_SCHEMES = new HashSet<String>();
    static {
        SKIP_SCHEMES.add("file");
        SKIP_SCHEMES.add("content");
        SKIP_SCHEMES.add("data");
        SKIP_SCHEMES.add("javascript");
    }

    /** 一个 intent-filter 块。 */
    private static final class Filter {
        String comp;
        final List<String> actions = new ArrayList<String>();
        final List<String> cats = new ArrayList<String>();
        final List<String> schemes = new ArrayList<String>();
        final List<String> hosts = new ArrayList<String>();
        final List<String> paths = new ArrayList<String>();
        final List<String> types = new ArrayList<String>();
    }

    /**
     * 解析 dump 文本，挖出深链 / 私有 action / 导出 Activity / ContentProvider。
     *
     * @param dump               {@code pm dump <pkg>} 或 {@code dumpsys package <pkg>} 输出
     * @param pkg                包名
     * @param labels             组件（pkg/完整类名）→ 中文标签（来自 PackageManager，可为 null）
     * @param exportedActivities 导出且无需权限的 Activity 组件集合（PackageManager 给出，可为 null）
     * @param exportedProviders  导出的 provider authority 集合（可为 null；不在其中的 provider 只列出、不注册工具）
     */
    public static List<Iface> mine(String dump, String pkg, Map<String, String> labels,
                                   Set<String> exportedActivities, Set<String> exportedProviders) {
        Map<String, Iface> out = new LinkedHashMap<String, Iface>();
        if (dump == null || pkg == null) return new ArrayList<Iface>();
        String[] lines = dump.split("\r?\n");

        // ---- 1) Activity Resolver Table：按 filter id 去重（同一 filter 会在多个分节重复出现）----
        Map<String, Filter> filters = new LinkedHashMap<String, Filter>();
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("Activity Resolver Table")) { start = i; break; }
        }
        if (start >= 0) {
            Filter cur = null;
            for (int i = start + 1; i < lines.length; i++) {
                String ln = lines[i];
                if (P_TABLE_END.matcher(ln).find()) break;
                Matcher mc = P_COMP.matcher(ln);
                if (mc.find()) {
                    String id = mc.group(1) + "#" + mc.group(2);
                    Filter f = filters.get(id);
                    if (f == null) {
                        f = new Filter();
                        f.comp = mc.group(1);
                        filters.put(id, f);
                        cur = f;
                    } else {
                        cur = null;   // 重复出现的 filter：内容相同，跳过
                    }
                    continue;
                }
                if (cur == null) continue;
                Matcher m;
                if ((m = P_ACTION.matcher(ln)).find()) { addUnique(cur.actions, m.group(1)); continue; }
                if ((m = P_CAT.matcher(ln)).find()) { addUnique(cur.cats, m.group(1)); continue; }
                if ((m = P_SCHEME.matcher(ln)).find()) { addUnique(cur.schemes, m.group(1)); continue; }
                if ((m = P_TYPE.matcher(ln)).find()) { addUnique(cur.types, m.group(1)); continue; }
                if ((m = P_AUTH.matcher(ln)).find()) {
                    String host = m.group(1);
                    int port = parseInt(m.group(2));
                    if (port > 0) host = host + ":" + port;
                    addUnique(cur.hosts, host);
                    continue;
                }
                if ((m = P_PATH.matcher(ln)).find()) {
                    String type = m.group(1);
                    String p = m.group(2).trim();
                    if ("LITERAL".equals(type) || "PREFIX".equals(type)) addUnique(cur.paths, p);
                    else {
                        // GLOB：取通配符前的字面前缀
                        int cut = indexOfAny(p, "*.[");
                        String lit = cut >= 0 ? p.substring(0, cut) : p;
                        if (lit.length() > 1) addUnique(cur.paths, lit);
                    }
                }
            }
        }

        for (Filter f : filters.values()) {
            String comp = ProbeEngine.compToAm(f.comp);
            if (comp == null || !SAFE_COMP.matcher(comp).matches()) continue;
            if (!comp.startsWith(pkg + "/")) continue;
            if (NOISE_COMP.matcher(comp).find()) continue;   // 登录/授权回调，不是入口

            // 深链：VIEW + BROWSABLE/DEFAULT + scheme
            boolean view = f.actions.contains("android.intent.action.VIEW");
            if (view && !f.schemes.isEmpty()) {
                for (String sc : f.schemes) {
                    String scheme = sc.toLowerCase(Locale.ROOT);
                    if (!SAFE_SCHEME.matcher(scheme).matches() || SKIP_SCHEMES.contains(scheme)) continue;
                    boolean web = "http".equals(scheme) || "https".equals(scheme);
                    if (f.hosts.isEmpty()) {
                        if (web) continue;   // 没有 host 的 http(s) filter = 通用浏览器入口，不是本应用接口
                        // ★ M-1：只有 scheme 的 filter 不编造 host 语义、不生成 scheme×host 组合
                        Iface d;
                        if (STANDARD_SCHEMES.containsKey(scheme)) {
                            d = new Iface(Kind.DEEPLINK, pkg, comp, null, scheme + ":", null);
                            d.standardScheme = scheme;
                        } else {
                            d = new Iface(Kind.DEEPLINK, pkg, comp, null, scheme + "://", null);
                            d.launcherOnly = true;
                        }
                        d.hostless = true;
                        put(out, d);
                        continue;
                    }
                    for (String host : f.hosts) {
                        String h = host;
                        int colon = h.indexOf(':');
                        String bare = colon > 0 ? h.substring(0, colon) : h;
                        if (bare.startsWith("*.")) continue;
                        if (!SAFE_HOST.matcher(bare).matches()) continue;
                        if (colon > 0 && !h.substring(colon + 1).matches("\\d{1,5}")) continue;
                        if (f.paths.isEmpty()) {
                            put(out, new Iface(Kind.DEEPLINK, pkg, comp, null, scheme + "://" + h, null));
                        } else {
                            for (String p : f.paths) {
                                if (!SAFE_PATH.matcher(p).matches()) continue;
                                put(out, new Iface(Kind.DEEPLINK, pkg, comp, null, scheme + "://" + h + p, null));
                            }
                        }
                    }
                }
            }

            // 私有 action：非 android.* 标准动作、非噪音、无 data 约束（有 scheme 的已当深链处理；
            // ★ M-1：声明了 MIME 类型的也跳过 —— 真机 qqmail.intent.action.SEND_DATA 要附带文件数据，单独拉起没意义）
            if (f.schemes.isEmpty() && f.types.isEmpty()) {
                for (String a : f.actions) {
                    if (a.startsWith("android.") || a.startsWith("com.android.")) continue;
                    if (!SAFE_ACTION.matcher(a).matches()) continue;
                    if (NOISE_PRIVATE.matcher(a).find()) continue;
                    put(out, new Iface(Kind.ACTION, pkg, comp, a, null, null));
                }
            }
        }

        // ---- 2) 导出 Activity（来自 PackageManager）：只收带中文标签的，标准入口不重复 ----
        if (exportedActivities != null) {
            List<String> sorted = new ArrayList<String>(exportedActivities);
            Collections.sort(sorted);
            for (String c : sorted) {
                String comp = ProbeEngine.compToAm(c);
                if (comp == null || !SAFE_COMP.matcher(comp).matches() || !comp.startsWith(pkg + "/")) continue;
                String label = labels != null ? labels.get(comp) : null;
                if (label == null || label.trim().length() == 0 || !hasCjk(label)) continue;
                put(out, new Iface(Kind.ACTIVITY, pkg, comp, null, null, null));
            }
        }

        // ---- 3) ContentProvider authorities ----
        boolean inAuth = false;
        String pendingAuth = null;
        for (String ln : lines) {
            if (ln.trim().startsWith("ContentProvider Authorities:")) { inAuth = true; continue; }
            if (!inAuth) continue;
            if (ln.trim().length() == 0) { inAuth = false; continue; }
            if (!ln.startsWith(" ")) { inAuth = false; continue; }
            Matcher ma = P_PROVIDER_AUTH.matcher(ln);
            if (ma.find()) { pendingAuth = ma.group(1); continue; }
            Matcher mr = P_PROVIDER_REC.matcher(ln);
            if (mr.find() && pendingAuth != null) {
                String comp = ProbeEngine.compToAm(mr.group(1));
                for (String a : pendingAuth.split(";")) {
                    a = a.trim();
                    if (!SAFE_AUTH.matcher(a).matches()) continue;
                    if (comp == null || !comp.startsWith(pkg + "/")) continue;
                    Iface p = new Iface(Kind.PROVIDER, pkg, comp, null, null, a);
                    p.exported = exportedProviders != null && exportedProviders.contains(a);
                    put(out, p);
                }
                pendingAuth = null;
            }
        }

        // ---- 4) 标签回填 + 导出标记 ----
        for (Iface i : out.values()) {
            if (i.label == null && labels != null && i.component != null) {
                String l = cleanLabel(labels.get(i.component));
                if (l != null) i.label = l;
            }
            if (i.kind == Kind.ACTIVITY) i.exported = true;
            if (i.kind == Kind.DEEPLINK || i.kind == Kind.ACTION) i.exported = true;   // 带 filter 的 Activity 默认导出
        }
        return new ArrayList<Iface>(out.values());
    }

    // ------------------------------------------------------------------ 排序

    /** 中文目标提示词（"|" 分隔的同义说法）→ 接口英文词元。 */
    static final Map<String, String[]> CUES = new LinkedHashMap<String, String[]>();
    static {
        CUES.put("收件箱|收信|读邮件|看邮件|最新邮件|新邮件|读信|邮件列表|未读",
                new String[]{"inbox", "mailbox", "maillist", "list", "readmail", "read", "folder", "unread", "home", "main"});
        CUES.put("写邮件|写信|发邮件|写一封|新建|撰写|发送|回复",
                new String[]{"compose", "write", "send", "sendto", "new", "edit", "reply", "mailto"});
        CUES.put("搜索|查找|搜一下|找一下",
                new String[]{"search", "find", "query"});
        CUES.put("扫一扫|扫码|二维码",
                new String[]{"scan", "qrcode", "qr", "scanner"});
        CUES.put("设置|选项",
                new String[]{"setting", "settings", "config", "preference", "pref"});
        CUES.put("草稿",
                new String[]{"draft", "drafts"});
        CUES.put("附件|文件|中转站",
                new String[]{"attach", "attachment", "file", "ftn"});
        CUES.put("日历|日程",
                new String[]{"calendar", "schedule", "event"});
        CUES.put("记事|笔记|备忘",
                new String[]{"note", "notes", "memo"});
        CUES.put("联系人|通讯录",
                new String[]{"contact", "contacts", "addr", "address"});
        CUES.put("支付|付款|钱包",
                new String[]{"pay", "wallet", "payment"});
        CUES.put("聊天|消息|会话",
                new String[]{"chat", "message", "conversation", "msg"});
        CUES.put("分享",
                new String[]{"share"});
        CUES.put("播放|音乐|歌",
                new String[]{"play", "music", "player", "song"});
        CUES.put("导航|地图|路线",
                new String[]{"navi", "navigation", "map", "route"});
        CUES.put("读|查看|看看|打开",
                new String[]{"view", "read", "detail", "show", "open"});
    }

    /**
     * 按用户目标给接口打分并降序排列（稳定排序）。规则：
     * <ul>
     *   <li>基础分：深链 3 / 带标签导出界面 2 / 私有 action 1 / provider 0；有中文标签再 +1；</li>
     *   <li>标签与目标的中文二元组重合：每个 +3（封顶 12）；标签整体出现在目标里 +6；</li>
     *   <li>目标命中某组中文提示词、且接口词元命中该组英文词：每组 +5；</li>
     *   <li>未导出的 provider −5（只列出、不注册）。</li>
     * </ul>
     */
    public static List<Iface> rank(List<Iface> list, String goal) {
        String g = goal == null ? "" : goal.toLowerCase(Locale.ROOT);
        for (Iface i : list) i.score = score(i, g);
        List<Iface> sorted = new ArrayList<Iface>(list);
        Collections.sort(sorted, new Comparator<Iface>() {
            @Override public int compare(Iface a, Iface b) { return b.score - a.score; }
        });
        return sorted;
    }

    static int score(Iface i, String goal) {
        int s;
        switch (i.kind) {
            case DEEPLINK: s = 3; break;
            case ACTIVITY: s = 2; break;
            case ACTION: s = 1; break;
            default: s = 0;
        }
        if (i.label != null && i.label.length() > 0) s += 1;
        if (i.kind == Kind.PROVIDER && !i.exported) s -= 5;
        // ★ M-1：只能拉起主界面的链接排在具体入口之后（与「带标签界面」同级）
        if (i.launcherOnly) s -= 1;
        // ★ M-1：标准 scheme 被多个组件声明时，组件名与标准语义相符者优先（mailto → LaunchComposeMail 而非 ComposeNote）
        if (i.standardScheme != null) {
            String[] hints = STANDARD_HINTS.get(i.standardScheme);
            Set<String> ct = new HashSet<String>();
            addTokens(ct, i.component.substring(i.component.indexOf('/') + 1));
            if (hints != null) for (String h : hints) if (ct.contains(h)) { s += 2; break; }
        }
        if (goal.length() == 0) return s;

        if (i.label != null && i.label.length() >= 2) {
            if (goal.contains(i.label)) s += 6;
            int hits = 0;
            for (int k = 0; k + 2 <= i.label.length(); k++) {
                if (goal.contains(i.label.substring(k, k + 2))) hits++;
            }
            s += Math.min(12, hits * 3);
        }
        Set<String> toks = i.tokens();
        for (Map.Entry<String, String[]> e : CUES.entrySet()) {
            boolean cue = false;
            for (String c : e.getKey().split("\\|")) {
                if (goal.contains(c)) { cue = true; break; }
            }
            if (!cue) continue;
            for (String en : e.getValue()) {
                if (toks.contains(en)) { s += 5; break; }
            }
        }
        // 英文目标词直接命中（用户说"打开 inbox"之类）
        for (String t : toks) {
            if (t.length() >= 4 && goal.contains(t)) { s += 3; break; }
        }
        return s;
    }

    /** 选出要注册为工具的接口（输入须已按 {@link #rank} 排好）：跳过未导出的 provider，取前 {@code max} 个。 */
    public static List<Iface> pickForTools(List<Iface> ranked, int max) {
        List<Iface> out = new ArrayList<Iface>();
        for (Iface i : ranked) {
            if (out.size() >= max) break;
            if (i.kind == Kind.PROVIDER && !i.exported) continue;
            out.add(i);
        }
        return out;
    }

    /** 软件段 + 动作段 → 工具名；与已占用名冲突时追加 _2、_3。 */
    public static String signFor(String software, Iface i, Set<String> taken) {
        String base = software + "." + i.actionKey();
        String s = base;
        int n = 2;
        while (taken != null && taken.contains(s)) s = base + "_" + (n++);
        if (taken != null) taken.add(s);
        return s;
    }

    // ------------------------------------------------------------------ 工具

    private static void put(Map<String, Iface> m, Iface i) {
        if (!m.containsKey(i.key())) m.put(i.key(), i);
    }

    private static void addUnique(List<String> l, String v) {
        if (v != null && !l.contains(v)) l.add(v);
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return -1; }
    }

    private static int indexOfAny(String s, String chars) {
        int best = -1;
        for (int k = 0; k < chars.length(); k++) {
            int x = s.indexOf(chars.charAt(k));
            if (x >= 0 && (best < 0 || x < best)) best = x;
        }
        return best;
    }

    /** 标签只留中日韩文字/字母/数字/空格，最长 16（防模板/日志污染）；没有中文返回 null。 */
    static String cleanLabel(String s) {
        if (s == null || !hasCjk(s)) return null;
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < s.length() && sb.length() < 16; k++) {
            char c = s.charAt(k);
            if ((c >= 0x4E00 && c <= 0x9FFF) || Character.isLetterOrDigit(c)) sb.append(c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    static boolean hasCjk(String s) {
        if (s == null) return false;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }

    static String lastSeg(String s, char sep) {
        if (s == null) return "";
        int i = s.lastIndexOf(sep);
        return i >= 0 ? s.substring(i + 1) : s;
    }

    static String stripActivity(String cls) {
        String c = cls;
        if (c.endsWith("Activity") && c.length() > 8) c = c.substring(0, c.length() - 8);
        return camelToSnake(c);
    }

    static String camelToSnake(String s) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (Character.isUpperCase(c) && k > 0 && !Character.isUpperCase(s.charAt(k - 1))) sb.append('_');
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    static void addTokens(Set<String> out, String s) {
        if (s == null) return;
        String snake = camelToSnake(s.replace('$', '.'));
        for (String t : snake.split("[^a-z0-9]+")) {
            if (t.length() >= 2) out.add(t);
        }
    }

    /** 与 ProbeEngine 同口径：小写、非字母数字转下划线、最长 24。 */
    static String sanitize(String s) {
        if (s == null) return "";
        String lower = camelToSnake(s);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lower.length() && sb.length() < 24; i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
            else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') sb.append('_');
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '_') sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    /** 测试/日志用：一行摘要。 */
    static String summary(List<Iface> l) {
        StringBuilder sb = new StringBuilder();
        for (Iface i : l) sb.append(i.kind).append(' ').append(i.key()).append(" s=").append(i.score).append('\n');
        return sb.toString();
    }
}
