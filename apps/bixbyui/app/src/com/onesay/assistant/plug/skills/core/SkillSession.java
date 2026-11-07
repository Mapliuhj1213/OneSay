/*
 * OneSay 自写（skill-pack，2026-11-02），非上游移植。core 包只依赖 java.*。
 */
package com.onesay.assistant.plug.skills.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 多轮对话里「已加载的技能」：同一个话题里的追问（「再大一点」「发吧」「换成明天」）继续带着上一轮的技能；
 * 换话题（新一句命中了别的技能，或既没命中也不像追问）就卸载。超过 {@link #maxHoldTurns} 轮没有被重新命中、
 * 或距离上次命中超过 {@link #ttlMs} 也卸载（stale）。线程安全（宿主可能在不同线程起任务）。
 * 宿主在会话清空（面板收起 / 新会话）时调 {@link #reset}。
 */
public final class SkillSession {
    public long ttlMs = 5 * 60 * 1000L;
    public int maxHoldTurns = 3;
    /** 追问判断：短句，或以这些词开头 / 含指代。 */
    public int shortQuery = 10;
    static final Pattern FOLLOW = Pattern.compile("^(再|还|继续|然后|接着|那|那就|就|改|换|发吧|发出去|好的|好|可以|行|对|不对|不是|也|顺便|再来|算了|取消|确认|是的|嗯)"
            + "|(它|他|她|这个|那个|刚才|上面|前面|同样|一样)");

    private final List<String> loaded = new ArrayList<String>();
    private final Map<String, Long> lastHitAt = new HashMap<String, Long>();
    private final Map<String, Integer> turnsSinceHit = new HashMap<String, Integer>();
    private long lastTurnAt;

    public synchronized List<String> loaded() {
        return new ArrayList<String>(loaded);
    }

    public synchronized void reset() {
        loaded.clear();
        lastHitAt.clear();
        turnsSinceHit.clear();
        lastTurnAt = 0;
    }

    /** 这一句像不像同一话题的追问（只在本轮没有新命中时才看）。 */
    public synchronized boolean isFollowUp(String query, long now, boolean noFreshHit) {
        if (!noFreshHit || loaded.isEmpty()) return false;
        if (lastTurnAt > 0 && now - lastTurnAt > ttlMs) return false;
        String q = SkillMatcher.norm(query);
        if (q.length() == 0) return false;
        return q.length() <= shortQuery || FOLLOW.matcher(q).find();
    }

    synchronized boolean stale(String id, long now) {
        Long at = lastHitAt.get(id);
        Integer turns = turnsSinceHit.get(id);
        if (at != null && now - at > ttlMs) return true;
        return turns != null && turns >= maxHoldTurns;
    }

    /** 本轮结束：loaded = 本轮实际注入的；hits = 其中本轮被规则重新命中的。 */
    synchronized void update(List<String> ids, List<String> hits, long now) {
        loaded.clear();
        loaded.addAll(ids);
        for (String id : new ArrayList<String>(lastHitAt.keySet())) if (!ids.contains(id)) {
            lastHitAt.remove(id);
            turnsSinceHit.remove(id);
        }
        for (String id : ids) {
            if (hits.contains(id)) {
                lastHitAt.put(id, now);
                turnsSinceHit.put(id, 0);
            } else {
                Integer t = turnsSinceHit.get(id);
                turnsSinceHit.put(id, t == null ? 1 : t + 1);
                if (!lastHitAt.containsKey(id)) lastHitAt.put(id, now);
            }
        }
        lastTurnAt = now;
    }
}
