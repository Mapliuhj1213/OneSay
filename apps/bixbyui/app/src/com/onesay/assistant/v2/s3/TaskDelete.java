package com.onesay.assistant.v2.s3;

/** ★ fix-1118：控制台「自动任务」逐条删除的二次点击判定（纯函数，JVM 可测）：第一次点只提示，3 秒内再点才删。 */
public final class TaskDelete {
    private TaskDelete() {}

    public static final long WINDOW_MS = 3000L;

    public static boolean secondTap(long armedAt, long now) {
        return armedAt > 0 && now >= armedAt && now - armedAt <= WINDOW_MS;
    }
}
