package com.onesay.assistant.v2.planner;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [planner] 计划 App 专属的几句话（纯 Java，JVM 全测）。其他说法照旧由 S3（待办 / 定时任务）、本地日程、记一记接，只是数据落到计划 App。
 * <ul>
 *   <li>「取件码是 5-2-1234」「记一下取餐码 A088，在瑞幸」→ 存一个取件码；</li>
 *   <li>「把这张票存起来」「存一下这张票」→ 读屏认票，存进票夹；</li>
 *   <li>「今天有啥事」「今天有什么安排」→ 读计划 App 的「今天」（日程 + 待取件 + 临近的票 + 待办）；</li>
 *   <li>「打开计划」→ 打开计划 App。</li>
 *   <li>第一次问出来了，第二次「取件码是 A1-2345 的…在哪个驿站」答不上——
 *       OneSay 只会存取件码、不会查，这句落到大模型，而模型看不到计划 App 里的件）：
 *       「取件码是 A1-2345 的快递在哪个驿站」「A1-2345 这个取件码在哪取」→ 按码查计划 App 的取件码（{@link #pickupAsk}）；
 *       「我的取件码是多少」「我有什么快递要取」→ 列出待取的件。问句不再被当成「存一个取件码」。</li>
 * </ul>
 */
public final class PlannerVoice {
    private PlannerVoice() {}

    /** 「是 / ：」可省（文档里的「记一下取餐码 A088，在瑞幸」原来存不上）。 */
    static final Pattern PICKUP = Pattern.compile(
            "^(?:帮我)?(?:记(?:一下|个|住)?)?(?:我的)?(?:快递)?(取件码|取餐码)(?:是|为|：|:)?\\s*([A-Za-z0-9][A-Za-z0-9\\-—–\\s]{1,22}?)\\s*(?:[，,。\\s]*(?:在|地址是|位置是)\\s*([^，,。]{2,24}))?[。.！!]?$");
    /** 票的说法（补「帮我记下这张票」「把这张票存下来」「记下票」等）。认出来 = 识屏认票：计划 App 在 → 票夹；不在 → 记一记识屏。 */
    static final String TK = "(?:票|车票|机票|火车票|高铁票|电影票|门票|演出票|票根)";
    static final Pattern TICKET = Pattern.compile("^(?:请|麻烦你?)?(?:帮我)?(?:把)?(?:这张|这个)?" + TK
            + "(?:给我)?(?:存起来|存下来|存一下|记下来|记一下|记下|记住|保存(?:一下|下来)?|收起来|放(?:进|到)(?:计划|票夹)(?:里)?)(?:吧|哦)?[。.！!]?$"
            + "|^(?:请|麻烦你?)?(?:帮我)?(?:存|保存|收|记下|记住|记|记一下|存一下)(?:一下)?(?:这张|这个)?" + TK + "(?:吧|哦)?[。.！!]?$");
    static final Pattern TODAY = Pattern.compile("^(?:我)?今天(?:都)?(?:有(?:啥|什么|哪些)(?:事|事情|安排|要做的)|要(?:干嘛|干啥|做什么|做啥)|的安排|安排(?:是)?(?:什么|啥)?)(?:呀|啊|吗|呢)?[？?。.]?$");
    static final Pattern OPEN = Pattern.compile("^(?:打开|看看|看一下)(?:我的)?(?:Bixby)?计划(?:App|应用)?[。.]?$|^(?:打开|看看)票夹[。.]?$");

    public static final class Pickup {
        public final String kind, code, station;

        Pickup(String kind, String code, String station) {
            this.kind = kind; this.code = code; this.station = station;
        }
    }

    public static Pickup pickup(String q) {
        if (q == null) return null;
        if (pickupAsk(q) != null) return null;   // 问句（「取件码是 A1-2345 在哪里」）不是「存一个」
        Matcher m = PICKUP.matcher(q.trim());
        if (!m.matches()) return null;
        if (m.group(3) != null && ASK_WORD.matcher(m.group(3)).find()) return null;   // 「在哪个驿站」不是驿站名
        String code = m.group(2).replaceAll("[—–]", "-").replaceAll("\\s+", "");
        if (!code.matches("[A-Za-z0-9][A-Za-z0-9\\-]{1,39}")) return null;
        return new Pickup(m.group(1), code.toUpperCase(java.util.Locale.ROOT), m.group(3) == null ? "" : m.group(3).trim());
    }

    // ------------------------------------------------------------------ 取件码问答（只读）

    static final String PK = "(?:取件码|取货码|提货码|快递码|取餐码)";
    static final String CODE = "([A-Za-z0-9][A-Za-z0-9\\-—–]{0,22}[A-Za-z0-9])";
    static final Pattern ASK_WORD = Pattern.compile("哪|什么|啥|吗|么|没|多少|几|谁|呢|[？?]");
    /** 「取件码是 A1-2345 的快递在哪个驿站」「查一下取件码 A1-2345」「我的取件码 A1-2345 还在吗」 */
    static final Pattern ASK_BY_CODE_1 = Pattern.compile("^(?:请|麻烦你?)?(?:帮我)?(?:查(?:一下|查|下)?|看(?:一下|看|下)?|找(?:一下|找)?)?(?:我的)?(?:快递)?"
            + PK + "(?:是|为|：|:)?" + CODE + "(.*)$");
    /** 「A1-2345 这个取件码在哪取」「A1-2345 是哪个驿站的取件码」 */
    static final Pattern ASK_BY_CODE_2 = Pattern.compile("^(?:请|麻烦你?)?(?:帮我)?(?:查(?:一下|查|下)?|看(?:一下|下)?)?" + CODE + "(?:这个|那个|的)?(.*" + PK + ".*)$");
    static final Pattern ASK_LIST = Pattern.compile(
            "^(?:请|麻烦你?)?(?:帮我)?(?:查(?:一下|查|下)?|看(?:一下|看|下)?)?(?:我的|我)?(?:快递)?" + PK + "(?:是多少|是啥|是什么|多少|有哪些|有几个|呢)?$"
            + "|^(?:我)?(?:还|现在)?有(?:什么|哪些|几个|多少|没有)?(?:快递|包裹|件)(?:要取|没取|待取|还没取|要拿|没拿)(?:的)?(?:吗|呢|没|没有)?$"
            + "|^(?:有没有|还有没有)(?:快递|包裹|件)(?:要取|没取|待取|还没取)(?:的)?$"
            + "|^(?:我的)?(?:待取|没取|还没取)(?:的)?(?:快递|包裹|件)(?:有哪些|有几个|呢)?$");

    /** 取件码问句。code 不空 = 按码查；空 = 列出待取的件（{@link #listOnly}：只是「取件码是多少」这种，计划里没有时留给记一记回答）。 */
    public static final class PickupAsk {
        public final String code;
        public final boolean listOnly;

        PickupAsk(String code, boolean listOnly) {
            this.code = code;
            this.listOnly = listOnly;
        }
    }

    static String norm(String q) {
        return q.trim().replaceAll("[\\s，,。.！!、]", "");
    }

    /** 统一取件码写法：大写、去掉 - — – 和空白（「a1 2345」=「A1-2345」）。 */
    public static String codeKey(String code) {
        return code == null ? "" : code.toUpperCase(java.util.Locale.ROOT).replaceAll("[\\s\\-—–]", "");
    }

    public static PickupAsk pickupAsk(String q) {
        if (q == null) return null;
        String n = norm(q);
        if (n.length() == 0) return null;
        if (ASK_LIST.matcher(n.replaceAll("[？?]", "")).matches()) {
            boolean codeOnly = n.replaceAll("[？?]", "").matches("^(?:请|麻烦你?)?(?:帮我)?(?:查(?:一下|查|下)?|看(?:一下|看|下)?)?(?:我的|我)?(?:快递)?" + PK + ".*$");
            return new PickupAsk("", codeOnly);
        }
        Matcher m = ASK_BY_CODE_1.matcher(n);
        if (!m.matches()) m = ASK_BY_CODE_2.matcher(n);
        if (!m.matches()) return null;
        String code = m.group(1), rest = m.group(2);
        if (codeKey(code).length() < 3 || !code.matches(".*\\d.*")) return null;   // 至少像个码（有数字、3 位以上）
        boolean ask = ASK_WORD.matcher(rest).find() || n.endsWith("?") || n.endsWith("？")
                || n.matches("^(?:请|麻烦你?)?(?:帮我)?(?:查|看|找).*");
        if (!ask) return null;
        return new PickupAsk(code.replaceAll("[—–]", "-").toUpperCase(java.util.Locale.ROOT), false);
    }

    /**
     * 按问句回答（rows = 计划 App 的 pickups 表）。返回 null = 不接（只问「取件码是多少」而计划里没有待取的件 → 交给记一记）。
     */
    public static String pickupAnswer(PickupAsk ask, List<Map<String, Object>> rows, long now, java.util.TimeZone tz) {
        List<Map<String, Object>> pending = new java.util.ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : rows) if (PlannerMap.l(r, "taken_at") <= 0) pending.add(r);
        java.util.Collections.sort(pending, new java.util.Comparator<Map<String, Object>>() {
            @Override public int compare(Map<String, Object> a, Map<String, Object> b) {
                long x = PlannerMap.l(a, "created_at"), y = PlannerMap.l(b, "created_at");
                return x > y ? -1 : (x == y ? 0 : 1);
            }
        });
        if (ask.code.length() == 0) {
            if (pending.isEmpty()) return ask.listOnly ? null : "计划里没有待取的快递。";
            StringBuilder sb = new StringBuilder("有 ").append(pending.size()).append(" 个件没取：");
            for (int i = 0; i < pending.size() && i < 3; i++) sb.append(i > 0 ? "；" : "").append(describe(pending.get(i), now, tz));
            if (pending.size() > 3) sb.append(" 等");
            return sb.append("。").toString();
        }
        String want = codeKey(ask.code);
        Map<String, Object> hit = null;
        for (Map<String, Object> r : rows) if (codeKey(PlannerMap.s(r, "code")).equals(want) && (hit == null || PlannerMap.l(hit, "taken_at") > 0)) hit = r;
        if (hit == null && want.length() >= 4) {   // 只说了后几位（「2345 那个件」）：唯一命中才算
            Map<String, Object> only = null;
            int n = 0;
            for (Map<String, Object> r : pending) if (codeKey(PlannerMap.s(r, "code")).endsWith(want)) { only = r; n++; }
            if (n == 1) hit = only;
        }
        if (hit == null) {
            StringBuilder sb = new StringBuilder("计划里没有取件码 ").append(ask.code).append(" 的件。");
            if (!pending.isEmpty()) {
                sb.append("待取的有：");
                for (int i = 0; i < pending.size() && i < 3; i++) sb.append(i > 0 ? "；" : "").append(describe(pending.get(i), now, tz));
                sb.append("。");
            }
            return sb.toString();
        }
        String kind = PlannerMap.s(hit, "kind").length() > 0 ? PlannerMap.s(hit, "kind") : "取件码";
        String code = PlannerMap.s(hit, "code"), st = PlannerMap.s(hit, "station");
        if (PlannerMap.l(hit, "taken_at") > 0) return kind + " " + code + (st.length() > 0 ? "（" + st + "）" : "") + "已经取了。";
        StringBuilder sb = new StringBuilder(kind).append(" ").append(code);
        sb.append(st.length() > 0 ? " 在" + st + "，还没取。" : " 还没取，没记在哪个驿站。");
        long dl = PlannerMap.l(hit, "deadline_at");
        if (dl > 0) sb.append(dl < now ? "截止时间 " + when(dl, tz) + " 已经过了，尽快去取。" : "请在 " + when(dl, tz) + " 前取。");
        return sb.toString();
    }

    static String describe(Map<String, Object> r, long now, java.util.TimeZone tz) {
        String kind = PlannerMap.s(r, "kind").length() > 0 ? PlannerMap.s(r, "kind") : "取件码";
        String st = PlannerMap.s(r, "station");
        long dl = PlannerMap.l(r, "deadline_at");
        return kind + " " + PlannerMap.s(r, "code") + (st.length() > 0 ? "（" + st + "）" : "") + (dl > 0 && dl < now ? "，已过截止时间" : "");
    }

    static String when(long t, java.util.TimeZone tz) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA);
        f.setTimeZone(tz);
        return f.format(new java.util.Date(t));
    }

    public static boolean ticket(String q) {
        return q != null && TICKET.matcher(q.trim()).matches();
    }

    public static boolean today(String q) {
        return q != null && TODAY.matcher(q.trim()).matches();
    }

    public static boolean open(String q) {
        return q != null && OPEN.matcher(q.trim()).matches();
    }

    /** 「今天」的一段大白话：日程按时间说，件 / 票 / 待办各一句；什么都没有就说没有。 */
    public static String todayReply(List<Map<String, Object>> rows) {
        StringBuilder ev = new StringBuilder(), pk = new StringBuilder(), tk = new StringBuilder(), td = new StringBuilder();
        int nEv = 0, nPk = 0, nTk = 0, nTd = 0, nPast = 0;
        for (Map<String, Object> r : rows) {
            String kind = PlannerMap.s(r, "kind"), title = PlannerMap.s(r, "title"), sub = PlannerMap.s(r, "sub"), flag = PlannerMap.s(r, "flag");
            if ("event".equals(kind)) {
                if ("past".equals(flag)) { nPast++; continue; }
                if (nEv++ < 4) ev.append(ev.length() > 0 ? "；" : "").append(first(sub)).append(" ").append(title);
            } else if ("pickup".equals(kind)) {
                if (nPk++ < 2) pk.append(pk.length() > 0 ? "、" : "").append(title).append(sub.length() > 0 ? "（" + sub + "）" : "");
            } else if ("ticket".equals(kind)) {
                if (nTk++ < 2) tk.append(tk.length() > 0 ? "；" : "").append(title).append("，").append(sub);
            } else if ("todo".equals(kind)) {
                if (nTd++ < 3) td.append(td.length() > 0 ? "、" : "").append(title).append("overdue".equals(flag) ? "（过期了）" : "");
            }
        }
        StringBuilder out = new StringBuilder();
        if (nEv > 0) out.append("今天还有 ").append(nEv).append(" 个安排：").append(ev).append(nEv > 4 ? " 等" : "").append("。");
        else out.append(nPast > 0 ? "今天的日程都过去了。" : "今天没有日程。");
        if (nPk > 0) out.append("有 ").append(nPk).append(" 个件没取：").append(pk).append(nPk > 2 ? " 等" : "").append("。");
        if (nTk > 0) out.append("快用到的票：").append(tk).append("。");
        if (nTd > 0) out.append("待办 ").append(nTd).append(" 件：").append(td).append(nTd > 3 ? " 等" : "").append("。");
        return out.toString();
    }

    /** 「15:00–16:00 · 3 楼」→「15:00」。 */
    static String first(String sub) {
        int i = sub.indexOf(' ');
        String t = i > 0 ? sub.substring(0, i) : sub;
        int d = t.indexOf('–');
        return d > 0 ? t.substring(0, d) : t;
    }
}
