/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core.scenes.shot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** 连拍 / 批量合并（§6.3-7）：3 s 窗口内的多张合成一条；同一路径重复上报（滚动截图多次改写）只算一张。 */
public final class BurstMerger {
    public static final long WINDOW_MS = 3000L;
    public static final int MAX_PATHS = 4;

    public static final int NEW = 0, MERGED = 1;

    public static final class MergeResult {
        public final int kind;
        public final long burstId;
        public final int count;
        /** 最近的 ≤4 张（旧 → 新），分析默认用最后一张。 */
        public final List<String> paths;

        MergeResult(int kind, long burstId, int count, List<String> paths) {
            this.kind = kind;
            this.burstId = burstId;
            this.count = count;
            this.paths = paths;
        }

        public String latest() {
            return paths.isEmpty() ? null : paths.get(paths.size() - 1);
        }
    }

    private final long mWindow;
    private long mBurst;
    private long mLastAt = Long.MIN_VALUE;
    private final LinkedHashMap<String, String> mPaths = new LinkedHashMap<String, String>();   // norm → 原路径

    public BurstMerger() {
        this(WINDOW_MS);
    }

    public BurstMerger(long windowMs) {
        mWindow = windowMs;
    }

    public synchronized MergeResult add(String path, long nowElapsed) {
        boolean inWindow = mLastAt != Long.MIN_VALUE && nowElapsed - mLastAt <= mWindow && nowElapsed >= mLastAt;
        int kind;
        if (!inWindow) {
            mBurst++;
            mPaths.clear();
            kind = NEW;
        } else {
            kind = MERGED;
        }
        String n = PathNorm.norm(path);
        mPaths.remove(n);
        mPaths.put(n, path);
        mLastAt = nowElapsed;
        List<String> all = new ArrayList<String>(mPaths.values());
        List<String> tail = all.size() > MAX_PATHS ? new ArrayList<String>(all.subList(all.size() - MAX_PATHS, all.size())) : all;
        return new MergeResult(kind, mBurst, all.size(), tail);
    }
}
