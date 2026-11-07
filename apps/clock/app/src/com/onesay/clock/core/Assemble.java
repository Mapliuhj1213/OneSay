package com.onesay.clock.core;

import java.util.ArrayList;
import java.util.List;

/** 件序列 → 成品 WAV（停顿件变成静音，其余件取 PCM、处理、拼接）。纯 Java。 */
public final class Assemble {
    private Assemble() { }

    /** 件 → 24 kHz 单声道 PCM（未处理也可，这里统一 prep）；拿不到返回 null。 */
    public interface Source { short[] pcm(Template.Piece p); }

    public static final class Result {
        public PcmJoin.Out out;
        public int pieces, missing;
        public final List<String> missingKeys = new ArrayList<String>();
    }

    public static Result run(List<Template.Piece> ps, Source src) {
        Result r = new Result();
        List<short[]> parts = new ArrayList<short[]>();
        List<Integer> gaps = new ArrayList<Integer>();
        for (Template.Piece p : ps) {
            if (p.isGap()) {
                if (!gaps.isEmpty()) gaps.set(gaps.size() - 1, Integer.valueOf(p.gapMs));
                continue;
            }
            r.pieces++;
            short[] s = null;
            try { s = src.pcm(p); } catch (Throwable ignored) { }
            if (s != null) s = PcmJoin.prep(s);
            if (s == null || s.length < PcmJoin.SR / 20) {
                r.missing++;
                r.missingKeys.add(p.isBank() ? p.bank : p.text);
                s = null;
            }
            parts.add(s);
            gaps.add(Integer.valueOf(Pauses.TIGHT));
        }
        r.out = PcmJoin.join(parts, gaps);
        return r;
    }
}
