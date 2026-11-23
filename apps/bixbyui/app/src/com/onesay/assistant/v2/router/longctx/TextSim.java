package com.onesay.assistant.v2.router.longctx;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * ★ fix/1123-longctx-phone：长对话组件共用的文字相似度（纯 Java，零依赖）。
 * 中文按二字组（bigram），英文 / 数字按整词；「内容二字组」先去掉虚词单字（的 了 吗 我 你 是…），用来判断「说的是不是同一件事」。
 */
public final class TextSim {
    private TextSim() {
    }

    /** 去掉的虚词 / 代词 / 语气字（只在「内容」比较时去）。 */
    static final String STOP = "的了吗呢吧啊呀哦嘛么是我你他她它们这那个一下有在和就都也还要会能得地着过来去把被给让对跟与及或很太更最又再才只啊呗哈嗯呃么吗啦咯喔哇什怎几多少哪谁为";

    public static String norm(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) b.append(Character.toLowerCase(c));
            else b.append(' ');
        }
        return b.toString();
    }

    static boolean cjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    /** 全部二字组（中文）+ 词（英文 / 数字）。 */
    public static Set<String> grams(String s) {
        return grams(s, false);
    }

    /** 内容二字组：先去虚词单字再切。 */
    public static Set<String> content(String s) {
        return grams(s, true);
    }

    static Set<String> grams(String s, boolean dropStop) {
        Set<String> out = new LinkedHashSet<String>();
        String n = norm(s);
        StringBuilder run = new StringBuilder();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i <= n.length(); i++) {
            char c = i < n.length() ? n.charAt(i) : ' ';
            if (cjk(c)) {
                flushWord(word, out);
                if (dropStop && STOP.indexOf(c) >= 0) {
                    flushRun(run, out);
                } else {
                    run.append(c);
                }
            } else {
                flushRun(run, out);
                if (c == ' ') flushWord(word, out);
                else word.append(c);
            }
        }
        return out;
    }

    private static void flushRun(StringBuilder run, Set<String> out) {
        if (run.length() == 1) out.add(run.toString());
        for (int i = 0; i + 1 < run.length(); i++) out.add(run.substring(i, i + 2));
        run.setLength(0);
    }

    private static void flushWord(StringBuilder w, Set<String> out) {
        if (w.length() > 0) out.add(w.toString());
        w.setLength(0);
    }

    /** a 有多少比例出现在 b 里（|a∩b| / |a|；a 空 = 0）。 */
    public static double containment(Set<String> a, Set<String> b) {
        if (a == null || a.isEmpty() || b == null || b.isEmpty()) return 0;
        int n = 0;
        for (String g : a) if (b.contains(g)) n++;
        return (double) n / a.size();
    }

    public static double jaccard(Set<String> a, Set<String> b) {
        if (a == null || a.isEmpty() || b == null || b.isEmpty()) return 0;
        int n = 0;
        for (String g : a) if (b.contains(g)) n++;
        return (double) n / (a.size() + b.size() - n);
    }

    public static String head(String s, int n) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() > n ? t.substring(0, n) + "…" : t;
    }
}
