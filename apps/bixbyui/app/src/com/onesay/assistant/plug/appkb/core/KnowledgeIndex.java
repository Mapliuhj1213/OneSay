/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: core/knowledge/FrontMatter.kt translated to Java 7; KnowledgeIndex / GuideCatalog reduced to a
 * name / alias / package / site matcher that picks at most N packs for the task start, plus a deep-link rule
 * (links of a known pack are allowed only after that pack was injected). Upstream: ebb0e44.
 */
package com.onesay.assistant.plug.appkb.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** APP.md / SITE.md 知识包：解析、按任务挑 ≤ N 份注入、深链放行规则。 */
public final class KnowledgeIndex {

    /** 一份知识。kind = app / site。deeplinks = 这个 App 能用的深链前缀（scheme://… 或 intent action）。 */
    public static final class Pack {
        public final String name, display, kind, description, body, open;
        public final List<String> packages, aliases, sites, deeplinks;

        Pack(Map<String, Object> fm, String body) {
            name = str(fm.get("name"));
            display = str(fm.get("display")).length() > 0 ? str(fm.get("display")) : name;
            kind = str(fm.get("kind")).length() > 0 ? str(fm.get("kind")) : "app";
            description = str(fm.get("description"));
            open = str(fm.get("open"));
            packages = list(fm.get("packages"));
            aliases = list(fm.get("aliases"));
            sites = list(fm.get("sites"));
            deeplinks = list(fm.get("deeplinks"));
            this.body = body == null ? "" : body;
        }

        /** 这段文字（模型给的参数）里有没有用到本包的 App 专属深链 scheme（xxx://，http/https、tel:、intent action 不算）。 */
        boolean usesLink(String text) {
            for (String d : deeplinks) {
                int k = d.indexOf("://");
                if (k <= 0) continue;
                String scheme = d.substring(0, k + 3).toLowerCase(Locale.ROOT);
                if (scheme.startsWith("http")) continue;
                if (text.toLowerCase(Locale.ROOT).contains(scheme)) return true;
            }
            return false;
        }
    }

    /** 开局注入的结果：给模型的知识块 + 放行的包。 */
    public static final class Injected {
        public final List<Pack> packs;
        public final String block;
        private final List<Pack> all;

        Injected(List<Pack> packs, String block, List<Pack> all) {
            this.packs = packs;
            this.block = block;
            this.all = all;
        }

        /** 深链放行：用到某个已知 App 的深链而那份知识没注入 → 不放行；不涉及已知深链 → 放行（原行为）。 */
        public boolean allowsDeepLink(String text) {
            if (text == null) return true;
            for (Pack p : all) if (p.usesLink(text) && !packs.contains(p)) return false;
            return true;
        }

        public List<String> names() {
            List<String> n = new ArrayList<String>();
            for (Pack p : packs) n.add(p.name);
            return n;
        }
    }

    private final List<Pack> packs = new ArrayList<Pack>();

    public KnowledgeIndex add(String markdown) {
        Object[] fm = FrontMatter.parse(markdown);
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) fm[0];
        if (str(m.get("name")).length() == 0) return this;
        packs.add(new Pack(m, (String) fm[1]));
        return this;
    }

    public List<Pack> packs() {
        return Collections.unmodifiableList(packs);
    }

    public Pack byName(String name) {
        for (Pack p : packs) if (p.name.equals(name)) return p;
        return null;
    }

    /** 相关度：显示名 / 别名出现在任务里 10 分（越长越高），包名 / 网站 8 分；没提到为 0。 */
    static int score(Pack p, String q) {
        int s = 0;
        List<String> names = new ArrayList<String>(p.aliases);
        names.add(p.display);
        names.add(p.name);
        for (String a : names) {
            String al = a.toLowerCase(Locale.ROOT);
            if (al.length() >= 2 && q.contains(al)) s = Math.max(s, 10 + Math.min(al.length(), 6));
        }
        for (String pk : p.packages) if (pk.length() > 0 && q.contains(pk.toLowerCase(Locale.ROOT))) s = Math.max(s, 8);
        for (String st : p.sites) if (st.length() > 0 && q.contains(st.toLowerCase(Locale.ROOT))) s = Math.max(s, 8);
        return s;
    }

    /** 按任务挑 ≤ max 份（只挑被点名的），拼成给模型的知识块（每份正文截到 perPackChars）。 */
    public Injected inject(String task, int max, int perPackChars) {
        final String q = task == null ? "" : task.toLowerCase(Locale.ROOT);
        final Map<Pack, Integer> sc = new LinkedHashMap<Pack, Integer>();
        for (Pack p : packs) {
            int s = score(p, q);
            if (s > 0) sc.put(p, s);
        }
        List<Pack> picked = new ArrayList<Pack>(sc.keySet());
        Collections.sort(picked, new Comparator<Pack>() {
            @Override public int compare(Pack a, Pack b) {
                return sc.get(b) - sc.get(a);
            }
        });
        if (picked.size() > max) picked = new ArrayList<Pack>(picked.subList(0, max));
        StringBuilder b = new StringBuilder();
        if (!picked.isEmpty()) {
            b.append("【App 知识（只作参考：界面以实际读到的为准；下面列的深链可以直接用，其余 App 的深链没给就别猜）】\n");
            for (Pack p : picked) {
                b.append("## ").append(p.display).append(p.packages.isEmpty() ? "" : "（" + join(p.packages) + "）").append('\n');
                if (p.description.length() > 0) b.append(p.description).append('\n');
                String body = p.body.length() > perPackChars ? p.body.substring(0, perPackChars) + "…" : p.body;
                b.append(body).append('\n');
                if (!p.deeplinks.isEmpty()) b.append("可用深链：").append(join(p.deeplinks)).append('\n');
            }
        }
        return new Injected(picked, b.toString().trim(), packs);
    }

    static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) {
            if (b.length() > 0) b.append("、");
            b.append(s);
        }
        return b.toString();
    }

    static String str(Object o) {
        return o instanceof String ? ((String) o).trim() : "";
    }

    @SuppressWarnings("unchecked")
    static List<String> list(Object o) {
        if (o instanceof List) return (List<String>) o;
        List<String> l = new ArrayList<String>();
        if (o instanceof String && ((String) o).length() > 0) l.add((String) o);
        return l;
    }
}
