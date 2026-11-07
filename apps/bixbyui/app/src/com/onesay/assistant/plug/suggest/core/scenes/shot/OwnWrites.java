/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core.scenes.shot;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** OneSay 自己写图前登记 path（60 s 有效），截图判定据此排除自产图片。线程安全。 */
public final class OwnWrites {
    public static final long TTL_MS = 60000L;
    private final LinkedHashMap<String, Long> mMap = new LinkedHashMap<String, Long>();

    public synchronized void register(String path, long nowElapsed) {
        if (path == null) return;
        prune(nowElapsed);
        mMap.put(PathNorm.norm(path), nowElapsed);
    }

    public synchronized boolean contains(String path, long nowElapsed) {
        if (path == null) return false;
        prune(nowElapsed);
        return mMap.containsKey(PathNorm.norm(path));
    }

    public synchronized int size() {
        return mMap.size();
    }

    private void prune(long now) {
        Iterator<Map.Entry<String, Long>> it = mMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (now - e.getValue() > TTL_MS || now < e.getValue() - TTL_MS) it.remove();
        }
        while (mMap.size() > 64) {
            it = mMap.entrySet().iterator();
            it.next();
            it.remove();
        }
    }
}
