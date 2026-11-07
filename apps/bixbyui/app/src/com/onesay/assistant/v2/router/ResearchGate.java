package com.onesay.assistant.v2.router;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ fix/shop-research（RESEARCH-TEST-basilisk R2 / R4 / R5）：AgentLoop 里购物 / 比价类任务的
 * ① 识别、② 候选工具过滤（不给亮度 / 音量这类无关工具）、③ 收工门槛（证据够不够）、④ 提议校验（不提做不到的动作）。
 * 纯 Java（JVM 可测），Java 7 语法。
 */
public final class ResearchGate {

    private ResearchGate() {}

    /** SHOP = 购物 / 比价；RESEARCH = 行情 / 市场 / 走势 / 对比 / 二手价这类要拆维度、给数字和例子的调研（★ fix/research-depth）。 */
    public enum Kind { NONE, SHOP, RESEARCH }

    // ------------------------------------------------------------------ ① 识别

    private static final Pattern SHOP_CUE = Pattern.compile(
            "比价|比比价|最便宜|哪家便宜|哪里买|在哪买|到手价|券后|多少钱|什么价|价格|报价|优惠|促销|加购物车|网购|网上买|"
                    + "(?:京东|淘宝|天猫|拼多多|苏宁|亚马逊).{0,12}(?:买|价|卖|便宜|下单)|(?:买|价|便宜).{0,12}(?:京东|淘宝|天猫|拼多多)");
    private static final Pattern NOT_SHOP = Pattern.compile("股价|股票|基金|汇率|油价|房价|金价|币价|比特币|话费|电费|水费|快递|物流|订单(?:到|状态)");

    /** ★ fix/research-depth：行情 / 市场 / 走势 / 二手价 / 客单价 / 对比类（用户 11-03 12:04：调研普遍太浅）。 */
    private static final Pattern MARKET = Pattern.compile(
            "行情|走势|趋势|市场|股市|大盘|美股|港股|A股|a股|欧股|日股|涨跌|板块|均价|客单价|成交价|回收价|"
                    + "二手.{0,16}(?:价|行情|多少钱|值多少)|横评|对比一下|做个?对比|哪个更值|优缺点对比");
    private static final Pattern NOT_RESEARCH = Pattern.compile("天气|气温|几点|日期|星期几|闹钟|提醒|音量|亮度|设置|打开|关掉|关闭|发给|发微信|短信|打电话");

    public static Kind kindOf(String query) {
        if (query == null) return Kind.NONE;
        String q = TaskContinuation.strip(query);
        if (com.onesay.assistant.v2.errand.ErrandIntent.isBuy(q)) return Kind.SHOP;
        if (q.length() >= 4 && MARKET.matcher(q).find() && !NOT_RESEARCH.matcher(q).find()) return Kind.RESEARCH;
        if (NOT_SHOP.matcher(q).find()) return Kind.NONE;
        if (SHOP_CUE.matcher(q).find()) return Kind.SHOP;
        return Kind.NONE;
    }

    // ------------------------------------------------------------------ ② 候选工具

    private static final String[] SHOP_IRRELEVANT = {
            "settings.brightness", "settings.autobrightness", "volume.", "torch.", "settings.rotation", "settings.screentimeout",
            "settings.aod", "settings.ambient", "settings.haptic", "settings.autotime",
            "connectivity.", "battery.saver", "alarm.", "clock.", "sys.reboot", "sys.poweroff", "sys.wipe", "notes.new",
    };

    /** 购物句里不该出现的工具（亮度 / 音量 / 开关 / 闹钟 / 关机…）。 */
    public static boolean irrelevantForShop(String sign) {
        if (sign == null) return false;
        for (String p : SHOP_IRRELEVANT) if (sign.startsWith(p)) return true;
        return false;
    }

    /** 购物域兜底：要补进候选的注册表能力（web.search / web.read / research.spawn 是常驻工具，由 LoopTools 追加）。 */
    public static final String[] SHOP_FALLBACK_SIGNS = {"browser.open", "app.launch"};

    // ------------------------------------------------------------------ ③ 证据与收工门槛

    /** 本任务拿到的证据（只看成功的 web.read / research.spawn / web.search）。 */
    public static final class Evidence {
        public int reads;
        public int searches;
        public final Set<String> readHosts = new LinkedHashSet<String>();
        public final Set<String> searchHosts = new LinkedHashSet<String>();

        public void onStep(String sign, JSONObject args, String observation, boolean ok) {
            if (sign == null || !ok) return;
            if (com.onesay.assistant.v2.agent.WebTools.SIGN_READ.equals(sign)) {
                reads++;
                String h = host(args != null ? args.optString("url", "") : "");
                if (h.length() > 0) readHosts.add(h);
            } else if (com.onesay.assistant.v2.agent.WebTools.SIGN_RESEARCH.equals(sign)) {
                reads++;
                collectHosts(observation, readHosts);
            } else if (com.onesay.assistant.v2.agent.WebTools.SIGN_SEARCH.equals(sign)) {
                searches++;
                collectHosts(observation, searchHosts);
            }
        }

        public void onSteps(List<AgentLoop.Step> steps) {
            if (steps == null) return;
            for (AgentLoop.Step st : steps) onStep(st.tool, st.args, st.observation, st.ok);
        }

        /** 终稿里引用到的站点 ∪ 读过原文的站点。 */
        public Set<String> sites(String answer) {
            Set<String> s = new LinkedHashSet<String>(readHosts);
            Set<String> cited = new LinkedHashSet<String>();
            collectHosts(answer, cited);
            for (String h : cited) s.add(h);
            return s;
        }
    }

    public static final int MIN_SITES = 2;
    public static final int MIN_READS = 1;

    private static final Pattern PRICE = Pattern.compile("(?:[¥￥]\\s*\\d+(?:\\.\\d+)?)|(?:\\d+(?:\\.\\d+)?\\s*(?:元|块))");
    private static final Pattern SOURCE_CUE = Pattern.compile("来源|\\[\\d+\\]|https?://|\\b[a-z0-9\\-]+\\.(?:com|cn|net)\\b", Pattern.CASE_INSENSITIVE);

    public static boolean hasPrice(String answer) {
        return answer != null && PRICE.matcher(answer).find();
    }

    public static int priceCount(String answer) {
        if (answer == null) return 0;
        Matcher m = PRICE.matcher(answer);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    public static boolean hasSource(String answer) {
        return answer != null && SOURCE_CUE.matcher(answer).find();
    }

    /** 证据不够（站点 < 2 或没读过原文）。 */
    public static boolean evidenceShort(Evidence ev, String answer) {
        if (ev == null) return true;
        return ev.reads < MIN_READS || ev.sites(answer).size() < MIN_SITES;
    }

    /** 有来源但没价格。 */
    public static boolean sourceWithoutPrice(String answer) {
        return hasSource(answer) && !hasPrice(answer);
    }

    public static final String SHOP_EVIDENCE_NUDGE = "【系统提醒】这是购物 / 比价任务，现在的证据还不够收工：至少要有 2 个不同站点的价格，"
            + "并且至少用 web.read 读过 1 个原网页核价（搜索摘要不算核价）。请继续：①web.search 分别搜「商品名 京东 价格」「商品名 天猫 价格」"
            + "（也可以搜比价 / 历史价格网站）；②挑价格明确的结果 web.read 读原文；③用户没说版本就列出各版本、默认推荐一款并说理由。"
            + "不要问预算，不要下单付款。";

    public static final String SHOP_PRICE_NUDGE = "【系统提醒】你的答复引用了来源，但没有写出任何具体价格。请把读到的价格写出来："
            + "每条写「平台 + 店铺类型（自营 / 官方旗舰 / 授权 / 专卖）+ 价格 + 日期 + 券后条件」，标出处；读不到价格就再 web.read 一个价格页。";

    public static final String RESEARCH_EVIDENCE_NUDGE = "【系统提醒】这是行情 / 市场 / 对比类调研，现在的证据还不够收工：至少要有 2 个独立来源，"
            + "并且至少用 web.read 读过 1 个原网页（搜索摘要不算）。请继续：先拆维度（例如股市按美股 / 欧股 / 亚太 / A 股港股、主要指数、强势与逆势板块；"
            + "二手商品按品牌 / 品类 / 成色 / 渠道），每个维度 web.search 找数据，挑数据最全的 web.read 读原文。";

    public static final String RESEARCH_DATA_NUDGE = "【系统提醒】你的答复缺具体数字或例子。请改写：按维度分条，每条给具体数字（点位 / 涨跌幅 / 价格 / 区间）"
            + "和代表性例子（3–5 个代表产品或指数），标注数据日期和来源；出现分化要点名走势相反的那部分；拿不准的标「待核实」，不要只给笼统趋势。";

    /** 答复里「具体数字」的个数（价格、百分比、点位、带单位的数）。 */
    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(?:[.,]\\d+)?\\s*(?:%|％|点|元|美元|块|万|亿|克|盎司|港元|欧元|日元)|[¥￥$]\\s*\\d+(?:\\.\\d+)?");

    public static int numberCount(String answer) {
        if (answer == null) return 0;
        Matcher m = NUMBER.matcher(answer);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /** 调研终答至少要有几个具体数字。 */
    public static final int RESEARCH_MIN_NUMBERS = 3;

    // ------------------------------------------------------------------ ★ 11-03 下午：「缺例子」= 代表款（商品 / 品牌名 + 价格成对）不足

    /** 购物 / 行情终答至少要有几个代表款（名字 + 价格成对出现）。 */
    public static final int MIN_EXAMPLES = 3;

    public static final String EXAMPLES_NUDGE = "【系统提醒】你的答复代表款不够：至少要有 3 个「商品 / 品牌 / 型号名 + 价格」成对写出的例子"
            + "（例：「雷蛇巴塞利斯蛇 V3 Pro 35K：京东 439 元（11-03，券后）」「V3 X 极速版：245 元」；行情类写 3–5 个代表款与当前价 / 二手价），"
            + "每条标日期与来源；只有一个平台价或只有笼统区间不算。资料不够就再 web.search / web.read 补，拿不准的标「待核实」。";

    /** 价格（带币种 / 单位），用来给代表款配对。 */
    private static final Pattern EX_PRICE = Pattern.compile(
            "(?:[¥￥$€]\\s*\\d+(?:[.,]\\d+)?(?:\\s*[万千kK])?)|(?:\\d+(?:[.,]\\d+)?\\s*(?:万元|万|千元)?\\s*(?:元|块|美元|港元|欧元|日元|英镑|rmb|RMB))");
    /** 名字片段里要去掉的：平台 / 店铺 / 价格修饰 / 行情虚词 / 日期 / 引用号 / 链接。 */
    private static final Pattern EX_STRIP = Pattern.compile(
            "https?://\\S+|\\b[a-z0-9\\-]+\\.(?:com|cn|net)(?:\\.cn)?\\b|\\[\\d+\\]|20\\d{2}[-/.年]\\d{1,2}(?:[-/.月]\\d{1,2}日?)?|\\d{1,2}[-/月]\\d{1,2}日?|"
                    + "京东|天猫|淘宝|拼多多|苏宁|亚马逊|官网|自营|官方|旗舰店|旗舰|专卖店|授权店?|店铺|门店|券后|叠券|用券|领券|到手价?|约|大约|左右|起|以上|以下|"
                    + "价格|售价|报价|定价|首发价?|原价|现价|二手价?|回收价?|成交价|均价|客单价|区间|参考价|国补|补贴|百亿|PLUS|plus|会员价?|秒杀|限时|"
                    + "整体|平均|国际|国内|上涨|下跌|涨幅|跌幅|大概|目前|现在|当前|今天|最近|报|在|为|是|的|和|或|及|"
                    + "USD|RMB|CNY|VIP|APP", Pattern.CASE_INSENSITIVE);
    private static final Pattern EX_NAME_CUE = Pattern.compile("[A-Za-z][A-Za-z0-9]*|[\\u4e00-\\u9fa5]{2,}");
    private static final Pattern EX_SEGMENT = Pattern.compile("[\\n。；;]+");

    /**
     * 答复里的代表款个数：同一句（换行 / 句号 / 分号切开）里，每个价格前面那段文字（从上一个价格之后算起）去掉平台、店铺、价格修饰词后
     * 还剩商品 / 品牌 / 型号名的，算一个代表款；名字相同（忽略空白标点大小写）只算一次。「京东 439 元，天猫 527 元」这种只有平台的不算。
     */
    public static int exampleCount(String answer) {
        if (answer == null || answer.length() == 0) return 0;
        Set<String> names = new LinkedHashSet<String>();
        for (String seg : EX_SEGMENT.split(answer)) {
            Matcher m = EX_PRICE.matcher(seg);
            int from = 0;
            while (m.find()) {
                String name = exampleName(seg.substring(from, m.start()));
                from = m.end();
                // 「V3 Pro：京东 439 元、天猫 527 元」：第二个价格前面只有平台 → 同一个款，不另算
                if (name != null) addName(names, name);
            }
        }
        return names.size();
    }

    /** 「巴塞利斯蛇v3pro35k」和「v3pro35k」是同一款：互相包含就只算一个。 */
    private static void addName(Set<String> names, String name) {
        for (String n : names) if (n.contains(name) || name.contains(n)) return;
        names.add(name);
    }

    static String exampleName(String chunk) {
        if (chunk == null) return null;
        String c = chunk.replaceFirst("^[\\s\\-*•·#>\\d.、)）]+(?=\\S)", " ");   // 列表符号 / 序号
        c = EX_STRIP.matcher(c).replaceAll(" ");
        // 名字只取最后一个分隔符之后（「推荐 V3 专业版，京东」→ 逗号前后都可能是名字：取有名字的最后一段）
        String[] parts = c.split("[，,、：:（）()\\[\\]【】|/]+");
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i].trim();
            if (p.length() == 0) continue;
            if (!EX_NAME_CUE.matcher(p).find()) continue;
            String key = p.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s\\p{Punct}·～~]+", "");
            if (key.length() < 2) continue;
            return key.length() > 24 ? key.substring(key.length() - 24) : key;
        }
        return null;
    }

    /** 收工前要不要提醒（null = 不用）。每种标志由调用方保存，每种只提醒一次。不查代表款（旧口径）。 */
    public static String finishNudge(Kind kind, Evidence ev, String answer, boolean evidenceNudged, boolean priceNudged) {
        return finishNudge(kind, ev, answer, evidenceNudged, priceNudged, true);
    }

    /**
     * 收工前要不要提醒（null = 不用）：证据不够 → 缺价格 / 缺数字 → 缺例子（代表款 &lt; {@link #MIN_EXAMPLES}），
     * 每种只提醒一次（标志由调用方保存）。
     */
    public static String finishNudge(Kind kind, Evidence ev, String answer, boolean evidenceNudged, boolean priceNudged,
                                     boolean examplesNudged) {
        if (kind == Kind.SHOP) {
            if (!evidenceNudged && evidenceShort(ev, answer)) return SHOP_EVIDENCE_NUDGE;
            if (!priceNudged && sourceWithoutPrice(answer)) return SHOP_PRICE_NUDGE;
            if (!examplesNudged && exampleCount(answer) < MIN_EXAMPLES) return EXAMPLES_NUDGE;
            return null;
        }
        if (kind == Kind.RESEARCH) {
            if (!evidenceNudged && evidenceShort(ev, answer)) return RESEARCH_EVIDENCE_NUDGE;
            if (!priceNudged && numberCount(answer) < RESEARCH_MIN_NUMBERS) return RESEARCH_DATA_NUDGE;
            if (!examplesNudged && needsExamples(answer) && exampleCount(answer) < MIN_EXAMPLES) return EXAMPLES_NUDGE;
            return null;
        }
        return null;
    }

    /** 行情类里只有「价格」型调研才数代表款（股市指数 / 涨跌幅那类没有「款」）：答复里出现过货币价格才查。 */
    static boolean needsExamples(String answer) {
        return answer != null && EX_PRICE.matcher(answer).find();
    }

    public static boolean isExamplesNudge(String nudge) {
        return EXAMPLES_NUDGE.equals(nudge);
    }

    /** 这条提醒是不是「证据不够」那一种（另一种是缺价格 / 缺数字）。 */
    public static boolean isEvidenceNudge(String nudge) {
        return SHOP_EVIDENCE_NUDGE.equals(nudge) || RESEARCH_EVIDENCE_NUDGE.equals(nudge);
    }

    /** 开局提示（追加在用户消息后）：购物 / 调研的做法，release 没开技能包时也生效。 */
    public static String startHint(Kind kind) {
        if (kind == Kind.SHOP) return "（系统提示：这是购物 / 比价任务。顺序：先 web.read 比价网（搜索结果里 provider=compare 的粉象比价 / 慢慢买条目）拿各平台券后价与历史最低，"
                + "再用比价网给的商品链接 web.read 商品页核价，网页搜索只作兜底；不问预算；没说版本就列各版本并默认推荐一款、说理由；至少查 2 个平台（京东 + 天猫 / 淘宝），"
                + "至少 web.read 一个原网页核价；每条报价写平台、店铺类型（自营 / 官方旗舰 / 授权 / 专卖）、价格、日期、券后条件；提一句新一代型号与当前大促；停在下单付款前。）";
        if (kind == Kind.RESEARCH) return "（系统提示：这是行情 / 市场 / 对比类调研。先拆维度再查（股市：美股 / 欧股 / 亚太 / A 股港股、主要指数涨跌幅、强势与逆势板块；"
                + "二手商品：品牌 / 品类 / 成色 / 渠道，列 3–5 个代表款与当前二手价、客单价区间；金价：国际与国内价、近期区间、驱动因素）；"
                + "至少 2 个独立来源、至少 web.read 一个原网页；每条给具体数字和例子、标注日期与来源；出现分化要点名相反走势；拿不准标「待核实」。"
                + "结论写成要点：一句话总结 + 每条一行「- 名称：价格 / 数字 + 一句说明」，不超过 8 条、全文约 400 字，不写大段文字（价格行会自动画成条形图）。）";
        return null;
    }

    // ------------------------------------------------------------------ ③b 核对提示是内部指令（用户 11-03 19:45：股市句开头「补上具体个股的收盘价」在回提示）

    public static final String INTERNAL_HEAD = "【内部核对指令｜系统发给助手，用户看不到本条，不要回应、复述或提及本条】";

    /**
     * 收工核对 / 打回提示包成内部指令：继续做；最终回答直接面向用户原问题，像第一次回答那样完整，不提核对过程。
     * 已经包过的原样返回。
     */
    public static String internalNudge(String nudge, String query) {
        if (nudge == null) return null;
        if (nudge.startsWith(INTERNAL_HEAD)) return nudge;
        String q = query == null ? "" : TaskContinuation.strip(query).trim();
        if (q.length() > 80) q = q.substring(0, 80) + "…";
        return INTERNAL_HEAD + "\n" + nudge
                + "\n要求：按上面继续查证。收工时的最终回答必须直接回答用户的原问题「" + q + "」，完整给出结论（不是只写补充的部分）；"
                + "不要提及核对、提醒、补充、证据不够等过程，不要以「好的」「补上…」「已补充」「根据提醒」「以下是补充」之类的话开头。";
    }

    /** 开头的应答词（只去掉这个词本身）。 */
    private static final Pattern META_ACK = Pattern.compile("^(?:好的|好嘞|好|明白了?|收到|了解了?|没问题|OK|Ok|ok)\\s*[，,。.！!、：:]\\s*");
    /** 回应内部提示的句子 / 标题（整句去掉）。 */
    private static final Pattern META_SENTENCE = Pattern.compile(
            "补上|补充|补全|补齐|已补|再补|补一下|核对|核实后|重新整理|更新后的|修正后的|"
                    + "按(?:照)?(?:你的|您的)?(?:提醒|要求)|根据(?:你的|您的)?(?:提醒|要求|反馈)|证据(?:不够|不足|还不够)|系统提醒|内部核对|收工|代表款");
    /** 一个开头单位：第一行，或第一行里的第一句 / 小标题（到 。！？；： 或行尾）。 */
    private static final Pattern FIRST_UNIT = Pattern.compile("^[^\\n。！？!?；;：:]*(?:[。！？!?；;：:]|\\n|$)");
    private static final int META_MAX_LEN = 60;

    /**
     * 去掉回应内部核对提示的开场：开头的「好的，」这类应答词，以及开头 ≤60 字、带「补上 / 补充 / 核对 / 按提醒…」的那一句 / 那一行
     * （如「补上具体个股的收盘价：」「以下是补充后的完整回答。」）。最多去 3 个开头单位；去完为空就原样返回。
     */
    public static String stripMetaOpening(String answer) {
        if (answer == null) return null;
        String s = answer;
        for (int i = 0; i < 3; i++) {
            String t = s.replaceFirst("^[\\s#>*\\-—•·]+", "");
            Matcher ack = META_ACK.matcher(t);
            if (ack.find()) { s = t.substring(ack.end()); continue; }
            Matcher u = FIRST_UNIT.matcher(t);
            if (!u.find()) break;
            String unit = u.group();
            String body = unit.replaceAll("[\\s*#：:。！？!?；;]+$", "");
            if (body.length() == 0 || body.length() > META_MAX_LEN || !META_SENTENCE.matcher(body).find()) break;
            s = t.substring(u.end());
        }
        s = s.replaceFirst("^[\\s：:，,]+", "");
        return s.trim().length() == 0 ? answer : s;
    }

    // ------------------------------------------------------------------ ④ 提议校验

    /** 「要我帮你 X 吗」里的 X → 需要的工具（任一在工具表里才算能做）。 */
    private static final Object[][] OFFERS = {
            {Pattern.compile("打开(?:浏览器|网页|网站|链接|京东|淘宝|天猫|拼多多|商品页)|用浏览器|去(?:京东|淘宝|天猫|拼多多)(?:搜|看)"), new String[]{"browser.open", "browser.search", "app.launch", "errand.run"}},
            {Pattern.compile("下单|付款|支付|提交订单|买下来|帮你买"), new String[]{}},
            {Pattern.compile("打(?:个)?电话|拨打"), new String[]{"phone.call"}},
            {Pattern.compile("发(?:个)?短信"), new String[]{"sms.send"}},
            {Pattern.compile("设(?:个)?(?:闹钟|提醒)"), new String[]{"clock.alarm.set", "reminder.add", "sched.add"}},
    };
    private static final Pattern OFFER_SENTENCE = Pattern.compile("[^。！？?!\\n]*(?:要我|需要我|我可以|我来|要不要我|我帮你)[^。！？?!\\n]*[。！？?!]?");

    /**
     * 删掉终稿里「提议一个工具表里没有的动作」的句子（例：没有任何能开网页的工具却问「要我帮你打开浏览器吗」；
     * 永远不提议替用户下单付款）。没有要删的原样返回。
     */
    public static String dropUnavailableOffers(String answer, java.util.Collection<String> signs) {
        if (answer == null || answer.length() == 0) return answer;
        Matcher m = OFFER_SENTENCE.matcher(answer);
        StringBuffer sb = new StringBuffer();
        boolean changed = false;
        while (m.find()) {
            String sent = m.group();
            if (unavailable(sent, signs)) {
                m.appendReplacement(sb, "");
                changed = true;
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(sent));
            }
        }
        m.appendTail(sb);
        if (!changed) return answer;
        String out = sb.toString().replaceAll("\\n{3,}", "\n\n").trim();
        return out.length() == 0 ? answer : out;
    }

    static boolean unavailable(String sentence, java.util.Collection<String> signs) {
        // 只管提议（问句）：「我停在下单前，下单付款需要你自己来」这种陈述不动
        if (!(sentence.contains("？") || sentence.contains("?") || sentence.contains("吗"))) return false;
        for (Object[] o : OFFERS) {
            if (!((Pattern) o[0]).matcher(sentence).find()) continue;
            String[] need = (String[]) o[1];
            if (need.length == 0) return true;   // 下单付款：永远不提议
            if (signs == null) return false;
            for (String n : need) if (signs.contains(n)) return false;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ 工具

    private static final Pattern URLISH = Pattern.compile("(?:https?://)?((?:[a-z0-9\\-]+\\.)+(?:com|cn|net|org|io|cc|top|me|tv)(?:\\.cn)?)", Pattern.CASE_INSENSITIVE);

    static void collectHosts(String text, Set<String> out) {
        if (text == null) return;
        Matcher m = URLISH.matcher(text);
        while (m.find()) {
            String h = site(m.group(1));
            if (h.length() > 0) out.add(h);
        }
    }

    public static String host(String url) {
        if (url == null) return "";
        String s = url.trim().replaceFirst("^[a-zA-Z]+://", "");
        int k = s.indexOf('/');
        if (k >= 0) s = s.substring(0, k);
        k = s.indexOf('?');
        if (k >= 0) s = s.substring(0, k);
        return site(s);
    }

    /** 归到站点：item.jd.com / search.jd.com → jd.com；detail.tmall.com → tmall.com。 */
    static String site(String host) {
        String h = host == null ? "" : host.toLowerCase(java.util.Locale.ROOT).replaceFirst("^www\\.", "");
        String[] p = h.split("\\.");
        if (p.length <= 2) return h;
        String last2 = p[p.length - 2] + "." + p[p.length - 1];
        if (last2.equals("com.cn") || last2.equals("net.cn") || last2.equals("org.cn")) return p[p.length - 3] + "." + last2;
        return last2;
    }

    /** 调试用：证据摘要（日志）。 */
    public static String describe(Evidence ev, String answer) {
        if (ev == null) return "-";
        JSONArray a = new JSONArray();
        for (String s : ev.sites(answer)) a.put(s);
        return "reads=" + ev.reads + " searches=" + ev.searches + " sites=" + a + " price=" + priceCount(answer) + " examples=" + exampleCount(answer);
    }
}
