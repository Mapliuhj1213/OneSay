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
 * app/src/main/java/com/wangzan101/mika/remote/RemoteBridge.kt 的路由表（Kotlin → Java 7）。
 * 兼容的接口：/pair、/pair/&lt;id&gt;、/state、/events、/tasks、/command（stop / new_chat / select_chat；answer、resume 明确拒绝）、
 * /messages、/notification/open、/device/name、/chats、/chat/&lt;id&gt;、/video/status、/video/start、/video、/screen/input、
 * /virtual/status（恒为未开启）。
 * 改动：远程改模型 / 设置 / 记忆 / 技能 / 定时计划 / 路线 / 文件 / 附件 / 浏览器 / 诊断这些管理接口<b>不开放</b>（回 501，
 * 缩小远程控制面）；新任务交给 OneSay 任务队列；/command 的去重只在内存（新任务的去重在 PgTasks 里持久化）。
 */
package com.onesay.assistant.plug.phongent.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** [phongent] 路由：一个已读好的请求 → 应答（或流）。纯 Java，JVM 可测。 */
public final class PgApi {

    /** 不开放的上游管理接口（回 501）。 */
    static final Set<String> UNSUPPORTED = new HashSet<String>(Arrays.asList(
            "/models/settings", "/settings", "/knowledge", "/plans", "/routes", "/browser", "/files", "/attachments",
            "/workspace", "/usage", "/diagnostics", "/virtual/video", "/virtual/input"));

    private final PgAuth mAuth;
    private final PgTasks mTasks;
    private final PgMessages mMessages;
    private final HostInfo mHost;
    private final PgState.Signal mSignal;
    private final PgScreen mScreen;
    private final PlugConfig mConfig;
    private final Map<String, String> mCommands = new LinkedHashMap<String, String>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) {
            return size() > 500;
        }
    };
    volatile boolean closed;

    public PgApi(PgAuth auth, PgTasks tasks, PgMessages messages, HostInfo host, PgState.Signal signal, PgScreen screen, PlugConfig config) {
        mAuth = auth;
        mTasks = tasks;
        mMessages = messages;
        mHost = host;
        mSignal = signal;
        mScreen = screen;
        mConfig = config;
    }

    public JSONObject snapshot() {
        return PgState.snapshot(mHost, mTasks, mMessages, System.currentTimeMillis());
    }

    static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        } catch (Exception ignored) {
        }
        return o;
    }

    static String msg(Throwable e, String def) {
        String m = e.getMessage();
        return m == null || m.length() == 0 ? def : m.length() > 160 ? m.substring(0, 160) : m;
    }

    /** 处理一个请求，把应答写进 out。 */
    public void handle(PgHttp.Request rq, OutputStream out) throws IOException {
        String m = rq.method, p = rq.path;
        if ("POST".equals(m) && "/pair".equals(p)) {
            PgAuth.Result r;
            try {
                r = mAuth.request(rq.json());
            } catch (IOException e) {
                r = null;
            }
            if (r == null) PgHttp.writeJson(out, 400, PgHttp.err("invalid_request"));
            else PgHttp.writeJson(out, r.code, r.body);
            return;
        }
        if ("GET".equals(m) && p.startsWith("/pair/")) {
            PgHttp.writeJson(out, 200, mAuth.poll(p.substring(6)));
            return;
        }
        String peer = mAuth.authorize(rq.header("authorization"));
        if (peer == null) {
            PgHttp.writeJson(out, 401, PgHttp.err("unauthorized"));
            return;
        }
        if (UNSUPPORTED.contains(p)) {
            PgHttp.writeJson(out, 501, PgHttp.err("unsupported_on_bixbyui"));
            return;
        }
        if ("GET".equals(m) && "/state".equals(p)) {
            mTasks.sync();
            PgHttp.writeJson(out, 200, snapshot());
        } else if ("GET".equals(m) && "/events".equals(p)) {
            events(rq, out);
        } else if ("/tasks".equals(p)) {
            tasks(rq, out);
        } else if ("POST".equals(m) && "/command".equals(p)) {
            command(rq, out);
        } else if ("GET".equals(m) && "/messages".equals(p) && rq.query.containsKey("after")) {
            try {
                long after = Long.parseLong(rq.query.get("after"));
                long wait = rq.query.containsKey("wait") ? Long.parseLong(rq.query.get("wait")) : 20;
                PgHttp.writeJson(out, 200, mMessages.changes(after, wait));
            } catch (NumberFormatException e) {
                PgHttp.writeJson(out, 400, PgHttp.err("invalid_query"));
            } catch (InterruptedException e) {
                throw new IOException("interrupted");
            }
        } else if ("POST".equals(m) && "/notification/open".equals(p)) {
            try {
                mMessages.open(rq.json().getString("id"));
                PgHttp.writeJson(out, 200, obj("status", "dispatched"));
            } catch (Exception e) {
                PgHttp.writeJson(out, 409, PgHttp.err("notification_expired_or_blocked"));
            }
        } else if ("POST".equals(m) && "/device/name".equals(p)) {
            try {
                String name = mHost.rename(rq.json().getString("name"));
                mSignal.signal();
                PgHttp.writeJson(out, 200, obj("name", name));
            } catch (Exception e) {
                PgHttp.writeJson(out, 422, PgHttp.err(msg(e, "invalid_device_name")));
            }
        } else if ("GET".equals(m) && "/chats".equals(p)) {
            PgHttp.writeJson(out, 200, chats());
        } else if ("GET".equals(m) && p.startsWith("/chat/")) {
            String id = p.substring(6);
            if (!PgTasks.CONVERSATION.equals(id)) PgHttp.writeJson(out, 404, PgHttp.err("conversation_not_found"));
            else PgHttp.writeJson(out, 200, chat());
        } else if ("GET".equals(m) && "/virtual/status".equals(p)) {
            PgHttp.writeJson(out, 200, obj("active", false, "width", 0, "height", 0,
                    "error", "OneSay 执行端没有虚拟屏，请用「实时画面」", "canControl", false, "controlled", false,
                    "controlledByOther", false, "runId", "", "conversationId", PgTasks.CONVERSATION, "displayId", -1));
        } else if (p.startsWith("/video") || "/screen/input".equals(p)) {
            video(rq, out, peer);
        } else {
            PgHttp.writeJson(out, 404, PgHttp.err("not_found"));
        }
    }

    private void tasks(PgHttp.Request rq, OutputStream out) throws IOException {
        if ("GET".equals(rq.method)) {
            mTasks.sync();
            PgHttp.writeJson(out, 200, mTasks.list());
            return;
        }
        try {
            JSONObject c = rq.json();
            String a = c.optString("action");
            JSONObject r;
            long now = System.currentTimeMillis();
            if ("stop_all".equals(a)) r = mTasks.stopAll();
            else if ("cancel".equals(a)) r = mTasks.cancel(c.getString("id"));
            else if ("acknowledge".equals(a)) r = mTasks.acknowledge(c.getString("id"));
            else if ("retry".equals(a)) r = mTasks.retry(c.getString("id"), now);
            else r = mTasks.enqueue(c, now);
            mSignal.signal();
            PgHttp.writeJson(out, 200, r);
        } catch (Exception e) {
            PgHttp.writeJson(out, 409, PgHttp.err(msg(e, "queue_failed")));
        }
    }

    private void command(PgHttp.Request rq, OutputStream out) throws IOException {
        JSONObject c;
        String id, action;
        try {
            c = rq.json();
            id = c.getString("id");
            action = c.getString("action");
        } catch (Exception e) {
            PgHttp.writeJson(out, 400, PgHttp.err("invalid_command"));
            return;
        }
        if (!PgTasks.validId(id)) {
            PgHttp.writeJson(out, 400, PgHttp.err("invalid_command"));
            return;
        }
        synchronized (mCommands) {
            String prior = mCommands.get(id);
            if (prior != null) {
                PgHttp.writeJson(out, 200, obj("id", id, "status", prior, "conversationId", PgTasks.CONVERSATION));
                return;
            }
            mCommands.put(id, "uncertain");
        }
        String error = null;
        try {
            if ("task".equals(action)) {
                mTasks.enqueue(c, System.currentTimeMillis());
            } else if ("stop".equals(action)) {
                String run = c.optString("runId");
                long n = run.startsWith("pg-") ? Long.parseLong(run.substring(3)) : -1;
                if (n <= 0 || !mTasks.stopRun(n)) throw new IllegalStateException("stale_run");
            } else if ("answer".equals(action)) {
                throw new IllegalStateException("not_waiting_for_input");
            } else if ("resume".equals(action)) {
                throw new IllegalStateException("not_paused");
            } else if ("new_chat".equals(action)) {
                if (mTasks.anyActive() || mHost.localBusy()) throw new IllegalStateException("agent_busy");
            } else if ("select_chat".equals(action)) {
                if (!PgTasks.CONVERSATION.equals(c.optString("conversationId"))) throw new IllegalStateException("conversation_not_found");
            } else if ("delete_chat".equals(action)) {
                throw new IllegalStateException("unsupported_on_bixbyui");
            } else {
                throw new IllegalArgumentException("unknown_action");
            }
        } catch (Exception e) {
            error = msg(e, "dispatch_failed");
        }
        String status = error == null ? "accepted" : "failed";
        synchronized (mCommands) {
            mCommands.put(id, status);
        }
        mSignal.signal();
        PgHttp.writeJson(out, error == null ? 202 : 409, obj("id", id, "status", status,
                "error", error == null ? JSONObject.NULL : error, "conversationId", PgTasks.CONVERSATION));
    }

    private JSONObject chats() {
        mTasks.sync();
        List<PgTasks.Task> recent = mTasks.recent(1);
        PgTasks.Task last = recent.isEmpty() ? null : recent.get(0);
        boolean busy = mTasks.anyActive();
        PgTasks.Task cur = mTasks.current();
        JSONObject chat = obj("id", PgTasks.CONVERSATION, "title", "OneSay",
                "preview", last == null ? "" : PgState.clip(last.result.length() > 0 ? last.result : last.text, 160),
                "updatedAt", last == null ? 0.0 : last.createdAt / 1000.0, "busy", busy,
                "needsAttention", cur != null && cur.confirming);
        JSONArray a = new JSONArray();
        a.put(chat);
        return obj("chats", a);
    }

    private JSONObject chat() {
        mTasks.sync();
        JSONArray msgs = new JSONArray();
        for (PgTasks.Task t : mTasks.recent(50)) {
            double time = t.createdAt / 1000.0;
            msgs.put(obj("id", t.id + "-user", "role", "user", "text", t.text, "status", "已接收", "time", time));
            String text, status;
            if ("completed".equals(t.state)) {
                text = t.result.length() > 0 ? t.result : "任务完成";
                status = "任务完成";
            } else if ("failed".equals(t.state)) {
                text = t.error;
                status = "执行失败";
            } else if ("cancelled".equals(t.state)) {
                text = "任务已停止";
                status = "已停止";
            } else if ("uncertain".equals(t.state)) {
                text = t.error;
                status = "待核实";
            } else {
                text = t.confirming ? "请在安卓端确认操作" : t.step.length() > 0 ? "正在：" + t.step : "执行中";
                status = t.confirming ? "需要确认" : "正在执行";
            }
            msgs.put(obj("id", (t.runId > 0 ? "pg-" + t.runId : t.id) + "-agent", "role", "agent", "text", text, "status", status, "time", time));
        }
        return obj("messages", msgs);
    }

    private void video(PgHttp.Request rq, OutputStream out, String peer) throws IOException {
        String m = rq.method, p = rq.path;
        PgScreen mScreen = this.mScreen != null && mConfig != null && mConfig.screenSharing() ? this.mScreen : null;
        if (mScreen == null) {
            if ("GET".equals(m) && "/video/status".equals(p)) {
                PgHttp.writeJson(out, 200, obj("active", false, "width", 0, "height", 0, "error", "本机没有打开「允许查看屏幕」"));
            } else {
                PgHttp.writeJson(out, 409, PgHttp.err("screen_sharing_disabled"));
            }
            return;
        }
        if ("GET".equals(m) && "/video/status".equals(p)) {
            PgHttp.writeJson(out, 200, mScreen.status());
        } else if ("POST".equals(m) && "/video/start".equals(p)) {
            mScreen.start(mAuth.peerName(peer));
            PgHttp.writeJson(out, 200, mScreen.status());
        } else if ("GET".equals(m) && "/video".equals(p)) {
            if (!mScreen.status().optBoolean("active")) {
                PgHttp.writeJson(out, 409, PgHttp.err("video_not_ready"));
                return;
            }
            PgHttp.writeStreamHead(out, "application/octet-stream");
            if (!mScreen.serve(new DataOutputStream(out))) throw new IOException("video_ended");
        } else if ("POST".equals(m) && "/screen/input".equals(p)) {
            try {
                if (mTasks.anyActive() || mHost.localBusy()) throw new IllegalStateException("agent_busy");
                mScreen.input(rq.json());
                PgHttp.writeJson(out, 200, obj("status", "accepted"));
            } catch (Exception e) {
                PgHttp.writeJson(out, 409, PgHttp.err(msg(e, "input_rejected")));
            }
        } else {
            PgHttp.writeJson(out, 404, PgHttp.err("not_found"));
        }
    }

    /** GET /events?heartbeat=N：首帧快照，之后 state / messages 差分，空闲时心跳。 */
    private void events(PgHttp.Request rq, OutputStream out) throws IOException {
        long hb = 30;
        try {
            if (rq.query.containsKey("heartbeat")) hb = Long.parseLong(rq.query.get("heartbeat"));
        } catch (NumberFormatException ignored) {
        }
        hb = Math.max(15, Math.min(120, hb));
        PgHttp.writeStreamHead(out, "text/event-stream");
        PgState.EventDiff diff = new PgState.EventDiff();
        long rev = mSignal.current();
        mTasks.sync();
        write(out, diff.first(snapshot()));
        long lastSent = System.currentTimeMillis();
        while (!closed) {
            try {
                rev = mSignal.await(rev, 1000L);
            } catch (InterruptedException e) {
                return;
            }
            JSONObject[] frames = diff.next(snapshot());
            for (JSONObject f : frames) write(out, f);
            long now = System.currentTimeMillis();
            if (frames.length > 0) lastSent = now;
            else if (now - lastSent >= hb * 1000L) {
                write(out, diff.heartbeat());
                lastSent = now;
            }
        }
    }

    private static void write(OutputStream out, JSONObject frame) throws IOException {
        out.write(("data: " + frame.toString() + "\n\n").getBytes("UTF-8"));
        out.flush();
    }
}
