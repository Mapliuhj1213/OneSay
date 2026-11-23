package com.onesay.assistant.v2.vision;

import android.content.Context;

import com.onesay.assistant.v2.capability.Capability;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ fix/1123-bug2：识屏现在用不用得了（没配识屏 Key = 用不了）。
 *
 * <p>以前没配 Key 时：AgentLoop 照样把「识屏」工具给模型，模型一调就先截一张屏（约 1 s）再报「识屏失败」，
 * 失败还标成可重试，于是模型一轮轮接着要识屏；点界面的代理（PhoneAgent）每个任务也要先白问一轮模型、白截一次屏才发现用不了。
 * 现在开局就判：用不了就不给工具、不截屏，真被调到也直接回「没配好、别再试」。
 *
 * <p>纯判断都是静态方法，JVM 可测；只有 {@link #usable(Context)} 读本机配置。
 */
public final class VisionAvailability {

    private VisionAvailability() {}

    /** 识屏工具签名（与 VisionBackend.SIGN 一致）。 */
    public static final String SIGN = "vision.screen.understand";

    /** 给用户 / 模型看的一句话（大白话，不出现内部 ID）。 */
    public static final String NO_KEY_TEXT = "识屏还没配好（缺识屏用的 Key），这次看不了屏幕图像";

    /** ★ 1123d B31：识屏引擎没 Key 时给用户的话（截屏前就说，不出现服务商名）。 */
    public static final String NO_KEY_USER = "识屏要先在设置里填好识屏用的 Key，这次看不了屏幕图像。";

    /** 给模型的处理提示：别再要识屏。 */
    public static final String NO_KEY_HINT = "识屏用不了：不要再调用识屏；改用读屏文字结果，或如实告诉用户要先在设置里填好识屏用的 Key";

    public static boolean keyMissing(String key) {
        return key == null || key.trim().length() == 0;
    }

    /**
     * 本机识屏能不能用。ctx 为 null（JVM 单测 / 自测假宿主）= 不知道 → 当能用，保持原行为。
     * 读配置出错也当能用（宁可多试一次，也不把能用的识屏误关）。
     */
    public static boolean usable(Context ctx) {
        if (ctx == null) return true;
        try {
            return !keyMissing(new com.onesay.assistant.v2.asr.AliyunAsrConfig(ctx).getDashScopeKey());
        } catch (Throwable t) {
            return true;
        }
    }

    /** 点界面代理用：看图兜底开着且有 Key 才算能用（与 FastScreenDriver.describeScreen 的前置条件一致）。 */
    public static boolean usableForPhoneAgent(Context ctx) {
        if (ctx == null) return true;
        try {
            if (!com.onesay.assistant.v2.vision.screenref.ScreenRefSettings.vlFallbackEnabled(ctx)) return false;
        } catch (Throwable t) {
            return true;
        }
        return usable(ctx);
    }

    /** 用不了就把识屏工具从候选里拿掉（其余顺序不变）；能用原样返回。 */
    public static List<Capability> filterCandidates(List<Capability> in, boolean usable) {
        if (usable || in == null) return in;
        List<Capability> out = new ArrayList<Capability>(in.size());
        for (Capability c : in) {
            if (c != null && SIGN.equals(c.sign)) continue;
            out.add(c);
        }
        return out;
    }
}
