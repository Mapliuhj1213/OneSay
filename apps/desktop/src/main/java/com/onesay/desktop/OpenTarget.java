package com.onesay.desktop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * [approvals-1118] 「打开 bilibili」这类：本地应用和网页都有时先问用户用哪个（任务卡上的选择，不是审批卡、不进通知），
 * 可以「记住我的选择」，以后按习惯直接开（设置 › 权限记录 › 打开习惯 里能看、能改、能删）。
 * 只有一种就直接开；用户原话说了「网页 / 网站 / 网址」就直接开网页。打开本身走 open_app / open_url（同一套检查，低风险的不弹卡）。
 */
public final class OpenTarget {

    /** 常见目标：别名 → 规范名、本地应用可能叫的名字、网页。 */
    static final class Known {
        final String key, url;
        final List<String> appNames;

        Known(String key, String url, String... appNames) {
            this.key = key;
            this.url = url;
            this.appNames = List.of(appNames);
        }
    }

    static final Map<String, Known> ALIASES = new LinkedHashMap<>();

    static void alias(Known k, String... names) {
        ALIASES.put(k.key, k);
        for (String n : names) ALIASES.put(norm(n), k);
    }

    static {
        alias(new Known("bilibili", "https://www.bilibili.com", "哔哩哔哩", "bilibili"), "哔哩哔哩", "b站", "B站", "bilibili", "哔哩", "blbl");
        alias(new Known("wechat", "", "微信", "WeChat", "Weixin"), "微信", "wechat", "weixin");
        alias(new Known("qq", "", "QQ", "腾讯QQ"), "qq", "腾讯qq");
        alias(new Known("qqmusic", "https://y.qq.com", "QQ音乐", "QQMusic"), "qq音乐", "qqmusic");
        alias(new Known("neteasemusic", "https://music.163.com", "网易云音乐", "CloudMusic"), "网易云音乐", "网易云", "cloudmusic");
        alias(new Known("douyin", "https://www.douyin.com", "抖音"), "抖音", "douyin");
        alias(new Known("baidunetdisk", "https://pan.baidu.com", "百度网盘", "BaiduNetdisk"), "百度网盘", "百度云");
        alias(new Known("feishu", "https://www.feishu.cn", "飞书", "Feishu", "Lark"), "飞书", "feishu", "lark");
        alias(new Known("dingtalk", "https://im.dingtalk.com", "钉钉", "DingTalk"), "钉钉", "dingtalk");
        alias(new Known("tencentmeeting", "https://meeting.tencent.com", "腾讯会议", "wemeet"), "腾讯会议", "wemeet");
        alias(new Known("iqiyi", "https://www.iqiyi.com", "爱奇艺"), "爱奇艺", "iqiyi");
        alias(new Known("youku", "https://www.youku.com", "优酷"), "优酷", "youku");
        alias(new Known("tencentvideo", "https://v.qq.com", "腾讯视频"), "腾讯视频");
        alias(new Known("xiaohongshu", "https://www.xiaohongshu.com", "小红书"), "小红书", "xhs");
        alias(new Known("zhihu", "https://www.zhihu.com", "知乎"), "知乎", "zhihu");
        alias(new Known("steam", "https://store.steampowered.com", "Steam"), "steam");
        alias(new Known("mumu", "", "MuMu模拟器", "MuMuPlayer", "MuMu"), "mumu", "mumu模拟器", "网易mumu");
        alias(new Known("vscode", "https://vscode.dev", "Visual Studio Code"), "vscode", "vs code", "visual studio code");
        alias(new Known("kugou", "https://www.kugou.com", "酷狗音乐"), "酷狗", "酷狗音乐");
    }

    static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s·・]+", "");
    }

    /** 用户原话明确要网页。 */
    static boolean wantsWeb(String userText) {
        return userText != null && userText.matches("(?s).*(网页|网站|网址|浏览器里|浏览器打开|官网|web版|网页版).*");
    }

    /** 用户原话明确要本地应用。 */
    static boolean wantsApp(String userText) {
        return userText != null && userText.matches("(?s).*(客户端|本地应用|桌面版|应用程序|app打开|软件打开).*");
    }

    // ------------------------------------------------------------------ 习惯

    /** 习惯（配置 open_habits，JSON）：规范名 → {choice: app/web, app: 打开的应用名, at}。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Map<String, Object>> parseHabits(String json) {
        Map<String, Map<String, Object>> out = new TreeMap<>();
        if (json == null || json.isBlank()) return out;
        try {
            Object v = Json.parse(json);
            if (!(v instanceof Map)) return out;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (!(e.getValue() instanceof Map)) continue;
                Map<String, Object> h = (Map<String, Object>) e.getValue();
                String c = String.valueOf(h.get("choice"));
                if (!c.equals("app") && !c.equals("web")) continue;
                Map<String, Object> n = new TreeMap<>();
                n.put("choice", c);
                n.put("app", h.get("app") == null ? "" : String.valueOf(h.get("app")));
                n.put("at", h.get("at") instanceof Number ? h.get("at") : 0L);
                out.put(norm(e.getKey()), n);
                if (out.size() >= 200) break;
            }
        } catch (RuntimeException ignored) { }
        return out;
    }

    public static String formatHabits(Map<String, Map<String, Object>> m) {
        return Json.stringify(new TreeMap<String, Object>(m));
    }

    // ------------------------------------------------------------------ 选择（任务卡上点）

    /** 等用户在任务卡上选「本地应用 / 网页」。界面轮询 {@link #pending}，点了调 {@link #answer}。 */
    public static final class Choices {
        static final class P {
            final Map<String, Object> info;
            final CountDownLatch latch = new CountDownLatch(1);
            volatile String pick = "";
            volatile boolean remember = true;

            P(Map<String, Object> info) { this.info = info; }
        }

        private final Map<Integer, P> pending = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicInteger seq = new AtomicInteger(1);
        public volatile long timeoutMs = 120_000;

        /** 返回 {"app"|"web"|"", remember}；超时 / 取消返回 ""。 */
        public Object[] ask(String task, String target, String appName, String url) {
            int id = seq.getAndIncrement();
            Map<String, Object> info = new TreeMap<>();
            info.put("id", (long) id);
            info.put("task", task == null ? "" : task);
            info.put("target", target);
            info.put("app", appName);
            info.put("url", url);
            info.put("deadline", System.currentTimeMillis() + timeoutMs);
            P p = new P(info);
            pending.put(id, p);
            try {
                p.latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                pending.remove(id);
            }
            return new Object[] {p.pick, p.remember};
        }

        /**
         * [overnight-1119] 目标有歧义时的选择卡：问题 + 2–3 个候选（标题、说明）+「都不是，我来补充」。
         * 返回 "o0".."o2" = 选了第几个；"other" = 都不是；"" = 超时 / 取消。
         */
        public String askOptions(String task, String question, List<Map<String, Object>> options) {
            int id = seq.getAndIncrement();
            Map<String, Object> info = new TreeMap<>();
            info.put("id", (long) id);
            info.put("kind", "pick");
            info.put("task", task == null ? "" : task);
            info.put("question", question == null ? "" : question);
            List<Object> opts = new ArrayList<>();
            if (options != null) for (Map<String, Object> o : options) opts.add(new TreeMap<>(o));
            info.put("options", opts);
            info.put("deadline", System.currentTimeMillis() + pickTimeoutMs);
            P p = new P(info);
            pending.put(id, p);
            try {
                p.latch.await(pickTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                pending.remove(id);
            }
            return p.pick;
        }

        /** 选择卡等多久（候选要看一会儿，比「本地应用 / 网页」长）。 */
        public volatile long pickTimeoutMs = 10 * 60_000;

        /** 界面点了（只收本机界面上的点击：App 的 choice 接口按 trusted 输入过滤）。 */
        public boolean answer(int id, String pick, boolean remember) {
            P p = pending.get(id);
            if (p != null && "pick".equals(p.info.get("kind"))) {
                Object os = p.info.get("options");
                int n = os instanceof List ? ((List<?>) os).size() : 0;
                if (pick.equals("other") || pick.equals("cancel")) { p.pick = pick.equals("other") ? "other" : ""; p.latch.countDown(); return true; }
                if (pick.matches("o\\d") && Integer.parseInt(pick.substring(1)) < n) { p.pick = pick; p.latch.countDown(); return true; }
                return false;
            }
            if (p == null || !(pick.equals("app") || pick.equals("web") || pick.equals("cancel"))) return false;
            p.pick = pick.equals("cancel") ? "" : pick;
            p.remember = remember;
            p.latch.countDown();
            return true;
        }

        public List<Object> list() {
            List<Object> out = new ArrayList<>();
            for (P p : pending.values()) out.add(new TreeMap<>(p.info));
            return out;
        }

        public int cancelAll() {
            int n = 0;
            for (P p : pending.values()) { p.pick = ""; p.latch.countDown(); n++; }
            return n;
        }
    }

    // ------------------------------------------------------------------ 决定打开什么

    /** 决定：app = 找到的本地应用名（null = 没有）；url = 网页（"" = 没有）。返回 {"app", 名字} / {"web", 网址} / {"ask"} / {"none"}。 */
    static String[] decide(String key, String appFound, String url, String userText, String prefer, Map<String, Map<String, Object>> habits) {
        boolean hasApp = appFound != null && !appFound.isEmpty(), hasWeb = url != null && !url.isEmpty();
        if (hasWeb && ("web".equals(prefer) || wantsWeb(userText))) return new String[] {"web", url};
        if (hasApp && ("app".equals(prefer) || wantsApp(userText))) return new String[] {"app", appFound};
        Map<String, Object> h = habits == null ? null : habits.get(key);
        if (h != null) {
            if ("web".equals(h.get("choice")) && hasWeb) return new String[] {"web", url};
            if ("app".equals(h.get("choice")) && hasApp) return new String[] {"app", appFound};
        }
        if (hasApp && hasWeb) return new String[] {"ask"};
        if (hasApp) return new String[] {"app", appFound};
        if (hasWeb) return new String[] {"web", url};
        return new String[] {"none"};
    }

    /** 已知目标的规范名；不认识的用名字本身。 */
    static String key(String name) {
        Known k = ALIASES.get(norm(name));
        return k != null ? k.key : norm(name);
    }

    /** 找本地应用：已知目标按它的几个应用名逐个找，否则按名字找。resolver 返回找到的显示名或 null。 */
    static String findApp(String name, Function<String, String> resolver) {
        Known k = ALIASES.get(norm(name));
        List<String> tries = new ArrayList<>();
        if (k != null) tries.addAll(k.appNames);
        tries.add(name);
        for (String t : tries) {
            try {
                String r = resolver.apply(t);
                if (r != null && !r.isEmpty()) return r;
            } catch (RuntimeException ignored) { }
        }
        return null;
    }

    static String knownUrl(String name) {
        Known k = ALIASES.get(norm(name));
        return k == null ? "" : k.url;
    }
}
