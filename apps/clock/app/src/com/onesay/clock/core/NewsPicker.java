package com.onesay.clock.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 从候选池里挑出「有音色 C 音频」的标题（P1-4）：按顺序取，单条失败顺延下一条，凑够 5 条或时间到为止；
 * 不足 NEWS_MIN 条 → 返回空（新闻段整段跳过，不用本机 TTS 兜底）。纯 Java，合成函数注入。
 */
public final class NewsPicker {
    private NewsPicker() { }

    public interface Voice { boolean ensure(String title); }   // 缓存命中或云端合成成功 → true

    public interface Clock { long now(); }

    public static final class Result {
        public final List<String> chosen = new ArrayList<String>();
        public int tried, failed;
        public boolean skipped;   // 不足 NEWS_MIN 条而整段跳过
    }

    public static Result pick(List<String> pool, Voice v, long deadline, Clock clk) {
        Result r = new Result();
        if (pool != null) {
            for (String t : pool) {
                if (r.chosen.size() >= Template.NEWS_N) break;
                if (clk.now() > deadline) break;
                r.tried++;
                boolean ok;
                try { ok = v.ensure(t); } catch (Throwable e) { ok = false; }
                if (ok) r.chosen.add(t); else r.failed++;
            }
        }
        if (r.chosen.size() < Template.NEWS_MIN) { r.chosen.clear(); r.skipped = true; }
        return r;
    }
}
