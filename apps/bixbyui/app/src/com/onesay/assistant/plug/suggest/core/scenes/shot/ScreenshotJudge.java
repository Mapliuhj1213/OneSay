/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core.scenes.shot;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/**
 * 截图判定（§6.3，纯 Java，可单测）。顺序：路径白名单 → 排除自产 → 新鲜度 → 尺寸 → 写完 → 去重 → 锁屏 → 私密 App。
 * 只读元数据，不读像素。原因码给日志用（不含文件名 / 包名）。
 */
public final class ScreenshotJudge {
    private ScreenshotJudge() {
    }

    public static final long FRESH_MS = 15000L;
    /** 设备时钟 / 媒体库秒级精度带来的小偏差。 */
    public static final long SKEW_MS = 2000L;

    public static final class Verdict {
        public final boolean show;
        public final String reason;
        public final String dedupKey;

        Verdict(boolean show, String reason, String dedupKey) {
            this.show = show;
            this.reason = reason;
            this.dedupKey = dedupKey;
        }

        @Override public String toString() {
            return show ? "SHOW" + (reason != null ? "(" + reason + ")" : "") : "SKIP(" + reason + ")";
        }
    }

    public static final class JudgeEnv {
        public int screenW, screenH;
        public long pluginStartWall, nowWall, nowElapsed;
        public Set<String> recentKeys = Collections.emptySet();
        public OwnWrites own;
        public String fgPkg;
        public Set<String> privatePkgs = Collections.emptySet();
        public boolean locked;
    }

    public static String dedupKey(ScreenshotCandidate c) {
        return PathNorm.norm(c.path) + "#" + c.size;
    }

    public static boolean pathLooksLikeScreenshot(String path) {
        if (path == null || path.length() == 0) return false;
        String dir = PathNorm.parentName(path);
        String name = PathNorm.fileName(path);
        if ("screenshots".equals(dir.toLowerCase(Locale.ROOT))) return true;
        return name.startsWith("Screenshot_") || name.startsWith("screenshot_") || name.startsWith("截屏") || name.startsWith("截图");
    }

    public static boolean isOwnPath(String path) {
        return PathNorm.lower(path).contains("onesay");
    }

    public static Verdict judge(ScreenshotCandidate c, JudgeEnv env) {
        if (c == null || c.path == null) return skip("no_path", null);
        // 1. 路径白名单
        if (!pathLooksLikeScreenshot(c.path)) return skip("path", null);
        // 2. 排除自产
        if (isOwnPath(c.path) || (env.own != null && env.own.contains(c.path, env.nowElapsed))) return skip("own", null);
        // 3. 新鲜度：now - added ≤ 15 s，且 mtime ≥ 插件启动（挡住重启后重扫、云相册恢复、电脑拷进来的旧图）
        long born = c.dateAddedSec > 0 ? c.dateAddedSec * 1000L : c.mtimeMs;
        if (born <= 0) return skip("no_time", null);
        if (env.nowWall - born > FRESH_MS || born - env.nowWall > SKEW_MS) return skip("stale", null);
        if (c.mtimeMs > 0 && c.mtimeMs + SKEW_MS < env.pluginStartWall) return skip("before_start", null);
        // 4. 尺寸像截屏（竖屏宽 = 屏宽且高 ≥ 屏高 —— 含滚动长截图；横屏宽 = 屏高、高 = 屏宽）；B / D 高置信来源可放行
        String sizeNote = null;
        if (!sizeMatches(c.width, c.height, env.screenW, env.screenH)) {
            if (!c.highConfidence()) return skip(c.width <= 0 || c.height <= 0 ? "size_unknown" : "size", null);
            sizeNote = "size_relaxed";
        }
        // 5. 写完了吗
        if (!c.settled || c.size <= 0) return skip("unsettled", null);
        // 6. 去重（A、B 同一文件只认先到的）
        String key = dedupKey(c);
        if (env.recentKeys != null && env.recentKeys.contains(key)) return skip("dup", key);
        // 8. 锁屏 / 私密 App
        if (env.locked) return skip("locked", key);
        if (env.fgPkg != null && env.privatePkgs != null && env.privatePkgs.contains(env.fgPkg)) return skip("private", key);
        return new Verdict(true, sizeNote, key);
    }

    public static boolean sizeMatches(int w, int h, int screenW, int screenH) {
        if (w <= 0 || h <= 0 || screenW <= 0 || screenH <= 0) return false;
        int sw = Math.min(screenW, screenH), sh = Math.max(screenW, screenH);
        boolean portrait = w == sw && h >= sh;
        boolean landscape = w == sh && h == sw;
        return portrait || landscape;
    }

    private static Verdict skip(String r, String key) {
        return new Verdict(false, r, key);
    }
}
