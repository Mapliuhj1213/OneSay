package com.onesay.assistant.v2.router;

import android.util.Log;

import com.onesay.assistant.v2.capability.Capability;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 缺参追问（slot filling）—— 单例状态机。
 *
 * <h3>要解决的缺陷（2026-10-04 实测定位）</h3>
 * 用户说「定个闹钟」（**没说要几点**）时：
 * <pre>
 *   L0 规则            → 模板「定{n}点闹钟」需要时间词，无模板可匹配 → 未命中
 *   落到 LLM           → prompt 里 clock.alarm.set 标注 "hour: 整数[0..23],必填"
 *                        LLM 看到"必填"就用一个貌似合理的默认值把 hour 填成 7
 *   返回 clock.alarm.set {"hour":7,"minute":0}
 *   写进 L1 缓存（AGENT 类永久）→ 此后「定个闹钟」永远给用户设 7 点闹钟
 * </pre>
 * 这不只是答错，而是**静默篡改用户意图**（用户没说 7 点），且写进永久缓存无法自愈。
 *
 * <p>同一坑覆盖 14 个能力（闹钟/倒计时/亮度/音量/搜索/记事/打电话…），
 * 它们的共同特征是「**每条 say 模板都带槽位** + **参数表里有 required**」。
 * 模板匹配不上时既不能执行也不能交给 LLM 去猜，唯一正确的做法是**追问**。
 *
 * <h3>状态机</h3>
 * <pre>
 *   IDLE ──检测到"意图明确但缺必填槽"──▶ PENDING(sign, missingParam)
 *   PENDING ──下一轮是纯数值("七点"/"8")──▶ 补槽成功 → 返回可执行 Invocation，回 IDLE
 *   PENDING ──下一轮不是补槽内容──────────▶ 放弃待补，走正常路由，回 IDLE
 * </pre>
 *
 * <p><b>为什么状态很浅</b>：只记住"上一次问的是什么、缺哪个参数"，不记忆多轮。
 * 补槽内容必须是**整句即为数值**（"七点"/"30秒"/"8"），一旦带动作词
 * （"设个八点的闹钟"）说明用户重说了完整句，应当走正常路由而不是补槽 ——
 * 否则会把「定个闹钟」+「设个八点的闹钟」错当成补槽，反而吞掉后半句。
 *
 * <p><b>Java 7 约束</b>：全程不用 lambda / String.join。
 */
public final class SlotFiller {

    private static final String TAG = "SlotFiller";

    /** 追问的虚拟能力签名。它不是注册表里的能力，UI 层需要按"问答"样式呈现。 */
    public static final String SIGN_CLARIFY = "clarify.ask";

    /**
     * 「意图明确但缺必填槽」的候选：动作关键词 -> 能力签名 + 缺哪个参数 + 追问话术。
     *
     * <p>判定顺序即优先级：越靠前的越具体。只收**动作意图明确、参数唯一可问**的场景——
     * 例如"闹钟"必然问"几点"，"倒计时"必然问"多久"。
     * 参数类型必须是单一数值（hour / seconds / level），这样"七点"能直接解析补入；
     * **不收** app.launch（"打开"后面跟的是应用名，是开放式文本，不适合数值补槽）、
     * **不收** phone.call / sms.send（需要人名/号码，属于实体而非数值）。
     */
    private static final SlotSpec[] SPECS = {
            new SlotSpec("闹钟", "clock.alarm.set", "hour",
                    "几点？比如「七点」或「晚上八点半」。"),
            new SlotSpec("叫我", "clock.alarm.set", "hour",
                    "几点叫你？比如「七点」。"),
            new SlotSpec("倒计时", "clock.timer.set", "seconds",
                    "多久？比如「三十秒」或「五分钟」。"),
            new SlotSpec("计时器", "clock.timer.set", "seconds",
                    "计时多久？比如「三十秒」或「五分钟」。"),
            new SlotSpec("定时", "clock.timer.set", "seconds",
                    "定时多久？比如「三十秒」或「五分钟」。"),
            new SlotSpec("亮度", "settings.brightness.set", "level",
                    "调到多少？亮度范围 0~255，也可以说「最低」「一半」「最高」。"),
            new SlotSpec("音量", "volume.media.set", "level",
                    "调到多少？音量范围 0~15，也可以说「一半」「最大」。"),
    };

    private static final class SlotSpec {
        final String keyword;      // 中文动作关键词（如"闹钟"）
        final String sign;         // 目标能力
        final String param;        // 缺失的参数名
        final String question;     // 追问话术

        SlotSpec(String keyword, String sign, String param, String question) {
            this.keyword = keyword;
            this.sign = sign;
            this.param = param;
            this.question = question;
        }
    }

    // ---------- 单例 ----------

    private static volatile SlotFiller sInstance;

    public static SlotFiller getInstance() {
        SlotFiller s = sInstance;
        if (s == null) {
            synchronized (SlotFiller.class) {
                s = sInstance;
                if (s == null) {
                    s = new SlotFiller();
                    sInstance = s;
                }
            }
        }
        return s;
    }

    private SlotFiller() {}

    // ---------- 待补槽状态 ----------

    private String mPendingSign;
    private String mPendingParam;
    private String mPendingQuery;
    private long mPendingAt;

    /** 待补槽状态存活时长：超过就作废（用户可能早就不想设闹钟了）。 */
    private static final long PENDING_TTL_MS = 60_000L;

    private synchronized boolean hasPending() {
        if (mPendingSign == null) return false;
        if (System.currentTimeMillis() - mPendingAt > PENDING_TTL_MS) {
            clearPending();
            return false;
        }
        return true;
    }

    private synchronized void setPending(String sign, String param, String query) {
        mPendingSign = sign;
        mPendingParam = param;
        mPendingQuery = query;
        mPendingAt = System.currentTimeMillis();
    }

    private synchronized void clearPending() {
        mPendingSign = null;
        mPendingParam = null;
        mPendingQuery = null;
        mPendingAt = 0L;
    }

    // ---------- 对外入口 ----------

    /**
     * 第一步：判断一句话是否是「意图明确但缺必填槽」。
     *
     * <p><b>只在 L0 未命中时调用</b>（L0 命中了就说明参数齐全，不需要追问）。
     *
     * @return 追问 Invocation（sign={@link #SIGN_CLARIFY}）；不需要追问返回 null
     */
    public Invocation maybeAsk(String query) {
        if (query == null || query.trim().length() == 0) return null;
        String q = L0RuleRouter.normalize(query);
        if (q.length() == 0) return null;

        // 若句子里已经含数值，说明参数齐全（多半是 L0 模板没覆盖到的说法），
        // 不归 slot filling 管，放行给后面的关键词倒排 / LLM 去解析。
        if (containsNumber(q)) return null;

        // 句子里已含**方向/档位语义**时也不算缺参。
        //   实测踩过：「天太黑了屏幕刺眼帮我调低点亮度」里"调低"已经表达了方向，
        //   属 settings.brightness.down 的语义，却被"亮度"关键词误判成"缺 level"而追问。
        //   这类词本身就指定了取值（低/高/最大/最小…），不需要再问"调到多少"。
        if (containsDirection(q)) return null;

        for (SlotSpec spec : SPECS) {
            if (q.contains(spec.keyword)) {
                Capability cap = com.onesay.assistant.v2.capability.CapabilityRegistry
                        .getInstance().describe(spec.sign);
                if (cap == null) continue;
                // 二次确认该能力确实有这个必填参数（防御：能力表改了就自动失效）
                com.onesay.assistant.v2.capability.ParamsSchema.PropertyRule rule =
                        cap.paramsSchema.getRules().get(spec.param);
                if (rule == null || !rule.required) continue;

                setPending(spec.sign, spec.param, query);
                Log.i(TAG, "[CLARIFY] 意图明确但缺必填槽: " + spec.sign
                        + " 缺=" + spec.param + " 语句=" + query);
                return buildClarify(spec, query);
            }
        }
        return null;
    }

    /**
     * 第二步：判断下一轮输入是否是**补槽应答**。
     *
     * <p>判据（必须全部满足）：
     * <ol>
     *   <li>当前确实有未过期的待补状态；</li>
     *   <li>整句剥离数量词后是个纯数值 —— "七点"/"30秒"/"8" 算，
     *       "设个八点的闹钟" 不算（它自己带动作词，是重说了完整句）。</li>
     * </ol>
     *
     * @return 补全后可执行的 Invocation；不是补槽应答返回 null（调用方继续正常路由）
     */
    public Invocation maybeFill(String query) {
        if (!hasPending()) return null;
        if (query == null || query.trim().length() == 0) return null;

        String q = L0RuleRouter.normalize(query);
        if (q.length() == 0) return null;

        // 带动作词 → 用户重说了完整句，放弃待补状态（避免吞掉后半句）
        if (hasActionWord(q)) {
            Log.i(TAG, "[CLARIFY] 补槽轮出现动作词，放弃待补走正常路由: " + com.onesay.assistant.v2.util.LogText.u(query));
            clearPending();
            return null;
        }

        // 档位词优先于数值解析：否则「一半」会被 NumberParser 拆成"一"→1，
        // 而不是"中点"（实测踩过：亮度一半被当成 1）。
        Long val = parseLevelWord(q);
        boolean byLevelWord = (val != null);
        if (val == null) {
            val = NumberParser.parse(q);
        }
        if (val == null) {
            // 既不是数值也不是档位词（例如用户改主意问了别的）→ 放弃待补
            Log.i(TAG, "[CLARIFY] 补槽轮无法解析数值，放弃待补走正常路由: " + com.onesay.assistant.v2.util.LogText.u(query));
            clearPending();
            return null;
        }

        String sign = mPendingSign;
        String param = mPendingParam;
        String origin = mPendingQuery;
        long raw = val.longValue();
        clearPending();

        Capability cap = com.onesay.assistant.v2.capability.CapabilityRegistry.getInstance().describe(sign);
        if (cap == null) return null;

        long v = byLevelWord ? resolveLevel(cap, param, raw) : clamp(cap, param, raw);

        JSONObject args = new JSONObject();
        try {
            args.put(param, v);
            // 闹钟补 hour 时顺带把 minute 补 0（模板默认也是 0），避免 LLM 再插一脚
            if ("clock.alarm.set".equals(sign) && "hour".equals(param)) {
                args.put("minute", 0L);
            }
        } catch (Exception ignored) {}

        Log.i(TAG, "[CLARIFY-FILL] 补槽成功: " + sign + " " + param + "=" + v
                + "（原始问句=" + origin + "，补槽答句=" + query + "，档位词=" + byLevelWord + "）");
        return new Invocation(sign, args, 1.0, Invocation.RouteSource.L0_RULE, origin);
    }

    // ---------- 档位词（最低/一半/最高）----------

    /** 档位词哨兵值：与真实数值域冲突可能性极低（负数 + 极值）。 */
    private static final long LEVEL_LOW = -1001L;
    private static final long LEVEL_MID = -1002L;
    private static final long LEVEL_HIGH = -1003L;

    /**
     * 把「最低/最小/一半/中等/最高/最大」映射成档位哨兵。
     * 用户被追问"调到多少"时，说档位词比说数字自然得多（亮度 0~255 用户记不住）。
     */
    private static Long parseLevelWord(String q) {
        String s = q.replaceAll("[的了呀啊吧呢嘛]", "").trim();
        if (s.length() == 0) return null;
        if (s.equals("最低") || s.equals("最小") || s.equals("最暗") || s.equals("最弱")
                || s.equals("最低档") || s.equals("最小声")) {
            return Long.valueOf(LEVEL_LOW);
        }
        if (s.equals("最高") || s.equals("最大") || s.equals("最亮") || s.equals("最强")
                || s.equals("最高档") || s.equals("最大声") || s.equals("满") || s.equals("满格")) {
            return Long.valueOf(LEVEL_HIGH);
        }
        if (s.equals("一半") || s.equals("中等") || s.equals("中间") || s.equals("中档")
                || s.equals("适中")) {
            return Long.valueOf(LEVEL_MID);
        }
        return null;
    }

    /** 按能力参数的 [min,max] 把档位哨兵换算成真实值。 */
    private static long resolveLevel(Capability cap, String param, long level) {
        com.onesay.assistant.v2.capability.ParamsSchema.PropertyRule rule =
                cap.paramsSchema.getRules().get(param);
        long lo = (rule != null && rule.min != null) ? rule.min.longValue() : 0L;
        long hi = (rule != null && rule.max != null) ? rule.max.longValue() : 100L;
        if (level == LEVEL_LOW) return lo;
        if (level == LEVEL_HIGH) return hi;
        return lo + (hi - lo) / 2;   // 一半
    }

    /** 供 UI 判断某个 Invocation 是不是追问。 */
    public static boolean isClarify(Invocation inv) {
        return inv != null && SIGN_CLARIFY.equals(inv.sign);
    }

    // ---------- 内部工具 ----------

    private static Invocation buildClarify(SlotSpec spec, String query) {
        JSONObject args = new JSONObject();
        try {
            args.put("text", spec.question);
            args.put("query", query);
            args.put("pendingSign", spec.sign);
            args.put("pendingParam", spec.param);
        } catch (Exception ignored) {}
        return new Invocation(SIGN_CLARIFY, args, 1.0, Invocation.RouteSource.L0_RULE, query);
    }

    /**
     * 句子里是否已含**有意义**的数值。
     *
     * <p>用于区分「定个闹钟」（缺参，要追问）与「定个八点的闹钟」（有参，不该追问）。
     *
     * <p><b>踩过的坑</b>：一开始只扫 "零〇一二两三四五六七八九十百千"，
     * 结果「调一下亮度」里的"一"（"一下"的"一"）被误判成数字 → 不再追问。
     * 口语里"一下/一个/一起/一样/一直/一边"的"一"是助词不是数量。
     * 所以先剥离这些口语搭配，再看剩下的有没有数字。
     */
    private static boolean containsNumber(String q) {
        String s = q;
        // 剥离"一"的口语助词搭配（这些"一"不表数量）
        final String[] STOP = {
                "一下", "一个", "一起", "一样", "一直", "一边", "一定", "一般",
                "一点", "一丝", "一切", "一遍", "一口", "一起", "一事", "一些"
        };
        for (String t : STOP) {
            s = s.replace(t, "");
        }
        // 阿拉伯数字
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') return true;
        }
        // 中文数字
        final String cn = "零〇一二两三四五六七八九十百千";
        for (int i = 0; i < cn.length(); i++) {
            if (s.indexOf(cn.charAt(i)) >= 0) return true;
        }
        return false;
    }

    /**
     * 句子里是否已含**方向/档位语义**（"调低/调高/最低/最高/一半/最大/最小"…）。
     *
     * <p>这些词本身就指定了取值方向，说明用户已经表达了诉求，
     * 不该再问"调到多少"。例如「帮我调低点亮度」是 brightness.down 的语义，
     * 而不是"缺 level 参数的 brightness.set"。
     */
    private static boolean containsDirection(String q) {
        final String[] DIRS = {
                "调低", "调高", "调小", "调大", "降低", "提高", "减小", "增大",
                "最低", "最小", "最暗", "最弱", "最高", "最大", "最亮", "最强",
                "一半", "中等", "适中", "中档", "满格", "静音", "最大声", "最小声",
                "调低点", "调高点", "低点", "高点", "小一点", "大一点", "低一点", "高一点"
        };
        for (String d : DIRS) {
            if (q.indexOf(d) >= 0) return true;
        }
        return false;
    }

    /**
     * 是否含"动作词"。补槽轮里出现动作词 = 用户重说了完整句，不是单纯回答"几点"。
     *
     * <p>只收真正表示"做一件事"的动词，避免把"点/分/秒"这类量词误判。
     */
    private static boolean hasActionWord(String q) {
        final String[] ACTIONS = {
                "闹钟", "倒计时", "计时器", "定时", "亮度", "音量", "叫我",
                "打开", "关闭", "设", "调", "记", "搜", "查", "发", "打",
                "播放", "暂停", "截图", "导航"
        };
        for (String a : ACTIONS) {
            if (q.contains(a)) return true;
        }
        return false;
    }

    /** 把数值夹到能力声明的 [min,max] 内（能力表没写就原样返回）。 */
    private static long clamp(Capability cap, String param, long v) {
        com.onesay.assistant.v2.capability.ParamsSchema.PropertyRule rule =
                cap.paramsSchema.getRules().get(param);
        if (rule == null) return v;
        long lo = (rule.min != null) ? rule.min.longValue() : Long.MIN_VALUE;
        long hi = (rule.max != null) ? rule.max.longValue() : Long.MAX_VALUE;
        if (v < lo) v = lo;
        if (v > hi) v = hi;
        return v;
    }

    /** 测试用：清空待补状态。 */
    public synchronized void resetForTesting() {
        clearPending();
    }

    /** 观测用：当前是否有待补状态。 */
    public synchronized boolean hasPendingForProbe() {
        return hasPending();
    }

    /**
     * 观测用：把 SPECS 里的关键词清单导出（供自测断言"哪些意图可被追问"）。
     */
    public static List<String> keywords() {
        List<String> out = new ArrayList<String>();
        for (SlotSpec s : SPECS) out.add(s.keyword);
        return out;
    }
}
