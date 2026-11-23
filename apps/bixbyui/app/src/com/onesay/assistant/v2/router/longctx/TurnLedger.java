package com.onesay.assistant.v2.router.longctx;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ fix/1123-longctx-phone 第 3 条：每条用户消息记账 —— 待答 / 在做 / 已结。
 *
 * <ul>
 *   <li>{@link #open}：一条用户消息进来（路由入口调）→ 新条目「在做」；之前还在做的旧条目降为「待答」（被新消息打断 / 还在后台跑）。</li>
 *   <li>{@link #close}：这条有了终稿（ConversationMemory.record）→「已结」，记下一句结论。找不到对应条目（不走路由的识屏 / 圈选等）就直接记一条已结的。</li>
 * </ul>
 * 编号 # 从 1 起、整条接力链（自动开的新会话）连续编号；只给模型看，不上屏。非线程安全，由 ConversationMemory 加锁调用。
 */
public final class TurnLedger {

    public enum Status {
        PENDING("待答"), DOING("在做"), DONE("已结");
        public final String label;

        Status(String l) {
            label = l;
        }
    }

    public static final class Entry {
        public final int id;
        public final String text;
        public Status status;
        /** 已结时的一句结论（≤120 字）。 */
        public String conclusion = "";
        public boolean tainted;
        /** 这条的原话 / 答复是否已被折叠出逐字历史（只剩状态摘要 + 存档）。 */
        public boolean folded;

        Entry(int id, String text, Status s) {
            this.id = id;
            this.text = text;
            this.status = s;
        }
    }

    /** 账本最多留多少条（更早的只在存档里）。 */
    public static final int MAX_ENTRIES = 400;
    public static final int CONCLUSION_CHARS = 120;

    private final List<Entry> entries = new ArrayList<Entry>();
    private int nextId = 1;

    public Entry open(String text) {
        String t = text == null ? "" : text.trim();
        for (Entry e : entries) if (e.status == Status.DOING) e.status = Status.PENDING;
        Entry e = new Entry(nextId++, t, Status.DOING);
        entries.add(e);
        trim();
        return e;
    }

    /** 记终稿：优先对上最近一条同原话的未结条目，其次最近一条「在做」的（原话被改写过），都没有就新记一条已结的。 */
    public Entry close(String text, String answer, boolean tainted) {
        String t = text == null ? "" : text.trim();
        Entry hit = null;
        for (int i = entries.size() - 1; i >= 0 && hit == null; i--) {
            Entry e = entries.get(i);
            if (e.status != Status.DONE && e.text.equals(t)) hit = e;
        }
        if (hit == null) {
            Entry last = entries.isEmpty() ? null : entries.get(entries.size() - 1);
            if (last != null && last.status == Status.DOING && looselySame(last.text, t)) hit = last;
        }
        if (hit == null) {
            hit = new Entry(nextId++, t, Status.DONE);
            entries.add(hit);
        }
        hit.status = Status.DONE;
        hit.conclusion = TextSim.head(conclusionOf(answer), CONCLUSION_CHARS);
        hit.tainted |= tainted;
        trim();
        return hit;
    }

    static boolean looselySame(String a, String b) {
        if (a.length() == 0 || b.length() == 0) return false;
        if (a.contains(b) || b.contains(a)) return true;
        return TextSim.jaccard(TextSim.grams(a), TextSim.grams(b)) >= 0.5;
    }

    /** Agent 回执的「结论：」之后才是给用户的话；回执头（[执行回执] …）去掉。 */
    public static String conclusionOf(String a) {
        if (a == null) return "";
        int i = a.lastIndexOf("结论：");
        String s = i >= 0 ? a.substring(i + 3) : a;
        if (s.startsWith("[")) {
            int j = s.indexOf(']');
            if (j > 0 && j < 16) s = s.substring(j + 1);
        }
        return s.trim();
    }

    private void trim() {
        while (entries.size() > MAX_ENTRIES) entries.remove(0);
    }

    public List<Entry> entries() {
        return new ArrayList<Entry>(entries);
    }

    public Entry byId(int id) {
        for (Entry e : entries) if (e.id == id) return e;
        return null;
    }

    /** 最近一条「在做」的（本轮）。 */
    public Entry current() {
        for (int i = entries.size() - 1; i >= 0; i--) if (entries.get(i).status == Status.DOING) return entries.get(i);
        return null;
    }

    public List<Entry> unfinished() {
        List<Entry> l = new ArrayList<Entry>();
        for (Entry e : entries) if (e.status != Status.DONE) l.add(e);
        return l;
    }

    public int size() {
        return entries.size();
    }

    public void reset() {
        entries.clear();
        nextId = 1;
    }
}
