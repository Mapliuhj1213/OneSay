package com.onesay.assistant.v2.roles;

/**
 * [roles] 「未安装 X，点此安装」的跳转决策（纯逻辑，JVM 可测；2026-10-10 用户拍板：<b>去掉应用宝</b>，按国内 / 国外分流）。
 * <b>通用</b>：任何指向未安装包的能力都可复用。
 *
 * <p>区域来自 app_roles.json 种子的 {@code region}（{@code cn} / {@code intl}）；表里没有的包一律按 {@code cn}。
 *
 * <ol>
 *   <li><b>cn</b> → 三星商店深链 {@code samsungapps://ProductDetail/<包名>}（指定 {@value #GALAXY_PKG} 打开，真机已验证）；
 *       没装三星商店 → {@code market://details?id=<包名>}。</li>
 *   <li><b>intl</b> → 先测连通性（{@value #CONNECTIVITY_URL} 期望 204，3 秒超时，<b>只能在后台线程调</b>）：
 *     <ul>
 *       <li>通 → {@code market://details?id=<包名>} 指定 Play 商店（{@value #PLAY_PKG}）；没装 Play → 普通 market://。</li>
 *       <li>不通且<b>当前没有 VPN</b>、用户在设置里填了代理 App 包名（{@link #vpnPackage()}，默认空）→ 启动它，
 *           每 {@value #POLL_INTERVAL_MS} ms 复测，最多约 {@value #POLL_MAX_MS} ms；通了 → Play，否则退回商店并附一句说明。</li>
 *       <li>不通且<b>已有 VPN</b>（安卓同时只能开一个 VPN）→ <b>绝不自动启动</b>，
 *           返回 {@link Step#OFFER_VPN_CHIP}：面板给芯片 {@link #VPN_CHIP}，点了才走上一条的启动+复测。</li>
 *       <li>没填代理 App / 没装 → 直接退回商店（附同一句说明）。</li>
 *     </ul></li>
 * </ol>
 * 打开商店是 UI 动作（不改系统状态），不弹确认卡。不需要 root。包名不合法一律不出链接（绝不把任意字符串拼进 URI）。
 */
public final class InstallLinkPlanner {

    private InstallLinkPlanner() {}

    public static final String GALAXY_PKG = "com.sec.android.app.samsungapps";
    public static final String PLAY_PKG = "com.android.vending";
    /** 用户填写的代理 / VPN App 包名（设置 › 设备能力与功能 › 隐私与外部请求）；默认空 = 不自动启动任何 App。 */
    private static volatile String sVpnPkg = "";

    public static String vpnPackage() { return sVpnPkg; }

    /** 设代理 App 包名；不合法 → 清空。 */
    public static void setVpnPackage(String pkg) {
        String p = pkg == null ? "" : pkg.trim();
        sVpnPkg = p.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+") ? p : "";
    }
    public static final String CONNECTIVITY_URL = "https://connectivitycheck.gstatic.com/generate_204";
    public static final int CONNECTIVITY_TIMEOUT_MS = 3000;
    public static final long POLL_MAX_MS = 10000L;
    public static final long POLL_INTERVAL_MS = 1000L;

    public static final String VPN_CHIP = "打开代理 App（会断开当前 VPN）";
    public static final String GALAXY_CHIP = "改用三星商店";
    public static final String NOTE_FALLBACK = "Play 连不上，改用三星商店。";
    public static final String NOTE_FALLBACK_MARKET = "Play 连不上，本机也没有三星商店，改用系统应用商店。";
    public static final String NOTE_VPN_BUSY = "Play 连不上。现在已经开着一个 VPN，自动打开代理 App 会把它断开，所以没有自动切。";
    public static final String NOTE_NO_STORE = "本机没有能打开的应用商店。";

    /** 区域：国内 App 走三星商店，国外 App 走 Play。 */
    public enum Region {
        CN, INTL;

        /** "intl" → INTL；其余（含 null / 空 / 拼错）→ CN。 */
        public static Region parse(String s) {
            return s != null && "intl".equalsIgnoreCase(s.trim()) ? INTL : CN;
        }

        public String id() {
            return this == INTL ? "intl" : "cn";
        }
    }

    public enum Kind { GALAXY, PLAY, MARKET, NONE }

    /** 一次决策的下一步。 */
    public enum Step {
        /** 打开 {@link Decision#link}（可能附 {@link Decision#note}）。 */
        OPEN,
        /** 已有 VPN：先不动，给「打开代理 App」芯片；{@link Decision#link} 是「改用三星商店」的备选链接。 */
        OFFER_VPN_CHIP
    }

    /** 静态环境探测（生产 = PackageManager；测试 = 假实现）。 */
    public interface Env {
        boolean isInstalled(String pkg);

        /** 该 URI 的 ACTION_VIEW 是否能解析到任一 Activity。 */
        boolean canResolve(String uri);
    }

    /** 需要联网 / 启动 App 的环境（生产 = {@code AndroidInstallEnv}；测试 = 脚本化假实现）。全部阻塞调用，只在后台线程用。 */
    public interface NetEnv extends Env {
        /** 连通性检查：{@link #CONNECTIVITY_URL} 返回 204 才算通（{@link #CONNECTIVITY_TIMEOUT_MS} 超时）。 */
        boolean connectivityOk();

        /** 当前是否有 VPN 在用（NetworkCapabilities.TRANSPORT_VPN）。 */
        boolean vpnActive();

        /** 用启动 intent 打开某个 App；打不开返回 false。 */
        boolean launchApp(String pkg);

        long nowMs();

        void sleepMs(long ms);
    }

    public static final class Link {
        public final Kind kind;
        public final String uri;
        /** 非 null = 指定由该包打开（三星商店 / Play）。 */
        public final String viaPkg;

        Link(Kind kind, String uri, String viaPkg) {
            this.kind = kind;
            this.uri = uri;
            this.viaPkg = viaPkg;
        }

        @Override public String toString() {
            return kind + " " + uri + (viaPkg != null ? " via " + viaPkg : "");
        }
    }

    public static final class Decision {
        public final Step step;
        public final Link link;
        /** 给用户的一句话（null = 不说）。 */
        public final String note;
        /** 调试 / 日志：走了哪条分支。 */
        public final String trace;

        Decision(Step step, Link link, String note, String trace) {
            this.step = step;
            this.link = link;
            this.note = note;
            this.trace = trace;
        }

        @Override public String toString() {
            return step + " " + link + (note != null ? " note=" + note : "") + " [" + trace + "]";
        }
    }

    /** [round7] 外部商店拉起后面板让位的延迟（与「打开应用 / 跳转界面」同口径：220 ms 后平滑收起）。 */
    public static final long YIELD_DELAY_MS = 220L;

    /**
     * [round7] 走完一次安装决策后面板要不要让位（10-10 真机：点安装提示打开三星商店后，面板还盖在商店详情页上）。
     * 真的拉起了外部界面（商店 / 已装好的 App）→ 让位；只给了「打开代理 App」芯片、或什么都没打开 → 留着（用户还要点）。
     */
    public static boolean yieldsPanel(Step step, boolean launched) {
        return launched && step != Step.OFFER_VPN_CHIP;
    }

    public static boolean validPkg(String pkg) {
        return pkg != null && RoleCatalog.PKG.matcher(pkg).matches();
    }

    public static String galaxyUri(String pkg) {
        return "samsungapps://ProductDetail/" + pkg;
    }

    public static String marketUri(String pkg) {
        return "market://details?id=" + pkg;
    }

    /** 区域查表：种子里有就用种子的 region，没有（或目录不可用）按 cn。 */
    public static Region regionOf(RoleCatalog catalog, String pkg) {
        if (catalog == null || pkg == null) return Region.CN;
        RoleCatalog.Seed s = catalog.seedByPkg(pkg);
        return s == null ? Region.CN : s.region;
    }

    private static boolean installed(Env env, String pkg) {
        try {
            return env != null && env.isInstalled(pkg);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean resolvable(Env env, String uri) {
        try {
            return env != null && env.canResolve(uri);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 普通 market://（不指定商店，系统选）；解析不到任何 Activity → NONE。 */
    static Link plainMarket(String pkg, Env env) {
        String u = marketUri(pkg);
        return resolvable(env, u) ? new Link(Kind.MARKET, u, null) : new Link(Kind.NONE, u, null);
    }

    /** 国内路径 / 国外失败的退路：三星商店深链；没装三星商店 → market://。包名不合法 → null。 */
    public static Link galaxyOrMarket(String pkg, Env env) {
        if (!validPkg(pkg)) return null;
        if (installed(env, GALAXY_PKG)) return new Link(Kind.GALAXY, galaxyUri(pkg), GALAXY_PKG);
        return plainMarket(pkg, env);
    }

    /** Play：装了 Play 商店 → 指定它打开 market://；没装 → 普通 market://。包名不合法 → null。 */
    public static Link play(String pkg, Env env) {
        if (!validPkg(pkg)) return null;
        if (installed(env, PLAY_PKG)) return new Link(Kind.PLAY, marketUri(pkg), PLAY_PKG);
        return plainMarket(pkg, env);
    }

    /** 不联网的「预选」：给日志 / 调试看（cn = 最终链接；intl = 假定 Play 通时的链接）。 */
    public static Link preview(String pkg, Region region, Env env) {
        return region == Region.INTL ? play(pkg, env) : galaxyOrMarket(pkg, env);
    }

    private static Decision fallback(String pkg, Env env, String why) {
        Link g = galaxyOrMarket(pkg, env);
        String note = g.kind == Kind.GALAXY ? NOTE_FALLBACK : g.kind == Kind.MARKET ? NOTE_FALLBACK_MARKET : NOTE_FALLBACK + NOTE_NO_STORE;
        return new Decision(Step.OPEN, g, note, why + "→" + g.kind);
    }

    private static Decision open(Link l, String trace) {
        return new Decision(Step.OPEN, l, l.kind == Kind.NONE ? NOTE_NO_STORE : null, trace + "→" + l.kind);
    }

    private static boolean probe(NetEnv env) {
        try {
            return env.connectivityOk();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 完整决策（<b>阻塞，后台线程</b>）：cn 不联网；intl 测一次连通性，必要时自动启动用户填写的代理 App并复测。
     * 已有 VPN 时只返回 {@link Step#OFFER_VPN_CHIP}，绝不自动切。包名不合法 → null。
     */
    public static Decision decide(String pkg, Region region, NetEnv env) {
        if (!validPkg(pkg)) return null;
        if (region != Region.INTL) return open(galaxyOrMarket(pkg, env), "cn");
        if (env == null) return fallback(pkg, null, "intl:无环境");
        if (probe(env)) return open(play(pkg, env), "intl:通");
        boolean vpn;
        try {
            vpn = env.vpnActive();
        } catch (Throwable t) {
            vpn = true; // 判断不了就当已有 VPN：宁可多问一次，也不误断用户正在用的 VPN
        }
        if (vpn) {
            if (!(vpnPackage().length() > 0 && installed(env, vpnPackage()))) return fallback(pkg, env, "intl:不通+已有VPN+无代理App");
            Link g = galaxyOrMarket(pkg, env);
            return new Decision(Step.OFFER_VPN_CHIP, g, NOTE_VPN_BUSY, "intl:不通+已有VPN→芯片");
        }
        return launchVpnAndRecheck(pkg, env, "intl:不通+无VPN");
    }

    /** 用户点了「打开代理 App（会断开当前 VPN）」芯片（<b>阻塞，后台线程</b>）：启动代理 App → 复测 → Play 或退三星商店。 */
    public static Decision afterVpnChip(String pkg, NetEnv env) {
        if (!validPkg(pkg)) return null;
        if (env == null) return fallback(pkg, null, "芯片:无环境");
        return launchVpnAndRecheck(pkg, env, "芯片");
    }

    private static Decision launchVpnAndRecheck(String pkg, NetEnv env, String trace) {
        if (!(vpnPackage().length() > 0 && installed(env, vpnPackage()))) return fallback(pkg, env, trace + "+无代理App");
        boolean launched;
        try {
            launched = env.launchApp(vpnPackage());
        } catch (Throwable t) {
            launched = false;
        }
        if (!launched) return fallback(pkg, env, trace + "+代理App打不开");
        long start = env.nowMs();
        int n = 0;
        while (true) {
            env.sleepMs(POLL_INTERVAL_MS);
            n++;
            if (probe(env)) return open(play(pkg, env), trace + "+代理App第" + n + "次通");
            if (env.nowMs() - start >= POLL_MAX_MS) break;
        }
        return fallback(pkg, env, trace + "+代理App" + n + "次仍不通");
    }
}
