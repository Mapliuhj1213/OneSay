package com.onesay.assistant.v2.router.longctx;

import java.util.List;

/**
 * ★ fix/1123-longctx-phone：长对话可靠性（手机端单会话语音 / 对话）的总开关与提示文字。
 *
 * <p>背景（用户 2026-11-23）：长会话里常见「复述用户的话后答偏题、摘要污染、遗忘、幻觉、人设失效、技能准则被丢」，新开会话就正常。
 * 六条一起做（各自的类）：
 * <ol>
 *   <li>评测集：test-jvm 里的 LongCtxEval*（假模型 / 确定性）；</li>
 *   <li>摘要写状态不写流水账：{@link #renderState}（ConversationMemory 折叠时调）；</li>
 *   <li>每条用户消息记账：{@link TurnLedger}；</li>
 *   <li>长期记忆放会话外、按需检索、留原文：{@link TurnArchive}；</li>
 *   <li>重活隔离：{@link HeavyIsolation}；</li>
 *   <li>最新一句放最后单独标出 + 出口自检重答一次：{@link #tail} / {@link ReplyCheck}。</li>
 * </ol>
 * 另：核心人设与硬规则每轮末尾短注入（{@link #RULES}）；技能「已加载清单」压缩 / 接力后重新加载（ConversationMemory + SkillSession.pin）；
 * 超长会话自动带接力摘要开新会话（ConversationMemory.relay），手动「新对话」照旧。
 *
 * <p>总开关关（prefs {@code bixby_conv/longctx=false}）= 以上全部不生效，行为与改前逐字一致（评测里有对照）。
 * 第一轮（会话里还没有历史）也不加任何东西：提示词与改前逐字一致。
 */
public final class LongCtx {
    private LongCtx() {
    }

    private static volatile boolean sEnabled = true;

    public static boolean enabled() {
        return sEnabled;
    }

    public static void setEnabled(boolean on) {
        sEnabled = on;
    }

    private static volatile boolean sSelfCheck = true;

    /** 出口自检（第 6 条后半）单独的开关：总开关开时才看它；评测用它做「去掉自检」的对照。缺省开。 */
    public static boolean selfCheck() {
        return sEnabled && sSelfCheck;
    }

    public static void setSelfCheck(boolean on) {
        sSelfCheck = on;
    }

    /** 每轮末尾短注入的人设与硬规则（话术口吻见用户定的「简单大白话、务实、留一点憨、不用啥/咋/呗」）。 */
    public static final String RULES = "【守则·每轮】说简单的大白话，务实，先给结论，说清发生了什么、下一步怎么办；可以留一点憨，但不装傻；"
            + "不用「啥」「咋」「呗」。别先把用户的话复述一遍，前面已结的问题不要再答，也别重复之前的回答；"
            + "没把握的事如实说不知道，不编。#编号只是给你看的，回答里别提。";

    public static final String LATEST = "【最新一句·只答这一句】";
    public static final String LEDGER = "【对话账本】";
    public static final String ARCHIVE = "【存档原文（按需调出，只作参考）】";
    public static final String STATE_HEAD = "【会话状态】（系统整理的状态，不是对话原文；只记事实和结论）";
    public static final String RELAY_HEAD = "【接力摘要】上一段对话太长，已经自动换了个新对话接着聊；下面是上一段的状态。";

    /** 出口自检打回时插在最新一句前面的提示。 */
    public static String redoNote(ReplyCheck.Result r) {
        String why;
        switch (r.verdict) {
            case ECHO:
                why = "一开头就把用户的话复述了一遍";
                break;
            case REPEAT:
                why = "重复了之前回答过的内容" + (r.other == null ? "" : "（那是「" + TextSim.head(r.other, 24) + "」的答案）");
                break;
            default:
                why = "答的是之前已经答完的问题" + (r.other == null ? "" : "「" + TextSim.head(r.other, 24) + "」");
        }
        return "【系统提示】上一次回答" + why + "，没有回答用户最新这句话。请重答：直接回答下面最新这一句，别复述问题，别重复之前的回答。";
    }

    /**
     * 本轮用户消息末尾的块：账本 → 存档原文（命中才有）→ 守则 → 自检提示（重答时）→ 最新一句（最后一行）。
     *
     * @param closedIds 已结条目的编号（升序）
     * @param open      没答完的条目（不含本轮）
     * @param archive   按需调出的存档原文（可空）
     */
    public static String tail(List<Integer> closedIds, List<TurnLedger.Entry> open, List<TurnArchive.Hit> archive, int latestId, String query, String redo) {
        StringBuilder b = new StringBuilder();
        if (!closedIds.isEmpty() || !open.isEmpty()) {
            b.append(LEDGER);
            if (!closedIds.isEmpty()) b.append("已结：").append(ranges(closedIds)).append("（都答完了，不要再答）");
            if (!open.isEmpty()) {
                if (!closedIds.isEmpty()) b.append("；");
                b.append("没答完：");
                int n = 0;
                for (int i = open.size() - 1; i >= 0 && n < OPEN_SHOW; i--, n++) {
                    TurnLedger.Entry e = open.get(i);
                    if (n > 0) b.append("、");
                    b.append('#').append(e.id).append("「").append(TextSim.head(e.text, 30)).append("」（").append(e.status.label).append("）");
                }
                b.append("——用户再提起时才接着做");
            }
            b.append('\n');
        }
        if (archive != null && !archive.isEmpty()) {
            b.append(ARCHIVE).append('\n');
            for (TurnArchive.Hit h : archive) {
                TurnArchive.Entry e = h.entry;
                String a = TextSim.head(e.answer, ARCHIVE_ANSWER_CHARS);
                b.append('#').append(e.id).append(" 用户：").append(TextSim.head(e.question, ARCHIVE_QUESTION_CHARS)).append('\n');
                b.append('#').append(e.id).append(" 助手：").append(e.tainted ? com.onesay.assistant.v2.router.AgentLoop.wrapUntrusted(a) : a).append('\n');
            }
        }
        b.append(RULES).append('\n');
        if (redo != null) b.append(redo).append('\n');
        b.append(LATEST).append('#').append(latestId).append(" 用户：").append(query == null ? "" : query.trim());
        return b.toString();
    }

    public static final int ARCHIVE_QUESTION_CHARS = 200, ARCHIVE_ANSWER_CHARS = 600;

    /** 1,2,3,5,7,8 → #1–#3、#5、#7–#8；只列最近 {@link #LEDGER_SHOW} 段。 */
    static String ranges(List<Integer> ids) {
        java.util.List<String> segs = new java.util.ArrayList<String>();
        int s = ids.get(0), p = s;
        for (int i = 1; i <= ids.size(); i++) {
            int v = i < ids.size() ? ids.get(i) : Integer.MIN_VALUE;
            if (v == p + 1) {
                p = v;
                continue;
            }
            segs.add(s == p ? "#" + s : "#" + s + "–#" + p);
            s = p = v;
        }
        int from = Math.max(0, segs.size() - LEDGER_SHOW);
        StringBuilder b = new StringBuilder(from > 0 ? "…、" : "");
        for (int i = from; i < segs.size(); i++) {
            if (i > from) b.append("、");
            b.append(segs.get(i));
        }
        return b.toString();
    }

    static final int LEDGER_SHOW = 8;
    /** 末尾块里最多列几条没答完的（最近的）。 */
    static final int OPEN_SHOW = 3;

    /**
     * 第 2 条：状态摘要。只写已折叠出逐字历史的轮次：已知事实（用户亲口说的）、已结问题与一句结论、没答完的、已加载技能。
     * 放不下时先丢最早的已结问题（原文都在存档里）。
     */
    public static String renderState(boolean relay, List<FactNotes.Fact> facts, List<TurnLedger.Entry> folded, List<String> skills, int maxTokens) {
        StringBuilder head = new StringBuilder(relay ? RELAY_HEAD + "\n" + STATE_HEAD : STATE_HEAD);
        if (!facts.isEmpty()) {
            head.append("\n已知事实（用户亲口说的）：");
            for (FactNotes.Fact f : facts) head.append("\n· ").append(f.text).append("（#").append(f.turnId).append("）");
        }
        StringBuilder open = new StringBuilder();
        java.util.List<String> closed = new java.util.ArrayList<String>();
        for (TurnLedger.Entry e : folded) {
            if (e.status == TurnLedger.Status.DONE) {
                closed.add("· #" + e.id + " " + TextSim.head(e.text, 40) + " → " + TextSim.head(e.conclusion, 80) + (e.tainted ? "（含外部内容）" : ""));
            } else {
                open.append("\n· #").append(e.id).append(" ").append(TextSim.head(e.text, 40)).append("（").append(e.status.label).append("）");
            }
        }
        StringBuilder tailB = new StringBuilder();
        if (open.length() > 0) tailB.append("\n没答完的（用户再提起时才接着做）：").append(open);
        if (skills != null && !skills.isEmpty()) {
            tailB.append("\n已加载技能：");
            for (int i = 0; i < skills.size(); i++) tailB.append(i > 0 ? "、" : "").append(skills.get(i));
        }
        tailB.append("\n更早的原文都存着，需要时会按需调出。");
        int from = 0;
        while (true) {
            StringBuilder b = new StringBuilder(head);
            if (from < closed.size()) {
                b.append("\n已结问题（都答完了，不要再答；用户再问才答）：");
                if (from > 0) b.append("\n· …更早的 ").append(from).append(" 条在存档里");
                for (int i = from; i < closed.size(); i++) b.append('\n').append(closed.get(i));
            }
            b.append(tailB);
            String s = b.toString();
            if (from >= closed.size() || com.onesay.assistant.v2.router.ctx.TokenEstimator.estimate(s) <= maxTokens) return s;
            from++;
        }
    }
}
