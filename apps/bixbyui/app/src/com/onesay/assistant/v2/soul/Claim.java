package com.onesay.assistant.v2.soul;

/**
 * [soul] 一条记忆记录（纯数据类）。三张表共用同一套列（字段取自 Muse {@code memory.claims}：
 * quote / evidence / confidence / supersedes / valid_until / first_seen / reinforced_at，见 SOUL-MEMORY-RESEARCH §1.1 §6.1）：
 * <ul>
 *   <li>{@link #T_CORE} memory_core：精选「事实 / 偏好 / 约定」三类（Muse MEMORY.md 三节），按预算注入；</li>
 *   <li>{@link #T_PROFILE} user_profile：指令式画像（称呼、回答风格、免打扰…），status = active / superseded（OpenClaw USER 模板）；</li>
 *   <li>{@link #T_LOG} daily_log：按日期的细节与候选，<b>不注入</b>，只检索（Muse/OpenClaw memory/YYYY-MM-DD.md）。</li>
 * </ul>
 */
public final class Claim {

    public static final String T_CORE = "memory_core";
    public static final String T_PROFILE = "user_profile";
    public static final String T_LOG = "daily_log";

    // core kind
    public static final String K_FACT = "fact";
    public static final String K_PREF = "pref";
    public static final String K_COMMIT = "commit";
    // profile kind
    public static final String K_DIRECTIVE = "directive";
    // log kind
    /** 「免前缀」随口说的事实（S2 implicit），S2 本地回忆立刻能查到，整理时再决定是否晋升。 */
    public static final String K_NOTE = "note";
    /** 候选（规则嗅探 / 会话结束 flush 产出）。 */
    public static final String K_CANDIDATE = "candidate";
    /** 显式写入的证据（原话 + 时间 + 会话），evidenceRef 指回 core/profile 条目（研究 §6.3-1）。不参与检索。 */
    public static final String K_EVIDENCE = "evidence";

    public static final String S_ACTIVE = "active";
    public static final String S_SUPERSEDED = "superseded";
    public static final String S_EXPIRED = "expired";
    /** 日志候选已晋升。 */
    public static final String S_PROMOTED = "promoted";
    /** 日志候选：未晋升、仍可检索。 */
    public static final String S_OPEN = "open";

    public static final String SRC_USER = "user";          // 用户亲口「记一下 / 记住」
    public static final String SRC_IMPLICIT = "implicit";  // 免前缀陈述
    public static final String SRC_SNIFF = "sniff";        // 规则嗅探
    public static final String SRC_FLUSH = "flush";        // 会话结束 LLM 提取
    public static final String SRC_CONSOLIDATE = "consolidate";
    public static final String SRC_MIGRATED = "migrated";
    public static final String SRC_EDIT = "edit";          // 控制台手改

    public static final String P_NORMAL = "normal";
    public static final String P_PERSONAL = "personal";
    /** 健康 / 财务：默认不注入，只在显式回忆时返回（研究 §6.5）。 */
    public static final String P_HEALTH_FINANCE = "health_finance";

    public long id;
    public String table = T_CORE;
    public String kind = K_FACT;
    public String text = "";
    /** 用户原话（证据）。 */
    public String quote = "";
    /** 证据引用：日志 id 列表（"log:12,log:15"）或会话号。 */
    public String evidenceRef = "";
    public double confidence = 1.0;
    public long firstSeen;
    public long reinforcedAt;
    /** 0 = 不过期。 */
    public long validUntil;
    /** 被本条替代的旧条目 id（同表）。 */
    public long supersedes;
    public String status = S_ACTIVE;
    public boolean pinned;
    public String privacy = P_NORMAL;
    public String source = SRC_USER;
    public int recallCount;
    /** S2 槽位（主体 / 关系 / 值），「同一件事以最新为准」。 */
    public String subject = "";
    public String relation = "";
    public String value = "";
    /** 画像主题（称呼 / 回答风格 / 打扰…），同主题新指令替代旧指令。 */
    public String topic = "";
    /** yyyy-MM-dd（日志按日期；其它表 = 首次记下那天）。 */
    public String day = "";
    public String sessionId = "";
    /** 来自识屏 / 邮件 / 网页等不可信内容：永不晋升、永不自动注入（研究 §3.2 污点门）。 */
    public boolean tainted;
    /** 迁移前 s2 memory 表的 id。 */
    public long legacyId;
    /** 用户在控制台手改过：整理不得覆盖（研究 §6.6）。 */
    public boolean userEdited;
    /** 候选类别（日志里用：fact/pref/commit/directive）。 */
    public String candKind = "";

    public boolean isActive() { return S_ACTIVE.equals(status); }

    public Claim copy() {
        Claim c = new Claim();
        c.id = id; c.table = table; c.kind = kind; c.text = text; c.quote = quote; c.evidenceRef = evidenceRef;
        c.confidence = confidence; c.firstSeen = firstSeen; c.reinforcedAt = reinforcedAt; c.validUntil = validUntil;
        c.supersedes = supersedes; c.status = status; c.pinned = pinned; c.privacy = privacy; c.source = source;
        c.recallCount = recallCount; c.subject = subject; c.relation = relation; c.value = value; c.topic = topic;
        c.day = day; c.sessionId = sessionId; c.tainted = tainted; c.legacyId = legacyId; c.userEdited = userEdited;
        c.candKind = candKind;
        return c;
    }

    /** 同一件事：主体 + 关系都非空且相同。 */
    public boolean sameThread(Claim o) {
        return o != null && subject.length() > 0 && subject.equals(o.subject) && relation.equals(o.relation);
    }

    @Override public String toString() {
        // 日志用：不打原文
        return table + "#" + id + "/" + kind + "/" + status + (subject.length() > 0 ? " subj=" + subject : "") + " len=" + text.length()
                + (tainted ? " tainted" : "");
    }
}
