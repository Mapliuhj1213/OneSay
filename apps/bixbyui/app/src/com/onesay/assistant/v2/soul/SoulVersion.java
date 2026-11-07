package com.onesay.assistant.v2.soul;

/**
 * [soul] 人设（SOUL）的一个版本。所有版本都保留（研究 §6.6：保留版本、可回滚）。
 * status：active（当前生效，只有一条）/ old（历史）/ proposed（助手提议、等用户确认）/ rejected。
 */
public final class SoulVersion {
    public static final String ST_ACTIVE = "active";
    public static final String ST_OLD = "old";
    public static final String ST_PROPOSED = "proposed";
    public static final String ST_REJECTED = "rejected";

    public static final String SRC_TEMPLATE = "template";
    public static final String SRC_USER = "user";
    public static final String SRC_AGENT = "agent";

    public long id;
    public String text = "";
    public String source = SRC_TEMPLATE;
    public String status = ST_ACTIVE;
    /** 用户手改过 → 助手不再提议覆盖。 */
    public boolean userLocked;
    public String reason = "";
    public long createdAt;
    /** 提议基于哪个版本（diff 用）。 */
    public long baseId;

    public SoulVersion copy() {
        SoulVersion v = new SoulVersion();
        v.id = id; v.text = text; v.source = source; v.status = status; v.userLocked = userLocked;
        v.reason = reason; v.createdAt = createdAt; v.baseId = baseId;
        return v;
    }
}
