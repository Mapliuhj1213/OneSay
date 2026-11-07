package com.onesay.assistant.v2.agent;

import com.onesay.assistant.v2.island.Subgoal;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ★ arch/agent-loop（B 路）：AgentLoop 的子目标账本（{@code task.todo} 工具的状态；纯 Java，JVM 可测）。
 *
 * <p>模型用 {@code task.todo{items:[{id,title,state}]}} 写 / 改账本：
 * <ul>
 *   <li>带 id 的项按 id <b>更新</b>（只给 state 也行，title 留旧的）；新 id 追加；</li>
 *   <li>不带 id 的项按顺序补成 g1、g2…；</li>
 *   <li>{@code replace=true} 时先清空再写（重排计划）；</li>
 *   <li>state ∈ pending / doing / done / failed（宽松解析见 {@link Subgoal.State#fromWire}）；同一时刻最多一个 doing（新标的 doing 胜出，旧的退回 pending）。</li>
 * </ul>
 * 每次变化后，AgentLoop 把<b>完整列表</b>作为结构化事件发给宿主（→ {@code S1Hooks.onSubgoals}，C 路灵动岛显示），
 * 并把账本文本回给模型（{@link #render}），模型下一轮看得到自己的计划和进度。
 */
public final class TodoLedger {

    private final List<Subgoal> mItems = new ArrayList<Subgoal>();
    private int mSeq;

    public synchronized List<Subgoal> snapshot() {
        return Collections.unmodifiableList(new ArrayList<Subgoal>(mItems));
    }

    public synchronized boolean isEmpty() {
        return mItems.isEmpty();
    }

    /** 应用一次 task.todo 调用。返回错误说明（null = 成功）。 */
    public synchronized String apply(JSONObject args) {
        if (args == null) return "缺少 items";
        JSONArray arr = args.optJSONArray("items");
        if (arr == null || arr.length() == 0) return "items 至少要有一项";
        if (args.optBoolean("replace", false)) {
            mItems.clear();
            mSeq = 0;
        }
        String doingId = null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it == null) continue;
            String id = it.optString("id", "").trim();
            String title = it.optString("title", "").trim();
            String stateRaw = it.optString("state", it.optString("status", "")).trim();
            int idx = id.length() > 0 ? indexOf(id) : -1;
            if (idx >= 0) {
                Subgoal old = mItems.get(idx);
                Subgoal.State st = stateRaw.length() > 0 ? Subgoal.State.fromWire(stateRaw) : old.state;
                mItems.set(idx, new Subgoal(old.id, title.length() > 0 ? clip(title) : old.title, st));
                if (st == Subgoal.State.DOING) doingId = old.id;
            } else {
                if (title.length() == 0) continue;   // 新项必须有标题
                if (mItems.size() >= Subgoal.MAX) return "子目标最多 " + Subgoal.MAX + " 个";
                if (id.length() == 0) id = nextId();
                Subgoal.State st = Subgoal.State.fromWire(stateRaw);
                mItems.add(new Subgoal(id, clip(title), st));
                if (st == Subgoal.State.DOING) doingId = id;
            }
        }
        if (doingId != null) {
            for (int i = 0; i < mItems.size(); i++) {
                Subgoal g = mItems.get(i);
                if (g.state == Subgoal.State.DOING && !g.id.equals(doingId)) mItems.set(i, g.withState(Subgoal.State.PENDING));
            }
        }
        return mItems.isEmpty() ? "items 里没有可用的子目标（新项需要 title）" : null;
    }

    /** 收尾时把还在 doing 的项标成 failed / done（不改 pending）。 */
    public synchronized boolean settleDoing(Subgoal.State to) {
        boolean changed = false;
        for (int i = 0; i < mItems.size(); i++) {
            if (mItems.get(i).state == Subgoal.State.DOING) {
                mItems.set(i, mItems.get(i).withState(to));
                changed = true;
            }
        }
        return changed;
    }

    public synchronized int count(Subgoal.State s) {
        int n = 0;
        for (Subgoal g : mItems) if (g.state == s) n++;
        return n;
    }

    /** 给模型看的账本文本（观察值）。 */
    public synchronized String render() {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("tool", "task.todo");
            JSONArray arr = new JSONArray();
            for (Subgoal g : mItems) {
                JSONObject it = new JSONObject();
                it.put("id", g.id);
                it.put("title", g.title);
                it.put("state", g.state.wire());
                arr.put(it);
            }
            o.put("items", arr);
            int done = 0, failed = 0;
            for (Subgoal g : mItems) {
                if (g.state == Subgoal.State.DONE) done++;
                else if (g.state == Subgoal.State.FAILED) failed++;
            }
            o.put("progress", done + "/" + mItems.size());
            o.put("hint", failed > 0 ? "有子目标失败：换一条没试过的路，或在最终答复里说明哪一项没做成、为什么。"
                    : (done == mItems.size() ? "子目标都完成了，可以给出最终答复。" : "按顺序做下一项；开始做时标 doing，做完标 done，做不成标 failed。"));
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":true,\"tool\":\"task.todo\"}";
        }
    }

    private int indexOf(String id) {
        for (int i = 0; i < mItems.size(); i++) if (mItems.get(i).id.equals(id)) return i;
        return -1;
    }

    private String nextId() {
        String id;
        do {
            id = "g" + (++mSeq);
        } while (indexOf(id) >= 0);
        return id;
    }

    private static String clip(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40);
    }
}
