package com.onesay.assistant.v2.roles;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [roles] 默认应用角色（App Role）目录：<b>纯数据</b>（{@code assets/app_roles.json}），本类只负责解析与查表（纯 Java，JVM 可测）。
 *
 * <p>一个「角色」= 一类可由多个 App 完成的用户意图（打车 / 导航 / 外卖 / 听歌 …），对标 Android RoleManager「默认应用」
 * 与 Siri / 小爱「用哪个 App」。加一个角色（浏览器 / 邮件 / 视频 / 购物）只需要在 JSON 里加一条，不改代码。
 *
 * <p>JSON 里的 {@code seeds} 只是<b>元数据提示</b>（标签、别名、交接方式），不是权威：真正的候选由
 * {@link CandidateDeriver} 在运行时从「已安装包 + 标准 intent 解析 + 挖掘接口」推导。
 */
public final class RoleCatalog {

    /** 标准 intent 探针（如导航 geo:、邮件 mailto:、浏览器 http:）。 */
    public static final class Probe {
        public final String action;
        public final String data;
        public final String category;

        Probe(String action, String data, String category) {
            this.action = action;
            this.data = data;
            this.category = category;
        }

        @Override public String toString() {
            return action + (data != null ? " " + data : "") + (category != null ? " [" + category + "]" : "");
        }
    }

    /** 种子候选（元数据提示）。 */
    public static final class Seed {
        public final String id;
        public final String pkg;
        public final String label;
        /** 交给 app.launch 的名字（只作展示/兜底；执行按 pkg）。 */
        public final String launchName;
        /** 口头叫法（已转小写，长的在前）。 */
        public final String[] aliases;
        /** 句子带目的地时用的能力（覆盖角色级 dest_sign）；null = 用角色级或直接打开。 */
        public final String destSign;
        /** 可选：挖掘出的深链能力 sign（设备上存在时代替 app.launch）。 */
        public final String handoffSign;
        /**
         * 可选（R5）：综合类 App（京东、淘宝…）。只有句子里说了角色（「用京东点外卖」）才算这个角色；
         * 光说「打开京东」不会被当成外卖的一次使用，也不会因此推断成外卖默认。
         */
        public final boolean general;
        /**
         * 区域（2026-10-10 安装链改造）：{@code cn} 国内 App → 三星商店；{@code intl} 国外 App → Play（必要时代理 App）。
         * JSON 不写或写错一律按 cn（见 {@link InstallLinkPlanner}）。
         */
        public final InstallLinkPlanner.Region region;
        /**
         * 可选（r2/bike-chooser）：已安装时点应用格子优先打开的深链（如支付宝扫一扫）；只收自定义 scheme（{@link StoreLinks#safeDeepLink}），
         * 打不开就退回按包名打开主界面。null = 直接打开主界面。
         */
        public String openUri;

        Seed(String id, String pkg, String label, String launchName, String[] aliases, String destSign, String handoffSign) {
            this(id, pkg, label, launchName, aliases, destSign, handoffSign, false);
        }

        Seed(String id, String pkg, String label, String launchName, String[] aliases, String destSign, String handoffSign, boolean general) {
            this(id, pkg, label, launchName, aliases, destSign, handoffSign, general, InstallLinkPlanner.Region.CN);
        }

        Seed(String id, String pkg, String label, String launchName, String[] aliases, String destSign, String handoffSign, boolean general,
             InstallLinkPlanner.Region region) {
            this.id = id;
            this.pkg = pkg;
            this.label = label;
            this.launchName = launchName;
            this.aliases = aliases;
            this.destSign = destSign;
            this.handoffSign = handoffSign;
            this.general = general;
            this.region = region == null ? InstallLinkPlanner.Region.CN : region;
        }
    }

    public static final class Role {
        public final String id;
        public final String label;
        public final Pattern trigger;
        /** "address" = 句子里可带目的地（「打车去机场」）。 */
        public final String slot;
        /** S1 剪贴板芯片种类（TAXI / NAV），null = 不接芯片。 */
        public final String clipChip;
        public final Probe probe;
        /** 角色级「带目的地」能力（如导航 s2.nav.app）。 */
        public final String destSign;
        /** 挖掘接口匹配词：挖到的接口文本含这些词且包已安装 → 也算候选。 */
        public final List<String> minedKeywords;
        public final List<Seed> seeds;
        /** 可选（r2/bike-chooser）：「打开应用商店查看更多」的搜索词（软件类别名）；null = 用 {@link #label}。 */
        public String storeQuery;
        /** 可选（r2/bike-chooser）：只装了一个时也弹网格让用户选（共享单车：常见是支付宝 / 微信里扫，用户要看到选项）。 */
        public boolean alwaysChoose;
        final Pattern destA;
        final Pattern destB;
        final Pattern trimA;
        final Pattern trimB;

        Role(String id, String label, Pattern trigger, String triggerAlt, String slot, String clipChip, Probe probe,
             String destSign, List<String> minedKeywords, List<Seed> seeds) {
            this.id = id;
            this.label = label;
            this.trigger = trigger;
            this.slot = slot;
            this.clipChip = clipChip;
            this.probe = probe;
            this.destSign = destSign;
            this.minedKeywords = minedKeywords;
            this.seeds = seeds;
            if (hasAddress()) {
                destA = Pattern.compile("(?:" + triggerAlt + "|带我)(?:去|到|回|前往)(.{1,30})$");
                destB = Pattern.compile("^(?:我要|我想|帮我)?(?:去|到|回)(.{1,30}?)(?:" + triggerAlt + ")$");
                String al = aliasAlt(seeds);
                trimA = al.length() == 0 ? null : Pattern.compile("^(用|拿)?(" + al + ")");
                trimB = al.length() == 0 ? null : Pattern.compile("(用|拿)(" + al + ")(" + triggerAlt + ")?$");
            } else {
                destA = destB = trimA = trimB = null;
            }
        }

        public boolean hasAddress() {
            return "address".equals(slot);
        }

        public Seed seed(String id) {
            if (id == null) return null;
            for (Seed s : seeds) if (s.id.equals(id)) return s;
            return null;
        }

        public Seed seedByPkg(String pkg) {
            if (pkg == null) return null;
            for (Seed s : seeds) if (s.pkg.equals(pkg)) return s;
            return null;
        }

        /** 从句子里取目的地（「打车去机场」「导航到公司」「去公司打车」）。没有返回 null。 */
        public String destination(String key) {
            if (!hasAddress() || key == null) return null;
            Matcher m = destA.matcher(key);
            if (m.find()) return trimDest(m.group(1));
            m = destB.matcher(key);
            if (m.find()) return trimDest(m.group(1));
            return null;
        }

        private String trimDest(String d) {
            String s = d;
            if (trimA != null) s = trimA.matcher(s).replaceAll("");
            if (trimB != null) s = trimB.matcher(s).replaceAll("");
            s = s.replaceAll("(吧|了|呗|啊|呀)$", "").trim();
            return s.length() == 0 ? null : s;
        }

        @Override public String toString() {
            return id + "(" + label + ")";
        }
    }

    private final List<Role> mRoles;
    private final String mSource;

    private RoleCatalog(List<Role> roles, String source) {
        this.mRoles = Collections.unmodifiableList(roles);
        this.mSource = source;
    }

    public List<Role> roles() {
        return mRoles;
    }

    public String source() {
        return mSource;
    }

    public Role role(String id) {
        if (id == null) return null;
        for (Role r : mRoles) if (r.id.equals(id)) return r;
        return null;
    }

    public Role roleForChip(String chipKind) {
        if (chipKind == null) return null;
        for (Role r : mRoles) if (chipKind.equals(r.clipChip)) return r;
        return null;
    }

    /** 句子属于哪个角色（看触发词）；都不是返回 null。 */
    public Role roleOf(String key) {
        if (key == null) return null;
        for (Role r : mRoles) if (r.trigger.matcher(key).find()) return r;
        return null;
    }

    /** 句子里点名的种子 App（最长别名优先）；限定在 role 内（null = 全表）。 */
    public Seed seedIn(String key, Role role) {
        if (key == null) return null;
        Seed best = null;
        int bestLen = 0;
        for (Role r : mRoles) {
            if (role != null && r != role) continue;
            for (Seed s : r.seeds) {
                for (String al : s.aliases) {
                    if (al.length() > bestLen && key.contains(al)) {
                        best = s;
                        bestLen = al.length();
                    }
                }
            }
        }
        return best;
    }

    /** 按包名找种子（全表）。 */
    public Seed seedByPkg(String pkg) {
        for (Role r : mRoles) {
            Seed s = r.seedByPkg(pkg);
            if (s != null) return s;
        }
        return null;
    }

    /** 只属于一个角色的种子（滴滴 → 打车）；属于多个（高德、美团）或标了 general 的综合 App（京东）返回 null。 */
    public Role uniqueRoleOf(Seed seed) {
        if (seed == null || seed.general) return null;
        Role only = null;
        for (Role r : mRoles) {
            if (r.seedByPkg(seed.pkg) != null) {
                if (only != null) return null;
                only = r;
            }
        }
        return only;
    }

    // ------------------------------------------------------------------ 解析

    public static RoleCatalog parse(String json, String source) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray arr = root.optJSONArray("roles");
        List<Role> roles = new ArrayList<Role>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Role r = parseRole(o);
                if (r != null && findIn(roles, r.id) == null) roles.add(r);
            }
        }
        return new RoleCatalog(roles, source);
    }

    /** 宽松解析：坏数据返回空目录（不让角色层拖垮 S2）。 */
    public static RoleCatalog parseOrEmpty(String json, String source) {
        try {
            return parse(json, source);
        } catch (Throwable t) {
            return empty("bad:" + source);
        }
    }

    public static RoleCatalog empty(String source) {
        return new RoleCatalog(new ArrayList<Role>(), source);
    }

    private static Role findIn(List<Role> l, String id) {
        for (Role r : l) if (r.id.equals(id)) return r;
        return null;
    }

    static final Pattern ID = Pattern.compile("[a-z][a-z0-9_]{0,31}");
    public static final Pattern PKG = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");

    private static Role parseRole(JSONObject o) {
        String id = o.optString("id", "").trim();
        String label = o.optString("label", "").trim();
        if (!ID.matcher(id).matches() || label.length() == 0) return null;
        List<String> alts = new ArrayList<String>();
        JSONArray tr = o.optJSONArray("triggers");
        if (tr != null) {
            for (int i = 0; i < tr.length(); i++) {
                String t = tr.optString(i, "").trim().toLowerCase(Locale.ROOT);
                if (t.length() > 0) alts.add(Pattern.quote(t));
            }
        }
        String rx = o.optString("trigger_regex", "").trim();
        if (rx.length() > 0) alts.add(rx);
        if (alts.isEmpty()) return null;
        StringBuilder alt = new StringBuilder();
        for (String a : alts) {
            if (alt.length() > 0) alt.append('|');
            alt.append(a);
        }
        Pattern trigger;
        try {
            trigger = Pattern.compile(alt.toString());
        } catch (Exception e) {
            return null;
        }
        Probe probe = null;
        JSONObject p = o.optJSONObject("probe");
        if (p != null && p.optString("action", "").length() > 0) {
            probe = new Probe(p.optString("action"), nz(p.optString("data", "")), nz(p.optString("category", "")));
        }
        List<String> mk = new ArrayList<String>();
        JSONArray m = o.optJSONArray("mined_keywords");
        if (m != null) for (int i = 0; i < m.length(); i++) {
            String s = m.optString(i, "").trim().toLowerCase(Locale.ROOT);
            if (s.length() > 0) mk.add(s);
        }
        List<Seed> seeds = new ArrayList<Seed>();
        JSONArray sa = o.optJSONArray("seeds");
        if (sa != null) {
            for (int i = 0; i < sa.length(); i++) {
                JSONObject s = sa.optJSONObject(i);
                if (s == null) continue;
                String sid = s.optString("id", "").trim();
                String pkg = s.optString("pkg", "").trim();
                if (!ID.matcher(sid).matches() || !PKG.matcher(pkg).matches()) continue;
                String sl = nzOr(s.optString("label", ""), pkg);
                List<String> al = new ArrayList<String>();
                JSONArray aa = s.optJSONArray("aliases");
                if (aa != null) for (int j = 0; j < aa.length(); j++) {
                    String a = aa.optString(j, "").trim().toLowerCase(Locale.ROOT);
                    if (a.length() > 0) al.add(a);
                }
                String lab = sl.toLowerCase(Locale.ROOT);
                if (!al.contains(lab)) al.add(lab);
                Collections.sort(al, new java.util.Comparator<String>() {
                    @Override public int compare(String x, String y) {
                        return y.length() - x.length();
                    }
                });
                Seed seed = new Seed(sid, pkg, sl, nzOr(s.optString("launch", ""), sl), al.toArray(new String[0]),
                        nz(s.optString("dest_sign", "")), nz(s.optString("handoff_sign", "")), s.optBoolean("general", false),
                        InstallLinkPlanner.Region.parse(s.optString("region", "cn")));
                try {   // [r2/bike-chooser] 可选扩展字段：任何异常都不能拖垮核心角色解析
                    String ou = s.optString("open_uri", "").trim();
                    if (StoreLinks.safeDeepLink(ou)) seed.openUri = ou;
                } catch (Throwable ignored) {
                }
                seeds.add(seed);
            }
        }
        Role role = new Role(id, label, trigger, alt.toString(), nz(o.optString("slot", "")), nz(o.optString("clip_chip", "")),
                probe, nz(o.optString("dest_sign", "")), Collections.unmodifiableList(mk), Collections.unmodifiableList(seeds));
        try {   // [r2/bike-chooser] 可选扩展字段：任何异常（含类初始化失败）都不能让整个目录变成 0 个角色
            role.storeQuery = AppChoiceSpec.sanitizeQuery(o.optString("store_query", ""));
        } catch (Throwable ignored) {
            role.storeQuery = null;
        }
        role.alwaysChoose = o.optBoolean("always_choose", false);
        return role;
    }

    private static String aliasAlt(List<Seed> seeds) {
        List<String> all = new ArrayList<String>();
        for (Seed s : seeds) for (String a : s.aliases) if (!all.contains(a)) all.add(a);
        Collections.sort(all, new java.util.Comparator<String>() {
            @Override public int compare(String x, String y) {
                return y.length() - x.length();
            }
        });
        StringBuilder sb = new StringBuilder();
        for (String a : all) {
            if (sb.length() > 0) sb.append('|');
            sb.append(Pattern.quote(a));
        }
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null || s.trim().length() == 0 ? null : s.trim();
    }

    private static String nzOr(String s, String d) {
        return s == null || s.trim().length() == 0 ? d : s.trim();
    }

    // ------------------------------------------------------------------ 当前目录（进程级）

    public static final String ASSET = "app_roles.json";
    private static volatile RoleCatalog sActive;

    /** Android 侧（RoleHooks）从 assets 读出后设置；JVM 测试也可直接设置。 */
    public static void setActive(RoleCatalog c) {
        sActive = c;
    }

    /**
     * 当前目录。没设置过时：JVM 下按 {@code -Dbixby.root} 读 {@code app/assets/app_roles.json}；都没有返回空目录（习惯功能自然失效，不抛异常）。
     */
    public static RoleCatalog active() {
        RoleCatalog c = sActive;
        if (c != null) return c;
        synchronized (RoleCatalog.class) {
            if (sActive == null) {
                RoleCatalog f = null;
                String root = System.getProperty("bixby.root");
                if (root != null) f = fromFile(new File(root, "app/assets/" + ASSET));
                sActive = f != null ? f : empty("none");
            }
            return sActive;
        }
    }

    public static RoleCatalog fromFile(File f) {
        if (f == null || !f.isFile()) return null;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            return parseOrEmpty(readAll(in), f.getName());
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), "UTF-8");
    }
}
