package com.onesay.assistant.v2.roles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * [roles] 候选 App 推导（纯 Java，JVM 可测）：<b>候选来自设备现有能力，种子只是提示</b>。
 *
 * <ol>
 *   <li>标准 intent 解析：角色有探针（导航 geo: / 邮件 mailto: / 浏览器 http: / 音乐 APP_MUSIC）→
 *       {@code queryIntentActivities} 能处理它的包全部是候选（种子里没有的也算，标签取系统标签）；</li>
 *   <li>挖掘接口：角色声明了 {@code mined_keywords} → 挖掘接口文本含这些词、且包已安装的也算候选；
 *       种子包在挖掘接口里出现的次数记为证据；</li>
 *   <li>种子：已安装 → 候选（来源 installed）；未安装 → 仍保留（供「未安装，点此安装」与选择芯片置灰）。</li>
 * </ol>
 * 排序：已安装在前；同为已安装时种子按 JSON 顺序在前，推导出的非种子在后；未安装种子最后。
 * {@code snap == null}（设备现状未知）时只返回种子，全部视为已安装（= 旧 S2 行为）。
 */
public final class CandidateDeriver {

    private CandidateDeriver() {}

    public static List<RoleCandidate> derive(RoleCatalog.Role role, DeviceSnapshot snap) {
        List<RoleCandidate> out = new ArrayList<RoleCandidate>();
        if (role == null) return out;
        if (snap == null) {
            for (RoleCatalog.Seed s : role.seeds) {
                Set<String> src = new LinkedHashSet<String>();
                src.add(RoleCandidate.SRC_SEED);
                out.add(fromSeed(role, s, true, src, 0, null));
            }
            return out;
        }
        // 证据收集：pkg → 来源
        Map<String, Set<String>> evidence = new LinkedHashMap<String, Set<String>>();
        if (role.probe != null) {
            List<String> pk = safe(snap.resolve(role.probe));
            for (String p : pk) add(evidence, p, RoleCandidate.SRC_INTENT);
        }
        List<DeviceSnapshot.MinedIface> mined = safeM(snap.mined());
        Map<String, Integer> minedCount = new LinkedHashMap<String, Integer>();
        for (DeviceSnapshot.MinedIface m : mined) {
            if (m.pkg == null) continue;
            Integer c = minedCount.get(m.pkg);
            minedCount.put(m.pkg, Integer.valueOf(c == null ? 1 : c.intValue() + 1));
            for (String kw : role.minedKeywords) {
                if (m.text.contains(kw)) {
                    add(evidence, m.pkg, RoleCandidate.SRC_MINED);
                    break;
                }
            }
        }
        List<RoleCandidate> installedSeeds = new ArrayList<RoleCandidate>();
        List<RoleCandidate> missingSeeds = new ArrayList<RoleCandidate>();
        Set<String> seen = new LinkedHashSet<String>();
        for (RoleCatalog.Seed s : role.seeds) {
            if (!seen.add(s.pkg)) continue;
            boolean inst = snap.isInstalled(s.pkg);
            Set<String> src = new LinkedHashSet<String>();
            src.add(RoleCandidate.SRC_SEED);
            if (inst) src.add(RoleCandidate.SRC_INSTALLED);
            Set<String> ev = evidence.get(s.pkg);
            if (ev != null && inst) src.addAll(ev);
            Integer mc = minedCount.get(s.pkg);
            String handoff = s.handoffSign != null && inst && snap.hasCapability(s.handoffSign) ? s.handoffSign : null;
            RoleCandidate c = fromSeed(role, s, inst, src, mc == null ? 0 : mc.intValue(), handoff);
            (inst ? installedSeeds : missingSeeds).add(c);
        }
        List<RoleCandidate> derived = new ArrayList<RoleCandidate>();
        for (Map.Entry<String, Set<String>> e : evidence.entrySet()) {
            String pkg = e.getKey();
            if (seen.contains(pkg) || !RoleCatalog.PKG.matcher(pkg).matches()) continue;
            if (!snap.isInstalled(pkg)) continue;
            seen.add(pkg);
            String label = snap.label(pkg);
            if (label == null || label.trim().length() == 0) label = pkg;
            Set<String> src = new LinkedHashSet<String>();
            src.add(RoleCandidate.SRC_INSTALLED);
            src.addAll(e.getValue());
            Integer mc = minedCount.get(pkg);
            String lab = label.trim();
            derived.add(new RoleCandidate(pkg, pkg, lab, lab, new String[]{lab.toLowerCase(Locale.ROOT).replaceAll("[\\s，,。·（）()]", "")},
                    role.destSign, null, true, src, mc == null ? 0 : mc.intValue()));
        }
        out.addAll(installedSeeds);
        out.addAll(derived);
        out.addAll(missingSeeds);
        return out;
    }

    private static RoleCandidate fromSeed(RoleCatalog.Role role, RoleCatalog.Seed s, boolean installed, Set<String> src,
                                          int minedCount, String handoff) {
        return new RoleCandidate(s.id, s.pkg, s.label, s.launchName, s.aliases,
                s.destSign != null ? s.destSign : role.destSign, handoff, installed, src, minedCount);
    }

    private static void add(Map<String, Set<String>> m, String pkg, String src) {
        if (pkg == null) return;
        Set<String> s = m.get(pkg);
        if (s == null) m.put(pkg, s = new LinkedHashSet<String>());
        s.add(src);
    }

    private static List<String> safe(List<String> l) {
        return l == null ? new ArrayList<String>() : l;
    }

    private static List<DeviceSnapshot.MinedIface> safeM(List<DeviceSnapshot.MinedIface> l) {
        return l == null ? new ArrayList<DeviceSnapshot.MinedIface>() : l;
    }

    public static RoleCandidate find(List<RoleCandidate> l, String idOrPkg) {
        if (l == null || idOrPkg == null || idOrPkg.length() == 0) return null;
        for (RoleCandidate c : l) if (c.id.equals(idOrPkg)) return c;
        for (RoleCandidate c : l) if (c.pkg.equals(idOrPkg)) return c;
        return null;
    }

    /** 句子里点名的候选（最长别名优先，含非种子候选的系统标签）。 */
    public static RoleCandidate named(String key, List<RoleCandidate> l) {
        if (key == null || l == null) return null;
        RoleCandidate best = null;
        int bestLen = 0;
        for (RoleCandidate c : l) {
            for (String al : c.aliases) {
                if (al.length() >= 2 && al.length() > bestLen && key.contains(al)) {
                    best = c;
                    bestLen = al.length();
                }
            }
        }
        return best;
    }

    public static List<RoleCandidate> installed(List<RoleCandidate> l) {
        List<RoleCandidate> o = new ArrayList<RoleCandidate>();
        if (l != null) for (RoleCandidate c : l) if (c.installed) o.add(c);
        return o;
    }
}
