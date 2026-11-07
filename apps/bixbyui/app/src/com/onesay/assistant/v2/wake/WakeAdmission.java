package com.onesay.assistant.v2.wake;

import android.content.Context;

/**
 * 唤醒 / 按键手势的「简报闸门」——全工程唯一一处判断（纯 Java，JVM 可测）。
 *
 * <h3>规则</h3>
 * 简报播放中按 Bixby 键 = 只停简报、这一次按键不唤醒；同一次按键经别的通道 / 后续事件再到也一并吞掉。
 * 只认按键类来源（{@code key-*}）。唤醒词（{@code voice}）、「✦ 语音」继续聆听（{@code voice-ui}）不在这里拦：
 * 唤醒照常，唤醒后说的「再播一遍 / 跳过 / 详细说说 / 停」由 {@code BriefPlugin}（L0，{@code BriefControl.parse}）按活跃 / 在播接管。
 *
 * <h3>只有两个调用点（都是汇合点，不是入口）</h3>
 * <ul>
 *   <li>{@code WakeService.requestWakeInternal} 第一行：一切唤醒都汇到这里——按键文件短按（key-file）、
 *       随口记收音（key-file-hold）、按键兜底 Activity（key-activity，经 requestWake）、开发页模拟（key-sim）、
 *       唤醒词（voice，wakeOnce）、冷启动排队补发（onMicReady）。</li>
 *   <li>{@code WakeService.dispatchKeyGesture} 第一行：长按不走唤醒，直接交插件（记一记）——按键文件的 long / 长按后松手 hold、
 *       守护 v7 冷启动 {@code -e gesture long} 都汇到这里。来源记作 {@code key-gesture-<手势>}。</li>
 * </ul>
 * 以前判断放在 {@code requestWake()}，按键文件通道直接调 requestWakeInternal 绕过了它（BUG-A1）。
 */
public final class WakeAdmission {

    private WakeAdmission() {}

    /** 长按类手势的来源前缀（仍是 key-*，所以走按键规则）。 */
    public static final String SRC_GESTURE_PREFIX = "key-gesture-";

    public static String gestureSource(String gesture) {
        return SRC_GESTURE_PREFIX + (gesture == null ? "" : gesture);
    }

    /** 按键文件通道（key-file / key-file-hold）才带守护的 seq；别的通道 seq 不可信，传 0。 */
    public static int seqFor(String source, int fileSeq) {
        return (source != null && source.startsWith(WakeService.SRC_KEY_FILE)) ? Math.max(0, fileSeq) : 0;
    }

    /**
     * 这一次唤醒 / 手势要不要被简报吞掉。true = 简报已停（或同一次按键已经停过），调用方直接 return，不唤醒、不交插件。
     *
     * @param seq 守护的按键序号（不知道传 0，见 {@link #seqFor}）
     */
    public static boolean briefSwallows(Context ctx, String source, int seq) {
        return com.onesay.assistant.v2.brief.BriefIsland.onKeyWake(ctx, source, seq);
    }
}
