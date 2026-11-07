/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core.scenes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 场景 2 的域名表（§7 #4）：复制购物 / 短视频 / 网盘链接 → 对应 App。口令类（淘口令 ￥…￥）直接拉起 App，由 App 自己读剪贴板（Android 8 允许）。
 * 未安装的不弹（宿主传已装包）。
 */
public final class LinkTable {
    private LinkTable() {
    }

    public static final class Entry {
        public final String key, label, pkg, pathPrefix;
        public final String[] hosts;

        Entry(String key, String label, String pkg, String pathPrefix, String... hosts) {
            this.key = key;
            this.label = label;
            this.pkg = pkg;
            this.pathPrefix = pathPrefix;
            this.hosts = hosts;
        }
    }

    public static final class Match {
        public final Entry entry;
        /** 可空（口令）。 */
        public final String url;
        public final boolean token;

        Match(Entry e, String url, boolean token) {
            this.entry = e;
            this.url = url;
            this.token = token;
        }
    }

    public static final List<Entry> ENTRIES;

    static {
        List<Entry> l = new ArrayList<Entry>();
        l.add(new Entry("taobao", "淘宝", "com.taobao.taobao", null, "m.tb.cn", "e.tb.cn", "tb.cn", "taobao.com", "tmall.com"));
        l.add(new Entry("jd", "京东", "com.jingdong.app.mall", null, "u.jd.com", "item.m.jd.com", "item.jd.com", "3.cn"));
        l.add(new Entry("pdd", "拼多多", "com.xunmeng.pinduoduo", null, "yangkeduo.com", "pinduoduo.com"));
        l.add(new Entry("douyin", "抖音", "com.ss.android.ugc.aweme", null, "v.douyin.com", "douyin.com", "iesdouyin.com"));
        l.add(new Entry("kuaishou", "快手", "com.smile.gifmaker", null, "v.kuaishou.com", "kuaishou.com"));
        l.add(new Entry("xhs", "小红书", "com.xingin.xhs", null, "xhslink.com", "xiaohongshu.com"));
        l.add(new Entry("baidupan", "百度网盘", "com.baidu.netdisk", "/s/", "pan.baidu.com"));
        l.add(new Entry("quark", "夸克", "com.quark.browser", "/s/", "pan.quark.cn"));
        ENTRIES = Collections.unmodifiableList(l);
    }

    private static final Pattern URL = Pattern.compile("(?i)(?:https?://)?((?:[a-z0-9-]+\\.)+[a-z]{2,})(/[^\\s，。！？、\"'<>【】（）()]*)?");
    /** 淘口令：成对的货币符号包住 8–16 位字母数字。 */
    private static final Pattern TOKEN = Pattern.compile("([￥¥€$₳₴₤])([A-Za-z0-9]{8,16})\\1");
    private static final Pattern TB_HINT = Pattern.compile("手机淘宝|淘宝|天猫|tb\\.cn");

    public static Set<String> allPackages() {
        Set<String> s = new LinkedHashSet<String>();
        for (Entry e : ENTRIES) s.add(e.pkg);
        return s;
    }

    public static Entry byKey(String key) {
        for (Entry e : ENTRIES) if (e.key.equals(key)) return e;
        return null;
    }

    /** 第一个命中的链接；没有链接但有淘口令 → 淘宝（token=true）。 */
    public static Match match(String text) {
        if (text == null || text.length() == 0) return null;
        Matcher m = URL.matcher(text);
        while (m.find()) {
            String host = m.group(1).toLowerCase(Locale.ROOT);
            String path = m.group(2) == null ? "" : m.group(2);
            Entry e = byHost(host, path);
            if (e != null) {
                String raw = m.group(0);
                String url = raw.toLowerCase(Locale.ROOT).startsWith("http") ? raw : "https://" + raw;
                return new Match(e, url, false);
            }
        }
        Matcher t = TOKEN.matcher(text);
        if (t.find() && TB_HINT.matcher(text).find()) return new Match(byKey("taobao"), null, true);
        return null;
    }

    static Entry byHost(String host, String path) {
        for (Entry e : ENTRIES) {
            for (String h : e.hosts) {
                if (host.equals(h) || host.endsWith("." + h)) {
                    if (e.pathPrefix == null || path.startsWith(e.pathPrefix)) return e;
                }
            }
        }
        return null;
    }
}
