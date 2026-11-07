package com.onesay.assistant.v2.executor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [r2/uxcompare] 系统控制执行后的即时反馈（纯 Java，JVM 全测）。
 *
 * <h3>差距</h3>
 * <ul>
 *   <li>「亮度调高一点」→ 回执只有「屏幕亮度已调高」，用户不知道调到了多少；</li>
 *   <li>「亮度调到 120」→「屏幕亮度已调至 120」，0~255 的原始值对用户没意义；</li>
 *   <li>「音量大一点」→「媒体音量已调大」，到顶了也照样说"已调大"；</li>
 *   <li>「发短信给妈妈」→「正在准备发送短信」，其实短信 App 已经打开、还要用户自己点「发送」，回执没说下一步。</li>
 * </ul>
 * 竞品（小爱 / 小艺 / Siri / Google）调完都报结果值（"亮度调到 60% 了""音量已经最大了"），并配一个可拖的滑条。
 *
 * <h3>规则</h3>
 * 只在 {@link UnifiedExecutor} 拼回执时，用<b>回读输出</b>（check，没有就用执行输出）改写下面这几个签名的文案；
 * 读不出合法数值就返回 null = 沿用能力自带的回执，<b>永远不会因为这里让成功变失败</b>。
 * 不改执行命令、不改回读断言、不改能力注册（避免和能力快照测试 / 其它线程冲突）。
 */
public final class ControlFeedback {

    private ControlFeedback() {}

    /** Android 8 / Note 8 screen_brightness 0..255。 */
    static final int BRIGHTNESS_MAX = 255;

    private static final Pattern P_INT = Pattern.compile("(?<![0-9])(\\d{1,3})(?![0-9])");
    private static final Pattern P_VOLUME = Pattern.compile(
            "volume is\\s*(\\d{1,3})(?:\\s*in range\\s*\\[(\\d{1,3})\\.\\.(\\d{1,3})\\])?");

    /**
     * @param sign    能力签名
     * @param checkOut 回读输出（可空串）
     * @param execOut  执行输出（可空串）
     * @return 改写后的回执；不认识 / 读不出数值 → null（调用方沿用原回执）
     */
    public static String refine(String sign, String checkOut, String execOut) {
        if (sign == null) return null;
        String out = nz(checkOut).length() > 0 ? nz(checkOut) : nz(execOut);
        try {
            if ("settings.brightness.set".equals(sign)
                    || "settings.brightness.up".equals(sign)
                    || "settings.brightness.down".equals(sign)) {
                int v = firstInt(out);
                if (v < 0 || v > BRIGHTNESS_MAX) return null;
                int pct = brightnessPercent(v);
                if ("settings.brightness.up".equals(sign) && v >= BRIGHTNESS_MAX) return "亮度已经最高了（100%）";
                if ("settings.brightness.down".equals(sign) && v <= 1) return "亮度已经最低了";
                if ("settings.brightness.up".equals(sign)) return "亮度调高到 " + pct + "%";
                if ("settings.brightness.down".equals(sign)) return "亮度调低到 " + pct + "%";
                return "亮度已调到 " + pct + "%";
            }
            if (sign.startsWith("volume.") && (sign.endsWith(".set") || sign.endsWith(".up") || sign.endsWith(".down"))) {
                Matcher m = P_VOLUME.matcher(out);
                if (!m.find()) return null;
                int v = Integer.parseInt(m.group(1));
                int max = m.group(3) != null ? Integer.parseInt(m.group(3)) : -1;
                if (v < 0 || v > 100 || (max > 0 && v > max)) return null;
                String name = volumeName(sign);
                if (name == null) return null;
                if (v == 0) return name + "已静音";
                if (max > 0 && v == max) return name + "已经最大了";
                String tail = max > 0 ? "（共 " + max + " 格）" : "";
                if (sign.endsWith(".up")) return name + "调大到 " + v + tail;
                if (sign.endsWith(".down")) return name + "调小到 " + v + tail;
                return name + "已调到 " + v + tail;
            }
            if ("sms.send".equals(sign)) {
                // SENDTO 只打开短信编辑页、预填正文；真正发出要用户在短信 App 里点「发送」（见 CommsRisk）
                return "短信已写好，核对后点「发送」就发出去了";
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    /** 0..255 → 百分比（1 也算 0%，与系统滑条一致四舍五入）。 */
    static int brightnessPercent(int raw) {
        if (raw <= 0) return 0;
        if (raw >= BRIGHTNESS_MAX) return 100;
        return (int) Math.round(raw * 100.0 / BRIGHTNESS_MAX);
    }

    private static String volumeName(String sign) {
        if (sign.startsWith("volume.media.")) return "媒体音量";
        if (sign.startsWith("volume.ring.")) return "铃声音量";
        if (sign.startsWith("volume.alarm.")) return "闹钟音量";
        if (sign.startsWith("volume.notif.")) return "通知音量";
        return null;
    }

    private static int firstInt(String s) {
        Matcher m = P_INT.matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
