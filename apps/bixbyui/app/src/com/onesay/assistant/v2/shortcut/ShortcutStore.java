package com.onesay.assistant.v2.shortcut;

import com.onesay.assistant.v2.conn.Redact;
import com.onesay.assistant.v2.planner.PlannerBackend;
import com.onesay.assistant.v2.planner.PlannerContract;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [shortcut] 快捷指令的读写：数据在计划 App（{@code shortcuts} / {@code shortcut_runs}），经现有后门 {@link PlannerBackend}
 * （真机 ContentResolver；单测 {@link PlannerBackend.Mem}）。OneSay 自己不另存一份。
 *
 * <p><b>执行记录不存参数</b>：只写工具名 + 每步成败（{@link ShortcutRunner.Result#summary}）和经 {@link Redact} 脱敏、截断的一句结果。
 */
public final class ShortcutStore {

    final PlannerBackend b;

    public ShortcutStore(PlannerBackend b) {
        this.b = b;
    }

    /** 全部快捷指令（步骤损坏的跳过，不让一条坏数据拖垮命中）。 */
    public List<ShortcutModel.Shortcut> all() {
        List<ShortcutModel.Shortcut> out = new ArrayList<ShortcutModel.Shortcut>();
        for (Map<String, Object> r : b.list(PlannerContract.T_SHORTCUTS)) {
            try {
                out.add(ShortcutModel.fromRow(r));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return out;
    }

    public ShortcutModel.Shortcut get(long id) {
        Map<String, Object> r = b.get(PlannerContract.T_SHORTCUTS, id);
        if (r == null) return null;
        try {
            return ShortcutModel.fromRow(r);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public ShortcutModel.Shortcut byName(String name) {
        String n = ShortcutModel.normName(name);
        for (ShortcutModel.Shortcut s : all()) if (ShortcutModel.normName(s.name).equals(n)) return s;
        return null;
    }

    /** 新建（对话里「存为快捷指令」）。 */
    public long insert(String name, List<String> aliases, List<ShortcutModel.Step> steps, String origin) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("name", name);
        m.put("aliases_json", new JSONArray(aliases == null ? new ArrayList<String>() : aliases).toString());
        m.put("steps_json", ShortcutModel.stepsJson(steps));
        m.put("triggers_json", ShortcutModel.DEFAULT_TRIGGERS);
        m.put("origin", origin == null ? "chat" : origin);
        m.put("source", "bixbyui");
        return b.insert(PlannerContract.T_SHORTCUTS, m);
    }

    public void update(long id, Map<String, Object> cols) {
        if (cols == null || cols.isEmpty()) return;
        b.update(PlannerContract.T_SHORTCUTS, id, cols);
    }

    // ------------------------------------------------------------------ 执行记录

    /** 开始一次运行：有排队中的请求（计划 App 点击 / 桌面）就沿用那一行，否则新插一行。返回 run 行 id。 */
    public long beginRun(ShortcutModel.Shortcut sc, String trigger, long now) {
        Map<String, Object> seq = ShortcutModel.beginRun(sc, now);
        update(sc.id, seq);
        sc.runSeq = ((Number) seq.get("run_seq")).longValue();
        Map<String, Object> queued = queued(sc.id);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("status", ShortcutModel.SC_RUNNING);
        m.put("run_seq", sc.runSeq);
        if (queued != null) {
            long id = ShortcutModel.l(queued, "_id");
            b.update(PlannerContract.T_SHORTCUT_RUNS, id, m);
            return id;
        }
        m.put("shortcut_id", sc.id);
        m.put("started_at", now);
        m.put("trigger_info", trigger == null ? "" : trigger);
        m.put("source", "bixbyui");
        return b.insert(PlannerContract.T_SHORTCUT_RUNS, m);
    }

    /** 这条快捷指令最近一个排队中的请求（没有返回 null）。 */
    public Map<String, Object> queued(long scId) {
        Map<String, Object> best = null;
        for (Map<String, Object> r : b.list(PlannerContract.T_SHORTCUT_RUNS)) {
            if (ShortcutModel.l(r, "shortcut_id") != scId || !ShortcutModel.SC_QUEUED.equals(ShortcutModel.str(r, "status"))) continue;
            if (best == null || ShortcutModel.l(r, "started_at") > ShortcutModel.l(best, "started_at")) best = r;
        }
        return best;
    }

    public void endRun(long runId, long scId, String status, String mode, String summary, String result, int failedStep, boolean updated, long now) {
        String safe = Redact.s(result == null ? "" : result, 300);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("status", status);
        m.put("mode", mode);
        m.put("ended_at", now);
        m.put("steps_summary", summary == null ? "" : (summary.length() > 1000 ? summary.substring(0, 1000) : summary));
        m.put("result", safe);
        m.put("failed_step", failedStep);
        m.put("updated", updated ? 1 : 0);
        b.update(PlannerContract.T_SHORTCUT_RUNS, runId, m);
        Map<String, Object> s = new LinkedHashMap<String, Object>();
        s.put("last_status", status);
        s.put("last_result", safe);
        update(scId, s);
    }

    /** 计划 App 发起、还没人认领的请求（过期的标成 stale，不再执行）。 */
    public List<Map<String, Object>> takeRequests(long now) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : b.list(PlannerContract.T_SHORTCUT_RUNS)) {
            if (!ShortcutModel.SC_REQUESTED.equals(ShortcutModel.str(r, "status"))) continue;
            long id = ShortcutModel.l(r, "_id");
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            if (now - ShortcutModel.l(r, "started_at") > ShortcutModel.REQUEST_STALE_MS) {
                m.put("status", ShortcutModel.SC_STALE);
                b.update(PlannerContract.T_SHORTCUT_RUNS, id, m);
                continue;
            }
            m.put("status", ShortcutModel.SC_QUEUED);
            b.update(PlannerContract.T_SHORTCUT_RUNS, id, m);
            out.add(r);
        }
        return out;
    }
}
