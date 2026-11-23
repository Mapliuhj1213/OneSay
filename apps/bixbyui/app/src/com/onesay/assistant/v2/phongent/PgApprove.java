package com.onesay.assistant.v2.phongent;

import com.onesay.assistant.v2.pclink.PcConfirm;

/**
 * ★ sec-1123 M5：多设备连接 / 查看屏幕的本机确认（纯 Java，JVM 可测）。
 *
 * <p>以前通知上的「允许连接」「允许本次」按钮直接批准：能读通知的别的 App 可以代点通知按钮，等于替你批准。
 * 现在通知按钮只是打开本机确认卡（{@link PcConfirm#ask}：只认真人触摸、700 毫秒防误点、60 秒没人点 = 不允许），
 * 卡上写清是哪个设备、从哪个地址来的，方便认出是不是自己的设备。
 */
public final class PgApprove {

    private PgApprove() {}

    /** 设置项默认值：只有「只允许 Tailscale」默认开（口令是明文传的，Tailscale 是加密隧道），其余默认关。 */
    public static boolean defaultOn(String key) {
        return "tailscale_only".equals(key);
    }

    /** 确认卡上的配对说明：设备名 + 来源地址（没有地址就不写）。 */
    public static String pairDetail(String name, String remote) {
        String n = name == null || name.trim().length() == 0 ? "一台设备" : name.trim();
        String from = remote == null || remote.trim().length() == 0 ? "" : "（来自 " + remote.trim() + "）";
        return n + from + " 想连这台手机：允许后它能给手机派任务、看任务进展。发消息、付款这类照旧要你在手机上点。"
                + "不认识这个设备、或者你现在没在主控上点配对，就点拒绝。";
    }

    public static String screenDetail() {
        return "主控想看这台手机的屏幕（约 4 帧/秒），允许后没有任务时还能点按、滑动、返回。没人看 30 秒自动停，最长 10 分钟。";
    }

    /** 只有真人点了「允许」（含勾了记住的那种）才算允许；拒绝、没人点、出错都不算。 */
    public static boolean allowed(int result) {
        return result == PcConfirm.ALLOW || result == PcConfirm.REMEMBER || result == PcConfirm.ALWAYS;
    }
}
