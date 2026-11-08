package com.onesay.desktop;

import java.util.List;
import java.util.Map;

/**
 * 协议 v2：电脑 → 手机的指令清单（手机端 {@code PcRxCommands} 是另一半）。命令集思路参考 Home Assistant 安卓伴侣 App
 * （Apache-2.0）的通知指令（command_screen_on / command_volume_level / command_launch_app / command_activity 等）；代码自己写的。
 *
 * <ul>
 *   <li>find_phone {action:start|stop, seconds≤120}：最大音量响铃（闹钟音频流，静音模式也响），再发一次 stop 或到时自动停；</li>
 *   <li>open_url {url}：只许 http/https；open_app {package} 或 {name}（按桌面上的名字找）；</li>
 *   <li>screen_on：点亮屏幕（不解锁）；volume_set {percent 0–100}：媒体音量；</li>
 *   <li>clipboard_set {text}：写手机剪贴板；notify {title, text}：手机上弹一条通知；status：电量、充电、屏幕、铃声模式、媒体音量；</li>
 *   <li>ping / caps：在线、手机上打开了哪些。</li>
 * </ul>
 * 每一项手机上都有开关，开源版危险项（打开网址 / 打开 App / 写剪贴板）默认关；都必须先配对。
 */
public final class PhoneCmds {
    public static final List<String> ALL = List.of("ping", "caps", "status", "find_phone", "open_url", "open_app", "screen_on", "volume_set", "clipboard_set", "notify");

    private PhoneCmds() {}

    public static long timeoutMs(String cmd) {
        return cmd.equals("find_phone") || cmd.equals("open_app") ? 12_000 : 8_000;
    }

    /** 参数检查；没问题返回 null，否则返回给人看的原因。手机端会再查一遍。 */
    public static String check(String cmd, Map<String, Object> a) {
        if (!ALL.contains(cmd)) return "手机不支持「" + cmd + "」";
        switch (cmd) {
            case "open_url": {
                String u = str(a, "url");
                if (!(u.startsWith("https://") || u.startsWith("http://")) || u.length() > 2000) return "只能打开 http/https 网址";
                return null;
            }
            case "open_app":
                return str(a, "package").isEmpty() && str(a, "name").isEmpty() ? "要说打开哪个 App" : null;
            case "volume_set": {
                Object p = a.get("percent");
                if (!(p instanceof Number) || ((Number) p).intValue() < 0 || ((Number) p).intValue() > 100) return "音量要在 0–100";
                return null;
            }
            case "clipboard_set":
                return str(a, "text").isEmpty() ? "剪贴板是空的" : str(a, "text").length() > 100_000 ? "文字太长（超过 10 万字）" : null;
            case "notify":
                return str(a, "title").isEmpty() && str(a, "text").isEmpty() ? "通知没有内容" : null;
            case "find_phone": {
                String act = str(a, "action");
                return act.isEmpty() || act.equals("start") || act.equals("stop") ? null : "action 只能是 start / stop";
            }
            default: return null;
        }
    }

    static String str(Map<String, Object> a, String k) {
        Object v = a == null ? null : a.get(k);
        return v == null ? "" : String.valueOf(v).trim();
    }
}
