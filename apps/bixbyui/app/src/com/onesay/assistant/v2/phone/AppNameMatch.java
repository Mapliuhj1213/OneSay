package com.onesay.assistant.v2.phone;

import com.onesay.assistant.v2.roles.KnownApps;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * [chat-1124] 「用户说的 App」和「要打开 / 前台的 App」对不对得上（纯 Java，JVM 可测）。
 *
 * <p>真机（魅族）：「打开美团拼好饭找到最近一张订单…」，实际进的是另一个购物 App，整轮在里面找订单，
 * 打开核对只看「前台是不是解析出的那个包」，包本身认错了就拦不住。现在：
 * <ol>
 *   <li>{@link #named}：从用户原话里认出点名的 App（常用名表 + 本机已装应用的名字，长的优先、不重叠）；</li>
 *   <li>{@link #matches}：一个 App（包名 + 名字）算不算用户说的那个——包名对上常用名表、或名字互相包含（至少两个字）；</li>
 *   <li>{@link #check}：要打开的 App 和用户点名的一个都对不上 → 一句大白话原因（调用方不打开）；系统自带的界面（设置等）不拦；</li>
 *   <li>{@link #driftNote}：做着做着前台变成了别的 App（开屏广告跳走、点错了）→ 给模型的一句提醒。</li>
 * </ol>
 */
public final class AppNameMatch {

    private AppNameMatch() {}

    /** 归一：小写、去空白和标点、去掉结尾的「app / 应用 / 客户端 / 软件」。 */
    public static String norm(String s) {
        if (s == null) return "";
        String t = s.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}，。？！、「」『』“”‘’（）《》：；·]", "");
        String cut = t.replaceAll("(?:app|应用|客户端|软件)$", "");
        return cut.length() >= 2 ? cut : t;
    }

    private static boolean overlap(String a, String b) {
        if (a.length() == 0 || b.length() == 0) return false;
        if (a.equals(b)) return true;
        return (a.length() >= 2 && b.contains(a)) || (b.length() >= 2 && a.contains(b));
    }

    /**
     * 一个 App 是不是用户说的 spoken。
     *
     * @param label 这个 App 的名字（读不到可 null）
     * @param pkg   包名（可 null）
     * @return spoken 为空 / 两边都没法判断 → true（核对不了不误拦）
     */
    public static boolean matches(String spoken, String label, String pkg) {
        String s = norm(spoken);
        if (s.length() == 0) return true;
        String l = norm(label);
        if (pkg != null && s.equals(pkg.trim().toLowerCase(Locale.ROOT))) return true;
        String known = KnownApps.pkgOf(spoken);
        if (known != null && pkg != null && known.equals(pkg)) return true;
        if (l.length() > 0) return overlap(s, l);
        // 名字读不到：只能靠常用名表；表里有且包名不同 = 对不上，表里没有 = 核对不了
        return known == null || pkg == null;
    }

    /** 系统自带、做任务时可能顺路要进的界面（设置、桌面、系统弹框…）：不按「点名的 App」拦。 */
    public static boolean systemPkg(String pkg, String selfPkg) {
        if (pkg == null || pkg.length() == 0) return true;
        if (selfPkg != null && pkg.equals(selfPkg)) return true;
        return pkg.equals("android") || pkg.startsWith("com.android.") || pkg.startsWith("com.google.android.permissioncontroller")
                || pkg.contains("launcher") || pkg.contains("systemui") || pkg.contains(".settings") || pkg.contains("packageinstaller")
                || pkg.contains("permission") || pkg.contains(".safe") || pkg.contains("securitycenter");
    }

    /**
     * 从用户原话里认出点名的 App 名（按出现顺序，长名字优先、不重叠）。
     *
     * @param installedLabels 本机已装应用的名字（可 null）；常用名表总是参与
     */
    public static List<String> named(String utterance, Collection<String> installedLabels) {
        List<String> out = new ArrayList<String>();
        if (utterance == null) return out;
        String u = utterance.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        if (u.length() == 0) return out;
        List<String> cands = new ArrayList<String>();
        for (String n : KnownApps.names().keySet()) if (n.length() >= 2) cands.add(n);
        if (installedLabels != null) {
            for (String l : installedLabels) {
                String n = l == null ? "" : l.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
                if (n.length() >= 2 && !cands.contains(n)) cands.add(n);
            }
        }
        Collections.sort(cands, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                return b.length() - a.length();
            }
        });
        boolean[] used = new boolean[u.length()];
        final Map<Integer, String> at = new java.util.TreeMap<Integer, String>();
        for (String c : cands) {
            int from = 0;
            while (true) {
                int i = u.indexOf(c, from);
                if (i < 0) break;
                boolean free = true;
                for (int k = i; k < i + c.length(); k++) if (used[k]) { free = false; break; }
                if (free) {
                    for (int k = i; k < i + c.length(); k++) used[k] = true;
                    at.put(i, c);
                }
                from = i + 1;
            }
        }
        for (String n : at.values()) if (!out.contains(n)) out.add(n);
        return out;
    }

    /**
     * 要打开 pkg（名字 label）之前核对：用户点名了 App、这个包一个都对不上（又不是系统界面）→ 原因；否则 null（照常打开）。
     */
    public static String check(List<String> named, String pkg, String label, String selfPkg) {
        if (named == null || named.isEmpty() || pkg == null || pkg.length() == 0) return null;
        if (systemPkg(pkg, selfPkg)) return null;
        for (String n : named) if (matches(n, label, pkg)) return null;
        String said = named.get(0);
        String what = label != null && label.trim().length() > 0 ? "「" + label.trim() + "」" : "另一个应用";
        return "你说的是「" + said + "」，可我要打开的是" + what + "，两个对不上，这次先不打开它。";
    }

    /** 前台 App 对不上点名的 App 时给模型的提醒；对得上 / 系统界面 / 没点名 → null。 */
    public static String driftNote(List<String> named, String fgPkg, String fgLabel, String selfPkg, Map<String, String> namedPkgs) {
        if (named == null || named.isEmpty() || fgPkg == null || fgPkg.length() == 0) return null;
        if (systemPkg(fgPkg, selfPkg)) return null;
        for (String n : named) if (matches(n, fgLabel, fgPkg)) return null;
        String said = named.get(0);
        String target = namedPkgs != null ? namedPkgs.get(said) : null;
        if (target == null) target = KnownApps.pkgOf(said);
        String fg = fgLabel != null && fgLabel.trim().length() > 0 ? "「" + fgLabel.trim() + "」" : fgPkg;
        return "注意：现在前台是 " + fg + "，不是用户说的「" + said + "」。别在这个 App 里接着点，先用 launch 打开「" + said + "」"
                + (target != null ? "（包名 " + target + "）" : "") + "，确认到了再接着做；打不开就如实说。";
    }

    /** 用户点名的 App → 包名（本机名字表优先，再查常用名表；查不到不放）。 */
    public static Map<String, String> namedPkgs(List<String> named, Map<String, String> labelToPkg) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (named == null) return m;
        for (String n : named) {
            String p = labelToPkg != null ? labelToPkg.get(norm(n)) : null;
            if (p == null && labelToPkg != null) p = labelToPkg.get(n);
            if (p == null) p = KnownApps.pkgOf(n);
            if (p != null) m.put(n, p);
        }
        return m;
    }

    /**
     * 名字 → 包名（不是包名的 app 参数用）：精确名字 / 常用名表优先；模糊只认「名字包含说法、说法至少占一半」里最短的那个。
     * 都不行 → null（调用方不打开，说没找到）。
     */
    public static String resolveLabel(String spoken, Map<String, String> labelToPkg) {
        String s = norm(spoken);
        if (s.length() == 0 || labelToPkg == null) return null;
        String p = labelToPkg.get(s);
        if (p != null) return p;
        String known = KnownApps.pkgOf(spoken);
        if (known != null && labelToPkg.containsValue(known)) return known;
        String best = null;
        int bestLen = Integer.MAX_VALUE;
        for (Map.Entry<String, String> e : labelToPkg.entrySet()) {
            String l = norm(e.getKey());
            if (s.length() >= 2 && l.contains(s) && s.length() * 2 >= l.length() && l.length() < bestLen) {
                best = e.getValue();
                bestLen = l.length();
            }
        }
        return best;
    }

    /** 像不像包名（a.b.c）。 */
    public static boolean looksLikePkg(String s) {
        return s != null && s.trim().matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+");
    }

    /**
     * PhoneAgent 用的核对器：用户点名的 App + 本机名字表（归一名字 → 包名）。
     */
    public static final class Guard implements PhoneAgent.AppGuard {
        final List<String> named;
        final Map<String, String> labelToPkg;
        final Map<String, String> pkgToLabel = new java.util.HashMap<String, String>();
        final Map<String, String> namedPkgs;
        final String selfPkg;

        public Guard(String userSaid, Map<String, String> labelToPkg, String selfPkg) {
            this.labelToPkg = labelToPkg == null ? new java.util.HashMap<String, String>() : labelToPkg;
            for (Map.Entry<String, String> e : this.labelToPkg.entrySet()) {
                if (!pkgToLabel.containsKey(e.getValue())) pkgToLabel.put(e.getValue(), e.getKey());
            }
            this.named = AppNameMatch.named(userSaid, this.labelToPkg.keySet());
            this.namedPkgs = AppNameMatch.namedPkgs(named, this.labelToPkg);
            this.selfPkg = selfPkg;
        }

        public List<String> named() {
            return named;
        }

        String label(String pkg) {
            String l = pkgToLabel.get(pkg);
            if (l != null) return l;
            for (Map.Entry<String, String> e : KnownApps.names().entrySet()) if (e.getValue().equals(pkg)) return e.getKey();
            return null;
        }

        @Override public String resolve(String app) {
            if (app == null || app.trim().length() == 0) return null;
            if (looksLikePkg(app)) return app.trim();
            return resolveLabel(app, labelToPkg);
        }

        @Override public String beforeLaunch(String pkg) {
            return check(named, pkg, label(pkg), selfPkg);
        }

        @Override public String drift(String fgPkg) {
            return driftNote(named, fgPkg, label(fgPkg), selfPkg, namedPkgs);
        }
    }
}
