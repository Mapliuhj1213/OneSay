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
 * app/src/main/java/com/wangzan101/mika/remote/RemoteBridge.kt 的 snapshot() 与 /events 差分逻辑、
 * BridgeEvents.kt（Kotlin → Java 7）。字段名、phase 取值（idle / running / waiting / confirmation / completed / failed /
 * cancelled）、事件种类（snapshot / state / messages / heartbeat）与逐连接递增的 sequence 与上游一致（iOS 端
 * AgentEventReducer 按 sequence 严格校验）。改动：状态来源换成 OneSay 任务队列；不导出 knowledgeVersion /
 * exchanges（OneSay 不支持远程改记忆 / 原生聊天），会话固定一个。
 */
package com.onesay.assistant.plug.phongent.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** [phongent] 状态快照与事件流差分。纯 Java。 */
public final class PgState {

    private PgState() {}

    /** 唤醒事件流（上游 BridgeEvents）。 */
    public static final class Signal {
        private final Object lock = new Object();
        private long revision;

        public void signal() {
            synchronized (lock) {
                revision++;
                lock.notifyAll();
            }
        }

        public long current() {
            synchronized (lock) {
                return revision;
            }
        }

        public long await(long after, long ms) throws InterruptedException {
            synchronized (lock) {
                long deadline = System.currentTimeMillis() + ms;
                while (revision == after) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) break;
                    lock.wait(left);
                }
                return revision;
            }
        }
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }

    public static JSONObject snapshot(HostInfo host, PgTasks tasks, PgMessages messages, long nowMs) {
        PgTasks.Task t = tasks.current();
        String phase, detail;
        if (t == null) {
            phase = "idle";
            detail = "等待你的下一项任务";
        } else if ("running".equals(t.state) && t.confirming) {
            phase = "confirmation";
            detail = "请在安卓端确认操作";
        } else if ("running".equals(t.state)) {
            phase = "running";
            detail = t.step.length() > 0 ? "正在：" + t.step : "正在执行：" + t.text;
        } else if ("queued".equals(t.state) || "dispatching".equals(t.state)) {
            phase = "running";
            detail = "已排队，等本机空闲后执行";
        } else if ("completed".equals(t.state)) {
            phase = "completed";
            detail = t.result.length() > 0 ? t.result : "任务完成";
        } else if ("failed".equals(t.state)) {
            phase = "failed";
            detail = t.error.length() > 0 ? t.error : "执行失败";
        } else if ("cancelled".equals(t.state)) {
            phase = "cancelled";
            detail = "任务已停止";
        } else {
            phase = "waiting";
            detail = t.error.length() > 0 ? t.error : "执行结果待核实";
        }
        JSONObject o = new JSONObject();
        try {
            o.put("phase", phase);
            o.put("detail", clip(detail, 8000));
            o.put("runId", t == null || t.runId <= 0 ? "" : "pg-" + t.runId);
            o.put("goal", t == null ? "" : clip(t.text, 8000));
            o.put("commandId", t == null ? "" : t.id);
            o.put("conversationId", t == null ? "" : PgTasks.CONVERSATION);
            o.put("currentConversationId", PgTasks.CONVERSATION);
            o.put("taskQueueVersion", 1);
            o.put("queuedTasks", tasks.status());
            o.put("chatRoutingVersion", 3);
            o.put("busy", tasks.anyActive() || host.localBusy());
            o.put("device", host.deviceName());
            o.put("updatedAt", nowMs / 1000.0);
            o.put("messages", messages.snapshot());
            o.put("messageWaitConfig", true);
            o.put("eventStream", true);
            o.put("notificationAccess", host.notificationAccess());
            o.put("notificationListenerConnected", host.listenerConnected());
            o.put("executor", "bixbyui");
        } catch (Exception ignored) {
        }
        return o;
    }

    /** 一条事件流连接的差分状态（每个连接一个，sequence 从 1 起）。 */
    public static final class EventDiff {
        private long sequence;
        private String previousState;
        private Map<String, String> previousMessages;

        static String stateKey(JSONObject snap) {
            try {
                JSONObject c = new JSONObject(snap.toString());
                c.remove("messages");
                c.remove("updatedAt");
                return c.toString();
            } catch (Exception e) {
                return "";
            }
        }

        static Map<String, String> records(JSONObject snap) {
            Map<String, String> m = new LinkedHashMap<String, String>();
            JSONArray a = snap.optJSONArray("messages");
            if (a == null) return m;
            for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.optJSONObject(i);
                if (x != null) m.put(x.optString("id"), x.toString());
            }
            return m;
        }

        private JSONObject event(String kind, JSONObject data) {
            try {
                data.put("kind", kind);
                data.put("sequence", ++sequence);
            } catch (Exception ignored) {
            }
            return data;
        }

        /** 连接建立时的首帧。 */
        public JSONObject first(JSONObject snap) {
            previousState = stateKey(snap);
            previousMessages = records(snap);
            JSONObject d = new JSONObject();
            try {
                d.put("snapshot", snap);
            } catch (Exception ignored) {
            }
            return event("snapshot", d);
        }

        /** 之后每次醒来：返回 0–2 帧（state / messages）；都没变返回空数组，heartbeat 由调用方按时补。 */
        public JSONObject[] next(JSONObject snap) {
            JSONObject stateEv = null, msgEv = null;
            String key = stateKey(snap);
            if (!key.equals(previousState)) {
                try {
                    JSONObject s = new JSONObject(snap.toString());
                    s.put("messages", new JSONArray());
                    JSONObject d = new JSONObject();
                    d.put("snapshot", s);
                    stateEv = event("state", d);
                } catch (Exception ignored) {
                }
                previousState = key;
            }
            Map<String, String> now = records(snap);
            JSONArray ups = new JSONArray(), removed = new JSONArray();
            for (Map.Entry<String, String> e : now.entrySet()) {
                if (!e.getValue().equals(previousMessages.get(e.getKey()))) {
                    try {
                        ups.put(new JSONObject(e.getValue()));
                    } catch (Exception ignored) {
                    }
                }
            }
            for (Iterator<String> it = previousMessages.keySet().iterator(); it.hasNext(); ) {
                String k = it.next();
                if (!now.containsKey(k)) removed.put(k);
            }
            if (ups.length() > 0 || removed.length() > 0) {
                JSONObject d = new JSONObject();
                try {
                    d.put("upserts", ups);
                    d.put("removed", removed);
                } catch (Exception ignored) {
                }
                msgEv = event("messages", d);
                previousMessages = now;
            }
            if (stateEv != null && msgEv != null) return new JSONObject[]{stateEv, msgEv};
            if (stateEv != null) return new JSONObject[]{stateEv};
            if (msgEv != null) return new JSONObject[]{msgEv};
            return new JSONObject[0];
        }

        public JSONObject heartbeat() {
            return event("heartbeat", new JSONObject());
        }
    }
}
