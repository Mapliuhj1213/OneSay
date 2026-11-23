package com.onesay.assistant.v2.router.longctx;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ★ fix/1123-longctx-phone 第 6 条：出口自检 —— 答复发出去之前看一眼，是不是这三种毛病：
 * <ul>
 *   <li>{@link Verdict#ECHO 复述}：开头一句基本就是把用户的话又说了一遍（「你问的是……」「关于你说的……」）；</li>
 *   <li>{@link Verdict#REPEAT 复读}：答复和之前某一轮的答复几乎一样，可这次问的不是那件事；</li>
 *   <li>{@link Verdict#OFF_TOPIC 偏题}：答复和这句话没什么关系，却和之前某个已经答完的问题对得上。</li>
 * </ul>
 * 只做确定性的字面判断（二字组），宁可漏判不误判：省略式追问（「那明天呢」「这个呢」）不判偏题；
 * 用户自己要「再说一遍」不判复读。命中了由调用方带着提示重答<b>一次</b>。
 */
public final class ReplyCheck {
    private ReplyCheck() {
    }

    public enum Verdict { OK, ECHO, REPEAT, OFF_TOPIC }

    public static final class Result {
        public final Verdict verdict;
        /** REPEAT / OFF_TOPIC 时对上的那条旧问题原话。 */
        public final String other;

        Result(Verdict v, String o) {
            verdict = v;
            other = o;
        }

        public boolean bad() {
            return verdict != Verdict.OK;
        }

        @Override public String toString() {
            return verdict + (other == null ? "" : "(" + TextSim.head(other, 20) + ")");
        }
    }

    static final Result OK = new Result(Verdict.OK, null);

    static final Pattern ECHO_LEAD = Pattern.compile("^(你(刚才|刚刚)?(问|说|想知道|提到|是想问|的问题)(的是|了|是|：|:|，|,)?|关于你(问|说|提)的|用户(问|说|想知道)|您(问|说)的是)");
    static final Pattern SENT_END = Pattern.compile("[。！？!?\n]");
    static final Pattern FOLLOW = Pattern.compile("^(那|那么|这|这个|它|他|她|刚才|还有|然后|再|继续|也|另外|顺便|所以|为啥|为什么呢|呢|还)");
    static final Pattern WANT_REPEAT = Pattern.compile("再说一遍|再讲一遍|重复一下|再说一次|刚才说的什么|你刚说啥|没听清");

    /**
     * @param prior 之前各轮（旧 → 新），每项 {问句, 答复或结论}；可含已折叠轮次的结论
     */
    public static Result check(String query, String answer, List<String[]> prior) {
        if (query == null || answer == null) return OK;
        String q = query.trim(), a = answer.trim();
        if (q.length() == 0 || a.length() == 0) return OK;
        // ---- 复述
        Set<String> qg = TextSim.grams(q);
        java.util.regex.Matcher m = SENT_END.matcher(a);
        String first = m.find() ? a.substring(0, m.start()) : a;
        if (first.length() > 80) first = first.substring(0, 80);
        if (qg.size() >= 4) {
            double c = TextSim.containment(qg, TextSim.grams(first));
            boolean lead = ECHO_LEAD.matcher(first).find();
            if (c >= 0.85 && (lead || first.length() <= q.length() + 12) || lead && c >= 0.6) return new Result(Verdict.ECHO, null);
        }
        if (prior == null || prior.isEmpty()) return OK;
        Set<String> ag = TextSim.grams(a);
        // ---- 复读旧答复
        if (!WANT_REPEAT.matcher(q).find() && ag.size() >= 8) {
            for (int i = prior.size() - 1; i >= 0; i--) {
                String[] p = prior.get(i);
                if (p == null || p[0] == null || p[1] == null || p[1].length() < 8) continue;
                if (TextSim.jaccard(qg, TextSim.grams(p[0])) >= 0.5) continue;   // 问的就是同一件事
                if (TextSim.jaccard(ag, TextSim.grams(p[1])) >= 0.6) return new Result(Verdict.REPEAT, p[0]);
            }
        }
        // ---- 偏题（省略式追问不判）
        Set<String> qc = TextSim.content(q);
        if (qc.size() < 3 || FOLLOW.matcher(q).find()) return OK;
        double rel = TextSim.containment(qc, ag);
        if (rel >= 0.2) return OK;
        for (int i = prior.size() - 1; i >= 0; i--) {
            String[] p = prior.get(i);
            if (p == null || p[0] == null) continue;
            Set<String> pc = TextSim.content(p[0]);
            if (pc.size() < 2) continue;
            double rp = TextSim.containment(pc, ag);
            if (rp >= 0.5 && rp - rel >= 0.3) return new Result(Verdict.OFF_TOPIC, p[0]);
        }
        return OK;
    }
}
