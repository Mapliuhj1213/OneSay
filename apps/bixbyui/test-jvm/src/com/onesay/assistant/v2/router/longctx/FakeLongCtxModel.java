package com.onesay.assistant.v2.router.longctx;

import com.onesay.assistant.v2.router.longctx.LongCtxEvalData.Kind;
import com.onesay.assistant.v2.router.longctx.LongCtxEvalData.Q;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ fix/1123-longctx-phone：确定性「长上下文假模型」—— 只看<b>真代码组出来的请求体</b>，按固定规则模拟
 * DeepSeek 长会话里常见的毛病。规则全写死在这里、改前改后同一份，不看任何「当前是新版还是旧版」的开关，
 * 只看请求里实际有什么：
 *
 * <ol>
 *   <li><b>偏题</b>：最后一句没被单独标成「最新一句」时，历史越长越容易去答历史里更早的问题
 *       （概率 min(40, 4×(历史用户句数−3))%；历史摘要是「问：…→答：…」流水账再 +10%，因为流水账里的旧问题看起来像还没答）。
 *       标了最新一句：只在账本里列着「没答完」的问题时有 8% 会去答那条。</li>
 *   <li><b>复述</b>：最后 800 字里没有「别先复述用户的话」这类提醒、历史 ≥4 句时，min(30, 3×句数)% 先把问题复述一遍。</li>
 *   <li><b>人设失效</b>：最后 800 字里没有口吻守则、请求 &gt;3000 字时 40% 冒出「呗」。</li>
 *   <li><b>技能准则</b>：请求里有技能正文（出行清单那条）才会在答复末尾附「【出行清单】」。</li>
 *   <li><b>遗忘 / 幻觉</b>：问早先说过的事时，只在请求全文里找标准值；找不到 → 60% 编一个错值（幻觉）、40% 说不记得（遗忘）；
 *       找到了但所有出现位置都在请求中段（20%–80%）且请求 &gt;3000 字 → 50% 当没看见（lost in the middle）。</li>
 *   <li>带「【系统提示】…重答」的重答请求：不偏题、不复述（被点名了）；其余规则照旧。</li>
 *   <li>加压（{@link #ignoreMarksPct}）：按比例当末尾标记 / 守则不存在，看出口自检能兜回多少。</li>
 * </ol>
 * 随机数 = 场景种子 × 轮次 × 规则号 × 第几次请求的确定性哈希。这只证明「机制有没有真接进请求、按设计生效」，
 * 不等于真模型上的效果；真模型评测见 LongCtxEvalReal（不在单测里跑、也不带任何 Key）。
 */
public class FakeLongCtxModel {

    public static final String LATEST_MARK = "只答这一句";
    public static final String ANTI_ECHO_MARK = "别先把用户的话复述";
    public static final String PERSONA_MARK = "不用「啥」「咋」「呗」";
    public static final String SKILL_MARK = "【出行清单】";
    public static final String REDO_MARK = "【系统提示】";

    int seed, turn, calls;
    /** 加压：这么多 % 的请求假模型「无视」末尾的最新一句标记与守则（模拟真模型照样跑偏），用来看出口自检兜不兜得住。重答请求不受影响。 */
    public int ignoreMarksPct;
    public final List<String> bodies = new ArrayList<String>();

    public void at(int seed, int turn) {
        this.seed = seed;
        this.turn = turn;
    }

    int rnd(int rule) {
        int x = seed * 73856093 ^ turn * 19349663 ^ rule * 83492791 ^ calls * 2654435;
        x ^= x >>> 16;
        x *= 0x45d9f3b;
        x ^= x >>> 16;
        return (x & 0x7fffffff) % 100;
    }

    static boolean nearEnd(String req, String s) {
        int i = req.lastIndexOf(s);
        return i >= 0 && i >= req.length() - 800;
    }

    /** 收请求体，回答复正文。 */
    public String answer(String body) {
        bodies.add(body);
        calls++;
        try {
            JSONObject b = new JSONObject(body);
            JSONArray ms = b.getJSONArray("messages");
            String system = ms.getJSONObject(0).optString("content", "");
            String last = ms.getJSONObject(ms.length() - 1).optString("content", "");
            StringBuilder all = new StringBuilder();
            List<String> histUser = new ArrayList<String>();
            String summary = null;
            for (int i = 0; i < ms.length(); i++) {
                JSONObject m = ms.getJSONObject(i);
                String c = m.optString("content", "");
                all.append(c).append('\n');
                if (i > 0 && i < ms.length() - 1 && "user".equals(m.optString("role"))) {
                    histUser.add(c);
                    if (c.startsWith("（更早的对话摘要") && i + 1 < ms.length() - 1) summary = ms.getJSONObject(i + 1).optString("content", "");
                }
            }
            String req = all.toString();
            boolean redo = last.contains(REDO_MARK) && last.contains("重答");
            Q target = latest(last);
            if (target == null) return "好的。";
            boolean ignore = !redo && rnd(7) < ignoreMarksPct;

            // ---- 偏题
            boolean marked = !ignore && last.contains(LATEST_MARK);
            int legacyLines = 0;
            List<Q> cands = new ArrayList<Q>();
            for (String u : histUser) {
                Q q = LongCtxEvalData.byText(u);
                if (q != null && q != target && judged(q) && !cands.contains(q)) cands.add(q);
            }
            if (summary != null) {
                for (String line : summary.split("\n")) {
                    if (!line.startsWith("问：")) continue;
                    legacyLines++;
                    String head = line.substring(2, Math.min(line.length(), line.indexOf(" → ") > 0 ? line.indexOf(" → ") : line.length())).replace("…", "");
                    for (Q q : LongCtxEvalData.pool()) {
                        if (q != target && judged(q) && head.length() >= 4 && q.text.startsWith(head) && !cands.contains(q)) cands.add(q);
                    }
                }
            }
            List<Q> openListed = new ArrayList<Q>();
            int oi = last.indexOf("没答完");
            if (oi >= 0) {
                int nl = last.indexOf('\n', oi);
                String seg = nl > 0 ? last.substring(oi, nl) : last.substring(oi);
                for (Q q : cands) if (seg.contains(q.text)) openListed.add(q);
            }
            int hp = histUser.size();
            int pDrift;
            if (redo) pDrift = 0;
            else if (marked) pDrift = openListed.isEmpty() ? 0 : 8;
            else pDrift = Math.min(40, 4 * Math.max(0, hp - 3)) + (legacyLines > 0 ? 10 : 0);
            Q answered = target;
            if (target.kind != Kind.RECALL && !cands.isEmpty() && rnd(1) < pDrift) {
                List<Q> pickFrom = marked && !openListed.isEmpty() ? openListed : cands;
                answered = pickFrom.get(rnd(9) % pickFrom.size());
            }

            StringBuilder out = new StringBuilder();
            // ---- 复述
            int pEcho = redo || !ignore && nearEnd(req, ANTI_ECHO_MARK) ? 0 : (hp >= 4 ? Math.min(30, 3 * hp) : 0);
            if (rnd(2) < pEcho) out.append("你问的是「").append(target.text).append("」。");

            // ---- 正文
            if (answered.kind == Kind.RECALL) {
                String hay = req.replace(target.text, "");
                int at = hay.indexOf(target.value);
                boolean seen = at >= 0;
                if (seen && hay.length() > 3000) {
                    boolean allMiddle = true;
                    for (int i = at; i >= 0; i = hay.indexOf(target.value, i + 1)) {
                        double pos = (double) i / hay.length();
                        if (pos < 0.2 || pos > 0.8) allMiddle = false;
                    }
                    if (allMiddle && rnd(4) < 50) seen = false;
                }
                if (seen) out.append("你之前说过，是").append(target.value).append("。");
                else if (rnd(5) < 60) out.append("我记得是").append(target.wrongValue).append("。");
                else out.append("这个我不记得了，你再跟我说一下？");
            } else {
                out.append(answered.answer);
            }
            // ---- 技能准则
            if (answered.kind == Kind.TRIP && req.contains(SKILL_MARK)) out.append(SKILL_MARK).append("证件 / 充电器 / 换洗衣物");
            // ---- 人设
            int pPersona = !ignore && nearEnd(req, PERSONA_MARK) ? 0 : (req.length() > 3000 ? 40 : 0);
            if (rnd(3) < pPersona) out.append("就这么回事呗。");
            return out.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static boolean judged(Q q) {
        return q.kind == Kind.CHAT || q.kind == Kind.FOLLOW || q.kind == Kind.TRIP || q.kind == Kind.STATE;
    }

    /** 认最后一句：有「只答这一句」标记就取标记后的原话；否则整条（或最后出现的题面）。 */
    static Q latest(String last) {
        int i = last.lastIndexOf(LATEST_MARK);
        String s = last;
        if (i >= 0) {
            s = last.substring(i + LATEST_MARK.length());
        }
        Q best = null;
        int bestAt = -1;
        for (Q q : LongCtxEvalData.pool()) {
            int at = s.lastIndexOf(q.text);
            if (at < 0) continue;
            if (at > bestAt || at == bestAt && q.text.length() > best.text.length()) {
                best = q;
                bestAt = at;
            }
        }
        return best;
    }
}
