package com.onesay.assistant.v2.vision.screenref;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [screenref] 屏幕文字 → 实体列表（纯 Java 正则 + 启发式，JVM 可测，零网络）。
 *
 * <p>识别顺序（先到先得，被占用的字符不再参与后续类型，避免"单号里的手机号""日期里的号"互相误伤）：
 * URL → 邮箱 → 裸域名 → 取件码/验证码 → 快递单号 → 电话 → 金额 → 日期时间 → 地址。
 *
 * <p>节点级合并：若一行只有标签（"取件码""运单号""地址：""联系电话"），会与下一行拼成"标签：值"再识别
 * （很多 App 把标签和值放在两个 TextView 里）。
 */
public final class EntityExtractor {

    private EntityExtractor() {}

    /** 行 + 该行在屏幕上的中心 y（-1 = 未知）。 */
    public static final class Line {
        public final String text;
        public final int screenY;

        public Line(String text, int screenY) {
            this.text = text;
            this.screenY = screenY;
        }
    }

    // ---------- 模式 ----------
    private static final Pattern URL_SCHEME = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])(?:https?://|www\\.)[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");
    private static final Pattern EMAIL = Pattern.compile(
            "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,10}(?![A-Za-z])");
    private static final Pattern URL_BARE = Pattern.compile(
            "(?i)(?<![A-Za-z0-9@./])(?:[A-Za-z0-9-]+\\.)+(?:com|cn|net|org|tv|io|me|cc|top|xyz|edu|gov|info|vip|link)"
                    + "(?:/[A-Za-z0-9\\-._~/?#=&%+]*)?(?![A-Za-z0-9])");
    private static final String CODE_WORDS = "取件码|取货码|提货码|开箱码|取餐码|取餐号|验证码|校验码|动态码|兑换码|券码|核销码|入园码";
    private static final Pattern CODE_LABELED = Pattern.compile(
            "(" + CODE_WORDS + ")[\\s:：]*(?:为|是)?[\\s:：]*[【\\[「]?"
                    + "([A-Za-z0-9]{1,6}(?:-[A-Za-z0-9]{1,6}){1,3}|[A-Za-z0-9]{3,12})(?![A-Za-z0-9])");
    /** 取票码 / 检票码 / 取票号 认成码（允许中间带空格「TEST 8888 0001」，存时去掉空格）。 */
    private static final Pattern CODE_TICKET = Pattern.compile(
            "(取票码|检票码|取票号)[\\s:：]*(?:为|是)?[\\s:：]*[【\\[「]?([A-Za-z0-9]{2,12}(?:[ \\-][A-Za-z0-9]{2,12}){0,3})(?![A-Za-z0-9])");
    private static final Pattern CODE_PING = Pattern.compile(
            "凭\\s*(?:取件码|取货码|提货码)?\\s*[【\\[「]?([A-Za-z0-9]{1,6}(?:-[A-Za-z0-9]{1,6}){1,3}|\\d{4,8})[】\\]」]?\\s*(?:到|至|前往|取|领)");
    private static final Pattern TRACK_LABELED = Pattern.compile(
            "(?:运单号|快递单号|物流单号|运单编号|快递号|单号)[\\s:：]*(?:为|是)?[\\s:：]*([A-Za-z]{0,4}\\d{8,20})(?![A-Za-z0-9])");
    private static final Pattern TRACK_PREFIX = Pattern.compile(
            "(?<![A-Za-z0-9])((?:SF|JT|YT|JD[A-Z]?|ZTO|YD|DPK|EMS|STO|YZ)\\d{10,16})(?![A-Za-z0-9])");
    private static final Pattern TRACK_DIGITS = Pattern.compile("(?<![A-Za-z0-9])([1-9]\\d{11,14})(?![0-9])");
    private static final Pattern TRACK_CONTEXT = Pattern.compile("快递|物流|包裹|运单|签收|派送|派件|驿站|丰巢|菜鸟|揽收|中转");
    private static final Pattern PHONE_MOBILE = Pattern.compile(
            "(?<![0-9A-Za-z])(?:\\+?86[- ]?)?(1[3-9]\\d)[- ]?(\\d{4})[- ]?(\\d{4})(?![0-9])");
    private static final Pattern PHONE_400 = Pattern.compile("(?<![0-9])([48]00)[- ]?(\\d{3})[- ]?(\\d{4})(?![0-9])");
    private static final Pattern PHONE_LAND = Pattern.compile(
            "(?<![0-9A-Za-z])(?:(0\\d{2,3})-(\\d{7,8})|[(（](0\\d{2,3})[)）]\\s?(\\d{7,8}))(?![0-9])");
    private static final Pattern PHONE_LAND_CTX = Pattern.compile(
            "(?:电话|热线|座机|Tel|TEL|tel|联系)[^0-9]{0,4}(?<![0-9])(0\\d{9,11})(?![0-9])");
    private static final Pattern PHONE_SERVICE = Pattern.compile(
            "(?:电话|热线|客服|拨打|致电|呼叫|服务号|详询|咨询|联系)[^0-9]{0,6}(?<![0-9])(9\\d{4}|1[0-2]\\d{3})(?![0-9])");
    private static final Pattern AMOUNT_PREFIX = Pattern.compile(
            "(?:[¥￥]|人民币|RMB)\\s?(\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)(?![0-9])");
    private static final Pattern AMOUNT_SUFFIX = Pattern.compile(
            "(?<![0-9.])(\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)\\s?(?:元|块钱)(?!旦|宵)");
    private static final Pattern AMOUNT_USD = Pattern.compile("\\$\\s?(\\d+(?:\\.\\d{1,2})?)(?![0-9])");

    private static final Pattern LABEL_ONLY = Pattern.compile(
            "^\\s*(取件码|取货码|提货码|取餐码|取餐号|取票码|检票码|取票号|验证码|运单号|快递单号|物流单号|单号|地址|收货地址|收件地址|详细地址|门店地址|"
                    + "电话|联系电话|手机|手机号|联系方式|客服电话|时间|日期|开始时间|会议时间|活动时间|邮箱|网址|链接)\\s*[:：]?\\s*$");

    // ---- 地址 ----
    private static final Pattern ADDR_LABELED = Pattern.compile(
            "(?:收货地址|收件地址|详细地址|门店地址|取件地址|店铺地址|会议地点|活动地点|集合地点|上车点|地址|位置|地点)\\s*[:：]\\s*([^\\n，,。；;！!？?|]{3,60})");
    private static final Pattern ADDR_SPLIT = Pattern.compile("[\\n，,。；;！!？?|、【】\\[\\]\"“”：:]+");
    private static final Pattern A_ADMIN = Pattern.compile("[\\u4e00-\\u9fa5]{2,}(?:省|自治区|特别行政区)");
    private static final Pattern A_CITY = Pattern.compile("[\\u4e00-\\u9fa5]{2,}?市");
    private static final Pattern A_DIST = Pattern.compile("[\\u4e00-\\u9fa5]{1,}?(?:区|县|镇|乡|街道)");
    private static final Pattern A_ROAD_NUM = Pattern.compile(
            "[\\u4e00-\\u9fa5A-Za-z0-9]{1,}?(?:路|街|大道|大街|巷|弄|胡同|道)\\s?(?:\\d+|[一二三四五六七八九十百]+)\\s?(?:号|弄|巷)");
    private static final Pattern A_ROAD = Pattern.compile("[\\u4e00-\\u9fa5]{1,}(?:路|大道|大街|街|巷|胡同)");
    private static final Pattern A_NUM = Pattern.compile("\\d+\\s?(?:号楼|号院|号|栋|幢|座|楼|层|室|单元)|[A-Za-z]座|号楼");
    private static final Pattern A_POI = Pattern.compile(
            "大厦|广场|中心|商场|商城|购物中心|小区|花园|公寓|酒店|宾馆|医院|学校|大学|学院|中学|小学|机场|火车站|高铁站|地铁站|汽车站|"
                    + "公园|写字楼|产业园|科技园|工业园|大楼|市场|超市|驿站|丰巢|快递柜|营业厅|网点|门店|分店|旗舰店|体育馆|图书馆|博物馆|景区");
    private static final Pattern A_ANCHOR = Pattern.compile("[\\u4e00-\\u9fa5](?:省|市|区|县|路|街|大道|镇)");
    private static final String[] ADDR_PREFIX_VERBS = {
            "已到达", "已到", "到达", "请到", "请至", "请于", "送至", "寄往", "位于", "地址在", "前往", "来到", "放在", "放至", "存放于", "存放在",
            "到", "至", "去", "在", "于"};
    private static final Pattern ADDR_TAIL_CUT = Pattern.compile("(?:取件|取货|领取|自提|集合|签收|派送|等你|见面|营业时间|电话).*$");
    private static final Pattern ADDR_BAD = Pattern.compile("我|你|他|她|吗|呢|吧|怎么|什么|没有|已经|正在|可以|如果|因为|所以|请|您|谢谢|快递员|包裹");

    // ---------- API ----------

    /** 便捷入口：纯文本行（无屏幕坐标）。 */
    public static List<ScreenEntity> extractFromTexts(List<String> texts, long nowMs, TimeZone tz) {
        List<Line> lines = new ArrayList<Line>();
        if (texts != null) for (String t : texts) lines.add(new Line(t, -1));
        return extract(lines, nowMs, tz);
    }

    /** 从 uiautomator 节点提取（text 与 content-desc 各算一行，去掉重复）。 */
    public static List<ScreenEntity> extractFromNodes(List<UiNode> nodes, long nowMs, TimeZone tz) {
        List<Line> lines = new ArrayList<Line>();
        if (nodes != null) {
            String prev = null;
            for (UiNode n : nodes) {
                String t = n.text.trim();
                String d = n.contentDesc.trim();
                if (t.length() > 0 && !t.equals(prev)) {
                    lines.add(new Line(t, n.centerY()));
                    prev = t;
                }
                if (d.length() > 0 && !d.equals(t) && !d.equals(prev)) {
                    lines.add(new Line(d, n.centerY()));
                    prev = d;
                }
            }
        }
        return extract(lines, nowMs, tz);
    }

    /** 主入口。结果去重（同类型同值保留第一次出现、置信度取最大）。 */
    public static List<ScreenEntity> extract(List<Line> rawLines, long nowMs, TimeZone tz) {
        List<Line> lines = mergeLabelLines(rawLines);
        Map<String, ScreenEntity> uniq = new LinkedHashMap<String, ScreenEntity>();
        for (int li = 0; li < lines.size(); li++) {
            Line line = lines.get(li);
            if (line.text == null) continue;
            String s = line.text;
            if (s.length() > 2000) s = s.substring(0, 2000);
            List<ScreenEntity> found = extractLine(s, li, nowMs, tz);
            for (ScreenEntity e : found) {
                ScreenEntity e2 = e.withScreenY(line.screenY).withContext(s);
                String k = e2.dedupKey();
                ScreenEntity old = uniq.get(k);
                if (old == null) {
                    uniq.put(k, e2);
                } else if (e2.confidence > old.confidence) {
                    uniq.put(k, new ScreenEntity(old.type, old.value, old.raw, e2.confidence, old.order,
                            old.subLabel != null ? old.subLabel : e2.subLabel, old.beginMs, old.endMs, old.allDay, old.screenY, old.contextLine));
                }
            }
        }
        return new ArrayList<ScreenEntity>(uniq.values());
    }

    private static List<Line> mergeLabelLines(List<Line> in) {
        List<Line> out = new ArrayList<Line>();
        if (in == null) return out;
        for (int i = 0; i < in.size(); i++) {
            Line l = in.get(i);
            if (l == null || l.text == null) continue;
            Matcher m = LABEL_ONLY.matcher(l.text);
            if (m.matches() && i + 1 < in.size() && in.get(i + 1) != null && in.get(i + 1).text != null) {
                Line next = in.get(i + 1);
                out.add(new Line(m.group(1) + "：" + next.text.trim(), next.screenY));
                i++;
                continue;
            }
            out.add(l);
        }
        return out;
    }

    /** 单行识别（public 供单测直接打点）。 */
    public static List<ScreenEntity> extractLine(String s, int lineIdx, long nowMs, TimeZone tz) {
        List<ScreenEntity> out = new ArrayList<ScreenEntity>();
        boolean[] used = new boolean[s.length()];
        int base = lineIdx * 1000;

        // 1. URL（带协议或 www.）
        Matcher m = URL_SCHEME.matcher(s);
        while (m.find()) {
            String u = trimUrl(m.group());
            if (u.length() < 6) continue;
            int end = m.start() + u.length();
            mark(used, m.start(), end);
            String v = u.toLowerCase().startsWith("www.") ? "http://" + u : u;
            out.add(ScreenEntity.simple(ScreenEntity.Type.URL, v, u, 0.95, base + m.start(), null));
        }
        // 2. 邮箱
        m = EMAIL.matcher(s);
        while (m.find()) {
            if (anyUsed(used, m.start(), m.end())) continue;
            mark(used, m.start(), m.end());
            out.add(ScreenEntity.simple(ScreenEntity.Type.EMAIL, m.group(), m.group(), 0.95, base + m.start(), null));
        }
        // 3. 裸域名（t.cn/xxx、example.com）
        m = URL_BARE.matcher(s);
        while (m.find()) {
            if (anyUsed(used, m.start(), m.end())) continue;
            String u = trimUrl(m.group());
            mark(used, m.start(), m.start() + u.length());
            out.add(ScreenEntity.simple(ScreenEntity.Type.URL, "http://" + u, u, 0.7, base + m.start(), null));
        }
        // 4. 取件码 / 验证码（★ 取票码 / 检票码先认）
        m = CODE_TICKET.matcher(s);
        while (m.find()) {
            if (anyUsed(used, m.start(2), m.end(2))) continue;
            String val = m.group(2).replaceAll("[\\s\\-]+", "");
            if (!val.matches(".*\\d.*") || val.length() < 4 || val.length() > 24) continue;
            mark(used, m.start(), m.end());
            out.add(ScreenEntity.simple(ScreenEntity.Type.CODE, val.toUpperCase(), m.group(2), 0.95, base + m.start(2),
                    m.group(1).startsWith("检票") ? "检票码" : "取票码"));
        }
        m = CODE_LABELED.matcher(s);
        while (m.find()) {
            if (anyUsed(used, m.start(2), m.end(2))) continue;
            String val = m.group(2);
            if (!val.matches(".*\\d.*")) continue;   // 纯字母不是码（"取件码是多少"之类）
            mark(used, m.start(), m.end());
            out.add(ScreenEntity.simple(ScreenEntity.Type.CODE, val.toUpperCase(), val, 0.95, base + m.start(2), codeLabel(m.group(1))));
        }
        m = CODE_PING.matcher(s);
        while (m.find()) {
            if (anyUsed(used, m.start(1), m.end(1))) continue;
            mark(used, m.start(1), m.end(1));
            out.add(ScreenEntity.simple(ScreenEntity.Type.CODE, m.group(1).toUpperCase(), m.group(1), 0.85, base + m.start(1), "取件码"));
        }
        // 5. 快递单号
        String carrier = carrierOf(s);
        m = TRACK_LABELED.matcher(s);
        while (m.find()) {
            if (anyUsed(used, m.start(1), m.end(1))) continue;
            mark(used, m.start(1), m.end(1));
            String v = m.group(1).toUpperCase();
            out.add(ScreenEntity.simple(ScreenEntity.Type.TRACKING, v, m.group(1), 0.95, base + m.start(1), carrierOrPrefix(carrier, v)));
        }
        m = TRACK_PREFIX.matcher(s);
        while (m.find()) {
            if (anyUsed(used, m.start(1), m.end(1))) continue;
            mark(used, m.start(1), m.end(1));
            String v = m.group(1).toUpperCase();
            out.add(ScreenEntity.simple(ScreenEntity.Type.TRACKING, v, m.group(1), 0.9, base + m.start(1), carrierOrPrefix(carrier, v)));
        }
        if (TRACK_CONTEXT.matcher(s).find()) {
            m = TRACK_DIGITS.matcher(s);
            while (m.find()) {
                if (anyUsed(used, m.start(1), m.end(1))) continue;
                mark(used, m.start(1), m.end(1));
                out.add(ScreenEntity.simple(ScreenEntity.Type.TRACKING, m.group(1), m.group(1), 0.75, base + m.start(1), carrier));
            }
        }
        // 6. 电话
        m = PHONE_MOBILE.matcher(s);
        while (m.find()) {
            if (anyUsed(used, m.start(), m.end())) continue;
            mark(used, m.start(), m.end());
            String v = m.group(1) + m.group(2) + m.group(3);
            out.add(ScreenEntity.simple(ScreenEntity.Type.PHONE, v, m.group().trim(), 0.9, base + m.start(), "手机"));
        }
        m = PHONE_400.matcher(s);
        while (m.find()) {
            if (anyUsed(used, m.start(), m.end())) continue;
            mark(used, m.start(), m.end());
            out.add(ScreenEntity.simple(ScreenEntity.Type.PHONE, m.group(1) + m.group(2) + m.group(3), m.group(), 0.9, base + m.start(), "客服"));
        }
        m = PHONE_LAND.matcher(s);
        while (m.find()) {
            if (anyUsed(used, m.start(), m.end())) continue;
            mark(used, m.start(), m.end());
            String v = m.group(1) != null ? m.group(1) + m.group(2) : m.group(3) + m.group(4);
            out.add(ScreenEntity.simple(ScreenEntity.Type.PHONE, v, m.group(), 0.85, base + m.start(), "座机"));
        }
        m = PHONE_LAND_CTX.matcher(s);
        while (m.find()) {
            if (anyUsed(used, m.start(1), m.end(1))) continue;
            mark(used, m.start(1), m.end(1));
            out.add(ScreenEntity.simple(ScreenEntity.Type.PHONE, m.group(1), m.group(1), 0.75, base + m.start(1), "座机"));
        }
        m = PHONE_SERVICE.matcher(s);
        while (m.find()) {
            if (anyUsed(used, m.start(1), m.end(1))) continue;
            mark(used, m.start(1), m.end(1));
            out.add(ScreenEntity.simple(ScreenEntity.Type.PHONE, m.group(1), m.group(1), 0.8, base + m.start(1), "客服"));
        }
        // 7. 金额
        Pattern[] amts = {AMOUNT_PREFIX, AMOUNT_SUFFIX, AMOUNT_USD};
        for (int pi = 0; pi < amts.length; pi++) {
            m = amts[pi].matcher(s);
            while (m.find()) {
                if (anyUsed(used, m.start(), m.end())) continue;
                mark(used, m.start(), m.end());
                String v = m.group(1).replace(",", "");
                out.add(ScreenEntity.simple(ScreenEntity.Type.AMOUNT, v, m.group().trim(), 0.85, base + m.start(), pi == 2 ? "USD" : "CNY"));
            }
        }
        // 8. 日期时间（在"被占用字符换成空格"的文本上找，避免电话/单号里的数字被当成钟点）
        String masked = maskUsed(s, used);
        for (DateTimeNormalizer.Span sp : DateTimeNormalizer.findAll(masked, nowMs, tz)) {
            if (anyUsed(used, sp.start, sp.end)) continue;
            mark(used, sp.start, sp.end);
            out.add(new ScreenEntity(ScreenEntity.Type.DATETIME, sp.raw.trim(), sp.raw.trim(), sp.confidence(), base + sp.start,
                    null, sp.beginMs, sp.endMs, sp.allDay(), -1));
        }
        // 9. 地址
        extractAddresses(s, used, base, out);
        return out;
    }

    // ---------- 地址 ----------

    private static void extractAddresses(String s, boolean[] used, int base, List<ScreenEntity> out) {
        Matcher m = ADDR_LABELED.matcher(s);
        while (m.find()) {
            if (anyUsed(used, m.start(1), m.end(1))) {
                // 地址里允许出现被"日期"误占的"号"——但若被电话/单号占用则跳过
                if (countUsed(used, m.start(1), m.end(1)) > 3) continue;
            }
            String v = cleanAddress(m.group(1));
            if (v.length() < 3) continue;
            mark(used, m.start(1), m.end(1));
            out.add(ScreenEntity.simple(ScreenEntity.Type.ADDRESS, v, v, 0.9, base + m.start(1), null));
        }
        String masked = maskUsedWith(s, used, '|');
        String[] chunks = ADDR_SPLIT.split(masked);
        int searchFrom = 0;
        for (String chunk : chunks) {
            if (chunk.trim().length() < 4) {
                continue;
            }
            int chunkPos = masked.indexOf(chunk, searchFrom);
            if (chunkPos >= 0) searchFrom = chunkPos + chunk.length();
            List<String> cands = new ArrayList<String>();
            String joined = chunk.trim().replaceAll("(?<=[\\u4e00-\\u9fa5\\d])\\s+(?=[\\u4e00-\\u9fa5\\d])", "");
            if (scoreAddress(stripAddressPrefix(joined)) >= 2) {
                cands.add(joined);
            } else {
                for (String part : chunk.trim().split("\\s+")) cands.add(part);
            }
            for (String c : cands) {
                String v = cleanAddress(stripAddressPrefix(c));
                if (v.length() < 4 || v.length() > 60) continue;
                if (ADDR_BAD.matcher(v).find()) continue;
                if (cjkRatio(v) < 0.4) continue;
                int sc = scoreAddress(v);
                if (sc < 2) continue;
                double conf = Math.min(0.92, 0.45 + 0.12 * sc);
                out.add(ScreenEntity.simple(ScreenEntity.Type.ADDRESS, v, v, conf, base + Math.max(0, chunkPos), null));
            }
        }
    }

    /** 地址打分（public 供单测）。≥2 视为地址。 */
    public static int scoreAddress(String v) {
        if (v == null) return 0;
        int sc = 0;
        boolean roadNum = A_ROAD_NUM.matcher(v).find();
        if (roadNum) sc += 2;
        else if (A_ROAD.matcher(v).find()) sc += 1;
        if (A_ADMIN.matcher(v).find() || A_CITY.matcher(v).find()) sc += 1;
        if (A_DIST.matcher(v).find()) sc += 1;
        if (!roadNum && A_NUM.matcher(v).find()) sc += 1;
        if (A_POI.matcher(v).find()) sc += 1;
        return sc;
    }

    static String stripAddressPrefix(String s) {
        if (s == null) return "";
        s = s.trim();
        Matcher a = A_ANCHOR.matcher(s);
        if (!a.find()) return s;
        int anchor = a.start() + 1;   // 行政/道路后缀所在位置
        int cut = -1;
        for (String verb : ADDR_PREFIX_VERBS) {
            int idx = s.lastIndexOf(verb, anchor);
            while (idx >= 0) {
                int end = idx + verb.length();
                if (end <= anchor - 2 || (end <= anchor - 1 && verb.length() >= 2)) {
                    if (end > cut) cut = end;
                    break;
                }
                idx = idx > 0 ? s.lastIndexOf(verb, idx - 1) : -1;
            }
        }
        return cut > 0 ? s.substring(cut).trim() : s;
    }

    private static String cleanAddress(String v) {
        if (v == null) return "";
        v = v.trim();
        v = ADDR_TAIL_CUT.matcher(v).replaceAll("");
        v = v.replaceAll("^[\\s(（]+|[\\s的)）]+$", "");
        // 括号不配对时去掉孤立的左括号尾巴
        return v.trim();
    }

    private static double cjkRatio(String v) {
        int c = 0;
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch >= '\u4e00' && ch <= '\u9fa5') c++;
        }
        return v.length() == 0 ? 0 : (double) c / v.length();
    }

    // ---------- 工具 ----------

    private static String codeLabel(String w) {
        if (w == null) return "取件码";
        if (w.startsWith("取餐")) return "取餐码";
        if (w.equals("验证码") || w.equals("校验码") || w.equals("动态码")) return "验证码";
        if (w.equals("兑换码") || w.equals("券码") || w.equals("核销码")) return "券码";
        if (w.equals("入园码")) return "入园码";
        return "取件码";
    }

    private static String carrierOf(String s) {
        String[][] map = {
                {"顺丰", "顺丰"}, {"中通", "中通"}, {"圆通", "圆通"}, {"韵达", "韵达"}, {"申通", "申通"},
                {"极兔", "极兔"}, {"京东", "京东"}, {"邮政", "邮政"}, {"EMS", "EMS"}, {"德邦", "德邦"}};
        for (String[] kv : map) if (s.contains(kv[0])) return kv[1];
        return null;
    }

    private static String carrierOrPrefix(String carrier, String v) {
        if (carrier != null) return carrier;
        if (v.startsWith("SF")) return "顺丰";
        if (v.startsWith("JT")) return "极兔";
        if (v.startsWith("YT")) return "圆通";
        if (v.startsWith("JD")) return "京东";
        if (v.startsWith("ZTO")) return "中通";
        if (v.startsWith("STO")) return "申通";
        if (v.startsWith("YD")) return "韵达";
        if (v.startsWith("DPK")) return "德邦";
        if (v.startsWith("EMS") || v.startsWith("YZ")) return "邮政";
        return null;
    }

    private static String trimUrl(String u) {
        while (u.length() > 0) {
            char c = u.charAt(u.length() - 1);
            if (c == '.' || c == ',' || c == ';' || c == ':' || c == ')' || c == '!' || c == '\'' || c == ']' || c == '?') {
                u = u.substring(0, u.length() - 1);
            } else {
                break;
            }
        }
        return u;
    }

    private static void mark(boolean[] used, int a, int b) {
        for (int i = Math.max(0, a); i < Math.min(used.length, b); i++) used[i] = true;
    }

    private static boolean anyUsed(boolean[] used, int a, int b) {
        for (int i = Math.max(0, a); i < Math.min(used.length, b); i++) if (used[i]) return true;
        return false;
    }

    private static int countUsed(boolean[] used, int a, int b) {
        int n = 0;
        for (int i = Math.max(0, a); i < Math.min(used.length, b); i++) if (used[i]) n++;
        return n;
    }

    private static String maskUsed(String s, boolean[] used) {
        return maskUsedWith(s, used, ' ');
    }

    private static String maskUsedWith(String s, boolean[] used, char ch) {
        char[] c = s.toCharArray();
        for (int i = 0; i < c.length; i++) if (used[i]) c[i] = ch;
        return new String(c);
    }
}
