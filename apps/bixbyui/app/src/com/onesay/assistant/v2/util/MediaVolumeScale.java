package com.onesay.assistant.v2.util;

import android.content.Context;
import android.media.AudioManager;

/**
 * 媒体音量的真实刻度（STREAM_MUSIC 满格）。
 *
 * <p>★ meizu-1118 B6：「音量调到50%」原先一律按 0–15 折算成 8；Meizu 21 Pro 的媒体流是 0–60，
 * 8/60 只有 13%。现在进程起来时用 {@link AudioManager#getStreamMaxVolume} 读一次真实满格，
 * 路由（百分比 / 一半 / 最大）、追问话术都按它算。读不到（JVM 单测、没 Context）时按 15（AOSP 默认）。
 */
public final class MediaVolumeScale {

    /** AOSP 媒体流默认满格。 */
    public static final int DEFAULT_MAX = 15;
    /** 参数校验的上限：厂商刻度见过 15 / 25 / 30 / 60 / 150，留足余量；真正设值时系统会再夹到满格。 */
    public static final int SCHEMA_CEILING = 160;

    private static volatile int sMax = DEFAULT_MAX;

    private MediaVolumeScale() {}

    public static int max() {
        return sMax;
    }

    /** 只接受 1..{@link #SCHEMA_CEILING}；其它值忽略。返回生效后的满格。 */
    public static int set(int max) {
        if (max >= 1 && max <= SCHEMA_CEILING) sMax = max;
        return sMax;
    }

    /** 测试用：回到默认。 */
    public static void reset() {
        sMax = DEFAULT_MAX;
    }

    /** 从系统读媒体流满格；任何异常都保持原值。 */
    public static int init(Context context) {
        if (context == null) return sMax;
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) set(am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        } catch (Throwable ignore) {
            // 保持原值
        }
        return sMax;
    }
}
