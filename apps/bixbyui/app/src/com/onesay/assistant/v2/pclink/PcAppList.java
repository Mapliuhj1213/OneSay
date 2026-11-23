package com.onesay.assistant.v2.pclink;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * [rel-1123-b] 「看手机上装了哪些 App」（app_list）的纯 Java 部分：去重、按名字排、按关键字筛、限条数、写一句给人看的话。
 * Android 侧（{@code PcRxAndroid.appList}）只负责从桌面启动项里拿到「名字 + 包名」。
 */
public final class PcAppList {

    private PcAppList() {}

    /** 一个 App：桌面上的名字、包名。 */
    public static final class App {
        public final String name, pkg;

        public App(String name, String pkg) {
            this.name = name == null ? "" : name.trim();
            this.pkg = pkg == null ? "" : pkg.trim();
        }
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replace(" ", "").replace("\u00a0", "");
    }

    /** 拼成回给电脑的结果：data = {apps:[{name, package}], total, shown, truncated, query}。 */
    public static PcLanClient.Result view(List<App> all, String query, int max) {
        Map<String, App> byPkg = new LinkedHashMap<String, App>();
        for (App a : all) {
            if (a == null || a.pkg.length() == 0) continue;
            if (!byPkg.containsKey(a.pkg)) byPkg.put(a.pkg, a); // 一个 App 有几个桌面入口：只算一次
        }
        String q = norm(query);
        List<App> hit = new ArrayList<App>();
        for (App a : byPkg.values()) {
            if (q.length() == 0 || norm(a.name).contains(q) || norm(a.pkg).contains(q)) hit.add(a);
        }
        final Collator zh = Collator.getInstance(Locale.CHINA);
        Collections.sort(hit, new Comparator<App>() {
            @Override public int compare(App x, App y) {
                int c = zh.compare(x.name.length() == 0 ? x.pkg : x.name, y.name.length() == 0 ? y.pkg : y.name);
                return c != 0 ? c : x.pkg.compareTo(y.pkg);
            }
        });
        int total = hit.size();
        boolean truncated = total > max;
        List<Object> out = new ArrayList<Object>();
        for (int i = 0; i < Math.min(max, total); i++) {
            Map<String, Object> m = new TreeMap<String, Object>();
            m.put("name", hit.get(i).name.length() == 0 ? hit.get(i).pkg : hit.get(i).name);
            m.put("package", hit.get(i).pkg);
            out.add(m);
        }
        Map<String, Object> d = new TreeMap<String, Object>();
        d.put("apps", out);
        d.put("total", Long.valueOf(total));
        d.put("shown", Long.valueOf(out.size()));
        d.put("truncated", Boolean.valueOf(truncated));
        if (q.length() > 0) d.put("query", query.trim());
        String text;
        if (q.length() > 0) {
            if (total == 0) text = "手机上没找到名字带「" + query.trim() + "」的 App";
            else text = "手机上名字带「" + query.trim() + "」的 App 有 " + total + " 个：" + names(hit, 10);
        } else if (total == 0) {
            text = "手机上没读到能打开的 App";
        } else {
            text = "手机桌面上有 " + total + " 个 App" + (truncated ? "（只列了前 " + max + " 个）" : "") + "：" + names(hit, 15);
        }
        return new PcLanClient.Result(true, text, d, "");
    }

    static String names(List<App> l, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(n, l.size()); i++) {
            if (b.length() > 0) b.append("、");
            b.append(l.get(i).name.length() == 0 ? l.get(i).pkg : l.get(i).name);
        }
        if (l.size() > n) b.append(" 等");
        return b.toString();
    }
}
