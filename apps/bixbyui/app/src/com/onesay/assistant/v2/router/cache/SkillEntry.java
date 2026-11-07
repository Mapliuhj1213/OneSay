package com.onesay.assistant.v2.router.cache;

/**
 * 模板化技能缓存的一条记录（P0-5/P1-8）。纯数据，无逻辑。
 *
 * <p>主键是 (key, grp)：同一句话在 ASK 组与 DO 组各自独立，互不满足（kind-aware）。
 */
public final class SkillEntry {
    public static final String STATUS_CANDIDATE = "candidate";
    public static final String STATUS_VERIFIED = "verified";
    public static final String STATUS_QUARANTINED = "quarantined";

    /** 模板键（数字已抽成 {n}，可能含文本槽 {t:参数名}）；ASK 组为字面键。 */
    public String key;
    /** 匹配组："ASK" 或 "DO"（DO_READ / DO_ACT 同组）。 */
    public String grp;
    /** 写入时的分诊标签（ASK / DO_READ / DO_ACT），仅供观测。 */
    public String label;
    public String sign;
    /** 参数模板 JSON：值可为常量，或 "{n0}"/"{t:app}" 占位；"__fixed" 存未被引用的数字约束。 */
    public String argsTpl;
    /** 能力契约指纹（签名+Schema+执行器）：能力被改/删即失效。 */
    public String capVersion;
    /** 缓存语义纪元（规范化/提示词等全局变化时由开发者手动递增）。 */
    public String epoch;
    public String status;
    public int okCount;
    public int failCount;
    public int hitCount;
    public long lastHitTs;
    public long createdTs;
    /** 存活毫秒数（从 lastHitTs/createdTs 起算，命中续期）；0 = 不过期。 */
    public long ttlMs;
    /** 是否含文本槽（需要正则扫描匹配）。 */
    public boolean hasTextSlot;

    public SkillEntry copy() {
        SkillEntry e = new SkillEntry();
        e.key = key; e.grp = grp; e.label = label; e.sign = sign; e.argsTpl = argsTpl;
        e.capVersion = capVersion; e.epoch = epoch; e.status = status;
        e.okCount = okCount; e.failCount = failCount; e.hitCount = hitCount;
        e.lastHitTs = lastHitTs; e.createdTs = createdTs; e.ttlMs = ttlMs; e.hasTextSlot = hasTextSlot;
        return e;
    }

    @Override public String toString() {
        return "[" + grp + "] " + key + " -> " + sign + " " + argsTpl + " (" + status
                + " ok=" + okCount + " fail=" + failCount + " hit=" + hitCount + ")";
    }
}
