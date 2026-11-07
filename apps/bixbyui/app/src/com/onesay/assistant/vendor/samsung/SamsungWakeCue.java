package com.onesay.assistant.vendor.samsung;

import com.onesay.assistant.v2.wake.WakeCue;

/**
 * 三星适配：唤醒提示音的厂商原声候选（运行时读手机上已有的系统音 / Bixby 组件资源，不打包进 APK，版权归厂商）。
 * 只有用户把提示音来源改成「厂商」时才会去读；非三星机上一个都找不到，自动退回 App 自带提示音。
 */
public final class SamsungWakeCue {
    private SamsungWakeCue() {}

    public static WakeCue.Candidate[] candidates() {
        return new WakeCue.Candidate[]{
                new WakeCue.Candidate("file", null, "/system/media/audio/ui/Bixby_BOS.ogg"),
                new WakeCue.Candidate("raw", "com.samsung.android.bixby.agent", "toneplay_bos"),
                new WakeCue.Candidate("raw", "com.samsung.android.bixby.agent", "voice_bos"),
                new WakeCue.Candidate("raw", "com.samsung.android.bixby.wakeup", "voice_bos"),
        };
    }
}
