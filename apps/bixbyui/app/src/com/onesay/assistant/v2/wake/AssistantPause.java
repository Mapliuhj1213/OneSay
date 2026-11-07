package com.onesay.assistant.v2.wake;

import android.content.Context;

/**
 * [console R12] 控制台 Hero 总开关 =「暂停」（D1 方案 A，用户未答复，按建议实现）。
 *
 * <p>暂停时：所有唤醒来源（Bixby 键短按、冷启动兜底 Activity、语音唤醒词、调试模拟键）和 Bixby 键手势（长按记一记 / 按住随口记）
 * 一律<b>忽略</b>；WakeService 本身<b>不停</b>（前台服务、麦克风循环、岛、定时任务、取件码都照常），
 * 所以 {@code am start MainActivity} 照样能把服务拉起来（验收 IN-3）。恢复 = 立即生效，不用重启。
 *
 * <p>存在 SharedPreferences {@code bixby_console/paused}（不碰 Manifest）。没有 kill 文件：这是用户开关，不是关停开关。
 */
public final class AssistantPause {

    public static final String PREF = "bixby_console";
    public static final String KEY = "paused";

    private AssistantPause() {}

    /** 纯函数（JVM 单测入口）：这次唤醒 / 手势要不要被暂停挡掉。 */
    public static boolean blocks(boolean paused, String source) {
        return paused;
    }

    public static boolean isPaused(Context ctx) {
        if (ctx == null) return false;
        try {
            return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY, false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void set(Context ctx, boolean paused) {
        // commit（同步）：守护随时可能写 trigger，开关一落盘就要生效
        ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY, paused).commit();
    }

    /** 前台通知文案（暂停时提醒用户为什么按键没反应）。 */
    public static String notificationText(boolean paused, boolean running) {
        if (paused) return "已暂停：唤醒与 Bixby 键不响应（在控制台恢复）";
        return running ? "语音与 Bixby 按键监听中" : "正在加载模型...";
    }
}
