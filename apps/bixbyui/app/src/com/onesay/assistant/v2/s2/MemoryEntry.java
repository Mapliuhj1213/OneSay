package com.onesay.assistant.v2.s2;

/**
 * [s2] 记忆库的一条记录（「记一下」笔记或闪记的一笔账，纯数据类）。
 *
 * <ul>
 *   <li>{@link #KIND_NOTE}：用户说「记一下…」写进来的一句话；{@link #subject}/{@link #relation}/{@link #value}
 *       是解析出的实体槽（如 车 / place / B2-17），用于「同一件事以最新为准」；</li>
 *   <li>{@link #KIND_LEDGER}：闪记记账（#20 作为 #5 的结构化子类）；金额以<b>分</b>存（人民币），
 *       {@link #day} 是消费发生的日期（「昨天买菜」= 昨天），{@link #createdAt} 是记下的时刻。</li>
 * </ul>
 */
public final class MemoryEntry {

    public static final String KIND_NOTE = "note";
    public static final String KIND_LEDGER = "ledger";

    public static final String REL_PLACE = "place";
    public static final String REL_ATTR = "attr";

    public long id;
    public String kind = KIND_NOTE;
    /** 展示用原话（去掉「记一下」前缀后的内容；账目为「午饭」这类描述）。 */
    public String content = "";
    /** 归一化后的主体（"车"、"护照"）；解析不出为空串。 */
    public String subject = "";
    /** 槽位关系：place / attr / 空。 */
    public String relation = "";
    /** 槽位值（"B2-17"、"书房第二个抽屉"）。 */
    public String value = "";
    /** 记账：金额（分）。 */
    public long amountFen;
    /** 记账：分类（餐饮/交通/买菜…）。 */
    public String category = "";
    /** 事件日期 yyyy-MM-dd（笔记 = 记下那天；账目 = 消费那天）。 */
    public String day = "";
    public long createdAt;
    /** [soul] 写入来源：{@link #ORIGIN_IMPLICIT} = 免前缀随口说（进日志）；其它 = 用户明确「记一下」（进核心记忆 / 画像）。不入旧表。 */
    public String origin = "";

    public static final String ORIGIN_IMPLICIT = "implicit";

    public boolean isLedger() {
        return KIND_LEDGER.equals(kind);
    }

    public MemoryEntry copy() {
        MemoryEntry e = new MemoryEntry();
        e.id = id;
        e.kind = kind;
        e.content = content;
        e.subject = subject;
        e.relation = relation;
        e.value = value;
        e.amountFen = amountFen;
        e.category = category;
        e.day = day;
        e.createdAt = createdAt;
        e.origin = origin;
        return e;
    }

    /** 同一件事（主体 + 关系都非空且相同）。 */
    public boolean sameThread(MemoryEntry o) {
        return o != null && subject.length() > 0 && subject.equals(o.subject) && relation.equals(o.relation)
                && kind.equals(o.kind);
    }

    @Override
    public String toString() {
        // 日志用：不打内容原文（隐私），只打结构
        return "#" + id + "/" + kind + (subject.length() > 0 ? " subj=" + subject : "") + (relation.length() > 0 ? " rel=" + relation : "")
                + (isLedger() ? " fen=" + amountFen + " cat=" + category : "") + " day=" + day + " len=" + content.length();
    }
}
