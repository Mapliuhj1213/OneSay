package com.onesay.assistant.v2.net;

import android.content.Context;

/**
 * [R13 离线兜底] 「现在有没有网」的唯一出口。
 *
 * <p>识别全走云端（本地识别链路已下线），没网时发云端请求只会白等 + 最后报「没听清」—— 2026-10-13 真机在飞行模式下
 * 用户看到的就是「没听清 → 正在想（卡住）→ 唤不醒」。现在：断句 / 收窗前先问一次，没网 → 不发云端、立刻出「没有网络」卡、会话收尾。
 *
 * <p>探测失败（没有 ConnectivityManager / 权限异常）一律按「有网」处理：宁可照旧发请求（有界超时），也不能因为探测出错把能用的语音挡掉。
 */
public final class NetState {

    private NetState() {}

    /** 调试 / 单测覆盖（null = 真实探测）。 */
    public static volatile Boolean sOverride;

    /** 纯函数裁决（JVM 可测）。 */
    public static boolean decide(Boolean override, boolean probeOk, boolean hasActiveNetwork, boolean connected) {
        if (override != null) return override.booleanValue();
        if (!probeOk) return true;
        return hasActiveNetwork && connected;
    }

    @SuppressWarnings("deprecation")
    public static boolean isOnline(Context ctx) {
        Boolean o = sOverride;
        if (o != null) return o.booleanValue();
        if (ctx == null) return true;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) ctx.getApplicationContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return decide(null, false, false, false);
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return decide(null, true, ni != null, ni != null && ni.isConnected());
        } catch (Throwable t) {
            return decide(null, false, false, false);
        }
    }
}
