/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: translated from Kotlin (core/action/OutwardPacing.kt) to Java 7; sites are package names
 * (phone) or the host's registrable domain approximated by the last two labels (web). Upstream: ebb0e44.
 */
package com.onesay.assistant.plug.action.core;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Random;

/**
 * 对外动作之间的间隔：同一 App / 网站里连着发送、提交、删除、下单时，两次之间隔一段随机时间（像人一条条处理）。
 * 第一次不等；之后每次和上一次至少隔 firstGap 里随机的一段，窗口里做得越多上下限各加一截，不超过 maxGap；
 * 间隔从上一次做完算起，只补不够的部分。random 为 null = 从不等待。
 */
public final class OutwardPacing {
    public long firstGapLo = 8000, firstGapHi = 20000, perActionLo = 500, perActionHi = 1000, maxGap = 30000, windowMs = 30 * 60000L;

    private static final class Done {
        final String site;
        final long at, gap;

        Done(String s, long a, long g) {
            site = s;
            at = a;
            gap = g;
        }
    }

    private final Random random;
    private final ArrayDeque<Done> done = new ArrayDeque<Done>();

    public OutwardPacing(Random random) {
        this.random = random;
    }

    public boolean paced() {
        return random != null;
    }

    public synchronized long waitBefore(String site, long nowMs) {
        if (random == null || site == null) return 0;
        prune(nowMs);
        Done last = null;
        for (Done d : done) if (d.site.equals(site)) last = d;
        if (last == null) return 0;
        return Math.max(0, Math.min(maxGap, last.gap - (nowMs - last.at)));
    }

    public synchronized void noteDone(String site, long nowMs) {
        if (random == null || site == null) return;
        prune(nowMs);
        int count = 1;
        for (Done d : done) if (d.site.equals(site)) count++;
        done.addLast(new Done(site, nowMs, gapAfter(count)));
    }

    long gapAfter(int n) {
        long extra = n - 1;
        long lo = Math.min(maxGap, firstGapLo + extra * perActionLo);
        long hi = Math.min(maxGap, firstGapHi + extra * perActionHi);
        if (lo >= hi) return hi;
        return lo + (long) (random.nextDouble() * (hi - lo + 1));
    }

    private void prune(long now) {
        Iterator<Done> it = done.iterator();
        while (it.hasNext()) {
            if (now - it.next().at > windowMs) it.remove();
            else break;
        }
    }

    /** 一屏属于哪个 App / 网站：手机按包名，网页按主机名后两段。 */
    public static String siteOf(String pkgOrHost, boolean web) {
        if (pkgOrHost == null || pkgOrHost.trim().length() == 0) return null;
        String p = pkgOrHost.trim().toLowerCase(java.util.Locale.ROOT);
        if (!web) return p;
        String[] parts = p.split("\\.");
        return parts.length <= 2 ? p : parts[parts.length - 2] + "." + parts[parts.length - 1];
    }
}
