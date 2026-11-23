package com.onesay.desktop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [fix-1123-a] 对话里一句话改 OneSay 自己的设置、或者跳到软件里的某一页。
 *
 * <ul>
 *   <li>跳页（「打开工作流页」「去设置里的外观」）：不改任何东西，直接切页面；</li>
 *   <li>改设置：只有下面 {@link #CATALOG} 里这几项（主题、配色包、按住空格说话、识别方式、投屏键盘、下载画质、任务时长、开机自动启动），
 *       每次都先过现有的审批链（{@link PcGuard#confirmLow}：弹确认卡；完全访问 / 已允许本次任务时直接做）；</li>
 *   <li>权限相关的（沙箱档、审批策略、对话权限、信任域名、白名单、安全插件、Key…）一律不让一句话改：只把那一页打开，让你自己点；</li>
 *   <li>手机交来的任务不能动电脑上 OneSay 的界面和设置（「禁止远程改 OneSay 设置」）。</li>
 * </ul>
 * 规则版（不调模型）认短句；长句交给模型，模型用同名工具 {@code app_control}，走的是同一套检查。
 */
public final class AppControl {

    /** 批准：detail 给人看；返回 null = 允许，否则是没批准的结果。 */
    public interface Approver { Actions.Result approve(String detail); }

    final Approver approver;
    /** 真正改设置（App 接到设置页同一个接口）：key, value → {ok, text}。 */
    final BiFunction<String, String, Map<String, Object>> setter;
    /** 现在的设置（给「本来就是」判断、列出现值用）。 */
    final Supplier<Map<String, Object>> current;
    /** 配色包列表（id、name、mode）。 */
    final Supplier<List<Object>> packs;
    /** 跳页：page, anchor。 */
    final Consumer<String[]> nav;

    public AppControl(Approver approver, BiFunction<String, String, Map<String, Object>> setter, Supplier<Map<String, Object>> current,
                      Supplier<List<Object>> packs, Consumer<String[]> nav) {
        this.approver = approver;
        this.setter = setter;
        this.current = current == null ? TreeMap::new : current;
        this.packs = packs == null ? ArrayList::new : packs;
        this.nav = nav == null ? x -> { } : nav;
    }

    // ------------------------------------------------------------------ 页面

    /** 一个能跳到的地方：页面 id、设置页里的分组锚点（可空）、给人看的名字、叫法。 */
    static final class Place {
        final String page, anchor, zh;
        final String[] names;
        /** 名字有歧义（「设置」「手机」「通知」…）：要说「…页」或者前面有「打开 / 去」才算。 */
        Place(String page, String anchor, String zh, String... names) { this.page = page; this.anchor = anchor; this.zh = zh; this.names = names; }
    }

    static final List<Place> PLACES = new ArrayList<>();
    static {
        PLACES.add(new Place("chat", "", "对话", "对话", "聊天"));
        PLACES.add(new Place("today", "", "今天（主页）", "今天", "主页", "首页"));
        PLACES.add(new Place("today", "home-pair", "主页 › 连接手机", "配对", "配对手机", "连接手机", "手机配对", "扫码配对", "配对码", "二维码"));
        PLACES.add(new Place("notes", "", "通知", "通知"));
        PLACES.add(new Place("events", "", "日程", "日程"));
        PLACES.add(new Place("todos", "", "待办", "待办"));
        PLACES.add(new Place("tickets", "", "票夹", "票夹"));
        PLACES.add(new Place("pickups", "", "取件", "取件"));
        PLACES.add(new Place("autos", "", "自动任务", "自动任务"));
        PLACES.add(new Place("shortcuts", "", "快捷指令", "快捷指令", "电脑快捷指令"));
        PLACES.add(new Place("workflows", "", "工作流", "工作流", "工作流画布"));
        PLACES.add(new Place("phone", "", "手机", "手机"));
        PLACES.add(new Place("mirror", "", "手机投屏", "手机投屏", "投屏"));
        PLACES.add(new Place("pc", "", "这台电脑", "这台电脑", "本机"));
        PLACES.add(new Place("settings", "", "设置", "设置", "onesay设置", "软件设置"));
        PLACES.add(new Place("settings", "set-aon", "设置 › 常驻与唤醒", "常驻与唤醒", "常驻", "网络唤醒", "唤醒"));
        PLACES.add(new Place("settings", "set-perm", "设置 › 权限与审批", "权限与审批", "权限", "审批", "沙箱档", "沙箱", "审批策略"));
        PLACES.add(new Place("settings", "set-chatperm", "设置 › 对话权限", "对话权限"));
        PLACES.add(new Place("settings", "set-permlog", "设置 › 权限审批记录", "权限审批记录", "审批记录"));
        PLACES.add(new Place("settings", "set-model", "设置 › 模型与密钥", "模型与密钥", "模型", "大模型", "密钥", "key", "apikey"));
        PLACES.add(new Place("settings", "set-voice", "设置 › 语音", "语音"));
        PLACES.add(new Place("settings", "set-act", "设置 › 替你操作", "替你操作", "可打开的程序", "共享文件夹"));
        PLACES.add(new Place("settings", "set-download", "设置 › 下载", "下载", "视频下载"));
        PLACES.add(new Place("settings", "set-look", "设置 › 外观", "外观", "主题", "配色"));
        PLACES.add(new Place("settings", "set-mcp", "设置 › MCP 连接器", "mcp", "mcp连接器", "连接器"));
        PLACES.add(new Place("settings", "set-plugins", "设置 › 安全插件", "安全插件", "插件"));
        PLACES.add(new Place("settings", "set-about", "设置 › 关于", "关于", "版本"));
    }

    static Place placeById(String page, String anchor) {
        for (Place p : PLACES) if (p.page.equals(page) && p.anchor.equals(anchor == null ? "" : anchor)) return p;
        return null;
    }

    /** 名字（规范化后）→ 地方；「设置里的外观」「外观设置」「工作流页」都认。认不出 null。 */
    static Place findPlace(String name) {
        String n0 = name == null ? "" : name.replaceFirst("^(onesay|软件|这个软件)(的|里的|里)?", "");
        for (Place p : PLACES) for (String x : p.names) if (x.equals(n0)) return p; // 先按原样找（「主页」本身带「页」）
        String n = n0;
        n = n.replaceFirst("^(onesay|软件|这个软件)(的|里的|里)?", "");
        n = n.replaceFirst("(页面|页|那一页|那页|界面|栏|部分|那块)$", "");
        boolean inSettings = false;
        Matcher m = Pattern.compile("^设置(里面的|里面|里的|里|中的|的)(.+)$").matcher(n);
        if (m.matches()) { n = m.group(2); inSettings = true; }
        if (!n.equals("设置") && n.endsWith("设置")) { n = n.substring(0, n.length() - 2); inSettings = true; }
        n = n.replaceFirst("(页面|页|那一页|那页|部分|那块|分组)$", "");
        if (n.isEmpty()) return null;
        for (Place p : PLACES) {
            if (inSettings && !p.page.equals("settings")) continue;
            for (String x : p.names) if (x.equals(n)) return p;
        }
        // 设置页的页面 id / 锚点 id（模型可能直接给）
        for (Place p : PLACES) if (p.anchor.isEmpty() ? p.page.equals(n) : p.anchor.equals(n)) return p;
        return null;
    }

    static final Pattern NAV = Pattern.compile(
            "^(?:帮我|请|麻烦|给我)?(打开|去|跳到|跳转到|切到|切换到|转到|进入|回到|显示|带我去|我要去|看看|看一下|打开一下)?(.+?)$");
    static final java.util.Set<String> NEED_PAGE = java.util.Set.of("下载", "视频下载", "本机", "版本", "关于", "插件", "唤醒", "常驻", "模型", "大模型", "密钥", "key", "apikey",
            "权限", "审批", "沙箱", "配色", "主题", "外观", "语音", "投屏", "聊天", "共享文件夹", "可打开的程序", "连接器");

    /** 「打开工作流页」「去设置里的外观」→ 地方；不是跳页 null。t = {@link Intent#normalize} 后的。 */
    static Place parseNav(String t) {
        if (t == null || t.isEmpty() || t.length() > 22) return null;
        Matcher m = NAV.matcher(t);
        if (!m.matches()) return null;
        String verb = m.group(1) == null ? "" : m.group(1);
        String rest = m.group(2);
        boolean pageWord = rest.matches(".*(页|页面|界面)$") || rest.matches("^设置(里|里的|里面|中的|的).+") || (rest.endsWith("设置") && rest.length() > 2);
        // 「手机」后面跟「设置」是手机系统设置，不是 OneSay 的页面
        if (rest.startsWith("手机") && !rest.startsWith("手机投屏") && !rest.matches("手机(页|页面|界面)")) return null;
        Place p = findPlace(rest);
        if (p == null) return null;
        if (verb.isEmpty() && !pageWord) return null;                       // 只说一个词（「工作流」）不算
        // 本身是常用词的（「打开下载」可能是下载文件夹、「打开投屏」可能是要开始投屏）：要说「…页 / …设置」才算
        if (!pageWord && NEED_PAGE.contains(rest)) return null;
        if (verb.matches("看看|看一下") && !pageWord) return null;             // 「看看日程」是要查内容，交给别的
        return p;
    }

    // ------------------------------------------------------------------ 设置

    /** 一项能一句话改的设置。 */
    static final class Item {
        final String key, zh, where;
        final String[] aliases;
        /** 值：id → 叫法（第一个是给人看的）。布尔项用 true / false。 */
        final Map<String, String[]> values = new LinkedHashMap<>();
        Item(String key, String zh, String where, String... aliases) { this.key = key; this.zh = zh; this.where = where; this.aliases = aliases; }
        Item v(String id, String... names) { values.put(id, names); return this; }
        boolean bool() { return values.containsKey("true") && values.size() == 2; }
        String valueZh(String id) { String[] n = values.get(id); return n == null ? id : n[0]; }
    }

    static final List<Item> CATALOG = new ArrayList<>();
    static {
        CATALOG.add(new Item("theme", "明暗", "set-look", "主题", "外观", "明暗", "颜色模式", "界面颜色", "深色模式", "浅色模式", "暗色模式", "夜间模式", "黑暗模式")
                .v("dark", "深色", "暗色", "黑色", "夜间", "暗黑", "黑暗", "dark")
                .v("light", "浅色", "亮色", "白色", "日间", "明亮", "light")
                .v("system", "跟随系统", "跟系统", "跟着系统", "自动"));
        CATALOG.add(new Item("theme_pack", "配色包", "set-look", "配色包", "配色", "主题包", "皮肤"));
        CATALOG.add(new Item("ptt", "按住空格说话", "set-voice", "按住空格说话", "空格说话", "按空格说话", "按住说话", "按键说话", "空格键说话")
                .v("true", "开").v("false", "关"));
        CATALOG.add(new Item("asr_pref", "识别方式", "set-voice", "识别方式", "语音识别", "识别")
                .v("auto", "先看云端", "自动", "先云端")
                .v("cloud", "仅云端", "只用云端", "云端", "在线")
                .v("local", "仅本机", "只用本机", "本机", "离线", "本地"));
        CATALOG.add(new Item("mirror_keyboard", "投屏键盘", "", "投屏键盘", "投屏的键盘")
                .v("sdk", "普通键盘", "普通", "默认")
                .v("uhid", "物理键盘", "物理", "uhid"));
        CATALOG.add(new Item("media_quality", "下载画质", "set-download", "下载画质", "视频画质", "画质", "清晰度", "下载清晰度")
                .v("best", "最高画质", "最高", "最好", "最佳", "最清楚")
                .v("1080", "1080p", "1080p", "1080")
                .v("720", "720p", "720p", "720"));
        CATALOG.add(new Item("chat_timeout_min", "任务总时长上限", "set-chatperm", "任务时长", "任务总时长", "任务时长上限", "总时长上限", "任务超时", "超时时长"));
        CATALOG.add(new Item("autostart", "开机自动启动", "set-perm", "开机自动启动", "开机自启动", "开机自启", "开机启动", "自动启动", "自启动")
                .v("true", "开").v("false", "关"));
    }

    /** 不让一句话改的（权限 / 安全 / 密钥）：叫法 → 去哪一块。 */
    static final Map<String, String> PROTECTED = new LinkedHashMap<>();
    static {
        for (String s : new String[] {"沙箱档", "沙箱", "审批策略", "完全访问", "工作区可写", "只读档", "权限档", "执行权限", "电脑权限", "权限"}) PROTECTED.put(s, "set-perm");
        for (String s : new String[] {"信任的网址", "信任网址", "信任域名", "信任的域名", "联网的域名", "联网域名", "可以联网的域名"}) PROTECTED.put(s, "set-perm");
        for (String s : new String[] {"对话权限", "免审批", "自动批准", "不用批准", "不用问我", "不再问", "自动放行", "记住的规则", "打开习惯"}) PROTECTED.put(s, "set-chatperm");
        for (String s : new String[] {"白名单", "应用白名单"}) PROTECTED.put(s, "set-perm");
        for (String s : new String[] {"安全插件", "拦截编码", "编码拦截", "隐藏命令", "永不提权", "提权", "自授权", "自批准", "付款最后一下"}) PROTECTED.put(s, "set-plugins");
        for (String s : new String[] {"apikey", "密钥", "key", "接口地址", "服务商"}) PROTECTED.put(s, "set-model");
    }

    static final Pattern OFF = Pattern.compile("关掉|关闭|关上|关了|停用|禁用|不要|取消|别开|^关|去掉");
    static final Pattern ON = Pattern.compile("打开|开启|启用|开起来|开开|开了|开一下|^开|恢复|用上");
    static final Pattern CHANGE = Pattern.compile("改|换|调|设|切|变|开|关|用|成|为|启|停|禁|放宽|允许|不要|取消|跟随|跟着|加|删|添|移除|去掉");
    /** 一句话里不止一件事。 */
    static final Pattern MULTI = Pattern.compile("然后|接着|并且|顺便|同时|再帮|再把|之后|以后再");

    /** 一句话改设置的请求（规则认出来的）。 */
    static final class SetReq {
        final Item item;      // null = 动的是受保护的项
        final String value;   // 认不出值时 null
        final String protectedName, protectedAnchor;
        SetReq(Item item, String value) { this.item = item; this.value = value; this.protectedName = null; this.protectedAnchor = null; }
        SetReq(String name, String anchor) { this.item = null; this.value = null; this.protectedName = name; this.protectedAnchor = anchor; }
    }

    /** 规范化后的短句 → 改设置请求；不像改设置返回 null。 */
    SetReq parseSet(String t) {
        if (t == null || t.isEmpty() || t.length() > 20) return null;
        if (!CHANGE.matcher(t).find() || MULTI.matcher(t).find()) return null; // 一句里还有别的事：交给模型
        if (!t.contains("onesay") && t.matches(".*(手机|安卓|android).*")) return null; // 说的是手机上的设置：交给手机那套
        // 先看动没动受保护的（「把沙箱档改成完全访问」「关掉安全插件」）：取最长的叫法
        String pk = null;
        for (String k : PROTECTED.keySet()) if (t.contains(k) && (pk == null || k.length() >= pk.length())) pk = k; // 一样长取后面的（更具体）
        if (pk != null) return new SetReq(pk, PROTECTED.get(pk));
        if (otherTarget(t)) return null; // 「让手机打开深色模式」「把 Windows 改成深色」：说的是别的东西的设置，不是 OneSay 的
        Item hit = null;
        String alias = "";
        for (Item it : CATALOG) for (String a : it.aliases) if (t.contains(a) && a.length() > alias.length()) { hit = it; alias = a; }
        if (hit == null) {
            // 只说了值：「切到深色」「换成浅色模式」
            if (t.matches(".*(切|换|改|调|变)(到|成|为)?(深色|浅色|暗色|亮色|黑色|白色|夜间|日间)(模式|主题|界面)?(吧|啊)?$")) hit = CATALOG.get(0);
            else return null;
        }
        String rest = alias.isEmpty() ? t : t.replace(alias, "#");
        String v = null;
        if (hit.key.equals("theme") && alias.endsWith("模式") && !alias.equals("颜色模式")) {
            // 「打开深色模式」= 深色；「关掉深色模式」= 跟随系统以外的另一边
            String side = alias.startsWith("浅") ? "light" : "dark";
            if (OFF.matcher(rest).find()) v = side.equals("dark") ? "light" : "dark";
            else v = side;
        } else if (hit.bool()) {
            boolean off = OFF.matcher(rest).find(), on = ON.matcher(rest).find();
            if (off && !on) v = "false";
            else if (on && !off) v = "true";
        } else if (hit.key.equals("theme_pack")) {
            v = packValue(rest);
        } else if (hit.key.equals("chat_timeout_min")) {
            Matcher m = Pattern.compile("(\\d{1,4})(个)?(分钟|分|小时|钟头)").matcher(rest);
            if (m.find()) {
                long n = Long.parseLong(m.group(1));
                if (m.group(3).startsWith("小") || m.group(3).startsWith("钟")) n *= 60;
                v = String.valueOf(n);
            }
        } else {
            String best = null;
            int bl = 0;
            for (Map.Entry<String, String[]> e : hit.values.entrySet()) for (String n : e.getValue()) if (rest.contains(n) && n.length() > bl) { best = e.getKey(); bl = n.length(); }
            v = best;
            if (v == null && hit.key.equals("theme")) {
                String pv = packValue(rest); // 「主题换成 Nord」：说的是配色包
                if (pv != null && !pv.isEmpty()) return new SetReq(CATALOG.get(1), pv);
            }
        }
        return new SetReq(hit, v);
    }

    /** [fix-1123-a 审计] 句子里点了别的对象（手机 / 安卓 / Windows / 电脑 / 系统），又没说 OneSay：不是改 OneSay 自己的设置。「跟随系统」这个值不算。 */
    static final Pattern OTHER_TARGET = Pattern.compile("手机|安卓|android|windows|win10|win11|电脑|系统|浏览器|微信");

    static boolean otherTarget(String t) {
        if (t.contains("onesay")) return false;
        return OTHER_TARGET.matcher(t.replaceAll("跟随系统|跟着系统|跟系统", "")).find();
    }

    /** 配色包叫法 → id（""= 不用配色包）；认不出 null。 */
    String packValue(String rest) {
        if (rest.matches(".*(默认|不用|关掉|去掉|取消|不要|原来的|原样).*")) return "";
        String best = null;
        int bl = 0;
        for (Object o : packs.get()) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> p = (Map<?, ?>) o;
            String id = String.valueOf(p.get("id")), name = Intent.normalize(String.valueOf(p.get("name")));
            for (String n : new String[] {id.toLowerCase(Locale.ROOT), name}) if (!n.isEmpty() && rest.contains(n) && n.length() > bl) { best = id; bl = n.length(); }
        }
        return best;
    }

    String packName(String id) {
        if (id == null || id.isEmpty()) return "默认配色";
        for (Object o : packs.get()) if (o instanceof Map && id.equals(String.valueOf(((Map<?, ?>) o).get("id")))) return String.valueOf(((Map<?, ?>) o).get("name"));
        return id;
    }

    static Item item(String keyOrName) {
        String k = keyOrName == null ? "" : Intent.normalize(keyOrName);
        for (Item it : CATALOG) if (it.key.equals(k)) return it;
        for (Item it : CATALOG) { if (Intent.normalize(it.zh).equals(k)) return it; for (String a : it.aliases) if (a.equals(k)) return it; }
        return null;
    }

    static String protectedAnchor(String keyOrName) {
        String k = keyOrName == null ? "" : Intent.normalize(keyOrName);
        switch (k) {
            case "pc_sandbox": case "pc_approval": case "pc_level": case "trusted_domains": case "net_allow": case "whitelist": return "set-perm";
            case "chat_preset": case "chat_rules": case "habits": return "set-chatperm";
            case "plugin_enabled": case "plugin_encoded": return "set-plugins";
            case "llm_key": case "asr_key": case "vision_key": case "llm_base": case "vision_base": case "llm_provider": return "set-model";
            default: break;
        }
        for (Map.Entry<String, String> e : PROTECTED.entrySet()) if (k.contains(e.getKey())) return e.getValue();
        return null;
    }

    // ------------------------------------------------------------------ 执行（规则和模型工具共用）

    /** 跳到一个地方。 */
    public Actions.Result open(Place p) {
        nav.accept(new String[] {p.page, p.anchor});
        Map<String, Object> d = new TreeMap<>();
        d.put("page", p.page);
        if (!p.anchor.isEmpty()) d.put("anchor", p.anchor);
        return Actions.Result.ok("打开了「" + p.zh + "」", d);
    }

    /** 受保护的项：不改，只把那一块打开。 */
    public Actions.Result refuseProtected(String name, String anchor) {
        Place p = placeById("settings", anchor);
        if (p != null) nav.accept(new String[] {p.page, p.anchor});
        return new Actions.Result(false, "「" + name + "」跟权限和安全有关，不能一句话改。" + (p == null ? "" : "我把「" + p.zh + "」打开了，") + "要改请你自己在那里点。",
                new TreeMap<>(Map.of("reason", "protected_setting")));
    }

    /** 改一项（已认出 item 和值）：本来就是 → 不动；否则先批准再改。 */
    public Actions.Result set(Item it, String value) {
        if (value == null) return Actions.Result.fail("没听出要把「" + it.zh + "」改成什么。" + hint(it));
        String v = value.trim();
        if (it.key.equals("chat_timeout_min")) {
            long n;
            try { n = Long.parseLong(v); } catch (NumberFormatException e) { return Actions.Result.fail("任务时长要说几分钟，比如「任务时长改成 30 分钟」"); }
            if (n < 5 || n > 1440) return Actions.Result.fail("任务时长只能是 5 分钟到 24 小时");
            v = String.valueOf(n);
        } else if (it.key.equals("theme_pack")) {
            if (!v.isEmpty() && !packIds().contains(v)) {
                String pv = packValue(Intent.normalize(v));
                if (pv == null) return Actions.Result.fail("没有叫「" + value + "」的配色包。现有：" + String.join("、", packNames()));
                v = pv;
            }
        } else if (!it.values.containsKey(v)) {
            String id = null;
            for (Map.Entry<String, String[]> e : it.values.entrySet()) for (String n : e.getValue()) if (Intent.normalize(v).equals(Intent.normalize(n))) id = e.getKey();
            if (id == null && it.bool()) id = v.matches("(?i)on|开|打开|开启|yes") ? "true" : v.matches("(?i)off|关|关闭|关掉|no") ? "false" : null;
            if (id == null) return Actions.Result.fail("「" + it.zh + "」只能改成：" + choices(it));
            v = id;
        }
        String shown = it.key.equals("theme_pack") ? packName(v) : it.key.equals("chat_timeout_min") ? v + " 分钟" : it.valueZh(v);
        Map<String, Object> cur = current.get();
        Object now = cur == null ? null : cur.get(it.key);
        boolean packOverrides = it.key.equals("theme") && cur != null && cur.get("theme_pack") != null && !String.valueOf(cur.get("theme_pack")).isEmpty();
        if (now != null && String.valueOf(now).equals(v) && !packOverrides) {
            Place p = placeById("settings", it.where);
            return Actions.Result.ok("「" + it.zh + "」本来就是" + shown + "，没动。" + (p == null ? "" : "在「" + p.zh + "」里能看到。"));
        }
        String detail = "改 OneSay 的设置：" + it.zh + " → " + shown + (packOverrides ? "（同时换回默认配色，配色包自己的明暗会盖过这一项）" : "");
        Actions.Result no = approver == null ? Actions.Result.fail("确认卡没接好，没改") : approver.approve(detail);
        if (no != null) return new Actions.Result(false, "没改「" + it.zh + "」：" + (no.text == null ? "没批准" : no.text), no.data);
        if (packOverrides) {
            Map<String, Object> r0 = setter.apply("theme_pack", "");
            if (r0 == null || !Boolean.TRUE.equals(r0.get("ok"))) return Actions.Result.fail("没改成：" + (r0 == null ? "没有结果" : r0.get("text")));
        }
        Map<String, Object> r = setter.apply(it.key, v);
        if (r == null || !Boolean.TRUE.equals(r.get("ok"))) return Actions.Result.fail("没改成：" + (r == null ? "没有结果" : r.get("text")));
        Map<String, Object> d = new TreeMap<>();
        d.put("setting", it.key);
        d.put("value", v);
        return Actions.Result.ok("「" + it.zh + "」改成" + shown + "了" + (packOverrides ? "（配色包换回了默认）" : "") + "。想改回来，跟我说一声，或者去「设置」里点。", d);
    }

    List<String> packIds() { List<String> l = new ArrayList<>(); for (Object o : packs.get()) if (o instanceof Map) l.add(String.valueOf(((Map<?, ?>) o).get("id"))); return l; }
    List<String> packNames() { List<String> l = new ArrayList<>(List.of("默认配色")); for (Object o : packs.get()) if (o instanceof Map) l.add(String.valueOf(((Map<?, ?>) o).get("name"))); return l; }

    String choices(Item it) {
        if (it.key.equals("theme_pack")) return String.join("、", packNames());
        if (it.key.equals("chat_timeout_min")) return "5 到 1440 分钟";
        List<String> l = new ArrayList<>();
        for (String id : it.values.keySet()) l.add(it.valueZh(id));
        return String.join(" / ", l);
    }

    String hint(Item it) {
        switch (it.key) {
            case "theme": return "比如「把主题改成深色」「主题跟随系统」";
            case "theme_pack": return "比如「配色换成 Nord」「配色包换回默认」";
            case "chat_timeout_min": return "比如「任务时长改成 30 分钟」";
            default: return it.bool() ? "比如「打开" + it.zh + "」「关掉" + it.zh + "」" : "可以改成：" + choices(it);
        }
    }

    /**
     * 规则版入口：短句是跳页 / 改设置就做掉，返回给人看的一句；不是返回 null（交给别的）。
     * remote = 手机交来的；modelOk = 大模型能用（认不全的交给它）。
     */
    public String handle(String text, boolean remote, boolean modelOk) {
        String t = Intent.normalize(text);
        Place p = parseNav(t);
        SetReq s = p == null ? parseSet(t) : null;
        if (s == null && p == null) return null;
        // 认出是哪项、没听出改成什么：有模型就交给模型（它能问、能用工具），没模型就说怎么说
        if (s != null && s.item != null && s.value == null && modelOk) return null;
        if (remote) return "手机交来的任务不能动电脑上 OneSay 的界面和设置，要在电脑上自己点。";
        if (p != null) return open(p).text + "。";
        if (s.item == null) return refuseProtected(s.protectedName, s.protectedAnchor).text;
        return set(s.item, s.value).text;
    }

    // ------------------------------------------------------------------ 模型工具

    public static final String TOOL = "app_control";

    public static Map<String, Object> toolDef() {
        Map<String, Object> p = new TreeMap<>();
        p.put("description", ChatAgent.prop("string", ChatAgent.DESC));
        p.put("action", ChatAgent.prop("string", "open_page = 把 OneSay 的界面切到某一页（page 写页面或设置分组的名字，如「工作流」「设置 › 外观」「权限与审批」）；"
                + "set = 改 OneSay 自己的一项设置（setting + value）；list = 列出能改的设置和现在的值、能跳的页面"));
        p.put("page", ChatAgent.prop("string", "open_page 用：页面名（对话 / 今天 / 通知 / 日程 / 待办 / 票夹 / 取件 / 自动任务 / 快捷指令 / 工作流 / 手机 / 手机投屏 / 这台电脑 / 设置），或设置里的分组（连接手机 / 常驻与唤醒 / 权限与审批 / 对话权限 / 模型与密钥 / 语音 / 替你操作 / 下载 / 外观 / MCP 连接器 / 安全插件 / 关于）"));
        p.put("setting", ChatAgent.prop("string", "set 用：明暗（theme：dark 深色 / light 浅色 / system 跟随系统）、配色包（theme_pack：配色包名字，空 = 默认）、按住空格说话（ptt：true / false）、"
                + "识别方式（asr_pref：auto / cloud / local）、投屏键盘（mirror_keyboard：sdk / uhid）、下载画质（media_quality：best / 1080 / 720）、任务总时长上限（chat_timeout_min：分钟数 5–1440）、开机自动启动（autostart：true / false）"));
        p.put("value", ChatAgent.prop("string", "set 用：改成什么"));
        return ChatAgent.fn(TOOL, "操作 OneSay 软件自己：切到某一页，或者改一项外观 / 语音之类的设置（改之前会弹确认卡）。"
                + "权限、审批、安全插件、信任域名、白名单、Key 这些不能用它改：用了只会把那一页打开，由用户自己点。不是改 Windows 的设置。", p, List.of("description", "action"));
    }

    /** 模型参数 → 规整后的参数（认不出返回 null）。 */
    static Map<String, Object> mapArgs(Map<String, Object> a) {
        String act = a.get("action") == null ? "" : String.valueOf(a.get("action")).trim().toLowerCase(Locale.ROOT);
        if (act.equals("open") || act.equals("nav") || act.equals("goto") || act.equals("navigate")) act = "open_page";
        if (act.equals("get") || act.equals("ls")) act = "list";
        if (!act.matches("open_page|set|list")) return null;
        Map<String, Object> out = new TreeMap<>();
        out.put("action", act);
        for (String k : new String[] {"page", "setting", "value"}) if (a.get(k) != null) out.put(k, ChatAgent.clip(String.valueOf(a.get(k)).trim(), 80));
        if (act.equals("open_page") && String.valueOf(out.getOrDefault("page", "")).isEmpty()) return null;
        if (act.equals("set") && String.valueOf(out.getOrDefault("setting", "")).isEmpty()) return null;
        return out;
    }

    /** 模型工具的一步。remote = 手机交来的任务。 */
    public Actions.Result run(Map<String, Object> a, boolean remote) {
        String act = String.valueOf(a.getOrDefault("action", ""));
        if (remote && !act.equals("list")) return new Actions.Result(false, "手机交来的任务不能动电脑上 OneSay 的界面和设置", new TreeMap<>(Map.of("reason", "remote_app_control")));
        switch (act) {
            case "list": return list();
            case "open_page": {
                String raw = String.valueOf(a.getOrDefault("page", ""));
                Place p = findPlace(Intent.normalize(raw.replace("›", "里的").replace(">", "里的")));
                if (p == null) {
                    String n = Intent.normalize(raw);
                    String[] parts = raw.split("[›>/]");
                    if (parts.length > 1) p = findPlace(Intent.normalize(parts[parts.length - 1]));
                    if (p == null) p = findPlace(n.replaceFirst("^设置", ""));
                }
                return p == null ? Actions.Result.fail("OneSay 里没有叫「" + raw + "」的页面") : open(p);
            }
            case "set": {
                String k = String.valueOf(a.getOrDefault("setting", ""));
                String pa = protectedAnchor(k);
                Item it = item(k);
                if (it == null && pa != null) return refuseProtected(k, pa);
                if (it == null) return Actions.Result.fail("「" + k + "」不能在对话里改。能改的：" + catalogZh() + "；别的请在设置页里自己改（可以让我把那一页打开）");
                String v = String.valueOf(a.getOrDefault("value", ""));
                if (it.key.equals("theme_pack") && !v.isEmpty() && !packIds().contains(v)) {
                    String pv = packValue(Intent.normalize(v));
                    v = pv == null ? v : pv;
                }
                return set(it, v);
            }
            default: return Actions.Result.fail("不认识的动作");
        }
    }

    static String catalogZh() { List<String> l = new ArrayList<>(); for (Item it : CATALOG) l.add(it.zh); return String.join("、", l); }

    Actions.Result list() {
        Map<String, Object> cur = current.get();
        StringBuilder b = new StringBuilder("能在对话里改的设置（改之前会弹确认卡）：\n");
        for (Item it : CATALOG) {
            Object now = cur == null ? null : cur.get(it.key);
            String nz = now == null ? "" : it.key.equals("theme_pack") ? packName(String.valueOf(now)) : it.key.equals("chat_timeout_min") ? now + " 分钟" : it.valueZh(String.valueOf(now));
            b.append("- ").append(it.zh).append("（").append(it.key).append("）：现在 ").append(nz.isEmpty() ? "未知" : nz).append("；可选 ").append(choices(it)).append('\n');
        }
        b.append("不能在对话里改：权限与审批、对话权限、信任域名、白名单、安全插件、Key（只能打开那一页让用户自己点）。\n能跳的页面：");
        List<String> ps = new ArrayList<>();
        for (Place p : PLACES) ps.add(p.zh);
        b.append(String.join("、", ps));
        return Actions.Result.ok(b.toString());
    }
}
