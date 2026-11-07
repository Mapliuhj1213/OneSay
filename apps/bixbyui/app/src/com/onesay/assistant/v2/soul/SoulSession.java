package com.onesay.assistant.v2.soul;

import java.util.ArrayList;
import java.util.List;

/**
 * [soul] 一次面板会话里用户说过的话（只留用户原话，最多 20 句），会话结束时决定要不要做那一次整理调用（纯 Java）。
 * 用户说了「清空对话」→ 本会话作废，不整理（不能把用户要删的对话再提炼进记忆）。
 */
public final class SoulSession {

    public static final int MAX = 20;

    private final List<String> mUser = new ArrayList<String>();
    private boolean mDiscard;
    private String mId = newId();

    private static String newId() {
        return "s" + Long.toString(System.currentTimeMillis(), 36);
    }

    public synchronized void onUser(String q) {
        if (q == null || q.trim().length() == 0) return;
        if (mUser.size() >= MAX) mUser.remove(0);
        mUser.add(q.trim());
    }

    public synchronized void discard() {
        mDiscard = true;
        mUser.clear();
    }

    public synchronized String id() { return mId; }

    /**
     * 会话结束：返回要交给整理调用的用户原话（不需要整理返回 null），并重置为新会话。
     *
     * @param locked   锁屏中不整理（锁屏不读写私密记忆）
     * @param minTurns 至少几句
     */
    public synchronized List<String> end(boolean locked, int minTurns) {
        List<String> out = null;
        if (!mDiscard && !locked && mUser.size() >= minTurns) out = new ArrayList<String>(mUser);
        mUser.clear();
        mDiscard = false;
        mId = newId();
        return out;
    }

    public synchronized int size() { return mUser.size(); }
}
