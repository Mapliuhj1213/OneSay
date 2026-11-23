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
 *   <li>★ 2026-11-23 用户定：存档就是单纯的聊天记录，和长期记忆分开。
 *       {@link #MAX_AGE_MS 30 天}前的条目自动删（{@link #setFile 启动}和每次{@link #add 写入}时按条目的<b>墙上时间</b>清，
 *       不用单调时钟 —— 关机重启后单调时钟会归零）；{@link #wipe} = 设置里 / 语音「清空聊天存档」确认后删光，不碰长期记忆。</li>
 * </ul>
 * 线程安全（方法加锁）。
 */
public final class TurnArchive {

    public static final int MAX_ENTRIES = 500;
    /** 单条存档上限（字）；超长保留头尾。 */
    public static final int MAX_CHARS = 20000;
    /** 存档保留天数：条目写入时刻（墙上时间）早于「现在 − 30 天」就删。 */
    public static final long MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000;

    /** 墙上时钟（毫秒）；测试可换。 */
    public interface WallClock { long now(); }

    static final WallClock SYSTEM_WALL = new WallClock() {
        @Override public long now() { return System.currentTimeMillis(); }
    };

    public static final class Entry {
        public final String chain;
        public final int id;
        public final String question, answer;
        public final boolean tainted;
        public final long at;
        /** 写入时的墙上时间（毫秒），30 天清理按这个算。 */
        public final long wall;

        Entry(String chain, int id, String q, String a, boolean tainted, long at, long wall) {
            this.chain = chain;
            this.id = id;
            this.question = q;
            this.answer = a;
            this.tainted = tainted;
            this.at = at;
            this.wall = wall;
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
    /** 文件里最早一条的墙上时间（Long.MAX_VALUE = 文件空 / 没有带时间的行）；早于 30 天线就重写文件。 */
    private long fileOldest = Long.MAX_VALUE;
    private WallClock wallClock = SYSTEM_WALL;

    /** 测试用：换墙上时钟。 */
    public synchronized void setWallClock(WallClock c) {
        wallClock = c == null ? SYSTEM_WALL : c;
    }

    /** 启动时调：接上落盘文件，顺手把 30 天前的条目和超出 {@link #MAX_ENTRIES} 的旧条目从文件里删掉。 */
    public synchronized void setFile(File f) {
        file = f;
        fileLines = 0;
        fileOldest = Long.MAX_VALUE;
        if (f == null || !f.exists()) return;
        try {
            pruneFile(f, true);
        } catch (Throwable ignored) {
        }
    }

    public synchronized Entry add(String chain, int id, String q, String a, boolean tainted, long at) {
        long now = wallClock.now();
        Entry e = new Entry(chain, id, clip(q), clip(a), tainted, at, now);
        entries.add(e);
        pruneMemory(now);
        while (entries.size() > MAX_ENTRIES) entries.remove(0);
        persist(e, now);
        return e;
    }

    /** 内存里删 30 天前的（按写入顺序，最早的在前）。 */
    private void pruneMemory(long now) {
        long cut = now - MAX_AGE_MS;
        while (!entries.isEmpty() && entries.get(0).wall < cut) entries.remove(0);
    }

    /** 测试 / memdump：现在落盘文件里有几行。 */
    public synchronized int fileLines() {
        return fileLines;
    }

    static String clip(String s) {
        if (s == null) return "";
        if (s.length() <= MAX_CHARS) return s;
        int tail = MAX_CHARS / 3;
        return s.substring(0, MAX_CHARS - tail - 1) + "…" + s.substring(s.length() - tail);
    }

    private void persist(Entry e, long now) {
        File f = file;
        if (f == null) return;
        try {
            if (fileOldest < now - MAX_AGE_MS || fileLines >= 2 * MAX_ENTRIES) pruneFile(f, false);
            if (SENSITIVE.matcher(e.question).find() || SENSITIVE.matcher(e.answer).find()) return;
            Writer w = new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8");
            try {
                w.write(line(e));
                w.write('\n');
            } finally {
                w.close();
            }
            fileLines++;
            if (e.wall < fileOldest) fileOldest = e.wall;
        } catch (Throwable ignored) {
        }
    }

    /**
     * 按文件里的行清理（不靠内存 —— 重启后内存是空的，旧做法按内存重写会把上次进程的条目一起丢掉）：
     * 删 30 天前的、删读不出的坏行，只留最近 {@link #MAX_ENTRIES} 条。没有墙上时间（"t"）的行是本分支早期格式，算不出天数，先留着，靠 500 条上限挤掉。
     * onlyIfChanged = 启动时用：没有要删的就不重写文件。
     */
    private void pruneFile(File f, boolean onlyIfChanged) throws Exception {
        long cut = wallClock.now() - MAX_AGE_MS;
        List<String> keep = new ArrayList<String>();
        List<Long> times = new ArrayList<Long>();
        int total = 0;
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            String s;
            while ((s = r.readLine()) != null) {
                total++;
                if (s.trim().length() == 0) continue;
                long t;
                try {
                    t = new JSONObject(s).optLong("t", -1L);
                } catch (Throwable bad) {
                    continue;
                }
                if (t >= 0 && t < cut) continue;
                keep.add(s);
                times.add(t);
            }
        } finally {
            r.close();
        }
        int from = Math.max(0, keep.size() - MAX_ENTRIES);
        long oldest = Long.MAX_VALUE;
        for (int i = from; i < times.size(); i++) {
            long t = times.get(i);
            if (t >= 0 && t < oldest) oldest = t;
        }
        fileOldest = oldest;
        if (onlyIfChanged && from == 0 && keep.size() == total) {
            fileLines = total;
            return;
        }
        Writer w = new OutputStreamWriter(new FileOutputStream(f, false), "UTF-8");
        try {
            for (int i = from; i < keep.size(); i++) {
                w.write(keep.get(i));
                w.write('\n');
            }
        } finally {
            w.close();
        }
        fileLines = keep.size() - from;
    }

    static String line(Entry e) throws Exception {
        JSONObject o = new JSONObject();
        o.put("chain", e.chain);
        o.put("id", e.id);
        o.put("at", e.at);
        o.put("t", e.wall);
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

    /**
     * 「清空聊天存档」（设置按钮 / 语音，都先确认）：删光内存里和落盘文件里的存档。只动存档，长期记忆、本段会话的账本和事实都不碰。
     * @return 删掉了多少条（内存条数和文件行数取大的；只用来回话）
     */
    public synchronized int wipe() {
        int n = Math.max(entries.size(), fileLines);
        entries.clear();
        File f = file;
        if (f != null && f.exists()) {
            // noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        fileLines = 0;
        fileOldest = Long.MAX_VALUE;
        return n;
    }
}
