/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core;

import com.onesay.assistant.plug.api.Storage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * 频控与防打扰（§5.5）。判定顺序：去重 → 退避冷却 → 场景最小间隔 → 场景日上限 → 静默条件 → 夜间 → 全局配额。
 * 只记场景 id / 时间 / 原因，不记内容。状态存插件 Storage（键 {@code gov}），不同步、不上报。
 *
 * <ul>
 *   <li>全局：每 10 分钟 ≤3、每小时 ≤6、每天 ≤30（克制档减半；priority ≥ {@link SuggestDefaults#QUOTA_EXEMPT_PRIORITY} 不受全局配额约束但计数）；</li>
 *   <li>连续忽略（TIMEOUT / DISMISSED）3 次 → 冷却 2 h；6 次 → 冷却 24 h，下次出卡副标题提示「不常用？可以关掉」；点了按钮清零；</li>
 *   <li>静默：锁屏、息屏、通话中、勿扰、全屏沉浸、助手忙 → priority &lt; 2 丢弃，≥2 推迟 10 s 再试一次；</li>
 *   <li>夜间 23:00–07:00 只放 priority ≥ 2；</li>
 *   <li>{@link RatePolicy#userInitiated}（截图 = 用户刚主动按了截屏）：夜间 / 克制档 / 全局配额不拦，仍计数；到时自动收起不计入连续忽略；</li>
 *   <li>external（只记账，例如取件码）一律放行并计数。</li>
 * </ul>
 */
public final class RateGovernor {

    public static final int ALLOW = 0, DEFER = 1, DROP = 2;

    public static final class Verdict {
        public final int kind;
        public final String reason;
        public final long deferMs;
        /** 本次出卡要带「不常用？可以关掉」提示。 */
        public final boolean hint;

        Verdict(int kind, String reason, long deferMs, boolean hint) {
            this.kind = kind;
            this.reason = reason;
            this.deferMs = deferMs;
            this.hint = hint;
        }

        public boolean allowed() {
            return kind == ALLOW;
        }

        @Override public String toString() {
            return kind == ALLOW ? "ALLOW" + (hint ? "+hint" : "") : kind == DEFER ? "DEFER(" + reason + ")" : "DROP(" + reason + ")";
        }
    }

    static final class SceneState {
        long lastShown;
        String dayKey = "";
        int dayCount;
        int streak;
        long cooldownUntil;
        boolean hintPending;
    }

    private final SceneRegistry mReg;
    private final Storage mStore;
    private final TimeZone mTz;
    private final List<Long> mShown = new ArrayList<Long>();
    private final Map<String, SceneState> mScenes = new HashMap<String, SceneState>();
    private final LinkedHashMap<String, Long> mDedup = new LinkedHashMap<String, Long>();

    public RateGovernor(SceneRegistry reg, Storage store, TimeZone tz) {
        mReg = reg;
        mStore = store;
        mTz = tz == null ? TimeZone.getDefault() : tz;
        load();
    }

    public synchronized Verdict permit(Suggestion s, HostState h, int quietLevel, long now, boolean retried) {
        if (s.external) return new Verdict(ALLOW, null, 0, false);
        RatePolicy p = mReg.policy(s.sceneId);
        SceneState st = scene(s.sceneId);
        if (s.dedupKey != null) {
            Long at = mDedup.get(hash(s.dedupKey));
            if (at != null && now - at < p.dedupWindowMs && now >= at) return drop("dup");
        }
        if (st.cooldownUntil > now) return drop("backoff");
        if (p.minIntervalMs > 0 && st.lastShown > 0 && now - st.lastShown < p.minIntervalMs && now >= st.lastShown) return drop("interval");
        if (p.dailyCap > 0 && dayKey(now).equals(st.dayKey) && st.dayCount >= p.dailyCap) return drop("scene_daily");
        String quiet = quietReason(h);
        if (quiet != null) {
            if (s.priority >= 2 && !retried) return new Verdict(DEFER, "quiet:" + quiet, SuggestDefaults.DEFER_MS, false);
            return drop("quiet:" + quiet);
        }
        // 用户主动行为（截图）：人刚按了截屏，问一句不算打扰 → 夜间 / 克制档 / 全局配额都不拦（真机 2026-11-05 00:07 授权后两次截图都 SKIP(night)）
        if (!p.userInitiated && s.priority < SuggestDefaults.NIGHT_MIN_PRIORITY && isNight(now)) return drop("night");
        if (!p.userInitiated && quietLevel >= 2 && s.priority < 2) return drop("quiet_level");
        if (!p.userInitiated && s.priority < SuggestDefaults.QUOTA_EXEMPT_PRIORITY) {
            prune(now);
            int div = quietLevel >= 1 ? 2 : 1;
            if (count(now, 10L * 60000L) >= Math.max(1, SuggestDefaults.GLOBAL_PER_10MIN / div)) return drop("global_10min");
            if (count(now, 3600000L) >= Math.max(1, SuggestDefaults.GLOBAL_PER_HOUR / div)) return drop("global_hour");
            if (count(now, 24L * 3600000L) >= Math.max(1, SuggestDefaults.GLOBAL_PER_DAY / div)) return drop("global_day");
        }
        return new Verdict(ALLOW, null, 0, st.hintPending);
    }

    /** 出卡 / 外部上岛成功：计数、去重登记、清掉提示标记。 */
    public synchronized void recordShown(Suggestion s, long now) {
        prune(now);
        mShown.add(now);
        SceneState st = scene(s.sceneId);
        st.lastShown = now;
        String dk = dayKey(now);
        if (!dk.equals(st.dayKey)) {
            st.dayKey = dk;
            st.dayCount = 0;
        }
        st.dayCount++;
        st.hintPending = false;
        if (s.dedupKey != null) {
            String hk = hash(s.dedupKey);
            mDedup.remove(hk);
            mDedup.put(hk, now);
            while (mDedup.size() > 300) {
                Iterator<String> it = mDedup.keySet().iterator();
                it.next();
                it.remove();
            }
        }
        save();
    }

    /** 结局回流：连续忽略退避。 */
    public synchronized void recordOutcome(String sceneId, Outcome o, long now) {
        if (sceneId == null) return;
        RatePolicy p = mReg.policy(sceneId);
        SceneState st = scene(sceneId);
        if (o == Outcome.ACTION) {
            st.streak = 0;
            st.hintPending = false;
        } else if (o == Outcome.TIMEOUT && p.userInitiated) {
            return;   // 主动截图卡到时自动收起 = 这次不需要，不算「忽略」，不攒退避（否则测 3 次不点就 2 h 没卡，像坏了）；点「忽略」/ 划掉仍算
        } else if ((o == Outcome.TIMEOUT || o == Outcome.DISMISSED) && p.ignoreBackoff) {
            st.streak++;
            if (st.streak % SuggestDefaults.IGNORE_STREAK_LONG == 0) {
                st.cooldownUntil = now + SuggestDefaults.IGNORE_COOLDOWN_LONG_MS;
                st.hintPending = true;
            } else if (st.streak % SuggestDefaults.IGNORE_STREAK_LONG == SuggestDefaults.IGNORE_STREAK_SHORT) {
                st.cooldownUntil = now + SuggestDefaults.IGNORE_COOLDOWN_SHORT_MS;
            }
        } else {
            return;
        }
        save();
    }

    /** 重新打开某场景时清掉它的退避（控制台 / 撤销）。 */
    public synchronized void resetScene(String sceneId) {
        mScenes.remove(sceneId);
        save();
    }

    public synchronized int streak(String sceneId) {
        return scene(sceneId).streak;
    }

    public synchronized long cooldownUntil(String sceneId) {
        return scene(sceneId).cooldownUntil;
    }

    public synchronized int shownInLast(long now, long windowMs) {
        return count(now, windowMs);
    }

    static String quietReason(HostState h) {
        if (h == null) return null;
        try {
            if (!h.screenOn()) return "screen_off";
            if (h.keyguardLocked()) return "locked";
            if (h.inCall()) return "in_call";
            if (h.dndOn()) return "dnd";
            if (h.fullscreenImmersive()) return "immersive";
            if (h.assistantBusy()) return "assistant_busy";
        } catch (Throwable t) {
            return "host_error";
        }
        return null;
    }

    boolean isNight(long now) {
        Calendar c = Calendar.getInstance(mTz);
        c.setTimeInMillis(now);
        int hr = c.get(Calendar.HOUR_OF_DAY);
        return hr >= SuggestDefaults.NIGHT_FROM_HOUR || hr < SuggestDefaults.NIGHT_TO_HOUR;
    }

    private String dayKey(long now) {
        Calendar c = Calendar.getInstance(mTz);
        c.setTimeInMillis(now);
        return c.get(Calendar.YEAR) + "-" + c.get(Calendar.DAY_OF_YEAR);
    }

    private int count(long now, long window) {
        int n = 0;
        for (Long t : mShown) if (now - t < window && now >= t) n++;
        return n;
    }

    private void prune(long now) {
        Iterator<Long> it = mShown.iterator();
        while (it.hasNext()) if (now - it.next() >= 24L * 3600000L) it.remove();
    }

    private SceneState scene(String id) {
        SceneState s = mScenes.get(id);
        if (s == null) {
            s = new SceneState();
            mScenes.put(id, s);
        }
        return s;
    }

    /** 去重键落盘前先散列（截图键含文件名，不以明文存）。FNV-1a 64。 */
    static String hash(String k) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < k.length(); i++) {
            h ^= k.charAt(i);
            h *= 0x100000001b3L;
        }
        return Long.toHexString(h);
    }

    private static Verdict drop(String r) {
        return new Verdict(DROP, r, 0, false);
    }

    // ---------------------------------------------------------------- 持久化（JSON，键 gov）

    private void load() {
        if (mStore == null) return;
        try {
            String raw = mStore.all().get("gov");
            if (raw == null) return;
            JSONObject o = new JSONObject(raw);
            JSONArray sh = o.optJSONArray("shown");
            if (sh != null) for (int i = 0; i < sh.length(); i++) mShown.add(sh.getLong(i));
            JSONObject sc = o.optJSONObject("scenes");
            if (sc != null) {
                Iterator<String> it = sc.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject x = sc.getJSONObject(k);
                    SceneState s = new SceneState();
                    s.lastShown = x.optLong("last");
                    s.dayKey = x.optString("day", "");
                    s.dayCount = x.optInt("dc");
                    s.streak = x.optInt("streak");
                    s.cooldownUntil = x.optLong("cool");
                    s.hintPending = x.optBoolean("hint");
                    mScenes.put(k, s);
                }
            }
            JSONObject dd = o.optJSONObject("dedup");
            if (dd != null) {
                Iterator<String> it = dd.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    mDedup.put(k, dd.getLong(k));
                }
            }
        } catch (Throwable ignored) {
            // 坏数据：从空开始
        }
    }

    private void save() {
        if (mStore == null) return;
        try {
            JSONObject o = new JSONObject();
            JSONArray sh = new JSONArray();
            for (Long t : mShown) sh.put(t.longValue());
            o.put("shown", sh);
            JSONObject sc = new JSONObject();
            for (Map.Entry<String, SceneState> e : mScenes.entrySet()) {
                SceneState s = e.getValue();
                JSONObject x = new JSONObject();
                x.put("last", s.lastShown);
                x.put("day", s.dayKey);
                x.put("dc", s.dayCount);
                x.put("streak", s.streak);
                x.put("cool", s.cooldownUntil);
                x.put("hint", s.hintPending);
                sc.put(e.getKey(), x);
            }
            o.put("scenes", sc);
            JSONObject dd = new JSONObject();
            for (Map.Entry<String, Long> e : mDedup.entrySet()) dd.put(e.getKey(), e.getValue().longValue());
            o.put("dedup", dd);
            mStore.put("gov", o.toString());
        } catch (Throwable ignored) {
            // 落盘失败不影响判定
        }
    }
}
