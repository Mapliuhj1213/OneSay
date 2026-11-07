/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core.scenes.shot;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * ★ fix/shot-1104 B 源事件闸：同一个文件 inotify 会连着报 n（新建）/ w（写完）/ y（移入），三星滚动截图还会多次改写。
 * 同一规范化路径「正在等写完」或「刚上报过（{@link #HOLD_MS} 内）」就不再起一次等待，避免一张图起两路 WriteSettle。
 * 纯 Java，时间由调用方给（elapsedRealtime）。
 */
public final class ShotEventGate {
    public static final long HOLD_MS = 5000L;

    private final Map<String, Long> mInflight = new HashMap<String, Long>();
    private final Map<String, Long> mDone = new HashMap<String, Long>();

    /** true = 这次事件要起一次「等写完」；false = 同一文件已经在等 / 刚报过。 */
    public synchronized boolean begin(String path, long now) {
        String k = PathNorm.norm(path);
        if (k.length() == 0) return false;
        prune(now);
        if (mInflight.containsKey(k)) return false;
        Long d = mDone.get(k);
        if (d != null && now - d < HOLD_MS) return false;
        mInflight.put(k, now);
        return true;
    }

    /** 等待结束；emitted = 真的上报了（之后 HOLD_MS 内同路径不再起）。 */
    public synchronized void end(String path, long now, boolean emitted) {
        String k = PathNorm.norm(path);
        mInflight.remove(k);
        if (emitted) mDone.put(k, now);
    }

    public synchronized int inflight() {
        return mInflight.size();
    }

    private void prune(long now) {
        for (Iterator<Map.Entry<String, Long>> it = mDone.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue() >= HOLD_MS) it.remove();
        }
        for (Iterator<Map.Entry<String, Long>> it = mInflight.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue() > 4L * WriteSettle.MAX_MS) it.remove();   // 回调丢了也不永远卡住
        }
    }
}
