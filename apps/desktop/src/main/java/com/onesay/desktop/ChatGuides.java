package com.onesay.desktop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * [longctx-1123] 对话的「做法准则」按需加载。
 *
 * <p>以前系统提示一次写全所有做法（下载、提醒、手机、看屏幕、打开程序、查网页、日期类信息…），长会话里这些和眼下的话题无关的大段文字
 * 一直压在最前面，真要用的那条反而被冲淡。现在系统提示只留核心（身份、工具清单、权限、安全、结果标记、怎么接着做），
 * 每条准则按需加进来：用户这句话的说法沾边就加；模型第一次用到某类工具时，把那条准则跟着工具结果送过去。
 * 这一段对话里加载过的准则记成「已加载清单」（存在任务卡和换段标记上），上下文被截短、自动换段之后照样重新加进来，不会丢。
 *
 * <p>老的 {@link ChatAgent#systemPrompt} 几个重载（不带清单）仍然给全量，行为不变。
 */
final class ChatGuides {
    private ChatGuides() {}

    static final class G {
        final String id, zh, text;
        final Pattern words;
        G(String id, String zh, String words, String text) { this.id = id; this.zh = zh; this.words = Pattern.compile(words, Pattern.CASE_INSENSITIVE); this.text = text; }
    }

    static final List<G> ALL = new ArrayList<>();
    static {
        ALL.add(new G("timely", "时效信息和日期", "日期|几号|哪天|星期|周[一二三四五六日天]|活动|展会|比赛|考试|签证|入境|政策|开放时间|门票|票价|最新|版本|航班|车次|高铁|火车|机票|出差|旅行|旅游|行程|攻略|规划|计划|安排|报名|截止|马拉松|演唱会",
                // [preview-1120 真机综合测试 3] 出行 / 活动 / 政策类：先查后问、时效信息核验、日期对照今天
                "问到会变的公开信息（活动 / 展会 / 比赛 / 考试的日期和地点、签证与入境政策、开放时间、票价、最新版本）时，先联网查官方或权威来源再答，答案里写明来源和查到的日期；查不到就明说「没查到 / 未核实」，不要凭印象给具体日期。"
                        + "拿用户说的日期和现在的日期对照：已经过去、星期对不上、活动查不到或地名对不上，先指出来再往下做。"
                        + "规划类请求缺偏好时，按常见情况假设并写明，先给一版能用的方案，最后最多问 1–2 个关键问题，不要一上来列一串问题。\n"
                        // [fix-1120 晚] 推测的日期不能当事实写进计划
                        + "日期没查到官方来源时：计划里写「日期待确认」，不要写一个具体的推测日期当安排的依据（可以说「往年一般在几月上 / 中旬」，并注明是往年情况）；"
                        + "按它排的安排写成相对的（如「比赛日（待确认）前一天领物」），并给出核实入口（官网 / 官方公众号 / 官方 App 的名字或网址）。\n"));
        ALL.add(new G("web", "查网页", "网页|网站|官网|查一下|查查|搜|链接|https?://|新闻|资料|公告",
                "你自己要看网页内容时用 run_command 的 Invoke-WebRequest（-UseBasicParsing）读出文字；open_url 只是在用户的浏览器里打开页面，你看不到，不要靠打开后截图来读网页。\n"));
        ALL.add(new G("reminder", "提醒和日程", "提醒|闹钟|日程|叫我|记得|到点|定时|备忘|待办|考试|出差|比赛|预约|体检|车次|航班|会议|开会",
                // [fix-1120 晚] 电脑本机的提醒
                "提醒 / 日程用 reminder 工具：提醒建在这台电脑上，到点由 OneSay 在电脑上弹通知，不需要手机，手机没连不是不建提醒的理由；不要用 schtasks / Register-ScheduledTask 建 Windows 计划任务来提醒。"
                        + "用户说「提醒我」就直接建（时间按今天的日期算准，写进 at）；规划有明确时间的事（考试、出差、比赛、预约、体检、车次、航班）收尾时，主动建关键时间点的提醒（如前一晚、出发前），或在答复里一句话提议要不要建。"
                        + "日期是推测的（没查到官方）时，date_verified 填 false：提醒会标「待确认」；也可以建成「去核实 XX 日期」的提醒。"
                        + "把东西发到手机是另一件事（phone_* 工具）：手机没连就如实说「没发到手机」，但电脑上的提醒照样建，答复里两件事分开说。\n"));
        ALL.add(new G("download", "下载和安装", "下载|安装|装个|装一个|装上|装一下|安装包|更新|升级|\\.exe|\\.msi|\\.zip|\\.apk|setup|游戏",
                "下载文件用 download 工具（任务卡上有进度条，用户能取消），不要用 Invoke-WebRequest / curl 下载大文件。"));
        ALL.add(new G("open", "打开程序和网站", "打开|开一下|启动|运行|进入|b站|哔哩|微信|网站|客户端",
                "用户要「打开某个程序 / 网站」（如打开 B 站、微信）用 open_target：先用本地应用、没有再用网页，用户说了网页 / 网站就填 prefer=web；打开某个具体网址（搜索结果、下载页）用 open_url。\n"));
        ALL.add(new G("phone", "手机", "手机|安卓|android|模拟器|mumu|adb|短信|快捷指令|发到|传到",
                "手机：用户的手机通过 OneSay 手机联动和这台电脑配对，phone_status / phone_open_app / phone_operate 就是通过这条链路操作手机的。"
                        + "不要说你没法控制手机：先看下面的「运行环境」里有没有连着的手机，没有就照那里的说法告诉用户怎么连上；phone_operate 被手机拒（没打开这一项）时告诉用户在手机上打开。"
                        + "模拟器或开了 USB / 无线调试的手机也可以用 adb（run_command）。\n"));
        ALL.add(new G("screen", "看屏幕和点界面", "截图|截屏|屏幕|界面|弹窗|按钮|root|授权|勾选|点一下|画面|窗口|看一眼",
                "看屏幕（这台电脑，或 adb 连着的模拟器 / 手机）用 screenshot：结果写明是看图模型的描述还是 OCR 读出的文字；OCR 只有文字，看不出图标、颜色和布局，拿不准就如实说。"
                        + "安卓界面上的按钮 / 复选框（如 root 授权弹窗）用 android_ui：先 dump 看有哪些，再按文字 tap_text / check_text，不要硬编码 input tap 坐标；"
                        + "要「记住选择」的弹窗先 check_text 勾上再点允许（then_tap）。点完再 dump 或截图确认。"
                        + "不要用截图文件的大小、修改时间去猜屏幕上是什么。截图和你生成的文件会显示在任务卡里；要给用户看某个文件用 show_file，不要用 open_url 打开 file: 地址。\n"
                        + "截图结果里写着「截到的不是目标画面」或「没法确认」时，不能说「这张就是…」，要先把目标画面调出来重截，或如实说没截到。\n"));
    }

    static G get(String id) { for (G g : ALL) if (g.id.equals(id)) return g; return null; }

    static Set<String> allIds() {
        Set<String> s = new LinkedHashSet<>();
        for (G g : ALL) s.add(g.id);
        return s;
    }

    /** 这句话沾边的准则。 */
    static Set<String> byWords(String text) {
        Set<String> s = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return s;
        for (G g : ALL) if (g.words.matcher(text).find()) s.add(g.id);
        return s;
    }

    /** 用到这一步（内部动作名 + 命令）该配的准则；没有返回 null。 */
    static String byStep(String cmd, String command) {
        if (cmd == null) return null;
        if (cmd.equals("net_download")) return "download";
        if (cmd.equals("reminder")) return "reminder";
        if (cmd.startsWith("phone_")) return "phone";
        if (cmd.equals("screen_look") || cmd.equals("screenshot") || cmd.equals("android_ui") || cmd.equals("show_file")) return "screen";
        if (cmd.equals("open_url") || cmd.equals("open_target") || cmd.equals("open_app")) return "open";
        if (cmd.equals("sys_exec") && command != null) {
            if (command.matches("(?is).*\\b(invoke-webrequest|iwr|curl|wget|start-bitstransfer)\\b.*")) return command.matches("(?is).*(-outfile|-o\\s|>\\s*\\S+\\.(exe|msi|zip|apk)).*") ? "download" : "web";
            if (command.matches("(?is).*\\badb\\b.*")) return "phone";
            if (command.matches("(?is).*\\b(schtasks|register-scheduledtask)\\b.*")) return "reminder";
        }
        return null;
    }

    /** 拼准则正文（按 ALL 的顺序；ids 为 null = 全部）。 */
    static String text(Collection<String> ids) {
        StringBuilder b = new StringBuilder();
        for (G g : ALL) if (ids == null || ids.contains(g.id)) b.append(g.text);
        return b.toString();
    }

    /** 中文名列表（给模型看的已加载清单）。 */
    static String names(Collection<String> ids) {
        List<String> l = new ArrayList<>();
        for (G g : ALL) if (ids != null && ids.contains(g.id)) l.add(g.zh);
        return String.join("、", l);
    }

    /** 核心提示里的一句索引：没加载的准则用到时会补上。 */
    static String indexLine(Collection<String> loaded) {
        List<String> l = new ArrayList<>();
        for (G g : ALL) if (loaded == null || !loaded.contains(g.id)) l.add(g.zh);
        if (l.isEmpty()) return "";
        return "专门的做法（" + String.join("、", l) + "）用到时 OneSay 会把那一条准则补进来（可能跟着工具结果一起给你）；没给的照工具说明做。"
                + "下载文件用 download 工具，提醒用 reminder 工具，打开程序 / 网站用 open_target。\n";
    }
}
