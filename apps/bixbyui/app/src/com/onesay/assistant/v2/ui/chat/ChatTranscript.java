package com.onesay.assistant.v2.ui.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ★ ui-K1 对话窗的会话历史（界面层，纯 Java，JVM 单测）。与 {@code ConversationMemory}（喂模型的历史）分开：
 * 这里只记屏上要排出来的东西 —— 问句、回答、附图、路由 / 耗时、赞踩、执行卡快照（不透明对象，由 Android 侧放位图）。
 *
 * <p>一轮的生命周期：出结果时 {@link #upsert}（同一轮再出结果 = 覆盖）→ 下一轮开始时 {@link #freeze}（冻结进历史，之后不再变）。
 * {@link #items()} 把历史展开成列表项（问句气泡 / 回答 / 页脚 / 追问建议），追问建议只挂在最后一条回答下面。
 */
public final class ChatTranscript {

    public enum Kind { USER, ANSWER, FOOTER, SUGGESTIONS }

    public static final class Round {
        public final int seq;
        public String question;
        public String answer;
        /** 附图（上传图片那一轮）：Android 侧的缩略图对象（不透明）。 */
        public Object image;
        public String route;
        public long latencyMs;
        /** 1 = 赞，-1 = 踩，0 = 没点。 */
        public int feedback;
        public boolean frozen;
        /** 执行类结果卡快照（Android 位图，不透明）。 */
        public final List<Object> cards = new ArrayList<Object>();
        public final List<String> suggestions = new ArrayList<String>();

        Round(int seq) {
            this.seq = seq;
        }
    }

    public static final class Item {
        public final Kind kind;
        public final Round round;
        public final boolean last;

        Item(Kind kind, Round round, boolean last) {
            this.kind = kind;
            this.round = round;
            this.last = last;
        }
    }

    private final List<Round> mRounds = new ArrayList<Round>();
    private int mSeq;
    private final int mMax;

    public ChatTranscript() {
        this(ChatWindowPolicy.MAX_ROUNDS);
    }

    public ChatTranscript(int maxRounds) {
        mMax = Math.max(1, maxRounds);
    }

    /** 当前（还没冻结的）这一轮出结果：新建或覆盖。question 为空 / 伪问句时保留原来的问句。 */
    public synchronized Round upsert(String question, String answer, String route, long latencyMs) {
        Round r = live();
        if (r == null) {
            r = new Round(++mSeq);
            mRounds.add(r);
            trim();
        }
        if (question != null && question.trim().length() > 0) r.question = question.trim();
        r.answer = answer == null ? "" : answer;
        if (route != null) r.route = route;
        if (latencyMs > 0) r.latencyMs = latencyMs;
        return r;
    }

    /** 还没冻结的那一轮（屏上正在显示的），没有 → null。 */
    public synchronized Round live() {
        if (mRounds.isEmpty()) return null;
        Round r = mRounds.get(mRounds.size() - 1);
        return r.frozen ? null : r;
    }

    /** 下一轮开始：把当前这一轮冻结进历史。返回被冻结的那一轮（没有 → null）。 */
    public synchronized Round freeze() {
        Round r = live();
        if (r == null) return null;
        if ((r.question == null || r.question.length() == 0) && (r.answer == null || r.answer.length() == 0)) {
            mRounds.remove(r);
            return null;
        }
        r.frozen = true;
        return r;
    }

    public synchronized void setSuggestions(List<String> labels) {
        Round r = mRounds.isEmpty() ? null : mRounds.get(mRounds.size() - 1);
        if (r == null) return;
        r.suggestions.clear();
        r.suggestions.addAll(suggestionsFrom(labels));
    }

    /** 第 seq 轮的赞 / 踩（再点同一个 = 取消）。返回新状态。 */
    public synchronized int toggleFeedback(int seq, int value) {
        for (Round r : mRounds) {
            if (r.seq != seq) continue;
            r.feedback = r.feedback == value ? 0 : value;
            return r.feedback;
        }
        return 0;
    }

    public synchronized int size() {
        return mRounds.size();
    }

    public synchronized int frozenCount() {
        int n = 0;
        for (Round r : mRounds) if (r.frozen) n++;
        return n;
    }

    public synchronized List<Round> rounds() {
        return Collections.unmodifiableList(new ArrayList<Round>(mRounds));
    }

    public synchronized List<Round> frozenRounds() {
        List<Round> l = new ArrayList<Round>();
        for (Round r : mRounds) if (r.frozen) l.add(r);
        return l;
    }

    public synchronized void clear() {
        mRounds.clear();
    }

    /** 已冻结的历史展开成列表项（按时间往下排）。 */
    public synchronized List<Item> items() {
        List<Item> out = new ArrayList<Item>();
        List<Round> fr = frozenRounds();
        for (int i = 0; i < fr.size(); i++) {
            Round r = fr.get(i);
            boolean last = i == fr.size() - 1 && live() == null;
            if (r.question != null && r.question.length() > 0) out.add(new Item(Kind.USER, r, last));
            if (r.answer != null && r.answer.length() > 0) {
                out.add(new Item(Kind.ANSWER, r, last));
                out.add(new Item(Kind.FOOTER, r, last));
            }
            if (last && !r.suggestions.isEmpty()) out.add(new Item(Kind.SUGGESTIONS, r, true));
        }
        return out;
    }

    /** 追问建议：复用现有来源（面板动作芯片的文案），去空 / 去重 / 截长，最多 {@link ChatWindowPolicy#MAX_SUGGESTIONS} 个。 */
    public static List<String> suggestionsFrom(List<String> labels) {
        List<String> out = new ArrayList<String>();
        if (labels == null) return out;
        for (String s : labels) {
            if (s == null) continue;
            String t = s.trim();
            if (t.length() == 0 || out.contains(t)) continue;
            if (t.length() > 40) t = t.substring(0, 39) + "…";
            out.add(t);
            if (out.size() >= ChatWindowPolicy.MAX_SUGGESTIONS) break;
        }
        return out;
    }

    private void trim() {
        while (mRounds.size() > mMax) mRounds.remove(0);
    }
}
