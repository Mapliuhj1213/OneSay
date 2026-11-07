package com.onesay.assistant.v2.island;

import org.json.JSONObject;

/**
 * ★ 11-04 ①（用户：灵动岛只显示「收到我来弄 / 还在弄 / 快好了」套话，不显示每一步）：工具调用 → 岛上一行短句（纯函数，JVM 可测）。
 *
 * <p>根因：AgentLoop 的 web.search / web.read / research.spawn、读屏 / 记忆等本地工具在循环内执行（{@code AgentLoop.exec}），
 * 根本不经过宿主的执行回调，宿主里唯一的 {@code S1Hooks.onTaskStep("正在：" + summary)} 一次都没被调用；岛上只剩语音字幕（套话）。
 * 紧凑态又只取当前步骤前 4 个字（「搜索：二」），看不出在干什么。
 *
 * <p>文案：「搜索：二手摩托车 价格」「读取：摩托范」「调研：…」「比价：粉象 17 条」「整理结论」；失败如实写「搜索失败：…」「读取失败：携程」。
 * 截断只在词边界（空格 / 、 / ， / ·）切，不加省略号、不切断英文单词或数字；一个词都放不下才按字切（中文）。
 */
public final class IslandStepText {

    private IslandStepText() {}

    /** 岛上一行最多多少「字宽」（汉字 1、ASCII 0.5）。 */
    public static final int MAX_UNITS = 14;

    public static final String CONCLUDE = "整理结论";

    /** 工具调用开始时的一行。认不出的工具用能力摘要（summary）。 */
    public static String forTool(String sign, JSONObject args, String summary) {
        String s = sign == null ? "" : sign;
        JSONObject a = args == null ? new JSONObject() : args;
        String t;
        if (s.equals("web.search")) {
            t = "搜索：" + squash(a.optString("query", ""));
        } else if (s.equals("web.read")) {
            t = "读取：" + siteName(a.optString("url", ""));
        } else if (s.equals("research.spawn")) {
            t = "调研：" + squash(a.optString("question", ""));
        } else if (s.equals("clock.alarm.set")) {   // ★ 11-04 午：快路径单步也显示真实动作
            int h = a.optInt("hour", -1), m = a.optInt("minute", 0);
            t = h >= 0 ? "设闹钟：" + h + ":" + (m < 10 ? "0" : "") + m : "设闹钟";
        } else if (s.equals("mail.inbox.latest")) {
            t = a.optLong("uid", 0L) > 0 || a.optString("uid", "").length() > 0 || a.optString("keyword", "").length() > 0
                    ? "读邮件：" + squash(a.optString("keyword", "")) : "读邮件";
            if (t.endsWith("：")) t = "读邮件";
        } else if (s.equals("memory.search")) {
            t = "查记忆";
        } else if (s.startsWith("screen.") || s.startsWith("vision.") || s.equals("ui.screenshot")) {
            t = "读屏幕";
        } else if (s.equals("phone.operate")) {
            String app = a.optString("app", "");
            t = app.length() > 0 ? "操作：" + appLabel(app) : "操作手机";
        } else if (s.equals("app.launch")) {
            String app = a.optString("app", a.optString("package", a.optString("name", "")));
            t = app.length() > 0 ? "打开：" + appLabel(app) : "打开应用";
        } else if (s.startsWith("probe.apps")) {
            String kw = a.optString("keyword", "");
            t = kw.length() > 0 ? "查应用：" + kw : "查已装应用";
        } else if (s.startsWith("probe.")) {
            t = "查看本机";
        } else if (s.equals("ask_user")) {
            t = "等你选择";
        } else {
            String sum = summary == null || summary.trim().length() == 0 ? s : summary.trim();
            t = IslandModel.stripDoing(sum);
        }
        return fit(t, MAX_UNITS);
    }

    /** 失败如实显示：「搜索：X」→「搜索失败：X」；没有冒号 →「X 没成功」。 */
    public static String failed(String startText) {
        String s = startText == null ? "" : startText.trim();
        if (s.length() == 0) return "这一步没成功";
        int k = s.indexOf('：');
        if (k > 0 && k <= 4) return fit(s.substring(0, k) + "失败" + s.substring(k), MAX_UNITS);
        return fit(s + " 没成功", MAX_UNITS);
    }

    /** 是不是工具步骤文案（「动词：对象」，冒号在前 4 个字以内）。 */
    public static boolean isToolStep(String s) {
        if (s == null) return false;
        int k = s.indexOf('：');
        return k > 0 && k <= 5 && k < s.length() - 1;
    }

    static String squash(String s) {
        return s == null ? "" : s.replaceAll("[\\s\\u3000]+", " ").replaceAll("[\"“”]", "").trim();
    }

    static String appLabel(String app) {
        String a = app.trim();
        int p = a.indexOf('（');
        if (p > 0) return a.substring(0, p);
        p = a.indexOf('(');
        if (p > 0) return a.substring(0, p);
        if (a.matches("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+")) {
            String last = a.substring(a.lastIndexOf('.') + 1);
            return last;
        }
        return a;
    }

    private static final String[][] SITES = {
            {"58moto", "摩托范"}, {"motofan", "摩托范"}, {"smzdm", "什么值得买"}, {"manmanbuy", "慢慢买"}, {"fenxianglife", "粉象比价"},
            {"jd.com", "京东"}, {"tmall", "天猫"}, {"taobao", "淘宝"}, {"yangkeduo", "拼多多"}, {"pinduoduo", "拼多多"},
            {"58.com", "58同城"}, {"ganji", "赶集"}, {"goofish", "闲鱼"}, {"xianyu", "闲鱼"}, {"zhihu", "知乎"}, {"baidu", "百度"},
            {"eastmoney", "东方财富"}, {"sina", "新浪"}, {"163.com", "网易"}, {"cls.cn", "财联社"}, {"qq.com", "腾讯"},
            {"autohome", "汽车之家"}, {"dongchedi", "懂车帝"}, {"bilibili", "B站"}, {"weibo", "微博"}, {"ctrip", "携程"}, {"trip.com", "携程"},
            {"meituan", "美团"}, {"dianping", "大众点评"}, {"fliggy", "飞猪"}, {"ly.com", "同程"}, {"klook", "客路"}, {"cngold", "金投网"},
            {"jin10", "金十数据"}, {"chinadaily", "中国日报"}, {"xinhuanet", "新华网"}, {"people.com", "人民网"}, {"thepaper", "澎湃"},
            {"36kr", "36氪"}, {"ithome", "IT之家"}, {"sohu", "搜狐"}, {"ifeng", "凤凰网"}, {"hupu", "虎扑"}, {"douban", "豆瓣"},
            {"xiaohongshu", "小红书"}, {"wikipedia", "维基百科"}, {"cfmoto", "春风摩托"}, {"haojue", "豪爵"},
    };

    /** 网址 → 站点名（认识的写中文名，否则主域名）。 */
    public static String siteName(String url) {
        String u = url == null ? "" : url.trim().toLowerCase(java.util.Locale.ROOT);
        if (u.length() == 0) return "网页";
        String host = u.replaceFirst("^[a-z]+://", "");
        int sl = host.indexOf('/');
        if (sl >= 0) host = host.substring(0, sl);
        int q = host.indexOf('?');
        if (q >= 0) host = host.substring(0, q);
        for (String[] kv : SITES) if (host.contains(kv[0])) return kv[1];
        host = host.replaceFirst("^(www|m|wap|mobile)\\.", "");
        return host.length() == 0 ? "网页" : host;
    }

    static double units(char ch) {
        return ch < 0x2E80 ? 0.5 : 1.0;
    }

    static double units(String s) {
        double u = 0;
        for (int i = 0; i < s.length(); i++) u += units(s.charAt(i));
        return u;
    }

    /**
     * 截到 maxUnits 以内：保留「动词：」前缀，后面按词（空格 / 、 / ， / · / |）整词保留；
     * 第一个词就放不下时：中文按字切，英文 / 数字串整串丢掉（不出半个单词），绝不加省略号。
     */
    public static String fit(String text, double maxUnits) {
        String s = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        if (units(s) <= maxUnits) return s;
        String head = "";
        String body = s;
        int k = s.indexOf('：');
        if (k > 0 && k <= 4) {
            head = s.substring(0, k + 1);
            body = s.substring(k + 1).trim();
        }
        double left = maxUnits - units(head);
        if (left <= 0) return head.length() > 0 ? head.substring(0, head.length() - 1) : s.substring(0, (int) Math.max(1, maxUnits));
        String[] words = body.split("(?<=[ 、，,·|/])|(?=[ 、，,·|/])");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (units(sb.toString() + w) > left) break;
            sb.append(w);
        }
        String out = sb.toString().replaceAll("[ 、，,·|/]+$", "").trim();
        if (out.length() == 0) {
            // 第一个词就放不下：中文逐字截（不切 ASCII 串）
            StringBuilder c = new StringBuilder();
            double u = 0;
            for (int i = 0; i < body.length(); i++) {
                char ch = body.charAt(i);
                if (u + units(ch) > left) break;
                c.append(ch);
                u += units(ch);
            }
            out = c.toString();
            // 截断点落在 ASCII 字母数字串中间 → 整串退掉
            if (out.length() < body.length() && out.length() > 0 && isAlnum(out.charAt(out.length() - 1)) && isAlnum(body.charAt(out.length()))) {
                int j = out.length();
                while (j > 0 && isAlnum(out.charAt(j - 1))) j--;
                out = out.substring(0, j);
            }
            out = out.trim();
        }
        if (out.length() == 0) return head.length() > 0 ? head.substring(0, head.length() - 1) : "";
        return head + out;
    }

    /**
     * ★ 11-04 午（展开岛「…」遗留）：多行段落放不下时，在最后一行 [lineStart, lineEnd) 内找切点 —— 优先句读 / 空格之后，
     * 其次中文字与字之间；绝不切断英文单词 / 数字串，也不加省略号。返回保留的前缀（去尾部空白和连接符）。
     */
    public static String cutAt(String text, int lineStart, int lineEnd) {
        if (text == null) return "";
        int end = Math.max(0, Math.min(lineEnd, text.length()));
        int start = Math.max(0, Math.min(lineStart, end));
        if (end >= text.length()) return text;
        // 最后一行里最后一个句读 / 空格（切在它后面），且不早于本行一半
        int best = -1;
        for (int i = end - 1; i >= start; i--) {
            char ch = text.charAt(i);
            if ("。！？；，、：,.;:!? ）)】」』\n".indexOf(ch) >= 0) { best = i + 1; break; }
        }
        int cut;
        if (best > start && best - start >= (end - start) / 2) cut = best;
        else {
            cut = end;
            // 落在 ASCII 词中间 → 退到词首
            while (cut > start && isAlnum(text.charAt(cut - 1)) && cut < text.length() && isAlnum(text.charAt(cut))) cut--;
            if (cut == start) cut = best > start ? best : end;
        }
        return text.substring(0, cut).replaceAll("[\\s、，,·|/：:]+$", "");
    }

    static boolean isAlnum(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '.' || c == '%';
    }
}
