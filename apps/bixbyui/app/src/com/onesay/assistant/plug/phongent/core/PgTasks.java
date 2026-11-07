/*
 * Copyright 2026 wangzan101 (Mika) and mylea (phongent, https://github.com/mylea1/phongent)
 * Modifications Copyright 2026 OneSay
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * 改动声明（OneSay，2026-11-02）：本文件改写自 phongent
 * core/src/main/kotlin/com/wangzan101/mika/core/run/ControllerQueue.kt 与
 * app/src/main/java/com/wangzan101/mika/remote/RemoteTaskQueue.kt（Kotlin → Java 7）。
 * 保留：任务 JSON 形状 {id,text,conversation,state,error,result}、状态名（queued / dispatching / running / completed /
 * failed / cancelled / uncertain）、id 幂等、先持久化再派发、重启后把 dispatching/running 记成 uncertain 不重放、
 * 只能取消排队中的、只能确认 uncertain 的、终态保留最近 400 条、活动任务上限 100。
 * 改动：执行交给 OneSay 自己的任务队列（{@link Executor}，生产 = JobQueue + S3Runner + AgentLoop，照走 ExecutionGate），
 * 状态从执行记录里读而不是自己推；会话固定一个（OneSay 没有多会话）；通知代回类任务标记为 tainted。
 */
package com.onesay.assistant.plug.phongent.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** [phongent] 主控下发的任务队列（协议层）。纯 Java；线程安全。 */
public final class PgTasks {

    public static final String CONVERSATION = "bixbyui-main";
    public static final int MAX_ACTIVE = 100;
    public static final int KEEP_TERMINAL = 400;
    public static final int MAX_TEXT = 8000;
    static final String KEY = "tasks";

    static final Set<String> ACTIVE = new HashSet<String>(Arrays.asList("queued", "dispatching", "running", "uncertain"));
    static final Set<String> TERMINAL = new HashSet<String>(Arrays.asList("completed", "failed", "cancelled"));

    public static final class Task {
        public String id;
        public String text;
        public String conversation = CONVERSATION;
        public String state = "queued";
        public String error = "";
        public String result = "";
        public long runId;
        public String step = "";
        /** 本机执行记录处在「等确认」（岛上确认卡）。 */
        public boolean confirming;
        public long createdAt;

        JSONObject toJson(boolean withText) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("text", withText ? text : "");
                o.put("conversation", conversation);
                o.put("state", state);
                o.put("error", error);
                o.put("result", result);
            } catch (Exception ignored) {
            }
            return o;
        }

        JSONObject persist() {
            JSONObject o = toJson(true);
            try {
                o.put("runId", runId);
                o.put("step", step);
                o.put("createdAt", createdAt);
            } catch (Exception ignored) {
            }
            return o;
        }

        static Task restore(JSONObject o) {
            Task t = new Task();
            t.id = o.optString("id");
            t.text = o.optString("text");
            t.conversation = o.optString("conversation", CONVERSATION);
            t.state = o.optString("state", "queued");
            t.error = o.optString("error");
            t.result = o.optString("result");
            t.runId = o.optLong("runId");
            t.step = o.optString("step");
            t.createdAt = o.optLong("createdAt");
            return t;
        }
    }

    private final Storage mStore;
    private final TaskExecutor mExec;
    private final List<Task> mTasks = new ArrayList<Task>();
    private Runnable mOnChange;

    public PgTasks(Storage store, TaskExecutor exec) {
        mStore = store;
        mExec = exec;
        String raw = store.all().get(KEY);
        if (raw != null) {
            try {
                JSONArray a = new JSONArray(raw);
                for (int i = 0; i < a.length(); i++) {
                    Task t = Task.restore(a.getJSONObject(i));
                    // 上游口径：重启时还在派发中的，不知道到底起没起 → 待核实，绝不重放
                    if ("dispatching".equals(t.state) && t.runId <= 0) {
                        t.state = "uncertain";
                        t.error = "执行结果待核实";
                    }
                    mTasks.add(t);
                }
            } catch (Exception ignored) {
            }
        }
    }

    public synchronized void setOnChange(Runnable r) {
        mOnChange = r;
    }

    static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9-]{8,80}");
    }

    /** 通知代回 / 带第三方原文的任务：从第一步起按 tainted 过闸（发送类动作必在本机确认）。 */
    public static boolean taintedText(String text) {
        if (text == null) return false;
        return text.contains("\"notification\"") || text.contains("\"notificationPreview\"")
                || text.contains("\"notificationTitle\"") || text.contains("回复通知对应会话");
    }

    private void save() {
        JSONArray a = new JSONArray();
        for (Task t : mTasks) a.put(t.persist());
        if (!mStore.put(KEY, a.toString())) throw new IllegalStateException("persist_failed");
        Runnable r = mOnChange;
        if (r != null) {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
        }
    }

    private Task find(String id) {
        for (Task t : mTasks) if (t.id.equals(id)) return t;
        return null;
    }

    private static JSONObject receipt(Task t) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", t.id);
            o.put("status", t.state);
            o.put("conversationId", t.conversation);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** POST /tasks（action 缺省 = 新任务）。 */
    public synchronized JSONObject enqueue(JSONObject body, long now) {
        String id = body.optString("id");
        Task existing = validId(id) ? find(id) : null;
        if (existing != null) return receipt(existing);   // 幂等：同一 id 只执行一次
        if (!validId(id)) throw new IllegalArgumentException("invalid_task_id");
        String text = body.optString("text").trim();
        if (text.length() == 0 || text.length() > MAX_TEXT) throw new IllegalArgumentException("invalid_task_text");
        String expected = body.optString("conversationId");
        if (expected.length() > 0 && !CONVERSATION.equals(expected)) throw new IllegalStateException("conversation_changed");
        int active = 0;
        for (Task t : mTasks) if (ACTIVE.contains(t.state)) active++;
        if (active >= MAX_ACTIVE) throw new IllegalStateException("queue_full");
        Task t = new Task();
        t.id = id;
        t.text = text;
        t.createdAt = now;
        mTasks.add(t);
        trim();
        save();
        pump();
        return receipt(t);
    }

    private void trim() {
        int terminal = 0;
        for (Task t : mTasks) if (TERMINAL.contains(t.state)) terminal++;
        for (int i = 0; i < mTasks.size() && terminal > KEEP_TERMINAL; ) {
            if (TERMINAL.contains(mTasks.get(i).state)) {
                mTasks.remove(i);
                terminal--;
            } else {
                i++;
            }
        }
    }

    /** 把还没交出去的排队任务交给执行端（先记 dispatching 再交，交上了记 runId）。 */
    private void pump() {
        for (Task t : mTasks) {
            if (!"queued".equals(t.state) || t.runId > 0) continue;
            t.state = "dispatching";
            save();
            long run;
            try {
                run = mExec.submit(t.text, taintedText(t.text));
            } catch (Throwable e) {
                run = -1;
            }
            if (run > 0) {
                t.runId = run;
                t.state = "queued";
                save();
            } else {
                t.state = "queued";   // 执行队列满 / 服务没起来：下次再交（没交出去，不会重复执行）
                save();
                return;
            }
        }
    }

    /** 从执行记录刷新状态，并尝试交出排队的。返回是否有变化。 */
    public synchronized boolean sync() {
        boolean changed = false;
        for (Task t : mTasks) {
            if (t.runId <= 0 || TERMINAL.contains(t.state)) continue;
            TaskExecutor.RunView v;
            try {
                v = mExec.view(t.runId);
            } catch (Throwable e) {
                continue;
            }
            String state, error = t.error, result = t.result;
            boolean confirming = false;
            if (v == null) {
                state = "uncertain";
                error = "执行记录已不在，请核实";
            } else if ("PENDING".equals(v.state) || "RETRY_WAIT".equals(v.state)) {
                state = "queued";
            } else if ("RUNNING".equals(v.state) || "WAITING_CONFIRM".equals(v.state)) {
                state = "running";
                confirming = "WAITING_CONFIRM".equals(v.state);
            } else if ("DONE".equals(v.state)) {
                state = "completed";
                result = v.result;
                error = "";
            } else if ("FAILED".equals(v.state)) {
                state = "failed";
                error = v.error.length() > 0 ? v.error : "执行失败";
            } else if ("CANCELLED".equals(v.state)) {
                state = "cancelled";
            } else {
                continue;
            }
            String step = v == null ? "" : v.step;
            if (!state.equals(t.state) || !error.equals(t.error) || !result.equals(t.result) || confirming != t.confirming
                    || !step.equals(t.step)) {
                t.state = state;
                t.error = error;
                t.result = result;
                t.confirming = confirming;
                t.step = step;
                changed = true;
            }
        }
        if (changed) save();
        int before = mTasks.size();
        pump();
        return changed || before != mTasks.size();
    }

    /** GET /tasks。 */
    public synchronized JSONObject list() {
        JSONArray a = new JSONArray();
        for (Task t : mTasks) a.put(t.toJson(true));
        JSONObject o = new JSONObject();
        try {
            o.put("items", a);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** 快照里的 queuedTasks：活动的 + 最近 20 条终态，正文不带。 */
    public synchronized JSONArray status() {
        List<Task> terminal = new ArrayList<Task>();
        for (Task t : mTasks) if (!ACTIVE.contains(t.state)) terminal.add(t);
        Set<String> recent = new HashSet<String>();
        for (int i = Math.max(0, terminal.size() - 20); i < terminal.size(); i++) recent.add(terminal.get(i).id);
        JSONArray a = new JSONArray();
        for (Task t : mTasks) if (ACTIVE.contains(t.state) || recent.contains(t.id)) a.put(t.toJson(false));
        return a;
    }

    /** 只取消排队中的（上游口径）；执行中的用 stop。 */
    public synchronized JSONObject cancel(String id) {
        Task t = find(id);
        if (t == null) throw new IllegalStateException("task_not_found");
        if (!"queued".equals(t.state)) throw new IllegalStateException("task_not_queued");
        if (t.runId > 0) mExec.cancel(t.runId);
        t.state = "cancelled";
        save();
        return list();
    }

    public synchronized JSONObject acknowledge(String id) {
        Task t = find(id);
        if (t == null) throw new IllegalStateException("task_not_found");
        if (!"uncertain".equals(t.state)) throw new IllegalStateException("task_not_uncertain");
        t.state = "completed";
        save();
        return list();
    }

    public synchronized JSONObject retry(String id, long now) {
        Task t = find(id);
        if (t == null) throw new IllegalStateException("task_not_found");
        if (!"failed".equals(t.state) && !"cancelled".equals(t.state)) throw new IllegalStateException("task_cannot_retry");
        JSONObject b = new JSONObject();
        try {
            b.put("id", UUID.randomUUID().toString());
            b.put("text", t.text);
        } catch (Exception ignored) {
        }
        enqueue(b, now);
        return list();
    }

    /** 先把排队的全记成已取消（不让新的起来），再停正在跑的。 */
    public synchronized JSONObject stopAll() {
        for (Task t : mTasks) {
            if (!ACTIVE.contains(t.state)) continue;
            if (t.runId > 0) mExec.cancel(t.runId);
            t.state = "cancelled";
        }
        save();
        JSONObject o = new JSONObject();
        try {
            o.put("status", "accepted");
        } catch (Exception ignored) {
        }
        return o;
    }

    /** 按 runId 停（/command stop）。 */
    public synchronized boolean stopRun(long runId) {
        for (Task t : mTasks) {
            if (t.runId == runId && ACTIVE.contains(t.state)) {
                mExec.cancel(runId);
                return true;
            }
        }
        return false;
    }

    /** 快照用：正在执行的那条；没有就最近一条有执行记录的。 */
    public synchronized Task current() {
        Task last = null;
        for (Task t : mTasks) {
            if ("running".equals(t.state)) return copy(t);
            if (t.runId > 0 || TERMINAL.contains(t.state) || "uncertain".equals(t.state)) last = t;
        }
        return last == null ? null : copy(last);
    }

    public synchronized boolean anyActive() {
        for (Task t : mTasks) if (ACTIVE.contains(t.state)) return true;
        return false;
    }

    /** 最近 n 条（聊天记录用）。 */
    public synchronized List<Task> recent(int n) {
        List<Task> out = new ArrayList<Task>();
        for (int i = Math.max(0, mTasks.size() - n); i < mTasks.size(); i++) out.add(copy(mTasks.get(i)));
        return out;
    }

    private static Task copy(Task s) {
        Task t = Task.restore(s.persist());
        t.confirming = s.confirming;
        return t;
    }
}
