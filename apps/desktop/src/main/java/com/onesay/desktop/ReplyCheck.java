package com.onesay.desktop;

import java.util.List;
import java.util.regex.Pattern;

/**
 * [longctx-1123] 出口自检：模型的答复是不是在复读用户的话、是不是答偏到前面已经答过的问题上、是不是把程序提示念出来了。
 * 查出来就打回重答一次（{@link ChatAgent#selfCheck}）。宁可漏判也别误判：拿不准的（追问、用户自己要求再说一遍）一律放过。
 */
final class ReplyCheck {
    private ReplyCheck() {}

    /** 追问 / 指代上一句的说法：这种句子答复和上文更像是正常的，不判偏题。 */
    static final Pattern FOLLOW = Pattern.compile("^(那|那么|还有|另外|然后|再|所以|它|这个|那个|上面|刚才|前面|继续|接着|换成|也)|它|这个|那个|刚才|上面|前面|上一个|再说|再讲|重复|又|还是");
    /** 用户自己要求复述 / 照抄的说法。 */
    static final Pattern WANTS_COPY = Pattern.compile("重复|复述|照抄|原样|再说一遍|念一遍|跟我说一遍|翻译|改写|润色|纠错|改成|帮我写|总结");

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{IsHan}a-z0-9]", "");
    }

    /** 两段最长的公共子串长度（规范化后）。 */
    static int lcs(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        int best = 0;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = a.charAt(i - 1) == b.charAt(j - 1) ? prev[j - 1] + 1 : 0;
                if (cur[j] > best) best = cur[j];
            }
            int[] t = prev; prev = cur; cur = t;
            java.util.Arrays.fill(cur, 0);
        }
        return best;
    }

    /**
     * 判一条答复：返回打回的原因（给 {@link #nudge} 用），没问题返回 null。
     *
     * @param reply        模型的答复（去掉了结果标记）
     * @param latest       用户最新一句
     * @param earlierQs    这一段对话里更早、已经答过的用户问题（新的在后）
     * @param earlierAns   和 earlierQs 一一对应的答复
     */
    static String why(String reply, String latest, List<String> earlierQs, List<String> earlierAns) {
        String r = norm(reply), l = norm(latest);
        if (r.isEmpty()) return null;
        if (reply.contains("OneSay 程序提示") || reply.contains("【用户最新一句")) return "leak";
        boolean copyOk = WANTS_COPY.matcher(latest == null ? "" : latest).find();
        // 1) 原样复读最新这句：答复里整句都是用户原话，几乎没有别的内容
        if (!copyOk && l.length() >= 6 && r.contains(l) && r.length() <= l.length() * 3 / 2 + 12) return "echo";
        // 2) 复读更早的某句用户原话：答复的大半是那句话
        if (!copyOk && earlierQs != null) for (String q : earlierQs) {
            String nq = norm(q);
            if (nq.length() < 12 || nq.length() > 1500) continue;
            int k = lcs(r.length() > 1500 ? r.substring(0, 1500) : r, nq);
            if (k >= 12 && k * 10 >= r.length() * 6) return "echo_old:" + q;
        }
        // 3) 答偏：答复明显在说前面某个已经答过的问题，和最新这句几乎不沾边
        String lt = latest == null ? "" : latest.trim();
        if (earlierQs == null || earlierQs.isEmpty() || l.length() < 6 || lt.length() <= 8 || FOLLOW.matcher(lt).find()) return null;
        double sl = Chat.related(reply, latest);
        if (sl >= 0.08) return null;
        for (int i = earlierQs.size() - 1; i >= 0; i--) {
            String q = earlierQs.get(i), a = earlierAns != null && i < earlierAns.size() ? earlierAns.get(i) : "";
            if (Chat.related(latest, q) >= 0.3) continue; // 用户又问了一遍同一个问题：答得像以前正常
            double sq = Chat.related(reply, q), sa = a == null || a.isBlank() ? 0 : Chat.related(reply, a);
            boolean sameAsOld = sa >= 0.6 && norm(a).length() >= 20; // 和以前某个实打实的回答几乎一样（「好的，记下了」这种短回应不算）
            if (sameAsOld || (sq >= 0.18 && sq >= sl * 2 + 0.08)) return "off:" + q;
        }
        return null;
    }

    /** 打回的提示（内部提示，不进对话）。 */
    static String nudge(String why, String latest) {
        String what;
        if (why.equals("leak")) what = "把 OneSay 的程序提示当成了回答念了出来";
        else if (why.equals("echo")) what = "只是把用户的话原样复述了一遍，没有回答";
        else if (why.startsWith("echo_old:")) what = "在复述用户前面说过的话「" + ChatAgent.clip(why.substring(9), 40) + "」";
        else what = "答的是前面已经答过的「" + ChatAgent.clip(why.substring(4), 40) + "」，不是用户现在问的";
        return "（OneSay 内部提示）你刚才的回答" + what + "。不要复述用户的话，也不要再答前面的问题。重新回答，只针对用户最新这一句：「" + ChatAgent.clip(latest == null ? "" : latest.trim(), 300) + "」";
    }
}
