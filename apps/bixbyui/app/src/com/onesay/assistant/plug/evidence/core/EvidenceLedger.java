/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: core/verify/EvidenceLedger.kt translated to Java 7; Mika's Grounding claim checker is replaced
 * by a light claim extractor (numbers, quoted / delimited fragments), Redactor by a local masker (card / ID numbers,
 * API keys, verification codes). Upstream: ebb0e44.
 */
package com.onesay.assistant.plug.evidence.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一次任务里实际读到的全部材料（证据账本）：读屏、读网页、工具结果里的事实。进账前脱敏、按来源截短；同样的原文只留最近一次；
 * 总量有上限（从最早的丢起）。助手自己打进输入框的字不给回答作证（noteTyped）。{@link #forAnswer} 挑出交给核对 / 来源的条目。
 */
public final class EvidenceLedger {

    public enum Kind { READ, OUTPUT, FACT, USER_SAID }

    public static final class Entry {
        public final int id, step;
        public final Kind kind;
        public final String text, place, title;

        Entry(int id, int step, Kind kind, String text, String place, String title) {
            this.id = id;
            this.step = step;
            this.kind = kind;
            this.text = text;
            this.place = place;
            this.title = title;
        }
    }

    public static final int STORE_CHARS = 400000, READ_CHARS = 6000, OUTPUT_CHARS = 4000, RECENT = 1;

    private final int cap;
    private final ArrayDeque<Entry> entries = new ArrayDeque<Entry>();
    private final Set<String> typed = new HashSet<String>();
    private int total, seq;

    public EvidenceLedger(int capChars) {
        cap = capChars > 0 ? capChars : STORE_CHARS;
    }

    public synchronized List<Entry> all() {
        return new ArrayList<Entry>(entries);
    }

    public synchronized int lastId() {
        return seq;
    }

    /** 记一条；脱敏截短后为空的不记。place = App 包名 / 网址 / 工具名。 */
    public synchronized Entry add(Kind kind, String text, String place, String title, int step) {
        if (text == null) return null;
        String t = clip(kind, redact(text)).trim();
        if (t.length() == 0) return null;
        java.util.Iterator<Entry> it = entries.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (e.text.equals(t) && eq(e.place, place)) {
                total -= e.text.length();
                it.remove();
                break;
            }
        }
        Entry e = new Entry(++seq, step, kind, t, place, title == null ? null : redact(title));
        entries.addLast(e);
        total += t.length();
        while (total > cap && entries.size() > 1) total -= entries.removeFirst().text.length();
        return e;
    }

    /** 助手打进输入框的字（非密码框）。 */
    public synchronized void noteTyped(String text) {
        String n = norm(text);
        if (n.length() > 0) typed.add(n);
    }

    /** 编号 id 之后读到过东西（工具报的事实不算读）。 */
    public synchronized boolean readSince(int id) {
        for (Entry e : entries) if (e.id > id && e.kind != Kind.FACT) return true;
        return false;
    }

    /** 交给核对 / 来源的条目：含回答里某条事实的每一条、这些条目与回答提到的各处最近的一条、最近读到的 RECENT 条，按先后。 */
    public synchronized List<Entry> forAnswer(String goal, String answer) {
        List<Entry> cands = new ArrayList<Entry>();
        for (Entry e : entries) {
            Entry w = withoutEcho(e);
            if (w != null) cands.add(w);
        }
        List<String> claims = claims(answer);
        Set<Integer> pick = new LinkedHashSet<Integer>();
        Set<String> places = new LinkedHashSet<String>();
        String said = ((goal == null ? "" : goal) + "\n" + (answer == null ? "" : answer)).toLowerCase(Locale.ROOT);
        for (int i = 0; i < cands.size(); i++) {
            if (holds(cands.get(i).text, claims)) {
                pick.add(i);
                if (cands.get(i).place != null) places.add(cands.get(i).place);
            }
            String p = cands.get(i).place;
            if (p != null) for (String part : p.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                if (part.length() >= 3 && !NOISE.contains(part) && said.contains(part)) places.add(p);
        }
        for (String p : places) {
            for (int i = cands.size() - 1; i >= 0; i--) if (p.equals(cands.get(i).place)) {
                pick.add(i);
                break;
            }
        }
        for (int i = Math.max(0, cands.size() - RECENT); i < cands.size(); i++) pick.add(i);
        List<Integer> idx = new ArrayList<Integer>(pick);
        java.util.Collections.sort(idx);
        List<Entry> out = new ArrayList<Entry>();
        for (int i : idx) out.add(cands.get(i));
        return out;
    }

    /** 回答里的事实对得上的条目（来源由它来挑）。 */
    public synchronized List<Entry> supporting(String answer) {
        List<String> claims = claims(answer);
        List<Entry> out = new ArrayList<Entry>();
        if (claims.isEmpty()) return out;
        for (Entry e : entries) {
            Entry w = withoutEcho(e);
            if (w != null && holds(w.text, claims)) out.add(w);
        }
        return out;
    }

    private Entry withoutEcho(Entry e) {
        if (typed.isEmpty() || e.kind != Kind.READ) return e;
        StringBuilder b = new StringBuilder();
        boolean dropped = false;
        for (String l : e.text.split("\n")) {
            if (typed.contains(norm(l))) {
                dropped = true;
                continue;
            }
            if (b.length() > 0) b.append('\n');
            b.append(l);
        }
        if (!dropped) return e;
        return b.toString().trim().length() == 0 ? null : new Entry(e.id, e.step, e.kind, b.toString(), e.place, e.title);
    }

    static final Set<String> NOISE = new HashSet<String>(java.util.Arrays.asList("com", "cn", "net", "org", "www", "http", "https", "android", "app", "html"));
    private static final Pattern NUM = Pattern.compile("\\d[\\d.,:：/\\-]*\\d|\\d{2,}");
    private static final Pattern SPLIT = Pattern.compile("[，。！？；、,.!?;:：\\s「」『』“”\"'（）()【】\\[\\]]+");

    /** 回答里可核对的事实：两位以上的数字串、被标点隔开的 ≥ 4 字片段。 */
    static List<String> claims(String answer) {
        List<String> out = new ArrayList<String>();
        if (answer == null) return out;
        Matcher m = NUM.matcher(answer);
        while (m.find()) out.add(m.group());
        for (String s : SPLIT.split(answer)) {
            String t = s.trim();
            if (t.length() >= 4 && t.length() <= 40 && !out.contains(t)) out.add(t);
        }
        return out;
    }

    static boolean holds(String text, List<String> claims) {
        String n = norm(text);
        for (String c : claims) if (norm(c).length() >= 2 && n.contains(norm(c))) return true;
        return false;
    }

    private static final Pattern CARD = Pattern.compile("(?<![0-9])(?:[0-9][ -]?){12,18}[0-9](?![0-9])");
    private static final Pattern KEY = Pattern.compile("(?i)\\b(sk|ak|tk)-[A-Za-z0-9_\\-]{12,}|\\b[A-Za-z0-9]{32,}\\b");
    private static final Pattern CODE = Pattern.compile("(验证码|校验码|code)[^0-9]{0,6}([0-9]{4,8})", Pattern.CASE_INSENSITIVE);

    /** 脱敏：卡号 / 身份证（13–19 位数字）、像 API Key 的长串、验证码。 */
    public static String redact(String s) {
        if (s == null) return "";
        String r = CARD.matcher(s).replaceAll("[已打码]");
        r = KEY.matcher(r).replaceAll("[已打码]");
        Matcher m = CODE.matcher(r);
        StringBuffer b = new StringBuffer();
        while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement(m.group(1) + " [已打码]"));
        m.appendTail(b);
        return b.toString();
    }

    static String clip(Kind k, String t) {
        if (k == Kind.READ || k == Kind.USER_SAID) return t.length() > READ_CHARS ? t.substring(0, READ_CHARS) : t;
        if (t.length() <= OUTPUT_CHARS) return t;
        return t.substring(0, OUTPUT_CHARS * 3 / 4) + "\n…（中间省略）…\n" + t.substring(t.length() - OUTPUT_CHARS / 4);
    }

    static String norm(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
