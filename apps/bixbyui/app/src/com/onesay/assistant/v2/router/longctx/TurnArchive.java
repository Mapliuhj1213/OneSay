package com.onesay.assistant.v2.router.longctx;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ★ fix/1123-longctx-phone 第 4 条：会话外的原文存档 —— 每一轮的原话和<b>完整</b>答复（含重活过程原文）都存这里，
 * 主对话上下文里只留状态摘要 / 精简回执；需要时按这一句话检索调出原文（{@link #search}）。
 *
 * <ul>
 *   <li>检索只在<b>同一条接力链</b>（chain）里做：用户关面板 / 说「新对话」后开的新会话，不会把上一段的原文调回来（「下次唤醒像第一次」）。</li>
 *   <li>可选落盘（{@link #setFile}，手机上是应用私有目录 conv_archive.jsonl）：留原文可查；含密码 / 验证码 / 证件 / 卡号的条目不落盘。
 *       落盘只追加，超过 2×{@link #MAX_ENTRIES} 行时重写成最近 {@link #MAX_ENTRIES} 条。</li>
 * </ul>
 * 线程安全（方法加锁）。
 */
public final class TurnArchive {

    public static final int MAX_ENTRIES = 500;
    /** 单条存档上限（字）；超长保留头尾。 */
    public static final int MAX_CHARS = 20000;

    public static final class Entry {
        public final String chain;
        public final int id;
        public final String question, answer;
        public final boolean tainted;
        public final long at;

        Entry(String chain, int id, String q, String a, boolean tainted, long at) {
            this.chain = chain;
            this.id = id;
            this.question = q;
            this.answer = a;
            this.tainted = tainted;
            this.at = at;
        }
    }

    public static final class Hit {
        public final Entry entry;
        public final double score;

        Hit(Entry e, double s) {
            entry = e;
            score = s;
        }
    }

    static final Pattern SENSITIVE = FactNotes.SENSITIVE;

    private final List<Entry> entries = new ArrayList<Entry>();
    private File file;
    private int fileLines;

    public synchronized void setFile(File f) {
        file = f;
        fileLines = 0;
        if (f == null || !f.exists()) return;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                while (r.readLine() != null) fileLines++;
            } finally {
                r.close();
            }
        } catch (Throwable ignored) {
        }
    }

    public synchronized Entry add(String chain, int id, String q, String a, boolean tainted, long at) {
        Entry e = new Entry(chain, id, clip(q), clip(a), tainted, at);
        entries.add(e);
        while (entries.size() > MAX_ENTRIES) entries.remove(0);
        persist(e);
        return e;
    }

    static String clip(String s) {
        if (s == null) return "";
        if (s.length() <= MAX_CHARS) return s;
        int tail = MAX_CHARS / 3;
        return s.substring(0, MAX_CHARS - tail - 1) + "…" + s.substring(s.length() - tail);
    }

    private void persist(Entry e) {
        File f = file;
        if (f == null) return;
        try {
            if (SENSITIVE.matcher(e.question).find() || SENSITIVE.matcher(e.answer).find()) return;
            if (fileLines >= 2 * MAX_ENTRIES) rewrite(f);
            Writer w = new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8");
            try {
                w.write(line(e));
                w.write('\n');
            } finally {
                w.close();
            }
            fileLines++;
        } catch (Throwable ignored) {
        }
    }

    private void rewrite(File f) throws Exception {
        List<Entry> keep = new ArrayList<Entry>(entries);
        Writer w = new OutputStreamWriter(new FileOutputStream(f, false), "UTF-8");
        int n = 0;
        try {
            for (Entry e : keep) {
                if (SENSITIVE.matcher(e.question).find() || SENSITIVE.matcher(e.answer).find()) continue;
                w.write(line(e));
                w.write('\n');
                n++;
            }
        } finally {
            w.close();
        }
        fileLines = n;
    }

    static String line(Entry e) throws Exception {
        JSONObject o = new JSONObject();
        o.put("chain", e.chain);
        o.put("id", e.id);
        o.put("at", e.at);
        o.put("q", e.question);
        o.put("a", e.answer);
        if (e.tainted) o.put("tainted", true);
        return o.toString();
    }

    /**
     * 按这一句话找存档原文：只看 chain 这条接力链、跳过 exclude 里的（逐字还在历史里的）。
     * 内容二字组覆盖率 ≥ minScore 的按分数取前 k 条（同分取新的）。
     */
    public synchronized List<Hit> search(String chain, String query, Set<Integer> exclude, int k, double minScore) {
        List<Hit> hits = new ArrayList<Hit>();
        Set<String> qg = TextSim.content(query);
        if (qg.isEmpty() || chain == null) return hits;
        for (Entry e : entries) {
            if (!chain.equals(e.chain)) continue;
            if (exclude != null && exclude.contains(e.id)) continue;
            double s = TextSim.containment(qg, TextSim.grams(e.question + "\n" + e.answer));
            if (s >= minScore) hits.add(new Hit(e, s));
        }
        Collections.sort(hits, new Comparator<Hit>() {
            @Override public int compare(Hit a, Hit b) {
                int c = Double.compare(b.score, a.score);
                return c != 0 ? c : (b.entry.id - a.entry.id);
            }
        });
        return hits.size() > k ? new ArrayList<Hit>(hits.subList(0, k)) : hits;
    }

    public synchronized Entry get(String chain, int id) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (e.id == id && (chain == null || chain.equals(e.chain))) return e;
        }
        return null;
    }

    public synchronized int size() {
        return entries.size();
    }

    /** 测试 / 控制台「清空存档」用。 */
    public synchronized void wipe() {
        entries.clear();
        File f = file;
        if (f != null && f.exists()) {
            // noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        fileLines = 0;
    }
}
