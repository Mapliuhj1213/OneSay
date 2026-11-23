package com.onesay.assistant.v2.whitelist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * [app-whitelist-1116] 应用白名单：数据模型 + 同步格式（JSON）+ 合并规则。纯 Java（Java 7 语法，无第三方依赖），
 * 手机个人版、手机开源版、电脑端三份<b>除 package 行外逐字相同</b>（一致性测试按去掉 package 行后的 SHA-256 核对，见 docs/APP-WHITELIST.md）。
 *
 * <p>同步格式（format = onesay.app_whitelist，version = 1）：
 * <pre>
 * {"format":"onesay.app_whitelist","version":1,"device":"pc-xxx","entries":[
 *   {"platform":"android","id":"com.example.chat","name":"聊天","added_at":1763280000000,"updated_at":1763280000000,
 *    "origin":"phone-1","origin_kind":"phone","deleted":false}]}
 * </pre>
 * <ul>
 *   <li>条目键 = platform + ":" + 规范化 id（android：包名原样、比较时小写；windows：只取 exe 文件名、小写、必须以 .exe 结尾）。</li>
 *   <li>删除写墓碑（deleted=true，updated_at = 删除时刻），不真删，这样离线的一端重连后不会把它「复活」。</li>
 *   <li>合并 = 按条目最后写入优先：updated_at 大的赢；相同时墓碑赢（删除更安全）；再相同按 origin 字典序大的赢。
 *       合并满足交换律、结合律、幂等，两端各合一遍结果相同。</li>
 *   <li>新版本号（version &gt; 1）或格式名不对的整份拒收；单条不合法（平台不认识、id 写法不对、属于不能加白名单的应用）的丢掉，其余照收。</li>
 *   <li>上限：活条目 {@link #MAX_LIVE} 条，总条目（含墓碑）{@link #MAX_TOTAL} 条，超出时先丢最老的墓碑。</li>
 * </ul>
 * 白名单默认空。本类只管数据；放不放行由 {@link ActionPolicy} 判。
 *
 * <p><b>本机确认一次才生效</b>（2026-11-16 用户定）：另一端同步来的放行条目先是「待确认」，{@link #contains} 不认，
 * 本机用户在界面上看过风险说明点「确认生效」（{@link #confirm}）后才算数；本机自己加的（{@link #add}，加之前已弹过风险确认）直接生效。
 * 确认记在本机（键 + added_at），不进同步格式（{@link #toJson}），只进本机存盘格式（{@link #toLocalJson}）：
 * 对方删了又加回来（added_at 变了）要重新确认。旧存盘文件没有确认记录 → 全部待确认（宁可多问一次）。
 */
public final class AppWhitelist {

    public static final String FORMAT = "onesay.app_whitelist";
    public static final int VERSION = 1;
    public static final String ANDROID = "android", WINDOWS = "windows";
    public static final int MAX_LIVE = 200, MAX_TOTAL = 1000, MAX_NAME = 64;

    private static final Pattern PKG_RE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    private static final Pattern EXE_RE = Pattern.compile("[^\\\\/:*?\"<>|\\s][^\\\\/:*?\"<>|]{0,99}\\.exe");

    /**
     * 不能加进白名单的（加了等于把门禁整个拿掉）：OneSay 自己、系统安装器 / 权限管理、Shizuku / Magisk，
     * Windows 上的命令行 / 脚本解释器 / 常被滥用的系统程序 / 系统管理工具。
     */
    private static final String[] NEVER_ANDROID = {
            "com.bixbyui", "com.bixbyui.debug", "com.onesay.assistant", "com.onesay.assistant.debug",
            "com.android.packageinstaller", "com.google.android.packageinstaller", "com.android.permissioncontroller",
            "com.google.android.permissioncontroller", "moe.shizuku.privileged.api", "com.topjohnwu.magisk", "com.android.shell",
            "com.android.systemui",
            // ★ rel-1118 B13：厂商安装器 / 安全中心 / 权限管理（魅族本机就是 com.meizu.safe）、系统设置、其它 root / 内核补丁管理器
            "com.android.settings", "com.meizu.safe", "com.meizu.permissioncontroller",
            "com.miui.packageinstaller", "com.miui.securitycenter", "com.miui.securitycore",
            "com.coloros.safecenter", "com.oplus.safecenter", "com.coloros.securitypermission", "com.oplus.securitypermission", "com.oppo.safe",
            "com.samsung.android.packageinstaller", "com.samsung.android.permissioncontroller", "com.samsung.android.lool", "com.samsung.android.sm.devicesecurity",
            "com.huawei.systemmanager", "com.huawei.securitymgr", "com.hihonor.systemmanager", "com.hihonor.securitymgr",
            "com.vivo.permissionmanager", "com.vivo.safecenter", "com.iqoo.secure", "com.bbk.account",
            "me.weishu.kernelsu", "io.github.huskydg.magisk", "io.github.vvb2060.magisk", "me.bmax.apatch"};
    private static final String[] NEVER_WINDOWS = {
            "onesay.exe", "javaw.exe", "java.exe", "cmd.exe", "powershell.exe", "pwsh.exe", "powershell_ise.exe", "wscript.exe", "cscript.exe",
            "mshta.exe", "rundll32.exe", "regsvr32.exe", "regedit.exe", "reg.exe", "msiexec.exe", "schtasks.exe", "sc.exe", "net.exe", "net1.exe",
            "wmic.exe", "bash.exe", "wsl.exe", "python.exe", "pythonw.exe", "py.exe", "node.exe", "certutil.exe", "bitsadmin.exe", "taskmgr.exe",
            "mmc.exe", "explorer.exe", "conhost.exe", "windowsterminal.exe", "wt.exe", "psexec.exe", "psexec64.exe", "runas.exe", "consent.exe",
            "control.exe", "installutil.exe", "msbuild.exe", "forfiles.exe", "winget.exe", "curl.exe", "bitsadmin.exe",
            // ★ rel-1118 B13：卷影 / 启动配置 / 日志 / 磁盘 / 文件所有权与 ACL / 关机等高危系统工具
            "vssadmin.exe", "bcdedit.exe", "bcdboot.exe", "wevtutil.exe", "diskpart.exe", "takeown.exe", "icacls.exe", "cacls.exe",
            "shutdown.exe", "netsh.exe", "fsutil.exe", "cipher.exe", "wbadmin.exe", "reagentc.exe", "sdelete.exe", "sdelete64.exe",
            "auditpol.exe", "format.exe"};
    /** ★ rel-1118 B13：8.3 短名（POWERS~1.EXE）看不出真名，一律不收。 */
    private static final Pattern SHORT_83 = Pattern.compile(".*~[0-9]+(\\.exe)?");

    // ================================================================== 条目

    public static final class Entry {
        public final String platform, id, name, origin, originKind;
        public final long addedAt, updatedAt;
        public final boolean deleted;

        public Entry(String platform, String id, String name, long addedAt, long updatedAt, String origin, String originKind, boolean deleted) {
            this.platform = platform;
            this.id = id;
            this.name = name == null ? "" : name;
            this.addedAt = addedAt;
            this.updatedAt = updatedAt;
            this.origin = origin == null ? "" : origin;
            this.originKind = originKind == null ? "" : originKind;
            this.deleted = deleted;
        }

        public String key() { return AppWhitelist.key(platform, id); }

        /** 合并时谁赢：updated_at 大的；相同时墓碑；再相同 origin 字典序大的；全同 = 同一条。 */
        static boolean beats(Entry a, Entry b) {
            if (a.updatedAt != b.updatedAt) return a.updatedAt > b.updatedAt;
            if (a.deleted != b.deleted) return a.deleted;
            int c = a.origin.compareTo(b.origin);
            if (c != 0) return c > 0;
            int n = a.name.compareTo(b.name);
            if (n != 0) return n > 0;
            if (a.addedAt != b.addedAt) return a.addedAt > b.addedAt;
            // ★ rel-1118 B20：以前到这里就「先到的赢」，id 只差大小写 / origin_kind 不同的两条在两端合并结果不一样（不满足交换律）。
            //   再比 id（区分大小写的字典序）和 origin_kind，全同才算同一条。
            int i = a.id == null ? (b.id == null ? 0 : -1) : (b.id == null ? 1 : a.id.compareTo(b.id));
            if (i != 0) return i > 0;
            return a.originKind.compareTo(b.originKind) > 0;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("platform", platform);
            m.put("id", id);
            m.put("name", name);
            m.put("added_at", Long.valueOf(addedAt));
            m.put("updated_at", Long.valueOf(updatedAt));
            m.put("origin", origin);
            m.put("origin_kind", originKind);
            m.put("deleted", Boolean.valueOf(deleted));
            return m;
        }

        @Override public String toString() { return (deleted ? "-" : "+") + key() + "@" + updatedAt; }
    }

    // ================================================================== 规范化 / 能不能加

    /** 规范化 id：android 去空白；windows 只留文件名、小写。写法不对返回 null。 */
    public static String normId(String platform, String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (ANDROID.equals(platform)) {
            if (s.length() == 0 || s.length() > 150 || !PKG_RE.matcher(s).matches()) return null;
            return s;
        }
        if (WINDOWS.equals(platform)) {
            int k = Math.max(s.lastIndexOf('\\'), s.lastIndexOf('/'));
            if (k >= 0) s = s.substring(k + 1);
            if (s.startsWith("\"")) s = s.substring(1);
            if (s.endsWith("\"")) s = s.substring(0, s.length() - 1);
            s = s.trim().toLowerCase(Locale.ROOT);
            if (!s.endsWith(".exe") && s.length() > 0 && s.indexOf('.') < 0) s = s + ".exe";
            if (!EXE_RE.matcher(s).matches()) return null;
            return s;
        }
        return null;
    }

    public static String key(String platform, String id) {
        return platform + ":" + (id == null ? "" : id.toLowerCase(Locale.ROOT));
    }

    /** 不能加白名单的原因；null = 可以加（id 先规范化过）。 */
    public static String refuseReason(String platform, String id) {
        String n = normId(platform, id);
        if (n == null) return ANDROID.equals(platform) ? "包名写法不对（例如 com.example.app）" : WINDOWS.equals(platform) ? "程序名写法不对（例如 notepad.exe）" : "平台只能是 android 或 windows";
        String low = n.toLowerCase(Locale.ROOT);
        String[] never = ANDROID.equals(platform) ? NEVER_ANDROID : NEVER_WINDOWS;
        if (WINDOWS.equals(platform) && SHORT_83.matcher(low).matches()) return "8.3 短名（带 ~数字）看不出是哪个程序，不能加白名单，请写完整程序名";
        for (String x : never) if (x.equals(low)) return ANDROID.equals(platform) ? "系统安装器 / 权限管理 / 助手自己这类应用不能加白名单" : "命令行、脚本解释器、系统管理工具和助手自己不能加白名单（加了等于关掉审批）";
        if (ANDROID.equals(platform) && (low.startsWith("com.onesay.") || low.startsWith("com.bixbyui"))) return "助手自己不能加白名单";
        return null;
    }

    /** 加白名单前那一次确认卡上要写的风险说明（两端同一段话）。 */
    public static String riskText(String platform, String id, String name) {
        String who = (name == null || name.trim().length() == 0 ? "" : "「" + clip(name.trim(), 24) + "」") + "（" + normIdOrRaw(platform, id) + "）";
        return "把" + who + "加进应用白名单后：在这个应用里，除了付款和发送隐私资料（身份证、银行卡、住址、真名、手机号、邮箱等，文字或图片），"
                + "助手的操作不再逐步问你，包括发送、删除、登录。认不准的写法、付款最后一下、给自己授权等硬规则照旧。"
                + "白名单会同步到配对的另一台设备，随时可以删除。确定要加吗？";
    }

    static String normIdOrRaw(String platform, String id) {
        String n = normId(platform, id);
        return n != null ? n : clip(id == null ? "" : id, 40);
    }

    static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    /** 显示名清理：去控制字符 / 换行，最长 {@link #MAX_NAME}。 */
    static String cleanName(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < MAX_NAME; i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f || (c >= 0x200b && c <= 0x200f) || (c >= 0x202a && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069)) continue;
            b.append(c);
        }
        return b.toString().trim();
    }

    // ================================================================== 状态

    private final Map<String, Entry> entries = new TreeMap<String, Entry>();

    public synchronized List<Entry> all() { return new ArrayList<Entry>(entries.values()); }

    public synchronized List<Entry> live() {
        List<Entry> out = new ArrayList<Entry>();
        for (Entry e : entries.values()) if (!e.deleted) out.add(e);
        return out;
    }

    public synchronized List<Entry> live(String platform) {
        List<Entry> out = new ArrayList<Entry>();
        for (Entry e : entries.values()) if (!e.deleted && e.platform.equals(platform)) out.add(e);
        return out;
    }

    public synchronized boolean contains(String platform, String id) {
        String n = normId(platform, id);
        if (n == null) return false;
        Entry e = entries.get(key(platform, n));
        return e != null && !e.deleted && refuseReason(platform, n) == null && confirmedHere(e);
    }

    /** 本机确认过的条目：键 → 确认时那一条的 added_at（只在本机，不同步）。 */
    private final Map<String, Long> confirmed = new TreeMap<String, Long>();

    private boolean confirmedHere(Entry e) {
        Long c = confirmed.get(e.key());
        return c != null && c.longValue() == e.addedAt;
    }

    /** 这一条在本机确认过了吗（活条目、本机加的或本机点过「确认生效」）。 */
    public synchronized boolean isConfirmed(String platform, String id) {
        Entry e = get(platform, id);
        return e != null && !e.deleted && confirmedHere(e);
    }

    /** 另一端同步来、本机还没确认的活条目（界面列出来让用户「确认生效」或删除）。 */
    public synchronized List<Entry> pending() {
        List<Entry> out = new ArrayList<Entry>();
        for (Entry e : entries.values()) if (!e.deleted && !confirmedHere(e) && refuseReason(e.platform, e.id) == null) out.add(e);
        return out;
    }

    /** 本机用户看过风险说明、点了「确认生效」（调用方保证是真人在本机界面上点的）。返回 true = 原来待确认、现在生效。 */
    public synchronized boolean confirm(String platform, String id) {
        Entry e = get(platform, id);
        if (e == null || e.deleted || refuseReason(e.platform, e.id) != null || confirmedHere(e)) return false;
        confirmed.put(e.key(), Long.valueOf(e.addedAt));
        return true;
    }

    public synchronized Entry get(String platform, String id) {
        String n = normId(platform, id);
        return n == null ? null : entries.get(key(platform, n));
    }

    /** 时钟往回跳时也保证新写入比旧的大。 */
    private long stamp(String k, long now) {
        Entry old = entries.get(k);
        return old != null && old.updatedAt >= now ? old.updatedAt + 1 : now;
    }

    /** 本机加一条（调用方必须已经弹过风险确认并得到真人「确定」）。返回 null = 成功，否则原因。 */
    public synchronized String add(String platform, String id, String name, String device, String deviceKind, long now) {
        String why = refuseReason(platform, id);
        if (why != null) return why;
        String n = normId(platform, id);
        String k = key(platform, n);
        Entry old = entries.get(k);
        if (old == null || old.deleted) {
            if (live().size() >= MAX_LIVE) return "白名单最多 " + MAX_LIVE + " 个应用";
        }
        long t = stamp(k, now);
        Entry e = new Entry(platform, n, cleanName(name), old != null && !old.deleted ? old.addedAt : t, t, device, deviceKind, false);
        entries.put(k, e);
        confirmed.put(k, Long.valueOf(e.addedAt)); // 本机加的：加之前已在本机弹过风险确认，直接生效（也等于确认了对方同步来的同一条）
        trim();
        return null;
    }

    /** 本机删一条（写墓碑）。返回 true = 原来在白名单里。 */
    public synchronized boolean remove(String platform, String id, String device, String deviceKind, long now) {
        String n = normId(platform, id);
        if (n == null) return false;
        String k = key(platform, n);
        Entry old = entries.get(k);
        if (old == null || old.deleted) return false;
        long t = stamp(k, now);
        entries.put(k, new Entry(platform, n, old.name, old.addedAt, t, device, deviceKind, true));
        confirmed.remove(k);
        trim();
        return true;
    }

    /** 合并另一端发来的条目。返回对方带来的、本机原来没有的「新放行」条目（给用户提示用；这些在本机确认前是「待确认」、不放行）。 */
    public synchronized List<Entry> merge(List<Entry> theirs) {
        List<Entry> added = new ArrayList<Entry>();
        if (theirs == null) return added;
        for (Entry e : theirs) {
            if (e == null) continue;
            String n = normId(e.platform, e.id);
            if (n == null || refuseReason(e.platform, n) != null) continue;
            Entry in = new Entry(e.platform, n, cleanName(e.name), e.addedAt, e.updatedAt, e.origin, e.originKind, e.deleted);
            String k = in.key();
            Entry mine = entries.get(k);
            if (mine == null || Entry.beats(in, mine)) {
                boolean wasLive = mine != null && !mine.deleted;
                if (!in.deleted && !wasLive) {
                    if (live().size() >= MAX_LIVE) continue;
                    added.add(in);
                }
                entries.put(k, in);
                if (in.deleted) confirmed.remove(k);
            }
        }
        trim();
        return added;
    }

    private void trim() {
        if (entries.size() <= MAX_TOTAL) return;
        List<Entry> tomb = new ArrayList<Entry>();
        for (Entry e : entries.values()) if (e.deleted) tomb.add(e);
        Collections.sort(tomb, new java.util.Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) { return a.updatedAt < b.updatedAt ? -1 : a.updatedAt > b.updatedAt ? 1 : a.key().compareTo(b.key()); }
        });
        for (Entry e : tomb) {
            if (entries.size() <= MAX_TOTAL) break;
            entries.remove(e.key());
            confirmed.remove(e.key());
        }
    }

    // ================================================================== JSON

    /** 整份状态写成同步格式（条目按键排序，同样的状态写出来逐字相同）。 */
    public synchronized String toJson(String device) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("format", FORMAT);
        root.put("version", Integer.valueOf(VERSION));
        root.put("device", device == null ? "" : device);
        List<Object> list = new ArrayList<Object>();
        for (Entry e : entries.values()) list.add(e.toMap());
        root.put("entries", list);
        return J.write(root);
    }

    /** 本机存盘格式 = 同步格式 + 本机确认记录 confirmed_here（["键@added_at", …]）。确认记录只在本机，{@link #toJson} 不带。 */
    public synchronized String toLocalJson(String device) {
        String doc = toJson(device);
        List<Object> c = new ArrayList<Object>();
        for (Map.Entry<String, Long> e : confirmed.entrySet()) {
            Entry x = entries.get(e.getKey());
            if (x != null && !x.deleted && x.addedAt == e.getValue().longValue()) c.add(e.getKey() + "@" + e.getValue());
        }
        return doc.substring(0, doc.length() - 1) + ",\"confirmed_here\":" + J.write(c) + "}";
    }

    /** 读同步格式；格式名 / 版本不对抛 IllegalArgumentException（整份拒收），单条坏的丢掉。 */
    @SuppressWarnings("unchecked")
    public static List<Entry> parse(String json) {
        Object o = J.parse(json);
        if (!(o instanceof Map)) throw new IllegalArgumentException("不是 JSON 对象");
        Map<String, Object> m = (Map<String, Object>) o;
        if (!FORMAT.equals(m.get("format"))) throw new IllegalArgumentException("format 不对");
        Object v = m.get("version");
        if (!(v instanceof Number) || ((Number) v).intValue() < 1 || ((Number) v).intValue() > VERSION) throw new IllegalArgumentException("version 不支持");
        Object es = m.get("entries");
        if (!(es instanceof List)) throw new IllegalArgumentException("entries 不对");
        List<Entry> out = new ArrayList<Entry>();
        for (Object x : (List<Object>) es) {
            if (!(x instanceof Map)) continue;
            Map<String, Object> e = (Map<String, Object>) x;
            String p = str(e.get("platform"));
            String id = normId(p, str(e.get("id")));
            Object ua = e.get("updated_at"), aa = e.get("added_at");
            if (id == null || !(ua instanceof Number)) continue;
            long u = ((Number) ua).longValue();
            if (u < 0) continue;
            out.add(new Entry(p, id, cleanName(str(e.get("name"))), aa instanceof Number ? ((Number) aa).longValue() : u, u,
                    clip(str(e.get("origin")), 64), clip(str(e.get("origin_kind")), 16), Boolean.TRUE.equals(e.get("deleted"))));
        }
        return out;
    }

    /** 本机存盘的读法：坏文件 → 空白名单（宁可不放行，见 {@link ActionPolicy} 的「读不出就不放行」）。 */
    public static AppWhitelist load(String json) {
        AppWhitelist w = new AppWhitelist();
        if (json == null || json.trim().length() == 0) return w;
        try {
            w.merge(parse(json));
            Object c = ((Map<?, ?>) J.parse(json)).get("confirmed_here");
            if (c instanceof List) for (Object x : (List<?>) c) {
                String s = String.valueOf(x);
                int at = s.lastIndexOf('@');
                if (at <= 0) continue;
                Entry e = w.entries.get(s.substring(0, at));
                if (e != null && !e.deleted && String.valueOf(e.addedAt).equals(s.substring(at + 1))) w.confirmed.put(e.key(), Long.valueOf(e.addedAt));
            }
        } catch (RuntimeException e) {
            w.broken = true;
        }
        return w;
    }

    /** 存盘文件读坏了（{@link #load}）：这时白名单当空用，并且不会把坏状态同步出去。 */
    public volatile boolean broken;

    static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    // ================================================================== 小 JSON（只为同步格式；三端同一份，不依赖各自的 JSON 库）

    public static final class J {
        private final String s;
        private int i;

        private J(String s) { this.s = s; }

        public static Object parse(String text) {
            if (text == null) throw new IllegalArgumentException("空");
            if (text.length() > 2000000) throw new IllegalArgumentException("太大");
            J p = new J(text);
            p.ws();
            Object v = p.value(0);
            p.ws();
            if (p.i != p.s.length()) throw new IllegalArgumentException("多余内容 @" + p.i);
            return v;
        }

        private void ws() { while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++; }

        private IllegalArgumentException bad() { return new IllegalArgumentException("JSON 不对 @" + i); }

        private Object value(int depth) {
            if (depth > 32) throw bad();
            if (i >= s.length()) throw bad();
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                ws();
                if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws();
                    if (i >= s.length() || s.charAt(i) != '"') throw bad();
                    String k = string();
                    ws();
                    if (i >= s.length() || s.charAt(i) != ':') throw bad();
                    i++;
                    ws();
                    m.put(k, value(depth + 1));
                    ws();
                    if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                    if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
                    throw bad();
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<Object>();
                ws();
                if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    ws();
                    l.add(value(depth + 1));
                    ws();
                    if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                    if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
                    throw bad();
                }
            }
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            if (i < s.length() && s.charAt(i) == '-') i++;
            if (i >= s.length() || !Character.isDigit(s.charAt(i))) throw bad();
            if (s.charAt(i) == '0' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1))) throw bad();
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            boolean frac = false;
            if (i < s.length() && s.charAt(i) == '.') {
                frac = true;
                i++;
                if (i >= s.length() || !Character.isDigit(s.charAt(i))) throw bad();
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                frac = true;
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                if (i >= s.length() || !Character.isDigit(s.charAt(i))) throw bad();
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            String num = s.substring(st, i);
            if (!frac) {
                try { return Long.valueOf(num); } catch (NumberFormatException e) { throw bad(); }
            }
            double d = Double.parseDouble(num);
            if (Double.isInfinite(d) || Double.isNaN(d)) throw bad();
            return Double.valueOf(d);
        }

        private String string() {
            i++;
            StringBuilder b = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw bad();
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c < 0x20) throw bad();
                if (c != '\\') { b.append(c); continue; }
                if (i >= s.length()) throw bad();
                char e = s.charAt(i++);
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u': {
                        if (i + 4 > s.length()) throw bad();
                        int v = 0;
                        for (int k = 0; k < 4; k++) {
                            int h = Character.digit(s.charAt(i + k), 16);
                            if (h < 0) throw bad();
                            v = v * 16 + h;
                        }
                        i += 4;
                        b.append((char) v);
                        break;
                    }
                    default: throw bad();
                }
            }
        }

        @SuppressWarnings("unchecked")
        public static String write(Object o) {
            StringBuilder b = new StringBuilder();
            write(b, o);
            return b.toString();
        }

        @SuppressWarnings("unchecked")
        private static void write(StringBuilder b, Object o) {
            if (o == null) { b.append("null"); return; }
            if (o instanceof Boolean || o instanceof Long || o instanceof Integer) { b.append(o); return; }
            if (o instanceof Number) { b.append(o); return; }
            if (o instanceof Map) {
                b.append('{');
                boolean first = true;
                for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                    if (!first) b.append(',');
                    first = false;
                    quote(b, e.getKey());
                    b.append(':');
                    write(b, e.getValue());
                }
                b.append('}');
                return;
            }
            if (o instanceof List) {
                b.append('[');
                boolean first = true;
                for (Object x : (List<Object>) o) {
                    if (!first) b.append(',');
                    first = false;
                    write(b, x);
                }
                b.append(']');
                return;
            }
            quote(b, String.valueOf(o));
        }

        private static void quote(StringBuilder b, String s) {
            b.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': b.append("\\\""); break;
                    case '\\': b.append("\\\\"); break;
                    case '\n': b.append("\\n"); break;
                    case '\r': b.append("\\r"); break;
                    case '\t': b.append("\\t"); break;
                    default:
                        if (c < 0x20 || c == 0x2028 || c == 0x2029) b.append(String.format("\\u%04x", Integer.valueOf(c)));
                        else b.append(c);
                }
            }
            b.append('"');
        }
    }
}
