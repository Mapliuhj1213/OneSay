package com.onesay.clock.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** 新闻件缓存限额清理（P2-1）：超过件数或字节上限时按最近使用（mtime）从旧到新删，受保护的（今天用到的）不删。 */
public final class CacheJanitor {
    private CacheJanitor() { }

    public static final int MAX_FILES = 400;
    public static final long MAX_BYTES = 40L * 1024 * 1024;
    public static final long MAX_AGE_MS = 30L * 86400_000L;

    public static final class F {
        public final String name;
        public final long size, mtime;

        public F(String name, long size, long mtime) { this.name = name; this.size = size; this.mtime = mtime; }
    }

    public static List<String> victims(List<F> files, Set<String> keep, long now, int maxFiles, long maxBytes) {
        List<F> s = new ArrayList<F>(files);
        Collections.sort(s, new Comparator<F>() {
            public int compare(F a, F b) { return a.mtime < b.mtime ? -1 : (a.mtime == b.mtime ? 0 : 1); }
        });
        long total = 0;
        for (F f : s) total += f.size;
        int count = s.size();
        List<String> out = new ArrayList<String>();
        for (F f : s) {
            boolean over = count > maxFiles || total > maxBytes;
            boolean old = now - f.mtime > MAX_AGE_MS;
            if (!over && !old) continue;
            if (keep != null && keep.contains(f.name)) continue;
            out.add(f.name);
            count--;
            total -= f.size;
        }
        return out;
    }
}
