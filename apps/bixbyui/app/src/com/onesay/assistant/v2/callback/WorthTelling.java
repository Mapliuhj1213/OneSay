package com.onesay.assistant.v2.callback;

import org.json.JSONObject;

/**
 * worth_telling 结构化判断（lane-a 第 5 项；研究报告 §3.3.2）。纯 Java，JVM 可测。
 *
 * <p>无人值守的 AGENT 任务在提示词末尾要求模型额外输出一行 JSON：
 * <pre>{"worth_telling": true|false, "urgency": "low|normal|high", "one_line": "一句话结论"}</pre>
 * 规则是确定性的：<b>模型只能下调紧急度、不能上调</b>（任务本身是 NORMAL，模型说 high 也按 NORMAL）；
 * worth_telling = false 或监控类「没有变化」→ 只留历史不打扰；模型没给 / 给坏了 → 按原紧急度照常送达（不因为解析失败漏报）。
 */
public final class WorthTelling {

    private WorthTelling() {}

    public static final String PROMPT_SUFFIX = "\n\n（最后单独一行输出 JSON：{\"worth_telling\": true 或 false, \"urgency\": \"low\" 或 \"normal\" 或 \"high\", "
            + "\"one_line\": \"一句话结论\"}。结果没有变化、没有用户需要知道的新东西时 worth_telling 写 false。）";

    public static final class Verdict {
        public final boolean worth;
        public final DeliveryPolicy.Urgency urgency;
        public final String oneLine;
        /** 正文（去掉了末尾的 JSON 行）。 */
        public final String body;
        public final boolean parsed;

        Verdict(boolean worth, DeliveryPolicy.Urgency urgency, String oneLine, String body, boolean parsed) {
            this.worth = worth;
            this.urgency = urgency;
            this.oneLine = oneLine;
            this.body = body;
            this.parsed = parsed;
        }
    }

    static final String[] NO_CHANGE = {"没有变化", "无变化", "一切正常", "暂无更新", "没有新的", "与上次相同", "跟上次一样"};

    /**
     * @param answer 模型的最终回答（可能带末尾 JSON 行）
     * @param base   任务本身的紧急度（上限）
     */
    public static Verdict judge(String answer, DeliveryPolicy.Urgency base) {
        String a = answer == null ? "" : answer.trim();
        DeliveryPolicy.Urgency b = base == null ? DeliveryPolicy.Urgency.NORMAL : base;
        int i = a.lastIndexOf('{');
        int j = a.lastIndexOf('}');
        JSONObject o = null;
        String body = a;
        if (i >= 0 && j > i && a.substring(i, j + 1).contains("worth_telling")) {
            try {
                o = new JSONObject(a.substring(i, j + 1));
                body = (a.substring(0, i) + a.substring(j + 1)).trim();
            } catch (Exception e) {
                o = null;
            }
        }
        boolean noChange = false;
        for (String k : NO_CHANGE) if (body.contains(k) && body.length() <= 40) noChange = true;
        if (o == null) {
            return new Verdict(!noChange, noChange ? DeliveryPolicy.Urgency.LOW : b, firstLine(body), body, false);
        }
        boolean worth = o.optBoolean("worth_telling", true) && !noChange;
        DeliveryPolicy.Urgency u = parseUrgency(o.optString("urgency", ""), b);
        if (rank(u) > rank(b)) u = b;   // 只能下调
        if (!worth) u = DeliveryPolicy.Urgency.LOW;
        String one = o.optString("one_line", "").trim();
        return new Verdict(worth, u, one.length() > 0 ? one : firstLine(body), body, true);
    }

    static DeliveryPolicy.Urgency parseUrgency(String s, DeliveryPolicy.Urgency def) {
        String t = s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.equals("low")) return DeliveryPolicy.Urgency.LOW;
        if (t.equals("normal")) return DeliveryPolicy.Urgency.NORMAL;
        if (t.equals("high")) return DeliveryPolicy.Urgency.HIGH;
        return def;
    }

    static int rank(DeliveryPolicy.Urgency u) {
        switch (u) {
            case LOW: return 0;
            case NORMAL: return 1;
            default: return 2;
        }
    }

    static String firstLine(String s) {
        if (s == null) return "";
        int n = s.indexOf('\n');
        String f = n < 0 ? s : s.substring(0, n);
        return f.length() > 60 ? f.substring(0, 59) + "…" : f;
    }
}
