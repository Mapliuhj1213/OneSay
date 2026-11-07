package com.onesay.assistant.v2.conn.watch;

import com.onesay.assistant.v2.conn.ConnResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [conn/watch] 监控任务的数据层 + 运行入口（纯逻辑，存储 / 搜索 / 时钟 / 端口都注入，可单测）。
 *
 * <ul>
 *   <li>增删改查：{@link #add} / {@link #remove} / {@link #setEnabled} / {@link #list}；改动后通知 Trigger 改期；</li>
 *   <li>运行：{@link #runOne}（A 线到点回调用）/ {@link #runDue}（批量）：搜索 → {@link WatchJudge} → 落状态 → 需要时调 Sink；</li>
 *   <li>上限 {@link #MAX_WATCHES} 个（搜索 Key 有额度）。</li>
 * </ul>
 * 线程：会触网（搜索），只在后台线程调；内部方法 synchronized，状态读写一致。
 */
public final class WatchService {

    public static final int MAX_WATCHES = 10;
    public static final int BROKEN_AFTER = 3;

    public interface Searcher {
        /** 搜索；data = [{title,url,snippet}]。 */
        ConnResult search(String query, int max);
    }

    public interface Store {
        String load();

        void save(String json);
    }

    public interface Clock {
        long now();
    }

    private final Store mStore;
    private final Searcher mSearch;
    private final Clock mClock;
    private final Map<String, WatchSpec> mSpecs = new LinkedHashMap<String, WatchSpec>();
    private final Map<String, WatchState> mStates = new LinkedHashMap<String, WatchState>();

    public WatchService(Store store, Searcher search, Clock clock) {
        mStore = store;
        mSearch = search;
        mClock = clock != null ? clock : new Clock() {
            @Override public long now() { return System.currentTimeMillis(); }
        };
        load();
    }

    private void load() {
        if (mStore == null) return;
        try {
            String raw = mStore.load();
            JSONArray arr = new JSONArray(raw == null || raw.length() == 0 ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                WatchSpec s = WatchSpec.fromJson(o == null ? null : o.optJSONObject("spec"));
                if (s == null || s.validate() != null) continue;
                mSpecs.put(s.id, s);
                mStates.put(s.id, WatchState.fromJson(o.optJSONObject("state")));
            }
        } catch (Exception ignored) {
        }
    }

    private void save() {
        if (mStore == null) return;
        JSONArray arr = new JSONArray();
        for (WatchSpec s : mSpecs.values()) {
            try {
                arr.put(new JSONObject().put("spec", s.toJson()).put("state", state(s.id).toJson()));
            } catch (Exception ignored) {
            }
        }
        mStore.save(arr.toString());
    }

    private WatchState state(String id) {
        WatchState st = mStates.get(id);
        if (st == null) {
            st = new WatchState();
            mStates.put(id, st);
        }
        return st;
    }

    public synchronized List<WatchSpec> list() {
        return new ArrayList<WatchSpec>(mSpecs.values());
    }

    public synchronized WatchSpec get(String id) {
        return mSpecs.get(id);
    }

    public synchronized WatchState stateOf(String id) {
        WatchState st = mStates.get(id);
        return st == null ? null : WatchState.fromJson(st.toJson());   // 拷贝
    }

    /** 新增 / 替换（替换会清空状态：查询变了，基线要重建）。@return null = 成功 */
    public synchronized String add(WatchSpec s) {
        String why = s == null ? "空" : s.validate();
        if (why != null) return why;
        WatchSpec old = mSpecs.get(s.id);
        if (old == null && mSpecs.size() >= MAX_WATCHES) return "监控最多 " + MAX_WATCHES + " 个，先删掉不用的";
        if (old == null || !old.query.equals(s.query) || !old.mode.equals(s.mode) || !old.keywords.equals(s.keywords)) {
            mStates.put(s.id, new WatchState());
        }
        mSpecs.put(s.id, s);
        save();
        reschedule(s);
        return null;
    }

    public synchronized boolean remove(String id) {
        boolean hit = mSpecs.remove(id) != null;
        mStates.remove(id);
        if (hit) {
            save();
            WatchPorts.trigger().cancel(id);
        }
        return hit;
    }

    public synchronized boolean setEnabled(String id, boolean on) {
        WatchSpec s = mSpecs.get(id);
        if (s == null) return false;
        WatchSpec n = s.withEnabled(on);
        mSpecs.put(id, n);
        save();
        reschedule(n);
        return true;
    }

    private void reschedule(WatchSpec s) {
        if (!s.enabled) {
            WatchPorts.trigger().cancel(s.id);
            return;
        }
        long at = WatchJudge.nextDueAt(s, mStates.get(s.id));
        WatchPorts.trigger().schedule(s.id, Math.max(at, mClock.now()));
    }

    /** 跑一个监控（不管到没到期；A 线到点回调 / 调试「立即运行」用）。 */
    public WatchJudge.Verdict runOne(String id) {
        WatchSpec s;
        synchronized (this) {
            s = mSpecs.get(id);
        }
        if (s == null) return null;
        ConnResult r = mSearch == null ? ConnResult.fail(ConnResult.Code.NOT_CONFIGURED, "没有可用的搜索") : mSearch.search(s.query, 8);
        List<WatchItem> items = r.ok() ? WatchItem.parse(r.data) : null;
        WatchJudge.Verdict v;
        WatchState snapshot;
        synchronized (this) {
            if (!mSpecs.containsKey(id)) return null;   // 运行期间被删
            WatchState st = state(id);
            v = WatchJudge.judge(s, st, items, r.ok() ? null : r.code + " " + r.text, mClock.now());
            snapshot = st;
            save();
            reschedule(s);
        }
        if (v.notifyNow()) {
            WatchPorts.sink().onChange(s, v.items, WatchJudge.message(s, v.items));
        } else if (v.outcome == WatchJudge.Outcome.ERROR && snapshot.failures == BROKEN_AFTER) {
            WatchPorts.sink().onBroken(s, snapshot.lastError);
        }
        return v;
    }

    /** 跑所有到期的（A 线也可以只用这一个入口，周期性调）。@return 跑了的 id → 结果 */
    public Map<String, WatchJudge.Verdict> runDue() {
        List<String> due = new ArrayList<String>();
        synchronized (this) {
            long now = mClock.now();
            for (WatchSpec s : mSpecs.values()) if (WatchJudge.due(s, mStates.get(s.id), now)) due.add(s.id);
        }
        Map<String, WatchJudge.Verdict> out = new LinkedHashMap<String, WatchJudge.Verdict>();
        for (String id : due) out.put(id, runOne(id));
        return out;
    }

    /** 最早的下次运行时间（没有返回 Long.MAX_VALUE）。 */
    public synchronized long nextDueAt() {
        long min = Long.MAX_VALUE;
        for (WatchSpec s : mSpecs.values()) min = Math.min(min, WatchJudge.nextDueAt(s, mStates.get(s.id)));
        return min;
    }
}
