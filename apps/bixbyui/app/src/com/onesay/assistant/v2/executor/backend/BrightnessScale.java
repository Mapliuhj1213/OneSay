package com.onesay.assistant.v2.executor.backend;

import com.onesay.assistant.v2.executor.ShellExecutor;
import com.onesay.assistant.v2.executor.ShellRunner;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ fix-1118 c32：screen_brightness 的满格不一定是 255。AOSP / 旧机是 0..255；部分厂商（真机 Meizu 21 Pro 读到 5968）用更大的刻度。
 * 满格按这个顺序取：① 系统资源 config_screenBrightnessSettingMaximum（当前值没超过它才信）→ ② {@code dumpsys power} 里的
 * mScreenBrightnessSettingMaximum / mScreenBrightnessMaximum（整数且不小于当前值）→ ③ 推算：不小于当前值的最小 2^k−1（至少 1023），
 * 这时 {@link #guessed} 为 true，回执写「约」。纯 Java，JVM 可测（系统资源在 JVM 里取不到，按 255）。
 */
public final class BrightnessScale {
    public static final int AOSP_MAX = 255;
    public static final String DUMPSYS_POWER = "dumpsys power";
    static final Pattern P_MAX = Pattern.compile("mScreenBrightness(?:Setting)?Maximum=(\\d{1,6})(?![.\\d])");

    public final int max;
    public final boolean guessed;

    BrightnessScale(int max, boolean guessed) { this.max = max; this.guessed = guessed; }

    /** 测试可替换；null = 从系统资源读。 */
    static volatile Integer frameworkMaxOverride;

    static int frameworkMax() {
        Integer o = frameworkMaxOverride;
        if (o != null) return o;
        try {
            android.content.res.Resources r = android.content.res.Resources.getSystem();
            int id = r.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android");
            if (id != 0) {
                int v = r.getInteger(id);
                if (v > 0) return v;
            }
        } catch (Throwable ignored) {
        }
        return AOSP_MAX;
    }

    public static BrightnessScale of(ShellRunner sh, int current) {
        int fw = frameworkMax();
        if (current <= fw) return new BrightnessScale(fw, false);
        if (sh != null) {
            try {
                ShellExecutor.ShellResponse r = sh.execute(DUMPSYS_POWER);
                if (r != null && r.isSuccess() && r.output != null) {
                    Matcher m = P_MAX.matcher(r.output);
                    while (m.find()) {
                        int v = Integer.parseInt(m.group(1));
                        if (v >= current && v > AOSP_MAX) return new BrightnessScale(v, false);
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        return new BrightnessScale(guessMax(current), true);
    }

    static int guessMax(int current) {
        int m = 1023;
        while (m < current && m < (1 << 30)) m = m * 2 + 1;
        return m;
    }

    public int percent(int raw) {
        if (raw <= 0) return 0;
        if (raw >= max) return 100;
        int p = (int) Math.round(raw * 100.0 / max);
        return Math.max(1, Math.min(100, p));
    }

    /** 调亮 / 调暗一步：原来是 255 刻度上 ±40，按满格等比放大。 */
    public int step(int delta) {
        long s = Math.round(Math.abs(delta) * (double) max / AOSP_MAX);
        return (int) Math.max(1, s) * (delta < 0 ? -1 : 1);
    }

    /** 读取结果：「73%（5968/8191）」；ReadOnlyAnswer / 回执模板都认这个格式。 */
    public String describe(int raw) {
        return (guessed ? "约 " : "") + percent(raw) + "%（" + raw + "/" + max + "）";
    }

    public static final Pattern P_DESCRIBED = Pattern.compile("^\\s*(约 )?(\\d{1,3})%（(\\d{1,10})/(\\d{1,10})）\\s*$");
    public static final Pattern P_STEP_OUT = Pattern.compile("^\\s*(\\d{1,10})/(\\d{1,10})\\s*$");
}
