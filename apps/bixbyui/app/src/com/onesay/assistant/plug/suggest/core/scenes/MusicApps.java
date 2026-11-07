/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core.scenes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 场景 6 用的音乐 App 表（只用来认「哪些包算音乐 App」；推荐谁看用户最近 7 天的使用时长，不写死某一个 —— 咪咕的教训）。 */
public final class MusicApps {
    private MusicApps() {
    }

    public static final Map<String, String> KNOWN;

    static {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("com.netease.cloudmusic", "网易云音乐");
        m.put("com.tencent.qqmusic", "QQ音乐");
        m.put("com.kugou.android", "酷狗音乐");
        m.put("cn.kuwo.player", "酷我音乐");
        m.put("cmccwm.mobilemusic", "咪咕音乐");
        m.put("com.spotify.music", "Spotify");
        m.put("com.apple.android.music", "Apple Music");
        m.put("com.google.android.apps.youtube.music", "YouTube Music");
        m.put("com.ximalaya.ting.android", "喜马拉雅");
        m.put("com.luna.music", "汽水音乐");
        KNOWN = Collections.unmodifiableMap(m);
    }

    public static boolean isMusic(String pkg) {
        return pkg != null && KNOWN.containsKey(pkg);
    }

    public static final class Usage {
        public final String pkg, label;
        public final long ms;

        public Usage(String pkg, String label, long ms) {
            this.pkg = pkg;
            this.label = label;
            this.ms = ms;
        }
    }

    /** 「pkg|label|ms;…」→ 按使用时长降序，只留 ms &gt; 0 的已知音乐 App。 */
    public static List<Usage> rank(String encoded) {
        List<Usage> out = new ArrayList<Usage>();
        if (encoded == null) return out;
        for (String part : encoded.split(";")) {
            String[] f = part.split("\\|");
            if (f.length < 3 || !isMusic(f[0])) continue;
            long ms;
            try {
                ms = Long.parseLong(f[2].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (ms <= 0) continue;
            out.add(new Usage(f[0], f[1].length() > 0 ? f[1] : KNOWN.get(f[0]), ms));
        }
        Collections.sort(out, new Comparator<Usage>() {
            @Override public int compare(Usage a, Usage b) {
                return a.ms == b.ms ? 0 : (a.ms > b.ms ? -1 : 1);
            }
        });
        return out;
    }

    public static String encode(List<Usage> l) {
        StringBuilder b = new StringBuilder();
        for (Usage u : l) b.append(b.length() > 0 ? ";" : "").append(u.pkg).append('|').append(u.label == null ? "" : u.label.replace("|", " ").replace(";", " ")).append('|').append(u.ms);
        return b.toString();
    }
}
