package com.onesay.assistant.v2.wake;

/**
 * ★ 1123e P1：录音引擎坏了（授录音权限之前就启动、AudioRecord 初始化失败）以后，唤醒时就地重建，带退避。
 *
 * <p>以前引擎启动失败一次就一直坏着，直到进程重启；快捷开关 / 按键唤醒排队 10 秒后悄悄丢掉。
 * 现在：每次唤醒看一眼引擎，坏了就重建（第 1 次立刻，之后 1 s、2 s、4 s … 最长 30 s 才再试）；
 * 重建也不成 → 不再干等，直接打开卡片说清楚原因，并把输入框交给用户打字。纯函数，JVM 单测。
 */
public final class MicEngineRecovery {

    private MicEngineRecovery() {}

    public static final long BACKOFF_FIRST_MS = 1000L;
    public static final long BACKOFF_MAX_MS = 30000L;

    /** 连续失败 failures 次以后，距上次尝试至少要等多久才再重建（0 次 = 不用等）。 */
    public static long backoffMs(int failures) {
        if (failures <= 0) return 0L;
        long d = BACKOFF_FIRST_MS;
        for (int i = 1; i < failures && d < BACKOFF_MAX_MS; i++) d *= 2;
        return Math.min(d, BACKOFF_MAX_MS);
    }

    /** 唤醒来了：要不要现在重建引擎。引擎好着 / 正在重建 / 退避没到 → 不建。 */
    public static boolean shouldRebuild(boolean micReady, boolean engineFailed, boolean rebuilding,
                                        long now, long lastAttemptAt, int failures) {
        if (micReady || !engineFailed || rebuilding) return false;
        if (lastAttemptAt <= 0 || now < lastAttemptAt) return true;
        return now - lastAttemptAt >= backoffMs(failures);
    }

    /** 收不了音时给用户的话（大白话，不带内部错误）。 */
    public static String userMessage(boolean recordPermission) {
        return recordPermission
                ? "麦克风暂时打不开，可能被别的应用占着。先打字吧，稍后再按一次试试"
                : "还没给麦克风权限，到设置里给 OneSay 打开麦克风就能说话。现在可以先打字";
    }

    /** 输入框里的提示（短，放得下）。 */
    public static String inputHint(boolean recordPermission) {
        return recordPermission ? "麦克风打不开，先打字…" : "没有麦克风权限，先打字…";
    }
}
