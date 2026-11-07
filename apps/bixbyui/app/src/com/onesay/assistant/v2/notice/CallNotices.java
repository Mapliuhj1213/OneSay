package com.onesay.assistant.v2.notice;

/**
 * · 通话上岛（纯 Java，JVM 可测）：电话状态机 + 号码打码 + 提醒构造。
 *
 * <p>输入是 {@code TelephonyManager} 的三态（IDLE / RINGING / OFFHOOK，经 PhoneStateListener，只要 READ_PHONE_STATE；
 * 来电号码在 Android 9 还要 READ_CALL_LOG，清单里已有）。输出是要对中枢做的事：发 / 撤哪条。
 * <ul>
 *   <li>RINGING → 「来电 · 张三」（高优先级），动作「接听 / 挂断」；</li>
 *   <li>OFFHOOK → 「通话中 · 张三」+ 时长自己走，动作「静音 / 免提 / 挂断」（静音、免提点完卡片不收，按钮文字跟着变）；</li>
 *   <li>RINGING → IDLE 且没接起来 → 「未接来电 · 张三」（普通优先级，免打扰时段照常压着），动作「通话记录 / 知道了」；</li>
 *   <li>IDLE → 撤下「来电 / 通话中」。</li>
 * </ul>
 * <b>隐私</b>：号码不进去重键、不进动作参数、不进日志；有联系人显示名字，没有就打码（138****5678），除非用户在控制台打开「显示号码」。
 */
public final class CallNotices {

    private CallNotices() {}

    public static final int IDLE = 0, RINGING = 1, OFFHOOK = 2;

    public static final String TAG = "call";
    public static final String A_ANSWER = "answer", A_END = "end", A_MUTE = "mute", A_SPEAKER = "speaker";

    /** 号码打码（纯函数）：只留前 3 后 4；7 位以下（10086、短号）原样；空 / 隐藏号码 → 「未知号码」。 */
    public static String maskNumber(String raw) {
        if (raw == null) return "未知号码";
        StringBuilder digits = new StringBuilder();
        boolean plus = raw.trim().startsWith("+");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') digits.append(c);
        }
        String d = digits.toString();
        if (d.length() == 0) return "未知号码";
        if (d.length() < 7) return d;
        String head = d.substring(0, 3), tail = d.substring(d.length() - 4);
        return (plus ? "+" : "") + head + "****" + tail;
    }

    /** 显示名：联系人名优先；否则按设置显示号码原文或打码。 */
    public static String display(String contactName, String number, boolean revealNumber) {
        if (contactName != null && contactName.trim().length() > 0) return IslandNotice.clip(contactName, 16);
        if (revealNumber && number != null && number.trim().length() > 0) return IslandNotice.clip(number.trim(), 20);
        return maskNumber(number);
    }

    /** 电话状态机：喂状态，吐要做的事（纯逻辑）。 */
    public static final class Machine {
        int mState = IDLE;
        long mRingAt, mOffhookAt;
        boolean mAnswered;
        String mWho = "";

        public int state() { return mState; }
        public long offhookAt() { return mOffhookAt; }
        public long ringAt() { return mRingAt; }
        public String who() { return mWho; }

        /** 一次状态变化 → 结果（同状态重复回调 = 只更新显示名）。 */
        public Step on(int state, String who, long now) {
            Step s = new Step();
            if (who != null && who.length() > 0) mWho = who;
            int prev = mState;
            if (state == prev) {
                if (state == RINGING) s.post = ringing(mWho, mRingAt);
                else if (state == OFFHOOK) s.post = inCall(mWho, mOffhookAt, false, false);
                return s;
            }
            mState = state;
            if (state == RINGING) {
                mRingAt = now;
                mAnswered = false;
                s.post = ringing(mWho, mRingAt);
            } else if (state == OFFHOOK) {
                mOffhookAt = now;
                if (prev == RINGING) mAnswered = true;
                s.retract = new String[]{"call:ring:"};
                s.post = inCall(mWho, mOffhookAt, false, false);
            } else {   // IDLE
                s.retract = new String[]{"call:ring:", "call:live:"};   // 未接来电留着
                if (prev == RINGING && !mAnswered) s.post = missed(mWho, mRingAt);
                mWho = "";
                mAnswered = false;
            }
            return s;
        }
    }

    public static final class Step {
        /** 先撤：键以这些前缀开头、还没处理的（来电 / 通话中；未接来电不撤）。 */
        public String[] retract = new String[0];
        public IslandNotice post;
    }

    public static IslandNotice ringing(String who, long at) {
        IslandNotice n = new IslandNotice(IslandNotice.Source.CALL, "call:ring:" + at, IslandNotice.Priority.HIGH,
                "来电 · " + (who == null || who.length() == 0 ? "未知号码" : who), "");
        n.sourceTag = TAG;
        n.tone = "call";
        n.persist = false;
        n.action(new NoticeAction("接听", NoticeAction.Kind.CUSTOM, A_ANSWER));
        n.action(new NoticeAction("挂断", NoticeAction.Kind.CUSTOM, A_END));
        return n;
    }

    public static IslandNotice inCall(String who, long since, boolean muted, boolean speaker) {
        IslandNotice n = new IslandNotice(IslandNotice.Source.CALL, "call:live:" + since, IslandNotice.Priority.NORMAL,
                "通话中 · " + (who == null || who.length() == 0 ? "未知号码" : who),
                (muted ? "已静音" : "") + (muted && speaker ? " · " : "") + (speaker ? "免提" : ""));
        n.sourceTag = TAG;
        n.tone = "call";
        n.chronoBase = since;
        n.persist = false;
        n.action(new NoticeAction(muted ? "取消静音" : "静音", NoticeAction.Kind.INLINE, A_MUTE));
        n.action(new NoticeAction(speaker ? "关免提" : "免提", NoticeAction.Kind.INLINE, A_SPEAKER));
        n.action(new NoticeAction("挂断", NoticeAction.Kind.CUSTOM, A_END));
        return n;
    }

    public static IslandNotice missed(String who, long at) {
        IslandNotice n = new IslandNotice(IslandNotice.Source.CALL, "call:miss:" + at, IslandNotice.Priority.NORMAL,
                "未接来电 · " + (who == null || who.length() == 0 ? "未知号码" : who), "");
        n.sourceTag = TAG;
        n.expireAt = at + 2L * 3600000L;
        n.action(new NoticeAction("通话记录", NoticeAction.Kind.CUSTOM, "calllog"));
        n.action(new NoticeAction("知道了", NoticeAction.Kind.DISMISS, ""));
        return n;
    }
}
