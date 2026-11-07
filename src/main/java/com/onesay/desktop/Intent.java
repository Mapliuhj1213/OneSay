package com.onesay.desktop;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 电脑端一句话 → 指令（规则版，不调模型；模型识意图在后续版本）。
 * 只产出白名单能力；关机、睡眠必须说「确认关机」「确认睡眠」。
 */
public final class Intent {
    public final String cmd;               // null = 不执行，只回 say
    public final Map<String, Object> args;
    public final String say;               // 给用户看的一句话

    private Intent(String cmd, Map<String, Object> args, String say) {
        this.cmd = cmd;
        this.args = args;
        this.say = say;
    }

    static Intent run(String cmd, String say, Object... kv) {
        Map<String, Object> a = new TreeMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) a.put((String) kv[i], kv[i + 1]);
        return new Intent(cmd, a, say);
    }

    static Intent reply(String say) { return new Intent(null, Map.of(), say); }

    /** 常用网站（「打开 B 站」）。 */
    static final Map<String, String> SITES = new LinkedHashMap<>();
    static {
        SITES.put("b站", "https://www.bilibili.com");
        SITES.put("哔哩哔哩", "https://www.bilibili.com");
        SITES.put("百度", "https://www.baidu.com");
        SITES.put("淘宝", "https://www.taobao.com");
        SITES.put("京东", "https://www.jd.com");
        SITES.put("知乎", "https://www.zhihu.com");
        SITES.put("微博", "https://weibo.com");
        SITES.put("github", "https://github.com");
        SITES.put("抖音网页版", "https://www.douyin.com");
        SITES.put("小红书网页版", "https://www.xiaohongshu.com");
    }

    public static String normalize(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s，。！？、,!?；;：:「」“”\"']", "").replaceAll("^\\.+|\\.+$", "").toLowerCase(Locale.ROOT)
                .replace("bilibili", "b站").replace("哔哩哔哩", "b站");
    }

    /** apps：可按名字打开的程序（小写名）；shortcuts：快捷指令文件名（不含扩展名）。 */
    public static Intent parse(String text, Map<String, String> apps, List<String> shortcuts) {
        String t = normalize(text);
        if (t.isEmpty()) return reply("没听清");

        if (t.contains("确认关机")) return run("shutdown", "60 秒后关机，说「取消关机」或运行 shutdown /a 可取消", "confirm", true);
        if (t.contains("取消关机")) return run("shutdown_abort", "已取消关机");
        if (t.contains("确认睡眠") || t.contains("确认休眠")) return run("sleep", "电脑睡眠", "confirm", true);
        if (t.matches(".*(关机|关电脑|关掉电脑).*")) return reply("关机要说「确认关机」");
        if (t.matches(".*(睡眠|休眠).*")) return reply("睡眠要说「确认睡眠」");

        if (t.matches(".*(手机).*")) return reply("让手机干活的功能还在做");

        for (String s : shortcuts) {
            String n = normalize(s);
            if (!n.isEmpty() && (t.equals(n) || t.equals("执行" + n) || t.equals("运行" + n) || t.equals("执行快捷指令" + n)))
                return run("shortcut_run", "正在执行快捷指令", "name", s);
        }

        if (t.matches(".*(锁屏|锁定电脑|锁住电脑|锁一下电脑).*")) return run("lock", "锁屏");
        if (t.matches(".*(截图|截屏|截个图|截一下屏).*")) return run("screenshot", "截好了，已复制到剪贴板");

        if (t.matches(".*(静音|闭嘴|别出声).*") && !t.contains("取消")) return run("volume", "静音", "action", "mute");
        if (t.matches(".*(取消静音|打开声音).*")) return run("volume", "取消静音", "action", "mute");
        if (t.matches(".*(大声|调大|音量大|声音大|音量加|音量高).*")) return run("volume", "音量调大", "action", "up", "steps", 5L);
        if (t.matches(".*(小声|调小|音量小|声音小|音量减|音量低).*")) return run("volume", "音量调小", "action", "down", "steps", 5L);
        if (t.matches(".*(下一首|下一曲|下一个视频|切歌).*")) return run("media", "下一首", "action", "next");
        if (t.matches(".*(上一首|上一曲).*")) return run("media", "上一首", "action", "prev");
        if (t.matches("(暂停|播放|继续播放|继续|暂停播放|停一下|放歌)")) return run("media", "播放/暂停", "action", "play_pause");

        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(?:帮我)?(?:打开|启动|开一下|运行|进入)(.+)$").matcher(t);
        if (m.find()) {
            String what = m.group(1).replaceAll("^(一下|下)", "");
            for (Map.Entry<String, String> e : apps.entrySet()) {
                if (normalize(e.getKey()).equals(what)) return run("open_app", "打开" + e.getKey(), "name", e.getKey());
            }
            for (Map.Entry<String, String> e : SITES.entrySet()) {
                if (what.equals(e.getKey()) || what.equals(e.getKey() + "网站") || what.equals(e.getKey() + "官网"))
                    return run("open_url", "打开" + e.getKey(), "url", e.getValue());
            }
            if (what.matches("[a-z0-9-]+(\\.[a-z0-9-]+)+(/.*)?")) return run("open_url", "打开 " + what, "url", "https://" + what);
            return reply("没找到「" + what + "」。程序要先在设置里加上名字和路径");
        }
        return reply("没听懂：" + text);
    }
}
