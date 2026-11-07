package com.onesay.assistant.v2.router.cache;

import com.onesay.assistant.v2.router.IntentClassifier;
import com.onesay.assistant.v2.router.TimeContext;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板化技能缓存的策略核心（P0-5 + P1-8，对应审计 §8.3）。
 *
 * <h3>铁律</h3>
 * <ol>
 *   <li><b>只写"已验证成功"</b>：写入点在执行完成之后（{@link #recordSuccess} 需要一个 ok 的
 *       {@link Outcome}）；失败、被拒、取消、超时都不写。</li>
 *   <li><b>参数必须来自原句</b>：数字参数必须能在原句里找到（"定个闹钟"→hour=7 不写，杜绝投毒）；
 *       0 只在有其它参数已落地时作为隐含默认放行（"七点叫醒我"的 minute=0）。</li>
 *   <li><b>槽位模板</b>："亮度调到30" 写入为 "亮度调到{n}" + {"level":"{n0}"}，"亮度调到50" 复用并代入 50。</li>
 *   <li><b>分组匹配</b>：ASK 条目永远满足不了 DO 查询，反之亦然。</li>
 *   <li><b>分类 TTL</b>：DO 90 天滑动；稳定 ASK 3 天；时效类 ASK 不写；负缓存 10 分钟；纠错 1 天。</li>
 *   <li><b>失效</b>：能力指纹变了（改/删能力）→ 删；缓存纪元变了 → 删；回放连续失败 2 次 → 隔离。</li>
 *   <li><b>纠错</b>：用户紧接着说"不对/错了" → 删除刚才那条并加 1 天负缓存。</li>
 * </ol>
 *
 * <p>纯 Java（只依赖 org.json 与本包/路由包的纯函数），存储与能力表都由接口注入，JVM 可测。
 */
public final class SkillCache {

    /**
     * 问答提示词形态改了就换这个串（rel 上机：改了「一句话 / 翻译只给译文」后，
     * 缓存还在吐旧的长答案）。旧条目命中时按版本不符删除。
     */
    public static final String ASK_CAP_VERSION = "ask-20261103t";


    // ---------------- 常量 ----------------

    public static final String GRP_ASK = "ASK";
    public static final String GRP_DO = "DO";

    public static final long DAY = 24L * 3600 * 1000;
    public static final long TTL_DO = 90 * DAY;
    public static final long TTL_ASK = 3 * DAY;
    public static final long TTL_NEGATIVE = 10L * 60 * 1000;
    public static final long TTL_CORRECTION = DAY;
    public static final int CAPACITY = 500;
    /** 回放连续失败多少次进隔离。 */
    public static final int QUARANTINE_AFTER = 2;
    /** 用户纠错的有效窗口：上一次命中/写入后多久内说"不对"才算纠它。 */
    public static final long CORRECTION_WINDOW_MS = 30_000L;

    /** 允许做"文本槽"泛化的参数名（只这些；其余字符串参数作常量或拒写）。 */
    private static final String[] TEXT_SLOT_PARAMS = {
            "app", "keyword", "query", "contact", "name", "title", "url",
    };
    /** 文本槽取值里不能出现的词（出现说明匹配吞掉了第二件事）。 */
    private static final String[] SLOT_POISON = {
            "和", "然后", "并且", "再", "顺便", "接着", "同时", "还有", "以及", "打开", "关闭", "关掉",
    };

    // ---------------- 注入接口 ----------------

    /** 能力表视图（生产：CapabilityRegistry；测试：假实现）。 */
    public interface CapabilityOracle {
        /** 能力契约指纹；能力不存在返回 null。 */
        String versionOf(String sign);

        /** 风险等级（0/1/2）；不存在返回 Integer.MAX_VALUE。 */
        int risk(String sign);

        /** 代入槽值后的参数是否通过 Schema 校验（可就地补默认值）。 */
        boolean validate(String sign, JSONObject args);
    }

    /** 时钟（测试可注入）。 */
    public interface Clock {
        long now();
    }

    /** 执行结果摘要 —— 只用 ExecutionResult 已有字段（ok / code / verified）换算，不依赖执行层类。 */
    public static final class Outcome {
        public final boolean ok;
        public final String code;
        public final boolean verified;
        /** 用户在确认卡上拒绝 / 取消 / 超时（不是能力的错，不计失败次数）。 */
        public final boolean userDenied;

        public Outcome(boolean ok, String code, boolean verified, boolean userDenied) {
            this.ok = ok;
            this.code = code;
            this.verified = verified;
            this.userDenied = userDenied;
        }

        public static Outcome success(boolean verified) {
            return new Outcome(true, "OK", verified, false);
        }

        public static Outcome failed(String code) {
            return new Outcome(false, code, false, false);
        }

        public static Outcome denied() {
            return new Outcome(false, "RISK_REJECTED", false, true);
        }

        /** 真正成功：ok 且 code==OK（VERIFY_FAILED 之类即便 ok 也不算）。 */
        public boolean isSuccess() {
            return ok && (code == null || "OK".equals(code));
        }
    }

    /** 命中结果。 */
    public static final class Hit {
        public final String sign;
        public final JSONObject args;
        public final String key;
        public final String grp;
        /** 是否经由槽位模板代入（而非与写入时同一句话）。 */
        public final boolean generalized;

        Hit(String sign, JSONObject args, String key, String grp, boolean generalized) {
            this.sign = sign;
            this.args = args;
            this.key = key;
            this.grp = grp;
            this.generalized = generalized;
        }

        /** ASK 命中时的答案文本。 */
        public String text() {
            return args != null ? args.optString("text", null) : null;
        }
    }

    /** 写入结论（日志与测试断言用）。 */
    public enum Record {
        VERIFIED, CANDIDATE, PROMOTED,
        REJ_LABEL, REJ_SIGN, REJ_FAILED, REJ_RISK, REJ_UNKNOWN_CAP, REJ_TIME_SENSITIVE,
        REJ_CONTEXT, REJ_INVENTED_ARG, REJ_REFUSAL, REJ_NEGATIVE, REJ_SELFTEST
    }

    // ---------------- 状态 ----------------

    private final SkillStore mStore;
    private final String mEpoch;
    private final CapabilityOracle mOracle;
    private final Clock mClock;

    /** 最近一次命中或写入（供"不对/错了"纠错）。 */
    private String mLastKey;
    private String mLastGrp;
    private long mLastTs;

    public SkillCache(SkillStore store, String epoch, CapabilityOracle oracle, Clock clock) {
        this.mStore = store;
        this.mEpoch = epoch;
        this.mOracle = oracle;
        this.mClock = clock != null ? clock : new Clock() {
            @Override public long now() { return System.currentTimeMillis(); }
        };
    }

    public SkillStore store() { return mStore; }

    public String epoch() { return mEpoch; }

    public static String groupOf(String label) {
        if (label == null) return null;
        if ("ASK".equals(label)) return GRP_ASK;
        if ("DO_READ".equals(label) || "DO_ACT".equals(label)) return GRP_DO;
        return null;   // MIXED / CLARIFY / AMBIGUOUS 不参与缓存
    }

    private static String keyFor(CacheKey k, String grp) {
        return GRP_ASK.equals(grp) ? k.literal : k.shape;
    }

    // =====================================================================
    // 写入
    // =====================================================================

    /**
     * 执行成功后写入（DO：单步能力调用；ASK：sign=ask.reply，args.text=终稿）。
     *
     * @param contextual 本轮是否带会话历史（答案依赖上下文 → 不写）
     */
    public synchronized Record recordSuccess(String utterance, String label, String sign, JSONObject args,
                                             Outcome outcome, boolean contextual) {
        String grp = groupOf(label);
        if (grp == null) return Record.REJ_LABEL;
        if (sign == null || sign.length() == 0 || "plan.multi".equals(sign) || "chat.reply".equals(sign)
                || "clarify.ask".equals(sign)) return Record.REJ_SIGN;
        if (outcome == null || !outcome.isSuccess()) return Record.REJ_FAILED;
        // 旧 D-1（用户 2026-10-09 14:25 拍板第 8 项「实现」）：确定性 DO_ACT —— 单步动作、句子自身说全了（没有「这个 / 刚才 / 再」
        //   之类指代上文的词）—— 不因为本轮带会话历史就拒写。参数落地 / 时效 / 自测等其它闸门照旧。
        if (contextual && !contextIndependentAct(utterance, label)) return Record.REJ_CONTEXT;
        if (looksLikeSelfTest(utterance)) return Record.REJ_SELFTEST;

        CacheKey k = CacheKey.of(utterance);
        if (k.literal.length() == 0) return Record.REJ_LABEL;
        long now = mClock.now();

        if (GRP_ASK.equals(grp)) {
            if (!"ask.reply".equals(sign)) return Record.REJ_SIGN;
            if (IntentClassifier.isTimeSensitive(utterance)) return Record.REJ_TIME_SENSITIVE;
            // 表格回答（富内容关时靠本进程登记的表头放行）不进缓存：回放时面板认不出 → 会露 Markdown
            if (com.onesay.assistant.plug.richout.core.RichOutPlugs.wantsTable(utterance)) return Record.REJ_LABEL;
            String text = args != null ? args.optString("text", "") : "";
            if (looksLikeRefusal(text)) return Record.REJ_REFUSAL;
            if (negativeActive(k.literal, grp, now)) return Record.REJ_NEGATIVE;
            SkillEntry e = new SkillEntry();
            e.key = k.literal;
            e.grp = grp;
            e.label = label;
            e.sign = sign;
            JSONObject a = new JSONObject();
            try { a.put("text", text); } catch (Exception ignored) {}
            e.argsTpl = a.toString();
            e.capVersion = ASK_CAP_VERSION;
            e.epoch = mEpoch;
            e.status = SkillEntry.STATUS_VERIFIED;
            e.okCount = 1;
            e.createdTs = now;
            e.ttlMs = TTL_ASK;
            mStore.put(e);
            touchLast(e.key, grp, now);
            mStore.evictLru(CAPACITY);
            return Record.VERIFIED;
        }

        // ---------- DO ----------
        if ("ask.reply".equals(sign)) return Record.REJ_SIGN;
        String ver = mOracle != null ? mOracle.versionOf(sign) : null;
        if (ver == null) return Record.REJ_UNKNOWN_CAP;
        int risk = mOracle.risk(sign);
        if (risk >= 2) return Record.REJ_RISK;
        // 相对时间（"40分钟后"）→ 参数是"当时"才成立的绝对时刻；动作类带时效词（"明天七点"）同理
        if (TimeContext.parseRelative(k.literal) != null) return Record.REJ_TIME_SENSITIVE;
        if ("DO_ACT".equals(label) && IntentClassifier.isTimeSensitive(utterance)) return Record.REJ_TIME_SENSITIVE;
        if (negativeActive(k.shape, grp, now)) return Record.REJ_NEGATIVE;

        Templated t = templatize(k, args);
        if (t == null) return Record.REJ_INVENTED_ARG;

        SkillEntry old = mStore.get(t.key, grp);
        SkillEntry e = old != null ? old : new SkillEntry();
        boolean sameMapping = old != null && sign.equals(old.sign) && t.argsTpl.equals(old.argsTpl)
                && ver.equals(old.capVersion) && mEpoch.equals(old.epoch);
        e.key = t.key;
        e.grp = grp;
        e.label = label;
        e.sign = sign;
        e.argsTpl = t.argsTpl;
        e.capVersion = ver;
        e.epoch = mEpoch;
        e.hasTextSlot = t.hasTextSlot;
        e.ttlMs = TTL_DO;
        e.failCount = 0;
        if (!sameMapping) {
            e.okCount = 0;
            e.hitCount = 0;
            e.createdTs = now;
        }
        e.okCount++;
        Record r;
        if (e.okCount >= 2 || (outcome.verified || risk == 0)) {
            r = (sameMapping && SkillEntry.STATUS_CANDIDATE.equals(old.status)) ? Record.PROMOTED : Record.VERIFIED;
            e.status = SkillEntry.STATUS_VERIFIED;
        } else {
            e.status = SkillEntry.STATUS_CANDIDATE;
            r = Record.CANDIDATE;
        }
        if (e.createdTs == 0) e.createdTs = now;
        mStore.put(e);
        mStore.deleteNegative(t.key, grp);
        touchLast(e.key, grp, now);
        mStore.evictLru(CAPACITY);
        return r;
    }

    /**
     * 旧库（skills_v2.db）一次性迁移：可疑条目直接丢弃，其余以 candidate 身份导入
     * （旧库是"执行前写入"，没有成功证据；下一次 LLM 真成功一次即转正）。
     */
    public synchronized Record importLegacy(String intent, String kind, String sign, JSONObject args) {
        if (looksLikeSelfTest(intent)) return Record.REJ_SELFTEST;
        if (sign == null || "ask.reply".equals(sign) || "chat.reply".equals(sign)
                || "clarify.ask".equals(sign) || "plan.multi".equals(sign)) return Record.REJ_SIGN;
        if ("ASK".equals(kind) || "MIXED".equals(kind)) return Record.REJ_LABEL;
        String ver = mOracle != null ? mOracle.versionOf(sign) : null;
        if (ver == null) return Record.REJ_UNKNOWN_CAP;
        if (mOracle.risk(sign) >= 2) return Record.REJ_RISK;
        if (IntentClassifier.isTimeSensitive(intent) && args != null && args.length() > 0) {
            return Record.REJ_TIME_SENSITIVE;
        }
        CacheKey k = CacheKey.of(intent);
        if (k.literal.length() == 0) return Record.REJ_LABEL;
        Templated t = templatize(k, args);
        if (t == null) return Record.REJ_INVENTED_ARG;
        if (mStore.get(t.key, GRP_DO) != null) return Record.CANDIDATE;
        SkillEntry e = new SkillEntry();
        e.key = t.key;
        e.grp = GRP_DO;
        e.label = "DO_ACT";
        e.sign = sign;
        e.argsTpl = t.argsTpl;
        e.capVersion = ver;
        e.epoch = mEpoch;
        e.hasTextSlot = t.hasTextSlot;
        e.status = SkillEntry.STATUS_CANDIDATE;
        e.okCount = 1;
        e.createdTs = mClock.now();
        e.ttlMs = TTL_DO;
        mStore.put(e);
        return Record.CANDIDATE;
    }

    // =====================================================================
    // 查询
    // =====================================================================

    /** 按分诊标签查询；不命中返回 null。命中会计数、续期并记为"最近一次"（供纠错）。 */
    public synchronized Hit lookup(String utterance, String label) {
        return lookupInternal(utterance, label, true);
    }

    /** 同 {@link #lookup}，但不计命中、不续期（唤醒热路径 / 兼容查询用）。 */
    public synchronized Hit peekHit(String utterance, String label) {
        return lookupInternal(utterance, label, false);
    }

    private Hit lookupInternal(String utterance, String label, boolean count) {
        String grp = groupOf(label);
        if (grp == null) return null;
        CacheKey k = CacheKey.of(utterance);
        if (k.literal.length() == 0) return null;
        long now = mClock.now();
        String key = keyFor(k, grp);
        if (negativeActive(key, grp, now)) return null;

        SkillEntry e = mStore.get(key, grp);
        if (e != null) {
            Hit h = tryHit(e, k, null, now, false, count);
            if (h != null) return h;
        }
        if (GRP_DO.equals(grp)) {
            for (SkillEntry t : mStore.listTextTemplates(grp)) {
                List<String> texts = matchTextTemplate(t.key, k.shape);
                if (texts == null) continue;
                Hit h = tryHit(t, k, texts, now, true, count);
                if (h != null) return h;
            }
        }
        return null;
    }

    /** 只读探测（不计命中、不续期）：分诊信号用。 */
    public synchronized boolean peek(String utterance, String label) {
        String grp = groupOf(label);
        if (grp == null) return false;
        CacheKey k = CacheKey.of(utterance);
        String key = keyFor(k, grp);
        long now = mClock.now();
        if (k.literal.length() == 0 || negativeActive(key, grp, now)) return false;
        SkillEntry e = mStore.get(key, grp);
        if (e != null && usable(e, now) && substitute(e, k, null) != null) return true;
        if (GRP_DO.equals(grp)) {
            for (SkillEntry t : mStore.listTextTemplates(grp)) {
                List<String> texts = matchTextTemplate(t.key, k.shape);
                if (texts != null && usable(t, now) && substitute(t, k, texts) != null) return true;
            }
        }
        return false;
    }

    private Hit tryHit(SkillEntry e, CacheKey k, List<String> texts, long now, boolean generalized, boolean count) {
        if (!SkillEntry.STATUS_VERIFIED.equals(e.status)) return null;
        if (!mEpoch.equals(e.epoch)) {
            mStore.delete(e.key, e.grp);
            return null;
        }
        if (GRP_DO.equals(e.grp)) {
            String ver = mOracle != null ? mOracle.versionOf(e.sign) : null;
            if (ver == null || !ver.equals(e.capVersion)) {
                mStore.delete(e.key, e.grp);     // 能力被改名/删除/改 Schema
                return null;
            }
        } else if (!ASK_CAP_VERSION.equals(e.capVersion)) {
            mStore.delete(e.key, e.grp);         // 旧答案作废
            return null;
        }
        if (expired(e, now)) {
            mStore.delete(e.key, e.grp);
            return null;
        }
        JSONObject args = substitute(e, k, texts);
        if (args == null) return null;
        if (GRP_DO.equals(e.grp) && mOracle != null && !mOracle.validate(e.sign, args)) return null;
        if (GRP_DO.equals(e.grp) && e.argsTpl.contains("{n")) generalized = true;   // 数字槽代入
        if (count) {
            e.hitCount++;
            e.lastHitTs = now;
            mStore.put(e);
            touchLast(e.key, e.grp, now);
        }
        return new Hit(e.sign, args, e.key, e.grp, generalized);
    }

    private boolean usable(SkillEntry e, long now) {
        if (!SkillEntry.STATUS_VERIFIED.equals(e.status)) return false;
        if (!mEpoch.equals(e.epoch)) return false;
        if (GRP_DO.equals(e.grp)) {
            String ver = mOracle != null ? mOracle.versionOf(e.sign) : null;
            if (ver == null || !ver.equals(e.capVersion)) return false;
        }
        return !expired(e, now);
    }

    private static boolean expired(SkillEntry e, long now) {
        if (e.ttlMs <= 0) return false;
        long base = Math.max(e.lastHitTs, e.createdTs);
        return now > base + e.ttlMs;
    }

    // =====================================================================
    // 回放结果 / 负缓存 / 纠错
    // =====================================================================

    /** 命中后真正执行的结果回报：成功清零失败计数；连续失败 2 次 → 隔离。 */
    public synchronized void onReplayResult(String key, String grp, Outcome outcome) {
        if (key == null || grp == null || outcome == null) return;
        SkillEntry e = mStore.get(key, grp);
        if (e == null) return;
        if (outcome.userDenied) return;     // 用户拒绝不是能力的错
        if (outcome.isSuccess()) {
            e.okCount++;
            e.failCount = 0;
        } else {
            e.failCount++;
            if (e.failCount >= QUARANTINE_AFTER) e.status = SkillEntry.STATUS_QUARANTINED;
        }
        mStore.put(e);
    }

    /** 便捷：按原句回报。 */
    public synchronized void onReplayResult(String utterance, String label, Outcome outcome, boolean byUtterance) {
        String grp = groupOf(label);
        if (grp == null) return;
        CacheKey k = CacheKey.of(utterance);
        String key = keyFor(k, grp);
        if (mStore.get(key, grp) == null && mLastKey != null && grp.equals(mLastGrp)) key = mLastKey;
        onReplayResult(key, grp, outcome);
    }

    /** 记一条负缓存（LLM 拒答 / "做不到" / 用户纠错）。 */
    public synchronized void recordRefusal(String utterance, String label, String reason, long ttlMs) {
        String grp = groupOf(label);
        if (grp == null) grp = GRP_DO;
        CacheKey k = CacheKey.of(utterance);
        if (k.literal.length() == 0) return;
        mStore.putNegative(keyFor(k, grp), grp, reason == null ? "" : reason, mClock.now() + ttlMs);
    }

    public synchronized boolean isNegative(String utterance, String label, long now) {
        String grp = groupOf(label);
        if (grp == null) grp = GRP_DO;
        CacheKey k = CacheKey.of(utterance);
        return negativeActive(keyFor(k, grp), grp, now);
    }

    /** 负缓存原因（未命中或已过期返回 null）。 */
    public synchronized String negativeReason(String utterance, String label) {
        String grp = groupOf(label);
        if (grp == null) grp = GRP_DO;
        CacheKey k = CacheKey.of(utterance);
        String key = keyFor(k, grp);
        Object[] n = mStore.getNegative(key, grp);
        if (n == null) return null;
        if (mClock.now() > ((Long) n[1]).longValue()) {
            mStore.deleteNegative(key, grp);
            return null;
        }
        return (String) n[0];
    }

    /**
     * ★ round3 #4（验收脚本用）：清掉某句话在 ASK / DO 两组里的负缓存（原句键与形状键都清）。
     * 起因：真机第 3 轮 cache 组假失败 —— 上一次 CA-5「不对」留下的 1 天负缓存让 [L1] PUT 变成 REJ_NEGATIVE。
     * 只删负缓存，不碰正缓存条目。@return 实际删掉的条数
     */
    public synchronized int clearNegative(String utterance) {
        CacheKey k = CacheKey.of(utterance);
        if (k.literal.length() == 0) return 0;
        int n = 0;
        String[] grps = { GRP_ASK, GRP_DO };
        for (String grp : grps) {
            java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<String>();
            keys.add(k.literal);
            keys.add(k.shape);
            for (String key : keys) {
                if (key == null || key.length() == 0) continue;
                if (mStore.getNegative(key, grp) != null) {
                    mStore.deleteNegative(key, grp);
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * ★ round4（验收脚本用）：清掉某句话在 ASK / DO 两组里的<b>正缓存</b>条目（原句键与形状键都清，含 CANDIDATE 候选）。
     * 起因：真机第 4 轮 CA-1 假失败 —— 测试句上一轮已转正留在正缓存，第 1 次直接 HIT、根本不 PUT。
     * 与 {@link #clearNegative} 对称：只删正缓存，负缓存不动；别的句子的条目不动。槽位模板键（{n}）不在这里清。
     * @return 实际删掉的条数
     */
    public synchronized int clearPositive(String utterance) {
        CacheKey k = CacheKey.of(utterance);
        if (k.literal.length() == 0) return 0;
        int n = 0;
        String[] grps = { GRP_ASK, GRP_DO };
        for (String grp : grps) {
            java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<String>();
            keys.add(k.literal);
            keys.add(k.shape);
            for (String key : keys) {
                if (key == null || key.length() == 0) continue;
                if (mStore.get(key, grp) != null) {
                    mStore.delete(key, grp);
                    n++;
                    if (key.equals(mLastKey) && grp.equals(mLastGrp)) mLastKey = null;
                }
            }
        }
        return n;
    }

    private boolean negativeActive(String key, String grp, long now) {
        Object[] n = mStore.getNegative(key, grp);
        if (n == null) return false;
        if (now > ((Long) n[1]).longValue()) {
            mStore.deleteNegative(key, grp);
            return false;
        }
        return true;
    }

    /** 用户说"不对/错了"：淘汰窗口内最近一次命中/写入的条目，并加 1 天负缓存。 */
    public synchronized boolean onUserCorrection() {
        long now = mClock.now();
        if (mLastKey == null || now - mLastTs > CORRECTION_WINDOW_MS) return false;
        mStore.delete(mLastKey, mLastGrp);
        mStore.putNegative(mLastKey, mLastGrp, "用户纠错", now + TTL_CORRECTION);
        mLastKey = null;
        return true;
    }

    /** 指定原句的纠错（UI 有明确"不对"按钮时用）。 */
    public synchronized void onUserCorrection(String utterance, String label) {
        String grp = groupOf(label);
        if (grp == null) grp = GRP_DO;
        CacheKey k = CacheKey.of(utterance);
        String key = keyFor(k, grp);
        mStore.delete(key, grp);
        mStore.putNegative(key, grp, "用户纠错", mClock.now() + TTL_CORRECTION);
    }

    private void touchLast(String key, String grp, long now) {
        mLastKey = key;
        mLastGrp = grp;
        mLastTs = now;
    }

    /** 指代上文 / 依赖会话的词：出现任何一个就不算「确定性」（宁可不缓存）。 */
    private static final String[] CONTEXT_WORDS = {
            "这个", "那个", "这张", "那张", "这条", "那条", "这里", "那里", "这儿", "那儿", "这些", "那些", "这首", "那首",
            "它", "他", "她", "刚才", "刚刚", "上一个", "上一条", "上面", "前面那", "之前那", "再", "继续", "还是", "同样",
            "一样", "也", "改回", "回去", "换一个", "另一个", "下一个", "然后",
    };

    /**
     * 旧 D-1：这句话是不是「确定性 DO_ACT」—— 标签 DO_ACT、句子本身不指代上文（{@link #CONTEXT_WORDS}）。
     * 这类句子执行结果不依赖会话历史，带着历史执行成功也可以安全写缓存。纯函数。
     */
    public static boolean contextIndependentAct(String utterance, String label) {
        if (!"DO_ACT".equals(label) || utterance == null) return false;
        String q = com.onesay.assistant.v2.router.TriageDecider.normalize(utterance);
        if (q.length() == 0) return false;
        for (String w : CONTEXT_WORDS) if (q.contains(w)) return false;
        return true;
    }

    /** 是否是纠错语（"不对"/"错了"/"不是这个"…）。 */
    public static boolean isCorrection(String utterance) {
        String q = com.onesay.assistant.v2.router.TriageDecider.normalize(utterance);
        if (q.length() == 0 || q.length() > 8) return false;
        String[] exact = { "不对", "错了", "不是这个", "搞错了", "你搞错了", "不是我要的", "不是这样", "说错了",
                "不对不对", "弄错了", "你错了", "不是", "撤销", "错的" };
        for (String w : exact) if (q.equals(w)) return true;
        return q.startsWith("不对") || q.startsWith("错了") || q.startsWith("搞错");
    }

    // =====================================================================
    // 模板化
    // =====================================================================

    static final class Templated {
        String key;
        String argsTpl;
        boolean hasTextSlot;
    }

    /**
     * 把 (规范化原句, 参数) 变成 (模板键, 参数模板)。参数里出现原句不存在的值 → null（拒写）。
     */
    static Templated templatize(CacheKey k, JSONObject args) {
        Templated t = new Templated();
        String key = k.shape;
        JSONObject tpl = new JSONObject();
        boolean[] usedNum = new boolean[k.numbers.size()];
        boolean anyGrounded = false;
        List<String> zeroDefaults = new ArrayList<String>();
        boolean hasText = false;
        try {
            if (args != null) {
                Iterator<String> it = args.keys();
                List<String> names = new ArrayList<String>();
                while (it.hasNext()) names.add(it.next());
                java.util.Collections.sort(names);
                for (String name : names) {
                    Object v = args.opt(name);
                    if (v instanceof Boolean) {
                        tpl.put(name, v);
                        continue;
                    }
                    if (v instanceof Number) {
                        int idx = findNumber(k.numbers, (Number) v, usedNum);
                        if (idx >= 0) {
                            usedNum[idx] = true;
                            tpl.put(name, "{n" + idx + "}");
                            anyGrounded = true;
                        } else if (((Number) v).doubleValue() == 0.0) {
                            zeroDefaults.add(name);
                        } else {
                            return null;       // 原句里没有这个数 → LLM 自填 → 拒写
                        }
                        continue;
                    }
                    if (v instanceof String) {
                        String sv = (String) v;
                        String nv = com.onesay.assistant.v2.router.TriageDecider.normalize(sv);
                        if (nv.length() == 0) {
                            tpl.put(name, sv);
                            continue;
                        }
                        // 纯数字字符串按数字处理
                        if (nv.matches("[0-9]+(\\.[0-9]+)?")) {
                            int idx = findNumber(k.numbers, Double.valueOf(nv), usedNum);
                            if (idx >= 0) {
                                usedNum[idx] = true;
                                tpl.put(name, "{n" + idx + "}");
                                anyGrounded = true;
                                continue;
                            }
                            return null;
                        }
                        String nvKey = CacheKey.of(sv).literal;
                        if (nvKey.length() > 0 && isTextSlotParam(name) && key.contains(nvKey)
                                && literalRemainder(key, nvKey) >= 2 && !key.contains("{t:")) {
                            key = key.replaceFirst(Pattern.quote(nvKey),
                                    Matcher.quoteReplacement("{t:" + name + "}"));
                            tpl.put(name, "{t:" + name + "}");
                            hasText = true;
                            anyGrounded = true;
                        } else if (k.literal.contains(nv) || k.literal.contains(nvKey)) {
                            tpl.put(name, sv);   // 原句里有，只是不做泛化 → 当常量（键里字面固定）
                            anyGrounded = true;
                        } else if (isIdentifierLike(sv)) {
                            tpl.put(name, sv);   // 包名/枚举等标识符常量（键字面固定了语义）
                        } else {
                            return null;         // 自由文本却不在原句里 → 编造 → 拒写
                        }
                        continue;
                    }
                    if (v == null || v == JSONObject.NULL) continue;
                    tpl.put(name, String.valueOf(v));
                }
            }
            for (String z : zeroDefaults) {
                if (!anyGrounded) return null;     // 全是 0 的"默认值"没有任何落地依据
                tpl.put(z, 0);
            }
            // 未被参数引用的数字：作为字面约束（查询时必须相等）
            JSONObject fixed = new JSONObject();
            for (int i = 0; i < usedNum.length; i++) {
                if (!usedNum[i]) fixed.put(String.valueOf(i), k.numbers.get(i));
            }
            if (fixed.length() > 0) tpl.put("__fixed", fixed);
        } catch (Exception e) {
            return null;
        }
        t.key = key;
        t.argsTpl = tpl.toString();
        t.hasTextSlot = hasText;
        return t;
    }

    /** 代入：数字槽、文本槽；__fixed 约束不满足 → null。 */
    static JSONObject substitute(SkillEntry e, CacheKey k, List<String> texts) {
        try {
            JSONObject tpl = new JSONObject(e.argsTpl);
            if (GRP_ASK.equals(e.grp)) return tpl;
            JSONObject fixed = tpl.optJSONObject("__fixed");
            if (fixed != null) {
                Iterator<String> fi = fixed.keys();
                while (fi.hasNext()) {
                    String idx = fi.next();
                    int i = Integer.parseInt(idx);
                    if (i >= k.numbers.size()) return null;
                    if (!numEq(k.numbers.get(i), fixed.optString(idx))) return null;
                }
            }
            List<String> textNames = textSlotNames(e.key);
            JSONObject out = new JSONObject();
            Iterator<String> it = tpl.keys();
            while (it.hasNext()) {
                String name = it.next();
                if ("__fixed".equals(name)) continue;
                Object v = tpl.opt(name);
                if (v instanceof String) {
                    String s = (String) v;
                    if (s.startsWith("{n") && s.endsWith("}")) {
                        int i = Integer.parseInt(s.substring(2, s.length() - 1));
                        if (i >= k.numbers.size()) return null;
                        String num = k.numbers.get(i);
                        if (num.contains(".")) out.put(name, Double.parseDouble(num));
                        else out.put(name, Long.parseLong(num));
                        continue;
                    }
                    if (s.startsWith("{t:") && s.endsWith("}")) {
                        String tn = s.substring(3, s.length() - 1);
                        int pos = textNames.indexOf(tn);
                        if (texts == null) {
                            return null;   // 文本模板只能经由模板匹配命中
                        }
                        if (pos < 0 || pos >= texts.size()) return null;
                        out.put(name, texts.get(pos));
                        continue;
                    }
                }
                out.put(name, v);
            }
            return out;
        } catch (Exception ex) {
            return null;
        }
    }

    /** 文本模板匹配：返回各文本槽取值；不匹配或取值可疑 → null。 */
    static List<String> matchTextTemplate(String templateKey, String queryShape) {
        StringBuilder re = new StringBuilder("^");
        int i = 0;
        int slots = 0;
        while (i < templateKey.length()) {
            int a = templateKey.indexOf("{t:", i);
            if (a < 0) {
                re.append(Pattern.quote(templateKey.substring(i)));
                break;
            }
            if (a > i) re.append(Pattern.quote(templateKey.substring(i, a)));
            int b = templateKey.indexOf('}', a);
            if (b < 0) return null;
            re.append("(.{1,20}?)");
            slots++;
            i = b + 1;
        }
        re.append("$");
        if (slots == 0) return null;
        Matcher m = Pattern.compile(re.toString()).matcher(queryShape);
        if (!m.matches()) return null;
        List<String> out = new ArrayList<String>();
        for (int g = 1; g <= m.groupCount(); g++) {
            String v = m.group(g);
            if (v == null || v.length() == 0 || v.contains("{n}")) return null;
            for (String p : SLOT_POISON) if (v.contains(p)) return null;
            out.add(v);
        }
        return out;
    }

    private static List<String> textSlotNames(String key) {
        List<String> out = new ArrayList<String>();
        int i = 0;
        while (true) {
            int a = key.indexOf("{t:", i);
            if (a < 0) break;
            int b = key.indexOf('}', a);
            if (b < 0) break;
            out.add(key.substring(a + 3, b));
            i = b + 1;
        }
        return out;
    }

    private static int findNumber(List<String> nums, Number v, boolean[] used) {
        for (int i = 0; i < nums.size(); i++) {
            if (used[i]) continue;
            if (numEq(nums.get(i), String.valueOf(v))) return i;
        }
        return -1;
    }

    private static boolean numEq(String a, String b) {
        try {
            return Double.parseDouble(a) == Double.parseDouble(b);
        } catch (Exception e) {
            return a.equals(b);
        }
    }

    private static boolean isTextSlotParam(String name) {
        for (String p : TEXT_SLOT_PARAMS) if (p.equals(name)) return true;
        return false;
    }

    private static int literalRemainder(String key, String slotValue) {
        return key.replace(slotValue, "").replace("{n}", "").length();
    }

    private static boolean isIdentifierLike(String s) {
        return s.length() <= 64 && s.matches("[A-Za-z0-9_.:/-]+");
    }

    /** 拒答/兜底/做不到类文本不缓存。 */
    public static boolean looksLikeRefusal(String text) {
        if (text == null) return true;
        String t = text.trim();
        if (t.length() < 2) return true;
        String[] cues = { "做不到", "无法", "不能帮", "没办法", "不支持", "暂不支持", "答不上来", "不知道", "抱歉",
                "网络暂时不可用", "还没有配置", "未配置", "失败", "出错", "稍后再试", "请重试" };
        String lower = t.toLowerCase(Locale.ROOT);
        for (String c : cues) if (lower.contains(c)) return true;
        return false;
    }

    /** 自测探针语句（旧库里实测有"修复回归探针指令/自测缓存优先探针"）。 */
    public static boolean looksLikeSelfTest(String utterance) {
        if (utterance == null) return true;
        return utterance.contains("探针") || utterance.contains("自测") || utterance.contains("测试指令")
                || utterance.contains("selftest");
    }
}
