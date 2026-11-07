/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core.scenes.shot;

import com.onesay.assistant.plug.suggest.core.Signal;

import java.util.HashMap;
import java.util.Map;

/** 截图候选（§6.3）。source：A = MediaStore，B = root inotify，D = 截图按键 / 系统广播（待验证的加分信号）。 */
public final class ScreenshotCandidate {
    public static final char SRC_MEDIASTORE = 'A', SRC_INOTIFY = 'B', SRC_SYSTEM = 'D';

    public final String path;
    /** MediaStore _ID；B 源为 -1。 */
    public final long mediaId;
    public final long size;
    public final long mtimeMs;
    public final int width, height;
    /** MediaStore DATE_ADDED（秒）；B 源为 0。 */
    public final long dateAddedSec;
    public final char source;
    public final long seenAtElapsed;
    /** 写完了：B 源 = CLOSE_WRITE；A 源 = 宿主 WriteSettle 判定 250 ms 内大小不变。 */
    public final boolean settled;

    public ScreenshotCandidate(String path, long mediaId, long size, long mtimeMs, int width, int height, long dateAddedSec,
                               char source, long seenAtElapsed, boolean settled) {
        this.path = path;
        this.mediaId = mediaId;
        this.size = size;
        this.mtimeMs = mtimeMs;
        this.width = width;
        this.height = height;
        this.dateAddedSec = dateAddedSec;
        this.source = source;
        this.seenAtElapsed = seenAtElapsed;
        this.settled = settled;
    }

    public boolean highConfidence() {
        return source == SRC_INOTIFY || source == SRC_SYSTEM;
    }

    /** 信号属性键（宿主 emit 时用）。 */
    public static final String K_PATH = "path", K_ID = "media_id", K_SIZE = "size", K_MTIME = "mtime", K_W = "w", K_H = "h",
            K_ADDED = "date_added", K_SRC = "src", K_SETTLED = "settled", K_FG = "fg", K_FG_LABEL = "fg_label";

    public Map<String, String> toAttrs() {
        Map<String, String> m = new HashMap<String, String>();
        m.put(K_PATH, path);
        m.put(K_ID, String.valueOf(mediaId));
        m.put(K_SIZE, String.valueOf(size));
        m.put(K_MTIME, String.valueOf(mtimeMs));
        m.put(K_W, String.valueOf(width));
        m.put(K_H, String.valueOf(height));
        m.put(K_ADDED, String.valueOf(dateAddedSec));
        m.put(K_SRC, String.valueOf(source));
        m.put(K_SETTLED, settled ? "1" : "0");
        return m;
    }

    public static ScreenshotCandidate from(Signal s) {
        String src = s.get(K_SRC, "A");
        return new ScreenshotCandidate(s.get(K_PATH), s.getLong(K_ID, -1), s.getLong(K_SIZE, -1), s.getLong(K_MTIME, 0),
                (int) s.getLong(K_W, 0), (int) s.getLong(K_H, 0), s.getLong(K_ADDED, 0), src.length() > 0 ? src.charAt(0) : 'A',
                s.atElapsed, s.getBool(K_SETTLED));
    }
}
